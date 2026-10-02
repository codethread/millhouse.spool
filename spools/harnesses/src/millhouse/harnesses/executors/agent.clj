(ns millhouse.harnesses.executors.agent
  "Adapt headless Harnesses runs to the common Workflow attempt contract."
  (:require [clojure.spec.alpha :as s]
            [clojure.string :as str]
            [millhouse.harnesses :as harnesses]
            [millhouse.harnesses.internal.lifecycle :as life]
            [millhouse.workflow.execution :as execution]
            [millstrand.api.events.alpha :as events]
            [millstrand.api.format.alpha :as format-alpha]
            [millstrand.api.lifecycle.alpha :as lifecycle]
            [millstrand.api.spool.alpha :refer [attr-get fail! require-valid!]]
            [millstrand.api.weaver.alpha :as weaver]))

(defn- nonblank? [value]
  (and (string? value) (not (str/blank? value))))

(s/def ::request :millhouse.harnesses/create-request)
(s/def ::run-id nonblank?)
(s/def ::result nonblank?)
(s/def ::value (s/keys :req-un [::run-id ::result]))

(defn- attribute-name [key]
  (if (keyword? key)
    (if-let [namespace (namespace key)]
      (str namespace "/" (name key))
      (name key))
    (str key)))

(defn- overlay-key? [key]
  (let [key (attribute-name key)]
    (or (contains? #{"harness/model" "harness/effort"
                     "harness/extra-argv" "harness/appended-system-prompts"} key)
        (str/starts-with? key "harness."))))

(defn request
  "Freeze the effective prompt and all gate overlays from the captured image.

  Explicit malformed input is invalid, not an invitation to fall back. Alias
  resolution remains Harnesses-owned; dispatch never rereads gate attributes."
  [{:keys [gate run-id attempt-id]}]
  (let [attributes (into {} (map (fn [[k v]] [(attribute-name k) v])) (:attributes gate))
        prompt (if (contains? attributes "harness/prompt")
                 (get attributes "harness/prompt")
                 (some #(when (nonblank? %) %)
                       [(attr-get gate :workflow/instruction)
                        (attr-get gate :description) (:title gate)]))]
    (require-valid! nonblank? (attr-get gate :harness/alias) "Agent gate requires harness/alias")
    (require-valid! nonblank? prompt "Agent gate requires a nonblank prompt")
    (when (contains? attributes "harness/mode")
      (fail! "Agent workflow gates support headless runs only" {:gate (:id gate)}))
    (cond-> {:harness (attr-get gate :harness/alias)
             :mode :headless
             :prompt prompt
             :target (:id gate)
             :title (str "Agent: " (:title gate))
             :context {"workflow/run-id" run-id "workflow/gate-id" (:id gate)
                       "workflow/attempt-id" attempt-id}
             :attributes (into {} (filter (fn [[k _]] (overlay-key? k))) attributes)
             :append-system-prompt
             (format-alpha/prose
              "
                This run fulfils workflow gate {gate-id} ({gate-title}) in workflow
                run {run-id}.

                Your final message is recorded as the gate result. Do not close or
                mutate strands in this workflow.
                "
              {:gate-id (:id gate) :gate-title (:title gate) :run-id run-id})}
      (contains? attributes "harness/cwd")
      (assoc :cwd (require-valid! nonblank? (get attributes "harness/cwd")
                                  "Agent gate cwd must be a nonblank string")))))

(defn- unknown [reference message]
  {:status :unknown :reference reference
   :reason {:code "agent/unknown" :message message :data {}}})

(defn- failure-message [run]
  (let [error (attr-get run :harness/error)
        message (if (nonblank? error) error
                    "Agent did not complete successfully with nonblank findings")]
    (subs message 0 (min 2048 (count message)))))

(defn- observation [run]
  (let [id (:id run)
        result (attr-get run :harness/result)
        status (life/status run)]
    (cond
      (and (life/terminal? run) (not (life/settled? run)))
      (unknown id "Harnesses run settlement is unknown")

      (life/terminal? run)
      (let [success? (and (life/accepted? run) (= "stopped" status)
                          (= "completed" (life/substatus run))
                          (zero? (attr-get run :harness/exit-code)) (nonblank? result))
            outcome (cond success? :succeeded
                          (and (= "stopped" status) (= "requested" (life/substatus run))) :cancelled
                          :else :failed)]
        {:status :terminal :outcome outcome :settlement :settled
         :reference id :executor-run-id id
         :value (when success? {:run-id id :result result})
         :error (when-not success?
                  {:code (str "agent/" (name outcome))
                   :message (failure-message run)
                   :data {}})
         :evidence {"run-id" id "settlement" (attr-get run :harness/settlement)}})

      (not (life/accepted? run))
      (unknown id "Harnesses publication is not accepted")

      :else
      {:status :pending :reference id
       :phase (cond (life/stop-requested? run) :stopping
                    (= "ready" status) :waiting :else :running)})))

(defn- correlated-run [rt {:keys [request-id reference]}]
  (let [run (first (weaver/list rt [:and [:= [:attr "harness/run"] "true"]
                                    [:= [:attr "harness/request-id"] request-id]] {}))]
    (when (and reference (not= reference (:id run)))
      (fail! "Agent request/run correlation changed" {:request-id request-id :reference reference}))
    (when run (harnesses/run rt (:id run)))))

(defn start!
  "Publish exactly one request-bound Harnesses run for this common attempt."
  [rt {:keys [request request-id]}]
  (observation (harnesses/create! rt (assoc request :request-id request-id))))

(defn observe!
  "Adopt retained request evidence after lost publication response, never respawn."
  [rt {:keys [reference] :as context}]
  (if-let [run (correlated-run rt context)]
    (observation run)
    (unknown reference "No retained Harnesses request; publication is unknown")))

(defn stop!
  "Request stop through the public exact-run operation and observe settlement."
  [rt {:keys [reference] :as context}]
  (if-let [run (correlated-run rt context)]
    (observation (harnesses/stop! rt (:id run) {:reason "Workflow attempt stopped"}))
    (unknown reference "No retained Harnesses request; stop settlement is unknown")))

(defn acknowledge!
  "Retain Harnesses history; no backend evidence needs releasing."
  [_ _]
  {:status :acknowledged})

(def executor
  "Inert Agent descriptor; select agent-engine only after Harnesses and aliases."
  {:waiter :agent :revision "agent-v1"
   :request 'millhouse.harnesses.executors.agent/request :request-spec ::request :result-spec ::value
   :start 'millhouse.harnesses.executors.agent/start!
   :observe 'millhouse.harnesses.executors.agent/observe!
   :stop 'millhouse.harnesses.executors.agent/stop!
   :acknowledge 'millhouse.harnesses.executors.agent/acknowledge!})

(defn open-agent-engine!
  "Admit managed Agent work only after Harnesses execution is installed."
  [{:keys [runtime]}]
  (when-not (some #(and (= :on-event (:key %)) (= "harnesses" (get-in % [:metadata :spool])))
                  (events/handlers runtime))
    (fail! "Agent executor requires harness execution to be installed first" {}))
  (execution/open! runtime executor))

(defn close-agent-engine!
  "Remove Agent admission without inventing provider settlement."
  [{:keys [runtime resource] :as context}]
  (execution/close! runtime resource (:effect/phase context)))

(lifecycle/defresource agent-engine
  "Select the common Agent driver; Harnesses owns provider process custody."
  {:open 'millhouse.harnesses.executors.agent/open-agent-engine!
   :close 'millhouse.harnesses.executors.agent/close-agent-engine!})
