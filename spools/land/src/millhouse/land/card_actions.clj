(ns millhouse.land.card-actions
  "Short, repeatable kanban card updates used by landing workflows."
  (:require [millhouse.kanban :as kanban]
            [millstrand.api.current.alpha :as current]
            [millstrand.api.spool.alpha :refer [attr-get fail!]]
            [millstrand.api.weaver.alpha :as weaver]))

(defn- card-view [runtime id]
  (let [card (weaver/show runtime id)]
    (when-not (= "true" (attr-get card :kanban/card))
      (fail! "Expected a kanban card" {:card id}))
    card))

(defn review!
  "Mark an optional card as needing human attention; in_review is unchanged."
  [runtime {:keys [card]}]
  (when card
    (let [view (card-view runtime card)]
      (case (attr-get view :kanban/lane)
        "in_review" nil
        "claimed" (do
                    (when-not (= "active" (:state view))
                      (fail! "Card must be active to review" {:card card}))
                    (weaver/update! runtime card {:attributes {:kanban/lane "in_review"}}))
        (fail! "Card must be claimed or in review" {:card card}))))
  nil)

(defn rework!
  "Resume agent work on an optional card in claimed; repeat calls are harmless."
  [runtime {:keys [card]}]
  (when card
    (let [view (card-view runtime card)]
      (case (attr-get view :kanban/lane)
        "claimed" nil
        "in_review" (do
                      (when-not (= "active" (:state view))
                        (fail! "Card must be active to rework" {:card card}))
                      (weaver/update! runtime card {:attributes {:kanban/lane "claimed"}}))
        (fail! "Aborted landing card must be claimed or in review" {:card card}))))
  nil)

(defn finish!
  "Finish an optional card after housekeeping, accepting an existing done result."
  [runtime {:keys [card]}]
  (when card
    (let [view (card-view runtime card)]
      (if (= "closed" (:state view))
        (when-not (= "done" (attr-get view :kanban/outcome))
          (fail! "Landing card closed with a different outcome" {:card card}))
        (kanban/finish! runtime card {"--outcome" "done"}))))
  nil)

;; The Workflow code executor invokes qualified one-argument callbacks while
;; binding the originating runtime. These named adapters must remain public so
;; generation-scoped resolution is an explicit, inspectable contract; the
;; explicit-runtime functions above remain the reusable Clojure surface.
(defn review-card!
  "Workflow callback for `review!` in the code executor's bound runtime."
  [params]
  (review! (current/runtime) params))

(defn rework-card!
  "Workflow callback for `rework!` in the code executor's bound runtime."
  [params]
  (rework! (current/runtime) params))

(defn finish-card!
  "Workflow callback for `finish!` in the code executor's bound runtime."
  [params]
  (finish! (current/runtime) params))
