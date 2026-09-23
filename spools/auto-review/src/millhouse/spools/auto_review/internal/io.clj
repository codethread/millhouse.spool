(ns millhouse.spools.auto-review.internal.io
  "Own the review coordinator's GitLab, Git, and workspace-hook boundary.

  Commands use literal argv, bounded output, deadlines, and non-interactive Git
  settings. This namespace admits only a successful pipeline for the observed
  head. Consumer-owned setup and teardown hooks acquire and release review
  workspaces; the coordinator validates their exact Git revision before dispatch."
  (:require [clojure.data.json :as json]
            [clojure.java.io :as io]
            [clojure.string :as str])
  (:import [java.io ByteArrayOutputStream InputStream]
           [java.util.concurrent TimeUnit]))

(defn- read-output [^InputStream stream cap process]
  (with-open [stream stream
              out (ByteArrayOutputStream.)]
    (let [buffer (byte-array 8192)]
      (loop [total 0]
        (let [n (.read stream buffer)]
          (if (neg? n)
            (.toString out "UTF-8")
            (if (> (+ total n) cap)
              (do (.destroyForcibly ^Process process)
                  (throw (ex-info "Command output exceeded its byte limit" {:cap cap})))
              (do (.write out buffer 0 n) (recur (+ total n))))))))))

(defn command!
  "Run a literal argv vector with bounded output and a deadline."
  [cwd argv cap timeout-seconds]
  (let [builder (doto (ProcessBuilder. ^java.util.List argv)
                  (.directory (io/file cwd)))
        _ (doto (.environment builder)
            (.put "GIT_TERMINAL_PROMPT" "0")
            (.put "GIT_CONFIG_NOSYSTEM" "1")
            (.put "GIT_CONFIG_GLOBAL" "/dev/null")
            (.put "GLAB_CHECK_UPDATE" "false"))
        process (.start builder)
        stdout (future (read-output (.getInputStream process) cap process))
        stderr (future (read-output (.getErrorStream process) 65536 process))]
    (try
      (when-not (.waitFor process timeout-seconds TimeUnit/SECONDS)
        (throw (ex-info "Command timed out" {:command (first argv)})))
      (let [out @stdout err @stderr]
        (when-not (zero? (.exitValue process))
          (throw (ex-info "Command failed" {:command (first argv) :stderr err})))
        out)
      (finally
        ;; Exact owned process handles only; no pattern-based process killing.
        (when (.isAlive process)
          (doseq [child (.toList (.descendants process))] (.destroyForcibly child))
          (.destroyForcibly process))))))

(defn- glab [config & argv]
  (json/read-str
   (command! (:repo-dir config) (into [(:glab-bin config)] argv) (* 8 1024 1024) 60)
   :key-fn keyword))

(defn- paged-api
  "Read every array page explicitly so successful completion proves absence."
  [config endpoint]
  (loop [page 1 result []]
    (let [separator (if (str/includes? endpoint "?") "&" "?")
          rows (glab config "api" (str endpoint separator "per_page=100&page=" page))]
      (when-not (vector? rows)
        (throw (ex-info "GitLab API returned a non-array page"
                        {:endpoint endpoint :page page})))
      (if (= 100 (count rows))
        (recur (inc page) (into result rows))
        (into result rows)))))

(defn- login-shell []
  (let [value (System/getenv "SHELL")]
    (if (str/blank? value) "/bin/bash" value)))

(defn- review-env [config review mr]
  {"MILLSTRAND_REVIEW_ID" (:id review)
   "MILLSTRAND_REVIEW_REPO" (:repo-dir config)
   "MILLSTRAND_REVIEW_MR_IID" (str (:iid mr))
   "MILLSTRAND_REVIEW_HEAD" (:sha mr)
   "MILLSTRAND_REVIEW_BASE" (get-in mr [:diff_refs :base_sha])})

(defn- temp-file [review phase suffix]
  (str (java.nio.file.Files/createTempFile
        (str "millstrand-review-" (:id review) "-" phase "-") suffix
        (make-array java.nio.file.attribute.FileAttribute 0))))

