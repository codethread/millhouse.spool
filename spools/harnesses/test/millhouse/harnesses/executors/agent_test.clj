(ns millhouse.harnesses.executors.agent-test
  "Thin Agent mapping and real publication proofs with a nonexecuting provider."
  (:require [clojure.string :as str]
            [clojure.test :refer [deftest is testing]]
            [millhouse.harnesses :as harnesses]
            [millhouse.harnesses.executors.agent :as agent]
            [millhouse.workflow :as workflow]
            [millhouse.workflow.execution :as execution]
            [millstrand.api.current.alpha :as current]
            [millstrand.api.events.alpha :as events]
            [millstrand.api.runtime.alpha :as runtime]
            [millstrand.api.spool.alpha :refer [attr-get]]
            [millstrand.api.weaver.alpha :as weaver]
            [millstrand.test.alpha :as t]))

(defn- await-view [rt selector predicate]
  (let [deadline (+ (System/nanoTime) 10000000000)]
    (loop []
      (let [view (execution/reconcile! rt selector)]
        (cond
          (predicate view) view
          (> (System/nanoTime) deadline) (throw (ex-info "Agent proof timed out" {:view view}))
          :else (do (Thread/yield) (recur)))))))

(defn lose-first-publication-response!
  "Inject a lost response after real publication; the kernel must adopt by key."
  [rt context]
  (let [result (agent/start! rt context)
        lost? (runtime/spool-state rt ::lost-response {:version 1} #(atom false))]
    (if (compare-and-set! lost? false true)
      (throw (ex-info "Lost publication response" {}))
      result)))

(defn- with-agent-runtime [f]
  (t/run-with-bare-runtime
   {:storage :sqlite-file}
   (fn [{rt :runtime}]
     (current/with-runtime rt
       (t/activate-module! rt :identity 'millhouse.identity)
       (t/activate-module! rt :workflow 'millhouse.workflow)
       (harnesses/open-harness-core! {:runtime rt})
       (harnesses/register-harness! rt :fake
                                    {:modes #{:headless} :prepare 'millhouse.harnesses/create!
                                     :finish 'millhouse.harnesses/finish!})
       ;; No Harnesses execution resource: publication is real, no provider launches.
       (let [resource (execution/open!
                       rt (assoc agent/executor :start
                                 'millhouse.harnesses.executors.agent-test/lose-first-publication-response!))]
         (try (f rt)
              (finally (execution/close! rt resource))))))))

(defn- definition [attributes]
  (workflow/workflow
   "Agent review"
   (workflow/gate :review "Review" :agent :attributes attributes)
   (workflow/checkpoint :decide "Review decision" :depends-on [:review] :choices [:accept :reject])))

(defn- start-gate! [run-id attributes]
  {:run-id run-id
   :step (:id (first (:ready (workflow/start! run-id (definition attributes) {}))))})

(defn- retry-request [selector token key]
  (merge selector {:expected-attempt token :request-id key :reason "Corrected review input"
                   :by-identity "test-worker"}))

(defn- serving [rt gate]
  (weaver/list rt [:and [:= [:attr "harness/run"] "true"] [:edge/out "serves" [:= :id gate]]] {}))

(deftest projection-freezes-fallback-and-rejects-explicit-malformed-prompts
  (let [input {:run-id "workflow" :attempt-id "attempt"
               :gate {:id "gate" :title "Title"
                      :attributes {"harness/alias" "fake" "description" "Description"
                                   "workflow/instruction" "Instruction"}}}]
    (is (= "Instruction" (:prompt (agent/request input))))
    (is (= "Description" (:prompt (agent/request (update-in input [:gate :attributes] dissoc "workflow/instruction")))))
    (is (= "Title" (:prompt (agent/request (assoc-in input [:gate :attributes] {"harness/alias" "fake"})))))
    (doseq [value [nil "" "  " 1]]
      (is (thrown? clojure.lang.ExceptionInfo
                   (agent/request (assoc-in input [:gate :attributes "harness/prompt"] value)))))))

(deftest publication-adoption-frozen-repair-and-separate-decision
  (with-agent-runtime
    (fn [rt]
      (let [selector (start-gate! "review" {"harness/alias" "fake" "harness/prompt" "Original"
                                            "harness/cwd" "/tmp" "harness/model" "original-model"
                                            "harness.pi/thinking" "high"
                                            "harness/appended-system-prompts" ["Existing policy"]})
            first-view (await-view rt selector :reference)
            first-id (:reference first-view)
            first-request (:request first-view)
            context {:request first-request :request-id (str "execution/" (:attempt-id first-view) "/start")}
            first-run (harnesses/run rt first-id)]
        (is (= "/tmp" (attr-get first-run :harness/cwd)))
        (is (= "high" (attr-get first-run :harness.pi/thinking)))
        (is (= "Existing policy" (first (attr-get first-run :harness/appended-system-prompts))))
        (is (str/includes? (second (attr-get first-run :harness/appended-system-prompts)) "Your final message"))
        (testing "lost publication response adopts the same request, without a reference"
          (is (= first-id (:reference (agent/observe! rt context))))
          (is (= first-id (:reference (agent/start! rt context))))
          (dotimes [_ 3] (execution/reconcile! rt selector))
          (is (= 1 (count (serving rt (:step selector))))))
        (weaver/update! rt (:step selector)
                        {:attributes {"harness/prompt" "Corrected" "harness/model" "corrected-model"}})
        (is (= first-request (:request (execution/inspect rt selector))))
        (is (= first-id (:reference (agent/start! rt context))))
        (is (= "Original" (attr-get (harnesses/run rt first-id) :harness/prompt)))
        (is (= "original-model" (attr-get (harnesses/run rt first-id) :harness/model)))
        (harnesses/finish! rt first-id {:status :failed :exit-code 7 :error "Provider failed"})
        (let [failed (await-view rt selector #(= :done (:phase %)))
              retry (retry-request selector (:attempt-id failed) "retry-review")]
          (is (= :failed (get-in failed [:result :outcome])))
          (is (= "active" (:state (weaver/show rt (:step selector)))))
          (doseq [operation [#(harnesses/retry! rt first-id {})
                             #(harnesses/resume! rt first-id {:request-id "forbidden-resume"})]]
            (is (thrown-with-msg? clojure.lang.ExceptionInfo #"workflow retry" (operation))))
          (is (false? (:eligible? (harnesses/resume-eligibility rt first-id))))
          (is (= :accepted (:status (execution/retry! rt retry))))
          (let [replacement (await-view rt selector :reference)
                second-id (:reference replacement)]
            (is (not= first-id second-id))
            (is (not= (:attempt-id failed) (:attempt-id replacement)))
            (is (= "Corrected" (get-in replacement [:request :prompt])))
            (is (= "corrected-model" (attr-get (harnesses/run rt second-id) :harness/model)))
            (is (= :replayed (:status (execution/retry! rt retry))))
            (testing "late old completion and observation cannot complete the replacement"
              (harnesses/finish! rt first-id {:status :done :exit-code 0 :result "stale findings"})
              (is (= :failed (:outcome (agent/observe! rt context))))
              (dotimes [_ 3] (execution/reconcile! rt selector))
              (is (nil? (:result (execution/inspect rt selector))))
              (is (= "active" (:state (weaver/show rt (:step selector))))))
            (harnesses/finish! rt second-id {:status :done :exit-code 0 :result "No findings"})
            (let [finished (await-view rt selector #(= :confirmed (get-in % [:cleanup :acknowledgement])))
                  gate (weaver/show rt (:step selector))]
              (is (= {:run-id second-id :result "No findings"} (get-in finished [:result :value])))
              (is (= second-id (get-in finished [:result :executor-run-id])
                     (attr-get gate :workflow/executor-run-id)))
              (is (= "agent" (attr-get gate :workflow/executor)))
              (is (nil? (attr-get gate :workflow/outcome-by)))
              (is (= "closed" (:state gate)))
              (is (= "Review decision" (:title (first (workflow/ready "review")))))
              (is (= "active" (:state (weaver/show rt (:id (first (workflow/ready "review")))))))
              (is (= 2 (count (serving rt (:step selector))))))))))))

(deftest invalid-input-retry-and-exact-stop-retain-blocking-evidence
  (with-agent-runtime
    (fn [rt]
      (let [selector (start-gate! "invalid" {"harness/alias" "fake" "harness/prompt" " "})
            failed (await-view rt selector #(= :done (:phase %)))]
        (is (= :failed (get-in failed [:result :outcome])))
        (is (= :settled (get-in failed [:result :settlement])))
        (is (false? (:accepted? failed)))
        (is (empty? (serving rt (:step selector))))
        (weaver/update! rt (:step selector) {:attributes {"harness/prompt" "Fixed"}})
        (execution/retry! rt (retry-request selector (:attempt-id failed) "repair-invalid"))
        (let [running (await-view rt selector :reference)
              id (:reference running)
              context {:request-id (str "execution/" (:attempt-id running) "/start") :reference id}
              unrelated (harnesses/create! rt {:harness "fake" :prompt "Unrelated"})]
          (harnesses/finish! rt id {:status :failed :error "Custody unknown"})
          (is (= :unknown (:status (agent/observe! rt context))))
          (dotimes [_ 3] (execution/reconcile! rt selector))
          (is (nil? (:result (execution/inspect rt selector))))
          (harnesses/settle! rt id {:settled true :settlement "process-exit"})
          (let [settled (await-view rt selector #(= :done (:phase %)))]
            (execution/retry! rt (retry-request selector (:attempt-id settled) "retry-stopped")))
          (let [replacement (await-view rt selector :reference)
                stop-context {:request-id (str "execution/" (:attempt-id replacement) "/start")
                              :reference (:reference replacement)}]
            (is (= :cancelled (:outcome (agent/stop! rt stop-context))))
            (is (= "ready" (attr-get (harnesses/run rt (:id unrelated)) :harness/status)))
            (is (= :cancelled (get-in (await-view rt selector #(= :done (:phase %))) [:result :outcome])))
            (is (= "active" (:state (weaver/show rt (:step selector)))))))))))

(deftest optional-selector-owns-only-the-common-driver
  (t/run-with-bare-runtime
   {:storage :sqlite-file}
   (fn [{rt :runtime}]
     (current/with-runtime rt
       (t/activate-module! rt :identity 'millhouse.identity)
       (t/activate-module! rt :workflow 'millhouse.workflow)
       (is (thrown-with-msg? clojure.lang.ExceptionInfo #"requires harness execution"
                             (agent/open-agent-engine! {:runtime rt})))
       (t/activate-module! rt :harnesses 'millhouse.harnesses.spool)
       (is (not (contains? (workflow/executors) :agent)))
       (t/activate-module! rt :agent 'millhouse.harnesses.executors.agent.spool)
       (is (contains? (workflow/executors) :agent))
       (is (not-any? #(= :agent/engine (:key %)) (events/handlers rt)))
       (is (= 1 (count (filter #(= :workflow/execution (:key %)) (events/handlers rt)))))
       (is (= :applied (get-in (runtime/status rt)
                               [:last-refresh :modules :agent :lifecycle/outcomes :agent-engine :status])))))))
