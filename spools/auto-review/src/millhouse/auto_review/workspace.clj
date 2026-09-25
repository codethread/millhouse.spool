(ns millhouse.auto-review.workspace
  "Optional exact-revision preparation for Auto-run's existing callback seam."
  (:require [clojure.data.json :as json]
            [clojure.java.io :as io]
            [clojure.string :as str]
            [millhouse.auto-review :as review]
            [millhouse.auto-review.internal.process :as process]
            [millstrand.api.spool.alpha :refer [attr-get fail!]]
            [millstrand.api.weaver.alpha :as weaver]))

(defn- command! [repo argv]
  (process/command! repo argv (* 8 1024 1024) 900))

(defn inspect!
  "Require an external, clean Git worktree root at the frozen head and base.

  Used after consumer preparation and again before reviewer execution. Tracked
  changes are rejected; untracked dependency/build artifacts are allowed. Both
  comparison trees must exist. Returns {:cwd canonical-path :head sha :base sha}."
  [repo revision directory]
  (let [repo (.getCanonicalPath (io/file repo))
        directory (.getCanonicalPath (io/file directory))
        git (fn [& args]
              (str/trim (command! directory
                                  (into ["git" "-c" "core.hooksPath=/dev/null"
                                         "-c" "core.fsmonitor=false"] args))))]
    (when (or (= repo directory) (str/starts-with? directory (str repo java.io.File/separator)))
      (fail! "Review workspace must be outside the canonical repository" {:cwd directory}))
    (when-not (and (.isDirectory (io/file directory))
                   (= directory (.getCanonicalPath (io/file (git "rev-parse" "--show-toplevel")))))
      (fail! "Review workspace must be a Git worktree root" {:cwd directory}))
    (when-not (= (:head revision) (git "rev-parse" "HEAD"))
      (fail! "Review workspace has an unexpected HEAD" {:cwd directory}))
    (when-not (str/blank? (git "status" "--porcelain=v1" "--untracked-files=no"))
      (fail! "Review workspace has tracked changes" {:cwd directory}))
    (doseq [sha [(:head revision) (:base revision)]]
      (git "cat-file" "-e" (str sha "^{tree}")))
    {:cwd directory :head (:head revision) :base (:base revision)}))

(defn prepare!
  "Auto-run :prepare callback for an isolated exact-head review workspace.

  Fetches frozen head/base objects from origin, creates review/<card-id> at HEAD,
  allocates that existing branch through wktree, records the allocation before
  running its bootstrap, then
  validates the clean revision. Returns {:cwd ... :branch ...}; never claims.
  Consumer-specific dependency preparation can wrap this and re-run inspect!.
  Existing/blocked allocation, bootstrap or inspection failure stays visible in
  Auto-run's error receipt. No retries, fallback checkout or rollback is invented.
  Retain any allocated workspace/branch for explicit inspection and cleanup."
  [rt {:keys [repo card]}]
  (let [revision (review/request card)
        repo (.getCanonicalPath (io/file repo))
        branch (str "review/" (:id card))]
    (when-not (= repo (attr-get card :auto-review/repo))
      (fail! "Review card belongs to another local repository" {:card (:id card)}))
    (command! repo ["git" "fetch" "--no-tags" "--no-write-fetch-head"
                    "origin" (:head revision) (:base revision)])
    ;; wktree --base accepts a branch name, not an arbitrary object ID. Create
    ;; the exact local ref first, then let wktree allocate that existing branch.
    (command! repo ["git" "branch" branch (:head revision)])
    (weaver/update! rt (:id card) {:attributes {:auto-review/branch branch}})
    (let [allocation (json/read-str
                      (command! repo ["wktree" "--cwd" repo "add" "--branch" branch "--json"])
                      :key-fn keyword)
          cwd (:worktree_path allocation)
          script (:post_create_script_path allocation)]
      (when-not (and (= "ready" (:kind allocation)) (= branch (:branch allocation))
                     (string? cwd) (.isAbsolute (io/file cwd))
                     (or (nil? script) (and (string? script) (not (str/blank? script)))))
        (fail! "wktree did not prepare the requested review branch" {:allocation allocation}))
      (weaver/update! rt (:id card) {:attributes {:auto-review/workspace allocation}})
      ;; Refuse a wrong/canonical allocation before executing its bootstrap.
      (inspect! repo revision cwd)
      (when script (command! repo ["bash" script]))
      (inspect! repo revision cwd)
      (when-not (= branch (str/trim (command! cwd ["git" "branch" "--show-current"])))
        (fail! "Review workspace has an unexpected branch" {:cwd cwd :branch branch}))
      {:cwd (.getCanonicalPath (io/file cwd)) :branch branch})))
