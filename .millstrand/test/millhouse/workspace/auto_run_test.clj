(ns millhouse.workspace.auto-run-test
  "Exercise real workspace activation in disposable, unlabelled Weaver worlds."
  (:require [clojure.edn :as edn]
            [clojure.java.io :as io]
            [clojure.data.json :as json]
            [clojure.test :refer [deftest is run-tests testing]]
            [millhouse.auto-run :as auto-run]
            [millhouse.auto-run-land :as autonomous]
            [millhouse.chime :as chime]
            [millhouse.harnesses :as harnesses]
            [millhouse.harnesses.reviewers :as reviewers]
            [millhouse.workflow :as workflow]
            [millstrand.api.current.alpha :as current]
            [millstrand.api.graph.alpha :as graph]
            [millstrand.api.runtime.alpha :as runtime]
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
     :files (into {} (for [path ["me/land.clj" "me/auto_run_workflows.clj"
                                 "me/auto_run.clj" "me/agents/reviewers.clj"
                                 "me/notifications/attention.clj"]]
                       [path (slurp path)]))}))

(defn- role-step [strands role]
  (first (filter #(= role (attr-get % :auto-run/role)) strands)))

(deftest repository-activation-and-delivery-contracts
  (t/with-weaver-world
    [ctx (world-options)]
    (let [rt (:runtime ctx)
          status (auto-run/status rt)]
      (is (= {:enabled true :max-running 1 :workflow "auto-human-review"}
             (select-keys (assoc (:config status) :enabled (:enabled status))
                          [:enabled :max-running :workflow])))
      (is (empty? (:dispatched (auto-run/scan! rt))))
      (testing "workspace publishes policy-aligned attention rules"
        (is (= #{:auto-run-failed :ticket-needs-attention}
               (set (map :key (current/with-runtime rt (chime/rules))))))
        (let [auto-run-rule (runtime/resolve-var
                             rt 'me.notifications.attention/auto-run-failed-rule)
              attention-rule (runtime/resolve-var
                              rt 'me.notifications.attention/ticket-needs-attention-rule)
              failed-run {:id "run-1"
                          :title "Feature worker"
                          :state "active"
                          :attributes
                          {:harness/run "true"
                           :harness/status "failed"
                           :harness/substatus "execution"
                           :harness/target "card-1"
                           :harness/exit-code 1
                           :harness/error "quality failed"
                           :harness/context
                           {"assignment/policy" "auto-run-workflow"}}}
              attention-ticket {:id "card-1"
                                :title "Blocked feature"
                                :state "active"
                                :attributes
                                {:kanban/card "true"
                                 :kanban/lane "in_review"
                                 :auto-run/agent-evidence "evidence-1"}}]
          (is (= {:title "Auto-run failed: Feature worker"
                  :body (str "Auto-run run-1 failed.\n"
                             "Target: card-1\n"
                             "Failure class: execution\n"
                             "Exit code: 1\n"
                             "Error: quality failed\n"
                             "Inspect with `strand agent show run-1`.")}
                 (auto-run-rule {:strand failed-run})))
          (is (nil? (auto-run-rule
                     {:strand (assoc-in failed-run
                                        [:attributes :harness/context]
                                        {"assignment/policy" "review"})})))
          (is (= {:title "Auto-run failed: Landing finisher"
                  :body (str "Auto-run finisher-1 failed.\n"
                             "Inspect with `strand agent show finisher-1`.")}
                 (auto-run-rule
                  {:strand {:id "finisher-1"
                            :title "Landing finisher"
                            :state "active"
                            :attributes
                            {:harness/run "true"
                             :harness/status "failed"
                             :harness/request-id
                             "auto-land-finisher/target-1"}}})))
          (is (= {:title "Ticket needs attention: Blocked feature"
                  :body (str "Ticket card-1 is waiting for you in the in_review lane.\n"
                             "Evidence: evidence-1\n"
                             "Inspect with `strand kanban card card-1`.")}
                 (attention-rule {:strand attention-ticket})))
          (is (nil? (attention-rule
                     {:strand (assoc-in attention-ticket
                                        [:attributes :kanban/lane]
                                        "claimed")})))))
      (testing "checked-in basis publishes the complete CLI surface"
        (let [aliases (set (map :name (weaver/op! rt 'agent ["list"])))
              reviewers (weaver/op! rt 'agent ["reviewers"])
              workflows (set (map :name (:definitions
                                         (weaver/op! rt 'workflow ["list"]))))
              operations (set (map :name (weaver/ops rt)))]
          (is (contains? aliases "sol"))
          (is (= ["docs-and-tests" "runtime-correctness" "source-form" "test-layering"]
                 (mapv :name (:reviewers reviewers))))
          (is (every? workflows ["auto-full-land" "auto-human-review" "land"]))
          (is (contains? operations "auto-run"))
          (is (contains? operations "merge-queue"))))
      (testing "the repository owns its squash landing policy"
        (let [{:keys [prepare-policy merge-tail abort-definition
                      retry-instructions release-instruction]}
              (t/repl!
               ctx
               '(let [definition @(requiring-resolve
                                   'millhouse.workspace.land/land-merge)
                      steps (into {} (map (juxt :id identity)) (:steps definition))
                      prepare-argv ((get-in steps [:prepare-merge :attributes "shell/argv"])
                                    {:branch "feature/fixture"})
                      merge-argv ((get-in steps [:merge-pr :attributes "shell/argv"])
                                  {:pr-number 42 :subject "Subject" :body "Body"
                                   :branch "feature/fixture"})]
                  {:prepare-policy (nth prepare-argv (- (count prepare-argv) 2))
                   :merge-tail (subvec merge-argv (- (count merge-argv) 2))
                   :abort-definition
                   (get-in definition [:attributes "land/abort-definition"])
                   :retry-instructions
                   (mapv #(get-in steps [% :attributes "workflow/instruction"])
                         [:prepare-merge :merge-pr :pull-main
                          :remove-branch-worktree])
                   :release-instruction
                   (get-in steps [:release-turn :attributes
                                  "workflow/instruction"])}))]
          (is (= "rebase" prepare-policy))
          (is (= ["feature/fixture" "squash"] merge-tail))
          (is (= "millhouse.workspace.land/land-abort" abort-definition))
          (is (every? #(re-find
                        #"workflow retry RUN --step GATE --expected-attempt TOKEN"
                        %)
                      retry-instructions))
          (is (re-find #"clear gate/error" release-instruction))))
      (testing "workspace policy adds its lens without replacing shared reviewers"
        (let [catalog (into {} (map (juxt :name identity)) (reviewers/reviewers rt))
              lens (get catalog "test-layering")]
          (is (every? #(contains? catalog %)
                      ["source-form" "docs-and-tests" "runtime-correctness"]))
          (is (= ["test-layer-reviewer"] (:seats lens)))
          (is (= {:harness/model "deepseek/deepseek-flash"
                  :harness/effort "max"}
                 (select-keys (:generated (harnesses/resolve-harness rt :test-layer-reviewer))
                              [:harness/model :harness/effort])))
          (harnesses/set-flag! rt :seat/allow-china false)
          (is (false? (:available (harnesses/availability rt :test-layer-reviewer))))
          (harnesses/set-flag! rt :seat/allow-china true)
          (is (= ["PR" "Tests" "Test-layering"] (:labels lens)))
          (is (= ["test/**" "spools/*/test/**" "spools/*/*/test/**"
                  ".millstrand/test/**"]
                 (:glob lens)))))
      (let [card (weaver/add! rt {:title "Blocked work"
                                    :attributes {:kanban/card "true"
                                                 :kanban/type "feature"
                                                 :kanban/lane "claimed"}})
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
            (is (not= (:id worker) (:id finisher))))
          (testing "repository validation gates use the shared recovery policy"
            (let [validation-gates (filter #(= "shell" (attr-get % :workflow/gate)) strands)
                  repair (autonomous/validation-failure-policy "fixture-card")]
              (is (= 2 (count validation-gates)))
              (is (every? #(= repair (attr-get % :workflow/instruction)) validation-gates)))))))))

(defn -main
  "Run disposable workspace tests without touching the repository's live Weaver."
  [& _]
  (let [{:keys [fail error]} (run-tests 'millhouse.workspace.auto-run-test)]
    (shutdown-agents)
    (System/exit (if (zero? (+ fail error)) 0 1))))
