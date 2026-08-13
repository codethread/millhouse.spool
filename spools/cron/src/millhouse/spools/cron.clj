(ns millhouse.spools.cron
  "Fixed-interval recurrence over Millstrand's durable scheduler wakes.

  Module authors normally declare jobs with `defjob`; trusted code holding a
  runtime may use `register!` and `unregister!` directly. Handler returns and
  errors appear in `jobs`; throws are also recorded by `recent-failures` and do
  not stop cadence. The scheduler remains the sole next-fire authority.
  Delivery is at-least-once, so handlers must tolerate duplicate fires.

  This reference also lists `desired-jobs`, `actual-jobs`, `apply-jobs!`, and
  `remove-jobs!`. They are public because the `scheduled-jobs` lifecycle
  declaration resolves them by symbol; module authors do not call them."
  (:require [clojure.spec.alpha :as s]
            [clojure.string :as str]
            [millstrand.api.format.alpha :as format-alpha]
            [millstrand.api.lifecycle.alpha :as lifecycle]
            [millstrand.api.registry.alpha :as registry]
            [millstrand.api.runtime.alpha :as runtime]
            [millstrand.api.scheduler.alpha :as scheduler]
            [millstrand.api.spool.alpha :refer [fail! poll-until! reject-unknown-keys!
                                           require-valid!]])
  (:import [java.time Instant]
           [java.util Random]
           [java.util.concurrent ExecutorService Executors
            ThreadFactory TimeUnit]))

(declare execute-job!)

(def ^:private state-version
  "Shape version for cron's runtime spool-state map. Bump whenever `new-state`'s
  key set changes: spool-state survives module refresh, so a post-upgrade refresh
  would otherwise reuse a preserved map missing the new key and offload against
  a nil executor (docs/spools/writing-shared-spools.md 'Versioned spool state',
  SPEC-004.C95). The `state-shape-matches-declared-version` test fails loudly if
  `new-state` and this version drift apart."
  4)

(def job-kind
  "Registry kind `:millhouse.spools.cron/jobs`, targeted by `defjob` and the
  `scheduled-jobs` lifecycle declaration."
  :millhouse.spools.cron/jobs)
(def ^:private repl-owner :millstrand.owner/repl)

(s/def ::id (s/or :keyword keyword?
                  :string (s/and string? (complement str/blank?))))