(defn- hook!
  "Run trusted inline Bash from the repository with bounded lifetime and file-backed output."
  [config review mr phase script timeout-seconds extra-env]
  (let [shell (login-shell)
        log (temp-file review phase ".log")
        builder (doto (ProcessBuilder.
                       ^java.util.List
                       [shell "-lic"
                        (str "exec /bin/bash -e -c 'cd -- \"$MILLSTRAND_REVIEW_REPO\"; "
                             "eval \"$MILLSTRAND_REVIEW_HOOK\"'")])
                  (.directory (io/file (:repo-dir config)))
                  (.redirectErrorStream true)
                  (.redirectOutput (io/file log)))
        _ (doto (.environment builder)
            (.putAll (review-env config review mr))
            (.putAll extra-env)
            (.put "MILLSTRAND_REVIEW_HOOK" script)
            (.put "GIT_TERMINAL_PROMPT" "0"))
        process (.start builder)
        succeeded? (atom false)]
    (try
      (.close (.getOutputStream process))
      (when-not (.waitFor process timeout-seconds TimeUnit/SECONDS)
        (throw (ex-info (str "Review " phase " timed out") {:phase phase :log log :shell shell})))
      (when-not (zero? (.exitValue process))
        (throw (ex-info (str "Review " phase " failed")
                        {:phase phase :exit-code (.exitValue process) :log log :shell shell})))
      (reset! succeeded? true)
      {:shell shell :log log}
      (finally
        (when (.isAlive process)
          (doseq [child (.toList (.descendants process))] (.destroyForcibly child))
          (.destroyForcibly process))
        (when-not @succeeded?
          (io/delete-file log true))))))

(defn setup!
  "Acquire and prepare a review workspace through the configured setup hook.

  The hook receives immutable review facts in MILLSTRAND_REVIEW_* variables and
  writes a JSON object to MILLSTRAND_REVIEW_RESULT. The object must contain an
  absolute worktree_path; extra fields are retained for teardown. Ordinary hook
  output is redirected to a durable log rather than mixed with the contract."
  [config review mr]
  (let [result-file (temp-file review "setup-result" ".json")]
    (try
      (let [execution (hook! config review mr "setup" (:setup config)
                             (:setup-timeout-seconds config)
                             {"MILLSTRAND_REVIEW_RESULT" result-file})
            context (try
                      (json/read-str (slurp result-file) :key-fn keyword)
                      (catch Exception error
                        (throw (ex-info "Review setup returned invalid JSON"
                                        {:result result-file :log (:log execution)} error))))
            path (:worktree_path context)]
        (when-not (and (map? context)
                       (or (nil? (:kind context)) (= "ready" (:kind context)))
                       (string? path) (.isAbsolute (io/file path)))
          (throw (ex-info "Review setup must return ready JSON with an absolute worktree_path"
                          {:result result-file :log (:log execution)})))
        (let [worktree (.getCanonicalPath (io/file path))
              repo (.getCanonicalPath (io/file (:repo-dir config)))]
          (when (or (= worktree repo)
                    (str/starts-with? worktree (str repo java.io.File/separator)))
            (throw (ex-info "Review setup worktree must be outside the source repository"
                            {:worktree worktree :repo (:repo-dir config) :log (:log execution)})))
          (merge execution {:worktree worktree :context (assoc context :worktree_path worktree)})))
      (catch Exception error
        (when-let [log (:log (ex-data error))]
          (io/delete-file log true))
        (throw error))
      (finally
        (io/delete-file result-file true)))))

(defn teardown!
  "Release a review workspace through the configured idempotent teardown hook.

  Setup's complete JSON result is supplied in MILLSTRAND_REVIEW_WORKSPACE, while
  MILLSTRAND_REVIEW_WORKTREE exposes its canonical path directly."
  [config review mr workspace]
  (let [context-file (temp-file review "workspace" ".json")]
    (try
      (spit context-file (json/write-str (or (:context workspace) {})))
      (hook! config review mr "teardown" (:teardown config)
             (:teardown-timeout-seconds config)
             {"MILLSTRAND_REVIEW_WORKTREE" (or (:worktree workspace) "")
              "MILLSTRAND_REVIEW_WORKSPACE" context-file})
      (finally
        (io/delete-file context-file true)))))

