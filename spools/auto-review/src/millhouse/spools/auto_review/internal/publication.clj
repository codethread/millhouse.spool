(ns millhouse.spools.auto-review.internal.publication
  "Publish one persisted curation snapshot through the coordinator's GitLab boundary.

  Publication never decides or tears down a review. Every review-comment enters a
  durable reconciling state before remote I/O. The hidden marker is then searched
  across all discussions; only a successful absence check permits a POST, so an
  ambiguous prior success is never retried blindly."
  (:require [clojure.string :as str]
            [millhouse.spools.auto-review.internal.board :as board]
            [millhouse.spools.auto-review.internal.comments :as comments]
            [millhouse.spools.auto-review.internal.io :as review-io]
            [millhouse.spools.auto-review.internal.logs :as logs]
            [millhouse.spools.auto-review.internal.views :as views]
            [millstrand.api.batch.alpha :as batch]
            [millstrand.api.runtime.alpha :as runtime]
            [millstrand.api.spool.alpha :refer [attr-get]]))

(defn marker
  "Return the stable, invisible remote idempotency marker for one comment."
  [review revision review-comment]
  (str "<!-- millstrand-review=" (:id review)
       ";revision=" revision ";comment=" (:id review-comment) " -->"))

(defn- fail! [message data]
  (throw (ex-info message data)))

(defn- assert-local! [review revision curation-version]
  (when-not (= "active" (:state review))
    (fail! "Review is not active" {:id (:id review) :state (:state review)}))
  (when-not (= "reviewed" (board/stage review))
    (fail! "Review has not settled structured comments"
           {:id (:id review) :stage (board/stage review)}))
  (when-not (= "pending" (attr-get review :mr-review/decision))
    (fail! "Review no longer awaits a local decision" {:id (:id review)}))
  (when-not (= "true" (attr-get review :mr-review/current))
    (fail! "Review is not the current MR revision" {:id (:id review)}))
  (when-not (= revision (attr-get review :mr-review/comment-revision))
    (fail! "Review revision is stale"
           {:id (:id review) :expected (attr-get review :mr-review/comment-revision)
            :got revision}))
  (when-not (= curation-version (or (attr-get review :mr-review/curation-version) 0))
    (fail! "Curation version is stale"
           {:id (:id review)
            :expected (or (attr-get review :mr-review/curation-version) 0)
            :got curation-version})))

(defn- assert-remote! [stored-mr {:keys [mr version]}]
  (let [expected {:project (:project_id stored-mr)
                  :iid (:iid stored-mr)
                  :head (:sha stored-mr)
                  :base (get-in stored-mr [:diff_refs :base_sha])
                  :start (get-in stored-mr [:diff_refs :start_sha])}
        actual {:project (:project_id mr) :iid (:iid mr) :head (:sha mr)
                :base (get-in mr [:diff_refs :base_sha])
                :start (get-in mr [:diff_refs :start_sha])}
        anchors {:head (:head_commit_sha version)
                 :base (:base_commit_sha version)
                 :start (:start_commit_sha version)}]
    (when-not (= "opened" (:state mr))
      (fail! "GitLab merge request is not open" {:expected expected :state (:state mr)}))
    (when-not (= expected actual)
      (fail! "GitLab merge request revision moved" {:expected expected :actual actual}))
    (when-not (= (select-keys expected [:head :base :start]) anchors)
      (fail! "GitLab latest diff anchors do not match the frozen review"
             {:expected (select-keys expected [:head :base :start]) :actual anchors}))
    expected))

(defn- assert-selection! [diffs included]
  (when-not (seq included)
    (fail! "Publication requires at least one included comment" {}))
  (doseq [review-comment included]
    (let [position (board/data review-comment :mr-review/position)
          text (attr-get review-comment :mr-review/candidate-text)]
      (when-not (and (string? text) (not (str/blank? text)))
        (fail! "Included review-comment has no candidate text" {:id (:id review-comment)}))
      (when (= "unsupported" (:kind position))
        (fail! "Unsupported review-comment position cannot be published"
               {:id (:id review-comment) :position position}))
      (when-not (comments/position-valid? diffs position)
        (fail! "Comment position is not valid in the frozen GitLab diff"
               {:id (:id review-comment) :position position})))))