(s/def ::interval-ms pos-int?)
(s/def ::jitter-ms nat-int?)
(s/def ::handler qualified-symbol?)
(s/def ::job (s/keys :req-un [::id ::interval-ms ::handler] :opt-un [::jitter-ms]))
(s/def ::job-options
  (s/and map?
         #(every? #{:override?} (keys %))
         #(or (not (contains? % :override?)) (boolean? (:override? %)))))
(s/def ::runtime #(and (map? %) (contains? % :spool-state)))
(s/def ::jobs (s/map-of keyword? ::job))
(s/def ::lifecycle-context
  (s/and map?
         #(s/valid? ::runtime (:runtime %))))
(s/def ::apply-context
  (s/and ::lifecycle-context
         #(s/valid? ::jobs (:desired %))
         #(s/valid? ::jobs (:actual %))))
(s/def ::reconciled #{:cron})
(s/def ::job-ids (s/coll-of keyword? :kind vector?))
(s/def ::reconcile-result
  (s/and map?
         #(= #{:reconciled :jobs} (set (keys %)))
         #(s/valid? ::reconciled (:reconciled %))
         #(s/valid? ::job-ids (:jobs %))))

(defn- ^ThreadFactory daemon-thread-factory [prefix]
  (let [counter (atom 0)]
    (reify ThreadFactory
      (newThread [_ runnable]
        (doto (Thread. ^Runnable runnable (str prefix "-" (swap! counter inc)))
          (.setDaemon true))))))

(defn- new-state []
  (let [^ExecutorService executor (Executors/newSingleThreadExecutor
                                   (daemon-thread-factory "cron"))]
    {:executor executor
     ;; id -> {:id :interval-ms :jitter-ms :handler sym
     ;;        :last-result :last-fired-at :last-error}
     :jobs (atom {})
     :failure-log (atom [])
     :rng (Random.)
     ;; In-flight offloaded-job latch: a count incremented on the event lane in
     ;; `fire-wake` before submit and decremented in the executor task's finally.
     ;; `await-quiescent!` polls this atom to zero.
     :in-flight-count (atom 0)
     :close-fn (fn []
                 (.shutdownNow executor)
                 (when-not (.awaitTermination executor 1000 TimeUnit/MILLISECONDS)
                   (fail! "Cron executor did not stop" {})))}))

(defn- state [runtime]
  (runtime/spool-state runtime ::state {:version state-version} new-state))

(defn- ^ExecutorService executor [runtime] (:executor (state runtime)))
(defn- jobs-atom [runtime] (:jobs (state runtime)))
(defn- job-kinds [runtime]
  (runtime/spool-state runtime ::job-kinds registry/registry))
(defn- failure-log [runtime] (:failure-log (state runtime)))
(defn- ^Random rng [runtime] (:rng (state runtime)))

(defn- record-failure! [runtime entry]
  (let [full (assoc entry :at (str (Instant/now)))]
    (swap! (failure-log runtime) #(->> (conj (vec %) full) (take-last 100) vec))
    full))

(defn recent-failures
  "Return up to 100 recorded failures for this runtime's weaver lifetime,
  oldest first.

  Each entry carries `:kind` (`:run` for a handler throw or `:offload` for an
  executor rejection), `:job`, `:message`, and `:at`. A `:run` failure also
  carries the handler exception's `:data` when present."
  [runtime]
  @(failure-log runtime))

(defn- jitter-offset-ms
  "Return a uniform jitter offset in the range [-bound-ms, bound-ms].

  `rng` is a `java.util.Random`; pass a seeded one for deterministic tests. A
  zero or negative bound yields 0."
  [bound-ms ^Random rng]
  (if (pos? bound-ms)
    (long (Math/round (* (- (* 2.0 (.nextDouble rng)) 1.0) (double bound-ms))))
    0))

(defn- reschedule-delay-ms [interval-ms jitter-ms ^Random rng]
  (max 0 (+ (long interval-ms) (jitter-offset-ms jitter-ms rng))))

(defn- job-id [id]
  (cond
    (keyword? id) id
    (and (string? id) (not (str/blank? id))) (keyword id)
    :else (fail! "Cron job :id must be a keyword or non-blank string" {:id id})))

(defn- wake-key
  "The stable scheduler wake key owning job `id`'s cadence."
  [id]
  (str "cron/" (name id)))

(defn- wake-pending?
  [runtime id]
  (let [key (wake-key id)]
    (some #(= key (:key %)) (scheduler/pending runtime))))

(defn- resolve-symbol [role sym]
  (when-not (and (symbol? sym) (namespace sym))
    (fail! (str "Cron job " role " must be a fully qualified symbol") {role sym}))
  (or (requiring-resolve sym)
      (fail! (str "Cron job " role " cannot be resolved") {role sym})))

(defn- arm-wake!
  "Persist (replacing any existing) the `cron/<id>` wake at `now + interval +
  jitter`, keyed and payloaded so `fire-wake` can rediscover the job."
  [runtime id interval-ms jitter-ms]
  (let [^Instant now (runtime/now runtime)
        delay-ms (reschedule-delay-ms interval-ms jitter-ms (rng runtime))
        wake-at (.plusMillis now (long delay-ms))]
    (scheduler/schedule! runtime {:key (wake-key id)
                                  :wake-at wake-at
                                  :handler 'millhouse.spools.cron/fire-wake
                                  :payload {:job (name id)}})))

(defn- config-tuple [job]
  [(:interval-ms job) (or (:jitter-ms job) 0) (:handler job)])

(defn unregister!
  "Remove job `id` from `runtime` and cancel its pending `cron/<id>` wake.

  `id` accepts the same keyword or non-blank string as `register!` and is
  normalized to a keyword. Returns `{:unregistered id}` when either managed
  configuration or a pending wake existed, otherwise `{:unregistered nil}`.
  A missing wake is tolerated; genuine scheduler cancellation failures surface."
  [runtime id]
  (let [id (job-id id)
        key (wake-key id)
        old @(jobs-atom runtime)
        pending? (wake-pending? runtime id)]
    (when pending?
      (scheduler/cancel! runtime key))
    (swap! (jobs-atom runtime) dissoc id)
    {:unregistered (when (or pending? (contains? old id)) id)}))

(defn- in-flight-count [runtime] (:in-flight-count (state runtime)))

(defn- inc-in-flight! [runtime]
  (swap! (in-flight-count runtime) inc))

(defn- dec-in-flight! [runtime]
  (swap! (in-flight-count runtime) dec))

(defn- execute-job!
  "Run job `id`'s resolved `:handler` on the execution executor, recording the
  result cron-side and always releasing the in-flight latch. Never reschedules —
  cadence was already persisted on the event lane in `fire-wake`."
  [runtime id]
  (try
    (when-let [job (get @(jobs-atom runtime) id)]
      (let [fired-at (str (Instant/now))]
        (try
          (let [handler-fn (resolve-symbol :handler (:handler job))
                result (handler-fn runtime)]
            (swap! (jobs-atom runtime) update id
                   (fn [j] (when j (assoc j :last-result result
                                         :last-fired-at fired-at
                                         :last-error nil)))))
          (catch Throwable t
            (record-failure! runtime {:kind :run :job id
                                      :message (ex-message t) :data (ex-data t)})
            (swap! (jobs-atom runtime) update id
                   (fn [j] (when j (assoc j :last-error (ex-message t)
                                         :last-fired-at fired-at))))))))
    (finally
      (dec-in-flight! runtime))))

(defn fire-wake
  "Scheduler callback for a `cron/<id>` wake; consumers do not call it directly.

  The scheduler supplies `{:runtime runtime :payload {:job id}}` on the shared
  event lane. Cron ignores stale wakes for unregistered jobs. For a live job it
  persists the next wake before submitting the resolved handler to Cron's
  execution executor, then returns so the lane never runs the job body. An
  executor rejection is recorded as an `:offload` failure rather than thrown
  onto the event lane."
  [{:keys [runtime payload]}]
  (when-let [job (get @(jobs-atom runtime) (job-id (:job payload)))]
    (let [id (:id job)]
      (arm-wake! runtime id (:interval-ms job) (:jitter-ms job))
      (inc-in-flight! runtime)
      (try
        (.submit (executor runtime) ^Runnable (fn [] (execute-job! runtime id)))
        (catch Throwable t
          ;; Any submit-time failure — the expected executor shutdown/reload race
          ;; (RejectedExecutionException) or an unexpected throw from a corrupt
          ;; executor — means `execute-job!` never runs to release the latch in its
          ;; finally. The next wake is already armed, so balance the increment we
          ;; took, record the dropped run loudly, and return rather than throwing
          ;; into the lane (`PLAN-cron-on-scheduler-001.R3`).
          (dec-in-flight! runtime)
          (record-failure! runtime {:kind :offload :job id :message (ex-message t)})))))
  nil)

(defn await-quiescent!
  "Block until every offloaded cron job on `runtime` has finished, then return
  `runtime`.

  Because job bodies run off the event lane,
  `millstrand.test.alpha/await-quiescent!` returns before a Cron job necessarily
  completes. Deterministic tests join both surfaces in order:

  ```clojure
  (require '[millhouse.spools.cron :as cron]
           '[millstrand.test.alpha :as test-alpha])

  (test-alpha/advance! runtime (java.time.Duration/ofMinutes 10))
  (test-alpha/await-quiescent! runtime)
  (cron/await-quiescent! runtime)
  ```

  The in-flight latch is incremented on the event lane in `fire-wake` before
  submission, so once the lane has quiesced every submitted body is counted.
  Polling and timeout use the runtime Clock. `opts` accepts only positive-integer
  `:timeout-ms`, defaulting to 10000; timeout fails loudly with the remaining
  in-flight count."
  ([runtime] (await-quiescent! runtime {}))
  ([runtime {:keys [timeout-ms] :as opts}]
   (reject-unknown-keys! "await-quiescent!" #{:timeout-ms} opts)
   (let [counter (in-flight-count runtime)
         timeout-ms (or timeout-ms 10000)]
     (require-valid! ::timeout-ms timeout-ms
                     "await-quiescent! :timeout-ms must be a positive integer")
     (poll-until!
      (runtime/clock runtime)
      {:timeout-ms timeout-ms
       :poll-ms 5
       :check #(deref counter)
       :pred->result #(when (zero? %) runtime)
       :on-timeout #(fail! "Timed out awaiting cron quiescence"
                           {:timeout-ms timeout-ms :in-flight %})}))))

;; Public seam shape (clojure.spec)
;;
;; `::job` is the declared, discoverable source of truth for `register!`'s job
;; map — the contract downstream config authors write against — matching the
;; sibling reference spools such as delegation. `register!` gates each field
;; through `require-valid!` so the specs own the shape while the contextual
;; loud messages survive (failing value + allowed shape, TEN-003), and closes
;; the key set with `reject-unknown-keys!` since `s/keys` stays open. `:id`
;; accepts a keyword or non-blank string (coerced by `job-id`); `:handler` only
;; asserts a fully-qualified symbol here — `resolve-symbol` layers the
;; requiring-resolve check spec cannot express.
;; `await-quiescent!`'s single opt: the poll budget in milliseconds.
(s/def ::timeout-ms pos-int?)

(def ^:private job-keys
  "The closed key set of a `register!` job map (see `::job`)."
  #{:id :interval-ms :jitter-ms :handler})

(defn register!
  "Register or replace one job directly on `runtime`.

  ```clojure
  (require '[millhouse.spools.cron :as cron])

  (cron/register! runtime
    {:id :nightly-report
     :interval-ms 86400000
     :jitter-ms 3600000
     :handler 'my.jobs/emit-report})
  ```

  `job` is the same closed map documented by `defjob`, plus required `:id` as a
  keyword or non-blank string. Values and unknown keys are validated before the
  fully qualified handler symbol is resolved. Returns the normalized job status
  map.

  Re-registration preserves a pending `cron/<id>` wake when
  `[interval-ms jitter-ms handler]` is unchanged, including a fresh JVM adopting
  a durable wake. A changed tuple or missing wake arms a fresh wake at
  `now + interval + jitter`. Cron writes `fire-wake` as the scheduler callback;
  the job's `:handler` remains a function of the runtime."
  [runtime job]
  (when-not (map? job)
    (fail! "Cron register! job must be a map" {:job job}))
  (reject-unknown-keys! "Cron register!" job-keys job)
  (require-valid! ::id (:id job)
                  "Cron job :id must be a keyword or non-blank string")
  (require-valid! ::interval-ms (:interval-ms job)
                  "Cron job :interval-ms must be a positive integer")
  (require-valid! ::jitter-ms (or (:jitter-ms job) 0)
                  "Cron job :jitter-ms must be a non-negative integer")
  (require-valid! ::handler (:handler job)
                  "Cron job :handler must be a fully-qualified symbol")
  (let [id (job-id (:id job))
        interval (:interval-ms job)
        jitter (or (:jitter-ms job) 0)]
    (resolve-symbol :handler (:handler job))
    (let [old-entry (get @(jobs-atom runtime) id)
          pending? (wake-pending? runtime id)
          entry {:id id :interval-ms interval :jitter-ms jitter :handler (:handler job)}
          replace? (or (not pending?)
                       (and old-entry (not= (config-tuple old-entry) (config-tuple entry))))]
      (when replace?
        (arm-wake! runtime id interval jitter))
      (swap! (jobs-atom runtime) assoc id entry)
      (get @(jobs-atom runtime) id))))

(defn job-declaration
  "Build the validated registry value used by `defjob`; consumers normally call
  the macro instead.

  Attaches stable `id` to the closed `job` map, validates the optional
  `{:override? boolean}` declaration options, and returns the job value.
  `:override? true` marks same-id shadowing as intentional under registry layer
  rules; it remains collection metadata rather than part of the job value."
  [id options job]
  (require-valid! ::job-options options "Invalid Cron job options")
  (reject-unknown-keys! "Cron job declaration" job-keys job)
  (require-valid! ::job (assoc job :id id) "Invalid Cron job declaration"))

(defmacro defjob
  "Collect one Cron job declaration for the current runtime module.

  ```clojure
  (require '[millhouse.spools.cron :as cron])

  (cron/defjob :nightly-report
    {:interval-ms 86400000
     :jitter-ms 3600000
     :handler 'my.jobs/emit-report})
  ```

  `id` is the stable registry key. The closed `job` map takes positive
  `:interval-ms`, optional non-negative `:jitter-ms` (default `0`, sampled
  uniformly in `[-jitter, +jitter]`), and a fully qualified `:handler` symbol
  resolving at fire time to `(fn [runtime] ...)`.
  The optional options map accepts only boolean `:override?`; true marks
  same-id shadowing as intentional under registry layer rules. It remains
  collection metadata rather than part of the job value.

  Source evaluation schedules nothing. The form contributes under the current
  module owner; after publication Cron preserves unchanged wakes, replaces
  changed jobs, and removes declarations omitted by their owner."
  ([id job]
   `(defjob ~id {} ~job))
  ([id options job]
   `(runtime/collect-entry! job-kind ~id (job-declaration ~id ~options ~job)
                            (select-keys ~options #{:override?}))))

(runtime/collect-kind! ::job-kinds
                       {:id job-kind
                        :entry-spec ::job
                        :binding-moment :cron/fire})

(defn desired-jobs
  "Lifecycle read hook: return the effective owner-published declarations as a
  normalized `id -> job` map.

  `scheduled-jobs` calls this with `{:runtime runtime}`; module authors do not."
  [{:keys [runtime] :as context}]
  (require-valid! ::lifecycle-context context "Invalid Cron lifecycle context")
  (into {}
        (map (fn [[id job]]
               (let [normalized-id (job-id id)]
                 [normalized-id (assoc job :id normalized-id)])))
        (registry/effective (job-kinds runtime) job-kind)))

(defn actual-jobs
  "Lifecycle read hook: return Cron's managed `id -> job-status` map.

  `scheduled-jobs` calls this with `{:runtime runtime}`; consumers wanting the
  sorted status projection use `jobs`."
  [{:keys [runtime] :as context}]
  (require-valid! ::lifecycle-context context "Invalid Cron lifecycle context")
  @(jobs-atom runtime))

(defn- apply-job-change!
  [runtime operation id declaration change!]
  (try
    (change!)
    (catch Throwable t
      (throw (ex-info "Cron job reconciliation failed"
                      {:job id
                       :operation operation
                       :declaration declaration
                       :wake-key (wake-key id)
                       :remedy
                       (format-alpha/reflow
                        "|Repair the named Cron declaration or durable wake,
                         |then refresh the owning module.")}
                      t)))))

(defn apply-jobs!
  "Lifecycle apply hook: converge managed jobs and wakes onto `:desired`.

  Accepts `{:runtime runtime :desired id->job :actual id->job-status}` from
  `scheduled-jobs`. It removes omitted jobs, applies changed jobs, restores
  missing wakes, and returns `{:reconciled :cron :jobs [sorted-ids...]}`. A
  failed change names its job, operation, declaration, wake key, and remedy."
  [{:keys [runtime desired actual] :as context}]
  (require-valid! ::apply-context context "Invalid Cron apply context")
  (let [removed (remove (set (keys desired)) (keys actual))]
    (doseq [id removed]
      (apply-job-change! runtime :remove id (get actual id)
                         #(unregister! runtime id)))
    (doseq [[id job] desired]
      (when (or (not= (config-tuple job) (some-> (get actual id) config-tuple))
                (not (wake-pending? runtime id)))
        (apply-job-change! runtime :apply id job
                           #(register! runtime (assoc job :id id)))))
    (require-valid! ::reconcile-result
                    {:reconciled :cron :jobs (vec (sort (keys desired)))}
                    "Invalid Cron reconciliation result")))

(defn remove-jobs!
  "Lifecycle removal hook: cancel every managed job and wake.

  `scheduled-jobs` calls this with `{:runtime runtime}` when its declaration is
  removed. Returns `{:reconciled :cron :jobs []}`."
  [{:keys [runtime] :as context}]
  (require-valid! ::lifecycle-context context "Invalid Cron lifecycle context")
  (doseq [id (keys @(jobs-atom runtime))]
    (unregister! runtime id))
  (require-valid! ::reconcile-result
                  {:reconciled :cron :jobs []}
                  "Invalid Cron removal result"))

(lifecycle/defreconcile! scheduled-jobs
  "Lifecycle declaration that keeps durable Cron wakes converged on the
  effective `job-kind` registry.

  Any owner publication for that kind triggers desired/actual reconciliation;
  removing this declaration invokes `remove-jobs!`."
  {:read-desired 'millhouse.spools.cron/desired-jobs
   :read-actual 'millhouse.spools.cron/actual-jobs
   :apply 'millhouse.spools.cron/apply-jobs!
   :on-removed 'millhouse.spools.cron/remove-jobs!
   :trigger-kinds #{job-kind}})

(defn jobs
  "Return Cron's managed jobs on `runtime` as status maps, sorted by id.

  Each map carries `:id`, `:interval-ms`, `:jitter-ms`, the `:handler` symbol,
  and (once fired) `:last-result`/`:last-fired-at`/`:last-error`. When a job next
  fires lives in its durable `cron/<id>` wake — read scheduler introspection
  (`millstrand.api.scheduler.alpha/pending`), the single timing view."
  [runtime]
  (->> @(jobs-atom runtime) vals (sort-by (comp str :id)) vec))
