(ns millhouse.devflow-test
  "Tests Devflow as a collection of static Millstrand workflows and named discovery
  queries. Workflow execution itself belongs to millhouse.workflow."
  (:require [clojure.data.json :as json]
            [clojure.string :as str]
            [clojure.test :refer [deftest is testing]]
            [millhouse.devflow :as devflow]
            [millhouse.devflow.execution :as execution]
            [millhouse.devflow.cards :as cards]
            [millhouse.devflow.planning :as planning]
            [millhouse.devflow-equivalence :as equivalence]
            [millstrand.api.authoring.alpha :as authoring]
            [millstrand.api.cli.alpha :as cli-alpha]
            [millstrand.api.current.alpha :as current]
            [millstrand.api.graph.alpha :as graph]
            [millstrand.api.runtime.alpha :as runtime]
            [millstrand.api.spool.alpha :refer [attr-get]]
            [millstrand.api.weaver.alpha :as weaver]
            [millhouse.workflow :as workflow]
            [millstrand.test.alpha :as t]))

(def ^:private worktree-receipt
  {:repository "repo" :worktree "/tmp/feature-worktree" :branch "feature"})

(def ^:private merge-receipt
  {:repository "repo" :mainline "main" :merged-revision "abc123"
   :proposal-path "devflow/feat/carded/proposal.md" :merge-evidence "merge-record-1"})

(def ^:private stage-names
  #{:intake :proposal :land-proposal :decompose :review-cards :spec-plan
    :route-after-plan :tasks :run-afk-loop :run-afk-manual :run-afk-delegated
    :direct-implementation :agent-review :abort
    :author-task-strands :author-card-strands})

