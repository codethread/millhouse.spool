(ns millhouse.workspace.auto-run-test
  "Exercise real workspace activation in disposable, unlabelled Weaver worlds."
  (:require [clojure.edn :as edn]
            [clojure.java.io :as io]
            [clojure.data.json :as json]
            [clojure.test :refer [deftest is run-tests testing]]
            [millhouse.auto-run :as auto-run]
            [millhouse.workflow :as workflow]
            [millstrand.api.current.alpha :as current]
            [millstrand.api.graph.alpha :as graph]
            [millstrand.api.spool.alpha :refer [attr-get]]
            [millstrand.api.weaver.alpha :as weaver]
            [millstrand.test.alpha :as t]))

(defn- world-options []
  (let [deps (:deps (edn/read-string (slurp "deps.edn")))]
    {:storage :sqlite-memory
     :deps-edn (pr-str
                {:deps (update-vals deps
                                    #(if-let [root (:local/root %)]
                                       (assoc % :local/root (.getCanonicalPath (io/file root)))
                                       %))})
     :init-clj (slurp "init.clj")
     :files (into {} (for [path ["me/auto_run_workflows.clj" "me/auto_run.clj"]]
                       [path (slurp path)]))}))

(defn- role-step [strands role]
  (first (filter #(= role (attr-get % :auto-run/role)) strands)))

(deftest repository-activation-and-delivery-contracts
  (t/with-weaver-world
    [ctx (world-options)]
    (let [rt (:runtime ctx)
          status (auto-run/status rt)]
      (is (= {:enabled true :max-running 2 :workflow "auto-human-review"}
             (select-keys (assoc (:config status) :enabled (:enabled status))
                          [:enabled :max-running :workflow])))
      (is (empty? (:dispatched (auto-run/scan! rt))))
      (let [card (weaver/add! rt {:title "Blocked work"})
            evidence (weaver/add! rt {:title "Decision context"})]
        (weaver/op! rt 'weave
                    ["--pattern" "auto-run-needs-decision" "--input"
                     (json/write-str {:strand (:id card) :evidence (:id evidence)})])
        (let [reported (weaver/show rt (:id card))]
          (is (= "needs-decision" (attr-get reported :auto-run/agent-blocked-status)))
          (is (= (:id evidence) (attr-get reported :auto-run/agent-evidence)))
          (is (= "true" (attr-get reported :kanban.label/agent-blocked)))))
      (current/with-runtime rt
        (let [human-run "test-auto-human-review"
              _ (workflow/start! human-run :auto-human-review
                                 {:card "fixture-card" :feature "Disposable feature"
                                  :branch "auto/fixture-card" :worktree (:config-dir ctx)})
              root (workflow/current-root human-run)
              strands (:strands (graph/subgraph rt [(:id root)]))
              views (map workflow/step-view strands)
              checkpoint (first (filter #(= "human" (:checkpoint-kind %)) views))]
          (testing "repository policy retains the human review boundary"
            (is (= ["reviewed"] (:choices checkpoint)))
            (is (some #(= ["bash" ".millstrand/land-quality.sh"]
                           (attr-get % :shell/argv)) strands)
                "The automatic gate uses the same single lock owner as Land")
            (is (= ["millhouse.land.card-actions/review-card!"]
                   (keep #(attr-get % :code/fn) strands)))
            (is (nil? (role-step strands "finisher")))))
        (let [full-run "test-auto-full-land"
              _ (workflow/start! full-run :auto-full-land
                                 {:card "fixture-card" :feature "Disposable feature"
                                  :branch "auto/fixture-card" :worktree (:config-dir ctx)})
              root (workflow/current-root full-run)
              strands (:strands (graph/subgraph rt [(:id root)]))
              worker (role-step strands "handoff-worker")
              finisher (role-step strands "finisher")]
          (testing "repository policy delegates full landing to the shared two-role workflow"
            (is (some? worker))
            (is (some? finisher))
            (is (not= (:id worker) (:id finisher)))))))))

(defn -main
  "Run disposable workspace tests without touching the repository's live Weaver."
  [& _]
  (let [{:keys [fail error]} (run-tests 'millhouse.workspace.auto-run-test)]
    (shutdown-agents)
    (System/exit (if (zero? (+ fail error)) 0 1))))
