(ns millhouse.land.spool
  "Activation namespace for reusable review, landing, and merge-queue behavior."
  (:require [millhouse.land :as land]
            [millhouse.land.merge-queue :as queue]
            [millhouse.land.queries :as queries]
            [millhouse.workflow :as workflow]
            [millstrand.api.lifecycle.alpha :as lifecycle]
            [millstrand.api.millstrand.alpha :as millstrand]))

(workflow/use-workflow! land/review land/land land/land-merge land/land-abort)
(workflow/use-executor! queue/merge-turn-stalled? queue/merge-release-stalled?)
(millstrand/use-op! queue/merge-queue)
(millstrand/use-query! queries/merge-lock queries/merge-queue)
(lifecycle/use-resource! queue/queue-completion-guard queue/queue-handler)
