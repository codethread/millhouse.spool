# Cron cookbook

Compositions for recurring work that needs more than Cron's basic job API: isolating side-effecting startup modules and coordinating duplicate-tolerant work across many weavers.

## Keep a side-effecting job out of broadly loaded config

**Situation.** A job performs real network, filesystem, or process side effects. The repository's broad startup config also loads in tests, where publishing that job would be unsafe.

**Composition.** Put the handler and `defjob` declaration in a dedicated startup-file module. Activate Cron first, then order the job module after it.

```clojure
;; report_job.clj
(ns report-job
  (:require [millhouse.spools.cron :as cron]))

(defn report-tick [runtime]
  ;; Perform one duplicate-tolerant unit of periodic work.
  {:outcome :reported})

(cron/defjob! nightly-report
  "Run the nightly report."
  {:interval-ms 86400000
   :jitter-ms 3600000
   :handler 'report-job/report-tick})

;; init.clj
(runtime/module! runtime :millhouse/cron
  {:ns 'millhouse.spools.cron
   :spools ['millhouse.spools/cron]
   :required? true})

(runtime/module! runtime :report-job
  {:file "report_job.clj"
   :after [:millhouse/cron]
   :required? true})
```

**Why this shape.**

- Broad config tests can load shared helpers without publishing the job or running Cron reconciliation for it.
- Behavior and cadence remain together in one owned module.
- Owner-complete publication makes removal expressible: unchanged declarations preserve their wakes, changed declarations replace them, and omission cancels them.
- The explicit `:after` edge makes Cron's registry-kind ownership a prerequisite rather than relying on source load order.

An example of this split is Millstrand's [NVD scan job](https://github.com/codethread/millstrand/blob/3bbe5dc15359975a8e8203ef47b3a7514177e75b/.millstrand/jobs/nvd_scan.clj), activated separately from its [main startup config](https://github.com/codethread/millstrand/blob/3bbe5dc15359975a8e8203ef47b3a7514177e75b/.millstrand/init.clj).

## Coordinate many weavers with a best-effort lock and durable card

**Situation.** Every maintainer's weaver publishes the same expensive job. Common duplicate runs should be avoided, but Cron's at-least-once contract means the work must still tolerate a race. A finding needs to become durable user-visible work.

**Composition.** Add jitter to spread normal starts, use shared external state as a best-effort lock, and create the Kanban card before lower-value follow-up side effects. Keep the job body injectable so its lock, command, and card behavior can be tested without real services.

```clojure
(defn run-scan!
  [{:keys [run-cmd raise-card!]}]
  (cond
    (open-lock-held? run-cmd)
    {:outcome :skipped-locked}

    :else
    (let [lock (acquire-lock! run-cmd)]
      (try
        (let [findings (do-the-work run-cmd)]
          (when (seq findings)
            (raise-card! {:title "Scan: findings"
                          :body (report findings)}))
          {:outcome :scanned :findings findings})
        (finally
          (release-lock! run-cmd lock))))))

(defn scan-tick [runtime]
  (run-scan!
   {:run-cmd run-command
    :raise-card!
    (fn [{:keys [title body]}]
      ((requiring-resolve 'millhouse.spools.kanban/add!)
       runtime title {"--body" body "--priority" "p1"}))}))

(cron/defjob! shared-scan
  "Run the shared scan."
  {:interval-ms 518400000
   :jitter-ms 3600000
   :handler 'my.scan/scan-tick})
```

**Why this shape.**

- Jitter lowers contention but makes no exclusion promise.
- The external lock is explicitly best-effort; a race or duplicate wake remains harmless because the scan itself tolerates duplicates.
- Releasing in `finally` prevents a failed scan from suppressing future cadence.
- Creating the card first makes it the alert of record. A later notification or cleanup failure cannot erase the finding.
- Injected side effects let tests exercise locking and finding behavior independently of Cron's scheduler tests.

Millstrand's [NVD scan job](https://github.com/codethread/millstrand/blob/3bbe5dc15359975a8e8203ef47b3a7514177e75b/.millstrand/jobs/nvd_scan.clj) is the reference composition for this pattern.
