(ns millhouse.spools.auto-run-test
  "Disposable Weaver tests for card admission and durable assignment receipts."
  (:require [clojure.test :refer [deftest is testing]]
            [millhouse.spools.auto-run :as auto-run]
            [millhouse.spools.auto-run-reporting :as reporting]
            [millhouse.spools.auto-run-worktree :as worktree]
            [ct.spools.harnesses :as harnesses]
            [millhouse.spools.kanban :as kanban]
            [millhouse.spools.workflow :as workflow]
            [millstrand.api.patterns.alpha :as patterns]
            [millstrand.api.current.alpha :as current]
            [millstrand.api.runtime.alpha :as runtime]
            [millstrand.api.scheduler.alpha :as scheduler]
            [millstrand.api.spool.alpha :refer [attr-get]]
            [millstrand.api.weaver.alpha :as weaver]
            [millstrand.test.alpha :as t]))

(def ^:private fixture
  "(ns auto-run.fixture
     (:require [clojure.java.io :as io]
               [clojure.spec.alpha :as s]
               [clojure.string :as str]
               [ct.spools.harnesses :as harnesses]
               [ct.spools.harnesses.assignment :as assignment]
               [millhouse.spools.workflow :as workflow]
               [millstrand.api.lifecycle.alpha :as lifecycle]
               [millstrand.api.spool.alpha :refer [attr-get]]
               [millstrand.api.weaver.alpha :as weaver]))
   (lifecycle/use-resource! harnesses/harness-core-runtime assignment/assignment-runtime)
   (s/def ::card (s/and string? (complement str/blank?)))
   (s/def ::delivery-params (s/keys :req-un [::card]))
   (workflow/defworkflow! deliver
     \"A worker-driven delivery ending at human acceptance.\"
     {:entrypoints #{:start} :param-spec ::delivery-params}
     (workflow/workflow \"Delivery\"
       (workflow/step :implement \"Implement\" :self)
       (workflow/checkpoint :accept \"Human acceptance\"
         :depends-on [:implement] :kind :human
         :choices [{:key :approved :label \"Approved\"}])))
   (defn prepare! [_rt {:keys [repo card]}]
     (let [cwd (io/file repo (:id card))]
       (.mkdirs cwd)
       {:cwd (.getCanonicalPath cwd) :branch (str \"auto/\" (:id card))}))
   (defn withdrawn! [rt {:keys [card] :as request}]
     (weaver/update! rt (:id card) {:attributes {:kanban/lane \"refinement\"}})
     (prepare! rt request))
   (defn revised! [rt {:keys [card] :as request}]
     (weaver/update! rt (:id card) {:attributes {:acme/review-scope \"prepared\"}})
     (prepare! rt request))
   (defn broken! [_rt _request] (throw (ex-info \"No worktree capacity\" {})))
   (defn no-land! [_rt _request] nil)
   (defn unreadable-land! [_rt _request]
     (throw (ex-info \"Land evidence store is offline\" {})))
   (defn start-params! [_rt {:keys [card settings prepared]}]
     {:repository-param (str (:id card) \"/\" (:workflow settings) \"/\"
                             (:branch prepared))
      :review-scope (attr-get card :acme/review-scope)})
   (defn withdraw-start-params! [rt {:keys [card]}]
     (weaver/update! rt (:id card) {:attributes {:kanban/lane \"refinement\"}})
     {:repository-param \"withdrawn\"})
   (defn retitle-start-params! [rt {:keys [card]}]
     (weaver/update! rt (:id card) {:title \"Retitled feature\"})
     {:repository-param \"retitled\"})
   (defn corrupt-start-params! [rt {:keys [card]}]
     (weaver/update! rt (:id card)
                     {:attributes
                      (case (attr-get card :acme/mutation)
                        \"type\" {:kanban/type \"epic\"}
                        \"card\" {:kanban/card nil}
                        \"receipt\" {:auto-run/request-id \"replacement\"})})
     {:repository-param \"corrupt\"})
   (defn invalid-start-params! [_rt _request] [:not-a-map])
   (defn conflicting-start-params! [_rt _request] {:seat \"other\"})
   (defn broken-start-params! [_rt _request]
     (throw (ex-info \"No repository workflow parameters\" {})))")

(defn- with-world [f]
  (t/with-weaver-world
    [ctx {:storage :sqlite-file
          :deps-edn
          (pr-str
           {:deps
            {'millhouse.spools/auto-run
             {:local/root (.getCanonicalPath
                           (t/spool-checkout-root
                            "millhouse/spools/auto_run.clj"))}}})
          :init-clj
          "(require '[millstrand.api.current.alpha :as current]
                    '[millstrand.api.runtime.alpha :as runtime])
           (runtime/module! (current/runtime) :identity
             {:ns 'millhouse.spools.identity :required? true})
           (runtime/module! (current/runtime) :workflow
             {:ns 'millhouse.spools.workflow :required? true})
           (runtime/module! (current/runtime) :fixture
             {:file \"fixture.clj\" :after [:identity :workflow] :required? true})"
          :files {"fixture.clj" fixture
                  "signal_labels.clj"
                  "(ns auto-run.signal-labels
                     (:require [millhouse.spools.auto-run-reporting :as reporting]
                               [millstrand.api.millstrand.alpha :as millstrand]))
                   (millstrand/use-hook! reporting/derive-labels)
                   (millstrand/use-pattern! reporting/auto-run-needs-decision
                                            reporting/auto-run-unknown-failure
                                            reporting/auto-run-unblock)"}}]
    (let [rt (:runtime ctx)
          config {:repo (:config-dir ctx) :seat "fake" :effort "high"
                  :workflow "deliver" :workflows #{"deliver"}
                  :prepare 'auto-run.fixture/prepare!
                  :enabled? true :max-running 1 :interval-ms 3600000}]
      (harnesses/register-harness!
       rt :fake {:modes #{:headless}
                 :prepare 'ct.spools.harnesses/create!
                 :finish 'ct.spools.harnesses/finish!})
      (auto-run/configure! rt config)
      (f rt config))))

(defn- card! [rt attrs & [edges]]
  (weaver/add! rt (cond-> {:title "A bounded feature"
                           :attributes (merge {:kanban/card "true"
                                               :kanban/type "feature"
                                               :kanban/lane "pending"
                                               :kanban/priority "p2"
                                               :kanban.label/auto-run "true"}
                                              attrs)}
                    edges (assoc :edges edges))))

(defn- show [rt card key]
  (attr-get (weaver/show rt (:id card)) key))

(defn- claim-card! [rt card owner]
  (kanban/claim! rt (:id card)
                 {"--owner" owner
                  "--branch" (str "claimed/" (:id card))})
  ;; Keep the card otherwise admissible so this fixture isolates the durable
  ;; ownership projection from the legacy scalar and lane snapshots.
  (weaver/update! rt (:id card)
                  {:attributes {:kanban/lane "pending" :owner nil}})
  card)

(deftest eligibility-uses-current-ownership-not-scalar-or-participation-history
  (with-world
    (fn [rt _config]
      (let [unowned (card! rt {:owner "legacy-snapshot"
                               :identity/by-identity "historical-actor"
                               :kanban/reported-by "original-reporter"})
            owned (claim-card! rt (card! rt {}) "unregistered-owner")]
        (is (auto-run/eligible? rt (weaver/show rt (:id unowned))))
        (is (not (auto-run/eligible? rt (weaver/show rt (:id owned)))))
        (is (= "unregistered-owner"
               (:owner (kanban/current-ownership rt (:id owned)))))
        (doseq [[key value] [[:kanban/type "epic"] [:kanban/lane "refinement"]
                             [:kanban.label/auto-run "false"]
                             [:auto-run/status "assigned"] [:auto-run/status "error"]
                             [:auto-run/request-id "previous"]]]
          (let [card (card! rt {key value})]
            (is (not (auto-run/eligible? rt (weaver/show rt (:id card)))))))
        (weaver/update! rt (:id unowned) {:state "closed"})
        (is (not (auto-run/eligible? rt (weaver/show rt (:id unowned)))))))))

(deftest admission-respects-dependencies-overrides-capacity-and-replay
  (with-world
    (fn [rt _config]
      (let [blocker (weaver/add! rt {:title "Prerequisite"})
            blocked (card! rt {} [{:type "depends-on" :to (:id blocker)}])
            unlabelled (card! rt {:kanban.label/auto-run nil})
            refinement (card! rt {:kanban/lane "refinement"})
            owner (claim-card! rt (card! rt {}) "unresolved-manual-worker")
            selected (card! rt {:kanban/priority "p1" :auto-run/effort "low"})
            later (card! rt {:kanban/priority "p3"})
            result (auto-run/scan! rt "manual-scanner")
            run (weaver/show rt (get-in result [:dispatched 0 :run]))]
        (is (= [(:id selected)] (mapv :card (:dispatched result))))
        (is (= "low" (attr-get run :harness/effort)))
        (is (= (:id selected) (attr-get run :harness/target)))
        (is (= "manual-scanner" (attr-get run :identity/by-identity)))
        (is (= "assigned" (show rt selected :auto-run/status)))
        (is (= "pending" (show rt selected :kanban/lane)) "Worker, not dispatcher, claims")
        (is (nil? (show rt selected :owner)))
        (is (= "fake" (show rt selected :auto-run/effective-seat)))
        (current/with-runtime rt
          (let [root (workflow/current-root (show rt selected :auto-run/workflow-run-id))]
            (is (some? root))
            (is (= #{:card :feature :worktree :branch :seat :effort}
                   (set (keys (attr-get root :workflow/context)))))
            (is (= (:id selected)
                   (get (attr-get root :workflow/context) :card)))))
        (doseq [untouched [blocked unlabelled refinement owner later]]
          (is (nil? (show rt untouched :auto-run/status))))
        (is (empty? (:dispatched (auto-run/scan! rt))))
        (testing "a terminal assignment does not automatically rearm its card"
          (weaver/update! rt (:id run)
                          {:state "closed"
                           :attributes {:harness/status "stopped" :harness/settled "true"}})
          (weaver/update! rt (:id blocker) {:state "closed"})
          (is (= [(:id blocked)] (mapv :card (:dispatched (auto-run/scan! rt)))))
          (is (= 2 (count (weaver/list rt [:= [:attr "harness/run"] "true"] {})))))))))

(deftest repository-parameters-reach-workflows-without-recovery-duplication
  (with-world
    (fn [rt config]
      (auto-run/configure! rt (assoc config
                                     :prepare 'auto-run.fixture/revised!
                                     :start-params 'auto-run.fixture/start-params!))
      (let [card (card! rt {:acme/review-scope "initial"})
            run-id (get-in (auto-run/scan! rt) [:dispatched 0 :run])
            workflow-run-id (show rt card :auto-run/workflow-run-id)]
        (current/with-runtime rt
          (let [root (workflow/current-root workflow-run-id)]
            (is (= (str (:id card) "/deliver/auto/" (:id card))
                   (get (attr-get root :workflow/context) :repository-param)))
            (is (= "prepared"
                   (get (attr-get root :workflow/context) :review-scope)))))
        (weaver/update! rt (:id card)
                        {:attributes {:auto-run/status "preparing" :auto-run/run-id nil}})
        (auto-run/scan! rt)
        (is (= run-id (show rt card :auto-run/run-id)))
        (is (= 1 (count (weaver/list rt [:= [:attr "harness/run"] "true"] {}))))))))

(deftest callback-title-edits-reach-workflow-context
  (with-world
    (fn [rt config]
      (auto-run/configure! rt (assoc config
                                     :start-params 'auto-run.fixture/retitle-start-params!))
      (let [card (card! rt {})]
        (auto-run/scan! rt)
        (current/with-runtime rt
          (let [root (workflow/current-root (show rt card :auto-run/workflow-run-id))]
            (is (= "Retitled feature" (get (attr-get root :workflow/context) :feature)))
            (is (= (:id card) (get (attr-get root :workflow/context) :card)))))))))

(deftest card-edits-during-workflow-parameter-callback-cancel-admission
  (with-world
    (fn [rt config]
      (auto-run/configure! rt (assoc config
                                     :start-params 'auto-run.fixture/withdraw-start-params!))
      (let [card (card! rt {})]
        (auto-run/scan! rt)
        (is (= "error" (show rt card :auto-run/status)))
        (is (= "refinement" (show rt card :kanban/lane)))
        (is (empty? (weaver/list rt [:= [:attr "harness/run"] "true"] {})))
        (current/with-runtime rt
          (is (nil? (workflow/current-root (show rt card :auto-run/workflow-run-id)))))))))

(deftest callback-cannot-corrupt-card-or-dispatch-receipt
  (with-world
    (fn [rt config]
      (auto-run/configure! rt (assoc config
                                     :start-params 'auto-run.fixture/corrupt-start-params!))
      (doseq [mutation ["type" "card" "receipt"]]
        (let [card (card! rt {:acme/mutation mutation})]
          (auto-run/scan! rt)
          (is (= "error" (show rt card :auto-run/status)))
          (is (empty? (weaver/list rt [:= [:attr "harness/run"] "true"] {})))
          (current/with-runtime rt
            (is (nil? (workflow/current-root
                       (show rt card :auto-run/workflow-run-id))))))))))

(deftest invalid-workflow-parameter-callbacks-stall-without-assignment
  (with-world
    (fn [rt config]
      (doseq [[callback error]
              [['auto-run.fixture/invalid-start-params! "Invalid auto-run workflow parameter result"]
               ['auto-run.fixture/conflicting-start-params!
                "Auto-run workflow parameters cannot override dispatcher fields"]
               ['auto-run.fixture/broken-start-params! "No repository workflow parameters"]]]
        (auto-run/configure! rt (assoc config :start-params callback))
        (let [card (card! rt {})]
          (auto-run/scan! rt)
          (is (= "error" (show rt card :auto-run/status)))
          (is (re-find (re-pattern error) (show rt card :auto-run/error)))
          (is (nil? (show rt card :auto-run/run-id)))
          (is (empty? (:dispatched (auto-run/scan! rt))))
          (current/with-runtime rt
            (is (nil? (workflow/current-root (show rt card :auto-run/workflow-run-id)))))))
      (is (empty? (weaver/list rt [:= [:attr "harness/run"] "true"] {}))))))

(deftest invalid-card-and-preparation-errors-stall-without-retry
  (with-world
    (fn [rt config]
      (let [invalid (card! rt {:auto-run/workflow "not-allowed"})]
        (is (seq (:dispatched (auto-run/scan! rt))))
        (is (= "error" (show rt invalid :auto-run/status)))
        (is (nil? (show rt invalid :auto-run/run-id)))
        (is (empty? (:dispatched (auto-run/scan! rt)))))
      (auto-run/configure! rt (assoc config :prepare 'auto-run.fixture/broken!))
      (let [broken (card! rt {})]
        (auto-run/scan! rt)
        (is (= "No worktree capacity" (show rt broken :auto-run/error)))
        (is (empty? (:dispatched (auto-run/scan! rt))))
        (is (empty? (weaver/list rt [:= [:attr "harness/run"] "true"] {})))))))

(deftest board-edits-during-preparation-do-not-pour-an-unused-workflow
  (with-world
    (fn [rt config]
      (auto-run/configure! rt (assoc config :prepare 'auto-run.fixture/withdrawn!))
      (let [card (card! rt {})]
        (auto-run/scan! rt)
        (is (= "error" (show rt card :auto-run/status)))
        (is (= "refinement" (show rt card :kanban/lane)))
        (is (empty? (weaver/list rt [:= [:attr "harness/run"] "true"] {})))
        (current/with-runtime rt
          (is (nil? (workflow/current-root (show rt card :auto-run/workflow-run-id)))))))))

(deftest interrupted-publication-adopts-but-incomplete-preparation-needs-intervention
  (with-world
    (fn [rt _config]
      (let [card (card! rt {})
            run-id (get-in (auto-run/scan! rt) [:dispatched 0 :run])]
        (weaver/update! rt (:id card)
                        {:attributes {:auto-run/status "preparing" :auto-run/run-id nil}})
        (auto-run/scan! rt)
        (is (= run-id (show rt card :auto-run/run-id)))
        (is (= "assigned" (show rt card :auto-run/status)))
        (is (= 1 (count (weaver/list rt [:= [:attr "harness/run"] "true"] {})))))
      (let [card (card! rt {:auto-run/status "preparing" :auto-run/request-id "interrupted"})]
        (auto-run/scan! rt)
        (is (= "error" (show rt card :auto-run/status)))
        (is (re-find #"interrupted" (show rt card :auto-run/error)))))))

(deftest disable-and-stale-wakes-cannot-admit-work
  (with-world
    (fn [rt config]
      (let [card (card! rt {})
            wake (some #(when (= "codethread/auto-run" (:key %)) %) (scheduler/pending rt))]
        (auto-run/configure! rt (assoc config :enabled? false))
        (auto-run/wake! {:runtime rt :payload (:payload wake)})
        (is (empty? (:dispatched (auto-run/scan! rt))))
        (is (nil? (show rt card :auto-run/status)))
        (auto-run/configure! rt config)
        (auto-run/wake! {:runtime rt :payload (:payload wake)})
        (is (nil? (show rt card :auto-run/status)))
        (let [fresh (some #(when (= "codethread/auto-run" (:key %)) %) (scheduler/pending rt))]
          (auto-run/wake! {:runtime rt :payload (:payload fresh)})
          (is (= "assigned" (show rt card :auto-run/status))
              (show rt card :auto-run/error))
          (let [run (weaver/show rt (show rt card :auto-run/run-id))]
            (is (nil? (attr-get run :identity/by-identity))
                "scheduler wakes do not fabricate a caller")))
        (auto-run/stop! rt)
        (is (false? (:enabled (auto-run/status rt))))))))

(deftest explanation-is-read-only-and-land-availability-is-bounded
  (with-world
    (fn [rt config]
      (let [card (card! rt {})
            _ (auto-run/scan! rt)
            snapshot #(hash-map
                       :strands (count (weaver/list rt [:not [:missing :id]] {}))
                       :runs (count (weaver/list rt [:= [:attr "harness/run"] "true"] {}))
                       :wakes (count (scheduler/pending rt)))
            before (snapshot)
            unsupported (auto-run/explain rt (:id card))]
        (is (= before (snapshot)))
        (is (= "codethread.auto-run.explain/v1" (:schema-version unsupported)))
        (is (= "unsupported" (get-in unsupported [:land :availability])))
        (is (= "unknown" (get-in unsupported [:evidence :merge-boundary :value])))
        (auto-run/configure! rt (assoc config :evidence-adapters
                                       {:land 'auto-run.fixture/no-land!}))
        (is (= "absent" (get-in (auto-run/explain rt (:id card))
                                [:land :availability])))
        (auto-run/configure! rt (assoc config :evidence-adapters
                                       {:land 'auto-run.fixture/unreadable-land!}))
        (let [unknown (auto-run/explain rt (:id card))]
          (is (= "unknown" (get-in unknown [:land :availability])))
          (is (= "Land evidence store is offline" (get-in unknown [:land :reason]))))))))

(deftest unpublished-run-is-evidence-but-not-an-active-accepted-head
  (with-world
    (fn [rt _config]
      (let [card (card! rt {:auto-run/status "assigned"
                            :auto-run/request-id "auto-run/skeleton"})
            skeleton (weaver/add!
                      rt {:title "Unpublished assignment skeleton"
                          :attributes {:harness/run "true"
                                       :harness/status "ready"
                                       :harness/request-id "auto-run/skeleton"
                                       :harness/target (:id card)}})
            explanation (auto-run/explain rt (:id card))]
        (is (= [(:id skeleton)] (get-in explanation [:agents :unpublished])))
        (is (empty? (get-in explanation [:agents :accepted-heads])))
        (is (not= "active" (:disposition explanation)))
        (is (= "publication" (:phase explanation)))))))

(deftest accepted-continuation-head-ignores-unpublished-sibling
  (with-world
    (fn [rt _config]
      (let [card (card! rt {:auto-run/status "assigned"
                            :auto-run/request-id "auto-run/lineage"})
            original (weaver/add!
                      rt {:title "Original" :state "closed"
                          :attributes {:harness/run "true" :harness/published "true"
                                       :harness/logical-id "logical-worker"
                                       :harness/status "stopped" :harness/settled "true"
                                       :harness/request-id "auto-run/lineage"
                                       :harness/target (:id card)}})
            continuation (weaver/add!
                          rt {:title "Accepted continuation"
                              :attributes {:harness/run "true" :harness/published "true"
                                           :harness/logical-id "logical-worker"
                                           :harness/status "running" :harness/settled "false"
                                           :harness/after (:id original)}
                              :edges [{:type "continues" :to (:id original)}]})
            sibling (weaver/add!
                     rt {:title "Unpublished sibling"
                         :attributes {:harness/run "true" :harness/status "ready"
                                      :harness/after (:id original)}
                         :edges [{:type "continues" :to (:id original)}]})
            explanation (auto-run/explain rt (:id card))]
        (is (= [(:id continuation)] (get-in explanation [:agents :accepted-heads])))
        (is (= [(:id sibling)] (get-in explanation [:agents :unpublished])))
        (is (= "active" (:disposition explanation)))
        (is (nil? (get-in explanation [:next :permission])))))))

(deftest classifier-separates-current-failure-from-history-and-human-wait
  (let [base {:card {:state "active"}
              :admission {:blockers []}
              :land {:availability "unsupported"}
              :evidence {:merge-boundary {:value "unknown"}}}
        human (auto-run/classify
               (assoc base :agents {:lineages []}
                      :workflow {:frontier [{:id "accept" :title "Human acceptance"
                                             :role "checkpoint"
                                             :phase "human-checkpoint"}]}))
        failed (auto-run/classify
                (assoc base
                       :agents {:lineages [{:id "old" :error "old"
                                            :accepted-head false :status "failed"}
                                           {:id "head" :accepted-head true
                                            :status "failed"}]}
                       :workflow {:frontier [{:id "quality" :phase "validation"}]}))]
    (is (= ["human-checkpoint" "waiting" "human"]
           [(:phase human) (:disposition human) (get-in human [:next :role])]))
    (is (= ["validation" "failed" "operator"]
           [(:phase failed) (:disposition failed) (get-in failed [:next :role])]))))

(deftest blocker-patterns-publish-complete-state-and-labels-atomically
  (with-world
    (fn [rt _config]
      (let [card (card! rt {:kanban.label/custom "true"})
            evidence (weaver/add! rt {:title "Which scope?"})
            input {:strand (:id card) :evidence (:id evidence)}]
        (is (thrown? clojure.lang.ExceptionInfo
                     (patterns/weave! rt :auto-run-needs-decision input))
            "Autorun does not implicitly activate reporting patterns")
        (runtime/module! rt :signal-labels {:file "signal_labels.clj" :required? true})
        (patterns/weave! rt :auto-run-needs-decision input)
        (is (= ["true" "needs-decision" (:id evidence) "true" "true"]
               (mapv #(show rt card %)
                     [:auto-run/agent-blocked :auto-run/agent-blocked-status
                      :auto-run/agent-evidence :kanban.label/agent-blocked
                      :kanban.label/needs-decision])))
        (patterns/weave! rt :auto-run-unknown-failure input)
        (is (= "unknown-failure" (show rt card :auto-run/agent-blocked-status)))
        (is (nil? (show rt card :kanban.label/needs-decision)))
        (is (= "true" (show rt card :kanban.label/agent-blocked)))
        (weaver/update! rt (:id card) {:attributes {:unrelated "changed"}})
        (is (= "true" (show rt card :kanban.label/agent-blocked)))
        (let [before (weaver/show rt (:id card))]
          (doseq [bad-input [(dissoc input :evidence)
                             (assoc input :evidence "missing")
                             (assoc input :strand "missing")]]
            (is (thrown? clojure.lang.ExceptionInfo
                         (patterns/weave! rt :auto-run-needs-decision bad-input)))
            (is (= before (weaver/show rt (:id card)))))
          (is (thrown? clojure.lang.ExceptionInfo
                       (weaver/update! rt (:id card)
                                       {:attributes {:auto-run/agent-blocked nil}})))
          (is (= before (weaver/show rt (:id card)))))
        (patterns/weave! rt :auto-run-unblock {:strand (:id card)})
        (is (every? nil? (map #(show rt card %)
                              [:auto-run/agent-blocked :auto-run/agent-blocked-status
                               :auto-run/agent-evidence :kanban.label/agent-blocked
                               :kanban.label/needs-decision])))
        (is (= "true" (show rt card :kanban.label/custom)))
        (is (= evidence (weaver/show rt (:id evidence))))))))

(deftest blocker-evidence-is-a-strand-reference-independent-of-run-status
  (with-world
    (fn [rt _config]
      (runtime/module! rt :signal-labels {:file "signal_labels.clj" :required? true})
      (let [card (card! rt {:auto-run/error "Historical preparation failure"})
            evidence (weaver/add! rt {:title "Investigate tool timeout"
                                      :state "closed"
                                      :attributes {:note/text "The tool stopped responding."
                                                   :identity/by-identity "author"}})
            run (weaver/add! rt {:title "Worker" :state "closed"
                                 :attributes {:harness/run "true"
                                              :harness/published "true"
                                              :harness/target (:id card)
                                              :harness/status "stopped"
                                              :harness/settled "true"}})
            snapshot #(vector (weaver/list rt [:not [:missing :id]] {})
                              (scheduler/pending rt))
            explain #(let [before (snapshot)
                           result (auto-run/explain rt (:id card))]
                       (is (= before (snapshot)))
                       result)]
        (patterns/weave! rt :auto-run-unknown-failure
                         {:strand (:id card) :evidence (:id evidence)})
        (let [result (explain)]
          (is (= {:blocked true :status "unknown-failure"
                  :evidence (select-keys evidence [:id :title])}
                 (:agent-blocker result)))
          (is (= "waiting" (:disposition result)))
          (is (empty? (get-in result [:cause :evidence])))
          (is (true? (get-in result [:agents :lineages 0 :settled]))))
        (weaver/update! rt (:id run) {:attributes {:harness/status "failed"
                                                   :harness/error "Provider crashed"}})
        (patterns/weave! rt :auto-run-needs-decision
                         {:strand (:id card) :evidence (:id evidence)})
        (let [result (explain)]
          (is (= "failed" (:disposition result)))
          (is (= "needs-decision" (get-in result [:agent-blocker :status])))
          (is (= (:id run) (get-in result [:cause :evidence 0 :run])))
          (is (= "Historical preparation failure" (get-in result [:admission :receipt :error]))))
        (patterns/weave! rt :auto-run-unblock {:strand (:id card)})
        (let [result (explain)]
          (is (= {:blocked false} (:agent-blocker result)))
          (is (= "failed" (:disposition result)))
          (is (= evidence (weaver/show rt (:id evidence)))))))))

(deftest incomplete-blockers-and-missing-evidence-fail-visibly
  (with-world
    (fn [rt _config]
      (doseq [attrs [{:auto-run/agent-blocked "false"}
                     {:auto-run/agent-blocked "true"}
                     {:auto-run/agent-blocked "true" :auto-run/agent-blocked-status "other"
                      :auto-run/agent-evidence "missing"}
                     {:auto-run/agent-blocked-status "needs-decision"}
                     {:auto-run/agent-evidence "orphan"}]]
        (let [card (card! rt attrs)]
          (is (thrown-with-msg? clojure.lang.ExceptionInfo #"Invalid agent blocker"
                                (reporting/read-blocker rt card)))))
      (let [card (card! rt {:auto-run/agent-blocked "true"
                            :auto-run/agent-blocked-status "needs-decision"
                            :auto-run/agent-evidence "missing"})]
        (is (thrown-with-msg? clojure.lang.ExceptionInfo #"Agent evidence strand not found"
                              (reporting/read-blocker rt card)))))))

(deftest wktree-output-is-a-strict-boundary
  (is (= {:cwd "/tmp/feature" :branch "auto/abc" :script nil}
         (worktree/ready-result
          "{\"kind\":\"ready\",\"worktree_path\":\"/tmp/feature\",\"branch\":\"auto/abc\"}"
          "auto/abc")))
  (doseq [text ["{\"kind\":\"pool_full\"}"
                "{\"kind\":\"ready\",\"worktree_path\":\"/tmp/f\",\"branch\":\"main\"}"]]
    (is (thrown? clojure.lang.ExceptionInfo (worktree/ready-result text "auto/abc")))))