(defn- freeze-selection! [rt review revision curation-version all-comments included]
  (let [selection (vec (sort (map :id included)))
        state (or (attr-get review :mr-review/publication-state) "unpublished")]
    (if (= "unpublished" state)
      (let [refs (into {:review (:id review)}
                       (map-indexed #(vector (keyword (str "comment" %1)) (:id %2))
                                    all-comments))]
        (batch/apply!
         rt
         {:refs refs
          :strands
          (into [{:ref :review
                  :attributes {:mr-review/publication-state "publishing"
                               :mr-review/publish-revision revision
                               :mr-review/publish-curation-version curation-version
                               :mr-review/publish-selection (pr-str selection)}}]
                (keep-indexed
                 (fn [index review-comment]
                   (when (= "dismissed" (attr-get review-comment :mr-review/inclusion))
                     {:ref (keyword (str "comment" index))
                      :attributes {:mr-review/publication-status "excluded"}}))
                 all-comments))}))
      (when-not (and (= revision (attr-get review :mr-review/publish-revision))
                     (= curation-version (attr-get review :mr-review/publish-curation-version))
                     (= selection (board/data review :mr-review/publish-selection)))
        (fail! "Publication retry does not match the frozen curation snapshot"
               {:id (:id review)})))))

(defn- published! [rt review-comment discussion-id]
  (comments/comment-view
   (board/patch! rt review-comment {:mr-review/publication-status "published"
                                    :mr-review/discussion-id (str discussion-id)
                                    :mr-review/publication-error nil})))

(defn- reconciling! [rt review-comment error]
  (comments/comment-view
   (board/patch! rt review-comment {:mr-review/publication-status "reconciling"
                                    :mr-review/publication-error
                                    (when error (pr-str (logs/error-facts error)))})))

(defn- publish-one! [rt config review stored-mr version revision review-comment]
  (if (= "published" (attr-get review-comment :mr-review/publication-status))
    (comments/comment-view review-comment)
    (let [marker (marker review revision review-comment)
          body (str (attr-get review-comment :mr-review/candidate-text) "\n\n" marker)]
      (reconciling! rt review-comment nil)
      (try
        (if-let [existing (review-io/find-discussion config stored-mr marker)]
          (published! rt review-comment (:id existing))
          (let [created (review-io/create-discussion!
                         config stored-mr version
                         (board/data review-comment :mr-review/position) body)]
            (when-not (:id created)
              (fail! "GitLab created no discussion receipt" {:id (:id review-comment)}))
            (published! rt review-comment (:id created))))
        (catch InterruptedException error (throw error))
        (catch Exception error
          ;; The remote effect is uncertain after any failed POST. Keep this
          ;; state until a complete marker search proves whether it exists.
          (reconciling! rt review-comment error))))))

(defn- receipt [comment-view]
  (let [{:keys [state discussionId retryable error]} (:publication comment-view)]
    (cond-> {:id (:id comment-view) :state state :retryable retryable}
      discussionId (assoc :discussionId discussionId)
      error (assoc :error error))))

(defn publish!
  "Publish the persisted included candidates for one exact curation version."
  [rt config id raw-request]
  (when-not config
    (fail! "Review is not configured; publication requires its GitLab boundary" {:id id}))
  (let [{:keys [revision curationVersion]}
        (comments/normalize-publish-request raw-request)
        lock (views/review-lock rt id)]
    (locking lock
      (let [review (views/require-review rt id)
            _ (assert-local! review revision curationVersion)
            stored-mr (board/data review :mr-review/mr)
            remote (review-io/publication-context config stored-mr)
            _ (assert-remote! stored-mr remote)
            all-comments (vec (board/comments rt review))
            included (filterv #(= "included" (attr-get % :mr-review/inclusion)) all-comments)
            _ (assert-selection! (:diffs remote) included)
            _ (freeze-selection! rt review revision curationVersion all-comments included)
            results (mapv #(publish-one! rt config review stored-mr (:version remote)
                                         revision %)
                          included)
            published (count (filter #(= "published" (get-in % [:publication :state])) results))
            overall (cond
                      (= published (count results)) "published"
                      (pos? published) "partial"
                      :else "failed")]
        (board/patch! rt review {:mr-review/publication-state overall
                                 :mr-review/published-at
                                 (when (= "published" overall) (str (runtime/now rt)))})
        {:reviewId id :revision revision :curationVersion curationVersion
         :state overall :comments (mapv receipt results)}))))
