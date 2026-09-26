(ns millhouse.harnesses.guidance-process-scan-test
  "Scanner backpressure and retained cleanup identity regressions."
  (:require [clojure.java.io :as io]
            [clojure.string :as str]
            [clojure.test :refer [deftest is testing]]
            [millhouse.harnesses.internal.guidance-capability :as capability]
            [millhouse.harnesses.internal.guidance-closure :as closure]
            [millhouse.harnesses.internal.guidance-process :as process]
            [millhouse.harnesses.internal.guidance-process-identity :as identity]
            [millhouse.harnesses.internal.guidance-process-scan :as scan]
            [millhouse.harnesses.internal.strict-json :as strict-json])
  (:import [java.io ByteArrayInputStream]
           [java.lang ProcessHandle]
           [java.nio.charset StandardCharsets]
           [java.util Arrays]
           [java.util.concurrent CountDownLatch TimeUnit]))

(defn- capability-document []
  {"schema" "millstrand.agent-guidance-capability/v1"
   "harness" "codex"
   "adapter-contract" "native-v1"
   "adapter-sha256" (str/join (repeat 64 "a"))
   "executable-sha256" (capability/file-sha256 "/usr/bin/true")
   "host-version" "0.154.0"
   "launch-profile-sha256" (str/join (repeat 64 "b"))
   "max-context-bytes" 3072
   "hook-fact"
   {"eventName" "sessionStart"
    "key" "managed-guidance"
    "source" "plugin"
    "sourcePath" "/fixture/plugin/hooks.json"
    "pluginId" "agents-fixture"
    "command" "node managed-guidance.js"
    "enabled" true
    "trustStatus" "trusted"
    "currentHash" "trusted-current-hash"
    "timeoutSec" 15
    "additionalContextLimit" 4096}})

(defn- helper-source [root]
  (str "import fs from 'node:fs';\n"
       "fs.writeFileSync(" (strict-json/canonical-json
                            (str (io/file root "helper.pid")))
       ", String(process.pid));\n"
       "process.stdin.resume();\n"
       "process.stdin.on('end', () => {\n"
       "  process.stdout.write('{}');\n"
       "});\n"))

(defn- scanner-source [root mode]
  (str "#!/bin/sh\n"
       "if [ \"$1\" = \"-o\" ]; then\n"
       "  printf '%s' \"$4\" > " (pr-str (str (io/file root "anchor.pid"))) "\n"
       "  exec /bin/ps \"$@\"\n"
       "fi\n"
       (case mode
         :normal "exec /bin/ps \"$@\"\n"
         :finite-flood
         (str "/usr/bin/awk 'BEGIN { for (i=0; i<20000; i++) "
              "print \"scanner\" > \"/dev/stderr\" }'\n"
              "exec /bin/ps \"$@\"\n")
         :stdout-overflow
         "exec /usr/bin/awk 'BEGIN { for (i=0; i<80000; i++) print \"1 1\" }'\n"
         :stderr-overflow
         (str "exec /usr/bin/awk 'BEGIN { for (i=0; i<80000; i++) "
              "print \"scanner\" > \"/dev/stderr\" }'\n")
         :stalled "while :; do :; done\n"
         :exited "printf '1 1\\n'; exit 0\n")))

(defn- finalize-profile [profile]
  (assoc-in profile [:process-ownership :reviewed-closure-sha256]
            (closure/reviewed-sha256 profile)))

(defn- with-scanner-profile [mode f]
  (let [root (.toFile
              (java.nio.file.Files/createTempDirectory
               "guidance-scanner"
               (make-array java.nio.file.attribute.FileAttribute 0)))
        scripts (doto (io/file root "scripts") .mkdirs)
        entrypoint (io/file scripts "managed-guidance-preflight.mjs")
        scanner (io/file root "scanner.sh")
        environment (-> (into {} (System/getenv))
                        (dissoc "NODE_OPTIONS" "NODE_PATH" "OPENSSL_CONF"
                                "DYLD_INSERT_LIBRARIES"))
        interpreter (capability/resolve-executable "node" environment)
        _ (spit entrypoint (helper-source root))
        _ (spit scanner (scanner-source root mode))
        _ (.setExecutable scanner true)
        profile
        (finalize-profile
         {:harness "codex"
          :preflight {:path (.getCanonicalPath entrypoint)
                      :sha256 (closure/file-sha256 entrypoint)}
          :capability (capability-document)
          :executable-closure
          {:schema "millstrand.local-guidance-executable-closure/v1"
           :reviewed-complete true
           :resolver-policy (closure/resolver-policy)
           :artifacts
           [(closure/artifact "entrypoint" entrypoint)
            (closure/artifact "interpreter" interpreter)
            (closure/artifact "ownership-scanner" scanner)
            (closure/artifact "subprocess" "/bin/sh")
            (closure/artifact "subprocess" "/bin/ps")
            (closure/artifact "subprocess" "/usr/bin/awk")]
           :resolution-inputs
           {:cwd (.getCanonicalPath root)
            :environment (closure/resolution-environment environment)}}
          :process-ownership
          {:contract "private-posix-session/inherited-process-group-v1"
           :reviewed-closure-sha256 (str/join (repeat 64 "0"))
           :child-process-behavior "inherited-process-group-only"}})]
    (try
      (f {:root root
          :profile (assoc profile :effective-environment environment)})
      (finally
        (doseq [file (reverse (file-seq root))]
          (.delete file))))))

