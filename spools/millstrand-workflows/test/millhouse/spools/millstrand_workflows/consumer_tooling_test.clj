(ns millhouse.spools.millstrand-workflows.consumer-tooling-test
  "Contract tests for manual consumer tooling setup by repository style."
  (:require [clojure.spec.alpha :as s]
            [clojure.string :as str]
            [clojure.test :refer [deftest is testing]]
            [millhouse.spools.millstrand-workflows.consumer-tooling :as tooling]
            [millhouse.spools.millstrand-workflows.unchanged-proof :as unchanged-proof]
            [millhouse.spools.workflow :as workflow]))

(def ^:private params
  {:worktree "/tmp/consumer"
   :workspace "/tmp/consumer/.millstrand"
   :invocation-producer {:kind "pinned-remote-family"
                         :family "millhouse/spools"
                         :coordinate {:git/url "https://github.com/codethread/millhouse.spool.git"
                                      :git/sha "0123456789012345678901234567890123456789"}}})

(def ^:private workflow-host-params
  (assoc params :workspace "/tmp/workflow-host/.millstrand"))

(def ^:private captured-pending-conflict
  {:changed-roots
   [{:lib 'demo/root
     :previous-root "/tmp/demo-v1"
     :new-root "/tmp/demo-v2"}
    {:lib 'demo/other-root
     :previous-root "/tmp/other-v1"
     :new-root "/tmp/other-v2"}]
   :namespace-residuals
   [{:reason :root-repointed
     :namespace 'demo.ns
     :binding {:namespace 'demo.ns
               :root-lib 'demo/root
               :root "/tmp/demo-v1"
               :file "/tmp/demo-v1/src/demo/ns.clj"}
     :providers [{:namespace 'demo.ns
                  :root-lib 'demo/root
                  :root "/tmp/demo-v2"
                  :file "/tmp/demo-v2/src/demo/ns.clj"}]}
    {:reason :unledgered-loaded-namespace
     :namespace 'demo.other
     :providers [{:namespace 'demo.other
                  :root-lib 'demo/other-root
                  :root "/tmp/other-v2"
                  :file "/tmp/other-v2/src/demo/other.clj"}]}]})

(def ^:private captured-direct-refusal
  {:module/key 'demo/direct
   :status :refused
   :reason :hard-conflict
   :root-lib 'demo/root
   :root/outcome {:status :hard-conflict
                  :conflict captured-pending-conflict}})

(def ^:private captured-one-hop-wrapper
  {:module/key 'demo/one-hop
   :status :failed
   :reason :missing-dependency
   :dependency 'demo/direct
   :dependency/outcome captured-direct-refusal})

(def ^:private captured-multi-hop-wrapper
  {:module/key 'demo/multi-hop
   :status :skipped
   :reason :missing-dependency
   :dependency 'demo/one-hop
   :dependency/outcome captured-one-hop-wrapper})

