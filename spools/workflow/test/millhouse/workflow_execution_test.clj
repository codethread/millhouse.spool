(ns millhouse.workflow-execution-test
  "One file-backed transaction/lifecycle witness for the common execution kernel."
  (:require [clojure.java.io :as io]
            [clojure.test :refer [deftest is testing]]
            [millhouse.test-support :as support]
            [millhouse.workflow :as workflow]
            [millhouse.workflow.execution :as execution]
            [millstrand.api.current.alpha :as current]
            [millstrand.api.hooks.alpha :as hooks]
            [millstrand.api.graph.alpha :as graph]
            [millstrand.api.runtime.alpha :as runtime]
            [millstrand.api.spool.alpha :refer [attr-get]]
            [millstrand.api.weaver.alpha :as weaver])
  (:import [java.util.concurrent CountDownLatch TimeUnit]))

(def ^:private trap (atom nil))
(def ^:private entered (atom nil))
(def ^:private release (atom nil))
(def ^:private calls (atom 0))

(defn callback "Count a callback and return a stable JSON value." [_]
  (swap! calls inc)
  "value")

(defn blocked-callback "Ignore interruption until positive test-controlled return." [_]
  (swap! calls inc)
  (.countDown ^CountDownLatch @entered)
  (loop []
    (if (try (.await ^CountDownLatch @release) true (catch InterruptedException _ false))
      "late" (recur))))

