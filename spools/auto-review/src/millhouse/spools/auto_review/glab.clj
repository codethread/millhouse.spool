(ns millhouse.spools.auto-review.glab
  "GitLab's read-only adapter for the provider-neutral Auto-review contract.

  All GitLab fields end here. Only explicit GETs are issued; no comments,
  approvals, merges, worktrees or agent requests can be published by this adapter."
  (:require [clojure.data.json :as json]
            [clojure.spec.alpha :as s]
            [clojure.string :as str]
            [millhouse.spools.auto-review :as review]
            [millhouse.spools.auto-review.internal.process :as process]
            [millstrand.api.spool.alpha :refer [fail! require-valid!]]))

(s/def ::host (s/and string? #(boolean (re-matches #"[A-Za-z0-9.-]+(?::[0-9]+)?" %))))
(s/def ::project pos-int?)
(s/def ::bin (s/and string? (complement str/blank?)))
(s/def ::labels (s/coll-of ::review/text :kind vector?))
(s/def ::config
  (s/and (s/keys :req-un [::host ::project ::bin ::labels])
         #(every? #{:host :project :bin :labels} (keys %))))

(defn- get! [repo config endpoint]
  (json/read-str
   (process/command! repo [(:bin config) "api" "--hostname" (:host config)
                           "--method" "GET" endpoint] (* 8 1024 1024) 60)
   :key-fn keyword))

(defn- listing [repo config endpoint]
  (loop [page 1 rows []]
    (let [result (get! repo config (str endpoint "?state=opened&per_page=100&page=" page))]
      (when-not (vector? result)
        (fail! "GitLab returned a non-array request page" {:page page}))
      (if (= 100 (count result))
        (recur (inc page) (into rows result))
        (into rows result)))))

(defn- normalized [config user-id listed detail]
  (when-not (and (map? detail) (= (:project config) (:project_id detail))
                 (= (:iid listed) (:iid detail))
                 (contains? #{"opened" "closed" "merged"} (:state detail)))
    (fail! "GitLab returned a different or invalid request" {:iid (:iid listed)}))
  ;; A moved/closed/draft/label-changed revision is not a polling failure and
  ;; cannot be admitted from stale list evidence. The next poll observes it anew.
  (when (and (= "opened" (:state detail)) (not (:draft detail))
             (not (:work_in_progress detail))
             (= (:sha listed) (:sha detail) (get-in detail [:diff_refs :head_sha]))
             (every? (set (:labels detail)) (:labels config)))
    (let [pipeline (:head_pipeline detail)
          pipeline-head (:sha pipeline)
          status (cond
                   (and (pos-int? (:id pipeline)) (= "success" (:status pipeline))) "passed"
                   (contains? #{"failed" "canceled"} (:status pipeline)) "failed"
                   (contains? #{"created" "waiting_for_resource" "preparing" "pending"
                                "running" "manual" "scheduled"} (:status pipeline)) "pending"
                   :else "unknown")]
      (require-valid!
       ::review/revision
       {:provider "gitlab"
        :repository (str "https://" (:host config) "/projects/" (:project config))
        :request (str (:iid detail)) :url (:web_url detail) :title (:title detail)
        :head (:sha detail) :base (get-in detail [:diff_refs :base_sha])
        :requested? (boolean (some #(= user-id (:id %)) (:reviewers detail)))
        :ci (cond-> {:status status}
              pipeline-head (assoc :head pipeline-head)
              (:web_url pipeline) (assoc :url (:web_url pipeline)))}
       "GitLab returned invalid revision evidence"))))

(defn poll
  "Auto-review :poll callback, returning normalized open eligible revisions.

  Receives runtime (unused) and {:repo local-checkout :config {...}}. Config
  requires :host (explicit GitLab hostname) and :project (numeric target project
  ID); :bin defaults to glab and :labels to []. Authentication is glab-owned.
  Listing is paginated, requested-review identity uses the authenticated user ID,
  and detail is rechecked against the listed head before normalization. Only the
  current head_pipeline aggregate is used, never job success or pipeline history.
  Core requires its passed evidence to name the exact admitted head."
  [_rt {:keys [repo config]}]
  (let [config (require-valid! ::config (merge {:bin "glab" :labels []} config)
                               "Invalid glab provider configuration")
        user-id (:id (get! repo config "user"))
        endpoint (str "projects/" (:project config) "/merge_requests")]
    (when-not (pos-int? user-id)
      (fail! "GitLab returned an invalid authenticated user" {}))
    (into []
          (keep (fn [listed]
                  (when-not (and (pos-int? (:iid listed)) (s/valid? ::review/head (:sha listed)))
                    (fail! "GitLab returned an invalid request listing" {}))
                  (normalized config user-id listed
                              (get! repo config (str endpoint "/" (:iid listed))))))
          (listing repo config endpoint))))
