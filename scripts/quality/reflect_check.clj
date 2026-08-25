(ns quality.reflect-check
  "Compile every production namespace with reflection warnings promoted to failure."
  (:require [clojure.java.io :as io]
            [clojure.string :as str]))

(def ^:private roots
  {"spools/chime/src" "millhouse/spools"
   "spools/cron/src" "millhouse/spools"
   "spools/workflow/src" "millhouse/spools"
   "spools/kanban/src" "millhouse/spools"})

(defn- clj-file->ns
  [root file]
  (let [root-path (.toPath (io/file root))
        relative (str (.relativize root-path (.toPath file)))]
    (-> relative
        (str/replace #"\.clj$" "")
        (str/replace #"[/\\]" ".")
        (str/replace "_" "-")
        symbol)))

(defn- namespaces-under
  [root subdir]
  (let [dir (io/file root subdir)]
    (when-not (.isDirectory dir)
      (throw (ex-info "Configured reflection root is missing"
                      {:root root :subdir subdir})))
    (for [file (file-seq dir)
          :when (and (.isFile file) (str/ends-with? (.getName file) ".clj"))]
      (clj-file->ns root file))))

(defn -main
  [& _]
  (let [namespaces (sort (mapcat (fn [[root subdir]]
                                   (namespaces-under root subdir))
                                 roots))
        compile-dir (.toFile
                     (java.nio.file.Files/createTempDirectory
                      "millhouse-reflect-check"
                      (make-array java.nio.file.attribute.FileAttribute 0)))
        warnings (atom [])
        original-err *err*
        warning-err (proxy [java.io.Writer] []
                      (write
                        ([value]
                         (when (str/includes? value "Reflection warning")
                           (swap! warnings conj value))
                         (.write original-err value))
                        ([value offset length]
                         (let [chunk (subs value offset (+ offset length))]
                           (when (str/includes? chunk "Reflection warning")
                             (swap! warnings conj chunk))
                           (.write original-err value offset length))))
                      (flush [] (.flush original-err))
                      (close [] nil))]
    (try
      (binding [*warn-on-reflection* true
                *compile-path* (.getAbsolutePath compile-dir)
                *err* warning-err]
        (doseq [namespace namespaces]
          (require namespace :reload)
          (compile namespace)))
      (finally
        (doseq [file (reverse (file-seq compile-dir))]
          (io/delete-file file true))))
    (if (seq @warnings)
      (do
        (binding [*out* *err*]
          (println "Reflection warnings detected:" (count @warnings)))
        (System/exit 1))
      (println "reflect-check: OK"))))
