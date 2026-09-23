(ns millhouse.spools.auto-review.internal.board
  "Own the durable graph model for review revisions and reviewer evidence.

  Review roots identify exact MR revisions. Evidence children target Harnesses
  runs, immutable notes hold reports, and review-of edges connect optional work.
  Mutations that publish runs or terminal evidence are batched here so the
  coordinator can reason in complete state transitions."
  (:require [clojure.edn :as edn]
            [millhouse.spools.auto-review.internal.comments :as comments]
            [millstrand.api.batch.alpha :as batch]
            [millstrand.api.notes.alpha :as notes]
            [millstrand.api.runtime.alpha :as runtime]
            [millstrand.api.spool.alpha :refer [attr-get]]
            [millstrand.api.weaver.alpha :as weaver]))

(defn data [strand key]
  (let [value (attr-get strand key)]
    (if (string? value) (edn/read-string value) value)))

(defn cards [rt]
  (weaver/list rt [:= [:attr "mr-review/review"] "true"] {}))

(defn key-for [config mr]
  (str (:repo-dir config) ":" (:project_id mr) ":" (:iid mr) ":" (:sha mr)))

(defn stage [review] (attr-get review :mr-review/stage))

(defn patch! [rt review attrs]
  (weaver/update! rt (:id review) {:attributes attrs})
  (weaver/show rt (:id review)))

(defn children [rt review]
  (weaver/list rt [:edge/in "parent-of" [:= :id (:id review)]] {}))

(defn comments [rt review]
  (filterv #(= "true" (attr-get % :mr-review/comment)) (children rt review)))

(defn report-note [rt review]
  (first (weaver/list rt
                      [:and [:= [:attr "mr-review/report"] "true"]
                       [:edge/out "notes" [:= :id (:id review)]]] {})))

(defn claim! [rt config mr]
  (weaver/add! rt
               {:title (str "Review !" (:iid mr) ": " (:title mr) " @" (subs (:sha mr) 0 8))
                :attributes {:kind "review" :mr-review/review "true" :mr-review/version 2
                             :mr-review/key (key-for config mr) :mr-review/repo (:repo-dir config)
                             :mr-review/mr (pr-str mr) :mr-review/stage "preparing"
                             :mr-review/current "true" :mr-review/decision "pending"
                             :mr-review/created-at (str (runtime/now rt))}}))

(defn prepare-dispatch!
  "Each reviewer owns an evidence strand, also its Harnesses target."
  [rt review plans]
  (let [refs (mapv #(keyword (str "reviewer" %)) (range (count plans)))
        result (batch/apply! rt
                             {:refs {:review (:id review)}
                              :strands (mapv (fn [ref plan]
                                               {:ref ref :title (str "Review: " (:reviewer plan))
                                                :attributes {:kind "review-pass" :mr-review/pass "true"
                                                             :mr-review/reviewer (:reviewer plan)}})
                                             refs plans)
                              :edges (mapv (fn [ref] {:op :upsert :from :review :to ref :type "parent-of"}) refs)})
        plans (mapv (fn [ref plan] (assoc plan :target (get-in result [:refs ref]))) refs plans)]
    (patch! rt review {:mr-review/stage "dispatching" :mr-review/plans (pr-str plans)})))

(defn note! [rt review text]
  (notes/note! rt (:id review) text {:by "mr-review" :note/kind "activity"}))

(defn record-runs! [rt review runs]
  (let [plans (data review :mr-review/plans)
        bindings (into {:review (:id review)}
                       (mapcat (fn [i plan run]
                                 [[(keyword (str "pass" i)) (:target plan)]
                                  [(keyword (str "run" i)) (:id run)]])
                               (range) plans runs))]
    (batch/apply! rt
                  {:refs bindings
                   :strands (into [{:ref :review :attributes {:mr-review/runs (pr-str runs)
                                                              :mr-review/stage "running"}}]
                                  (map-indexed (fn [i run]
                                                 {:ref (keyword (str "pass" i))
                                                  :attributes {:mr-review/run (:id run)}}) runs))
                   :edges (mapv (fn [i] {:op :upsert :from (keyword (str "pass" i))
                                         :to (keyword (str "run" i)) :type "review-run"}) (range (count runs)))})))

(defn finish!
  "Atomically freeze structured comments, reviewer summaries, and their derived report.

  Settlement is write-once. Recovery may restore the terminal stage but never
  replaces settled candidates with a later agent result."
  [rt review outcome {:keys [passes comment-records report]}]
  (let [review (weaver/show rt (:id review))
        pass-strands (filterv #(= "true" (attr-get % :mr-review/pass)) (children rt review))
        by-name (into {} (map (juxt :reviewer identity)) passes)
        completed-at (str (runtime/now rt))
        revision (str (java.util.UUID/randomUUID))
        comment-refs (mapv #(keyword (str "comment" %)) (range (count comment-records)))
        refs (into {:review (:id review)}
                   (map-indexed #(vector (keyword (str "pass" %1)) (:id %2)) pass-strands))]
    (when-not (report-note rt review)
      (batch/apply!
       rt
       {:refs refs
        :strands
        (into
         [{:ref :review
           :attributes {:mr-review/stage outcome
                        :mr-review/completed-at completed-at
                        :mr-review/comment-revision revision
                        :mr-review/curation-version 0
                        :mr-review/publication-state "unpublished"}}
          {:ref :report :title "Structured MR review" :state "closed"
           :attributes {:note/text report :note/at completed-at :note/by "mr-review"
                        :note/kind "review-report" :mr-review/report "true"}}]
         (concat
          (map-indexed
           (fn [i pass]
             (let [result (by-name (attr-get pass :mr-review/reviewer))]
               {:ref (keyword (str "pass" i)) :state "closed"
                :attributes (when result
                              {:mr-review/status (:status result)
                               :mr-review/substatus (:substatus result)
                               :mr-review/summary (:summary result)
                               :mr-review/error (:error result)})}))
           pass-strands)
          (map (fn [ref review-comment]
                 {:ref ref :title (:title review-comment) :state "closed"
                  :attributes (comments/comment-attributes review-comment)})
               comment-refs comment-records)))
        :edges (into [{:op :upsert :from :report :to :review :type "notes"}]
                     (map (fn [ref] {:op :upsert :from :review :to ref :type "parent-of"})
                          comment-refs))}))
    ;; Recovery can replay dispatch after publication; restore terminal stage
    ;; without duplicating evidence or updating frozen candidates.
    (when (and (report-note rt review)
               (not (contains? #{"reviewed" "failed"}
                               (stage (weaver/show rt (:id review))))))
      (patch! rt review {:mr-review/stage outcome}))))
