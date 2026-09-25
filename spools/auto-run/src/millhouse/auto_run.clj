(ns millhouse.auto-run
  "Dispatch opted-in, ready Kanban features into repository-owned workflows.

  One assignment owns a card's delivery. This module only admits work; it never
  advances lanes, retries workers, interprets results, or approves a merge."
  (:require [clojure.java.io :as io]
            [clojure.set :as set]
            [clojure.spec.alpha :as s]
            [clojure.string :as str]
            [millhouse.harnesses :as harnesses]
            [millhouse.harnesses.assignment :as assignment]
            [millhouse.auto-run-explain :as explanation]
            [millhouse.auto-run-recovery :as recovery]
            [millhouse.kanban :as kanban]
            [millhouse.workflow :as workflow]
            [millstrand.api.current.alpha :as current]
            [millstrand.api.format.alpha :as format]
            [millstrand.api.millstrand.alpha :as millstrand]
            [millstrand.api.runtime.alpha :as runtime]
            [millstrand.api.scheduler.alpha :as scheduler]
            [millstrand.api.spool.alpha :refer [attr-get fail! require-valid!]]
            [millstrand.api.weaver.alpha :as weaver])
  (:import [java.time Instant]
           [java.util UUID]))

(def ^:private wake-key "millhouse/auto-run")

(s/def ::text (s/and string? (complement str/blank?)))
(s/def ::repo ::text)
(s/def ::seat ::text)
(s/def ::effort ::text)
(s/def ::workflow ::text)
(s/def ::enabled? boolean?)
(s/def ::max-running pos-int?)
(s/def ::interval-ms pos-int?)
(s/def ::prepare qualified-symbol?)
(s/def ::start-params qualified-symbol?)
(s/def ::workflows (s/coll-of ::text :kind set? :min-count 1))
(s/def ::workflow-params map?)
(s/def ::land qualified-symbol?)
(s/def ::evidence-adapters (s/keys :opt-un [::land]))
(s/def ::config
  (s/and (s/keys :req-un [::repo ::seat ::effort ::workflow ::workflows ::prepare
                          ::enabled? ::max-running ::interval-ms]
                 :opt-un [::start-params ::evidence-adapters])
         #(every? #{:repo :seat :effort :workflow :workflows :prepare :start-params
                    :evidence-adapters :enabled? :max-running :interval-ms} (keys %))
         #(contains? (:workflows %) (:workflow %))))
