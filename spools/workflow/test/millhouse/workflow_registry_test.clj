(ns millhouse.workflow-registry-test
  "Test workflow and executor registration, publication and owner refresh."
  (:require [clojure.java.io :as io]
            [clojure.spec.alpha :as s]
            [clojure.test :refer [deftest is testing]]
            [millstrand.api.registry.alpha :as registry]
            [millstrand.api.runtime.alpha :as runtime]
            [millstrand.api.weaver.alpha :as weaver]
            [millhouse.test-support :as test-support :refer [assert-state-shape with-runtime]]
            [millhouse.workflow :as workflow]
            [millhouse.workflow.internal.registry :as wf-registry])
  (:import [java.time Instant]))

(defn- failure-reason [f]
  (:reason (ex-data (try (f) (catch clojure.lang.ExceptionInfo e e)))))

(defn- loopy-body
  "The shared body of the loopy stage and its revision round.

  Both are the same steps under different defaults, which is what a revision
  round IS now that a definition carries its own defaults."
  []
  (workflow/workflow
   "Loopy"
   (workflow/step :orient "Orient" :self :condition [:!= :revision true])
   (workflow/step :work "Do work" :self :depends-on [:orient])
   (workflow/checkpoint :signoff "Sign off"
                        :depends-on [:work]
                        :kind :agent
                        :choices [{:key :approved :label "Approve"}
                                  {:key :revise
                                   :label "Revise"
                                   :next 'millhouse.workflow-registry-test/loopy-revision}])))

(workflow/defworkflow loopy
  "A stage whose sign-off can route into a revision round."
  {:entrypoints #{:start :continue} :defaults {}}
  (loopy-body))

(workflow/defworkflow loopy-revision
  "The revision round of `loopy`: the same steps with :revision already true."
  {:entrypoints #{:continue} :defaults {:revision true}}
  (loopy-body))

(deftest workflow-describe-accepts-registered-keyword
  (with-runtime
    (fn [_rt _]
      (workflow/register-workflow! :loopy-describe 'millhouse.workflow-registry-test/loopy)
      (is (= "Loopy" (:name (workflow/describe :loopy-describe {})))))))

(deftest workflow-start-and-describe-reject-unknown-registered-keyword
  (with-runtime
    (fn [_rt _]
      (is (thrown-with-msg? clojure.lang.ExceptionInfo #"Unknown registered workflow"
                            (workflow/start! "missing-keyword-start" :missing-workflow {})))))
  (with-runtime
    (fn [_rt _]
      (is (thrown-with-msg? clojure.lang.ExceptionInfo #"Unknown registered workflow"
                            (workflow/describe :missing-workflow {}))))))

(defn- registry-router-stage [{:keys [target]}]
  (workflow/workflow
   "Registry router"
   (workflow/checkpoint :go "Go"
                        :kind :agent
                        :choices [{:key :advance :label "Advance" :next target}])))

(workflow/defworkflow registry-second-stage
  "Provide the second registry stage."
  {:entrypoints #{:continue}}
  (workflow/workflow "Registry second" (workflow/step :do-second "Do second" :self)))

(workflow/defworkflow registry-alt-second-stage
  "Provide the alternate second registry stage."
  {:entrypoints #{:continue}}
  (workflow/workflow "Registry alt" (workflow/step :do-alt "Do alt" :self)))

(deftest workflow-named-next-resolves-and-fails-loudly-on-unknown-name
  (with-runtime
    (fn [rt _]
      (workflow/register-workflow! :wt-second 'millhouse.workflow-registry-test/registry-second-stage)
      (is (= 'millhouse.workflow-registry-test/registry-second-stage
             (workflow/workflow-definition :wt-second)))
      (workflow/start! "named-run" (registry-router-stage {:target :wt-second}) {})
      ;; a registered keyword name routes just like a symbol :next target
      (is (= [{:title "Do second" :role "step"}]
             (mapv #(select-keys % [:title :role])
                   (:ready (workflow/choose! "named-run" :advance)))))
      ;; an unregistered name fails loudly at choose! time, before any mutation,
      ;; so the checkpoint stays active and resumable
      (workflow/start! "unknown-run" (registry-router-stage {:target :wt-never}) {})
      (let [go-id (:id (workflow/ready-step "unknown-run"))]
        (is (thrown-with-msg? clojure.lang.ExceptionInfo #"Unknown registered workflow"
                              (workflow/choose! "unknown-run" :advance)))
        (is (= "active" (:state (weaver/show rt go-id))))))))

(deftest workflow-registry-rename-repoints-in-flight-run
  (with-runtime
    (fn [_rt _]
      (workflow/register-workflow! :wt-rename 'millhouse.workflow-registry-test/registry-second-stage)
      (workflow/start! "rename-run" (registry-router-stage {:target :wt-rename}) {})
      ;; re-registering the name (a reloaded workflow) points the in-flight run's
      ;; not-yet-chosen route at the new constructor
      (workflow/register-workflow! :wt-rename 'millhouse.workflow-registry-test/registry-alt-second-stage)
      (is (= ["Do alt"]
             (mapv :title (:ready (workflow/choose! "rename-run" :advance))))))))

(deftest register-executor-rejects-invalid-waiters
  (is (thrown-with-msg? clojure.lang.ExceptionInfo #"Executor waiter must be.*other than :self"
                        (workflow/register-executor! :self (constantly nil))))
  (is (thrown-with-msg? clojure.lang.ExceptionInfo #"Executor waiter must be.*keyword, symbol, or non-blank string"
                        (workflow/register-executor! 42 (constantly nil))))
  (try
    (workflow/register-executor! :self (constantly nil))
    (is false "expected executor registration to reject :self")
    (catch clojure.lang.ExceptionInfo e
      (is (= :self (:waiter (ex-data e)))))))

(deftest executors-reflects-registrations
  (with-runtime
    (fn [_rt _]
      (workflow/register-executor! :registry-test-executor (constantly nil))
      (is (contains? (workflow/executors) :registry-test-executor)))))

(defn exec-detail-a
  "Return the A fixture executor's stall detail."
  [_step] {:by :a})

(defn exec-detail-b
  "Return the B fixture executor's stall detail."
  [_step] {:by :b})

(deftest executor-fn-value-registration-lives-in-resource-state
  ;; A bare function value has no symbol, so it is held as runtime-owned resource
  ;; state (DELTA-OlrDrt-001.CC8), not as owner-partition declaration data.
  (with-runtime
    (fn [rt _]
      (test-support/activate-spool! rt :millhouse/workflow 'millhouse.workflow)
      (let [pred (constantly {:raw true})]
        (workflow/register-executor! :raw-exec pred)
        (is (identical? pred (get @(wf-registry/executor-fns rt) "raw-exec")))
        (is (identical? pred (wf-registry/executor-for rt "raw-exec")))
        (is (empty? (registry/effective (wf-registry/registry-handle rt)
                                        workflow/executor-kind))
            "no function value reaches the declarative executor kind")))))

(deftest executor-symbol-resolves-to-a-function-value-per-gate-evaluation
  ;; DW1: an executor symbol is resolved to a function value at each gate
  ;; evaluation, so a re-pointed executor is observed on the next lookup while a
  ;; value already captured for an in-flight call keeps its snapshot (CC10).
  (with-runtime
    (fn [rt _]
      (test-support/activate-spool! rt :millhouse/workflow 'millhouse.workflow)
      (workflow/register-executor! :exec-snap 'millhouse.workflow-registry-test/exec-detail-a)
      (let [snapshot (wf-registry/executor-for rt "exec-snap")]
        (is (= {:by :a} (snapshot {})))
        (workflow/register-executor! :exec-snap 'millhouse.workflow-registry-test/exec-detail-b)
        (is (= {:by :b} ((wf-registry/executor-for rt "exec-snap") {}))
            "the next gate evaluation resolves the re-pointed executor")
        (is (= {:by :a} (snapshot {}))
            "a value captured for an in-flight call keeps its snapshot")))))

(deftest executor-unresolved-stall-symbol-fails-loudly
  ;; A declared stall symbol whose Var no longer exists must not read as an
  ;; absent executor: both lookup paths name the waiter and symbol.
  (with-runtime
    (fn [rt _]
      (test-support/activate-spool! rt :millhouse/workflow 'millhouse.workflow)
      (workflow/register-executor! :exec-gone 'millhouse.workflow-registry-test/no-such-predicate)
      (let [err (is (thrown-with-msg? clojure.lang.ExceptionInfo
                                      #"stall symbol does not resolve"
                                      (wf-registry/executor-for rt "exec-gone")))]
        (is (= "exec-gone" (:waiter (ex-data err))))
        (is (= 'millhouse.workflow-registry-test/no-such-predicate (:stalled? (ex-data err)))))
      (is (thrown-with-msg? clojure.lang.ExceptionInfo #"stall symbol does not resolve"
                            (wf-registry/executor-map rt))))))

(s/def :decl-exec/target string?)

(s/def ::decl-exec-request (s/keys :req [:decl-exec/target]))

(deftest executor-declaration-map-registers-and-projects
  ;; A declaration-map entry names the stall predicate and the gate-request
  ;; spec; the catalogue projects the contract while gate evaluation resolves
  ;; the same predicate a bare symbol would.
  (with-runtime
    (fn [rt _]
      (test-support/activate-spool! rt :millhouse/workflow 'millhouse.workflow)
      (workflow/register-executor!
       :decl-exec {:stalled? 'millhouse.workflow-registry-test/exec-detail-a
                   :request-spec ::decl-exec-request})
      (is (= {:by :a} ((wf-registry/executor-for rt "decl-exec") {}))
          "gate evaluation resolves the declared stall predicate")
      (is (contains? (workflow/executors) :decl-exec))
      (let [item (first (filter #(= "decl-exec" (:waiter %))
                                (workflow/executor-catalog)))]
        (is (= "millhouse.workflow-registry-test/exec-detail-a" (:stall-predicate item)))
        (is (= "millhouse.workflow-registry-test/decl-exec-request"
               (get-in item [:request :spec])))
        (is (= ["decl-exec/target"]
               (mapv #(get % "key")
                     (get-in item [:request :contract "required"])))
            "the projected contract names the exact qualified attribute key")
        (is (contains? (get-in item [:request :template]) "decl-exec/target")
            "the template is keyed by the attribute spelling an author writes")))))

(deftest executor-declaration-map-with-bad-shape-fails-loudly
  ;; A map is ifn?, so a mistyped declaration must fail loudly rather than
  ;; silently register as a lookup predicate.
  (with-runtime
    (fn [rt _]
      (test-support/activate-spool! rt :millhouse/workflow 'millhouse.workflow)
      (is (thrown-with-msg? clojure.lang.ExceptionInfo #"Executor declaration map"
                            (workflow/register-executor!
                             :bad-decl {:stalledd? 'millhouse.workflow-registry-test/exec-detail-a}))))))

(deftest executor-catalog-fails-loudly-on-an-unresolvable-request-spec
  ;; A declared spec that no longer resolves must not read as an executor with
  ;; no contract.
  (with-runtime
    (fn [rt _]
      (test-support/activate-spool! rt :millhouse/workflow 'millhouse.workflow)
      (workflow/register-executor!
       :stale-decl {:stalled? 'millhouse.workflow-registry-test/exec-detail-a
                    :request-spec ::never-registered-request})
      (is (= :workflow/spec-missing
             (failure-reason workflow/executor-catalog))))))

(deftest workflow-owner-refresh-removes-omitted-definitions-and-executors
  ;; DW2 / kxhd4 R4 per-domain deletion completeness: an owner-complete
  ;; replacement removes any route or executor the new partition omits, and
  ;; removing the owner clears the rest — no global reload.
  (with-runtime
    (fn [rt _]
      (test-support/activate-spool! rt :millhouse/workflow 'millhouse.workflow)
      (let [handle (wf-registry/registry-handle rt)
            spools-definitions (fn [entries]
                                 (registry/replace-owner!
                                  handle workflow/definition-kind :spools/pkg
                                  {:layer :spools :entries entries :overrides #{}}))
            spools-executors (fn [entries]
                               (registry/replace-owner!
                                handle workflow/executor-kind :spools/pkg
                                {:layer :spools :entries entries :overrides #{}}))]
        (spools-definitions {:route-a 'millhouse.workflow-registry-test/registry-second-stage
                             :route-b 'millhouse.workflow-registry-test/registry-alt-second-stage})
        (spools-executors {"exec-a" 'millhouse.workflow-registry-test/exec-detail-a
                           "exec-b" 'millhouse.workflow-registry-test/exec-detail-b})
        (is (= #{:route-a :route-b} (set (keys (workflow/workflows)))))
        (is (= #{"exec-a" "exec-b"} (set (keys (wf-registry/executor-map rt)))))
        ;; a complete replacement omitting one of each removes only those
        (spools-definitions {:route-a 'millhouse.workflow-registry-test/registry-second-stage})
        (spools-executors {"exec-a" 'millhouse.workflow-registry-test/exec-detail-a})
        (is (= #{:route-a} (set (keys (workflow/workflows)))) "omitted route removed")
        (is (= #{"exec-a"} (set (keys (wf-registry/executor-map rt)))) "omitted executor removed")
        ;; removing the owner clears the rest
        (registry/remove-owner! handle workflow/definition-kind :spools/pkg)
        (registry/remove-owner! handle workflow/executor-kind :spools/pkg)
        (is (empty? (workflow/workflows)))
        (is (empty? (wf-registry/executor-map rt)))))))

(deftest workflow-constructor-override-restores-shadowed-entry-on-removal
  ;; DW2 / DELTA-OlrDrt-001.CC3: a higher-layer entry shadows a lower one with
  ;; explicit override intent; removing the overriding owner re-exposes the
  ;; shadowed entry.
  (with-runtime
    (fn [rt _]
      (test-support/activate-spool! rt :millhouse/workflow 'millhouse.workflow)
      (let [handle (wf-registry/registry-handle rt)]
        (registry/replace-owner! handle workflow/definition-kind :spools/pkg
                                 {:layer :spools
                                  :entries {:route-x 'millhouse.workflow-registry-test/registry-second-stage}
                                  :overrides #{}})
        (is (= 'millhouse.workflow-registry-test/registry-second-stage
               (workflow/workflow-definition :route-x)))
        ;; a direct/REPL registration shadows the lower spools layer
        (workflow/register-workflow! :route-x 'millhouse.workflow-registry-test/registry-alt-second-stage)
        (is (= 'millhouse.workflow-registry-test/registry-alt-second-stage
               (workflow/workflow-definition :route-x))
            "the direct layer wins while its override stands")
        ;; removing the direct owner restores the shadowed spools entry
        (registry/remove-owner! handle workflow/definition-kind :millstrand.owner/repl)
        (is (= 'millhouse.workflow-registry-test/registry-second-stage
               (workflow/workflow-definition :route-x))
            "the shadowed entry becomes effective again")))))

(deftest executor-fns-state-shape-matches-declared-version
  (assert-state-shape #'wf-registry/new-executor-fns #{:executor-fns}))

(s/def ::scope string?)

(s/def ::static-build-params (s/keys :req-un [::scope]))

(workflow/defworkflow static-build
  "Build an agreed scope."
  {:entrypoints #{:start :continue}
   :param-spec ::static-build-params
   :defaults {:reviewer "agent"}}
  (workflow/workflow
   (fn [{:keys [scope]}] (str "Build " scope))
   (workflow/step :implement
                  (fn [{:keys [scope reviewer]}] (str "Implement " scope " for " reviewer))
                  :self)))

(workflow/defworkflow static-review
  "Review a completed implementation."
  {:entrypoints #{:call}}
  (workflow/workflow
   "Review"
   (workflow/step :inspect "Inspect the change" :self)))

(workflow/defworkflow static-spike
  "Reduce uncertainty and recommend the next routine."
  {:entrypoints #{:start}}
  (workflow/workflow
   "Spike"
   (workflow/checkpoint :recommendation "Choose what follows the spike"
                        :kind :agent
                        :choices [{:key :recommend-build :label "Build" :next :wt-build}
                                  {:key :stop :label "Stop"}])))

(def ^:private bad-defaults-definition
  (workflow/workflow
   "Bad defaults"
   {:entrypoints #{:start} :defaults {:at (Instant/parse "2026-01-01T00:00:00Z")}}
   (workflow/step :a "A" :self)))

(def ^:private bad-spec-definition
  (workflow/workflow
   "Bad spec"
   {:entrypoints #{:start} :param-spec ::never-registered}
   (workflow/step :a "A" :self)))

(defn- revisable [title]
  (workflow/workflow
   title
   {:entrypoints #{:start}}
   (workflow/checkpoint :again "Revise again"
                        :kind :agent
                        :choices [{:key :again :label "Again" :revise {:params {}}}])))

(def ^:private revisable-definition (revisable "Revisable v1"))

(def ^:private revisable-definition-v2 (revisable "Revisable v2"))

(def ^:private live-var-definition
  (workflow/workflow
   "Live v1"
   {:entrypoints #{:start}}
   (workflow/step :a "A" :self)))

(defn- exploding-constructor [_]
  (throw (ex-info "constructor blew up" {:cause :test})))

(defn- malformed-constructor [_]
  {:not-a "workflow"})

(deftest registered-call-target-requires-the-call-entrypoint
  (with-runtime
    (fn [rt _]
      (test-support/activate-spool! rt :millhouse/workflow 'millhouse.workflow)
      (workflow/register-workflow! :wt-review 'millhouse.workflow-registry-test/static-review)
      (workflow/register-workflow! :wt-build 'millhouse.workflow-registry-test/static-build)
      (let [caller (fn [target]
                     (workflow/workflow
                      "Caller"
                      (workflow/step :prepare "Prepare" :self)
                      (workflow/call :sub target {} :depends-on [:prepare])))]
        (is (= ["Caller" "Prepare" "Inspect the change" "Complete sub"]
               (mapv :title (:strands (workflow/compile (caller :wt-review))))))
        (let [thrown (try (workflow/compile (caller :wt-build))
                          (catch clojure.lang.ExceptionInfo e e))]
          (is (= :workflow/entrypoint-unsupported (:reason (ex-data thrown))))
          (is (= :call (:entrypoint (ex-data thrown)))))))))

(deftest unregister-workflow-removes-the-direct-registration
  (with-runtime
    (fn [rt _]
      (test-support/activate-spool! rt :millhouse/workflow 'millhouse.workflow)
      (workflow/register-workflow! :wt-build 'millhouse.workflow-registry-test/static-build)
      (workflow/register-workflow! :wt-review 'millhouse.workflow-registry-test/static-review)
      (is (= {:wt-review 'millhouse.workflow-registry-test/static-review}
             (workflow/unregister-workflow! :wt-build))
          "removal returns what the direct layer still declares")
      (is (= [:wt-review] (vec (keys (workflow/workflows)))))
      (is (thrown-with-msg? clojure.lang.ExceptionInfo #"Unknown registered workflow"
                            (workflow/start! "gone" :wt-build {})))
      (is (thrown-with-msg? clojure.lang.ExceptionInfo #"no direct registration to remove"
                            (workflow/unregister-workflow! :wt-build))))))

(deftest register-workflow-rejects-an-unresolvable-symbol-with-repair-context
  (with-runtime
    (fn [rt _]
      (test-support/activate-spool! rt :millhouse/workflow 'millhouse.workflow)
      (let [thrown (try (workflow/register-workflow!
                         :wt-missing 'millhouse.workflow-registry-test/no-such-definition)
                        (catch clojure.lang.ExceptionInfo e e))
            data (ex-data thrown)]
        (is (= :workflow/definition-unresolvable (:reason data)))
        (is (= :wt-missing (:name data)))
        (is (= 'millhouse.workflow-registry-test/no-such-definition (:definition data)))
        (is (= 'millhouse.workflow-registry-test (:namespace data)))
        (is (seq (:repair data))))
      (is (empty? (workflow/workflows))
          "a rejected registration leaves the live registry untouched"))))

(deftest register-workflow-rejects-a-route-to-a-missing-target
  (with-runtime
    (fn [rt _]
      (test-support/activate-spool! rt :millhouse/workflow 'millhouse.workflow)
      (let [thrown (try (workflow/register-workflow!
                         :wt-spike 'millhouse.workflow-registry-test/static-spike)
                        (catch clojure.lang.ExceptionInfo e e))]
        (is (= :workflow/reference-unregistered (:reason (ex-data thrown))))
        (is (= :wt-build (:target (ex-data thrown))))
        (is (= :continue (:entrypoint (ex-data thrown)))))
      ;; with the target registered first, the same registration is publishable
      (workflow/register-workflow! :wt-build 'millhouse.workflow-registry-test/static-build)
      (is (= :wt-spike (workflow/register-workflow!
                        :wt-spike 'millhouse.workflow-registry-test/static-spike))))))

(deftest register-workflow-rejects-non-json-defaults-and-unknown-param-specs
  (with-runtime
    (fn [rt _]
      (test-support/activate-spool! rt :millhouse/workflow 'millhouse.workflow)
      (let [thrown (try (workflow/register-workflow!
                         :wt-bad-defaults 'millhouse.workflow-registry-test/bad-defaults-definition)
                        (catch clojure.lang.ExceptionInfo e e))]
        (is (= :workflow/defaults-invalid (:reason (ex-data thrown))))
        (is (= [:at] (:path (ex-data thrown)))))
      (let [thrown (try (workflow/register-workflow!
                         :wt-bad-spec 'millhouse.workflow-registry-test/bad-spec-definition)
                        (catch clojure.lang.ExceptionInfo e e))]
        (is (= :workflow/param-spec-missing (:reason (ex-data thrown))))
        (is (= ::never-registered (:param-spec (ex-data thrown))))))))

(deftest a-registered-symbol-must-resolve-to-a-definition-map
  ;; Constructors are gone: a symbol that resolves to a function, or to any
  ;; other value, is refused at registration — before it can reach a run.
  (with-runtime
    (fn [rt _]
      (test-support/activate-spool! rt :millhouse/workflow 'millhouse.workflow)
      (doseq [[label sym] [["a function" 'millhouse.workflow-registry-test/exploding-constructor]
                           ["a non-workflow value" 'millhouse.workflow-registry-test/malformed-constructor]]]
        (let [thrown (try (workflow/register-workflow! :wt-boom sym)
                          (catch clojure.lang.ExceptionInfo e e))
              data (ex-data thrown)]
          (is (= :workflow/definition-invalid (:reason data)) label)
          (is (= sym (:definition data)) label)
          (is (string? (:resolved-class data))
              (str label ": the failure names what it did resolve to"))))
      (is (empty? (workflow/workflows))
          "a refused registration leaves the live registry untouched")
      (is (nil? (workflow/current-root "boom-run")) "nothing poured"))))

(deftest revision-resolves-the-live-registered-name
  (with-runtime
    (fn [rt _]
      (test-support/activate-spool! rt :millhouse/workflow 'millhouse.workflow)
      (workflow/register-workflow! :wt-revisable 'millhouse.workflow-registry-test/revisable-definition)
      (workflow/start! "revise-name-run" :wt-revisable {})
      (is (= "Revisable v1" (:title (workflow/current-root "revise-name-run"))))
      ;; repointing the name changes what the next revision pours; the strands
      ;; already poured are untouched
      (workflow/register-workflow! :wt-revisable 'millhouse.workflow-registry-test/revisable-definition-v2)
      (workflow/choose! "revise-name-run" :again)
      (is (= "Revisable v2" (:title (workflow/current-root "revise-name-run"))))
      (is (= "millhouse.workflow-registry-test/revisable-definition-v2"
             (get-in (workflow/current-root "revise-name-run")
                     [:attributes :workflow/definition])))
      ;; removing the name fails the next revision before any mutation
      (workflow/unregister-workflow! :wt-revisable)
      (let [checkpoint (:id (workflow/ready-step "revise-name-run"))]
        (is (thrown-with-msg? clojure.lang.ExceptionInfo #"Unknown registered workflow"
                              (workflow/choose! "revise-name-run" :again)))
        (is (= "active" (:state (weaver/show rt checkpoint))))))))

(deftest redefining-a-var-changes-the-next-transition-not-the-current-run
  ;; PROP-Wcd-001.S8: source load and code reload redefine Vars under a live
  ;; registry. Resolution is live, so the change lands at the next transition.
  (with-runtime
    (fn [rt _]
      (test-support/activate-spool! rt :millhouse/workflow 'millhouse.workflow)
      (let [original @#'live-var-definition]
        (try
          (workflow/register-workflow! :wt-live 'millhouse.workflow-registry-test/live-var-definition)
          (workflow/start! "live-run" :wt-live {})
          (is (= "Live v1" (:title (workflow/current-root "live-run"))))
          (alter-var-root #'live-var-definition assoc :name "Live v2")
          (is (= "Live v1" (:title (workflow/current-root "live-run")))
              "strands already poured keep the definition they were built from")
          (is (= "Live v2" (:name (:value (workflow/resolve-workflow :wt-live))))
              "the registered name resolves the redefined Var")
          (finally
            (alter-var-root #'live-var-definition (constantly original))))))))

(defn- definition-module-source
  "Write a module source file declaring `forms` and return its workspace path."
  [config-dir label forms]
  (let [source (str "modules/" label ".clj")
        file (io/file config-dir source)]
    (io/make-parents file)
    (spit file (str "(ns test.module." label "\n"
                    "  \"Definition module fixture for the workflow candidate tests.\"\n"
                    "  (:require [millhouse.workflow :as workflow]))\n"
                    forms))
    source))

(def ^:private alpha-definition-form
  (str "(workflow/defworkflow! alpha\n"
       "  \"Alpha routine.\"\n"
       "  {:entrypoints #{:start :continue}}\n"
       "  (workflow/workflow \"Alpha\" (workflow/step :a \"A\" :self)))\n"))

(def ^:private beta-routing-form
  (str "(workflow/defworkflow! beta\n"
       "  \"Beta routine routing to alpha.\"\n"
       "  {:entrypoints #{:start}}\n"
       "  (workflow/workflow \"Beta\"\n"
       "    (workflow/checkpoint :go \"Go\" :kind :agent\n"
       "      :choices [{:key :on :label \"On\" :next :alpha}])))\n"))

(deftest module-refresh-publishes-collected-definitions-across-owners
  ;; PROP-Wcd-001.S5/S6: defworkflow contributes its symbol only under a
  ;; collector, and a cross-owner route is judged against the complete candidate.
  (with-runtime
    (fn [rt config-dir]
      (test-support/activate-spool! rt :millhouse/workflow 'millhouse.workflow)
      (let [alpha-source (definition-module-source config-dir "wf-alpha" alpha-definition-form)
            beta-source (definition-module-source config-dir "wf-beta" beta-routing-form)]
        (is (= :applied (:status (runtime/module! rt :wf-alpha {:file alpha-source}))))
        (is (= :applied (:status (runtime/module! rt :wf-beta {:file beta-source}))))
        (is (= #{:alpha :beta} (set (keys (workflow/workflows)))))
        (is (= 'test.module.wf-alpha/alpha (workflow/workflow-definition :alpha)))
        (is (= 'test.module.wf-beta/beta (:definition (workflow/resolve-workflow :beta))))
        (is (map? (:value (workflow/resolve-workflow :beta)))
            "a registered name resolves to the definition map itself")))))

(deftest deleting-a-referenced-definition-by-omission-is-refused-atomically
  ;; The alpha owner's next contribution drops the definition beta routes to.
  ;; Publication is rejected whole, so both owners keep their live partitions.
  (with-runtime
    (fn [rt config-dir]
      (test-support/activate-spool! rt :millhouse/workflow 'millhouse.workflow)
      (let [alpha-source (definition-module-source config-dir "wf-alpha2" alpha-definition-form)
            beta-source (definition-module-source config-dir "wf-beta2" beta-routing-form)]
        (runtime/module! rt :wf-alpha2 {:file alpha-source})
        (runtime/module! rt :wf-beta2 {:file beta-source})
        (is (= #{:alpha :beta} (set (keys (workflow/workflows)))))
        (definition-module-source config-dir "wf-alpha2" "")
        (let [error (try
                      (runtime/module! rt :wf-alpha2 {:file alpha-source})
                      nil
                      (catch clojure.lang.ExceptionInfo error
                        error))]
          (is (instance? clojure.lang.ExceptionInfo error))
          (is (= :workflow/reference-unregistered
                 (:reason (ex-data error))))
          (is (= :wf-beta2 (:owner (ex-data error)))))
        (is (= #{:alpha :beta} (set (keys (workflow/workflows))))
            "every affected owner keeps its previous live partition")))))

(deftest an-unresolvable-contributed-definition-is-refused-with-owner-context
  (with-runtime
    (fn [rt config-dir]
      (test-support/activate-spool! rt :millhouse/workflow 'millhouse.workflow)
      (let [source (definition-module-source
                     config-dir "wf-gone"
                     (str "(millstrand.api.runtime.alpha/collect-entry!\n"
                          "  workflow/definition-kind :gone 'test.module.wf-gone/absent)\n"))
            error (try
                    (runtime/module! rt :wf-gone {:file source})
                    nil
                    (catch clojure.lang.ExceptionInfo error
                      error))]
        (is (instance? clojure.lang.ExceptionInfo error))
        (is (= :workflow/definition-unresolvable
               (:reason (ex-data error))))
        (is (= :wf-gone (:owner (ex-data error))))
        (is (empty? (workflow/workflows)))))))

(s/def ::reviewer string?)

(s/def ::spec-first-params (s/keys :req-un [::scope ::reviewer]))

(workflow/defworkflow spec-first-build
  "Build a scope under a whole-map param contract."
  {:entrypoints #{:start :continue}
   :param-spec ::spec-first-params
   :defaults {:reviewer "agent"}}
  (workflow/workflow
   (fn [{:keys [scope]}] (str "Build " scope))
   (workflow/step :implement
                  (fn [{:keys [scope reviewer]}] (str "Implement " scope " for " reviewer))
                  :self)))

(def ^:private unknown-input-definition
  (workflow/workflow
   "Unknown input"
   {:entrypoints #{:start}}
   (workflow/checkpoint :go "Go" :kind :agent
                        :choices [{:key :approve :input ::never-registered-input}])))

(deftest definition-view-params-carry-the-shared-projection
  (with-runtime
    (fn [rt _]
      (test-support/activate-spool! rt :millhouse/workflow 'millhouse.workflow)
      (workflow/register-workflow! :wt-spec-build 'millhouse.workflow-registry-test/spec-first-build)
      (let [params (:params (workflow/definition-view :wt-spec-build))]
        (is (= "spec" (:kind params)))
        (is (= "root" (get-in params [:spec-forms 0 "relation"])))
        (is (= "map" (get-in params [:contract "kind"])))
        (is (= #{"scope" "reviewer"}
               (set (map #(get % "key") (get-in params [:contract "required"])))))
        (is (contains? (:template params) "scope"))))))

(s/def ::documented-scope ::scope)

(s/def ::authored-params (s/keys :req-un [::scope ::documented-scope]
                                 :opt-un [::reviewer]))

(workflow/defworkflow authored-build
  "Build under authored params documentation."
  {:entrypoints #{:start}
   :param-spec ::authored-params
   :example {:scope "compact queue" :documented-scope "follow-up scope"}
   :param-docs {:scope "What to build."
                :documented-scope "Anchored through the collapsed alias."}}
  (workflow/workflow
   "Authored build"
   (workflow/step :implement "Implement" :self)))

(deftest definition-view-merges-authored-docs-and-example
  (with-runtime
    (fn [rt _]
      (test-support/activate-spool! rt :millhouse/workflow 'millhouse.workflow)
      (workflow/register-workflow! :wt-authored 'millhouse.workflow-registry-test/authored-build)
      (let [params (:params (workflow/definition-view :wt-authored))
            entry (fn [key]
                    (first (filter #(= key (get % "key"))
                                   (concat (get-in params [:contract "required"])
                                           (get-in params [:contract "optional"])))))]
        (is (= {:scope "compact queue" :documented-scope "follow-up scope"}
               (:example params)))
        (testing "an authored doc overrides the predicate-var docstring"
          (is (= "What to build." (get (entry "scope") "doc")))
          (is (= "What to build." (get-in (entry "scope") ["contract" "doc"])))
          (is (= "<What to build.>" (get-in params [:template "scope"]))))
        (testing "a doc anchors on an outer key whose recorded form collapsed an alias"
          (is (= "Anchored through the collapsed alias."
                 (get (entry "documented-scope") "doc"))))
        (testing "an undocumented key keeps the hoisted predicate doc"
          (is (= "Return true if x is a String" (get (entry "reviewer") "doc")))
          (is (= "<optional Return true if x is a String>"
                 (get-in params [:template "reviewer"]))))
        (testing "a definition without authored fields shows none"
          (workflow/register-workflow!
           :wt-plain 'millhouse.workflow-registry-test/spec-first-build)
          (is (not (contains? (:params (workflow/definition-view :wt-plain))
                              :example))))))))

(deftest registering-a-definition-with-an-unknown-input-spec-is-refused
  (with-runtime
    (fn [rt _]
      (test-support/activate-spool! rt :millhouse/workflow 'millhouse.workflow)
      (let [thrown (try (workflow/register-workflow!
                         :wt-bad-input 'millhouse.workflow-registry-test/unknown-input-definition)
                        (catch clojure.lang.ExceptionInfo e e))]
        (is (= :workflow/input-spec-missing (:reason (ex-data thrown))))
        (is (= ::never-registered-input (:spec (ex-data thrown))))
        (is (empty? (workflow/workflows)))))))

(s/def ::feature string?)

(s/def ::devflow-params (s/keys :req-un [::feature ::reviewer]))

(workflow/defworkflow defer-devflow
  "Plan and build a feature."
  {:entrypoints #{:start :call}
   :param-spec ::devflow-params
   :defaults {:reviewer "agent"}}
  (workflow/workflow
   (fn [{:keys [feature]}] (str "Plan and build " feature))
   (workflow/step :inspect
                  (fn [{:keys [feature reviewer]}]
                    (str "Inspect " feature " for " reviewer))
                  :self)))

(workflow/defworkflow defer-spike
  "Reduce uncertainty before committing."
  {:entrypoints #{:start :call}}
  (workflow/workflow
   "Spike"
   (workflow/step :probe "Probe the unknown" :self)))

(workflow/defworkflow defer-continue-only
  "A route-only routine that no defer may select."
  {:entrypoints #{:continue}}
  (workflow/workflow
   "Continue only"
   (workflow/step :inner "Inner" :self)))

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

(def ^:private continue-only-bound-card (bound-card #{:wt-continueonly}))

(defn- register-defer-targets! []
  (workflow/register-workflow! :wt-devflow 'millhouse.workflow-registry-test/defer-devflow)
  (workflow/register-workflow! :wt-spike 'millhouse.workflow-registry-test/defer-spike))

(defn- definition-symbol [v]
  (let [{ns-sym :ns name-sym :name} (meta v)]
    (symbol (str (ns-name ns-sym)) (str name-sym))))

(deftest registering-a-definition-with-an-unbound-defer-is-refused
  (with-runtime
    (fn [rt _]
      (test-support/activate-spool! rt :millhouse/workflow 'millhouse.workflow)
      (let [thrown (try (workflow/register-workflow!
                         :wt-template 'millhouse.workflow-registry-test/card-template)
                        (catch clojure.lang.ExceptionInfo e e))]
        (is (= :workflow/defer-unbound (:reason (ex-data thrown)))))
      (is (empty? (workflow/workflows)) "the live registry is untouched"))))

(deftest defer-targets-require-call-and-are-validated-against-the-complete-candidate
  (with-runtime
    (fn [rt _]
      (test-support/activate-spool! rt :millhouse/workflow 'millhouse.workflow)
      (testing "a target that is not registered at all"
        (let [thrown (try (workflow/register-workflow!
                           :wt-card 'millhouse.workflow-registry-test/tracked-card)
                          (catch clojure.lang.ExceptionInfo e e))]
          (is (= :workflow/reference-unregistered (:reason (ex-data thrown))))
          (is (= :call (:entrypoint (ex-data thrown)))
              "filling a defer runs its target as an inline procedure")))
      (register-defer-targets!)
      (is (= :wt-card (workflow/register-workflow!
                       :wt-card 'millhouse.workflow-registry-test/tracked-card)))
      (testing "a target that declares only :continue is not selectable"
        (workflow/register-workflow! :wt-continueonly
                                     'millhouse.workflow-registry-test/defer-continue-only)
        (let [thrown (try (workflow/register-workflow!
                           :wt-bad-card
                           (definition-symbol #'continue-only-bound-card))
                          (catch clojure.lang.ExceptionInfo e e))]
          (is (= :workflow/reference-entrypoint-unsupported (:reason (ex-data thrown))))
          (is (= :call (:entrypoint (ex-data thrown))))
          (is (= :defer (:declaring-kind (ex-data thrown))))))
      (testing "a target that is not a definition map at all"
        (let [thrown (try (workflow/register-workflow!
                           :wt-legacy 'millhouse.workflow-registry-test/exploding-constructor)
                          (catch clojure.lang.ExceptionInfo e e))]
          (is (= :workflow/definition-invalid (:reason (ex-data thrown))))
          (is (= 'millhouse.workflow-registry-test/exploding-constructor
                 (:definition (ex-data thrown)))))))))
