(ns millhouse.test-support
  "Small downstream test seam over Millstrand's blessed alpha APIs."
  (:require [clojure.java.io :as io]
            [clojure.test :as t]
            [millstrand.api.current.alpha :as current]
            [millstrand.test.alpha :as test-alpha]))

(defn await-budget-ms
  "Scale a polling budget with MILLSTRAND_TEST_AWAIT_SCALE."
  ([] (await-budget-ms 10000))
  ([base-ms]
   (let [raw (System/getenv "MILLSTRAND_TEST_AWAIT_SCALE")
         scale (if raw
                 (try
                   (Double/parseDouble raw)
                   (catch NumberFormatException cause
                     (throw (ex-info "MILLSTRAND_TEST_AWAIT_SCALE must be a number"
                                     {:value raw} cause))))
                 1.0)]
     (when-not (and (integer? base-ms) (pos? base-ms))
       (throw (ex-info "Polling base budget must be a positive integer"
                       {:base-ms base-ms})))
     (when-not (and (Double/isFinite scale) (pos? scale))
       (throw (ex-info "MILLSTRAND_TEST_AWAIT_SCALE must be finite and positive"
                       {:value raw :scale scale})))
     (long (* base-ms scale)))))

(defn poll-until
  "Return the first truthy predicate result, or fail when its budget expires."
  ([pred] (poll-until pred {}))
  ([pred {:keys [timeout-ms interval-ms on-timeout]
          :or {timeout-ms (await-budget-ms)
               interval-ms 50
               on-timeout #(throw (ex-info "Timed out waiting for predicate" {}))}}]
   (when-not (and (integer? timeout-ms) (pos? timeout-ms))
     (throw (ex-info "poll-until :timeout-ms must be a positive integer"
                     {:timeout-ms timeout-ms})))
   (when-not (and (integer? interval-ms) (pos? interval-ms))
     (throw (ex-info "poll-until :interval-ms must be a positive integer"
                     {:interval-ms interval-ms})))
   (let [deadline (+ (System/nanoTime) (* timeout-ms 1000000))]
     (loop []
       (if-let [value (pred)]
         value
         (if (>= (System/nanoTime) deadline)
           (on-timeout)
           (do (Thread/sleep interval-ms) (recur))))))))

(defn assert-state-shape
  "Assert the exact key set produced by a versioned spool state constructor."
  [new-state-fn expected-keys]
  (t/is (= (set expected-keys) (set (keys (new-state-fn))))
        "spool-state key set drifted; bump its state version and expected keys together"))

(def ^:private module-activation-lock (Object.))

(defn- repository-root []
  (-> (test-alpha/spool-checkout-root "millhouse/workflow.clj")
      .getParentFile
      .getParentFile
      .getCanonicalPath))

(defn- fixture-deps-edn []
  (let [root (repository-root)]
    (pr-str {:paths [(str root "/test")
                     (str root "/spools/workflow/test")]
             :deps {'millhouse/workflow
                    {:local/root (str root "/spools/workflow")}
                    'millhouse/land
                    {:local/root (str root "/spools/land")}}})))

(defn with-module-activation
  "Run one source-backed module activation under the JVM namespace lock.

  Source activation reloads namespace Vars shared by parallel test fixtures;
  callers use this seam around direct `runtime/module!` calls as well as the
  convenience wrapper below."
  [f]
  (locking module-activation-lock
    (f)))

(defn with-embedded-runtime
  "Call f with a fresh embedded Weaver runtime and its config directory File.

  Bind the runtime for runtime-implied APIs. Each call owns a disposable
  workspace, database, registries, generation basis and shutdown/cleanup.
  This is a full world, not a cheap bare-runtime constructor.

  Options are :prefix (Weaver name) and :storage (:sqlite-file by default,
  or :sqlite-memory for serialized, non-durable contracts). Memory storage
  still starts a world and basis; use file storage for durability or connection
  topology. No modules are activated implicitly. For startup files, explicit
  roots or durable reopen, use millstrand.test.alpha/with-weaver-world directly."
  ([f] (with-embedded-runtime {} f))
  ([opts f]
   (when-let [unknown (seq (remove #{:prefix :storage} (keys opts)))]
     (throw (ex-info "Unknown Millhouse runtime fixture options"
                     {:keys (vec unknown)})))
   (test-alpha/run-with-weaver-world
    (cond-> (merge {:deps-edn (fixture-deps-edn)} (select-keys opts [:storage]))
      (:prefix opts) (assoc :name (:prefix opts)))
    (fn [{:keys [runtime config-dir]}]
      (current/with-runtime runtime
        (f runtime (io/file config-dir)))))))

(defn with-runtime
  "Call f using with-embedded-runtime; this existing name starts a full world.

  Accept the same :prefix and :storage options. Omitted storage remains
  file-backed; existing callers are not switched to memory implicitly."
  ([f] (with-embedded-runtime f))
  ([opts f] (with-embedded-runtime opts f)))

(defn activate-spool!
  "Activate a namespace-backed module and fail on any refused outcome."
  [rt key ns-sym & {:keys [after load]}]
  ;; Source activation reloads a Clojure namespace in the shared test JVM.
  ;; Serialize that global namespace mutation so a parallel fixture cannot
  ;; select a declaration between its def and declaration-metadata install.
  (with-module-activation
    #(test-alpha/activate-module! rt key ns-sym
                                  (cond-> {}
                                    load (assoc :load load)
                                    after (assoc :after after)))))

(defn temp-dir
  "Create a disposable directory below /tmp. The caller owns cleanup."
  [prefix]
  (.toFile (java.nio.file.Files/createTempDirectory
            (.toPath (io/file "/tmp")) prefix
            (make-array java.nio.file.attribute.FileAttribute 0))))

(defn run-git!
  "Run `git` in `dir` with `args`, returning raw stdout on success."
  [dir & args]
  (let [process (-> (ProcessBuilder. (into-array String (cons "git" args)))
                    (.directory (io/file dir))
                    (.start))
        stderr (future (slurp (.getErrorStream process)))
        stdout (future (slurp (.getInputStream process)))
        exit (.waitFor process)
        stdout @stdout
        stderr @stderr]
    (when-not (zero? exit)
      (throw (ex-info "Git fixture command failed"
                      {:args args :exit exit :stdout stdout :stderr stderr})))
    stdout))

(defn delete-tree!
  "Delete a test path recursively without following directory symlinks."
  [root]
  (let [root-path (.toPath (io/file root))]
    (letfn [(delete-path! [path]
              (when (java.nio.file.Files/isDirectory
                     path
                     (into-array java.nio.file.LinkOption
                                 [java.nio.file.LinkOption/NOFOLLOW_LINKS]))
                (with-open [children (java.nio.file.Files/newDirectoryStream path)]
                  (doseq [child children]
                    (delete-path! child))))
              (java.nio.file.Files/deleteIfExists path))]
      (delete-path! root-path)
      nil)))