(s/def ::cwd ::text)
(s/def ::branch ::text)
(s/def ::prepared (s/keys :req-un [::cwd ::branch]))
(assignment/def-assign-policy auto-run-workflow
  "Follow the repository-selected workflow supplied with your assignment.

  If blocked, save the context on an evidence strand, then use
  `strand weave` with `auto-run-needs-decision` or `auto-run-unknown-failure`.
  Both take `strand` (the work strand ID) and `evidence` (the evidence strand ID).
  Use `strand pattern explain <name>` for the input contract. Publish the blocker
  as your final work-card mutation, return a brief handoff, and end your run.

  Use `auto-run-unblock` to clear a resolved blocker; it preserves the evidence.")

(declare scan! wake!)

(defn- state [rt]
  (runtime/spool-state rt ::dispatcher {:version 1}
                       #(hash-map :config (atom nil))))

(defn- pending-wake [rt]
  (some #(when (= wake-key (:key %)) %) (scheduler/pending rt)))

(defn- arm! [rt config]
  (scheduler/schedule!
   rt {:key wake-key
       :wake-at (.plusMillis ^Instant (runtime/now rt) (long (:interval-ms config)))
       :handler 'millhouse.auto-run/wake!
       :payload {:generation (:generation config)}}))

(defn configure!
  "Enable repository-owned dispatch from a lifecycle resource's open hook.

  Required config names the canonical repo, default seat/effort/workflow,
  allowed workflow names, qualified preparation callback, concurrency, cadence,
  and enabled flag. Preparation receives runtime and {:repo ... :card ...}, and
  returns {:cwd ... :branch ...}. It must not claim the card.

  Optional :start-params names a qualified callback. It receives runtime and
  {:repo ... :card ... :settings ... :prepared {:cwd ... :branch ...}}, then
  returns additional workflow start parameters. It must return a map and cannot
  replace :card (the card ID string), :feature, :worktree, :branch, :seat, or
  :effort.

  Invalid configuration fails activation. Disabling prevents new admission;
  it never stops existing workers. Reconfiguration is serialized with scans."
  [rt config]
  (require-valid! ::config config "Invalid auto-run repository configuration")
  (when-not (.isDirectory (io/file (:repo config)))
    (fail! "Auto-run repository directory does not exist" {:repo (:repo config)}))
  (when-not (ifn? @(runtime/resolve-var rt (:prepare config)))
    (fail! "Auto-run preparation callback is not callable" {:prepare (:prepare config)}))
  (when-let [start-params (:start-params config)]
    (when-not (ifn? @(runtime/resolve-var rt start-params))
      (fail! "Auto-run workflow parameter callback is not callable"
             {:start-params start-params})))
  (when-let [land-adapter (get-in config [:evidence-adapters :land])]
    (when-not (ifn? @(runtime/resolve-var rt land-adapter))
      (fail! "Auto-run Land evidence adapter is not callable"
             {:land-adapter land-adapter})))
  (current/with-runtime rt
    (doseq [name (:workflows config)]
      (when-not (contains? (:entrypoints (workflow/resolve-workflow (keyword name))) :start)
        (fail! "Auto-run workflow must support start" {:workflow name}))))
  (harnesses/resolve-harness rt (:seat config))
  (assignment/register-assign-policy! rt auto-run-workflow)
  (let [config-lock (:config (state rt))]
    (locking config-lock
      (let [config (assoc config :generation (str (UUID/randomUUID)))]
        (reset! config-lock config)
        (if (:enabled? config)
          (arm! rt config)
          (when (pending-wake rt) (scheduler/cancel! rt wake-key)))
        (dissoc config :generation)))))

(defn stop!
  "Disable admission and cancel its wake without touching any worker."
  [rt]
  (let [config-lock (:config (state rt))]
    (locking config-lock
      (reset! config-lock nil)
      (when (pending-wake rt) (scheduler/cancel! rt wake-key))
      {:enabled false})))

(defn eligible?
  "Return whether a graph-ready strand permits a first automatic assignment.

  Readiness itself belongs to Weaver. This predicate checks card state, opt-in,
  authoritative current ownership, and previous dispatch receipts. Reporter,
  actor, and other participation history do not make an unclaimed card owned."
  [rt card]
  (and (= "active" (:state card))
       (= "true" (attr-get card :kanban/card))
       (= "feature" (attr-get card :kanban/type))
       (= "pending" (attr-get card :kanban/lane))
       (= "true" (attr-get card :kanban.label/auto-run))
       (nil? (kanban/current-ownership rt (:id card)))
       (nil? (attr-get card :auto-run/status))
       (nil? (attr-get card :auto-run/request-id))))

(defn- runs [rt]
  (weaver/list rt [:= [:attr "harness/run"] "true"] {}))

(defn- occupied? [run]
  (or (contains? #{"ready" "running"} (attr-get run :harness/status))
      (not= "true" (attr-get run :harness/settled))))

(defn- request-run [all-runs card]
  (when-let [request-id (attr-get card :auto-run/request-id)]
    (some #(when (= request-id (attr-get % :harness/request-id)) %) all-runs)))

(defn- receipt! [rt card run]
  (weaver/update! rt (:id card)
                  {:attributes {:auto-run/status "assigned"
                                :auto-run/run-id (:id run)
                                :auto-run/error nil}}))

(defn- error! [rt card error]
  (weaver/update! rt (:id card)
                  {:attributes {:auto-run/status "error"
                                :auto-run/error (ex-message error)}}))

(defn- recover! [rt all-runs]
  (doseq [card (weaver/list rt [:= [:attr "auto-run/status"] "preparing"] {})]
    (if-let [run (request-run all-runs card)]
      (receipt! rt card run)
      (error! rt card
              (ex-info "Preparation interrupted before assignment; inspect the retained worktree and workflow. No automatic retry."
                       {:card (:id card)})))))

(defn- settings [rt config card]
  (let [request (into {} (for [key [:seat :effort :workflow]]
                           [key (or (attr-get card (keyword "auto-run" (name key)))
                                    (get config key))]))]
    (doseq [[key value] request]
      (require-valid! ::text value (str "Invalid auto-run " (name key))))
    (when-not (contains? (:workflows config) (:workflow request))
      (fail! "Auto-run workflow is not allowed by this repository" request))
    (when-not (contains? (get-in (harnesses/resolve-harness rt (:seat request))
                                 [:definition :modes]) :headless)
      (fail! "Auto-run seat must support headless execution" request))
    request))

(def ^:private reserved-workflow-params
  #{:card :feature :worktree :branch :seat :effort})

(defn- require-admitted! [rt card receipt]
  (when-not (and (= "active" (:state card))
                 (= "true" (attr-get card :kanban/card))
                 (= "feature" (attr-get card :kanban/type))
                 (= "pending" (attr-get card :kanban/lane))
                 (= "true" (attr-get card :kanban.label/auto-run))
                 (nil? (kanban/current-ownership rt (:id card)))
                 (assignment/target-ready? rt (:id card))
                 (every? (fn [[attribute value]]
                           (= value (attr-get card attribute)))
                         receipt))
    (fail! "Card changed before automatic assignment; automatic assignment cancelled"
           {:card (:id card)}))
  card)

(defn- additional-workflow-params [rt config card settings prepared]
  (if-let [callback (:start-params config)]
    (let [params (require-valid!
                  ::workflow-params
                  ((runtime/resolve-var rt callback)
                   rt {:repo (:repo config)
                       :card card
                       :settings settings
                       :prepared prepared})
                  "Invalid auto-run workflow parameter result")
          reserved (set/intersection reserved-workflow-params (set (keys params)))]
      (when (seq reserved)
        (fail! "Auto-run workflow parameters cannot override dispatcher fields"
               {:reserved reserved}))
      params)
    {}))

(defn- guidance [workflow-name workflow-run-id]
  (format/prose
   "
     Your repository-selected workflow is `{workflow-name}`, already started
     as `{workflow-run-id}`. Follow its instructions:

     ```text
     strand workflow ready {workflow-run-id}
     ```

   " {:workflow-name workflow-name :workflow-run-id workflow-run-id}))

(defn- dispatch! [rt config initial by-identity]
  (let [card (weaver/show rt (:id initial))]
    (when (eligible? rt card)
      (try
        (let [{:keys [seat effort workflow]} (settings rt config card)
              request-id (str "auto-run/" (:id card))
              workflow-run-id (str "auto-run-" (:id card))]
          (weaver/update!
           rt (:id card)
           {:attributes {:auto-run/status "preparing"
                         :auto-run/request-id request-id
                         :auto-run/workflow-run-id workflow-run-id
                         :auto-run/effective-seat seat
                         :auto-run/effective-effort effort
                         :auto-run/effective-workflow workflow}})
          (let [prepared ((runtime/resolve-var rt (:prepare config))
                          rt {:repo (:repo config) :card card})
                {:keys [cwd branch]} (require-valid! ::prepared prepared
                                                     "Invalid auto-run preparation result")]
            (when-not (.isDirectory (io/file cwd))
              (fail! "Prepared worktree does not exist" {:cwd cwd}))
            (weaver/update! rt (:id card)
                            {:attributes {:auto-run/worktree cwd
                                          :auto-run/branch branch}})
            ;; Admission was recorded before preparation. Detect intervening board
            ;; edits before pouring a workflow; this is not a cancellation API.
            (let [receipt {:auto-run/status "preparing"
                           :auto-run/request-id request-id
                           :auto-run/workflow-run-id workflow-run-id
                           :auto-run/effective-seat seat
                           :auto-run/effective-effort effort
                           :auto-run/effective-workflow workflow
                           :auto-run/worktree cwd
                           :auto-run/branch branch
                           :auto-run/run-id nil}
                  latest (require-admitted! rt (weaver/show rt (:id card)) receipt)
                  additional-params
                  (additional-workflow-params
                   rt config latest {:seat seat :effort effort :workflow workflow}
                   {:cwd cwd :branch branch})
                  ;; A repository callback is synchronous trusted code, but it is
                  ;; still an extra mutation boundary before workflow publication.
                  final (require-admitted! rt (weaver/show rt (:id card)) receipt)
                  workflow-params
                  (merge {:card (:id final) :feature (:title final)
                          :worktree cwd :branch branch
                          :seat seat :effort effort}
                         additional-params)]
              (current/with-runtime rt
                (workflow/start! workflow-run-id (keyword workflow) workflow-params)))
            (let [run (assignment/assign!
                       rt (cond-> {:harness seat :target (:id card) :cwd cwd
                                   :policy "auto-run-workflow"
                                   :request-id request-id
                                   :attributes {:harness/effort effort}
                                   :append-system-prompt (guidance workflow workflow-run-id)}
                            by-identity (assoc :by-identity by-identity)))]
              (receipt! rt card run)
              {:card (:id card) :run (:id run) :workflow-run-id workflow-run-id})))
        (catch Exception error
          ;; Publication can succeed before the caller receives its response.
          ;; Adopt that request's run rather than attempting another assignment.
          (if-let [run (request-run (runs rt) (weaver/show rt (:id card)))]
            (do (receipt! rt card run) {:card (:id card) :run (:id run)})
            (do (error! rt card error)
                {:card (:id card) :error (ex-message error)})))))))

(defn scan!
  "Admit ready cards up to repository capacity, returning dispatch receipts.

  Scheduled and manual scans serialize on runtime-owned state. A manual caller
  may supply its friendly identity for best-effort assignment attribution;
  scheduler-driven scans supply none and never fabricate one. Errors stay on
  their card and are never retried by another scan. Accepted assignments remain
  assigned after process exit; moving a card or toggling its label cannot rearm
  it. Use explicit Harnesses continuation for subsequent work."
  ([rt] (scan! rt nil))
  ([rt by-identity]
   (let [config-lock (:config (state rt))]
     (locking config-lock
       (let [config @config-lock]
         (if-not (:enabled? config)
           {:enabled false :dispatched []}
           (let [all-runs (runs rt)
                 _ (recover! rt all-runs)
                 active-runs (filter occupied? all-runs)
                 occupied-targets (set (keep #(attr-get % :harness/target) active-runs))
                 assigned (weaver/list rt [:= [:attr "auto-run/status"] "assigned"] {})
                 active-ids (set (map :id active-runs))
                 busy (count (filter #(contains? active-ids (attr-get % :auto-run/run-id)) assigned))
                 capacity (max 0 (- (:max-running config) busy))
                 candidates (->> (weaver/ready rt)
                                 (filter #(eligible? rt %))
                                 (remove #(contains? occupied-targets (:id %)))
                                 (sort-by (juxt #(attr-get % :kanban/priority)
                                                :created_at :id))
                                 (take capacity))]
             {:enabled true :busy busy
              :dispatched (vec (keep #(dispatch! rt config % by-identity) candidates))})))))))

(defn wake!
  "Rearm and scan one durable wake, ignoring an obsolete configuration."
  [{:keys [runtime payload]}]
  (let [config-lock (:config (state runtime))]
    (locking config-lock
      (let [config @config-lock]
        (when (and (:enabled? config) (= (:generation config) (:generation payload)))
          (arm! runtime config)
          (scan! runtime))))))

(defn classify
  "Classify already-collected delivery evidence without mutation."
  [collected]
  (explanation/classify collected))

(defn explain
  "Return one bounded, read-only delivery explanation for `card-id`."
  [rt card-id]
  (let [config @(:config (state rt))]
    (when-not config
      (fail! "Auto-run is not configured for this runtime" {}))
    (explanation/explain rt config card-id)))

(defn status
  "Return configuration and durable card receipts without inferring completion."
  [rt]
  (let [config @(:config (state rt))]
    {:enabled (boolean (:enabled? config))
     :config (when config
               (cond-> (dissoc config :generation)
                 (:start-params config) (update :start-params str)
                 (get-in config [:evidence-adapters :land])
                 (update-in [:evidence-adapters :land] str)
                 true (update :prepare str)
                 true (update :workflows sort)))
     :cards (weaver/list rt [:not [:missing [:attr "auto-run/status"]]] {})}))

(millstrand/defop auto-run
  "Inspect or explicitly scan the repository's automatic card dispatcher."
  {:arg-spec
   {:subcommands
    {"status" {:doc "Show configuration and durable dispatch receipts."
               :hook-class :read :deadline-class :standard}
     "explain" {:doc "Explain one feature's recorded delivery evidence without mutation."
                :hook-class :read :deadline-class :standard
                :positionals [{:name :card-id :type :string :required? true}]}
     "register-worker"
     {:doc "Register an authorized, accepted recovery worker before handoff freezes."
      :hook-class :mutating :deadline-class :standard
      :flags {:card {:type :string :required? true :doc "Delivery feature card."}
              :worker {:type :string :required? true :doc "Accepted continuation run ID."}
              :expected-current-worker {:type :string :required? true
                                        :doc "Expected predecessor receipt (compare before update)."}
              :reason {:type :string :required? true :doc "Authorized recovery reason."}
              :by-identity {:type :string :required? true :doc "Coordinator attribution, not authorization."}}}
     "scan" {:doc "Admit eligible cards once, respecting configured capacity."
             :hook-class :mutating :deadline-class :standard
             :flags {:by-identity {:type :string
                                   :doc "Identity initiating this scan."}}}}}
   :prime "Configure dispatch in a repository lifecycle resource. Only pending,
           graph-ready features labelled auto-run are admitted. Card overrides
           are auto-run/seat, auto-run/effort and auto-run/workflow. Inspect
           status and linked agent runs; failures require explicit intervention.
           Removing the label prevents admission, not an already accepted run."}
  [{:op/keys [runtime args]}]
  (case (:subcommand args)
    ["status"] (status runtime)
    ["explain"] (explain runtime (:card-id args))
    ["scan"] (scan! runtime (:by-identity args))
    ["register-worker"] (recovery/register-worker!
                         runtime (select-keys args [:card :worker :expected-current-worker
                                                    :reason :by-identity]))))
