(ns millhouse.workflow-authoring-test
  "Test workflow builders, inert declarations and authoring tooling."
  (:require [clojure.edn :as edn]
            [clojure.java.io :as io]
            [clojure.java.shell :as sh]
            [clojure.spec.alpha :as s]
            [clojure.string :as str]
            [clojure.test :refer [deftest is testing]]
            [millhouse.test-support :as test-support]
            [millhouse.workflow :as workflow]
            [millstrand.test.alpha :as test-alpha]))

(defn- workflow-root []
  (test-alpha/spool-checkout-root "millhouse/workflow.clj"))

(defn- repository-root []
  (-> (workflow-root) .getParentFile .getParentFile))

(defn- lint-workflow-hook [source]
  (let [root (repository-root)
        dir (test-support/temp-dir "millhouse-workflow-hook")
        source-file (io/file dir "workflow_hook_test.clj")]
    (try
      (spit source-file source)
      (sh/sh "clj-kondo"
             "--repro"
             "--lint"
             (.getPath source-file)
             "--config"
             (.getPath (io/file (workflow-root) ".clj-kondo/config.edn"))
             :dir (.getPath root))
      (finally
        (test-support/delete-tree! dir)))))

(deftest workflow-defworkflow-hook-analyzes-computed-docs
  (let [prefix "(ns workflow-hook-test\n  \"Workflow hook test.\"\n  (:require [millhouse.workflow :as workflow]))\n\n"
        valid (lint-workflow-hook
               (str prefix
                    "(workflow/defworkflow sample\n"
                    "  (str \"Computed \" \"doc.\")\n"
                    "  {:entrypoints #{:start}}\n"
                    "  (workflow/workflow \"Sample\"))"))
        unresolved (lint-workflow-hook
                    (str prefix
                         "(workflow/defworkflow sample\n"
                         "  (missing-doc)\n"
                         "  {:entrypoints #{:start}}\n"
                         "  (workflow/workflow \"Sample\"))"))]
    (is (zero? (:exit valid))
        (str (:out valid) (:err valid)))
    (is (str/includes? (str (:out unresolved) (:err unresolved))
                       "Unresolved symbol: missing-doc"))))

(deftest workflow-defexecutor-hook-validates-declaration-shape
  (let [prefix "(ns workflow-hook-test\n  \"Workflow hook test.\"\n  (:require [millhouse.workflow :as workflow]))\n\n"
        valid (lint-workflow-hook
               (str prefix
                    "(workflow/defexecutor sample \"Sample.\" {} [_] nil)"))
        computed-doc (lint-workflow-hook
                      (str prefix
                           "(workflow/defworkflow sample "
                           "(str \"Computed\" \".\") {} nil)"))]
    (testing "valid declarations remain lintable"
      (is (zero? (:exit valid))
          (str (:out valid) (:err valid)))
      (is (zero? (:exit computed-doc))
          (str (:out computed-doc) (:err computed-doc))))
    (doseq [[description declaration expected-value]
            [["missing name" "(workflow/defexecutor)" "(workflow/defexecutor)"]
             ["invalid name"
              "(workflow/defexecutor :bad \"Bad.\" {} [_] nil)"
              ":bad"]
             ["missing argv"
              "(workflow/defexecutor missing-argv \"Bad.\" {})"
              "(workflow/defexecutor missing-argv \"Bad.\" {})"]
             ["missing body"
              "(workflow/defexecutor missing-body \"Bad.\" {} [_])"
              "nil"]]]
      (let [result (lint-workflow-hook (str prefix declaration))
            output (str (:out result) (:err result))]
        (testing description
          (is (pos? (:exit result)) output)
          (is (str/includes? output "Invalid workflow/defexecutor declaration")
              output)
          (is (str/includes? output expected-value) output)
          (is (str/includes? output
                             "(defexecutor name doc options argv & body)")
              output))))))

