(ns millhouse.spools.auto-review.internal.process
  "Bounded literal subprocess boundary for read-only review preparation and glab."
  (:require [clojure.java.io :as io])
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
