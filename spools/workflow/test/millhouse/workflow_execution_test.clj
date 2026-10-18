(ns millhouse.workflow-execution-test
  "One file-backed transaction/lifecycle witness for the common execution kernel."
  (:require [clojure.java.io :as io]
            [clojure.spec.alpha :as s]
            [clojure.test :refer [deftest is testing]]
            [millhouse.test-support :as support]
            [millhouse.workflow :as workflow]
            [millhouse.workflow.execution :as execution]
            [millhouse.workflow.internal.execution.driver :as driver]
            [millstrand.api.current.alpha :as current]
            [millstrand.api.hooks.alpha :as hooks]
            [millstrand.api.graph.alpha :as graph]
            [millstrand.api.batch.alpha :as batch]
            [millstrand.api.runtime.alpha :as runtime]
            [millstrand.api.spool.alpha :refer [attr-get]]
            [millstrand.api.weaver.alpha :as weaver])
  (:import [java.util.concurrent Callable CountDownLatch FutureTask TimeUnit]))

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
                   :abandon (and close? (= id (some-> patch :ref name)))
                   :topology (and close? (= id (some-> patch :ref name)))
                   :observation (and (= "starting" (get-in patch [:attributes "execution/phase"]))
                                     (:dispatch-uncertain? (execution/inspect (current/runtime)
                                                                              {:run-id (:run-id expected) :step id})))
                   :hold-finalization (and close? (or (= id (:strand/id ctx))
                                                      (= id (some-> patch :ref name))))
                   :hold-completion close?
                   false)]
      (when match?
        (if (contains? #{:hold-completion :hold-finalization} mode)
          (throw (ex-info "Hold redelivery after before-image refusal" {}))
          (when (compare-and-set! trap expected (when (= :completion mode) {:mode :hold-completion :id id}))
            ;; A separate actor must not inherit the outer transaction's dynamic
            ;; authority. This writer commits before the planned batch starts.
            (let [rt (current/runtime)
                  _ (when-let [captured (:captured expected)]
                      (deliver captured (execution/inspect rt {:run-id (:run-id expected) :step id})))
                  write (FutureTask.
                         ^Callable
                         (fn []
                           (current/with-runtime rt
                             (if-let [payload (:writer-batch expected)]
                               (batch/apply! rt payload)
                               (weaver/update! rt id {:attributes {"test/drift" (name mode)}})))))
                  actor (Thread. write "execution-test-writer")]
              (.start actor)
              (try (.get write (long (support/await-budget-ms)) TimeUnit/MILLISECONDS)
                   (finally
                     (.interrupt actor)
                     (.join actor (long (support/await-budget-ms)))))))))))
  {:hook/value (:hook/value ctx)})

(s/def ::busy-request map?)
(s/def ::busy-result any?)

(defn busy-request "Capture input for the metadata-fence witness." [{:keys [gate]}]
  {:value (attr-get gate :test/value) :busy-proof/timeout-secs 30})

(defn busy-start "Reject admission without accepting work." [_ _] {:status :busy})

(defn busy-observe "Do not invent settlement for an absent accepted handle." [_ _]
  {:status :unknown :reason {:code "test/no-handle" :message "No accepted handle" :data {}}})

(defn busy-acknowledge "Acknowledge a settled test observation." [_ _] {:status :acknowledged})