(def ^:private captured-pending-refresh-fixture
  {:declared-prepared-changed-roots (:changed-roots captured-pending-conflict)
   :declared-prepared-conflict captured-pending-conflict
   :refresh
   {:status :partial
    :mode :full
    :modules
    {'demo/unchanged {:module/key 'demo/unchanged
                      :status :unchanged}
     'demo/direct captured-direct-refusal
     'demo/one-hop captured-one-hop-wrapper
     'demo/multi-hop captured-multi-hop-wrapper}}
   :runtime-status
   {:pending-generation
    {:status :pending
     :generation "generation-1"
     :diff captured-pending-conflict
     :approved-spools #{'demo/root 'demo/other-root}
     :remedy "recorded; takes effect at the next weaver generation"}}})

(def ^:private rejected-pending-evidence-fixtures
  [{:fixture :wrong-root-lib
    :refresh (update-in (:refresh captured-pending-refresh-fixture)
                        [:modules 'demo/direct :root-lib]
                        (constantly 'unrelated/root))
    :mutation-path [:modules 'demo/direct :root-lib]
    :required-text "reject any direct or terminal refusal whose `:root-lib` is outside or mismatched against the declared nonempty changed-root set"}
   {:fixture :empty-providers
    :refresh (assoc-in (:refresh captured-pending-refresh-fixture)
                       [:modules 'demo/direct :root/outcome :conflict
                        :namespace-residuals 0 :providers]
                       [])
    :mutation-path [:modules 'demo/direct :root/outcome :conflict
                    :namespace-residuals 0 :providers]
    :required-text "empty `:providers`"}
   {:fixture :mixed-applied
    :refresh (assoc-in (:refresh captured-pending-refresh-fixture)
                       [:modules 'demo/unchanged]
                       {:module/key 'demo/applied :status :applied})
    :mutation-path [:modules 'demo/unchanged]
    :required-text "`:applied` outcome"}
   {:fixture :mismatched-dependency
    :refresh (assoc-in (:refresh captured-pending-refresh-fixture)
                       [:modules 'demo/one-hop :dependency]
                       'demo/unrelated)
    :mutation-path [:modules 'demo/one-hop :dependency]
    :required-text "missing or mismatched dependency outcome"}
   {:fixture :unrelated-terminal
    :refresh (assoc-in (:refresh captured-pending-refresh-fixture)
                       [:modules 'demo/multi-hop :dependency/outcome
                        :dependency/outcome]
                       {:module/key 'demo/terminal :status :unchanged})
    :mutation-path [:modules 'demo/multi-hop :dependency/outcome
                    :dependency/outcome]
    :required-text "unrelated terminal"}
   {:fixture :top-level-module-key-mismatch
    :refresh (assoc-in (:refresh captured-pending-refresh-fixture)
                       [:modules 'demo/direct :module/key]
                       'demo/not-direct)
    :mutation-path [:modules 'demo/direct :module/key]
    :required-text "reject any missing or mismatched map-key identity"}
   {:fixture :direct-terminal-root-lib-outside-changed-roots
    :refresh (assoc-in (:refresh captured-pending-refresh-fixture)
                       [:modules 'demo/one-hop :dependency/outcome :root-lib]
                       'unrelated/root)
    :mutation-path [:modules 'demo/one-hop :dependency/outcome :root-lib]
    :required-text "reject any direct or terminal refusal whose `:root-lib` is outside or mismatched against the declared nonempty changed-root set"}
   {:fixture :invalid-unchanged-refresh
    :refresh (assoc (:refresh captured-pending-refresh-fixture)
                    :status :applied
                    :active-root-coordinate-changed? true
                    :pending-generation {:status :pending})
    :mutation-path [:status]
    :required-text "Reject `:status :applied`"}])

(def ^:private routes
  [[:app #'tooling/configure-consumer-tooling-app]
   [:spool #'tooling/configure-consumer-tooling-spool]
   [:clojure-app #'tooling/configure-consumer-tooling-clojure-app]])

(defn- definition [var]
  @var)

(defn- step [definition id]
  (some #(when (= id (:id %)) %) (:steps definition)))

(defn- instruction [definition id]
  ((get-in (step definition id) [:attributes "workflow/instruction"]) params))

(defn- instruction-with-params [definition id instruction-params]
  ((get-in (step definition id) [:attributes "workflow/instruction"])
   instruction-params))

(defn- precedes?
  [text first-needle second-needle]
  (let [first-index (str/index-of text first-needle)
        second-index (str/index-of text second-needle)]
    (and (some? first-index)
         (some? second-index)
         (< first-index second-index))))

(deftest params-require-an-explicit-consumer-world-and-producer-coordinate
  (is (s/valid? ::tooling/consumer-tooling-params params))
  (is (s/valid? ::tooling/consumer-tooling-params
                (assoc params :inherited-pre-refresh-evidence true)))
  (is (not (s/valid? ::tooling/consumer-tooling-params
                     (assoc params :inherited-pre-refresh-evidence "true"))))
  (is (not (s/valid? ::tooling/consumer-tooling-params
                     (dissoc params :workspace))))
  (is (not (s/valid? ::tooling/consumer-tooling-params
                     (dissoc params :invocation-producer))))
  (is (s/valid? ::tooling/invocation-producer
                (:invocation-producer params)))
  (is (s/valid? ::tooling/invocation-producer
                {:kind "local-self-root"
                 :family "millhouse/spools"
                 :root "spools/millstrand-workflows"
                 :coordinate {:local/root "/tmp/millhouse"}}))
  (is (not (s/valid? ::tooling/invocation-producer
                     (assoc (:invocation-producer params)
                            :coordinate {:git/url "https://example.test/millhouse.git"
                                         :git/sha "18f2f43"}))))
  (is (not (s/valid? ::tooling/consumer-tooling-params
                     (assoc params :worktree " ")))))

(deftest consumer-world-cannot-be-confused-with-workflow-host
  (is (not (s/valid? ::tooling/consumer-tooling-params workflow-host-params)))
  (doseq [[style definition-var] routes]
    (testing (name style)
      (let [route (definition definition-var)
            prepare-text (instruction-with-params route :prepare-bootstrap-kondo
                                                  workflow-host-params)
            bootstrap-text (instruction-with-params route :bootstrap-kondo
                                                    workflow-host-params)
            inspect-text (instruction-with-params (definition #'tooling/configure-consumer-tooling)
                                                  :inspect-repository
                                                  workflow-host-params)
            handover-text (instruction-with-params route :handover
                                                   workflow-host-params)]
        (is (str/includes? prepare-text
                           "derive the target consumer world as `/tmp/consumer/.millstrand`"))
        (is (str/includes? prepare-text
                           "{:worktree \"/tmp/consumer\" :workspace \"/tmp/consumer/.millstrand\"}"))
        (is (str/includes? prepare-text
                           "never substitute the disposable workflow-host workspace"))
        (is (str/includes? inspect-text "/tmp/consumer/.millstrand"))
        (is (str/includes? handover-text "/tmp/consumer/.millstrand"))
        (is (not (str/includes? prepare-text "/tmp/workflow-host/.millstrand")))
        (is (not (str/includes? bootstrap-text "/tmp/workflow-host/.millstrand")))
        (is (not (str/includes? inspect-text "/tmp/workflow-host/.millstrand")))
        (is (not (str/includes? handover-text "/tmp/workflow-host/.millstrand")))))))

(deftest unchanged-refresh-proof-uses-pre-refresh-roots-and-runtime-evidence
  (let [{:keys [refresh runtime-status pre-refresh-status current-roots]}
        unchanged-proof/refresh-fixture]
    (is (= :unchanged (:status refresh)))
    (is (= :full (:mode refresh)))
    (is (unchanged-proof/valid? unchanged-proof/refresh-fixture))
    (is (every? #(= :unchanged (:status %)) (vals (:modules refresh))))
    (is (nil? (:pending-generation runtime-status)))
    (is (= "/tmp/demo" (get current-roots ['demo/family 'demo/root])))
    (is (map? (:families pre-refresh-status)))
    (is (map? (get-in pre-refresh-status [:families 'demo/family :roots])))
    (is (= :synced (get-in pre-refresh-status [:families 'demo/family :roots
                                               'demo/root :status])))
    (is (= "/tmp/demo"
           (get-in pre-refresh-status [:families 'demo/family :roots
                                       'demo/root :sync :root])))
    (is (not (contains? pre-refresh-status :status)))))

(deftest unchanged-refresh-proof-rejects-every-requested-malformed-shape
  (let [text (instruction (definition #'tooling/configure-consumer-tooling-app)
                          :prove-invocation-producer)]
    (doseq [{:keys [fixture proof]} unchanged-proof/negative-fixtures]
      (testing (name fixture)
        (is (not (unchanged-proof/valid? proof)))))
    (doseq [needle ["missing or mismatched map-key identity"
                    "no `:error`, `:reason`, refusal, `:root/outcome`, `:dependency`, or `:dependency/outcome`"
                    "pre-refresh spool-status evidence"
                    "cover exactly that set"
                    "no missing, extra, or mismatched family/root"
                    "Every intended root outcome must have `:status :synced`, a `:sync` map"
                    "`[family root] -> sync.root` map as pre-refresh current-root evidence"
                    "Reject failed, conflicted, source-reload, partial, missing, extra"
                    "absent, blank, or otherwise invalid `:sync.root`"]]
      (is (str/includes? text needle) needle))))

(deftest repository-style-choice-is-explicit
  (let [parent (definition #'tooling/configure-consumer-tooling)
        checkpoint (step parent :repository-style)
        choices (get-in checkpoint [:attributes "workflow/choice-details"])
        inspect-text (instruction parent :inspect-repository)]
    (is (= [:inspect-repository] (:depends-on checkpoint)))
    (is (= ":configure-consumer-tooling-app"
           (get-in choices ["app" "next"])))
    (is (= ":configure-consumer-tooling-spool"
           (get-in choices ["spool" "next"])))
    (is (= ":configure-consumer-tooling-clojure-app"
           (get-in choices ["clojure-app" "next"])))
    (is (nil? (get-in choices ["unsupported" "next"])))
    (is (str/includes? inspect-text "spool status"))
    (is (str/includes? inspect-text "app"))
    (is (str/includes? inspect-text "spool"))
    (is (str/includes? inspect-text "clojure-app"))))

(deftest inspection-acquires-exact-consumer-weaver-before-classification
  (let [parent (definition #'tooling/configure-consumer-tooling)
        inspect-text (instruction parent :inspect-repository)]
    (is (str/includes? inspect-text
                       "First run the exact command `strand --workspace /tmp/consumer/.millstrand spool status`"))
    (is (str/includes? inspect-text
                       "If the result is `mill/no-selected-weaver`"))
    (is (str/includes? inspect-text
                       "`mill weaver start --workspace /tmp/consumer/.millstrand`"))
    (is (str/includes? inspect-text "record the start command, PID"))
    (is (str/includes? inspect-text "Weaver/root identities"))
    (is (str/includes? inspect-text "Rerun the same exact status command"))
    (is (str/includes? inspect-text "read shared and local spool approvals"))
    (is (str/includes? inspect-text "inspect the structured result"))
    (is (str/includes? inspect-text "one exact intended family/root set"))
    (is (str/includes? inspect-text "cover exactly that set"))
    (is (str/includes? inspect-text "`[family root] -> sync.root` map"))
    (is (str/includes? inspect-text "pre-refresh current-root evidence"))
    (is (str/includes? inspect-text
                       "Do not edit a coordinate or call runtime refresh during this step"))
    (is (str/includes? inspect-text "Never start or restart a canonical or already-running Weaver"))
    (is (precedes? inspect-text
                   "First run the exact command"
                   "If the result is `mill/no-selected-weaver`"))
    (is (precedes? inspect-text
                   "If the result is `mill/no-selected-weaver`"
                   "`mill weaver start --workspace /tmp/consumer/.millstrand`"))
    (is (precedes? inspect-text
                   "`mill weaver start --workspace /tmp/consumer/.millstrand`"
                   "Rerun the same exact status command"))
    (is (precedes? inspect-text
                   "Rerun the same exact status command"
                   "read shared and local spool approvals"))
    (is (precedes? inspect-text
                   "read shared and local spool approvals"
                   "one exact intended family/root set"))
    (is (precedes? inspect-text
                   "pre-refresh current-root evidence"
                   "Do not edit a coordinate or call runtime refresh"))
    (is (precedes? inspect-text
                   "Do not edit a coordinate or call runtime refresh"
                   "Choose `app`"))))

(deftest producer-alignment-fully-applied-refresh-continues
  (doseq [[style definition-var] routes]
    (testing (name style)
      (let [text (instruction (definition definition-var)
                              :prove-invocation-producer)]
        (is (str/includes? text "run exactly `(runtime/refresh! (current/runtime))`"))
        (is (str/includes? text "fully applied `:status :applied` result continues normally"))
        (is (precedes? text "run exactly `(runtime/refresh! (current/runtime))`"
                       "fully applied `:status :applied` result continues normally"))))))

(deftest producer-alignment-unchanged-refresh-is-the-no-coordinate-proof
  (doseq [[style definition-var] routes]
    (testing (name style)
      (let [text (instruction (definition definition-var)
                              :prove-invocation-producer)]
        (is (str/includes? text "If no coordinate changed, run exactly"))
        (is (str/includes? text
                           "the read-only `(runtime/status (current/runtime))` result"))
        (is (str/includes? text
                           "top-level `:status :unchanged`, every entry in its `:modules` map has the exact unchanged shape above"))
        (is (str/includes? text "runtime status has `:pending-generation nil`"))
        (is (str/includes? text "cover exactly that set"))
        (is (str/includes? text "exact pre-refresh current-root evidence"))
        (is (str/includes? text "without another spool-status command"))
        (is (not (str/includes? text
                                "strand --workspace <workspace> spool status")))
        (is (not (str/includes? text ":status :no-change")))
        (is (precedes? text "pre-refresh spool-status evidence"
                       "runtime/refresh!"))
        (is (precedes? text "If no coordinate changed, run exactly"
                       "top-level `:status :unchanged`"))
        (is (precedes? text "top-level `:status :unchanged`"
                       "runtime status has `:pending-generation nil`"))))))

(deftest producer-alignment-unchanged-refresh-rejects-other-outcomes
  (let [text (instruction (definition #'tooling/configure-consumer-tooling-app)
                          :prove-invocation-producer)]
    (doseq [needle ["Reject `:status :applied`, `:status :partial`, `:status :refused`"
                    "any refresh error"
                    "any other module status"
                    "any non-nil pending generation"
                    "any absent, contradictory, or malformed result loudly"]]
      (is (str/includes? text needle) needle))))

(deftest producer-alignment-supported-pending-repoint-continues-tooling
  (let [text (instruction (definition #'tooling/configure-consumer-tooling-app)
                          :prove-invocation-producer)]
    (is (str/includes? text "pending next-generation result has top-level `:status :partial`"))
    (is (str/includes? text "nonempty `:modules` outcome map"))
    (is (str/includes? text "Every module outcome must be exactly one of three forms"))
    (is (str/includes? text "unchanged module with `:status :unchanged` and no `:error`, `:reason`"))
    (is (str/includes? text "direct `:status :refused` and `:reason :hard-conflict`"))
    (is (str/includes? text "`:status :failed` or `:status :skipped` with `:reason :missing-dependency`"))
    (is (str/includes? text "finite acyclic chain"))
    (is (str/includes? text "nested `:dependency/outcome` `:module/key` at every hop"))
    (is (str/includes? text "exact same shared classification"))
    (is (str/includes? text "`:applied` outcome"))
    (is (str/includes? text "unrelated terminal"))
    (is (str/includes? text "missing or mismatched dependency outcome"))
    (is (str/includes? text "root outcome's `:conflict` must contain exactly `:changed-roots` and `:namespace-residuals`"))
    (is (str/includes? text "`:changed-roots` must equal that exact declared set"))
    (is (str/includes? text "Each changed-root entry must have exactly `:lib`, `:previous-root`, and `:new-root`"))
    (is (str/includes? text
                       "Resolve every changed `:lib` to exactly one family/root in the pre-refresh current-root evidence"))
    (is (str/includes? text
                       "`:previous-root` must equal that recorded `sync.root`"))
    (is (str/includes? text
                       "changed-root set must equal the prepared-root set exactly"))
    (is (str/includes? text
                       "Reject any missing, extra, ambiguous, or mismatched current, changed, or prepared root"))
    (is (str/includes? text "every allowed residual must have a nonempty `:namespace` and nonempty `:providers`"))
    (is (str/includes? text "residual must have exactly one old `:binding`"))
    (is (str/includes? text "provider must use `:root-lib` equal to the matched changed-root `:lib`"))
    (is (str/includes? text "Every binding and provider `:namespace` must equal the residual namespace"))
    (is (str/includes? text "Every binding and provider `:file` path must be nonempty"))
    (is (str/includes? text "empty `:providers` collection"))
    (is (str/includes? text "matching `:pending-generation` with exactly `:status`, `:generation`, `:diff`, `:approved-spools`, and `:remedy`"))
    (is (str/includes? text "matching `:pending-generation`"))
    (is (str/includes? text "Record the current generation, prepared generation"))
    (is (str/includes? text "continue tooling against the prepared roots"))
    (is (str/includes? text "without restarting or claiming adoption"))
    (is (str/includes? text "cover exactly that set"))))

(deftest pending-roots-must-match-pre-refresh-and-runtime-evidence
  (is (unchanged-proof/pending-valid? unchanged-proof/pending-fixture))
  (doseq [{:keys [fixture proof]} unchanged-proof/pending-negative-fixtures]
    (testing (name fixture)
      (is (not (unchanged-proof/pending-valid? proof))))))

(deftest producer-alignment-unsupported-partial-fails-loudly
  (let [text (instruction (definition #'tooling/configure-consumer-tooling-app)
                          :prove-invocation-producer)]
    (is (str/includes? text "Reject an empty `:providers` collection and every other empty or vacuous"))
    (is (str/includes? text "duplicate, missing, extra, unrelated, or mixed mappings"))
    (is (str/includes? text "wrong `:root-lib`, wrong root, wrong namespace, or wrong provider path"))
    (is (str/includes? text "Any other partial, refused, per-root failure"))
    (is (str/includes? text "missing pending record, refresh error, or ambiguous ownership fails loudly"))
    (is (str/includes? text "without restarting or claiming adoption"))
    (is (not (str/includes? text "restart to apply the pending generation")))))

(deftest captured-pending-refresh-shape-drives-exact-evidence-contract
  (let [fixture captured-pending-refresh-fixture
        refresh (:refresh fixture)
        unchanged (get-in refresh [:modules 'demo/unchanged])
        module (get-in refresh [:modules 'demo/direct])
        one-hop (get-in refresh [:modules 'demo/one-hop])
        multi-hop (get-in refresh [:modules 'demo/multi-hop])
        root-outcome (:root/outcome module)
        conflict (:conflict root-outcome)
        repointed (first (:namespace-residuals conflict))
        unledgered (second (:namespace-residuals conflict))
        pending-diff (get-in fixture [:runtime-status :pending-generation :diff])
        text (instruction (definition #'tooling/configure-consumer-tooling-app)
                          :prove-invocation-producer)]
    (is (= :partial (:status refresh)))
    (is (= 4 (count (:modules refresh))))
    (is (every? (fn [[module-key outcome]]
                  (= module-key (:module/key outcome)))
                (:modules refresh)))
    (is (= :unchanged (:status unchanged)))
    (is (every? nil? (map unchanged [:error :reason :root/outcome
                                     :dependency :dependency/outcome])))
    (is (= :refused (:status module)))
    (is (= :hard-conflict (:reason module)))
    (is (= 'demo/direct (:module/key module)))
    (is (= 'demo/root (:root-lib module)))
    (is (= :hard-conflict (:status root-outcome)))
    (is (= captured-pending-conflict conflict))
    (is (= (:declared-prepared-changed-roots fixture)
           (:changed-roots conflict)))
    (is (= (:declared-prepared-conflict fixture) conflict))
    (is (= captured-direct-refusal (:dependency/outcome one-hop)))
    (is (= (:dependency one-hop)
           (get-in one-hop [:dependency/outcome :module/key])))
    (is (= captured-one-hop-wrapper (:dependency/outcome multi-hop)))
    (is (= (:dependency multi-hop)
           (get-in multi-hop [:dependency/outcome :module/key])))
    (is (= captured-direct-refusal
           (get-in multi-hop [:dependency/outcome :dependency/outcome])))
    (is (= (:dependency one-hop)
           (get-in multi-hop [:dependency/outcome :dependency/outcome :module/key])))
    (is (= conflict pending-diff))
    (is (= #{:changed-roots :namespace-residuals} (set (keys pending-diff))))
    (is (seq (:namespace-residuals conflict)))
    (is (= :root-repointed (:reason repointed)))
    (is (= 'demo/root (get-in repointed [:binding :root-lib])))
    (is (seq (:providers repointed)))
    (is (= :unledgered-loaded-namespace (:reason unledgered)))
    (is (nil? (:binding unledgered)))
    (is (seq (:providers unledgered)))
    (is (= :pending (get-in fixture [:runtime-status :pending-generation :status])))
    (doseq [needle ["nonempty `:modules` outcome map"
                    "nonempty prepared conflict classification"
                    "exact declared set"
                    "nonempty `:providers`"
                    "exactly one old `:binding`"
                    "use `:root-lib` equal to the matched changed-root `:lib`"
                    "Every binding and provider `:namespace`"
                    "Every binding and provider `:file` path"
                    "runtime status has `:pending-generation nil`"]]
      (is (str/includes? text needle) needle))))

(deftest rejected-pending-fixtures-name-the-required-failures
  (let [text (instruction (definition #'tooling/configure-consumer-tooling-app)
                          :prove-invocation-producer)]
    (doseq [{:keys [fixture refresh mutation-path required-text]}
            rejected-pending-evidence-fixtures]
      (testing (name fixture)
        (is (map? refresh))
        (is (some? (get-in (:refresh captured-pending-refresh-fixture)
                           mutation-path))
            (str "mutation path must exist in accepted fixture: " mutation-path))
        (is (not= (get-in (:refresh captured-pending-refresh-fixture)
                          mutation-path)
                  (get-in refresh mutation-path))
            (str "fixture must mutate accepted path: " mutation-path))
        (is (str/includes? text required-text) required-text)))
    (is (str/includes? text "top-level `:status :unchanged`"))
    (is (str/includes? text "any absent, contradictory, or malformed result loudly"))))

(deftest every-route-is-an-ordinary-manual-sequence
  (doseq [[style definition-var] routes]
    (testing (name style)
      (let [route (definition definition-var)
            steps (:steps route)
            payload (workflow/compile route params)]
        (is (= [:prepare-bootstrap-kondo
                :prove-invocation-producer
                :bootstrap-kondo
                :align-tools-deps
                :configure-lsp
                :configure-lint
                :verify-tests
                :verify-weaver
                :handover]
               (mapv :id steps)))
        (is (= [nil
                [:prepare-bootstrap-kondo]
                [:prove-invocation-producer]
                [:bootstrap-kondo]
                [:align-tools-deps]
                [:configure-lsp]
                [:configure-lint]
                [:verify-tests]
                [:verify-weaver]]
               (mapv :depends-on steps)))
        (is (every? #(nil? (get-in % [:attributes "workflow/gate"])) steps))
        (is (every? #(nil? (get-in % [:attributes "shell/argv"])) steps))
        (is (every? #(nil? (:procedure %)) steps))
        (is (not (str/includes? (pr-str payload) "workflow/gate")))))))

(deftest every-standalone-route-bootstraps-before-lint
  (doseq [[style definition-var] routes]
    (testing (name style)
      (let [route (definition definition-var)
            steps (:steps route)
            bootstrap-step (step route :bootstrap-kondo)
            lint (step route :configure-lint)]
        (is (= :prepare-bootstrap-kondo (:id (first steps))))
        (is (nil? (:procedure bootstrap-step)))
        (let [prepare-text (instruction route :prepare-bootstrap-kondo)]
          (is (str/includes? prepare-text
                             "separate child `bootstrap-kondo` run"))
          (is (str/includes? prepare-text "`capture-spool-status`"))
          (is (str/includes? prepare-text
                             "Stop the child before completing any route `prepare` step"))
          (is (str/includes? prepare-text
                             "`[family root] -> sync.root` map"))
          (is (str/includes? prepare-text
                             "Coordinate alignment and refresh may start only after")))
        (let [producer-text (instruction route :prove-invocation-producer)]
          (is (str/includes? producer-text "exact invocation-producer contract"))
          (is (str/includes? producer-text "pinned-remote-family"))
          (is (str/includes? producer-text "local-self-root"))
          (is (str/includes? producer-text "selected `spools.edn` activation"))
          (is (str/includes? producer-text "every relevant Millhouse root"))
          (is (str/includes? producer-text "one version-coherent family coordinate"))
          (is (str/includes? producer-text "manually edit only the applicable"))
          (is (str/includes? producer-text "record the before and after values"))
          (is (str/includes? producer-text "Stop only when metadata is missing"))
          (is (str/includes? producer-text "Do not infer the running workflow SHA"))
          (is (str/includes? producer-text "automate these edits")))
        (let [bootstrap-text (instruction route :bootstrap-kondo)]
          (is (str/includes? bootstrap-text "resume the exact child"))
          (is (str/includes? bootstrap-text
                             "equal the repository-inspection evidence exactly"))
          (is (str/includes? bootstrap-text
                             "preceding producer refresh result and runtime status"))
          (is (str/includes? bootstrap-text
                             "Do not run, retry, or otherwise re-enter `spool status` after refresh"))
          (is (not (str/includes? bootstrap-text
                                  "strand --workspace /tmp/consumer/.millstrand spool status")))
          (is (not (str/includes? bootstrap-text
                                  "mill weaver start --workspace /tmp/consumer/.millstrand"))))
        (is (= [:prepare-bootstrap-kondo]
               (:depends-on (step route :prove-invocation-producer))))
        (is (= [:prove-invocation-producer]
               (:depends-on (step route :bootstrap-kondo))))
        (is (= [:bootstrap-kondo]
               (:depends-on (step route :align-tools-deps))))
        (is (= [:configure-lsp] (:depends-on lint)))
        (let [lint-text (instruction route :configure-lint)]
          (is (str/includes? lint-text "preceding registered `bootstrap-kondo`"))
          (is (str/includes? lint-text "missing import directories"))
          (is (str/includes? lint-text "nonzero lint result"))
          (is (str/includes? lint-text "cannot be handed over"))
          (is (str/includes? lint-text "Explicitly lint and diagnose"))
          (is (str/includes? lint-text "newly exposed `.millstrand`"))
          (is (str/includes? lint-text "namespace/path mismatch"))
          (is (str/includes? lint-text "coherent migration"))
          (is (str/includes? lint-text "one-file opportunistic rename"))
          (is (str/includes? lint-text "new-basis error")))))))

(deftest application-route-preserves-an-empty-product-base
  (let [route (definition #'tooling/configure-consumer-tooling-app)]
    (is (str/includes? (instruction route :align-tools-deps) ":paths []"))
    (is (str/includes? (instruction route :align-tools-deps)
                       "`.millstrand/deps.edn`"))
    (is (str/includes? (instruction route :align-tools-deps)
                       "require every external namespace"))
    (is (str/includes? (instruction route :configure-lsp)
                       "select nested `.millstrand/deps.edn`"))
    (is (str/includes? (instruction route :configure-lsp)
                       "clojure -Srepro -Sdeps .millstrand/deps.edn -Spath -M:lint"))
    (is (str/includes? (instruction route :configure-lsp)
                       "`:project-path` only"))
    (is (str/includes? (instruction route :configure-lsp)
                       "fresh LSP cache"))
    (is (str/includes? (instruction route :configure-lsp)
                       "external var definitions in the fresh LSP index"))
    (is (str/includes? (instruction route :configure-lsp)
                       "actual classpath roots"))
    (is (str/includes? (instruction route :configure-lsp)
                       "external var"))
    (is (str/includes? (instruction route :configure-lsp)
                       "Broad editor search or source-path visibility is not proof"))
    (is (str/includes? (instruction route :configure-lsp)
                       "best-effort human acceptance"))
    (is (str/includes? (instruction route :configure-lsp)
                       "Do not fail solely because no compatible editor"))
    (is (str/includes? (instruction route :configure-lint)
                       "Do not count syntax-only lint as hook proof"))
    (is (str/includes? (instruction route :verify-tests)
                       "discover and run the repository's existing test command"))
    (is (str/includes? (instruction route :verify-tests)
                       "repository-owned test configuration as ordinary Clojure"))
    (is (str/includes? (instruction route :verify-tests)
                       "do not prescribe a test runner"))
    (is (str/includes? (instruction route :verify-tests)
                       "add test scaffolding"))
    (is (str/includes? (instruction route :verify-tests)
                       "documented disposable-Weaver pattern"))
    (is (str/includes? (instruction route :verify-tests)
                       "coordinate or LSP projection alone does not require a new fixture"))
    (is (str/includes? (instruction route :verify-weaver)
                       "invoke one of its real operations"))))

(deftest spool-route-proves-publication-through-an-approved-world
  (let [route (definition #'tooling/configure-consumer-tooling-spool)]
    (is (str/includes? (instruction route :align-tools-deps)
                       "consumer approval in `spools.edn`"))
    (is (str/includes? (instruction route :align-tools-deps)
                       "every external namespace required by `.millstrand/init.clj`"))
    (is (str/includes? (instruction route :align-tools-deps)
                       "ct.spools.delegation"))
    (is (str/includes? (instruction route :align-tools-deps)
                       "ct.spools/delegation"))
    (is (str/includes? (instruction route :align-tools-deps)
                       "exact `:deps/root`"))
    (is (str/includes? (instruction route :align-tools-deps)
                       ":deps/root \"delegation\""))
    (is (str/includes? (instruction route :align-tools-deps)
                       ":git/sha \"RECORDED_SHA\""))
    (is (str/includes? (instruction route :align-tools-deps)
                       "current spool status/root metadata"))
    (is (str/includes? (instruction route :align-tools-deps)
                       "any unresolved placeholder in a"))
    (is (str/includes? (instruction route :align-tools-deps)
                       "committed consumer coordinate must fail"))
    (is (str/includes? (instruction route :align-tools-deps)
                       "RECORDED_SHA` is an example only"))
    (is (str/includes? (instruction route :align-tools-deps)
                       "stop on any missing namespace"))
    (is (str/includes? (instruction route :align-tools-deps)
                       "portable authoring project"))
    (is (str/includes? (instruction route :align-tools-deps)
                       "cannot be the only coordinate"))
    (is (str/includes? (instruction route :configure-lsp)
                       "clean fetched Git checkout"))
    (is (str/includes? (instruction route :configure-lsp)
                       "cache-isolated directory"))
    (is (str/includes? (instruction route :configure-lsp)
                       "directly require every namespace in the inventory"))
    (is (str/includes? (instruction route :configure-lsp)
                       "ct.spools/delegation"))
    (is (str/includes? (instruction route :configure-lsp)
                       "cached/partial"))
    (is (str/includes? (instruction route :configure-lsp)
                       "fresh LSP index contains that dependency's external var definitions"))
    (is (str/includes? (instruction route :configure-lsp)
                       "actual classpath roots"))
    (is (str/includes? (instruction route :configure-lsp)
                       "external var definitions"))
    (is (str/includes? (instruction route :configure-lsp)
                       "source-path visibility is not proof"))
    (is (str/includes? (instruction route :configure-lsp)
                       "both navigation hops as best-effort"))
    (is (str/includes? (instruction route :configure-lsp)
                       "Editor absence alone does not fail"))
    (is (str/includes? (instruction route :configure-lint)
                       "own producer coordinate"))
    (is (str/includes? (instruction route :verify-tests)
                       "discover and run the repository's existing test command"))
    (is (str/includes? (instruction route :verify-weaver)
                       "`:spools`-guarded namespace module"))))

(deftest clojure-application-route-preserves-the-ordinary-application
  (let [route (definition #'tooling/configure-consumer-tooling-clojure-app)]
    (is (str/includes? (instruction route :align-tools-deps)
                       "preserve the ordinary application's base"))
    (is (str/includes? (instruction route :align-tools-deps)
                       "separate tooling alias"))
    (is (str/includes? (instruction route :align-tools-deps)
                       "directly require every external namespace"))
    (is (str/includes? (instruction route :configure-lsp)
                       "base application basis remains unchanged"))
    (is (str/includes? (instruction route :configure-lsp)
                       "fresh LSP cache"))
    (is (str/includes? (instruction route :configure-lsp)
                       "actual classpath roots"))
    (is (str/includes? (instruction route :configure-lsp)
                       "external var definitions"))
    (is (str/includes? (instruction route :configure-lsp)
                       "source-path visibility is not proof"))
    (is (str/includes? (instruction route :configure-lsp)
                       "best-effort human acceptance"))
    (is (str/includes? (instruction route :configure-lsp)
                       "Do not fail solely for absent editor support"))
    (is (str/includes? (instruction route :verify-tests)
                       "discover and run the repository's existing test command"))
    (is (str/includes? (instruction route :verify-tests)
                       "coordinate or LSP projection alone does not require a new fixture"))
    (is (str/includes? (instruction route :verify-weaver)
                       "result came from ordinary application code"))))

(deftest pending-generation-evidence-is-not-called-adopted-proof
  (doseq [[style definition-var] routes]
    (testing (name style)
      (let [route (definition definition-var)
            verify-text (instruction route :verify-weaver)
            handover-text (instruction route :handover)]
        (is (str/includes? verify-text "Do not restart"))
        (is (str/includes? verify-text "run that same style-specific probe"))
        (is (str/includes? verify-text "current-generation-only evidence"))
        (is (str/includes? verify-text "not adoption proof"))
        (is (str/includes? verify-text "adopted generation as unfinished"))
        (is (str/includes? handover-text "prepared configuration"))
        (is (str/includes? handover-text "preserving the test result evidence"))
        (is (str/includes? handover-text "adopted generation proof"))
        (is (str/includes? handover-text "unfinished"))))))
