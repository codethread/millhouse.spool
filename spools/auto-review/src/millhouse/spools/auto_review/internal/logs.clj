(ns millhouse.spools.auto-review.internal.logs
  "Own persisted operational activity for the review coordinator.

  Events are bounded, sanitized JSON records linked beneath one repository log
  root. Logging is non-fatal to review execution; health captures write errors,
  query functions produce chronological JSONL, and pruning bounds the live
  graph according to the configured retention period."
  (:require [clojure.data.json :as json]
            [millstrand.api.runtime.alpha :as runtime]
            [millstrand.api.batch.alpha :as batch]
            [millstrand.api.graph.alpha :as graph]
            [millstrand.api.spool.alpha :refer [attr-get]]
            [millstrand.api.weaver.alpha :as weaver]))

(def capacity 100)
(def ^:dynamic *context* {})

(defn- settings [rt]
  (:config (runtime/spool-state rt ::settings {:version 1}
                                #(hash-map :config (atom nil)))))

(defn configure! [rt config] (reset! (settings rt) config))

(defn- roots [rt repo]
  (weaver/list rt [:and [:= [:attr "mr-review/log-root"] "true"]
                   [:= [:attr "mr-review/repo"] repo]] {}))

(defn- root! [rt repo]
  (or (:id (first (roots rt repo)))
      (:id (weaver/add! rt {:title "MR review activity"
                            :state "closed"
                            :attributes {:mr-review/log-root "true" :mr-review/repo repo}}))))

(defn- persist! [rt event facts]
  (let [repo (:repo-dir @(settings rt))
        _ (when-not repo (throw (ex-info "Review logging is not configured" {})))
        root (root! rt repo)
        entry (merge {:at (str (runtime/now rt)) :event event} *context* facts)]
    (batch/apply! rt
                  {:refs {:root root}
                   :strands [{:ref :event :title (str "MR review: " event) :state "closed"
                              :attributes (cond-> {:mr-review/log-event "true"
                                                   :mr-review/repo repo
                                                   :mr-review/log-at (:at entry)
                                                   :mr-review/log-json (json/write-str entry)}
                                            (:mr entry) (assoc :mr-review/log-mr (str (:mr entry))))}]
                   :edges [{:op :upsert :from :root :to :event :type "review-log"}]})))

(declare error-facts)

(defn- health [rt]
  (:error (runtime/spool-state rt ::health {:version 1} #(hash-map :error (atom nil)))))

(defn append!
  "Logging failure must never interrupt review execution or wedge its worker."
  [rt event facts]
  (try (persist! rt event facts)
       (catch Exception error
         (try (reset! (health rt) (assoc (error-facts error) :at (str (runtime/now rt))))
              (catch Exception _ nil))
         nil)))

(defn- event-strands [rt mr]
  (let [repo (:repo-dir @(settings rt))]
    (if-not repo []
            (weaver/list rt
                         (cond-> [:and [:= [:attr "mr-review/log-event"] "true"]
                                  [:= [:attr "mr-review/repo"] repo]
                                  [:edge/in "review-log" [:and [:= [:attr "mr-review/log-root"] "true"]
                                                          [:= [:attr "mr-review/repo"] repo]]]]
                           mr (conj [:= [:attr "mr-review/log-mr"] (str mr)])) {}))))

(defn prune! [rt]
  (let [days (:log-retention-days @(settings rt))
        cutoff (.minusSeconds (runtime/now rt) (* 86400 days))
        expired (filter #(try (.isBefore (java.time.Instant/parse (attr-get % :mr-review/log-at)) cutoff)
                              (catch Exception _ false))
                        (event-strands rt nil))
        ids (mapv :id expired)]
    (doseq [chunk (partition-all 100 ids)] (graph/burn-by-ids! rt (vec chunk)))
    (append! rt "logs-pruned" {:burned (count ids) :retention-days days})
    {:burned (count ids)}))

(defn status [rt]
  {:command "strand review-logs [--mr IID] [--limit 1..100]"
   :roots (mapv :id (if-let [repo (:repo-dir @(settings rt))] (roots rt repo) []))
   :retention-days (:log-retention-days @(settings rt))
   :storage "strands" :edge "review-log" :last-error @(health rt)})

(defn mr-facts [mr]
  (cond-> {}
    (pos-int? (:iid mr)) (assoc :mr (:iid mr))
    (pos-int? (:project_id mr)) (assoc :project (:project_id mr))
    (and (string? (:sha mr)) (re-matches #"[0-9a-f]{40,64}" (:sha mr)))
    (assoc :sha (:sha mr))))

(defn error-facts [error]
  ;; Exception data can contain stderr, commands, tokens or MR content. Only
  ;; known local messages are safe; all other failures get a fixed category.
  {:reason (get {"Command timed out" "command-timeout"
                 "Command failed" "command-failed"
                 "Command output exceeded its byte limit" "command-output-limit"
                 "Expected a GitLab MR array" "invalid-mr-list"
                 "MR moved, became a draft, or lacks prepared diff refs" "revision-changed-or-unprepared"
                 "Configured reviewer is unknown or unavailable" "reviewer-unavailable"}
                (ex-message error) "operation-failed")})

(defn entries [rt {:keys [mr limit] :or {limit capacity}}]
  (when-not (and (integer? limit) (<= 1 limit capacity)
                 (or (nil? mr) (pos-int? mr)))
    (throw (ex-info "Logs require --limit 1..100 and a positive --mr IID" {})))
  (->> (event-strands rt mr)
       (sort-by #(vector (java.time.Instant/parse (attr-get % :mr-review/log-at)) (:id %)))
       (take-last limit)
       (mapv #(assoc (json/read-str (attr-get % :mr-review/log-json) :key-fn keyword) :id (:id %)))))