(defn- thread-count [thread-name]
  (count (filter #(= thread-name (.getName ^Thread %))
                 (keys (Thread/getAllStackTraces)))))

(defn- await-uninterruptibly! [^CountDownLatch latch]
  (loop []
    (when-not (try
                (.await latch)
                true
                (catch InterruptedException _ false))
      (recur))))

(defn- workers-retired? []
  (loop [remaining 200]
    (if (zero? (thread-count "guidance-admission-worker"))
      true
      (if (zero? remaining)
        false
        (do (Thread/sleep 10) (recur (dec remaining)))))))

(defn- process-for-root? [root]
  (with-open [handles (ProcessHandle/allProcesses)]
    (boolean
     (some #(some-> (.commandLine (.info ^ProcessHandle %))
                    (.orElse "")
                    (str/includes? (.getCanonicalPath root)))
           (iterator-seq (.iterator handles))))))

(defn- run-profile
  ([profile]
   (run-profile profile nil))
  ([profile budget]
   (let [started (System/nanoTime)]
     (try
       {:result (if budget
                  (process/run! profile "{}" budget clojure.core/identity)
                  (process/run! profile "{}"))
        :elapsed-millis (/ (- (System/nanoTime) started) 1000000.0)}
       (catch Throwable error
         {:error error
          :elapsed-millis (/ (- (System/nanoTime) started) 1000000.0)})))))

(defn- cleanup-phase-budget []
  (let [started-at (System/nanoTime)]
    {:started-at started-at
     :work-deadline (+ started-at (.toNanos TimeUnit/SECONDS 8))
     :deadline (+ started-at (.toNanos TimeUnit/SECONDS 10))}))

(defn- pid-from [file]
  (when (.isFile file)
    (parse-long (str/trim (slurp file)))))

(defn- alive-pid? [pid]
  (boolean (and pid
                (some-> (ProcessHandle/of (long pid))
                        (.orElse nil)
                        .isAlive))))

(defn- start-sleep! []
  (.start (ProcessBuilder. ^java.util.List ["/bin/sleep" "30"])))

(defn- stop! [process]
  (when (.isAlive process)
    (.destroyForcibly process))
  (.waitFor process 5 TimeUnit/SECONDS))

