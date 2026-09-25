(ns millhouse.auto-run-reporting
  "Publish agent blockers with evidence references through validated patterns."
  (:require [clojure.spec.alpha :as s]
            [clojure.string :as str]
            [millstrand.api.millstrand.alpha :as millstrand]
            [millstrand.api.spool.alpha :refer [attr-get attr-key->str fail! require-valid!]]
            [millstrand.api.weaver.alpha :as weaver]))

(s/def ::strand (s/and string? (complement str/blank?)))
(s/def ::evidence ::strand)
(s/def ::report-input
  (s/and (s/keys :req-un [::strand ::evidence])
         #(every? #{:strand :evidence} (keys %))))
(s/def ::unblock-input
  (s/and (s/keys :req-un [::strand]) #(= #{:strand} (set (keys %)))))
(s/def :auto-run/agent-blocked (s/nilable #{"true"}))
(s/def :auto-run/agent-blocked-status (s/nilable #{"needs-decision" "unknown-failure"}))
(s/def :auto-run/agent-evidence (s/nilable ::evidence))
(s/def ::blocker
  (s/and (s/keys :req [:auto-run/agent-blocked :auto-run/agent-blocked-status
                       :auto-run/agent-evidence])
         #(if (= "true" (:auto-run/agent-blocked %))
            (and (some? (:auto-run/agent-blocked-status %))
                 (some? (:auto-run/agent-evidence %)))
            (and (nil? (:auto-run/agent-blocked-status %))
                 (nil? (:auto-run/agent-evidence %))))))

(defn- report-payload [{:keys [strand evidence]} status]
  {:refs {:target strand :evidence evidence}
   :strands [{:ref :target
              :attributes {:auto-run/agent-blocked "true"
                           :auto-run/agent-blocked-status status
                           :auto-run/agent-evidence evidence}}]})

(millstrand/defpattern auto-run-needs-decision
  "Report that the agent needs a decision, then end the run.

  Input: strand (work strand ID), evidence (existing evidence strand ID).
  Save the question and context on that evidence strand before applying this
  pattern. The complete blocker state is published atomically."
  {:spec ::report-input}
  [{:keys [input]}]
  (report-payload input "needs-decision"))

(millstrand/defpattern auto-run-unknown-failure
  "Report a problem the agent cannot resolve, then end the run.

  Input: strand (work strand ID), evidence (existing evidence strand ID).
  Save the investigation and supporting evidence before applying this pattern.
  The complete blocker state is published atomically."
  {:spec ::report-input}
  [{:keys [input]}]
  (report-payload input "unknown-failure"))

(millstrand/defpattern auto-run-unblock
  "Clear the agent blocker without starting or resuming work.

  Input: strand (work strand ID). Remove all three blocker attributes together;
  retain the referenced evidence strand."
  {:spec ::unblock-input}
  [{:keys [input]}]
  {:refs {:target (:strand input)}
   :strands [{:ref :target
              :attributes {:auto-run/agent-blocked nil
                           :auto-run/agent-blocked-status nil
                           :auto-run/agent-evidence nil}}]})

(millstrand/defhook derive-labels
  "Derive board labels atomically from a complete agent-blocker update.

  Explicit selection enables the hook. An omitted blocker is unchanged;
  a blocker update must carry all three attributes, including nil on removal."
  {:types #{:attributes/normalize}}
  [{:keys [hook/value]}]
  (let [attrs (into {} (map (fn [[key value]] [(keyword (attr-key->str key)) value])) value)
        blocker (select-keys attrs [:auto-run/agent-blocked :auto-run/agent-blocked-status
                                    :auto-run/agent-evidence])]
    {:hook/value
     (if (empty? blocker)
       value
       (let [blocker (require-valid! ::blocker blocker "Invalid agent blocker")]
         (assoc (dissoc value :kanban.label/agent-blocked "kanban.label/agent-blocked"
                        :kanban.label/needs-decision "kanban.label/needs-decision")
                "kanban.label/agent-blocked" (:auto-run/agent-blocked blocker)
                "kanban.label/needs-decision"
                (when (= "needs-decision" (:auto-run/agent-blocked-status blocker)) "true"))))}))

(defn read-blocker
  "Read the agent-reported blocker and resolve its evidence strand summary.

  Return an unblocked state or the blocked status with evidence ID and title.
  Missing evidence or an incomplete union fails visibly."
  [rt strand]
  (let [attrs (require-valid!
               ::blocker
               {:auto-run/agent-blocked (attr-get strand :auto-run/agent-blocked)
                :auto-run/agent-blocked-status (attr-get strand :auto-run/agent-blocked-status)
                :auto-run/agent-evidence (attr-get strand :auto-run/agent-evidence)}
               "Invalid agent blocker")]
    (if (= "true" (:auto-run/agent-blocked attrs))
      (let [id (:auto-run/agent-evidence attrs)
            evidence (or (weaver/show rt id)
                         (fail! "Agent evidence strand not found" {:strand (:id strand) :evidence id}))]
        {:blocked true :status (:auto-run/agent-blocked-status attrs)
         :evidence (select-keys evidence [:id :title])})
      {:blocked false})))
