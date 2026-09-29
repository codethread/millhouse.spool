(ns me.notifications.attention
  "Notify this repository's user about failed auto-runs and attention tickets."
  (:require [clojure.string :as str]
            [millhouse.chime :refer [defrule!]]
            [millstrand.api.spool.alpha :refer [attr-get]]))

(defn- context-value
  "Read `key` from a Harnesses assignment context."
  [strand key]
  (let [context (attr-get strand :harness/context)]
    (or (get context key)
        (get context (keyword key)))))

(defn- auto-run? [strand]
  (and (= "true" (attr-get strand :harness/run))
       (or (= "auto-run-workflow"
              (context-value strand "assignment/policy"))
           (some-> (attr-get strand :harness/request-id)
                   (str/starts-with? "auto-land-finisher/")))))

(defn- auto-run-failure-body [strand]
  (str/join
   "\n"
   (cond-> [(str "Auto-run " (:id strand) " failed.")]
     (attr-get strand :harness/target)
     (conj (str "Target: " (attr-get strand :harness/target)))

     (attr-get strand :harness/substatus)
     (conj (str "Failure class: " (attr-get strand :harness/substatus)))

     (some? (attr-get strand :harness/exit-code))
     (conj (str "Exit code: " (attr-get strand :harness/exit-code)))

     (attr-get strand :harness/error)
     (conj (str "Error: " (attr-get strand :harness/error)))

     true
     (conj (str "Inspect with `strand agent show " (:id strand) "`.")))))

(defrule! auto-run-failed
  "Notify when a delivery worker or full-land finisher enters failed state."
  [{:keys [strand]}]
  (when (and (auto-run? strand)
             (= "failed" (attr-get strand :harness/status)))
    {:title (str "Auto-run failed: " (:title strand))
     :body (auto-run-failure-body strand)}))

(defrule! ticket-needs-attention
  "Notify when an active Kanban ticket enters the user-attention lane."
  [{:keys [strand]}]
  (when (and (= "active" (:state strand))
             (= "true" (attr-get strand :kanban/card))
             (= "in_review" (attr-get strand :kanban/lane)))
    {:title (str "Ticket needs attention: " (:title strand))
     :body (str "Ticket " (:id strand) " is waiting for you in the in_review lane."
                (when-let [evidence (attr-get strand :auto-run/agent-evidence)]
                  (str "\nEvidence: " evidence))
                "\nInspect with `strand kanban card " (:id strand) "`.")}))
