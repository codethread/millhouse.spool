(ns millhouse.land.merge-queue
  "Strict FIFO landing turns, driven by short workflow queue gates."
  (:require [clojure.java.io :as io]
            [clojure.spec.alpha :as s]
            [clojure.string :as str]
            [millhouse.workflow :as workflow]
            [millhouse.workflow.execution :as execution]
            [millhouse.land :as land]
            [millhouse.land.internal.queue-authority :as authority]
            [millhouse.land.internal.queue-cli :as queue-cli]
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
(s/def ::repair-kind #{"preparation"})
(s/def ::timeout-secs (s/and int? (complement neg?)))
(defn- require-unfrozen-root! [runtime run-id]
  (let [{:keys [root freeze]} (execution/run-view runtime run-id)]
    (when (or (nil? root) freeze)
      (fail! "Queue activity requires an active unfrozen root" {:run-id run-id}))
    root))

(defn- fenced-batch! [runtime before payload]
  (let [payload (reduce (fn [p [id _]]
                          (if (some #{id} (vals (:refs p))) p
                              (-> p (assoc-in [:refs (keyword id)] id)
                                  (update :strands conj {:ref (keyword id) :attributes {}})))) payload before)]
    (binding [authority/*before-images* before
              authority/*writes* (into {} (keep (fn [patch]
                                                  (when-let [id (get (:refs payload) (:ref patch))] [id patch])))
                                       (:strands payload))
              authority/*creates* (into {} (keep (fn [patch]
                                                   (when-not (get (:refs payload) (:ref patch)) [(:ref patch) patch])))
                                        (:strands payload))]
      (batch/apply! runtime payload))))

(defn- with-gate-authorization [runtime _run-id gate-ids f]
  (binding [authority/*gate-ids* (set (for [gate-id gate-ids] [runtime gate-id]))] (f)))

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

(defn- next-sequence []
  (let [numbers (map #(attr-get % :queue/sequence) (rows "merge-queue-entry" false))]
    (when-not (every? nat-int? numbers)
      (fail! "Merge queue history contains an invalid sequence" {}))
    (inc (reduce max -1 numbers))))

(defn join!
  "Reserve an unfrozen run's FIFO position; repeats retain its reservation."
  [runtime run-id]
  (with-guard runtime
    (fn []
      (let [root (require-unfrozen-root! runtime run-id)]
        (or (entry-for run-id)
            (let [gate (gate-for run-id "merge-turn")]
              (when-not gate
                (fail! "Join the merge queue at the merge-turn gate" {:run-id run-id}))
              (fenced-batch! runtime {(:id root) root}
                             {:refs {} :strands [{:ref :entry :title (str "Merge queue: " run-id)
                                                  :attributes {:kind "merge-queue-entry" :land/run-id run-id
                                                               :queue/root (:id root) :queue/gate (:id gate)
                                                               :queue/sequence (next-sequence)
                                                               :queue/queued-at (str (runtime/now runtime))}}]})
              (entry-for run-id)))))))

(defn- completion-attributes
  [kind run-id gate entry lock]
  {"land/queue-completion" kind
   "land/run-id" run-id
   "queue/gate" (:id gate)
   "queue/entry" (:id entry)
   "queue/lock" (:id lock)})

(defn- complete-queue! [runtime run-id root gate entry lock kind]
  (let [fresh (require-unfrozen-root! runtime run-id)]
    (when-not (= (:id root) (:id fresh))
      (fail! "Queue root changed before completion" {:run-id run-id}))
    (with-gate-authorization runtime run-id [(:id gate)]
      #(binding [authority/*before-images* {(:id fresh) fresh}]
         (workflow/complete! run-id {:step (:id gate) :executor (if (= "grant" kind) "merge-turn" "merge-release")
                                     :context {}
                                     :attributes (completion-attributes kind run-id gate entry lock)})))))

(defn grant!
  "Grant the FIFO head, fencing the public root image in each transaction."
  [runtime run-id]
  (with-guard runtime
    (fn []
      (let [root (require-unfrozen-root! runtime run-id)
            entry (entry-for run-id)
            gate (gate-for run-id "merge-turn")
            current-lock (lock-row)]
        (when (and entry gate (= (:id entry) (:id (first (entries))))
                   (not (attr-get entry :queue/withdraw-reason))
                   (or (nil? current-lock) (= run-id (attr-get current-lock :land/run-id))))
          (when-not current-lock
            (fenced-batch! runtime {(:id root) root (:id entry) entry}
                           {:refs {} :strands [{:ref :lock :title (str "Merge lock: " run-id)
                                                :attributes {:kind "merge-lock" :land/run-id run-id
                                                             :queue/entry (:id entry)}}]}))
          (complete-queue! runtime run-id root gate entry (lock-row) "grant"))))))

(defn release!
  "Release only this exact reservation/lock; freeze prevents further mutation."
  [runtime run-id]
  (with-guard runtime
    (fn []
      (let [root (require-unfrozen-root! runtime run-id)]
        (when-let [gate (gate-for run-id "merge-release")]
          (let [[entry lock]
                (if-let [entry (entry-for run-id)]
                  (let [lock (lock-row)]
                    (when-not (and (= run-id (attr-get lock :land/run-id))
                                   (= (:id entry) (attr-get lock :queue/entry))
                                   (nil? (attr-get entry :queue/withdraw-reason)))
                      (fail! "Releasing a merge turn requires its own lock" {:run-id run-id}))
                    (fenced-batch! runtime {(:id root) root (:id entry) entry (:id lock) lock}
                                   {:refs {:entry (:id entry) :lock (:id lock)}
                                    :strands [{:ref :entry :state "closed"
                                               :attributes {:queue/outcome "merged"
                                                            :queue/released-at (str (runtime/now runtime))}}
                                              {:ref :lock :state "closed"}]})
                    [entry lock])
                  (let [entry (or (completed-entry-for run-id)
                                  (fail! "No completed reservation for release retry" {:run-id run-id}))
                        lock (or (unique-lock-for-entry run-id (:id entry))
                                 (fail! "Completed reservation has no recorded lock" {:run-id run-id}))]
                    [entry lock]))]
            (complete-queue! runtime run-id root gate entry lock "release")))))))

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

(defn- require-unattempted-irreversible! [root receipt]
  (let [attempted (set (keep #(when (:may-have-started? %) (:gate-id %)) (:attempts receipt)))]
    (doseq [gate (run-strands root)
            :when (true? (attr-get gate :land/irreversible))]
      (when (or (= "closed" (:state gate)) (contains? attempted (:id gate)))
        (fail! "Merge may already have been submitted; reconcile and resume this turn"
               {:root (:id root) :gate (:id gate)})))))

(defn- retire-run! [runtime run-id reason]
  (let [freeze (execution/quiesce-run! runtime run-id reason)]
    (poll-until! (runtime/clock runtime)
                 {:timeout-ms 10000 :poll-ms 100
                  :check #(execution/retire! runtime freeze)
                  :pred->result #(when (= :settled (:status %)) %)
                  :on-timeout #(fail! "Run retirement is unknown; retain the frozen reservation"
                                      {:retirement %})})))

(defn withdraw!
  "Retire before taking the queue lock, then atomically abandon into abort.

  Irreversible may-have-started evidence refuses even after local settlement.
  The final conditional batch fences root, retirement, attempts and domain rows."
  [runtime id reason by-identity]
  (when-not (every? #(s/valid? ::non-blank %) [reason by-identity])
    (fail! "Withdrawal requires a non-blank reason and actor" {:entry id}))
  (current/with-runtime runtime
    (let [entry (require-entry id)
          run-id (attr-get entry :land/run-id)]
      (if (= "closed" (:state entry))
        (do (when-not (= "withdrawn" (attr-get entry :queue/outcome))
              (fail! "A completed merge cannot be withdrawn" {:entry id}))
            (status runtime id))
        (let [receipt (retire-run! runtime run-id reason)]
          (with-guard
            runtime
            (fn []
              (let [current-entry (require-entry id)
                    root (workflow/current-root run-id)
                    lock (lock-row)
                    own-lock (when (= run-id (some-> lock (attr-get :land/run-id))) lock)
                    params (assoc (attr-get root :workflow/context) :reason reason)]
                (when-not (and (= entry current-entry) (= "active" (:state current-entry))
                               (= (:id root) (attr-get entry :queue/root))
                               (= (:id root) (get-in receipt [:freeze :root-id])))
                  (fail! "Reservation or root changed during retirement" {:entry id}))
                (when (and own-lock (not= id (attr-get own-lock :queue/entry)))
                  (fail! "Lock does not belong to the exact reservation" {:entry id}))
                (require-unattempted-irreversible! root receipt)
                (when-not (s/valid? ::land/land-abort-params params)
                  (fail! "Landing context cannot continue into abort" {:run-id run-id}))
                (let [patches (cond-> [{:before entry
                                        :update {:state "closed"
                                                 :attributes {:queue/outcome "withdrawn"
                                                              :queue/withdraw-reason reason
                                                              :queue/released-at (str (runtime/now runtime))}}}]
                                own-lock (conj {:before own-lock :update {:state "closed"}}))]
                  (binding [authority/*writes* (into {} (map (fn [{:keys [before update]}] [(:id before) update])) patches)
                            authority/*before-images* (into {(:id root) root}
                                                            (map (juxt :id identity))
                                                            (filter #(true? (attr-get % :land/irreversible)) (run-strands root)))]
                    (with-gate-authorization
                      runtime run-id (map :id (run-strands root))
                      #(execution/abandon-run!
                        runtime {:run-id run-id :root-id (:id root) :reason reason
                                 :by-identity by-identity :retirement receipt
                                 :workflow #'land/land-abort :params params :domain-patches patches}))))
                (status runtime id)))))))))

(defn repair!
  "Resume and explicitly retry failed reversible preparation, retaining its turn.

  Historical skipped-gate rewind is unsupported; resolve it under old loaded
  code before cutover. This operation neither rewinds graphs nor infers merges."
  [runtime run-id {:keys [kind by-identity reason evidence] :as request}]
  (when-not (and (= #{:kind :by-identity :reason :evidence} (set (keys request)))
                 (= :preparation kind) (every? #(s/valid? ::non-blank %) [run-id by-identity reason])
                 (= #{:root-id :gate-id :expected-attempt :request-id} (set (keys evidence)))
                 (every? #(s/valid? ::non-blank %) (vals evidence)))
    (fail! "Repair requires preparation with exact root, gate, attempt and request IDs; legacy rewind must precede cutover"
           {:request request}))
  (current/with-runtime runtime
    (let [receipt (retire-run! runtime run-id reason)
          {:keys [root-id gate-id expected-attempt request-id]} evidence]
      ;; No execution mutations occur under the queue lock. Domain ownership is
      ;; checked here; exact receipt/root/token checks repeat at resume/retry.
      (with-guard runtime
        (fn []
          (let [root (workflow/current-root run-id)
                entry (entry-for run-id)
                gate (weaver/show runtime gate-id)]
            (when-not (and entry (= root-id (:id root)) (= root-id (attr-get entry :queue/root))
                           (some #(= gate-id (:id %)) (run-strands root))
                           (= "shell" (attr-get gate :workflow/gate))
                           (= expected-attempt (:attempt-id (execution/inspect runtime {:run-id run-id :step gate-id})))
                           (not (attr-get gate :land/irreversible)) (= "active" (:state gate)))
              (fail! "Repair requires this reservation's active reversible Shell gate" {:evidence evidence}))
            (require-unattempted-irreversible! root receipt))))
      (execution/resume-run! runtime run-id receipt)
      (execution/retry! runtime {:run-id run-id :step gate-id :expected-attempt expected-attempt
                                 :request-id request-id :reason reason :by-identity by-identity}))))

(millstrand/defop merge-queue
  "Own strict FIFO reservations; ordinary landing progression uses workflow verbs."
  {:arg-spec queue-cli/arguments
   :returns {:subcommands (into {} (map (fn [name] [name {:type :map :extra :json}]))
                                (keys (:subcommands queue-cli/arguments)))}
   :prime (format-alpha/prose
           "
             Sign-off joins the queue automatically. Use workflow ready and await
             to drive the run. A failed head keeps its place and lock while repaired.
             Inspect merge-queue status for progress and merge-queue await ENTRY
             for a repeatable wait. Timeout never moves a reservation.

             Any trusted agent may withdraw another run with merge-queue withdraw
             ENTRY --reason REASON --by-identity ACTOR. Withdrawal stops merge work before releasing
             the turn. There is no automatic eviction or second merge approval.

             Use merge-queue repair --kind preparation for settled reversible work.
             Supply actor, reason, and exact root, gate, attempt and request IDs.
             Historical skipped-gate repair must happen before execution cutover.
           " {})}
  [ctx]
  (let [runtime (:op/runtime ctx)
        {:keys [subcommand run-id entry-id timeout-secs kind by-identity reason evidence]}
        (:op/args ctx)]
    (case (first subcommand)
      "join" {:entry (join! runtime run-id)}
      "status" (if entry-id (status runtime entry-id) (status runtime))
      "await" (await-turn runtime entry-id (or timeout-secs 300))
      "withdraw" (withdraw! runtime entry-id reason by-identity)
      "repair" (repair! runtime run-id
                        {:kind (keyword kind)
                         :by-identity by-identity
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
            :when (and (nil? (:freeze (execution/run-view runtime run-id)))
                       (contains? #{"merge-turn" "merge-release"} (:gate gate))
                       (nil? (gate-error gate)))]
      (try
        (case (:gate gate)
          "merge-turn" (do (join! runtime run-id) (grant! runtime run-id))
          "merge-release" (release! runtime run-id))
        (catch Exception e
          (when-let [current-root (:root (execution/run-view runtime run-id))]
            (when (and (= (:id root) (:id current-root))
                       (nil? (:freeze (execution/run-view runtime run-id))))
              (try
                (fenced-batch! runtime {(:id current-root) current-root}
                               {:refs {:gate (:id gate)}
                                :strands [{:ref :gate :attributes {:gate/error (str (ex-message e) (some->> (ex-data e) (str " ")))}}]})
                (catch clojure.lang.ExceptionInfo _ nil)))))))
    {:scanned true}))

(defn on-event
  "Reconsider queue gates after graph mutations."
  [_event]
  (scan! (current/runtime)))

(defn open-completion-guard!
  "Install the queue-gate completion guard before any queue scan can run."
  [{:keys [runtime]}]
  (hooks/register-hook!
   runtime :land/queue-gate-completion #{:batch/apply-before-commit :strand/update-before-commit :strand/add-before-commit}
   'millhouse.land.internal.queue-authority/before-commit
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
                            'millhouse.land.merge-queue/on-event {})
  (scan! runtime)
  {:registered :land/merge-queue})

(defn close-handler!
  "Remove the module's queue scanner; durable reservations remain."
  [{:keys [runtime]}]
  (events/unregister-handler! runtime :land/merge-queue)
  {:unregistered :land/merge-queue})

(lifecycle/defresource queue-completion-guard
  "Protect Land queue gates before persisted work is scanned."
  {:open 'millhouse.land.merge-queue/open-completion-guard!
   :close 'millhouse.land.merge-queue/close-completion-guard!})

(lifecycle/defresource queue-handler
  "Drive durable FIFO queue gates on graph changes."
  {:open 'millhouse.land.merge-queue/open-handler!
   :close 'millhouse.land.merge-queue/close-handler!
   :after #{:queue-completion-guard}})
