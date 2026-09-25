(ns millhouse.land.queries
  "Named projections over durable landing queue state."
  (:require [millstrand.api.millstrand.alpha :as millstrand]))

(millstrand/defquery merge-lock
  "Return the active singleton landing lock."
  {:usage "strand list --query merge-lock"}
  [:and
   [:= :state "active"]
   [:= [:attr "kind"] "merge-lock"]])

(millstrand/defquery merge-queue
  "Return active FIFO landing reservations, including the lock holder."
  {:usage "strand list --query merge-queue"}
  [:and
   [:= :state "active"]
   [:= [:attr "kind"] "merge-queue-entry"]])
