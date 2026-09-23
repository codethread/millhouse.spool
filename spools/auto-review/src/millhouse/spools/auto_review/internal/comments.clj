(ns millhouse.spools.auto-review.internal.comments
  "Normalize untrusted reviewer JSON into durable review comments.

  Reviewer processes return strict JSON, not rendered Markdown. This namespace
  is the pure trust boundary: it validates the complete result, models source
  positions explicitly, projects review-comment strands, validates positions against
  GitLab diff rows, and renders the optional Markdown report from structured
  records."
  (:require [clojure.data.json :as json]
            [clojure.edn :as edn]
            [clojure.string :as str]
            [clojure.walk :as walk]
            [millstrand.api.spool.alpha :refer [attr-get]]))

(def ^:private result-keys #{:summary :comments})
(def ^:private comment-keys #{:title :text :severity :position})
(def ^:private line-position-keys
  #{:kind :oldPath :newPath :side :line :startSide :startLine})
(def ^:private reason-position-keys #{:kind :reason})
(def ^:private curation-request-keys #{:revision :expectedVersion :by :changes})
(def ^:private curation-change-keys #{:id :inclusion :candidate})
(def ^:private candidate-change-keys #{:expectedVersion :text})
(def ^:private publish-request-keys #{:revision :curationVersion})

(defn- fail! [message data]
  (throw (ex-info message data)))

(defn- non-blank-string? [value]
  (and (string? value) (not (str/blank? value))))

(defn- exact-keys! [value allowed context]
  (when-not (map? value)
    (fail! (str context " must be an object") {:value value}))
  (when-let [unknown (seq (remove allowed (keys value)))]
    (fail! (str context " has unknown fields") {:unknown (vec unknown)}))
  value)

(defn- required-string! [value field context]
  (when-not (non-blank-string? value)
    (fail! (str context " requires non-blank " (name field)) {field value}))
  value)

(defn normalize-position
  "Validate one explicit reviewer position and return its canonical union arm."
  [position]
  (when-not (map? position)
    (fail! "Review review-comment position must be an object" {:position position}))
  (case (:kind position)
    "line"
    (let [position (exact-keys! position line-position-keys "Line position")
          side (:side position)
          line (:line position)
          start-side (:startSide position)
          start-line (:startLine position)]
      (doseq [field [:oldPath :newPath]]
        (required-string! (field position) field "Line position"))
      (when-not (contains? #{"old" "new"} side)
        (fail! "Line position side must be old or new" {:side side}))
      (when-not (pos-int? line)
        (fail! "Line position requires a positive line" {:line line}))
      (when-not (= (some? start-side) (some? start-line))
        (fail! "Line range requires both startSide and startLine" {:position position}))
      (when start-line
        (when-not (and (= side start-side) (pos-int? start-line) (<= start-line line))
          (fail! "Line range must start on the same side at or before line"
                 {:position position})))
      (cond-> {:kind "line" :oldPath (:oldPath position)
               :newPath (:newPath position) :side side :line line}
        start-line (assoc :startSide start-side :startLine start-line)))

    "general"
    (let [position (exact-keys! position reason-position-keys "General position")]
      {:kind "general"
       :reason (required-string! (:reason position) :reason "General position")})

    "unsupported"
    (let [position (exact-keys! position reason-position-keys "Unsupported position")]
      {:kind "unsupported"
       :reason (required-string! (:reason position) :reason "Unsupported position")})

    (fail! "Review review-comment position kind must be line, general, or unsupported"
           {:position position})))

(defn parse-result
  "Parse one Harness result as strict JSON and attach its authoritative reviewer.

  The whole result must be a JSON object with a summary and comments. Fenced JSON
  or prose is rejected so no rendered view is ever treated as source data."
  [reviewer run-id result]
  (required-string! reviewer :reviewer "Review result")
  (required-string! run-id :runId "Review result")
  (when-not (non-blank-string? result)
    (fail! "Reviewer returned no structured result" {:reviewer reviewer}))
  (let [parsed (try
                 (json/read-str result :key-fn keyword)
                 (catch Exception error
                   (throw (ex-info "Reviewer result is not strict JSON"
                                   {:reviewer reviewer} error))))]
    (exact-keys! parsed result-keys "Review result")
    (let [summary (required-string! (:summary parsed) :summary "Review result")
          raw-comments (:comments parsed)]
      (when-not (vector? raw-comments)
        (fail! "Review result comments must be an array" {:reviewer reviewer}))
      {:reviewer reviewer
       :summary summary
       :comments
       (mapv
        (fn [index review-comment]
          (exact-keys! review-comment comment-keys "Review comment")
          {:ordinal index
           :runId run-id
           :reviewer reviewer
           :category reviewer
           :title (required-string! (:title review-comment) :title "Review comment")
           :text (required-string! (:text review-comment) :text "Review comment")
           :severity (when (some? (:severity review-comment))
                       (required-string! (:severity review-comment) :severity "Review comment"))
           :position (normalize-position (:position review-comment))})
        (range) raw-comments)})))

(defn normalize-curation-request
  "Validate the typed CAS mutation accepted by review curate."
  [request]
  (let [request (walk/keywordize-keys request)]
    (exact-keys! request curation-request-keys "Curation request")
    (let [revision (required-string! (:revision request) :revision "Curation request")
          expected-version (:expectedVersion request)
          by (required-string! (:by request) :by "Curation request")
          changes (:changes request)]
      (when-not (and (int? expected-version) (not (neg? expected-version)))
        (fail! "Curation expectedVersion must be a non-negative integer"
               {:expectedVersion expected-version}))
      (when-not (and (vector? changes) (seq changes))
        (fail! "Curation changes must be a nonempty array" {:changes changes}))
      (let [changes
            (mapv
             (fn [change]
               (exact-keys! change curation-change-keys "Curation change")
               (let [id (required-string! (:id change) :id "Curation change")
                     inclusion (:inclusion change)
                     candidate (:candidate change)]
                 (when-not (or (some? inclusion) (some? candidate))
                   (fail! "Curation change requires inclusion or candidate" {:id id}))
                 (when (and (some? inclusion)
                            (not (contains? #{"included" "dismissed"} inclusion)))
                   (fail! "Curation inclusion must be included or dismissed"
                          {:id id :inclusion inclusion}))
                 (cond-> {:id id}
                   (some? inclusion) (assoc :inclusion inclusion)
                   (some? candidate)
                   (assoc :candidate
                          (let [candidate (exact-keys! candidate candidate-change-keys
                                                       "Candidate change")
                                version (:expectedVersion candidate)]
                            (when-not (pos-int? version)
                              (fail! "Candidate expectedVersion must be a positive integer"
                                     {:id id :expectedVersion version}))
                            {:expectedVersion version
                             :text (required-string! (:text candidate) :text
                                                     "Candidate change")})))))
             changes)
            ids (mapv :id changes)]
        (when-not (= (count ids) (count (distinct ids)))
          (fail! "Curation changes contain duplicate review-comment IDs" {:ids ids}))
        {:revision revision :expectedVersion expected-version :by by :changes changes}))))

(defn normalize-publish-request
  "Validate the publication pointer to one persisted canonical curation snapshot."
  [request]
  (let [request (walk/keywordize-keys request)]
    (exact-keys! request publish-request-keys "Publish request")
    (let [revision (required-string! (:revision request) :revision "Publish request")
          version (:curationVersion request)]
      (when-not (and (int? version) (not (neg? version)))
        (fail! "Publish curationVersion must be a non-negative integer"
               {:curationVersion version}))
      {:revision revision :curationVersion version})))

(defn comment-attributes
  "Return the durable attributes for one normalized comment."
  [review-comment]
  (cond-> {:kind "review-comment"
           :mr-review/comment "true"
           :mr-review/original-text (:text review-comment)
           :mr-review/candidate-text (:text review-comment)
           :mr-review/candidate-version 1
           :mr-review/candidate-source
           (pr-str {:kind "reviewer" :reviewer (:reviewer review-comment)
                    :runId (:runId review-comment)})
           :mr-review/inclusion "included"
           :mr-review/title (:title review-comment)
           :mr-review/run (:runId review-comment)
           :mr-review/reviewer (:reviewer review-comment)
           :mr-review/category (:category review-comment)
           :mr-review/position (pr-str (:position review-comment))
           :mr-review/publication-status "unpublished"}
    (:severity review-comment) (assoc :mr-review/severity (:severity review-comment))))

(defn- data [strand key]
  (let [value (attr-get strand key)]
    (if (string? value) (edn/read-string value) value)))

(defn comment-view
  "Project one durable review-comment strand into the typed public representation."
  [review-comment]
  (let [state (or (attr-get review-comment :mr-review/publication-status) "unpublished")
        error (data review-comment :mr-review/publication-error)
        source (data review-comment :mr-review/candidate-source)
        discussion-id (attr-get review-comment :mr-review/discussion-id)]
    (cond->
     {:id (:id review-comment)
      :inclusion (or (attr-get review-comment :mr-review/inclusion) "included")
      :candidate {:text (attr-get review-comment :mr-review/candidate-text)
                  :version (or (attr-get review-comment :mr-review/candidate-version) 1)
                  :source source
                  :original {:text (attr-get review-comment :mr-review/original-text)
                             :reviewer (attr-get review-comment :mr-review/reviewer)
                             :runId (attr-get review-comment :mr-review/run)}}
      :title (attr-get review-comment :mr-review/title)
      :category (attr-get review-comment :mr-review/category)
      :position (data review-comment :mr-review/position)
      :publication (cond-> {:state state
                            :retryable (contains? #{"unpublished" "reconciling" "failed"} state)}
                     discussion-id (assoc :discussionId discussion-id)
                     (:reason error) (assoc :error (:reason error)))}
      (attr-get review-comment :mr-review/severity)
      (assoc :severity (attr-get review-comment :mr-review/severity)))))

(defn render-report
  "Render the optional human-readable report from normalized structured data."
  [mr passes comments]
  (let [location (fn [{:keys [kind oldPath newPath side line reason]}]
                   (case kind
                     "line" (str (if (= "new" side) newPath oldPath) ":" line
                                 " (" side ")")
                     "general" (str "general discussion — " reason)
                     "unsupported" (str "unsupported position — " reason)))
        rendered-comments
        (map (fn [{:keys [reviewer severity title text position]}]
               (str "### " (when severity (str severity " — ")) title "\n"
                    "Reviewer: " reviewer "\n\n"
                    "Position: " (location position) "\n\n" text))
             comments)]
    (str "MR !" (:iid mr) " — " (:web_url mr) "\n"
         "Head: " (:sha mr) "\n"
         "Base: " (get-in mr [:diff_refs :base_sha]) "\n"
         "Structured reviewer comments; publication is a separate explicit action.\n\n"
         (str/join "\n\n" (concat
                           (map (fn [{:keys [reviewer summary]}]
                                  (str "## " reviewer "\n\n" summary)) passes)
                           rendered-comments)))))

(defn settlement
  "Compile successful Harness runs into the one authoritative review snapshot."
  [mr runs]
  (let [passes (mapv (fn [{:keys [id reviewer status substatus result]}]
                       (merge {:id id :status status :substatus substatus}
                              (parse-result reviewer id result)))
                     runs)
        comment-records (vec (mapcat :comments passes))]
    {:passes passes
     :comment-records comment-records
     :report (render-report mr passes comment-records)}))

(defn message-settlement
  "Build a structured empty snapshot for coordinator or execution failures."
  [mr runs message]
  (let [passes (if (seq runs)
                 (mapv (fn [{:keys [id reviewer status substatus error]}]
                         {:id id :reviewer reviewer :status status :substatus substatus
                          :summary message :error (or error message)}) runs)
                 [{:reviewer "coordinator" :summary message}])]
    {:passes passes :comment-records []
     :report (render-report mr passes [])}))

(defn changed-lines
  "Return explicit added/removed line identities from GitLab diff rows."
  [diffs]
  (reduce
   (fn [result {:keys [old_path new_path diff]}]
     (loop [lines (str/split-lines (or diff ""))
            old-line nil new-line nil result result]
       (if-let [line (first lines)]
         (if-let [[_ old-start _ new-start _]
                  (re-matches #"@@ -(\d+)(?:,(\d+))? \+(\d+)(?:,(\d+))? @@.*" line)]
           (recur (next lines) (parse-long old-start) (parse-long new-start) result)
           (cond
             (or (nil? old-line) (str/starts-with? line "\\"))
             (recur (next lines) old-line new-line result)

             (str/starts-with? line "+")
             (recur (next lines) old-line (inc new-line)
                    (conj result [old_path new_path "new" new-line]))

             (str/starts-with? line "-")
             (recur (next lines) (inc old-line) new-line
                    (conj result [old_path new_path "old" old-line]))

             :else
             (recur (next lines) (inc old-line) (inc new-line) result)))
         result)))
   #{} diffs))

(defn position-valid?
  "Return whether a position can be published against these exact GitLab diffs."
  [diffs position]
  (case (:kind position)
    "general" true
    "unsupported" false
    "line" (let [changed (changed-lines diffs)
                 start (or (:startLine position) (:line position))]
             (every? #(contains? changed [(:oldPath position) (:newPath position)
                                          (:side position) %])
                     (range start (inc (:line position)))))
    false))
