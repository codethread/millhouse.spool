(ns millhouse.spools.land.merge-queue
  "Strict FIFO landing turns, driven by short workflow queue gates."
  (:require [clojure.java.io :as io]
            [clojure.spec.alpha :as s]
            [clojure.string :as str]
            [millhouse.spools.workflow :as workflow]
            [millhouse.spools.executors.shell :as shell]
            [millhouse.spools.land :as land]
            [millhouse.spools.workflow.internal.guard :as workflow-guard]
            [millstrand.api.batch.alpha :as batch]
            [millstrand.api.current.alpha :as current]
            [millstrand.api.events.alpha :as events]
            [millstrand.api.format.alpha :as format-alpha]
            [millstrand.api.graph.alpha :as graph]
            [millstrand.api.hooks.alpha :as hooks]
            [millstrand.api.lifecycle.alpha :as lifecycle]
            [millstrand.api.millstrand.alpha :as millstrand]
            [millstrand.api.runtime.alpha :as runtime]
            [millstrand.api.spool.alpha :refer [attr-get fail! poll-until!]]
            [millstrand.api.weaver.alpha :as weaver]))

(s/def ::non-blank (s/and string? (complement str/blank?)))
(s/def ::timeout-secs (s/and int? (complement neg?)))
(s/def ::sha (s/and ::non-blank #(boolean (re-matches #"(?i)[0-9a-f]{40}" %))))
(s/def ::root-id ::non-blank)
(s/def ::gate-id ::non-blank)
(s/def ::entry-id ::non-blank)
(s/def ::lock-id ::non-blank)
(s/def ::pr-number pos-int?)
(s/def ::pr-state #{"MERGED"})
(s/def ::base-branch #{"main"})
(s/def ::pr-head ::sha)
(s/def ::merge-commit ::sha)
(s/def ::canonical-main ::sha)
(s/def ::irreversible-work #{"not-started"})
(s/def ::turn-evidence
  (s/keys :req-un [::root-id ::gate-id ::irreversible-work]))
(s/def ::release-evidence
  (s/keys :req-un [::root-id ::gate-id ::entry-id ::lock-id ::pr-number
                   ::pr-state ::base-branch ::pr-head ::merge-commit
                   ::canonical-main]))

(def ^:private protected-queue-gates #{"merge-turn" "merge-release"})

(def ^:private authorization-state-key
  ::queue-gate-authorization)

(defn- authorization-slot
  [runtime]
  (runtime/spool-state runtime authorization-state-key #(ThreadLocal.)))

(defn- authorized-gate-close?
  [runtime gate-id]
  (contains? (:gate-ids (.get ^ThreadLocal (authorization-slot runtime))) gate-id))

(defn queue-gate-completion-guard
  "Reject closure of Land queue gates unless the current Land operation authorized it.

  Gate kind is read only from each update's pre-image. Outcome attributes and
  actor attribution therefore cannot hide or authorize a protected close."
  [ctx]
  (let [runtime (current/runtime)]
    (doseq [{:keys [id before after]} (:batch/updated ctx)
            :let [gate (attr-get before :workflow/gate)]
            :when (and (= "active" (:state before))
                       (= "closed" (:state after))
                       (contains? protected-queue-gates gate))]
      (when-not (authorized-gate-close? runtime id)
        (throw (ex-info "Land queue gates are completed only by their queue executor"
                        {:code "land/queue-gate-completion-forbidden"
                         :gate-id id
                         :gate gate}))))))

(defn- with-gate-authorization
  [runtime run-id gate-ids f]
  (let [slot ^ThreadLocal (authorization-slot runtime)
        previous (.get slot)]
    (.set slot {:run-id run-id :gate-ids (set gate-ids)})
    (try
      (f)
      (finally
        (if (some? previous)
          (.set slot previous)
          (.remove slot))))))

(defn- with-guard [rt f]
  (let [config-dir (get-in rt [:metadata :config-dir])
        {:keys [monitor]} (runtime/spool-state rt ::state {:version 1}
                                               #(hash-map :monitor (Object.)))]
    (when-not (s/valid? ::non-blank config-dir)
      (fail! "Merge queue requires a selected workspace" {:config-dir config-dir}))
    (current/with-runtime rt
      (locking monitor
        (with-open [file (java.io.RandomAccessFile.
                          (io/file config-dir ".land-merge-lock.acquire") "rw")
                    channel (.getChannel file)
                    _lock (.lock channel)]
          (f))))))

(defn- rows [kind active?]
  (weaver/list (current/runtime)
               (cond-> [:and [:= [:attr "kind"] kind]]
                 active? (conj [:= :state "active"])) {}))

(defn- entries []
  (let [active (rows "merge-queue-entry" true)
        sequences (mapv #(attr-get % :queue/sequence) active)]
    (when-not (and (every? nat-int? sequences)
                   (= (count sequences) (count (distinct sequences))))
      (fail! "Merge queue ordering is invalid"
             {:entries (mapv :id active) :sequences sequences}))
    (vec (sort-by #(attr-get % :queue/sequence) active))))

(defn- lock-row []
  (let [locks (rows "merge-lock" true)]
    (when (> (count locks) 1)
      (fail! "Multiple merge locks require explicit repair" {:locks (mapv :id locks)}))
    (when-let [lock (first locks)]
      (when-not (s/valid? ::non-blank (attr-get lock :land/run-id))
        (fail! "Merge lock has no run owner" {:lock (:id lock)}))
      lock)))

(defn- reservations-for [run-id]
  (filterv #(= run-id (attr-get % :land/run-id))
           (rows "merge-queue-entry" false)))

(defn- unique-reservation
  [run-id reservations]
  (when (> (count reservations) 1)
    (fail! "Run has multiple queue reservations"
           {:run-id run-id :entries (mapv :id reservations)}))
  (first reservations))

(defn- entry-for [run-id]
  (unique-reservation run-id
                      (filterv #(= "active" (:state %))
                               (reservations-for run-id))))

(defn- completed-entry-for [run-id]
  (unique-reservation run-id
                      (filterv #(and (= "closed" (:state %))
                                     (= "merged" (attr-get % :queue/outcome)))
                               (reservations-for run-id))))

(defn- locks-for-entry [run-id entry-id]
  (filterv #(and (= run-id (attr-get % :land/run-id))
                 (= entry-id (attr-get % :queue/entry)))
           (rows "merge-lock" false)))

(defn- unique-lock-for-entry [run-id entry-id]
  (let [matches (locks-for-entry run-id entry-id)]
    (when (> (count matches) 1)
      (fail! "Queue reservation has multiple recorded locks"
             {:run-id run-id :entry entry-id :locks (mapv :id matches)}))
    (first matches)))

(defn- require-entry [id]
  (let [entry (weaver/show (current/runtime) id)]
    (when-not (= "merge-queue-entry" (attr-get entry :kind))
      (fail! "Expected a merge queue entry" {:entry id}))
    entry))

(defn- gate-for [run-id waiter]
  (first (filter #(= waiter (:gate %)) (workflow/ready run-id))))

(defn- patch! [patches]
  (batch/apply! (current/runtime)
                {:refs (into {} (map (fn [[id _]] [(keyword id) id])) patches)
                 :strands (mapv (fn [[id patch]] (assoc patch :ref (keyword id))) patches)
                 :edges []}))

(defn- next-sequence []
  (let [numbers (map #(attr-get % :queue/sequence) (rows "merge-queue-entry" false))]
    (when-not (every? nat-int? numbers)
      (fail! "Merge queue history contains an invalid sequence" {}))
    (inc (reduce max -1 numbers))))

(defn join!
  "Reserve a run's FIFO position at its merge-turn gate; repeat calls retain it."
  [runtime run-id]
  (with-guard
    runtime
    (fn []
      (or (entry-for run-id)
          (let [root (workflow/current-root run-id)
                gate (gate-for run-id "merge-turn")]
            (when-not (and root gate)
              (fail! "Join the merge queue at the merge-turn gate" {:run-id run-id}))
            (weaver/add!
             (current/runtime)
             {:title (str "Merge queue: " run-id)
              :attributes {:kind "merge-queue-entry"
                           :land/run-id run-id
                           :queue/root (:id root)
                           :queue/gate (:id gate)
                           :queue/sequence (next-sequence)
                           :queue/queued-at (str (runtime/now (current/runtime)))}}))))))

(defn- completion-attributes
  [kind run-id gate entry lock]
  {"land/queue-completion" kind
   "land/run-id" run-id
   "queue/gate" (:id gate)
   "queue/entry" (:id entry)
   "queue/lock" (:id lock)})

(defn grant!
  "Grant the head run's turn and close its queue gate without blocking a worker.

  Failure after lock creation retains the lock and reservation for retry in
  place. A non-head run simply remains waiting."
  [runtime run-id]
  (with-guard
    runtime
    (fn []
      (workflow-guard/with-run!
        runtime run-id
        (fn []
          (let [entry (entry-for run-id)
                gate (gate-for run-id "merge-turn")
                current-lock (lock-row)]
            (when (and entry gate
                       (= (:id entry) (:id (first (entries))))
                       (not (attr-get entry :queue/withdraw-reason))
                       (or (nil? current-lock)
                           (= run-id (attr-get current-lock :land/run-id))))
              (let [lock (or current-lock
                             (weaver/add! (current/runtime)
                                          {:title (str "Merge lock: " run-id)
                                           :attributes {:kind "merge-lock"
                                                        :land/run-id run-id
                                                        :queue/entry (:id entry)}}))]
                (with-gate-authorization
                  runtime run-id [(:id gate)]
                  #(workflow/complete!
                    run-id
                    {:step (:id gate)
                     :by "merge-turn"
                     :attributes
                     (completion-attributes "grant" run-id gate entry lock)}))))))))))

(defn release!
  "Close a completed turn's reservation and lock before closing its release gate.

  The queue writes share one batch. If workflow completion fails afterwards,
  retry recognizes the closed reservation and never releases another run's lock."
  [runtime run-id]
  (with-guard
    runtime
    (fn []
      (workflow-guard/with-run!
        runtime run-id
        (fn []
          (when-let [gate (gate-for run-id "merge-release")]
            (let [[entry lock]
                  (if-let [active-entry (entry-for run-id)]
                    (let [active-lock (lock-row)]
                      (when-not (and (= run-id (some-> active-lock
                                                       (attr-get :land/run-id)))
                                     (= (:id active-entry)
                                        (some-> active-lock (attr-get :queue/entry))))
                        (fail! "Releasing a merge turn requires its own lock"
                               {:run-id run-id :entry (:id active-entry)}))
                      (when (attr-get active-entry :queue/withdraw-reason)
                        (fail! "Withdrawal is pending for this turn"
                               {:entry (:id active-entry)}))
                      (patch! {(:id active-entry)
                               {:state "closed"
                                :attributes {:queue/outcome "merged"
                                             :queue/released-at
                                             (str (runtime/now (current/runtime)))}}
                               (:id active-lock) {:state "closed"}})
                      [active-entry active-lock])
                    (let [completed (or (completed-entry-for run-id)
                                        (fail! "No completed merge reservation for release retry"
                                               {:run-id run-id}))
                          recorded-lock
                          (or (unique-lock-for-entry run-id (:id completed))
                              (fail! "Completed reservation has no recorded lock"
                                     {:run-id run-id :entry (:id completed)}))]
                      [completed recorded-lock]))]
              (with-gate-authorization
                runtime run-id [(:id gate)]
                #(workflow/complete!
                  run-id
                  {:step (:id gate)
                   :by "merge-release"
                   :attributes
                   (completion-attributes "release" run-id gate entry lock)})))))))))

(defn- entry-view [entry lock]
  (let [run-id (attr-get entry :land/run-id)
        root (workflow/current-root run-id)]
    {:id (:id entry)
     :run-id run-id
     :state (:state entry)
     :outcome (attr-get entry :queue/outcome)
     :sequence (attr-get entry :queue/sequence)
     :queued-at (attr-get entry :queue/queued-at)
     :withdraw-reason (attr-get entry :queue/withdraw-reason)
     :holds-lock (= run-id (some-> lock (attr-get :land/run-id)))
     :run-state (if root "active" "missing")
     :updated-at (:updated_at root)
     :frontier (when root
                 (mapv (fn [step]
                         (assoc (select-keys step [:id :title :role :gate :checkpoint])
                                :error (attr-get (weaver/show (current/runtime) (:id step))
                                                 :gate/error)))
                       (workflow/ready run-id)))}))

(defn status
  "Report active FIFO order or one reservation, with current workflow evidence."
  ([runtime]
   (current/with-runtime runtime
     (let [lock (lock-row)]
       {:lock (when lock {:id (:id lock) :run-id (attr-get lock :land/run-id)})
        :entries (mapv (fn [index entry] (assoc (entry-view entry lock) :position index))
                       (range) (entries))})))
  ([runtime id]
   (current/with-runtime runtime
     (let [entry (require-entry id)
           queue (:entries (status runtime))
           position (first (keep-indexed #(when (= id (:id %2)) %1) queue))]
       (assoc (entry-view entry (lock-row))
              :position position
              :ahead (if position (subvec queue 0 position) []))))))

(defn await-turn
  "Wait for a reservation to hold the turn or close; timeout preserves its place."
  [runtime id timeout-secs]
  (when-not (s/valid? ::timeout-secs timeout-secs)
    (fail! "Queue await requires non-negative timeout seconds" {:timeout-secs timeout-secs}))
  (poll-until! (runtime/clock runtime)
               {:timeout-ms (* 1000 timeout-secs)
                :poll-ms 1000
                :check #(status runtime id)
                :pred->result #(when (or (:holds-lock %) (= "closed" (:state %))) %)
                :on-timeout #(assoc % :timeout true)}))

(defn- run-strands [root]
  (:strands (graph/subgraph (current/runtime) [(:id root)] {:type "parent-of"})))

(defn- abort-payload [root run-id reason]
  (let [params (assoc (attr-get root :workflow/context) :reason reason)]
    (when-not (s/valid? ::land/land-abort-params params)
      (fail! "Landing context cannot continue into abort" {:run-id run-id :context params}))
    (workflow/compile land/land-abort params
                      {:run-id run-id :family "land" :context params
                       :definition 'millhouse.spools.land/land-abort})))

(defn- close-and-abort! [runtime run-id entry lock root payload reason]
  (let [closeable (filter #(and (= "active" (:state %))
                                (contains? #{"root" "step" "checkpoint" "defer" "procedure"}
                                           (attr-get % :workflow/role)))
                          (run-strands root))
        protected (keep #(when (contains? protected-queue-gates
                                          (attr-get % :workflow/gate))
                           (:id %))
                        closeable)
        patches (into {(:id entry) {:state "closed"
                                    :attributes {:queue/outcome "withdrawn"
                                                 :queue/withdraw-reason reason
                                                 :queue/released-at
                                                 (str (runtime/now (current/runtime)))}}}
                      (map (fn [strand] [(:id strand) {:state "closed"}])) closeable)
        patches (cond-> patches
                  lock (assoc (:id lock) {:state "closed"}))]
    (with-gate-authorization
      runtime run-id protected
      #(batch/apply! (current/runtime)
                     {:refs (into {} (map (fn [[id _]] [(keyword id) id])) patches)
                      :strands (into
                                (mapv (fn [[id patch]]
                                        (assoc patch :ref (keyword id)))
                                      patches)
                                (:strands payload))
                      :edges (:edges payload)}))))

(defn withdraw!
  "Stop a named landing and atomically replace it with abort bookkeeping.

  Any trusted agent may withdraw; no owner restriction or timeout eviction.
  Shell quiescence precedes release. A started irreversible gate requires
  reconciliation instead: cancelling a local client cannot undo a remote merge.
  A failed withdrawal keeps the reservation and lock, with shell gates frozen
  for inspection. Repair and retry those gates to resume the original landing."
  [runtime id reason]
  (when-not (s/valid? ::non-blank reason)
    (fail! "Withdrawal requires a non-blank reason" {:entry id}))
  (with-guard
    runtime
    (fn []
      (let [entry (require-entry id)
            run-id (attr-get entry :land/run-id)]
        (if (= "closed" (:state entry))
          (when-not (= "withdrawn" (attr-get entry :queue/outcome))
            (fail! "A completed merge cannot be withdrawn" {:entry id}))
          (let [root (workflow/current-root run-id)]
            (when-not (and root (= (:id root) (attr-get entry :queue/root)))
              (fail! "Queue reservation no longer identifies the current root" {:entry id}))
            (let [stopped (shell/quiesce-run! run-id reason)
                  attempted (into #{} (keep #(when (:attempted? %) (:gate-id %)))
                                  (:gates stopped))]
              (workflow-guard/with-run!
                runtime run-id
                (fn []
                  (let [entry (require-entry id)
                        root (workflow/current-root run-id)
                        lock (lock-row)
                        own-lock (when (= run-id (some-> lock (attr-get :land/run-id)))
                                   lock)]
                    (when-not (and root (= (:id root) (attr-get entry :queue/root)))
                      (fail! "Queue reservation no longer identifies the current root"
                             {:entry id}))
                    (when-let [gate
                               (first
                                (filter #(and (true? (attr-get % :land/irreversible))
                                              (or (= "closed" (:state %))
                                                  (some? (attr-get % :shell/output))
                                                  (some? (attr-get % :shell/exit-code))
                                                  (contains? attempted (:id %))))
                                        (run-strands root)))]
                      (fail! "Merge may already have been submitted; reconcile and resume this turn"
                             {:entry id :gate (:id gate) :run-id run-id}))
                    (close-and-abort! runtime run-id entry own-lock root
                                      (abort-payload root run-id reason) reason)))))))
        (status runtime id)))))

(defn- exact-keys?
  [expected value]
  (= expected (set (keys value))))

(defn- require-evidence!
  [kind evidence]
  (let [[spec keys] (case kind
                      :skipped-turn
                      [::turn-evidence #{:root-id :gate-id :irreversible-work}]

                      :skipped-release
                      [::release-evidence
                       #{:root-id :gate-id :entry-id :lock-id :pr-number
                         :pr-state :base-branch :pr-head :merge-commit
                         :canonical-main}]

                      (fail! "Unknown merge queue repair kind"
                             {:kind kind
                              :allowed [:skipped-turn :skipped-release]}))]
    (when-not (and (s/valid? spec evidence) (exact-keys? keys evidence))
      (fail! "Merge queue repair evidence is invalid"
             {:kind kind :evidence evidence :spec spec
              :expected-keys keys :explain (s/explain-str spec evidence)}))
    (when (and (= :skipped-release kind)
               (not= (:merge-commit evidence) (:canonical-main evidence)))
      (fail! "Canonical main evidence does not identify the exact merge commit"
             {:merge-commit (:merge-commit evidence)
              :canonical-main (:canonical-main evidence)}))
    evidence))

(defn- require-repair-request!
  [{:keys [kind by reason evidence] :as request}]
  (when-not (exact-keys? #{:kind :by :reason :evidence} request)
    (fail! "Merge queue repair request has unknown or missing keys"
           {:keys (set (keys request))
            :expected #{:kind :by :reason :evidence}}))
  (when-not (s/valid? ::non-blank by)
    (fail! "Merge queue repair requires a non-blank actor" {:by by}))
  (when-not (s/valid? ::non-blank reason)
    (fail! "Merge queue repair requires a non-blank reason" {:reason reason}))
  (assoc request :evidence (require-evidence! kind evidence)))

(defn- require-recorded-root
  [runtime run-id root-id]
  (let [root (try
               (weaver/show runtime root-id)
               (catch Exception _
                 (fail! "Recorded landing root is missing"
                        {:run-id run-id :root-id root-id})))]
    (when-not (and (contains? #{"active" "closed"} (:state root))
                   (= "root" (attr-get root :workflow/role))
                   (= "land" (attr-get root :workflow/family))
                   (= "merge" (attr-get root :land/stage))
                   (= run-id (attr-get root :workflow/run-id)))
      (fail! "Repair evidence does not identify the recorded landing root"
             {:run-id run-id :root-id root-id
              :role (attr-get root :workflow/role)
              :family (attr-get root :workflow/family)
              :stage (attr-get root :land/stage)
              :recorded-run-id (attr-get root :workflow/run-id)}))
    root))

(defn- exact-gate
  [strands waiter gate-id]
  (let [matches (filterv #(= waiter (attr-get % :workflow/gate)) strands)]
    (when-not (= 1 (count matches))
      (fail! "Landing root has ambiguous queue gate ownership"
             {:gate waiter :expected gate-id :matches (mapv :id matches)}))
    (let [gate (first matches)]
      (when-not (= gate-id (:id gate))
        (fail! "Repair evidence names the wrong queue gate"
               {:gate waiter :expected (:id gate) :actual gate-id}))
      gate)))

(defn- repair-attributes
  [kind by reason evidence]
  {"land/repair-kind" (name kind)
   "land/repair-by" by
   "land/repair-reason" reason
   "land/repair-evidence" evidence
   "land/repaired-at" (str (runtime/now (current/runtime)))})

(defn- prior-repair
  [strand]
  (when-let [kind (attr-get strand :land/repair-kind)]
    {:kind (keyword kind)
     :by (attr-get strand :land/repair-by)
     :reason (attr-get strand :land/repair-reason)
     :evidence (attr-get strand :land/repair-evidence)}))

(defn- idempotent-repair?
  [strand request]
  (when-let [recorded (prior-repair strand)]
    (when-not (= request recorded)
      (fail! "Merge queue repair does not match the recorded repair"
             {:strand (:id strand) :recorded recorded :requested request}))
    true))

(defn- repair-result
  [kind run-id root-id gate-id entry-id]
  {:repair (name kind)
   :run-id run-id
   :root-id root-id
   :gate-id gate-id
   :entry-id entry-id})

(defn- shell-strands
  [strands]
  (filterv #(= "shell" (attr-get % :workflow/gate)) strands))

(defn- irreversible-gate
  [strands]
  (let [matches (filterv #(true? (attr-get % :land/irreversible)) strands)]
    (when-not (= 1 (count matches))
      (fail! "Landing root has ambiguous irreversible work"
             {:gates (mapv :id matches)}))
    (first matches)))

(defn- irreversible-attempt?
  [gate attempted]
  (or (= "closed" (:state gate))
      (some? (attr-get gate :shell/output))
      (some? (attr-get gate :shell/exit-code))
      (contains? attempted (:id gate))))

(defn- dependency-target
  [runtime strands from-id label]
  (let [ids (->> (:edges (graph/subgraph runtime [from-id] {:type "depends-on"}))
                 (filter #(and (= "depends-on" (:edge_type %))
                               (= from-id (:from_strand_id %))))
                 (map :to_strand_id)
                 distinct
                 vec)]
    (when-not (= 1 (count ids))
      (fail! "Landing repair dependency is ambiguous"
             {:step from-id :dependency label :matches ids}))
    (or (get strands (first ids))
        (fail! "Landing repair dependency is outside the recorded root"
               {:step from-id :dependency label :target (first ids)}))))

(defn- require-ownership-barrier!
  [runtime strands turn irreversible]
  (let [strands-by-id (into {} (map (juxt :id identity)) strands)
        barrier (dependency-target runtime strands-by-id (:id irreversible)
                                   "pre-irreversible")
        turn-dependency (dependency-target runtime strands-by-id (:id barrier)
                                           "merge-turn")]
    (when-not (and (= "shell" (attr-get barrier :workflow/gate))
                   (not (true? (attr-get barrier :land/irreversible)))
                   (contains? #{"active" "closed"} (:state barrier))
                   (= (:id turn) (:id turn-dependency)))
      (fail! "Landing repair cannot restore an ownership-blocked frontier"
             {:turn (:id turn)
              :irreversible (:id irreversible)
              :barrier (:id barrier)
              :barrier-state (:state barrier)
              :barrier-gate (attr-get barrier :workflow/gate)
              :barrier-irreversible (attr-get barrier :land/irreversible)
              :barrier-dependency (:id turn-dependency)}))
    barrier))

(defn- rewound-shell-attributes
  [prior-error]
  {:gate/error prior-error
   :workflow/outcome-by nil
   :shell/running nil
   :shell/attempt-id nil
   :shell/custody-handle nil
   :shell/timeout-deadline nil
   :shell/timeout-intent nil
   :shell/exit-code nil
   :shell/output nil})

(defn- require-skipped-turn-state!
  [runtime run-id {:keys [root-id gate-id]}]
  (let [root (require-recorded-root runtime run-id root-id)
        strands (run-strands root)
        gate (exact-gate strands "merge-turn" gate-id)
        entry (unique-reservation run-id (reservations-for run-id))]
    (when-not (= "active" (:state root))
      (fail! "Skipped-turn repair requires the recorded root to remain active"
             {:run-id run-id :root-id root-id :state (:state root)}))
    (when-not (= root-id (:id (workflow/current-root run-id)))
      (fail! "Skipped-turn repair root is not the run's current root"
             {:run-id run-id :root-id root-id}))
    (when-not (= "closed" (:state gate))
      (fail! "Skipped-turn repair requires a closed merge-turn gate"
             {:run-id run-id :gate-id gate-id :state (:state gate)}))
    (when (attr-get gate :land/queue-completion)
      (fail! "Merge turn has recorded authorized completion"
             {:run-id run-id :gate-id gate-id
              :completion (attr-get gate :land/queue-completion)}))
    (when (and entry (not= "active" (:state entry)))
      (fail! "Skipped turn has a terminal queue reservation"
             {:run-id run-id :entry (:id entry) :state (:state entry)}))
    (when (and entry
               (or (not= root-id (attr-get entry :queue/root))
                   (not= gate-id (attr-get entry :queue/gate))))
      (fail! "Queue reservation does not identify the skipped turn"
             {:run-id run-id :entry (:id entry)
              :root (attr-get entry :queue/root)
              :gate (attr-get entry :queue/gate)}))
    {:root root :strands strands :gate gate :entry entry}))

(defn- repair-skipped-turn!
  [runtime run-id {:keys [kind by reason evidence] :as request}]
  (let [{:keys [root-id gate-id]} evidence
        root (require-recorded-root runtime run-id root-id)
        initial-strands (run-strands root)
        gate (exact-gate initial-strands "merge-turn" gate-id)]
    (if (idempotent-repair? gate request)
      (let [entry (unique-reservation run-id (reservations-for run-id))]
        (repair-result kind run-id root-id gate-id (:id entry)))
      (let [{:keys [strands]} (require-skipped-turn-state! runtime run-id evidence)
            prior-errors (into {}
                               (map (juxt :id #(attr-get % :gate/error)))
                               (shell-strands strands))
            stopped (shell/quiesce-run! run-id (str "Skipped-turn repair: " reason))
            attempted (into #{}
                            (keep #(when (:attempted? %) (:gate-id %)))
                            (:gates stopped))]
        (workflow-guard/with-run!
          runtime run-id
          (fn []
            (let [{:keys [strands gate entry]}
                  (require-skipped-turn-state! runtime run-id evidence)
                  shells (shell-strands strands)
                  irreversible (irreversible-gate strands)
                  ownership-barrier
                  (require-ownership-barrier! runtime strands gate irreversible)]
              (when (irreversible-attempt? irreversible attempted)
                (fail! "Irreversible merge work may have started; retain the fenced turn"
                       {:run-id run-id :gate-id (:id irreversible)
                        :attempted (contains? attempted (:id irreversible))}))
              (let [gate-ref (keyword gate-id)
                    shell-patches
                    (mapv (fn [shell]
                            (if (and (= (:id ownership-barrier) (:id shell))
                                     (= "closed" (:state shell)))
                              {:ref (keyword (:id shell))
                               :state "active"
                               :attributes
                               (rewound-shell-attributes
                                (get prior-errors (:id shell)))}
                              {:ref (keyword (:id shell))
                               :attributes
                               {:gate/error (get prior-errors (:id shell))}}))
                          shells)
                    attributes (repair-attributes kind by reason evidence)
                    refs (into {gate-ref gate-id}
                               (map (fn [shell]
                                      [(keyword (:id shell)) (:id shell)]))
                               shells)
                    repair-strands (into [{:ref gate-ref :state "active"
                                           :attributes attributes}]
                                         shell-patches)
                    payload (if entry
                              {:refs refs :strands repair-strands}
                              {:refs refs
                               :strands
                               (conj repair-strands
                                     {:ref :repair-entry
                                      :title (str "Merge queue: " run-id)
                                      :attributes
                                      {:kind "merge-queue-entry"
                                       :land/run-id run-id
                                       :queue/root root-id
                                       :queue/gate gate-id
                                       :queue/sequence (next-sequence)
                                       :queue/queued-at
                                       (str (runtime/now (current/runtime)))}})})]
                (batch/apply! runtime payload)
                (let [repaired-entry (or entry (entry-for run-id))]
                  (repair-result kind run-id root-id gate-id
                                 (:id repaired-entry)))))))))))

(defn- require-successful-shell!
  [gate label]
  (let [exit-code (attr-get gate :shell/exit-code)]
    (when-not (and (= "closed" (:state gate))
                   (some? exit-code)
                   (zero? exit-code)
                   (nil? (attr-get gate :gate/error)))
      (fail! "Landing repair lacks a successful recorded shell gate"
             {:gate (:id gate) :label label :state (:state gate)
              :exit-code exit-code
              :error (attr-get gate :gate/error)}))
    gate))

(defn- prepared-head
  [prepare branch]
  (let [output (attr-get prepare :shell/output)
        pattern (re-pattern
                 (str "(?m)^land prepare: validated "
                      (java.util.regex.Pattern/quote branch)
                      " at ([0-9a-fA-F]{40})$"))]
    (or (some->> output (re-find pattern) second)
        (fail! "Prepare gate does not record the exact validated branch HEAD"
               {:gate (:id prepare) :branch branch :output output}))))

(defn- require-release-evidence!
  [runtime root subgraph release evidence]
  (let [strands (into {} (map (juxt :id identity)) (:strands subgraph))
        pull (dependency-target runtime strands (:id release) "pull-main")
        merge-gate (dependency-target runtime strands (:id pull) "merge-pr")
        prepare (dependency-target runtime strands (:id merge-gate) "prepare-merge")
        context (attr-get root :workflow/context)
        branch (:branch context)
        pr-number (:pr-number context)
        argv (attr-get merge-gate :shell/argv)]
    (require-successful-shell! prepare "prepare-merge")
    (require-successful-shell! merge-gate "merge-pr")
    (require-successful-shell! pull "pull-main")
    (when-not (true? (attr-get merge-gate :land/irreversible))
      (fail! "Recorded merge gate is not the irreversible landing gate"
             {:gate (:id merge-gate)}))
    (when-not (and (= pr-number (:pr-number evidence))
                   (= (str pr-number) (nth argv 4 nil))
                   (= branch (last argv)))
      (fail! "Repair PR evidence does not match the recorded landing"
             {:recorded-pr pr-number :evidence-pr (:pr-number evidence)
              :recorded-branch branch :merge-argv argv}))
    (when-not (= (:pr-head evidence) (prepared-head prepare branch))
      (fail! "Repair PR head does not match the validated landing HEAD"
             {:evidence-head (:pr-head evidence)
              :validated-head (prepared-head prepare branch)}))))

(defn- repair-skipped-release!
  [runtime run-id {:keys [kind by reason evidence] :as request}]
  (let [{:keys [root-id gate-id entry-id lock-id]} evidence
        entry (require-entry entry-id)]
    (if (idempotent-repair? entry request)
      (repair-result kind run-id root-id gate-id entry-id)
      (let [root (require-recorded-root runtime run-id root-id)
            subgraph (graph/subgraph runtime [root-id] {:type "parent-of"})
            release (exact-gate (:strands subgraph) "merge-release" gate-id)
            reservations (reservations-for run-id)
            reservation (unique-reservation run-id reservations)
            lock (lock-row)]
        (when-not (and (= entry-id (:id reservation))
                       (= "active" (:state entry))
                       (= run-id (attr-get entry :land/run-id))
                       (= root-id (attr-get entry :queue/root)))
          (fail! "Repair entry does not identify the active recorded reservation"
                 {:run-id run-id :entry entry-id :reservation (:id reservation)
                  :state (:state entry) :root (attr-get entry :queue/root)}))
        (when-not (and lock
                       (= lock-id (:id lock))
                       (= run-id (attr-get lock :land/run-id))
                       (= entry-id (attr-get lock :queue/entry)))
          (fail! "Repair lock does not identify the reservation's active owner"
                 {:run-id run-id :entry entry-id :lock lock-id
                  :active-lock (some-> lock :id)
                  :active-owner (some-> lock (attr-get :land/run-id))}))
        (when-let [active-root (workflow/current-root run-id)]
          (when-not (= root-id (:id active-root))
            (fail! "Another root is active for the repaired run"
                   {:run-id run-id :expected root-id :active (:id active-root)})))
        (when-not (= "closed" (:state release))
          (fail! "Skipped-release repair requires a closed merge-release gate"
                 {:run-id run-id :gate-id gate-id :state (:state release)}))
        (when (attr-get release :land/queue-completion)
          (fail! "Merge release has recorded authorized completion"
                 {:run-id run-id :gate-id gate-id
                  :completion (attr-get release :land/queue-completion)}))
        (require-release-evidence! runtime root subgraph release evidence)
        (patch! {entry-id {:state "closed"
                           :attributes
                           (merge {:queue/outcome "merged"
                                   :queue/released-at
                                   (str (runtime/now (current/runtime)))}
                                  (repair-attributes kind by reason evidence))}
                 lock-id {:state "closed"}})
        (repair-result kind run-id root-id gate-id entry-id)))))

(defn repair!
  "Repair one explicitly evidenced pre-guard skipped Land queue gate.

  Supported kinds are `:skipped-turn` before possible irreversible work and
  `:skipped-release` after exact successful merge/main evidence. Turn repair
  rewinds completed reversible preparation to restore an ownership-blocked
  frontier. Every request
  records actor, reason, graph ids, and evidence; mismatches fail without queue
  settlement. Repeating the exact request is idempotent."
  [runtime run-id request]
  (when-not (s/valid? ::non-blank run-id)
    (fail! "Merge queue repair requires a non-blank run id" {:run-id run-id}))
  (let [{:keys [kind] :as request} (require-repair-request! request)]
    (with-guard
      runtime
      #(case kind
         :skipped-turn (repair-skipped-turn! runtime run-id request)
         :skipped-release
         (workflow-guard/with-run!
           runtime run-id
           (fn []
             (repair-skipped-release! runtime run-id request)))))))

(def ^:private queue-args
  {:op "merge-queue"
   :doc "Inspect or explicitly withdraw strict FIFO landing reservations."
   :subcommands
   {"join" {:doc "Reserve a run at its merge-turn gate; repeats retain its place."
            :hook-class :mutating :deadline-class :standard
            :positionals [{:name :run-id :required? true :spec ::non-blank}]}
    "status" {:doc "Show queue order or one entry with workflow progress."
              :hook-class :read :deadline-class :standard
              :positionals [{:name :entry-id :spec ::non-blank}]}
    "await" {:doc "Wait for a reserved turn; timeout never dequeues it."
             :hook-class :read :deadline-class :unbounded
             :flags {:timeout-secs {:type :int :spec ::timeout-secs
                                    :doc "Seconds to wait; defaults to 300."}}
             :positionals [{:name :entry-id :required? true :spec ::non-blank}]}
    "withdraw" {:doc "Stop a named landing and release its turn with an explicit reason."
                :hook-class :mutating :deadline-class :unbounded
                :flags {:reason {:type :string :required? true :spec ::non-blank}}
                :positionals [{:name :entry-id :required? true :spec ::non-blank}]}
    "repair" {:doc "Repair one explicitly evidenced pre-guard skipped queue gate."
              :hook-class :mutating :deadline-class :unbounded
              :flags {:kind {:type :string :required? true
                             :doc "skipped-turn or skipped-release."}
                      :by {:type :string :required? true :spec ::non-blank
                           :doc "Trusted actor performing the repair."}
                      :reason {:type :string :required? true :spec ::non-blank}
                      :evidence {:type :string :parse :json :required? true
                                 :doc "Exact JSON evidence for the selected repair kind."}}
              :positionals [{:name :run-id :required? true :spec ::non-blank}]}}})

(millstrand/defop merge-queue
  "Own strict FIFO reservations; ordinary landing progression uses workflow verbs."
  {:arg-spec queue-args
   :returns {:subcommands (into {} (map (fn [name] [name {:type :map :extra :json}]))
                                (keys (:subcommands queue-args)))}
   :prime (format-alpha/prose
           "
             Sign-off joins the queue automatically. Use workflow ready and await
             to drive the run. A failed head keeps its place and lock while repaired.
             Inspect merge-queue status for progress and merge-queue await ENTRY
             for a repeatable wait. Timeout never moves a reservation.

             Any trusted agent may withdraw another run with merge-queue withdraw
             ENTRY --reason REASON. Withdrawal stops merge work before releasing
             the turn. There is no automatic eviction or second merge approval.

             Use merge-queue repair only for an evidenced gate skipped before the
             Land completion guard was active. Read the Land cookbook first. Repair
             requires kind, actor, reason, and the exact JSON evidence shape; any
             mismatch or uncertainty retains or fences the turn for reconciliation.
           " {})}
  [ctx]
  (let [runtime (:op/runtime ctx)
        {:keys [subcommand run-id entry-id timeout-secs kind by reason evidence]}
        (:op/args ctx)]
    (case (first subcommand)
      "join" {:entry (join! runtime run-id)}
      "status" (if entry-id (status runtime entry-id) (status runtime))
      "await" (await-turn runtime entry-id (or timeout-secs 300))
      "withdraw" (withdraw! runtime entry-id reason)
      "repair" (repair! runtime run-id
                        {:kind (keyword kind)
                         :by by
                         :reason reason
                         :evidence (workflow/json->params evidence)}))))

(defn- gate-error [view]
  (when-let [error (attr-get (weaver/show (current/runtime) (:id view)) :gate/error)]
    {:gate (:id view) :error error}))

(workflow/defexecutor merge-turn
  "Wait for automatic FIFO admission and acquisition; failed gates expose their error."
  {}
  [view]
  (gate-error view))

(workflow/defexecutor merge-release
  "Release the completed merge turn automatically before housekeeping."
  {}
  [view]
  (gate-error view))

(defn scan!
  "Advance ready queue gates using short serialized mutations, never a worker wait."
  [runtime]
  (current/with-runtime runtime
    (doseq [root (workflow/active-runs "land")
            :let [run-id (attr-get root :workflow/run-id)]
            gate (workflow/ready run-id)
            :when (and (contains? #{"merge-turn" "merge-release"} (:gate gate))
                       (nil? (gate-error gate)))]
      (try
        (case (:gate gate)
          "merge-turn" (do (join! runtime run-id) (grant! runtime run-id))
          "merge-release" (release! runtime run-id))
        (catch Exception e
          (weaver/update! (current/runtime) (:id gate)
                          {:attributes {:gate/error (str (ex-message e)
                                                         (some->> (ex-data e) (str " ")))}}))))
    {:scanned true}))

(defn on-event
  "Reconsider queue gates after graph mutations."
  [_event]
  (scan! (current/runtime)))

(defn open-completion-guard!
  "Install the queue-gate completion guard before any queue scan can run."
  [{:keys [runtime]}]
  (hooks/register-hook!
   runtime :land/queue-gate-completion #{:batch/apply-before-commit}
   'millhouse.spools.land.merge-queue/queue-gate-completion-guard
   {:order -100
    :doc "Require Land-scoped authority to close merge-turn and merge-release."})
  {:registered :land/queue-gate-completion})

(defn close-completion-guard!
  "Remove the queue-gate completion guard after the scanner is stopped."
  [{:keys [runtime]}]
  (hooks/unregister-hook! runtime :land/queue-gate-completion)
  {:unregistered :land/queue-gate-completion})

(defn open-handler!
  "Register queue scanning and recover pending queue gates on activation."
  [{:keys [runtime]}]
  (events/register-handler! runtime :land/merge-queue
                            #{:strand/added :strand/updated :batch/applied
                              :strand/burned :strand/superseded}
                            'millhouse.spools.land.merge-queue/on-event {})
  (scan! runtime)
  {:registered :land/merge-queue})

(defn close-handler!
  "Remove the module's queue scanner; durable reservations remain."
  [{:keys [runtime]}]
  (events/unregister-handler! runtime :land/merge-queue)
  {:unregistered :land/merge-queue})

(lifecycle/defresource queue-completion-guard
  "Protect Land queue gates before persisted work is scanned."
  {:open 'millhouse.spools.land.merge-queue/open-completion-guard!
   :close 'millhouse.spools.land.merge-queue/close-completion-guard!})

(lifecycle/defresource queue-handler
  "Drive durable FIFO queue gates on graph changes."
  {:open 'millhouse.spools.land.merge-queue/open-handler!
   :close 'millhouse.spools.land.merge-queue/close-handler!
   :after #{:queue-completion-guard}})