(def ^:private call-only-names
  #{:agent-review :author-task-strands :author-card-strands})

(defn- activate! [rt]
  (doseq [[key config] [[:millhouse/workflow {:ns 'millhouse.workflow}]
                        [:devflow {:ns 'millhouse.devflow
                                   :after [:millhouse/workflow]}]]]
    (let [result (runtime/module! rt key config)
          status (get-in result [:modules key :status])]
      (when-not (contains? #{:applied :unchanged} status)
        (throw (ex-info "Module activation failed" {:module key :result result})))))
  rt)

(defn with-runtime [f]
  (t/with-weaver-world [ctx {:storage :sqlite-memory}]
    (let [rt (activate! (:runtime ctx))]
      (current/with-runtime rt
        (f rt)))))

(deftest card-authoring-equivalence-rejects-target-divergence
  (testing "the executable verifier covers artifact, title, instruction, and binding regressions"
    (is (nil? (equivalence/-main)))))

(deftest devflow-contributes-static-definitions-not-a-runtime-facade
  (is (nil? (resolve 'millhouse.devflow/spool)))
  (is (nil? (resolve 'millhouse.devflow/contribute)))
  (doseq [sym '[start! ready ready-step complete! choose! advance!
                choice-detail choice-details current-root run-history squash-run!
                describe workflows commands]]
    (is (nil? (ns-resolve 'millhouse.devflow sym))
        (str sym " must remain a generic workflow operation"))))

(deftest devflow-declarations-carry-typed-selection-metadata
  (doseq [sym '[intake agent-review author-task-strands author-card-strands
                proposal land-proposal decompose review-cards route-after-plan
                spec-plan run-afk-loop run-afk-manual run-afk-delegated tasks
                direct-implementation abort devflow-runs devflow-ready
                devflow-tasks devflow]]
    (let [owner (cond
                  (contains? '#{intake proposal land-proposal} sym)
                  'millhouse.devflow.planning
                  (= sym 'review-cards) 'millhouse.devflow.cards
                  (contains? '#{route-after-plan spec-plan run-afk-loop run-afk-manual
                                run-afk-delegated tasks direct-implementation} sym)
                  'millhouse.devflow.execution
                  :else 'millhouse.devflow)
          var (ns-resolve owner sym)
          declaration (::authoring/declaration (meta var))]
      (is (var? var) (str sym " is a declaration Var"))
      (is (= :registry (:channel declaration))
          (str sym " uses the registry authoring channel"))
      (is (= (symbol (str owner) (name sym)) (:var declaration))
          (str sym " records its exact authored Var"))
      (is (= (:key declaration)
             (if (contains? #{'devflow-runs 'devflow-ready 'devflow-tasks} sym)
               (name sym)
               (if (= sym 'devflow) (name sym) (keyword sym))))
          (str sym " keeps its authored registry key")))))

(deftest module-publishes-the-complete-devflow-workflow-catalogue
  (with-runtime
    (fn [_rt]
      (is (= stage-names (set (keys (workflow/workflows)))))
      (is (= #{:start} (:entrypoints (workflow/resolve-workflow :intake))))
      (doseq [callee call-only-names]
        (is (= #{:call} (:entrypoints (workflow/resolve-workflow callee)))
            (str callee " is a call-only procedure")))
      (doseq [stage (disj stage-names :intake :agent-review
                          :author-task-strands :author-card-strands)]
        (is (= #{:continue :call}
               (:entrypoints (workflow/resolve-workflow stage)))
            (str stage " can route or return")))
      (is (= "Devflow proposal: <feature>"
             (:name (workflow/describe :proposal {:feature "<feature>"})))))))

(deftest generic-workflow-api-starts-and-drives-a-devflow-run
  (with-runtime
    (fn [_rt]
      (let [started (workflow/start! "search-filters" :intake
                                     {:feature "search-filters"
                                      :worktree-check "already-in-worktree-ok"})]
        (is (= ["Create or confirm feature worktree for search-filters"]
               (mapv :title (:ready started))))
        (is (= "devflow"
               (get-in (workflow/current-root "search-filters")
                       [:attributes :workflow/family])))
        (is (= "intake"
               (get-in (workflow/current-root "search-filters")
                       [:attributes :devflow/stage])))
        (workflow/choose! "search-filters" :already-in-worktree worktree-receipt)
        (is (= ["Capture user brief for search-filters"]
               (mapv :title (workflow/ready "search-filters"))))
        (workflow/complete! "search-filters")
        (workflow/choose! "search-filters" :proposal-ready)
        (is (= ["Inspect relevant RFCs, spikes, root specs, and active feature context for search-filters"]
               (mapv :title (workflow/ready "search-filters"))))))))

(deftest generic-workflow-api-enforces-devflow-choice-contracts
  (with-runtime
    (fn [_]
      (workflow/start! "abortable" :intake {:feature "abortable"})
      (is (thrown-with-msg? clojure.lang.ExceptionInfo
                            #"Value does not satisfy the named spec"
                            (workflow/choose! "abortable" :abort)))
      (is (= "create-or-confirm-worktree"
             (:checkpoint (workflow/ready-step "abortable"))))
      (let [result (workflow/choose! "abortable" :abort {:reason "superseded"})]
        (is (= ["Record abort for abortable: superseded"]
               (mapv :title (:ready result))))
        (is (true? (:done (workflow/complete! "abortable"))))))))

(deftest devflow-queries-surface-resumable-runs-and-actionable-work
  (with-runtime
    (fn [rt]
      (workflow/start! "paused" :intake {:feature "paused"})
      (workflow/start! "other" (workflow/workflow "Other"
                                        {:attributes {"workflow/family" "other"}}
                                        (workflow/step :work "Other work" :self)) {})
      (testing "the active-run query returns only Devflow roots"
        (is (= #{"paused"}
               (set (map #(get-in % [:attributes :workflow/run-id])
                         (weaver/list-query rt "devflow-runs" {}))))))
      (testing "the ready query follows a Devflow root's parent-of edges"
        (is (= #{"Create or confirm feature worktree for paused"}
               (set (map :title
                         (weaver/ready rt (graph/resolve-query rt "devflow-ready") {})))))
        (workflow/choose! "paused" :already-in-worktree worktree-receipt)
        (is (= #{"Capture user brief for paused"}
               (set (map :title
                         (weaver/ready rt (graph/resolve-query rt "devflow-ready") {}))))))
      (workflow/complete! "paused")
      (workflow/choose! "paused" :proposal-ready)
      (dotimes [_ 3] (workflow/complete! "paused"))
      (workflow/choose! "paused" :abort {:reason "stop"})
      (workflow/complete! "paused")
      (is (empty? (weaver/list-query rt "devflow-runs" {})))
      (is (empty? (weaver/ready rt (graph/resolve-query rt "devflow-ready") {}))))))

(deftest tasks-stage-defers-queue-authoring-to-a-pluggable-target
  (with-runtime
    (fn [_]
      (workflow/start! "queued" #'execution/tasks {:feature "queued"})
      (let [step (workflow/ready-step "queued")]
        (is (= "author-tasks" (:defer step)))
        (is (= ["author-task-strands"] (:workflows step))
            "the shipped binding allows exactly the strand-native target")
        (is (str/includes? (:instruction step) "strand workflow defer")))
      (testing "a defer cannot be completed and the target validates its params"
        (is (thrown? clojure.lang.ExceptionInfo (workflow/complete! "queued")))
        (is (thrown? clojure.lang.ExceptionInfo
                     (workflow/defer! "queued" :author-task-strands {}))))
      (let [filled (workflow/defer! "queued" :author-task-strands {:feature "queued"})]
        (is (= ["Author strand-native task queue for queued"]
               (mapv :title (:ready filled)))))
      (let [step (workflow/ready-step "queued")]
        (is (= "task strands" (:artifact step)))
        (is (str/includes? (:instruction step) "strand devflow guidance tasks")))
      (workflow/complete! "queued")
      (is (= ["Run agent review for queued task queue"]
             (mapv :title (workflow/ready "queued")))
          "the filled target returns into the declaring stage")
      (workflow/complete! "queued")
      (is (= "human-signoff-tasks" (:checkpoint (workflow/ready-step "queued")))))))

(deftest decompose-stage-defers-card-authoring-to-a-pluggable-target
  (with-runtime
    (fn [_]
      (workflow/start! "carded" #'devflow/decompose
                       {:feature "carded"
                        :card-reviewer "seat-a"
                        :card-set-reviewer "seat-b"})
      (let [step (workflow/ready-step "carded")]
        (is (= "author-cards" (:defer step)))
        (is (= ["author-card-strands"] (:workflows step))))
      (workflow/defer! "carded" :author-card-strands (assoc merge-receipt :feature "carded"))
      (is (= ["Author strand-native implementation cards for carded"]
             (mapv :title (workflow/ready "carded"))))
      (workflow/complete! "carded")
      (is (= "handoff-card-review" (:checkpoint (workflow/ready-step "carded"))))
      (testing "the review handoff takes one flat card-ref vector"
        (is (thrown-with-msg? clojure.lang.ExceptionInfo
                              #"Value does not satisfy the named spec"
                              (workflow/choose! "carded" :review
                                                {:epic-card {:id "e1" :title "Epic"}
                                                 :feature-cards [{:id "c1" :title "One"}]})))
        (let [result (workflow/choose! "carded" :review
                                       {:cards [{:id "c1" :title "One"}
                                                {:id "c2" :title "Two"}]})]
          (is (= #{"Focused review of card c1: One"
                   "Focused review of card c2: Two"}
                 (set (map :title (:ready result))))
              "focused reviews fan out over exactly the supplied refs"))))))

(deftest agent-gates-use-the-harnesses-executor-contract
  (with-runtime
    (fn [rt]
      (workflow/start! "agent-gates" #'cards/review-cards
                       {:feature "agent-gates"
                        :card-reviewer "reviewer"
                        :card-set-reviewer "oracle"
                        :cards [{:id "card-a" :title "A"}
                                {:id "card-b" :title "B"}]})
      (let [gates (workflow/ready-gates "agent-gates")
            strands (mapv #(weaver/show rt (:id %)) gates)]
        (is (= #{"agent"} (set (map :gate gates))))
        (is (= #{"reviewer"}
               (set (map #(attr-get % :harness/alias) strands))))
        (is (every? #(string? (attr-get % :harness/prompt)) strands))
        (doseq [gate gates]
          (workflow/complete! "agent-gates" {:step (:id gate)
                                             :by-identity "test"}))
        (let [set-gate (first (workflow/ready-gates "agent-gates"))
              set-strand (weaver/show rt (:id set-gate))]
          (is (= "agent" (:gate set-gate)))
          (is (= "oracle" (attr-get set-strand :harness/alias)))
          (is (string? (attr-get set-strand :harness/prompt))))))))

(deftest delegated-afk-gates-render-the-harness-contract
  (with-runtime
    (fn [rt]
      (workflow/start! "afk-contract" #'execution/run-afk-delegated
                       {:feature "afk-contract"
                        :delegate-harness "reviewer"
                        :delegate-cwd "/tmp/afk-contract"
                        :delegate-preamble "Use the approved task brief."
                        :tasks [{:id "override"
                                 :title "Override harness"
                                 :body "Use the task-level assignment."
                                 :harness "oracle"}
                                {:id "default"
                                 :title "Default harness"
                                 :body "Inherit the stage assignment."}]})
      (let [first-gate (first (workflow/ready-gates "afk-contract"))
            first-strand (weaver/show rt (:id first-gate))]
        (testing "the task-level override is an agent gate with cwd and prompt"
          (is (= "agent" (:gate first-gate)))
          (is (= "oracle" (attr-get first-strand :harness/alias)))
          (is (= "/tmp/afk-contract" (attr-get first-strand :harness/cwd)))
          (is (str/includes? (attr-get first-strand :harness/prompt)
                             "Use the approved task brief."))
          (is (str/includes? (attr-get first-strand :harness/prompt)
                             "Use the task-level assignment.")))
        (testing "the new mapping leaves no legacy agent-run attributes"
          (is (not-any? #(str/starts-with? (str %) "agent-run/")
                        (keys (:attributes first-strand)))))
        (workflow/complete! "afk-contract" {:step (:id first-gate)
                                            :by-identity "test"})
        (let [second-gate (first (workflow/ready-gates "afk-contract"))
              second-strand (weaver/show rt (:id second-gate))]
          (testing "the default harness is used by the next agent gate"
            (is (= "agent" (:gate second-gate)))
            (is (= "reviewer" (attr-get second-strand :harness/alias)))
            (is (= "/tmp/afk-contract" (attr-get second-strand :harness/cwd)))
            (is (str/includes? (attr-get second-strand :harness/prompt)
                               "Inherit the stage assignment.")))
          (is (not-any? #(str/starts-with? (str %) "agent-run/")
                        (keys (:attributes second-strand)))))))))

(deftest devflow-tasks-query-serves-the-strand-native-queue
  (with-runtime
    (fn [rt]
      (let [a (weaver/add! rt {:title "Task A"
                               :attributes {"devflow/task-type" "afk"
                                            "devflow/feature" "queued"}})]
        (weaver/add! rt {:title "Task B"
                         :attributes {"devflow/task-type" "afk"
                                      "devflow/feature" "queued"}
                         :edges [{:type "depends-on" :to (:id a)}]})
        (weaver/add! rt {:title "Task C"
                         :attributes {"devflow/task-type" "hitl"
                                      "devflow/feature" "queued"
                                      "hitl" "true"}})
        (weaver/add! rt {:title "Not a task"})
        (testing "list serves the whole open queue"
          (is (= #{"Task A" "Task B" "Task C"}
                 (set (map :title (weaver/list-query rt "devflow-tasks" {}))))))
        (testing "ready serves only the runnable frontier"
          (is (= #{"Task A" "Task C"}
                 (set (map :title
                           (weaver/ready rt (graph/resolve-query rt "devflow-tasks") {}))))))))))

(deftest guidance-and-artifact-metadata-remain-devflow-owned
  (is (= "devflow-spool" (devflow/dependency-sentinel)))
  (let [overview (devflow/guidance)]
    (is (str/includes? overview "**proposal**"))
    (is (str/includes? overview "devflow/specs/` is canonical for current contracts")))
  (with-runtime
    (fn [_]
      (workflow/start! "guided" #'planning/proposal {:feature "guided"})
      (workflow/complete! "guided")
      (let [step (workflow/ready-step "guided")]
        (is (= "proposal.md" (:artifact step)))
        (is (= "Run `strand devflow guidance proposal` for the authoring procedure, constraints, template, and validation checklist before writing proposal.md."
               (:instruction step)))))))

(defn- wire-value
  [value]
  (json/read-str (json/write-str value) :key-fn keyword))

(deftest guidance-markdown-expands-every-placeholder
  (doseq [k [:proposal :rfc :spec :plan :tasks :afk :decompose :finish-archive]]
    (let [doc (devflow/guidance k)]
      (is (not (str/includes? doc "{{"))
          (str k " has no unexpanded placeholders"))))
  (let [overview (devflow/guidance)]
    (is (not (str/includes? overview "{{"))))
  (testing "shared blocks land where their placeholders sit"
    (let [proposal (devflow/guidance :proposal)]
      (is (str/includes? proposal "# <Feature name> Proposal")
          "the proposal template is fenced into the guide")
      (is (str/includes? proposal "`PROP-Dwr-001.P1`")
          "the configuration-identification paragraph is rendered for PROP")
      (is (str/includes? proposal "| Document | Owns | Must not absorb | Lifetime |")
          "the ownership table is rendered"))))

(deftest the-proposal-guide-asks-the-author-to-show-the-change
  (let [proposal (devflow/guidance :proposal)]
    (testing "the template carries examples ahead of open questions"
      (is (str/includes? proposal "## PROP-<name>-<nnn>.P5 Examples"))
      (is (str/includes? proposal "## PROP-<name>-<nnn>.P6 Open questions")))
    (testing "the guide names the mediums an example can take"
      (doseq [medium ["invocations" "payloads" "mockup" "mermaid"]]
        (is (str/includes? proposal medium)
            (str "the examples guidance covers " medium))))
    (testing "examples are checked as part of validation"
      (is (str/includes? proposal
                         "- Problem, goals, non-goals, proposed scope, examples, and open questions are present")))))

(deftest the-devflow-op-serves-guidance-to-cli-workers
  (with-runtime
    (fn [rt]
      (let [entry (weaver/resolve-op rt 'devflow)]
        (is (= "devflow" (:name entry)))
        (is (= 'millhouse.devflow (:provenance entry)))
        (is (= :read (get-in entry [:arg-spec :subcommands "guidance" :hook-class])))
        (testing "the declared arg-spec routes guidance with an optional guide key"
          (let [parse (fn [argv] (cli-alpha/parse (:arg-spec entry) argv))]
            (is (= {:subcommand ["guidance"]} (parse ["guidance"])))
            (is (= {:subcommand ["guidance"] :guide "proposal"}
                   (parse ["guidance" "proposal"]))))))
      (testing "no argument serves the workspace overview"
        (let [result (devflow/devflow {:op/args {:subcommand ["guidance"]}})]
          (is (= "devflow guidance" (:operation result)))
          (is (= (devflow/guidance) (:guidance result)))
          (t/check-op-return! rt 'devflow {:subcommand ["guidance"]}
                              (wire-value result))))
      (testing "a guide key serves that artifact's live guide"
        (let [result (devflow/devflow
                      {:op/args {:subcommand ["guidance"] :guide "proposal"}})]
          (is (= "proposal" (:guide result)))
          (is (= (devflow/guidance :proposal) (:guidance result)))
          (t/check-op-return! rt 'devflow {:subcommand ["guidance"]}
                              (wire-value result))))
      (testing "an unknown guide fails loudly with the available keys"
        (let [data (try (devflow/devflow
                         {:op/args {:subcommand ["guidance"] :guide "nope"}})
                        (catch clojure.lang.ExceptionInfo e (ex-data e)))]
          (is (= :nope (:guide data)))
          (is (some #{:proposal} (:guides data))))))))

(defn -main [& _]
  (require 'millhouse.devflow-kanban-adapter-test
           'millhouse.devflow-receipts-test)
  (let [summary (clojure.test/run-tests 'millhouse.devflow-test
                                        'millhouse.devflow-kanban-adapter-test
                                        'millhouse.devflow-receipts-test)]
    (System/exit (if (pos? (+ (:fail summary) (:error summary))) 1 0))))
