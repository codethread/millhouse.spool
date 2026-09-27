(ns millhouse.e2e.cron.lifecycle-test
  "Embedded Cron lifecycle proofs: file-backed durable reopen and event-lane
  isolation. Two runtimes reopen one retained SQLite store; this is not
  replacement-process adoption or startup-config loading evidence.

  Re-registering the same job in the fresh runtime preserves its pending wake.
  A wall-future seed keeps the startup timer dormant until the manual clock
  releases that wake. The job then fires and re-arms its cadence.

  A separate blocking handler proves Cron offloads work without holding the
  shared event lane. Manual clocks release wakes; Cron quiescence joins the
  offloaded work. Handler signals are reset per test."
  (:require [clojure.test :refer [deftest is]]
            [millstrand.api.events.alpha :as events]
            [millstrand.api.scheduler.alpha :as scheduler]
            [millstrand.api.weaver.alpha :as weaver]
            [millhouse.cron :as cron]
            [millhouse.test-support :as test-support]
            [millstrand.test.alpha :as test-alpha])
  (:import [java.time Duration Instant]))

;; Job and event seams resolved by fully qualified symbol, so their signals are
;; namespace-level and reset per test.
(def ^:private run-started (atom (promise)))
(def ^:private run-release (atom (promise)))
(def ^:private marker-fired (atom (promise)))

(defn record-run
  "Durable-reopen job body: return a sentinel result the test reads back off
  the fired job's `:last-result`."
  [_runtime]
  :fired)

(defn blocking-run
  "Lane-hygiene job body: signal that it started on the cron executor, then block
  until released, holding a run in flight while the event lane must stay free."
  [_runtime]
  (deliver @run-started true)
  @@run-release)

(defn marker-handler
  "Event handler proving the lane still dispatches new work while a cron job is
  blocked off-lane."
  [_event]
  (deliver @marker-fired true))

(defn- cron-wake
  "The pending scheduler wake owning `key`, or nil."
  [rt key]
  (first (filter #(= key (:key %)) (scheduler/pending rt))))

(deftest cron-cadence-survives-durable-reopen-and-fires-on-rearm
  (let [root (test-support/temp-dir "millhouse-cron-restart")
        interval-ms (* 60 60 1000)
        job {:id :survivor :interval-ms interval-ms :jitter-ms 0
             :handler 'millhouse.e2e.cron.lifecycle-test/record-run}
        ;; A wall-future seed instant keeps the fresh weaver's startup timer
        ;; dormant, so the adopted wake fires only when the manual clock is
        ;; advanced past it (below), never on the wall clock mid-`register!`.
        seed-at (Instant/ofEpochSecond 4102444800)
        seed-ms (.toEpochMilli seed-at)]
    (try
      ;; First weaver registers a wake exactly at seed-at. The explicit world
      ;; root retains its SQLite store after the disposable runtime stops.
      (test-alpha/run-with-weaver-world
       {:root root}
       (fn [{rt1 :runtime}]
         (test-alpha/set-clock!
          rt1 (test-alpha/manual-clock (.minusMillis seed-at interval-ms)))
         (cron/register! rt1 job)
         (is (= seed-ms (:wake_at (cron-wake rt1 "cron/survivor")))
             "the cron wake is durably pending in the first weaver")))
      ;; A fresh embedded runtime reopens the durable wake:
      ;; re-running the identical register! with no in-memory config preserves
      ;; the pending wake (.A4) rather than resetting its countdown.
      (test-alpha/run-with-weaver-world
       {:root root}
       (fn [{rt2 :runtime}]
         (test-alpha/set-clock! rt2 (test-alpha/manual-clock (.plusSeconds seed-at 1)))
         (cron/register! rt2 job)
         (is (= seed-ms (:wake_at (cron-wake rt2 "cron/survivor")))
             "the equal config tuple adopts the overdue wake instead of resetting it")
          ;; Release the overdue fire deterministically off the manual clock.
         (let [fired-at-ms (.toEpochMilli (test-alpha/advance! rt2 (Duration/ofSeconds 2)))]
           (test-alpha/await-quiescent! rt2 {:timeout-ms (test-support/await-budget-ms)})
           (cron/await-quiescent! rt2 {:timeout-ms (test-support/await-budget-ms)})
           (is (= :fired (:last-result (first (cron/jobs rt2))))
               "the adopted wake fired and recorded its result after durable reopen")
           (is (= (+ fired-at-ms interval-ms) (:wake_at (cron-wake rt2 "cron/survivor")))
               "the next cron wake is re-armed at the fire instant + interval"))))
      (finally
        (test-support/delete-tree! root)))))

(deftest blocking-run-does-not-hold-the-event-lane
  (test-support/with-runtime
    (fn [rt _db-file]
      (reset! run-started (promise))
      (reset! run-release (promise))
      (reset! marker-fired (promise))
      (test-alpha/set-clock! rt (test-alpha/manual-clock (Instant/ofEpochSecond 0)))
      (events/register-handler! rt :marker #{:strand/added}
                                'millhouse.e2e.cron.lifecycle-test/marker-handler {})
      (cron/register! rt {:id :blocker :interval-ms 1000 :jitter-ms 0
                          :handler 'millhouse.e2e.cron.lifecycle-test/blocking-run})
      ;; The fire runs fire-wake on the lane; it arms the next wake and offloads
      ;; blocking-run to the cron executor, then returns, so the lane settles
      ;; while the job body is still blocked.
      (test-alpha/advance! rt (Duration/ofSeconds 2))
      (is (= rt (test-alpha/await-quiescent! rt {:timeout-ms (test-support/await-budget-ms)}))
          "the lane settles even though the job body is still blocked off-lane")
      (is (deref @run-started (test-support/await-budget-ms) false)
          "the offloaded job body started on the cron executor")
      (is (not (realized? @run-release))
          "the job body is still blocked mid-run")
      ;; A subsequent event still dispatches while the cron job blocks off-lane.
      (weaver/add! rt {:title "lane marker"})
      (test-alpha/await-quiescent! rt {:timeout-ms (test-support/await-budget-ms)})
      (is (deref @marker-fired (test-support/await-budget-ms) false)
          "a new event dispatches on the lane while the cron job is blocked")
      ;; Release and join before teardown so the executor thread is idle.
      (deliver @run-release true)
      (cron/await-quiescent! rt {:timeout-ms (test-support/await-budget-ms)}))))