(deftest workflow-exported-kondo-contract-is-on-consumer-classpath
  (testing "the consolidated Workflow root publishes resources"
    (is (some #(= "resources" %)
              (:paths (edn/read-string (slurp (io/file (workflow-root) "deps.edn")))))))
  (testing "a consumer resolves the Workflow-owned export and hook"
    (let [config (io/resource
                  "clj-kondo.exports/millhouse/workflow/config.edn")
          hook (io/resource
                "clj-kondo.exports/millhouse/workflow/hooks/millhouse/workflow.clj_kondo")
          config-data (some-> config slurp edn/read-string)]
      (is config)
      (is hook)
      (is (= 'clojure.core/def
             (get-in config-data [:lint-as 'millhouse.workflow/defworkflow])))
      (is (= 'clojure.core/def
             (get-in config-data [:lint-as 'millhouse.workflow/defexecutor])))
      (is (= 'clojure.core/def
             (get-in config-data [:lint-as 'millhouse.workflow/defworkflow!])))
      (is (= 'clojure.core/def
             (get-in config-data [:lint-as 'millhouse.workflow/defexecutor!])))
      (is (= 'hooks.millhouse.workflow/defworkflow
             (get-in config-data
                     [:hooks :analyze-call
                      'millhouse.workflow/defworkflow])))
      (is (= 'hooks.millhouse.workflow/defexecutor
             (get-in config-data
                     [:hooks :analyze-call
                      'millhouse.workflow/defexecutor])))
      (is (= 'hooks.millstrand/use-vars
             (get-in config-data
                     [:hooks :analyze-call
                      'millhouse.workflow/use-workflow!])))
      (is (= 'hooks.millstrand/use-vars
             (get-in config-data
                     [:hooks :analyze-call
                      'millhouse.workflow/use-executor!]))))))

(deftest workflow-spool-explains-public-input-shapes
  (let [contract (workflow/explain)]
    (is (= :workflow (:topic contract)))
    (is (= 'millhouse.workflow/checkpoint (get-in contract [:builders 'checkpoint])))
    (is (re-find #"millhouse.workflow/workflow" (get-in contract [:contract :spec])))
    (is (= :step (get-in contract [:step :topic])))
    (is (= :checkpoint (get-in contract [:checkpoint :topic])))
    (is (= :defer (:topic (workflow/explain :defer))))
    (is (thrown-with-msg? clojure.lang.ExceptionInfo #"Unknown workflow explain topic"
                          (workflow/explain :dispatch)))
    (is (= :definition (get-in contract [:definition :topic])))
    (is (= 'millhouse.workflow/defworkflow
           (get-in contract [:builders 'defworkflow])))
    (is (re-find #"millhouse.workflow/definition"
                 (get-in (workflow/explain :definition) [:contract :spec])))))

(deftest workflow-checkpoint-rejects-duplicate-choice-keys
  (is (thrown-with-msg? clojure.lang.ExceptionInfo #"choice keys must be unique"
                        (workflow/checkpoint :gate "Gate"
                                             :choices [{:key :abort :label "A"}
                                                       {:key :abort :label "B"}]))))

(deftest workflow-builders-reject-unknown-option-keys
  (testing "each builder and the choice map fail loudly on a mistyped option key"
    (is (thrown-with-msg? clojure.lang.ExceptionInfo #"Unknown workflow option keys"
                          (workflow/workflow "W" {:param {:x true}} (workflow/step :a "A" :self))))
    (is (thrown-with-msg? clojure.lang.ExceptionInfo #"Unknown workflow option keys"
                          (workflow/step :a "A" :self :depend-on [:b])))
    (is (thrown-with-msg? clojure.lang.ExceptionInfo #"Unknown workflow option keys"
                          (workflow/gate :a "A" :ci :dependson [:b])))
    (is (thrown-with-msg? clojure.lang.ExceptionInfo #"Unknown workflow option keys"
                          (workflow/checkpoint :a "A" :choicez [:x])))
    (is (thrown-with-msg? clojure.lang.ExceptionInfo #"Unknown workflow option keys"
                          (workflow/call :a 'x {} :dependson [:b])))
    (is (thrown-with-msg? clojure.lang.ExceptionInfo #"Unknown workflow option keys"
                          (workflow/checkpoint :a "A"
                                               :choices [{:key :ok :labl "Bad"}]))))
  (testing "ex-data carries the offending and allowed keys"
    (try
      (workflow/step :a "A" :self :depend-on [:b])
      (is false "expected step to throw")
      (catch clojure.lang.ExceptionInfo e
        (is (= [:depend-on] (:unknown (ex-data e))))
        (is (contains? (:allowed (ex-data e)) :depends-on))))))

(deftest workflow-step-and-gate-accept-final-instruction
  (let [render-instruction (fn [{:keys [feature]}] (str "Build " feature))]
    (is (= {:id :a
            :title "A"
            :depends-on [:prepare]
            :attributes {"workflow/instruction" render-instruction}}
           (workflow/step :a "A" :self
                          :depends-on [:prepare]
                          render-instruction)))
    (is (= {:id :ci
            :title "CI"
            :attributes {"workflow/instruction" "Wait for CI."
                         "workflow/gate" "ci"}}
           (workflow/gate :ci "CI" :ci "Wait for CI.")))))

(deftest workflow-step-and-gate-reject-duplicate-instruction
  (doseq [attributes [{"workflow/instruction" "Attribute instruction."}
                      {:workflow/instruction "Attribute instruction."}]]
    (is (thrown-with-msg?
         clojure.lang.ExceptionInfo
         #"cannot be authored both positionally and in :attributes"
         (workflow/step :a "A" :self
                        :attributes attributes
                        "Positional instruction."))))
  (is (thrown-with-msg?
       clojure.lang.ExceptionInfo
       #"instruction must be a non-blank string or rendering function"
       (workflow/gate :ci "CI" :ci ""))))

(deftest workflow-step-requires-self-waiter
  (testing "only :self is accepted; any other waiter fails loudly, directing to gate"
    (is (= {:id :a :title "A"} (workflow/step :a "A" :self)))
    (is (thrown-with-msg? clojure.lang.ExceptionInfo #"Step waiter must be :self.*use gate"
                          (workflow/step :a "A" :ci)))
    (is (thrown-with-msg? clojure.lang.ExceptionInfo #"Step waiter must be :self.*use gate"
                          (workflow/step :a "A" :agent)))
    (try
      (workflow/step :a "A" :ci)
      (is false "expected step to throw on a non-:self waiter")
      (catch clojure.lang.ExceptionInfo e
        (is (= :ci (:waiter (ex-data e))))))))

(deftest workflow-gate-rejects-self-waiter
  (is (thrown-with-msg? clojure.lang.ExceptionInfo #"Gate waiter must be.*other than :self"
                        (workflow/gate :handoff "Hand off" :self)))
  (try
    (workflow/gate :handoff "Hand off" :self)
    (is false "expected gate to reject :self")
    (catch clojure.lang.ExceptionInfo e
      (is (= :self (:waiter (ex-data e)))))))

(deftest workflow-gate-rejects-malformed-waiters
  (doseq [bad [42 nil "" "  " [:ci]]]
    (is (thrown-with-msg? clojure.lang.ExceptionInfo #"Gate waiter must be a keyword, symbol, or non-blank string"
                          (workflow/gate :handoff "Hand off" bad))
        (pr-str bad))))

(deftest workflow-self-step-carries-no-gate-attribute
  ;; :self steps compile identically to the old bare steps: zero graph churn
  (let [definition (workflow/workflow "Self step" (workflow/step :a "Do A" :self))
        payload (workflow/compile definition)
        strand (first (filter #(= :a (:ref %)) (:strands payload)))]
    (is (= "step" (get-in strand [:attributes "workflow/role"])))
    (is (not (contains? (:attributes strand) "workflow/gate")))))

(deftest workflow-step-view-reads-keyword-and-string-keyed-attributes
  ;; strand attributes arrive keyword-keyed in-memory but string-keyed after a
  ;; JSON round-trip through the weaver; step-view reads through the single attr
  ;; boundary so both key forms yield the same view (archived workflow-engine review, R2)
  (let [keyworded (workflow/step-view
                   {:id "s1" :title "Do it" :state "active"
                    :attributes {:workflow/role "checkpoint"
                                 :workflow/choices ["a" "b"]
                                 :skills "clojure"}})
        stringed (workflow/step-view
                  {:id "s1" :title "Do it" :state "active"
                   :attributes {"workflow/role" "checkpoint"
                                "workflow/choices" ["a" "b"]
                                "skills" "clojure"}})]
    (is (= {:id "s1" :title "Do it" :state "active" :role "checkpoint"
            :choices ["a" "b"] :skills "clojure"}
           keyworded))
    (is (= keyworded stringed))))

(deftest workflow-spool-fails-loudly-on-bad-definitions
  (is (thrown-with-msg? clojure.lang.ExceptionInfo #"Invalid workflow definition"
                        (workflow/compile {:name "Bad steps" :steps {:not "a vector"}})))
  (is (thrown-with-msg? clojure.lang.ExceptionInfo #"Invalid workflow definition"
                        (workflow/compile {:name "Bad attributes" :attributes [] :steps []})))
  (is (thrown-with-msg? clojure.lang.ExceptionInfo #"Invalid workflow params"
                        (workflow/compile {:name "Bad params" :steps []} {"x" true})))
  (is (thrown-with-msg? clojure.lang.ExceptionInfo #"step ids must be unique"
                        (workflow/compile {:name "Duplicate" :steps [{:id :a :title "A"}
                                                                     {:id :a :title "Again"}]})))
  (is (thrown-with-msg? clojure.lang.ExceptionInfo #"Invalid workflow definition"
                        (workflow/compile {:name "Bad condition" :steps [{:id :a :title "A" :condition '(bad)}]}))))

(deftest workflow-checkpoint-kind-carries-the-decision-owner
  ;; workflow/checkpoint-kind is the canonical HITL signal: :human is the default
  ;; kind, and an :agent checkpoint is distinguished by this one attribute.
  (let [human (workflow/checkpoint :signoff "Sign off" :kind :human :choices [:approved])
        default-kind (workflow/checkpoint :also "Also decide" :choices [:approved])
        agent (workflow/checkpoint :route "Route" :kind :agent :choices [:go])]
    (is (= "human" (get-in human [:attributes "workflow/checkpoint-kind"])))
    (is (= "human" (get-in default-kind [:attributes "workflow/checkpoint-kind"])))
    (is (= "agent" (get-in agent [:attributes "workflow/checkpoint-kind"])))))

(deftest workflow-choice-input-rejects-the-removed-vector-declaration
  ;; A choice input names one whole-map spec. The per-key vector is gone, so the
  ;; builder refuses it through the public ::choices grammar with explain data
  ;; naming both spec-first shapes it would have accepted (TEN-003).
  (let [data (ex-data (try
                        (workflow/checkpoint :gate "Decide"
                                             :choices [{:key :abort
                                                        :input [{:key :reason :required true}]}])
                        (catch clojure.lang.ExceptionInfo e e)))
        paths (set (map :path (::s/problems (:explain data))))]
    (is (= :millhouse.workflow/choices (::s/spec (:explain data))))
    (is (contains? paths [:declaration :input :spec])
        "the qualified-keyword spec form is offered")
    (is (contains? paths [:declaration :input :declaration])
        "the {:spec :doc} declaration form is offered")))

(deftest workflow-choice-input-accepts-both-spec-first-shapes
  (doseq [input [::approval-input {:spec ::approval-input :doc "Why"}]]
    (is (some? (workflow/checkpoint :gate "Decide"
                                    :choices [{:key :approve :input input}]))
        (pr-str input))))

(deftest workflow-choice-input-rejects-a-spec-declaration-with-unknown-keys
  (is (thrown-with-msg? clojure.lang.ExceptionInfo #"Unknown workflow option keys"
                        (workflow/checkpoint :gate "Decide"
                                             :choices [{:key :abort
                                                        :input {:spec ::approval-input
                                                                :doccc "typo"}}]))))

(deftest workflow-choice-input-rejects-an-unqualified-spec-name
  ;; a spec identity must be resolvable, so a bare keyword is not one
  (is (thrown-with-msg? clojure.lang.ExceptionInfo #"Invalid workflow checkpoint choices"
                        (workflow/checkpoint :gate "Decide"
                                             :choices [{:key :abort :input :reason}]))))

(deftest workflow-checkpoint-rejects-next-and-revise-together
  (is (thrown-with-msg? clojure.lang.ExceptionInfo #":next and :revise are mutually exclusive"
                        (workflow/checkpoint :c "C"
                                             :choices [{:key :x :next :foo :revise {:params {}}}]))))

(deftest workflow-checkpoint-rejects-malformed-revise
  (is (thrown-with-msg? clojure.lang.ExceptionInfo #"Invalid workflow checkpoint choices"
                        (workflow/checkpoint :c "C"
                                             :choices [{:key :x :revise {:no-params true}}]))))

(defn exec-detail-a
  "Return the fixture executor's stall detail."
  [_step] {:by :a})

(s/def ::scope string?)

(s/def ::static-build-params (s/keys :req-un [::scope]))

(workflow/defworkflow static-build
  (str "Build " "an agreed scope.")
  {:entrypoints #{:start :continue}
   :param-spec ::static-build-params
   :defaults {:reviewer "agent"}}
  (workflow/workflow
   (fn [{:keys [scope]}] (str "Build " scope))
   (workflow/step :implement
                  (fn [{:keys [scope reviewer]}] (str "Implement " scope " for " reviewer))
                  :self)))

(defn- with-authoring-namespace [f]
  (let [ns-sym (gensym "millhouse.workflow-authoring-")]
    (try
      (binding [*ns* (create-ns ns-sym)]
        (clojure.core/refer 'clojure.core)
        (f ns-sym))
      (finally
        (remove-ns ns-sym)))))

(deftest defworkflow-evaluates-computed-doc-once
  (let [[calls value-doc var-doc]
        (with-authoring-namespace
          (fn [_]
            (eval
             '(let [calls (atom 0)
                    definition
                    (millhouse.workflow/defworkflow sample
                      (do (swap! calls inc) "Computed workflow doc.")
                      {:entrypoints #{:start}}
                      (millhouse.workflow/workflow "Computed doc"))]
                [@calls (:doc @definition) (:doc (meta definition))]))))]
    (is (= 1 calls))
    (is (= "Computed workflow doc." value-doc var-doc))))

(deftest defworkflow-defines-a-self-describing-var-and-stays-passive
  ;; Metadata is ordinary authoring data. Even inside a contribution collector,
  ;; an inert declaration must not select itself; publication has its own tests.
  (is (= "Build an agreed scope." (:doc static-build)))
  (is (= "Build an agreed scope." (:doc (meta #'static-build))))
  (is (= #{:start :continue} (:entrypoints static-build)))
  (is (= ::static-build-params (:param-spec static-build)))
  (is (= {:reviewer "agent"} (:defaults static-build)))
  (is (= [:implement] (mapv :id (:steps static-build))))
  (with-authoring-namespace
    (fn [ns-sym]
      (is (empty?
           (:contribution
            (test-alpha/collect-module-forms
             :test/passive-workflow ns-sym
             #(eval '(millhouse.workflow/defworkflow sample
                       "An inert declaration."
                       {:entrypoints #{:start}}
                       (millhouse.workflow/workflow "Sample"))))))))))

(deftest defworkflow-rejects-an-invalid-declaration
  (is (thrown-with-msg? clojure.lang.ExceptionInfo #"Invalid workflow options"
                        (workflow/static-definition
                         "doc" {:entrypoints #{:teleport}}
                         (workflow/workflow "W" (workflow/step :a "A" :self)))))
  (is (thrown-with-msg? clojure.lang.ExceptionInfo #"Invalid workflow options"
                        (workflow/static-definition
                         "doc" {:entrypoints #{}}
                         (workflow/workflow "W" (workflow/step :a "A" :self)))))
  (is (thrown-with-msg? clojure.lang.ExceptionInfo #"Unknown workflow option keys"
                        (workflow/static-definition
                         "doc" {:entrypoint #{:start}}
                         (workflow/workflow "W" (workflow/step :a "A" :self)))))
  (is (thrown-with-msg? clojure.lang.ExceptionInfo #":doc must be a non-blank string"
                        (workflow/static-definition
                         "  " {:entrypoints #{:start}}
                         (workflow/workflow "W" (workflow/step :a "A" :self))))))

(deftest workflow-builder-validates-the-complete-definition
  ;; The builder owns the whole assembled shape, so a malformed nested step or
  ;; choice fails at authoring time rather than at the pour.
  (is (thrown-with-msg? clojure.lang.ExceptionInfo #"Invalid workflow definition"
                        (workflow/workflow "W" {:id :untitled}))
      "a step map carrying :id is a step, and a step needs a title")
  (is (thrown-with-msg? clojure.lang.ExceptionInfo #"Unknown workflow option keys"
                        (workflow/workflow "W" {:title "no id"}))
      "a leading map without :id is read as the options map")
  (is (thrown-with-msg? clojure.lang.ExceptionInfo #"Invalid workflow options"
                        (workflow/workflow "W" {:defaults [:not :a :map]}
                                           (workflow/step :a "A" :self)))))

(s/def ::approval-note string?)

(s/def ::approval-input (s/keys :req-un [::approval-note]))

(def ^:private card-template
  "The unregistered template a spool publishes: it names its selection point
  without naming anyone else's workflow, and carries on afterwards."
  (workflow/workflow
   "Track a card"
   {:entrypoints #{:start :call}}
   (workflow/step :prepare "Prepare the card" :self)
   (workflow/defer :perform-work "Choose how this work will be performed"
                   :depends-on [:prepare])
   (workflow/step :record "Record the result" :self :depends-on [:perform-work])))

(defn- bound-card
  "Return the template bound to `targets`, as user code with both spools would."
  [targets]
  (workflow/bind-defers card-template {:perform-work targets}))

(def ^:private tracked-card (bound-card #{:wt-devflow :wt-spike}))

(deftest defer-declares-a-runtime-selected-returning-point
  ;; PROP-Dfr-001.S1: a defer is ordinary returning composition, so ordinary
  ;; topology may depend on it. What it may not be is conditional or multiplied.
  (let [definition (workflow/workflow
                    "Defer"
                    (workflow/defer :perform-work "Choose work")
                    (workflow/step :record "Record outcome" :self
                                   :depends-on [:perform-work]))]
    (is (= {:id :perform-work
            :title "Choose work"
            :attributes {"workflow/role" "defer"
                         "workflow/defer" "perform-work"}}
           (first (:steps definition))))
    (is (s/valid? ::workflow/defer-declaration (first (:steps definition))))
    (is (not (s/valid? ::workflow/defer-declaration {:id :bad :title "Bad"}))))
  (testing "every way of continuing past a defer now builds"
    (doseq [[label successor]
            [[:successor (workflow/step :after "After" :self :depends-on [:perform-work])]
             [:condition (workflow/step :maybe "Maybe" :self
                                        :depends-on [:perform-work] :condition :flag)]
             [:loop (workflow/step :each "Each" :self
                                   :depends-on [:perform-work] :loop {:count 2})]
             [:call (workflow/call :sub :wt-spike {} :depends-on [:perform-work])]
             [:checkpoint (workflow/checkpoint :pick "Pick" :depends-on [:perform-work]
                                               :choices [:a])]]]
      (testing (name label)
        (is (= [:perform-work (:id successor)]
               (mapv :id (:steps (workflow/workflow
                                  "Fine"
                                  (workflow/defer :perform-work "Choose")
                                  successor))))))))
  (testing "the point itself is unconditional: the builder rejects the opts outright"
    (doseq [key [:condition :loop]]
      (is (thrown-with-msg? clojure.lang.ExceptionInfo #"Unknown workflow option keys"
                            (workflow/defer :perform-work "Choose" key :flag)))))
  (testing "and a raw map that skipped the builder is caught at the definition"
    (doseq [[key value] [[:condition :flag] [:loop {:count 2}]]]
      (let [thrown (try (workflow/workflow
                         "Bad"
                         (assoc (workflow/defer :perform-work "Choose") key value))
                        (catch clojure.lang.ExceptionInfo e e))]
        (is (= :workflow/defer-not-static (:reason (ex-data thrown))))
        (is (= key (:key (ex-data thrown))))))))

(deftest bind-defers-owns-the-user-authority-boundary
  (testing "targets materialize in registered-name order whatever the author wrote"
    (is (= ["wt-devflow" "wt-spike"]
           (get-in (second (:steps (bound-card #{:wt-spike :wt-devflow})))
                   [:attributes "workflow/defer-workflows"]))))
  (testing "binding a name the definition never declared is a defect, not a new point"
    (let [thrown (try (workflow/bind-defers card-template {:nope #{:wt-spike}})
                      (catch clojure.lang.ExceptionInfo e e))]
      (is (= :workflow/defer-unknown (:reason (ex-data thrown))))
      (is (= [:perform-work] (:declared (ex-data thrown))))))
  (testing "an empty target set is a defer no worker can fill"
    (is (thrown-with-msg? clojure.lang.ExceptionInfo #"Invalid workflow defer bindings"
                          (workflow/bind-defers card-template {:perform-work #{}})))
    (is (s/valid? ::workflow/defer-bindings {:perform-work #{:wt-devflow}}))
    (is (not (s/valid? ::workflow/defer-bindings {:perform-work #{}}))))
  (testing "an unbound template describes itself but cannot pour"
    (is (= [nil ["wt-devflow" "wt-spike"]]
           [(:workflows (second (:steps (workflow/describe card-template))))
            (:workflows (second (:steps (workflow/describe tracked-card))))]))
    (let [thrown (try (workflow/compile card-template)
                      (catch clojure.lang.ExceptionInfo e e))]
      (is (= :workflow/defer-unbound (:reason (ex-data thrown))))
      (is (= :perform-work (:defer (ex-data thrown)))))))

(deftest the-removed-dispatch-and-transfer-surface-is-gone
  ;; PROP-Dfr-001.NG1: a pre-v1 clean break, so nothing survives as an alias.
  (doseq [removed '[dispatch dispatch! run-dispatch! continue! run-continue!
                    bind-handoffs]]
    (is (nil? (ns-resolve 'millhouse.workflow removed))
        (str removed " must not survive as a public var")))
  (doseq [removed [:millhouse.workflow/dispatch-declaration
                   :millhouse.workflow/dispatch-request
                   :millhouse.workflow/continue-request
                   :millhouse.workflow/handoff-bindings]]
    (is (nil? (s/get-spec removed)) (str removed " must not survive as a spec")))
  (is (= #{"step" "checkpoint" "defer"}
         (set (s/form :millhouse.workflow.view/role)))
      "no ready item ever reports a dispatch role again"))

(deftest executor-authoring-validates-closed-options
  (is (= {:stalled? 'millhouse.workflow-authoring-test/exec-detail-a
          :request-spec ::request}
         (workflow/executor-declaration
          {:request-spec ::request}
          'millhouse.workflow-authoring-test/exec-detail-a)))
  (is (thrown-with-msg? clojure.lang.ExceptionInfo #"Invalid workflow executor options"
                        (workflow/executor-declaration
                         {:unknown true}
                         'millhouse.workflow-authoring-test/exec-detail-a))))
