(ns consumer.review
  "Copy into the consumer's module tree; fill in the four site-specific values.
   Start disabled. The consumer must already activate Workflow, Kanban, Harnesses
   seats/core/assignment, and code/agent executors. This is the sole Auto-run
   configuration resource, not a second dispatcher alongside an existing one."
  (:require [millhouse.spools.auto-review :as review]
            [millhouse.spools.auto-review.workflow :as review-workflow]
            [millhouse.spools.auto-review.workspace :as workspace]
            [millhouse.spools.auto-run :as auto-run]
            [millhouse.spools.auto-run-reporting :as reporting]
            [millhouse.spools.cron :as cron]
            [millhouse.spools.workflow :as workflow]
            [millstrand.api.lifecycle.alpha :as lifecycle]
            [millstrand.api.millstrand.alpha :as millstrand]))

;; Site-specific values. A new dependency generation needs separate activation
;; authorization; neither copying this module nor changing pins restarts Weaver.
(def repo "/absolute/canonical/repo")
(def provider {:host "git.example.com" :project 123 :labels []})
(def driver "review-driver")
(def reviewer "reviewer")
(def enabled? false)

(defn poll! [rt]
  (review/poll! rt {:repo repo
                   :poll 'millhouse.spools.auto-review.glab/poll
                   :provider-config provider
                   :max-open 2 :workflow "review-request"
                   :seat driver :effort "low"}))

(defn prepare! [rt request]
  ;; Add repository-specific dependency preparation here if required, then
  ;; workspace/inspect! again before returning. Do not claim the card here.
  (workspace/prepare! rt request))

(defn start-params [rt request]
  (assoc (review/start-params rt request) :reviewer reviewer))

(workflow/use-workflow! review-workflow/review-request)
(millstrand/use-op! auto-run/auto-run)
(millstrand/use-hook! reporting/derive-labels)
(millstrand/use-pattern! reporting/auto-run-needs-decision
                         reporting/auto-run-unknown-failure
                         reporting/auto-run-unblock)

(cron/defjob review-poll
  "Read remote requests into the ordinary board. No reviewer dispatch here."
  {:interval-ms 300000 :handler 'consumer.review/poll!})

;; Cron owns the only polling schedule and its failure reporting. Omitting the
;; selected job disarms recurrence; it does not cancel any admitted card.
(when enabled? (cron/use-job! review-poll))

(defn open! [{:keys [runtime]}]
  (auto-run/configure! runtime
                       {:repo repo :seat driver :effort "low"
                        :workflow "review-request" :workflows #{"review-request"}
                        :prepare 'consumer.review/prepare!
                        :start-params 'consumer.review/start-params
                        :enabled? enabled? :max-running 2 :interval-ms 15000}))

(defn close! [{:keys [runtime]}]
  (auto-run/stop! runtime))

(lifecycle/defresource! review-admission
  "Own repository Auto-run admission; stop never kills existing agents."
  {:open 'consumer.review/open! :close 'consumer.review/close!})