(deftest completed-direct-scanner-does-not-require-a-live-birth-observation
  (with-scanner-profile
    :exited
    (fn [{:keys [root profile]}]
      (let [original-direct identity/retain-direct
            completed (atom nil)
            sentinel (start-sleep!)
            deadline (+ (System/nanoTime) (.toNanos TimeUnit/SECONDS 3))]
        (try
          (let [rows
                (with-redefs
                 [identity/retain-direct
                  (fn [handle role destroy!]
                    (let [retained (original-direct handle role destroy!)]
                      ;; Force the legal fast-exit interleaving without a sleep.
                      (while (.isAlive ^ProcessHandle handle)
                        (when-not (< (System/nanoTime) deadline)
                          (throw (ex-info "Fixture scanner did not exit" {})))
                        (Thread/yield))
                      (reset! completed retained)
                      retained))]
                  (scan/scan! (dissoc profile :effective-environment)
                              (:effective-environment profile) root
                              (str (io/file root "scanner.sh")) deadline
                              #(- % (System/nanoTime))))]
            (is (= [{:pid 1 :pgid 1}] rows))
            (is (:direct? @completed))
            (is (not (identity/live? @completed)))
            (is (.isAlive sentinel)))
          (finally
            (stop! sentinel)))))))

(deftest live-scanner-with-unavailable-birth-still-fails-and-is-cleaned
  (with-scanner-profile
    :stalled
    (fn [{:keys [profile]}]
      (let [original-retain identity/retain
            original-direct identity/retain-direct
            scanner (atom nil)
            sentinel (start-sleep!)]
        (try
          (let [{:keys [error]}
                (with-redefs
                 [identity/retain-direct
                  (fn [& args]
                    (let [retained (apply original-direct args)]
                      (when (= "direct-ownership-scanner" (:role retained))
                        (reset! scanner retained))
                      retained))
                  identity/retain
                  (fn [handle role]
                    (when (= "ownership-scanner" role)
                      (is (.isAlive ^ProcessHandle handle))
                      (throw (ex-info "Fixture live scanner birth unavailable" {})))
                    (original-retain handle role))]
                  (run-profile profile))]
            (is (= "Fixture live scanner birth unavailable" (ex-message error)))
            (is @scanner)
            (is (not (identity/live? @scanner)))
            (is (.isAlive sentinel)))
          (finally
            (stop! sentinel)))))))

(deftest scanner-drains-finite-flood-without-starving-helper-io
  (with-scanner-profile
    :finite-flood
    (fn [{:keys [root profile]}]
      (let [before-helper (thread-count "guidance-preflight-io")
            before-scanner (thread-count "guidance-preflight-scan-io")
            {:keys [result error elapsed-millis]} (run-profile profile)]
        (is (nil? error))
        (is (zero? (:exit-code result)))
        (is (< elapsed-millis 3000.0))
        (is (false? (process-for-root? root)))
        (is (= before-helper (thread-count "guidance-preflight-io")))
        (is (= before-scanner (thread-count "guidance-preflight-scan-io")))))))

(deftest scanner-capture-enforces-the-byte-boundary-without-a-deadline-race
  ;; Capture owns the byte boundary; subprocess tests own deadlines and retirement.
  (doseq [stream-name ["stdout" "stderr"]]
    (testing stream-name
      (let [limit (* 256 1024)
            bytes (byte-array limit (byte 97))]
        (with-open [input (ByteArrayInputStream. bytes)]
          (is (Arrays/equals bytes ^bytes (scan/capture! input stream-name))))
        (with-open [input (ByteArrayInputStream. (byte-array (inc limit)))]
          (let [error (try
                        (scan/capture! input stream-name)
                        nil
                        (catch clojure.lang.ExceptionInfo error error))]
            (is (re-find #"exceeded its byte limit" (ex-message error)))
            (is (= stream-name (:stream (ex-data error))))
            (is (= limit (:max-bytes (ex-data error))))))))))

(deftest scanner-output-validation-does-not-race-process-deadlines
  (let [utf8 #(.getBytes ^String % StandardCharsets/UTF_8)
        valid (utf8 "1 1\n")
        empty-bytes (byte-array 0)
        invalid (byte-array [(unchecked-byte 255)])]
    (is (= [{:pid 1 :pgid 1}]
           (scan/interpret-output! 0 valid empty-bytes)))
    (doseq [[label exit-code stdout stderr message data]
            [["nonzero exit" 7 valid (utf8 "scanner failed\n")
              #"ownership scan failed" {:exit-code 7 :diagnostic "scanner failed\n"}]
             ["malformed row" 0 (utf8 "not-a-process-row\n") empty-bytes
              #"scan output is malformed" {:line "not-a-process-row"}]
             ["invalid stdout UTF-8" 0 invalid empty-bytes
              #"not valid UTF-8" {:stream "stdout"}]
             ["invalid stderr UTF-8" 0 valid invalid
              #"not valid UTF-8" {:stream "stderr"}]
             ["duplicate PID" 0 (utf8 "1 1\n1 1\n") empty-bytes
              #"duplicate PIDs" {}]]]
      (testing label
        (let [error (try
                      (scan/interpret-output! exit-code stdout stderr)
                      nil
                      (catch clojure.lang.ExceptionInfo error error))]
          (is (re-find message (ex-message error)))
          (is (= data (ex-data error))))))))

(deftest first-and-confirming-cleanup-scanner-failures-preserve-custody
  ;; Two real startup scans establish ownership. Fail only the selected cleanup
  ;; scan: custody must not depend on which scanner error won a wall-clock race.
  (doseq [[phase fail-at] [[:first 3] [:confirming 4]]]
    (testing (name phase)
      (with-scanner-profile
        :normal
        (fn [{:keys [root profile]}]
          (let [unrelated (start-sleep!)
                original-scan! scan/scan!
                calls (atom 0)
                failure (ex-info "Fixture late ownership scan failure" {:phase phase})
                before-helper (thread-count "guidance-preflight-io")
                before-scanner (thread-count "guidance-preflight-scan-io")]
            (try
              (let [{:keys [error]}
                    (with-redefs [scan/scan!
                                  (fn [& args]
                                    (if (= fail-at (swap! calls inc))
                                      (do
                                        (is (alive-pid? (pid-from (io/file root "anchor.pid"))))
                                        (throw failure))
                                      (apply original-scan! args)))]
                      (run-profile profile (cleanup-phase-budget)))
                    anchor-pid (pid-from (io/file root "anchor.pid"))
                    helper-pid (pid-from (io/file root "helper.pid"))]
                (is (= fail-at @calls))
                (is (identical? failure error))
                (is (pos-int? anchor-pid))
                (is (pos-int? helper-pid))
                (is (not (alive-pid? anchor-pid)))
                (is (not (alive-pid? helper-pid)))
                (is (.isAlive unrelated))
                (is (false? (process-for-root? root)))
                (is (= before-helper (thread-count "guidance-preflight-io")))
                (is (= before-scanner (thread-count "guidance-preflight-scan-io"))))
              (finally
                (stop! unrelated)))))))))

(deftest scanner-birth-observation-shares-the-admission-deadline
  (with-scanner-profile
    :stalled
    (fn [{:keys [root profile]}]
      (let [original-retain identity/retain
            original-direct identity/retain-direct
            entered (CountDownLatch. 1)
            release (CountDownLatch. 1)
            scanner (atom nil)
            unrelated (start-sleep!)]
        (try
          (let [{:keys [error elapsed-millis]}
                (with-redefs
                 [identity/retain-direct
                  (fn [& args]
                    (let [retained (apply original-direct args)]
                      (when (= "direct-ownership-scanner" (:role retained))
                        (reset! scanner retained))
                      retained))
                  identity/retain
                  (fn [handle role]
                    (when (= "ownership-scanner" role)
                      (.countDown entered)
                      (await-uninterruptibly! release))
                    (original-retain handle role))]
                  (run-profile profile))
                anchor-pid (pid-from (io/file root "anchor.pid"))]
            (is (zero? (.getCount entered)))
            (is (re-find #"Guidance preflight timed out" (ex-message error)))
            (is (< elapsed-millis 3200.0))
            (is @scanner)
            (is (not (identity/live? @scanner)))
            (is (not (alive-pid? anchor-pid)))
            (is (.isAlive unrelated))
            (is (false? (process-for-root? root))))
          (finally
            (.countDown release)
            (is (workers-retired?))
            (stop! unrelated)))))))

(deftest delayed-scanner-liveness-cannot-block-revocation-or-signal-late
  (with-scanner-profile
    :stalled
    (fn [{:keys [root profile]}]
      (let [original-live? identity/live?
            delayed? (atom false)
            {:keys [error elapsed-millis]}
            (with-redefs [identity/live?
                          (fn [retained]
                            (when (and (contains? #{"direct-ownership-scanner"
                                                    "ownership-scanner"}
                                                  (:role retained))
                                       (compare-and-set! delayed? false true))
                              (try
                                (Thread/sleep 3100)
                                (catch InterruptedException _ nil)))
                            (original-live? retained))]
              (run-profile profile))
            anchor-pid (pid-from (io/file root "anchor.pid"))
            helper-pid (pid-from (io/file root "helper.pid"))]
        (is @delayed?)
        (is (re-find #"scan timed out" (ex-message error)))
        (is (< elapsed-millis 3000.0))
        (is (not (alive-pid? anchor-pid)))
        (is (not (alive-pid? helper-pid)))
        (is (false? (process-for-root? root)))
        (is (zero? (thread-count "guidance-admission-worker")))))))

(deftest scanner-failures-remain-bounded-and-clean-retained-identities
  ;; Exercise scanner failures directly, without racing the outer preflight
  ;; admission deadline. Floods may hit either independent bound; capture! above
  ;; proves the exact byte boundary without asserting which bound wins here.
  (doseq [[mode message]
          [[:stdout-overflow #"exceeded its byte limit|scan timed out"]
           [:stderr-overflow #"exceeded its byte limit|scan timed out"]
           [:stalled #"scan timed out"]]]
    (testing (name mode)
      (with-scanner-profile
        mode
        (fn [{:keys [root profile]}]
          (let [unrelated (start-sleep!)
                original-direct identity/retain-direct
                scanners (atom [])
                before-scanner (thread-count "guidance-preflight-scan-io")
                started (System/nanoTime)
                deadline (+ started (.toNanos TimeUnit/SECONDS 3))]
            (try
              (let [error
                    (with-redefs [identity/retain-direct
                                  (fn [& args]
                                    (let [retained (apply original-direct args)]
                                      (swap! scanners conj retained)
                                      retained))]
                      (try
                        (scan/scan! (dissoc profile :effective-environment)
                                    (:effective-environment profile) root
                                    (str (io/file root "scanner.sh")) deadline
                                    #(- % (System/nanoTime)))
                        nil
                        (catch Throwable error error)))]
                (is (re-find message (ex-message error)))
                (is (< (/ (- (System/nanoTime) started) 1000000.0) 3000.0))
                (is (= 1 (count @scanners)))
                (is (every? (complement identity/live?) @scanners))
                (is (.isAlive unrelated))
                (is (false? (process-for-root? root)))
                (is (= before-scanner
                       (thread-count "guidance-preflight-scan-io"))))
              (finally
                (stop! unrelated)))))))))
