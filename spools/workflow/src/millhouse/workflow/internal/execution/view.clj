(ns millhouse.workflow.internal.execution.view
  "Private read projection shared by public inspection and Workflow attention."
  (:require [millstrand.api.spool.alpha :refer [attr-get]]
            [millstrand.api.weaver.alpha :as weaver]
            [millhouse.workflow.internal.execution.chart :as chart]
            [millhouse.workflow.internal.execution.data :as data]
            [millhouse.workflow.internal.execution.state :as state]))

(defn gate-view [rt gate]
  (let [token (attr-get gate :execution/current)
        attempt (when token
                  (some-> (first (weaver/list rt [:and [:= [:attr "kind"] "workflow-execution"]
                                                  [:= [:attr "execution/token"] token]] {}))
                          (attr-get :execution/data) data/decode))
        view (when attempt (chart/view (:snapshot attempt)))
        selected (get (state/selected rt) (attr-get gate :execution/owner))]
    {:gate-id (:id gate) :owner (attr-get gate :execution/owner)
     :attempt-id (:attempt-id attempt) :request (:request attempt)
     :deadline (:deadline attempt) :phase (or (:phase view) :unstarted)
     :accepted? (:accepted? view) :dispatch-uncertain? (:uncertain? view)
     :stop-reason (:stop-reason view) :result (:result attempt)
     :cleanup {:acknowledgement (:acknowledgement view)
               :root-finalization-pending? (boolean (:finalization-pending? attempt))}
     :attention (or (:attention attempt) (:attention view)
                    (when (and (attr-get gate :execution/owner) (nil? selected))
                      {:code "execution/descriptor-missing" :message "Select the recorded descriptor to reconcile" :data {}})
                    (get @(:errors (state/state rt)) [:attempt token])
                    (get @(:errors (state/state rt)) [:gate (:id gate)])
                    (get @(:errors (state/state rt)) :scan))}))

(defn stalled [rt item]
  (let [view (gate-view rt (weaver/show rt (:id item)))
        error (or (:attention view) (get-in view [:stop-reason :error])
                  (when (not= :succeeded (get-in view [:result :outcome]))
                    (get-in view [:result :error])))]
    (when error {:gate (:gate-id view) :error error})))