(defn drift-input
  "Inject one real public-store writer between planning and its transaction.

  :hold-completion prevents subsequent deliveries from hiding the first refusal.
  This is a test failure seam, not an alternate execution implementation."
  [ctx]
  (when-let [{:keys [mode id] :as expected} @trap]
    (let [patch (:strand/patch ctx)
          close? (or (= "closed" (:state patch)) (= "closed" (get-in ctx [:strand/after :state])))
          attempt? (= :attempt (:ref patch))
          match? (case mode
                   :claim attempt?
                   :completion (and close? (= "done" (get-in patch [:attributes "execution/phase"])))
                   :abandon (and close? (= id (name (:ref patch))))
                   :hold-finalization (and close? (or (= id (:strand/id ctx))
                                                      (= id (some-> patch :ref name))))
                   :hold-completion close?
                   false)]
      (when match?
        (if (contains? #{:hold-completion :hold-finalization} mode)
          (throw (ex-info "Hold redelivery after before-image refusal" {}))
          (when (compare-and-set! trap expected (when (= :completion mode) {:mode :hold-completion :id id}))
            (weaver/update! (current/runtime) id {:attributes {"test/drift" (name mode)}}))))))
  {:hook/value (:hook/value ctx)})

(def replacement
  "Continuation for the atomic routed-choice witness."
  (workflow/workflow "Replacement" {:entrypoints #{:continue}}
                     (workflow/step :after "Replacement step" :self)))

(def abort-replacement
  "Named replacement retains definition identity for subsequent revisions."
  (workflow/workflow "Abort" (workflow/step :after "Abort" :self)))

(defn- inspect [rt run-id gate]
  (execution/inspect rt {:run-id run-id :step gate}))

(defn- await-done [rt run-id gate]
  (support/poll-until #(let [view (inspect rt run-id gate)] (when (= :done (:phase view)) view))))

(defn- gate [id function & options]
  (apply workflow/gate id (name id) :code
         :attributes {"code/fn" function "code/params" {}} options))

(defn- request [run-id gate-id token key]
  {:run-id run-id :step gate-id :expected-attempt token
   :request-id key :reason "Repair input" :by-identity "test-worker"})

(deftest conditional-public-store-lifecycle-and-retired-routing
  (support/with-runtime
    (fn [rt directory]
      (reset! trap nil)
      (reset! calls 0)
      (support/activate-spool! rt :workflow 'millhouse.workflow)
      (hooks/register-hook! rt :test/drift #{:attributes/normalize :strand/update-before-commit}
                            'millhouse.workflow-execution-test/drift-input {:order 50})
      (let [source (io/file directory "execution-selector.clj")
            selected "(ns test.execution-selector (:require [millhouse.executors.code :as code] [millstrand.api.lifecycle.alpha :as lifecycle]))\n(lifecycle/use-resource! code/code-engine)\n"]
        (workflow/start! "legacy" (workflow/workflow "Legacy"
                                                     (workflow/gate :old "Old" :code
                                                                    :attributes {"code/running" "legacy-token"})) {})
        (spit source selected)
        (is (= :degraded (get-in (runtime/module! rt :code {:file (.getName source) :after [:workflow]})
                                 [:modules :code :lifecycle/outcomes :code-engine :status])))
        (is (zero? @calls))
        (workflow/complete! "legacy" {:by-identity "test-worker"})
        (is (= :applied (:status (runtime/module! rt :code {:file (.getName source) :after [:workflow]}))))
        (is (thrown? clojure.lang.ExceptionInfo (workflow/register-executor! :code (constantly nil))))
        (is (thrown? clojure.lang.ExceptionInfo
                     (weaver/add! rt {:title "Forged attempt" :attributes {"kind" "workflow-execution"}})))
        (is (thrown? clojure.lang.ExceptionInfo
                     (workflow/start! "forged" (workflow/workflow "Forged"
                                                                  (workflow/gate :fake "Fake" :code
                                                                                 :attributes {"execution/current" "forged-token"})) {})))
        (is (nil? (workflow/current-root "forged")))
        (testing "ordinary unregistered external gates retain manual completion"
          (workflow/start! "external" (workflow/workflow "External" (workflow/gate :wait "Wait" :external)) {})
          (is (:done (workflow/complete! "external" {:by-identity "test-worker"}))))
        (testing "failed claim writes no token, attempt row or retry action"
          (let [started (workflow/start! "claim" (workflow/workflow "Claim" (gate :check "invalid")) {})
                id (:id (first (:ready started)))
                failed (await-done rt "claim" id)
                retry (request "claim" id (:attempt-id failed) "retry")
                count-before (count (weaver/list rt [:= [:attr "kind"] "workflow-execution"] {}))]
            (weaver/update! rt id {:attributes {"code/fn" "millhouse.workflow-execution-test/callback"}})
            (is (thrown? clojure.lang.ExceptionInfo (workflow/burn! (:id (workflow/current-root "claim")))))
            (is (thrown? clojure.lang.ExceptionInfo
                         (graph/burn-by-ids! rt [(:id (workflow/current-root "claim"))])))
            (is (thrown? clojure.lang.ExceptionInfo
                         (weaver/update! rt (:id (workflow/current-root "claim")) {:state "closed"})))
            (let [row (first (weaver/list rt [:= [:attr "execution/token"] (:attempt-id failed)] {}))]
              (is (thrown? clojure.lang.ExceptionInfo (graph/burn-by-ids! rt [(:id row)]))))
            (reset! trap {:mode :claim :id id})
            (is (thrown? clojure.lang.ExceptionInfo (execution/retry! rt retry)))
            (is (= (:attempt-id failed) (:attempt-id (inspect rt "claim" id))))
            (is (= count-before (count (weaver/list rt [:= [:attr "kind"] "workflow-execution"] {}))))
            (is (empty? (weaver/list rt [:= [:attr "execution/action-key"] "retry"] {})))
            (is (= :accepted (:status (execution/retry! rt retry))))
            (is (= :succeeded (get-in (await-done rt "claim" id) [:result :outcome])))))
        (testing "failed completion retains the terminal attempt and closes no gate or join"
          (let [definition (workflow/workflow "Completion"
                                              (workflow/step :prepare "Prepare" :self)
                                              (workflow/call :join
                                                             (workflow/workflow "Nested" (gate :check "millhouse.workflow-execution-test/callback"))
                                                             {} :depends-on [:prepare])
                                              (workflow/step :next "Next" :self :depends-on [:join]))]
            (workflow/start! "completion" definition {})
            (let [id (:id (first (weaver/list rt [:and [:= [:attr "workflow/gate"] "code"]
                                                  [:= :state "active"]] {})))
                  before @calls]
              (reset! trap {:mode :completion :id id})
              (workflow/complete! "completion")
              (support/poll-until #(when (= :hold-completion (:mode @trap)) true))
              (is (= "active" (:state (weaver/show rt id))))
              (is (= :committing (:phase (inspect rt "completion" id))))
              (is (nil? (:result (inspect rt "completion" id))))
              (is (= [id] (mapv :id (workflow/ready "completion"))))
              (reset! trap nil)
              (execution/reconcile! rt {:run-id "completion" :step id})
              (is (= :succeeded (get-in (await-done rt "completion" id) [:result :outcome])))
              (is (= (inc before) @calls))
              (is (= "Next" (:title (first (workflow/ready "completion"))))))))
        (testing "unchanged selection, protected unstarted gates, freeze and atomic routed cutover"
          (reset! entered (CountDownLatch. 1))
          (reset! release (CountDownLatch. 1))
          (try
            (workflow/start!
             "route"
             (workflow/workflow "Route"
                                (workflow/step :wait "Wait" :self)
                                (gate :unstarted "millhouse.workflow-execution-test/callback" :depends-on [:wait])
                                (gate :live "millhouse.workflow-execution-test/blocked-callback")
                                (workflow/checkpoint :decide "Decide"
                                                     :choices [{:key :move :next 'millhouse.workflow-execution-test/replacement}])) {})
            (is (.await ^CountDownLatch @entered (support/await-budget-ms) TimeUnit/MILLISECONDS))
            (let [root (workflow/current-root "route")
                  frontier (workflow/ready "route")
                  checkpoint (:id (first (filter #(= "Decide" (:title %)) frontier)))
                  live (:id (first (filter #(= "live" (:title %)) frontier)))
                  before @calls]
              (is (= :unchanged (:status (runtime/module! rt :code {:file (.getName source) :after [:workflow]}))))
              (is (= before @calls))
              (is (thrown? clojure.lang.ExceptionInfo
                           (workflow/choose! "route" :move {} {:step checkpoint})))
              (is (= (:id root) (:id (workflow/current-root "route"))))
              (is (= "active" (:state (weaver/show rt live))))
              (let [freeze (execution/quiesce-run! rt "route" "Retire before routing")]
                (is (= :unknown (:status (execution/retire! rt freeze))))
                (.countDown ^CountDownLatch @release)
                (let [receipt (support/poll-until #(let [r (execution/retire! rt freeze)]
                                                     (when (= :settled (:status r)) r)))]
                  (is (thrown? clojure.lang.ExceptionInfo
                               (workflow/choose! "route" :move {} {:step checkpoint :retirement (assoc receipt :status :unknown)})))
                  (reset! trap {:mode :abandon :id (:id root)})
                  (is (thrown? clojure.lang.ExceptionInfo
                               (workflow/choose! "route" :move {} {:step checkpoint :retirement receipt})))
                  (is (= (:id root) (:id (workflow/current-root "route"))))
                  (is (= "active" (:state (weaver/show rt checkpoint))))
                  (is (= ["Replacement step"]
                         (mapv :title (:ready (workflow/choose! "route" :move {} {:step checkpoint :retirement receipt}))))))))
            (finally (.countDown ^CountDownLatch @release))))
        (testing "nearest nested root owns execution rather than its structural ancestor"
          (workflow/start! "outer" (workflow/workflow "Outer" (workflow/step :wait "Wait" :self)) {})
          (workflow/start! "inner" (workflow/workflow "Inner"
                                                      (workflow/step :wait "Wait" :self)
                                                      (gate :check "millhouse.workflow-execution-test/callback" :depends-on [:wait])) {})
          (weaver/update! rt (:id (workflow/current-root "outer"))
                          {:edges [{:type "parent-of" :to (:id (workflow/current-root "inner"))}]})
          (let [ready (workflow/complete! "inner")
                id (:id (first (:ready ready)))]
            (is (= "inner" (get-in (await-done rt "inner" id) [:result :run-id])))
            (is (= "active" (:state (workflow/current-root "outer"))))))
        (testing "postcommit root finalization reconciles without replaying the callback"
          (workflow/start! "finalize" (workflow/workflow "Finalize"
                                                         (workflow/step :ready "Ready" :self)
                                                         (gate :check "millhouse.workflow-execution-test/callback" :depends-on [:ready])) {})
          (let [root-id (:id (workflow/current-root "finalize"))
                before @calls]
            (reset! trap {:mode :hold-finalization :id root-id})
            (let [gate-id (:id (first (:ready (workflow/complete! "finalize"))))
                  done (await-done rt "finalize" gate-id)]
              (is (= :succeeded (get-in done [:result :outcome])))
              (is (get-in done [:cleanup :root-finalization-pending?]))
              (is (= "closed" (:state (weaver/show rt gate-id))))
              (is (= "active" (:state (weaver/show rt root-id))))
              (reset! trap nil)
              (support/poll-until #(let [view (execution/reconcile! rt {:run-id "finalize" :step gate-id})]
                                     (when (and (= :confirmed (get-in view [:cleanup :acknowledgement]))
                                                (not (get-in view [:cleanup :root-finalization-pending?]))) view)))
              (is (= "closed" (:state (weaver/show rt root-id))))
              (is (= (inc before) @calls))
              (is (false? (attr-get (first (weaver/list rt [:= [:attr "execution/token"] (:attempt-id done)] {}))
                                    :execution/reconcile))))))
        (testing "removing selection does not make unstarted managed gates manual"
          (doseq [run-id ["removed" "readopt"]]
            (workflow/start! run-id (workflow/workflow "Removed"
                                                       (workflow/step :wait "Wait" :self)
                                                       (gate :unstarted "millhouse.workflow-execution-test/callback" :depends-on [:wait])) {} {:family "execution-family"}))
          (spit source "(ns test.execution-selector)\n")
          (is (= :applied (:status (runtime/module! rt :code {:file (.getName source) :after [:workflow]}))))
          (workflow/complete! "removed")
          (let [id (:id (first (workflow/ready "removed")))]
            (is (= :unstarted (:phase (inspect rt "removed" id))))
            (is (= "execution/descriptor-missing" (get-in (inspect rt "removed" id) [:attention :code])))
            (is (thrown? clojure.lang.ExceptionInfo
                         (workflow/complete! "removed" {:step id :by-identity "test-worker"})))
            (is (thrown? clojure.lang.ExceptionInfo
                         (weaver/update! rt id {:state "closed" :attributes {"workflow/outcome" "cancelled"}})))))
        (testing "public abandonment composes only exact existing domain-row patches"
          (let [root (workflow/current-root "removed")
                freeze (execution/quiesce-run! rt "removed" "Replace retired work")
                receipt (execution/retire! rt freeze)
                domain (weaver/add! rt {:title "Domain reservation" :attributes {"test/state" "held"}})
                request {:run-id "removed" :root-id (:id root) :reason "Replace retired work"
                         :by-identity "test-worker" :retirement receipt
                         :workflow #'abort-replacement :params {}
                         :domain-patches [{:before domain :update {:attributes {"test/state" "released"}}}]}]
            (is (thrown? clojure.lang.ExceptionInfo
                         (execution/abandon-run! rt
                                                 (assoc-in request [:domain-patches 0 :update :attributes "kind"]
                                                           "workflow-execution"))))
            (is (thrown? clojure.lang.ExceptionInfo
                         (weaver/update! rt (:id domain) {:attributes {"kind" "workflow-execution"}})))
            (weaver/update! rt (:id domain) {:attributes {"test/drift" true}})
            (is (thrown? clojure.lang.ExceptionInfo (execution/abandon-run! rt request)))
            (is (= (:id root) (:id (workflow/current-root "removed"))))
            (is (= "held" (attr-get (weaver/show rt (:id domain)) :test/state)))
            (let [result (execution/abandon-run! rt
                                                 (assoc-in request [:domain-patches 0 :before]
                                                           (weaver/show rt (:id domain))))]
              (is (= ["Abort"] (mapv :title (:ready result))))
              (is (= "millhouse.workflow-execution-test/abort-replacement"
                     (attr-get (workflow/current-root "removed") :workflow/definition)))
              (is (= "execution-family" (attr-get (workflow/current-root "removed") :workflow/family)))
              (is (= "released" (attr-get (weaver/show rt (:id domain)) :test/state))))))
        (testing "the same descriptor can readopt persisted unstarted ownership"
          (spit source selected)
          (is (= :applied (:status (runtime/module! rt :code {:file (.getName source) :after [:workflow]}))))
          (let [result (workflow/complete! "readopt")
                id (:id (first (:ready result)))]
            (is (= :succeeded (get-in (await-done rt "readopt" id) [:result :outcome])))))))))
