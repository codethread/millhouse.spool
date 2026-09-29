(ns millhouse.land.internal.queue-authority
  "Private Land transaction scopes for exact queue writes and root freeze fencing."
  (:require [clojure.string :as str]
            [millstrand.api.current.alpha :as current]
            [millstrand.api.spool.alpha :refer [attr-get fail!]]))

(def ^:dynamic *before-images* nil)
(def ^:dynamic *writes* nil)
(def ^:dynamic *creates* nil)
(def ^:dynamic *gate-ids* nil)

(defn- domain-row? [row]
  (contains? #{"merge-queue-entry" "merge-lock"} (attr-get row :kind)))

(defn- field-name [key]
  (if (keyword? key) (subs (str key) 1) key))

(defn- protected-field? [key]
  (let [key (field-name key)]
    (or (contains? #{"kind" "land/run-id"} key) (str/starts-with? key "queue/"))))

(defn before-commit
  "Check exact queue domain writes and all participating before-images."
  [ctx]
  (let [updates (or (:batch/updated ctx)
                    (when (:strand/before ctx)
                      [{:id (:strand/id ctx) :before (:strand/before ctx) :after (:strand/after ctx)}]))
        images (into {} (map (juxt :id :before)) updates)]
    (doseq [[id expected] *before-images*]
      (when-not (= expected (get images id))
        (fail! "Queue transaction before-image changed or was omitted" {:id id})))
    (doseq [row (or (:batch/created ctx)
                    (when (and (nil? (:strand/before ctx)) (:strand/after ctx)) [(:strand/after ctx)]))
            :when (domain-row? row)]
      (let [expected (some (fn [[ref patch]]
                             (when (= (:id row) (get (:batch/refs ctx) ref)) patch)) *creates*)]
        (when-not (and expected (= (:title expected) (:title row))
                       (= (count (:attributes expected)) (count (:attributes row)))
                       (every? (fn [[key value]] (= value (attr-get row key))) (:attributes expected)))
          (fail! "Queue rows may only be created by their domain operation" {:id (:id row)}))))
    (doseq [{:keys [id before after]} updates]
      (when (or (domain-row? before) (domain-row? after))
        (let [patch (get *writes* id)
              attrs (into {} (map (fn [[key value]] [(field-name key) value])) (:attributes patch))]
          (when-not (= (:state after) (get patch :state (:state before)))
            (fail! "Queue state changes require exact domain authority" {:id id}))
          (doseq [key (filter protected-field? (into (set (keys (:attributes before))) (keys (:attributes after))))]
            (when-not (= (attr-get after key) (get attrs (field-name key) (attr-get before key)))
              (fail! "Queue ownership changes require exact domain authority" {:id id :key key})))))
      (when (and (= "active" (:state before)) (= "closed" (:state after))
                 (contains? #{"merge-turn" "merge-release"} (attr-get before :workflow/gate))
                 (not (contains? *gate-ids* [(current/runtime) id])))
        (throw (ex-info "Land queue gates are completed only by their queue executor"
                        {:code "land/queue-gate-completion-forbidden" :gate-id id
                         :gate (attr-get before :workflow/gate)})))))
  nil)
