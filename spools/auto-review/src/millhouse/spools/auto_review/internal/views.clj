(ns millhouse.spools.auto-review.internal.views
  "Own the user-facing projections and local mutations for review records.

  List and show shape durable graph state into stable command responses. Link
  relates a review to implementation work, while decide records a terminal local
  disposition without implying or performing any GitLab action."
  (:require [millhouse.spools.auto-review.internal.board :as board]
            [millhouse.spools.auto-review.internal.comments :as comments]
            [millstrand.api.batch.alpha :as batch]
            [millstrand.api.graph.alpha :as graph]
            [millstrand.api.notes.alpha :as notes]
            [millstrand.api.runtime.alpha :as runtime]
            [millstrand.api.spool.alpha :refer [attr-get]]
            [millstrand.api.weaver.alpha :as weaver]))

(def stages #{"preparing" "dispatching" "running" "reviewed" "failed"})

(defn review-lock
  "Return the runtime-owned per-review lock shared by curation and publication."
  [rt id]
  (:lock (runtime/spool-state rt [::review-mutation id] {:version 1}
                              #(hash-map :lock (Object.)))))

(defn require-review [rt id]
  (let [review (weaver/show rt id)]
    (when-not (and review (= "true" (attr-get review :mr-review/review)))
      (throw (ex-info "Review not found; use strand review list --all" {:id id})))
    review))

(defn- run-view [rt pass detail?]
  (let [run-id (attr-get pass :mr-review/run)
        run (when run-id (weaver/show rt run-id))]
    (cond-> {:id (:id pass) :name (attr-get pass :mr-review/reviewer) :runId run-id
             :status (or (attr-get pass :mr-review/status) (attr-get run :harness/status))
             :substatus (or (attr-get pass :mr-review/substatus) (attr-get run :harness/substatus))}
      (and detail? (attr-get pass :mr-review/summary))
      (assoc :summary (attr-get pass :mr-review/summary))
      (and detail? (attr-get pass :mr-review/error))
      (assoc :error (attr-get pass :mr-review/error)))))

(defn summary [rt review]
  (let [mr (board/data review :mr-review/mr)]
    {:id (:id review) :title (:title review) :state (:state review)
     :stage (board/stage review)
     :decision (attr-get review :mr-review/decision)
     :current (= "true" (attr-get review :mr-review/current))
     :createdAt (attr-get review :mr-review/created-at)
     :completedAt (attr-get review :mr-review/completed-at)
     :teardownStatus (attr-get review :mr-review/teardown-status)
     :repo (attr-get review :mr-review/repo)
     :mr {:projectId (:project_id mr) :iid (:iid mr) :title (:title mr) :url (:web_url mr)
          :headSha (:sha mr) :baseSha (get-in mr [:diff_refs :base_sha])
          :startSha (get-in mr [:diff_refs :start_sha])
          :sourceBranch (:source_branch mr) :targetBranch (:target_branch mr)}
     :reviewers (mapv #(run-view rt % false) (filter #(= "true" (attr-get % :mr-review/pass)) (board/children rt review)))
     :reportAvailable (boolean (board/report-note rt review))}))

(defn comments-view
  "Read the canonical structured comments and persisted curation snapshot."
  [rt id]
  (let [lock (review-lock rt id)]
    (locking lock
      (let [review (require-review rt id)
            revision (attr-get review :mr-review/comment-revision)]
        (when-not revision
          (throw (ex-info "Review has no structured comment snapshot" {:id id})))
        (let [comment-views (->> (board/comments rt review)
                                 (sort-by (juxt :created_at :id))
                                 (mapv comments/comment-view))
              statuses (frequencies (map #(get-in % [:publication :state]) comment-views))
              publication-state (or (attr-get review :mr-review/publication-state) "unpublished")]
          {:review (assoc (summary rt review)
                          :revision revision
                          :curation {:version (or (attr-get review :mr-review/curation-version) 0)
                                     :mutable (and (= "active" (:state review))
                                                   (= "reviewed" (board/stage review))
                                                   (= "pending" (attr-get review :mr-review/decision))
                                                   (= "true" (attr-get review :mr-review/current))
                                                   (= "unpublished" publication-state))}
                          :publication {:state publication-state
                                        :published (get statuses "published" 0)
                                        :failed (+ (get statuses "failed" 0)
                                                   (get statuses "reconciling" 0))})
           :comments comment-views})))))

(defn list-reviews [rt {:keys [all mr stage]}]
  (when (and mr (not (pos? mr))) (throw (ex-info "MR IID must be positive" {:mr mr})))
  (when (and stage (not (stages stage))) (throw (ex-info "Unknown review stage" {:stage stage :stages stages})))
  (let [reviews (->> (board/cards rt)
                     (filter #(and (or all (= "active" (:state %)))
                                   (or (nil? mr) (= mr (:iid (board/data % :mr-review/mr))))
                                   (or (nil? stage) (= stage (board/stage %)))))
                     (map #(summary rt %))
                     (sort-by (juxt #(if (= "pending" (:decision %)) 0 1)
                                    #(if (:current %) 0 1) #(-> % :mr :iid) :id)) vec)]
    {:reviews reviews :counts {:total (count reviews)
                               :pending (count (filter #(= "pending" (:decision %)) reviews))
                               :running (count (filter #(contains? #{"preparing" "dispatching" "running"} (:stage %)) reviews))
                               :reviewed (count (filter #(= "reviewed" (:stage %)) reviews))
                               :failed (count (filter #(= "failed" (:stage %)) reviews))}}))

(defn show-review [rt id]
  (let [review (require-review rt id)
        mr (board/data review :mr-review/mr)
        links (graph/outgoing-edges rt [id] "review-of")
        snapshot (when (attr-get review :mr-review/comment-revision)
                   (comments-view rt id))]
    {:review (cond-> (assoc (summary rt review)
                            :report (some-> (board/report-note rt review) (attr-get :note/text))
                            :worktree (attr-get review :worktree)
                            :reviewers (mapv #(run-view rt % true)
                                             (filter #(= "true" (attr-get % :mr-review/pass)) (board/children rt review)))
                            :notes (mapv (fn [note] {:id (:id note) :text (:note note) :at (:at note)
                                                     :by (:by note) :kind (attr-get (weaver/show rt (:id note)) :note/kind)})
                                         (remove #(= (:id %) (:id (board/report-note rt review))) (notes/notes rt id {})))
                            :links (mapv (fn [edge] (let [target (weaver/show rt (:to_strand_id edge))]
                                                      {:id (:id target) :title (:title target) :type "review-of"})) links)
                            :history (mapv #(summary rt %)
                                           (filter #(let [other (board/data % :mr-review/mr)]
                                                      (and (not= id (:id %))
                                                           (= (attr-get review :mr-review/repo) (attr-get % :mr-review/repo))
                                                           (= (:project_id mr) (:project_id other)) (= (:iid mr) (:iid other))))
                                                   (board/cards rt))))
               snapshot
               (assoc :comments (:comments snapshot)
                      :revision (attr-get review :mr-review/comment-revision)
                      :curation (get-in snapshot [:review :curation])
                      :publication (get-in snapshot [:review :publication])))}))

(defn- assert-curatable! [review id revision expected-version]
  (let [stored-revision (attr-get review :mr-review/comment-revision)
        stored-version (or (attr-get review :mr-review/curation-version) 0)
        publication-state (or (attr-get review :mr-review/publication-state) "unpublished")]
    (when-not (= "active" (:state review))
      (throw (ex-info "Review is not active" {:id id :state (:state review)})))
    (when-not (and (= "reviewed" (board/stage review))
                   (= "pending" (attr-get review :mr-review/decision))
                   (= "true" (attr-get review :mr-review/current)))
      (throw (ex-info "Review is not a current reviewed revision awaiting a decision"
                      {:id id :stage (board/stage review)})))
    (when-not (= "unpublished" publication-state)
      (throw (ex-info "Curation is frozen because publication has begun"
                      {:id id :publication publication-state})))
    (when-not (= stored-revision revision)
      (throw (ex-info "Review revision is stale"
                      {:id id :expected stored-revision :got revision})))
    (when-not (= stored-version expected-version)
      (throw (ex-info "Curation version is stale"
                      {:id id :expected stored-version :got expected-version})))
    stored-version))

(defn- validate-curation-changes! [rt review changes]
  (let [stored (into {} (map (juxt :id identity)) (board/comments rt review))]
    (doseq [{:keys [id candidate]} changes]
      (when-not (stored id)
        (throw (ex-info "Curation references an unknown comment" {:id id})))
      (when (and candidate
                 (not= (:expectedVersion candidate)
                       (or (attr-get (stored id) :mr-review/candidate-version) 1)))
        (throw (ex-info "Candidate version is stale"
                        {:id id
                         :expected (or (attr-get (stored id) :mr-review/candidate-version) 1)
                         :got (:expectedVersion candidate)}))))
    stored))

(defn- curation-changed? [stored changes]
  (some (fn [{:keys [id inclusion candidate]}]
          (let [review-comment (stored id)]
            (or (and inclusion
                     (not= inclusion (attr-get review-comment :mr-review/inclusion)))
                (and candidate
                     (not= (:text candidate)
                           (attr-get review-comment :mr-review/candidate-text))))))
        changes))

(defn- curation-patches [stored changes by at]
  (mapv
   (fn [index {:keys [id inclusion candidate]}]
     (let [review-comment (stored id)]
       {:ref (keyword (str "comment" index))
        :id id
        :attributes
        (cond-> {}
          inclusion (assoc :mr-review/inclusion inclusion)
          (and candidate
               (not= (:text candidate) (attr-get review-comment :mr-review/candidate-text)))
          (assoc :mr-review/candidate-text (:text candidate)
                 :mr-review/candidate-version
                 (inc (or (attr-get review-comment :mr-review/candidate-version) 1))
                 :mr-review/candidate-source
                 (pr-str {:kind "user-adopted" :by by :at at})))}))
   (range) changes))

(defn- persist-curation! [rt review version patches]
  (batch/apply!
   rt
   {:refs (into {:review (:id review)} (map (juxt :ref :id)) patches)
    :strands (into [{:ref :review
                     :attributes {:mr-review/curation-version (inc version)}}]
                   (map #(select-keys % [:ref :attributes]) patches))}))

(defn curate!
  "Apply one atomic compare-and-set curation mutation and return its snapshot."
  [rt id raw-request]
  (let [{:keys [revision expectedVersion by changes]}
        (comments/normalize-curation-request raw-request)
        lock (review-lock rt id)]
    (locking lock
      (let [review (require-review rt id)
            version (assert-curatable! review id revision expectedVersion)
            stored (validate-curation-changes! rt review changes)]
        (when-not (curation-changed? stored changes)
          (throw (ex-info "Curation request makes no changes"
                          {:id id :comments (mapv :id changes)})))
        (persist-curation! rt review version
                           (curation-patches stored changes by (str (runtime/now rt))))
        (comments-view rt id)))))

(defn decide! [rt id outcome by text before-close]
  (let [review (require-review rt id)
        decision (attr-get review :mr-review/decision)]
    (when-not (contains? #{"done" "dismissed"} outcome)
      (throw (ex-info "Local outcome must be done or dismissed" {:outcome outcome})))
    (when-not (contains? #{"reviewed" "failed"} (board/stage review))
      (throw (ex-info "Review is still executing; inspect review show before deciding" {:id id})))
    (when (and decision (not= "pending" decision) (not= outcome decision))
      (throw (ex-info "Review already has a different decision" {:id id :decision decision})))
    (when (= "active" (:state review))
      (before-close review)
      (let [at (str (runtime/now rt))]
        (batch/apply! rt
                      {:refs {:review id}
                       :strands [{:ref :review :state "closed"
                                  :attributes {:mr-review/decision outcome :mr-review/decided-at at}}
                                 {:ref :decision :title (str "Local review decision: " outcome) :state "closed"
                                  :attributes (cond-> {:note/text (or text (str "Marked " outcome " locally. GitLab was not changed."))
                                                       :note/at at :note/kind "decision"} by (assoc :note/by by))}]
                       :edges [{:op :upsert :from :decision :to :review :type "notes"}]})))
    (show-review rt id)))

(defn link! [rt id target-id]
  (require-review rt id)
  (when-not (weaver/show rt target-id) (throw (ex-info "Related work does not exist" {:id target-id})))
  (batch/apply! rt {:refs {:review id :work target-id}
                    :edges [{:op :upsert :from :review :to :work :type "review-of"}]})
  (show-review rt id))
