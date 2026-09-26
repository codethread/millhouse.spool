(ns quality.affected-cli
  "Run or print the same affected-test plan for local quality, CI and landing."
  (:require [clojure.data.json :as json]
            [clojure.string :as str]
            [quality.affected :as affected]))

(defn- options [args]
  (loop [args args opts {:base "main" :full? false :mode :plan}]
    (if-let [arg (first args)]
      (case arg
        "--base" (let [base (second args)]
                   (when (or (str/blank? base) (str/starts-with? base "--"))
                     (throw (ex-info "--base requires a Git revision" {})))
                   (recur (nnext args) (assoc opts :base base)))
        "--full" (recur (next args) (assoc opts :full? true))
        "--json" (recur (next args) (assoc opts :mode :json))
        "--run" (recur (next args) (assoc opts :mode :run))
        (throw (ex-info "Usage: --base REV [--full] [--json | --run]" {:argument arg})))
      opts)))

(defn- report! [{:keys [base merge-base full global-paths affected namespaces targets]}]
  (println "Test base:" base "merge-base:" merge-base)
  (println "Selection:" (if full "full" "affected")
           (when (seq global-paths) (str "(shared inputs: " (str/join ", " global-paths) ")")))
  (println "Components:" (str/join ", " affected))
  (println "Namespaces:" (str/join ", " namespaces))
  (println "Package gates:" (str/join ", " targets)))

(defn- execute! [plan]
  (report! plan)
  (let [commands (cond-> []
                   (seq (:namespaces plan))
                   (conj ["make" "test-root"
                          (str "TEST_NAMESPACES=--parallel " (str/join " " (:namespaces plan)))])
                   (seq (:targets plan))
                   (conj (into ["make"] (:targets plan))))]
    (when (empty? commands)
      (println "No affected tests."))
    (doseq [command commands]
      (flush)
      (let [exit (-> (ProcessBuilder. ^java.util.List command)
                     (.inheritIO) (.start) (.waitFor))]
        (when-not (zero? exit)
          (throw (ex-info "Affected test command failed" {:command command :exit exit})))))))

(defn -main
  "Print a plan by default; --json emits a CI matrix, --run executes selected gates."
  [& args]
  (try
    (let [{:keys [base full? mode]} (options args)
          plan (affected/checkout-plan "." base full?)]
      (case mode
        :json (println (json/write-str (assoc plan :matrix {:include (affected/jobs plan)})))
        :plan (report! plan)
        :run (execute! plan)))
    (finally
      (shutdown-agents))))
