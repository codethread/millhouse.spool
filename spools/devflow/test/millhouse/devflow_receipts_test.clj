(ns millhouse.devflow-receipts-test
  "Ready-frontier regressions for delegation and external evidence boundaries."
  (:require [clojure.test :refer [deftest is testing]]
            [millhouse.devflow.execution :as execution]
            [millhouse.devflow.cards :as cards]
            [millhouse.devflow :as devflow]
            [millhouse.devflow.planning :as planning]
            [millhouse.devflow-test :refer [with-runtime]]
            [millhouse.workflow :as workflow]
            [millhouse.workflow.cli :as cli]
            [millstrand.api.cli.alpha :as cli-alpha]
            [millstrand.api.runtime.alpha :as runtime]
            [millstrand.api.lifecycle.alpha :as lifecycle]
            [millstrand.api.millstrand.alpha :as millstrand]
            [millstrand.api.spool.alpha :refer [attr-get]]
            [millstrand.api.weaver.alpha :as weaver]))

(millstrand/use-op! cli/workflow)
(lifecycle/use-seed! cli/workflow-glossary-seed)

(deftest task-approval-without-inline-queue-can-delegate-or-run-manually
  (with-runtime
    (fn [rt]
      (workflow/start! "queue" #'execution/tasks {:feature "queue"})
      (workflow/defer! "queue" :author-task-strands {:feature "queue"})
      (workflow/complete! "queue")
      (workflow/complete! "queue")
      (workflow/choose! "queue" :approved)
      (let [choice (workflow/ready-step "queue")]
        (is (= "choose-afk-execution" (:checkpoint choice)))
        (is (= "human" (:checkpoint-kind choice)))
        (is (some? (get (workflow/choice-detail "queue" :delegate) "input-spec")))
        (testing "invalid input leaves the same decision ready and launches nothing"
          (doseq [input [{} {:tasks []}
                         {:tasks [{:id "a" :title "A"}]}
                         {:tasks [{:id "a" :title "A"} {:id "a" :title "Again"}]
                          :delegate-harness "worker"}
                         {:tasks [{:id "bad/id" :title "A" :harness "worker"}]}]]
            (is (thrown? clojure.lang.ExceptionInfo
                         (workflow/choose! "queue" :delegate input)))
            (is (= (:id choice) (:id (workflow/ready-step "queue"))))
            (is (empty? (workflow/ready-gates "queue")))))
        (workflow/choose! "queue" :delegate
                          {:tasks [{:id "a" :title "A" :harness "specific"}
                                   {:id "b" :title "B"}]
                           :delegate-harness "default"})
        (let [first-gate (workflow/ready-step "queue")]
          (is (= "specific" (attr-get (weaver/show rt (:id first-gate)) :harness/alias)))
          (is (= 1 (count (workflow/ready "queue"))))
          (workflow/complete! "queue" {:by-identity "test-worker"
                                       :attributes {"harness/result" "A verified"}}))
        (let [second-gate (workflow/ready-step "queue")]
          (is (= "default" (attr-get (weaver/show rt (:id second-gate)) :harness/alias)))
          (workflow/complete! "queue" {:by-identity "test-worker"}))
        (is (= "human-acceptance-afk" (:checkpoint (workflow/ready-step "queue"))))
        (is (= "human" (:checkpoint-kind (workflow/ready-step "queue"))))
        (is (false? (:done (workflow/choose! "queue" :revise))))
        (is (= 1 (count (workflow/ready-gates "queue")))))
      (workflow/start! "manual" #'execution/run-afk-loop {:feature "manual"})
      (workflow/choose! "manual" :manual)
      (let [step (workflow/ready-step "manual")
            receipt {:queue "task-root" :outcome "blocked" :completed ["a"]
                     :remaining ["b"] :reason "Needs user decision" :owner "coordinator"}]
        (is (= "step" (:role step)))
        (is (nil? (:gate step)))
        (is (true? (:done (workflow/complete! "manual"
                                             {:attributes {"devflow/afk-outcome" receipt}}))))
        (is (= receipt (attr-get (weaver/show rt (:id step)) :devflow/afk-outcome)))))))

(deftest intake-records-worktree-and-reuses-it-on-revision
  (with-runtime
    (fn [rt]
      (workflow/start! "brief" :intake {:feature "brief"})
      (let [checkpoint (workflow/ready-step "brief")
            receipt {:repository "repo" :branch "feature" :worktree "/tmp/feature"}]
        (doseq [choice [:created-worktree :already-in-worktree]]
          (is (thrown? clojure.lang.ExceptionInfo (workflow/choose! "brief" choice)))
          (is (= (:id checkpoint) (:id (workflow/ready-step "brief")))))
        (workflow/choose! "brief" :already-in-worktree receipt)
        (is (= receipt (attr-get (weaver/show rt (:id checkpoint)) :workflow/outcome-input)))
        (is (= "Capture user brief for brief" (:title (workflow/ready-step "brief"))))
        (workflow/complete! "brief" {:context receipt})
        (workflow/choose! "brief" :needs-more-brief)
        (is (= "Capture user brief for brief" (:title (workflow/ready-step "brief"))))
        (is (= receipt (select-keys (attr-get (workflow/current-root "brief") :workflow/context)
                                   (keys receipt))))))))

(deftest proposal-merge-needs-provenance-and-an-exact-receipt-before-decomposition
  (with-runtime
    (fn [rt]
      (workflow/start! "landing" #'planning/land-proposal
                       {:feature "landing" :card-reviewer "reviewer"
                        :card-set-reviewer "set-reviewer"})
      (let [gate (workflow/ready-step "landing")
            receipt {:repository "repo" :mainline "main" :merged-revision "abc123"
                     :proposal-path "proposal.md" :merge-evidence "external-merge-42"}]
        (is (= "human" (:gate gate)))
        (is (thrown? clojure.lang.ExceptionInfo (workflow/complete! "landing")))
        (is (= (:id gate) (:id (workflow/ready-step "landing"))))
        (workflow/complete! "landing" {:by-identity "test-landing-actor"
                                       :attributes {"devflow/merge-receipt" receipt}})
        (is (= receipt (attr-get (weaver/show rt (:id gate)) :devflow/merge-receipt)))
        (is (thrown? clojure.lang.ExceptionInfo (workflow/choose! "landing" :landed)))
        (is (= "confirm-proposal-landed" (:checkpoint (workflow/ready-step "landing"))))
        (workflow/choose! "landing" :landed receipt)
        (is (= "author-cards" (:defer (workflow/ready-step "landing"))))
        (testing "defer does not silently inherit the validated root receipt"
          (is (thrown? clojure.lang.ExceptionInfo
                       (workflow/defer! "landing" :author-card-strands {:feature "landing"})))
          (is (= "author-cards" (:defer (workflow/ready-step "landing")))))
        (workflow/defer! "landing" :author-card-strands (assoc receipt :feature "landing"))
        (let [author (workflow/ready-step "landing")
              refs [{:id "implementation" :title "Implementation"}]]
          (workflow/complete! "landing" {:attributes {"devflow/review-set" refs}})
          (is (= "handoff-card-review" (:checkpoint (workflow/ready-step "landing"))))
          (workflow/choose! "landing" :review
                            {:cards (attr-get (weaver/show rt (:id author)) :devflow/review-set)})
          (is (= ["implementation"]
                 (mapv #(attr-get (weaver/show rt (:id %)) :devflow/card)
                       (workflow/ready-gates "landing")))))))))

(deftest direct-loop-input-requires-keyword-keyed-maps
  (with-runtime
    (fn [_]
      (doseq [item [{"id" "alpha" "title" "Alpha"}
                    {:id "alpha" "title" "Alpha"}
                    {:id "alpha" :title "Alpha" "harness" "ignored"}]
              [definition params] [[#'execution/run-afk-delegated
                                    {:feature "invalid" :tasks [item]
                                     :delegate-harness "worker"}]
                                   [#'cards/review-cards
                                    {:feature "invalid" :cards [item]
                                     :card-reviewer "reviewer"
                                     :card-set-reviewer "set-reviewer"}]]]
        (is (thrown? clojure.lang.ExceptionInfo
                     (workflow/start! "invalid" definition params)))
        (is (nil? (workflow/current-root "invalid")))))))

(deftest cli-json-preserves-named-task-and-card-loop-ids
  (with-runtime
    (fn [rt]
      (runtime/module! rt :workflow-cli {:ns 'millhouse.devflow-receipts-test
                                         :after [:millhouse/workflow]})
      (let [arg-spec (:arg-spec (weaver/resolve-op rt 'workflow))
            invoke (fn [argv]
                     (cli/workflow {:op/args (cli-alpha/parse arg-spec argv)
                                    :op/argv argv}))]
        (workflow/start! "json-tasks" #'execution/run-afk-loop {:feature "json-tasks"})
        (invoke ["choose" "json-tasks" "delegate" "--input"
                 "{\"tasks\":[{\"id\":\"alpha\",\"title\":\"Alpha\",\"harness\":\"specific\"},
                             {\"id\":\"beta\",\"title\":\"Beta\"}],\"delegate-harness\":\"worker\"}"])
        (let [params (attr-get (workflow/current-root "json-tasks") :workflow/context)
              steps (:steps (workflow/describe #'execution/run-afk-delegated params))]
          (is (= [{:id "alpha" :title "Alpha" :harness "specific"}
                  {:id "beta" :title "Beta"}]
                 (:tasks params)))
          (is (= [:task-alpha :task-beta :human-acceptance-afk] (mapv :id steps)))
          (is (= [:task-alpha] (:depends-on (second steps)))))
        (is (= ["Delegate AFK task alpha for json-tasks"]
               (mapv :title (workflow/ready "json-tasks"))))
        (invoke ["complete" "json-tasks" "--step" (:id (workflow/ready-step "json-tasks"))
                 "--by-identity" "test-worker"])
        (is (= ["Delegate AFK task beta for json-tasks"]
               (mapv :title (workflow/ready "json-tasks"))))

        (workflow/start! "json-cards" #'devflow/decompose
                         {:feature "json-cards" :card-reviewer "reviewer"
                          :card-set-reviewer "set-reviewer"})
        (workflow/defer! "json-cards" :author-card-strands
                         {:feature "json-cards" :repository "repo" :mainline "main"
                          :merged-revision "abc123" :proposal-path "proposal.md"
                          :merge-evidence "merge-record"})
        (workflow/complete! "json-cards")
        (invoke ["choose" "json-cards" "review" "--input"
                 "{\"cards\":[{\"id\":\"alpha\",\"title\":\"Alpha\"},
                             {\"id\":\"beta\",\"title\":\"Beta\"}]}"])
        (let [params (attr-get (workflow/current-root "json-cards") :workflow/context)
              steps (:steps (workflow/describe #'cards/review-cards params))]
          (is (= [{:id "alpha" :title "Alpha"} {:id "beta" :title "Beta"}] (:cards params)))
          (is (= [:card-review-alpha :card-review-beta] (mapv :id (take 2 steps))))
          (is (= #{:card-review-alpha :card-review-beta}
                 (set (:depends-on (nth steps 2))))))
        (is (= #{"Focused review of card alpha: Alpha" "Focused review of card beta: Beta"}
               (set (map :title (workflow/ready "json-cards")))))))))