(def busy-descriptor
  "A no-capacity adapter: this witness owns storage, not Code's physical pool."
  {:waiter :busy-proof :revision "test/v1"
   :request 'millhouse.workflow-execution-test/busy-request :request-spec ::busy-request :result-spec ::busy-result
   :start 'millhouse.workflow-execution-test/busy-start
   :observe 'millhouse.workflow-execution-test/busy-observe
   :stop 'millhouse.workflow-execution-test/busy-observe
   :acknowledge 'millhouse.workflow-execution-test/busy-acknowledge})

(defn provenance-start
  "Return opaque provenance at the public adapter boundary for the store witness."
  [_ _]
  (swap! calls inc)
  {:status :terminal :outcome :succeeded :settlement :settled :value "value"
   :executor-run-id "opaque-backend-run" :evidence {"retained" true}})

(def provenance-descriptor
  "Exercise the common transaction seam without a backend-specific lifecycle."
  (assoc busy-descriptor :waiter :provenance-proof
         :start 'millhouse.workflow-execution-test/provenance-start))

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

(deftest runtime-detach-preserves-attempts-while-removal-records-stop-intent
  (doseq [phase [:runtime-stop :remove]]
    (testing phase
      (support/with-embedded-runtime
        (fn [rt _]
          (support/activate-spool! rt :workflow 'millhouse.workflow)
          (let [resource (execution/open! rt busy-descriptor)]
            (try
              (let [started (workflow/start! "detach"
                                             (workflow/workflow
                                              "Detach"
                                              (workflow/gate :check "Check" :busy-proof)) {})
                    id (:id (first (:ready started)))
                    before (support/poll-until
                            #(let [v (inspect rt "detach" id)]
                               (when (and (:attempt-id v)
                                          (false? (:dispatch-uncertain? v))) v)))
                    retain driver/retain-ownership!]
                ;; Force reconciliation between draining publication and removal.
                ;; Assertions stay on public close/inspection behavior; this seam
                ;; schedules the race without sleeps or a second executor model.
                (with-redefs [driver/retain-ownership!
                              (fn [runtime descriptor]
                                (dotimes [_ 3]
                                  (execution/reconcile! runtime {:run-id "detach" :step id}))
                                (retain runtime descriptor))]
                  (execution/close! rt resource phase))
                (let [after (inspect rt "detach" id)]
                  (is (= (:attempt-id before) (:attempt-id after)))
                  (is (nil? (:result after)))
                  (if (= :runtime-stop phase)
                    (is (= (select-keys before [:phase :stop-reason :request :deadline])
                           (select-keys after [:phase :stop-reason :request :deadline])))
                    (is (= "Executor removed" (get-in after [:stop-reason :error :message]))))))
              (finally (execution/close! rt resource)))))))))

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
                     (weaver/add! rt {:title "Already completed" :state "closed"
                                      :attributes {"workflow/gate" "code"}})))
        (is (thrown? clojure.lang.ExceptionInfo
                     (workflow/start! "forged" (workflow/workflow "Forged"
                                                                  (workflow/gate :fake "Fake" :code
                                                                                 :attributes {"execution/current" "forged-token"})) {})))
        (is (nil? (workflow/current-root "forged")))
        (testing "ordinary unregistered external gates retain manual completion"
          (workflow/start! "external" (workflow/workflow "External" (workflow/gate :wait "Wait" :external)) {})
          (is (thrown? clojure.lang.ExceptionInfo
                       (execution/retry! rt (request "external" (:id (first (workflow/ready "external")))
                                                     "fabricated" "manual-retry"))))
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
            (let [root-id (:id (workflow/current-root "claim"))]
              (is (thrown? clojure.lang.ExceptionInfo
                           (batch/apply! rt {:refs {:root root-id} :strands [] :edges [] :burn [:root]})))
              (is (= root-id (:id (workflow/current-root "claim")))))
            (let [row (first (weaver/list rt [:= [:attr "execution/token"] (:attempt-id failed)] {}))]
              (is (thrown? clojure.lang.ExceptionInfo (graph/burn-by-ids! rt [(:id row)]))))
            (reset! trap {:mode :claim :id id})
            (is (thrown? clojure.lang.ExceptionInfo (execution/retry! rt retry)))
            (is (= "claim" (attr-get (weaver/show rt id) :test/drift)))
            (is (= (:attempt-id failed) (:attempt-id (inspect rt "claim" id))))
            (is (= count-before (count (weaver/list rt [:= [:attr "kind"] "workflow-execution"] {}))))
            (is (empty? (weaver/list rt [:= [:attr "execution/action-key"] "retry"] {})))
            (is (= :accepted (:status (execution/retry! rt retry))))
            (is (= :succeeded (get-in (await-done rt "claim" id) [:result :outcome])))
            (testing "public burns retain the original idempotent retry action"
              (let [action-id (:id (first (weaver/list rt [:= [:attr "execution/action-key"] "retry"] {})))]
                (is (thrown? clojure.lang.ExceptionInfo (graph/burn-by-ids! rt [action-id])))
                (is (thrown? clojure.lang.ExceptionInfo
                             (batch/apply! rt {:refs {:action action-id} :burn [:action]})))
                (is (= :replayed (:status (execution/retry! rt retry))))))))
        (testing "failed completion retains the terminal attempt and closes no gate or join"
          (let [resource (execution/open! rt provenance-descriptor)
                definition (workflow/workflow "Completion"
                                              (workflow/step :prepare "Prepare" :self)
                                              (workflow/call :join
                                                             (workflow/workflow "Nested" (workflow/gate :check "Check" :provenance-proof))
                                                             {} :depends-on [:prepare])
                                              (workflow/step :next "Next" :self :depends-on [:join]))]
            (workflow/start! "completion" definition {})
            (let [id (:id (first (weaver/list rt [:and [:= [:attr "workflow/gate"] "provenance-proof"]
                                                  [:= :state "active"]] {})))
                  before @calls]
              (reset! trap {:mode :completion :id id})
              (workflow/complete! "completion")
              (support/poll-until #(when (and (= :hold-completion (:mode @trap))
                                              (= "completion" (attr-get (weaver/show rt id) :test/drift))) true))
              ;; Join the first refused delivery before inspecting it. The hold
              ;; also refuses another delivery without hiding the original one.
              (is (thrown? clojure.lang.ExceptionInfo
                           (execution/reconcile! rt {:run-id "completion" :step id})))
              (is (= "active" (:state (weaver/show rt id))))
              (is (= :committing (:phase (inspect rt "completion" id))))
              (is (nil? (:result (inspect rt "completion" id))))
              (is (nil? (attr-get (weaver/show rt id) :workflow/executor-run-id)))
              (is (nil? (attr-get (weaver/show rt id) :workflow/outcome-by)))
              (is (= [id] (mapv :id (workflow/ready "completion"))))
              (reset! trap nil)
              (execution/reconcile! rt {:run-id "completion" :step id})
              (let [result (:result (await-done rt "completion" id))
                    gate (weaver/show rt id)]
                (is (= :succeeded (:outcome result)))
                (is (= "opaque-backend-run" (:executor-run-id result)
                       (attr-get gate :workflow/executor-run-id)))
                (is (= {"retained" true} (:evidence result)))
                (is (= "provenance-proof" (attr-get gate :workflow/executor)))
                (is (nil? (attr-get gate :workflow/outcome-by))))
              (is (= (inc before) @calls))
              (is (= "Next" (:title (first (workflow/ready "completion")))))
              (execution/close! rt resource))))
        (testing "observation facts do not depend on mutable request-source metadata"
          (let [resource (execution/open! rt busy-descriptor)]
            (try
              (workflow/start! "busy-metadata"
                               (workflow/workflow "Busy metadata"
                                                  (workflow/step :wait "Wait" :self)
                                                  (workflow/gate :pending "Pending" :busy-proof :depends-on [:wait]
                                                                 :attributes {"test/value" "original"})) {})
              (let [root-id (:id (workflow/current-root "busy-metadata"))
                    gate-id (:id (first (filter #(= "busy-proof" (attr-get % :workflow/gate))
                                                (:strands (graph/subgraph rt [root-id])))))
                    captured (promise)]
                (reset! trap {:mode :observation :id gate-id :run-id "busy-metadata" :captured captured
                              :writer-batch {:refs {:gate gate-id :root root-id}
                                             :strands [{:ref :gate :attributes {"test/value" "edited"}}
                                                       {:ref :root :attributes {"test/metadata" "edited"}}]}})
                (workflow/complete! "busy-metadata")
                (let [before (deref captured (support/await-budget-ms) nil)
                      after (support/poll-until
                             #(let [v (execution/reconcile! rt {:run-id "busy-metadata" :step gate-id})]
                                (when (and (:attempt-id v) (false? (:dispatch-uncertain? v))) v))
                             {:on-timeout #(throw (ex-info "Known busy response was lost" (inspect rt "busy-metadata" gate-id)))})]
                  (is (some? before))
                  (is (= "edited" (attr-get (weaver/show rt gate-id) :test/value)))
                  (is (= "edited" (attr-get (weaver/show rt root-id) :test/metadata)))
                  (is (= "original" (get-in after [:request :value])))
                  (is (= (select-keys before [:attempt-id :request :deadline])
                         (select-keys after [:attempt-id :request :deadline])))
                  (is (false? (:accepted? after)))
                  (let [freeze (execution/quiesce-run! rt "busy-metadata" "Stop an unaccepted attempt")]
                    (is (= :settled (:status (execution/retire! rt freeze))))
                    (is (= :cancelled (get-in (await-done rt "busy-metadata" gate-id) [:result :outcome]))))))
              (finally (reset! trap nil) (execution/close! rt resource)))))
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
                  (is (= "abandon" (attr-get (weaver/show rt (:id root)) :test/drift)))
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
          (let [outer (:id (workflow/current-root "outer"))
                inner (:id (workflow/current-root "inner"))
                freeze (execution/quiesce-run! rt "outer" "Retire only this root")
                receipt (execution/retire! rt freeze)
                before (graph/subgraph rt [outer])]
            (is (= {} (:attempts freeze)))
            (is (= :settled (:status receipt)))
            (is (thrown? clojure.lang.ExceptionInfo
                         (execution/abandon-run! rt {:run-id "outer" :root-id outer
                                                     :reason "Retire only this root" :by-identity "test-worker"
                                                     :retirement receipt :workflow #'abort-replacement :params {}})))
            (is (= before (graph/subgraph rt [outer])))
            (is (= outer (:id (workflow/current-root "outer"))))
            (execution/resume-run! rt "outer" receipt)
            (testing "an edge-only writer cannot change ownership after planning"
              (workflow/start! "edge-fence" (workflow/workflow "Edge fence"
                                                               (workflow/step :wait "Wait" :self)
                                                               (gate :check "millhouse.workflow-execution-test/callback" :depends-on [:wait])) {})
              (let [root-id (:id (workflow/current-root "edge-fence"))
                    gate-id (:id (first (filter #(= "code" (attr-get % :workflow/gate))
                                                (:strands (graph/subgraph rt [root-id])))))
                    freeze (execution/quiesce-run! rt "edge-fence" "Fence topology")
                    receipt (execution/retire! rt freeze)
                    rows (mapv #(weaver/show rt %) [root-id gate-id inner])
                    edge {:refs {:parent inner :gate gate-id} :strands []
                          :edges [{:op :upsert :from :parent :to :gate :type "parent-of"}]}
                    request {:run-id "edge-fence" :root-id root-id :reason "Fence topology"
                             :by-identity "test-worker" :retirement receipt
                             :workflow #'abort-replacement :params {}}]
                (reset! trap {:mode :topology :id root-id :writer-batch edge})
                (is (thrown? clojure.lang.ExceptionInfo (execution/abandon-run! rt request)))
                (is (= rows (mapv #(weaver/show rt %) [root-id gate-id inner])))
                (is (= #{root-id inner}
                       (set (map :from_strand_id (graph/incoming-edges rt [gate-id] "parent-of")))))
                (is (= root-id (:id (workflow/current-root "edge-fence"))))
                (batch/apply! rt (assoc-in edge [:edges 0 :op] :remove))
                (is (= ["Abort"] (mapv :title (:ready (execution/abandon-run! rt request))))))))
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
