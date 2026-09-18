(ns millhouse.auto-run-test
  "Exercise real workspace activation in disposable, unlabelled Weaver worlds."
  (:require [clojure.edn :as edn]
            [clojure.java.io :as io]
            [clojure.string :as str]
            [clojure.test :refer [deftest is run-tests testing]]
            [ct.spools.codethread.auto-run :as auto-run]
            [millhouse.spools.workflow :as workflow]
            [millstrand.api.current.alpha :as current]
            [millstrand.api.graph.alpha :as graph]
            [millstrand.api.spool.alpha :refer [attr-get]]
            [millstrand.test.alpha :as t]))

(defn- fixture-deps-edn []
  (let [deps (:deps (edn/read-string (slurp "deps.edn")))]
    (pr-str
     {:deps (update-vals deps
                         #(if-let [root (:local/root %)]
                            (assoc % :local/root (.getCanonicalPath (io/file root)))
                            %))})))

(deftest repository-activation-and-delivery-contracts
  (t/with-weaver-world
    [ctx {:storage :sqlite-memory
          :deps-edn (fixture-deps-edn)
          :init-clj (slurp "init.clj")
          :files (into {} (for [path ["me/auto_run_workflows.clj" "me/auto_run.clj"]]
                            [path (slurp path)]))}]
    (let [rt (:runtime ctx)
          status (auto-run/status rt)]
      (is (:enabled status))
      (is (= 2 (get-in status [:config :max-running])))
      (is (= "auto-human-review" (get-in status [:config :workflow])))
      (is (empty? (:cards status)))
      (is (empty? (:dispatched (auto-run/scan! rt))))
      (current/with-runtime rt
        (doseq [name [:auto-human-review :auto-full-land]]
          (let [run-id (str "test-" (clojure.core/name name))
                result (workflow/start! run-id name
                                        {:card "fixture-card" :feature "Disposable feature"
                                         :branch "auto/fixture-card" :worktree (:config-dir ctx)})
                root (workflow/current-root run-id)
                strands (:strands (graph/subgraph rt [(:id root)]))
                views (map workflow/step-view strands)
                gates (set (keep #(attr-get % :workflow/gate) strands))]
            (is (= ["Implement and verify the assigned feature"] (mapv :title (:ready result))))
            (is (contains? gates "shell"))
            (is (contains? gates "code"))
            (is (not (contains? gates "agent")) "The finisher is an explicit handoff, not an eager agent gate")
            (if (= name :auto-human-review)
              (testing "Human review still stops without any landing delegation"
                (let [checkpoint (first (filter #(= "human" (:checkpoint-kind %)) views))]
                  (is (= ["reviewed"] (:choices checkpoint)))
                  (is (str/includes? (:instruction checkpoint) "Do not choose this checkpoint"))
                  (is (not-any? #(str/includes? (or (:instruction %) "") "auto-land-finisher/") views))
                  (is (every? #(str/includes? (:instruction %) "clear gate/error to retry")
                              (filter #(= "shell" (:gate %)) views)))))
              (testing "The rendered worker contract delegates before irreversible signoff"
                (let [handoff (first (filter #(= "Review then hand landing to a canonical-root grunt"
                                                (:title %)) views))
                      instruction (:instruction handoff)]
                  (is (= "step" (:role handoff)))
                  (is (= "active" (:state handoff)))
                  (is (not (:done result)))
                  ;; Instructions are the executable policy boundary, not prose documentation.
                  (doseq [required ["land-auto-fixture-card"
                                    "STOP at land's signoff checkpoint BEFORE choosing approved"
                                    "--target with THIS HANDOFF STEP ID (not card fixture-card"
                                    "--request-id `auto-land-finisher/HANDOFF_STEP_ID`"
                                    "auto-run/finisher-run-id"
                                    "Return immediately. Do not wait for the grunt"
                                    "--query agent-run-settled"
                                    "--param run-id=ORIGINAL_WORKER_RUN_ID --min-count 1"
                                    "require settled=true, completed"
                                    "Do not finish the card early"
                                    "Verify land is done and the card is closed with outcome done"]]
                    (is (str/includes? instruction required) required))
                  (testing "Failed autonomous gates preserve work for manual intervention"
                    (doseq [view (cons handoff (filter :gate views))]
                      (is (str/includes? (:instruction view) "`auto-run-failure` to card fixture-card"))
                      (is (str/includes? (:instruction view) "Stop and leave the card open"))
                      (is (str/includes? (:instruction view) "withdraw the merge turn")))))))))))))

(defn -main
  "Run disposable workspace tests without touching the repository's live Weaver."
  [& _]
  (let [{:keys [fail error]} (run-tests 'millhouse.auto-run-test)]
    (shutdown-agents)
    (System/exit (if (zero? (+ fail error)) 0 1))))