(defn open-mrs
  "Read all open MRs, paginating explicitly; drafts remain visible to policy."
  [config]
  (loop [page 1 result []]
    (let [rows (glab config "mr" "list" "--output" "json"
                     "--per-page" "100" "--page" (str page))]
      (when-not (vector? rows)
        (throw (ex-info "Expected a GitLab MR array" {})))
      (if (= 100 (count rows))
        (recur (inc page) (into result rows))
        (into result rows)))))

(defn authenticated-user-id
  "Resolve the stable user ID from the current glab session."
  [config]
  (let [id (:id (glab config "api" "user"))]
    (when-not (pos-int? id)
      (throw (ex-info "GitLab returned an invalid authenticated user" {:id id})))
    id))

(defn requested-review?
  "Return whether GitLab explicitly requests a review from user-id."
  [user-id mr]
  (and (pos-int? user-id)
       (boolean (some #(= user-id (:id %)) (:reviewers mr)))))

(defn mr-state
  "Read and validate the current GitLab lifecycle state for one MR IID."
  [config iid]
  (let [state (:state (glab config "mr" "view" (str iid) "--output" "json"))]
    (when-not (contains? #{"opened" "closed" "merged"} state)
      (throw (ex-info "GitLab returned an unknown MR state" {:iid iid :state state})))
    state))

(defn pipeline-success?
  "Trust the current head pipeline's aggregate result, not individual jobs.

  Missing or stale evidence cannot admit a new revision. Never fall back to
  the deprecated pipeline field or a successful pipeline from its history."
  [mr]
  (let [pipeline (:head_pipeline mr)]
    (and (pos-int? (:id pipeline))
         (= "success" (:status pipeline))
         (string? (:sha mr))
         (= (:sha mr) (:sha pipeline)))))

(defn ^:dynamic *observe-revision* [_] nil)

(defn pipeline-facts [detail]
  (let [pipeline (:head_pipeline detail)
        status (:status pipeline)]
    {:ci "observed"
     :pipeline-id (when (pos-int? (:id pipeline)) (:id pipeline))
     :pipeline-status (if (contains? #{"created" "waiting_for_resource" "preparing" "pending"
                                       "running" "success" "failed" "canceled" "skipped"
                                       "manual" "scheduled"} status) status "unknown")
     :pipeline-sha-matches (and (string? (:sha detail)) (= (:sha detail) (:sha pipeline)))
     :pipeline-passing (pipeline-success? detail)}))

(defn revision
  "Read an unchanged MR's exact diff refs; return nil until its pipeline passes."
  [config mr]
  (let [detail (glab config "mr" "view" (str (:iid mr)) "--output" "json")
        {:keys [base_sha start_sha head_sha]} (:diff_refs detail)]
    (*observe-revision* (pipeline-facts detail))
    (when-not (and (= "opened" (:state detail)) (not (:draft detail))
                   (= (:sha mr) (:sha detail) head_sha)
                   (every? #(and (string? %) (re-matches #"[0-9a-f]{40,64}" %))
                           [base_sha start_sha head_sha]))
      (throw (ex-info "MR moved, became a draft, or lacks prepared diff refs" {:iid (:iid mr)})))
    (when (pipeline-success? detail) detail)))

(defn publication-context
  "Read the current MR, latest diff anchors, and complete latest diff rows."
  [config mr]
  (let [iid (:iid mr)
        project (:project_id mr)
        detail (glab config "mr" "view" (str iid) "--output" "json")
        versions (paged-api config
                            (str "projects/" project "/merge_requests/" iid "/versions"))
        diffs (paged-api config
                         (str "projects/" project "/merge_requests/" iid "/diffs"))]
    (when-not (and (vector? versions) (seq versions) (vector? diffs))
      (throw (ex-info "GitLab returned invalid publication metadata" {:iid iid})))
    {:mr detail :version (first versions) :diffs diffs}))

(defn discussions
  "Read every discussion so a hidden stable marker can reconcile remote success."
  [config mr]
  (paged-api config
             (str "projects/" (:project_id mr) "/merge_requests/" (:iid mr)
                  "/discussions")))

(defn find-discussion
  "Return the discussion carrying marker, or nil after a successful complete read."
  [config mr marker]
  (some (fn [discussion]
          (when (some #(str/includes? (or (:body %) "") marker) (:notes discussion))
            discussion))
        (discussions config mr)))

(defn- sha1 [value]
  (let [digest (.digest (java.security.MessageDigest/getInstance "SHA-1")
                        (.getBytes (str value) "UTF-8"))]
    (clojure.string/join (map #(format "%02x" (bit-and % 255)) digest))))

(defn- line-code [position line]
  (str (sha1 (:newPath position)) "_"
       (when (= "old" (:side position)) line) "_"
       (when (= "new" (:side position)) line)))

(defn create-discussion!
  "Create one general or exact text-diff discussion and return GitLab's receipt."
  [config mr version position body]
  (let [endpoint (str "projects/" (:project_id mr) "/merge_requests/" (:iid mr)
                      "/discussions")
        base [(:glab-bin config) "api" "--method" "POST" endpoint
              "--raw-field" (str "body=" body)]
        argv
        (if (= "general" (:kind position))
          base
          (let [side (:side position)
                line (:line position)
                line-field (str "position[" side "_line]=" line)
                base-fields
                ["--raw-field" "position[position_type]=text"
                 "--raw-field" (str "position[base_sha]=" (:base_commit_sha version))
                 "--raw-field" (str "position[start_sha]=" (:start_commit_sha version))
                 "--raw-field" (str "position[head_sha]=" (:head_commit_sha version))
                 "--raw-field" (str "position[old_path]=" (:oldPath position))
                 "--raw-field" (str "position[new_path]=" (:newPath position))
                 "--raw-field" line-field]
                range-fields
                (when-let [start (:startLine position)]
                  ["--raw-field" (str "position[line_range][start][type]=" side)
                   "--raw-field" (str "position[line_range][start][" side "_line]=" start)
                   "--raw-field" (str "position[line_range][start][line_code]="
                                      (line-code position start))
                   "--raw-field" (str "position[line_range][end][type]=" side)
                   "--raw-field" (str "position[line_range][end][" side "_line]=" line)
                   "--raw-field" (str "position[line_range][end][line_code]="
                                      (line-code position line))])]
            (into base (concat base-fields range-fields))))]
    (json/read-str (command! (:repo-dir config) argv (* 8 1024 1024) 60)
                   :key-fn keyword)))

(defn inspect-workspace!
  "Validate the setup-owned workspace and expose exact Git references to reviewers."
  [_config mr directory]
  (let [head (:sha mr) base (get-in mr [:diff_refs :base_sha])
        git (fn [cwd & args]
              (command! cwd (into ["git" "-c" "core.hooksPath=/dev/null"
                                   "-c" "core.fsmonitor=false"] args) 65536 120))]
    (when-not (.isDirectory (io/file directory))
      (throw (ex-info "Review setup did not create its reported worktree"
                      {:directory directory})))
    (when-not (= head (str/trim (git directory "rev-parse" "HEAD")))
      (throw (ex-info "Review worktree has an unexpected HEAD" {:directory directory})))
    (when-not (= (.getCanonicalPath (io/file directory))
                 (.getCanonicalPath (io/file (str/trim (git directory "rev-parse" "--show-toplevel")))))
      (throw (ex-info "Review setup worktree_path must identify the Git worktree root"
                      {:directory directory})))
    (when-not (str/blank? (git directory "status" "--porcelain=v1" "--untracked-files=no"))
      (throw (ex-info "Review worktree has tracked changes outside the admitted revision"
                      {:directory directory})))
    {:cwd directory :head head :base base
     ;; Tree identity detects empty changes without materializing any patch,
     ;; including huge generated files that reviewers may never need to read.
     :changed? (not= (str/trim (git directory "rev-parse" (str base "^{tree}")))
                     (str/trim (git directory "rev-parse" (str head "^{tree}"))))}))
