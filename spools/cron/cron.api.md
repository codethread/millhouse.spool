
-----
# <a name="millhouse.spools.cron">millhouse.spools.cron</a>


Fixed-interval recurrence over Millstrand's durable scheduler wakes.

  Module authors normally declare jobs with `defjob`; trusted code holding a
  runtime may use `register!` and `unregister!` directly. Handler returns and
  errors appear in `jobs`; throws are also recorded by `recent-failures` and do
  not stop cadence. The scheduler remains the sole next-fire authority.
  Delivery is at-least-once, so handlers must tolerate duplicate fires.

  This reference also lists `desired-jobs`, `actual-jobs`, `apply-jobs!`, and
  `remove-jobs!`. They are public because the `scheduled-jobs` lifecycle
  declaration resolves them by symbol; module authors do not call them.




## <a name="millhouse.spools.cron/actual-jobs">`actual-jobs`</a>
``` clojure
(actual-jobs {:keys [runtime], :as context})
```
Function.

Lifecycle read hook: return Cron's managed `id -> job-status` map.

  `scheduled-jobs` calls this with `{:runtime runtime}`; consumers wanting the
  sorted status projection use `jobs`.
<p><sub><a href="https://github.com/codethread/millhouse.spool/blob/main/spools/cron/src/millhouse/spools/cron.clj#L412-L419">Source</a></sub></p>

## <a name="millhouse.spools.cron/apply-jobs!">`apply-jobs!`</a>
``` clojure
(apply-jobs! {:keys [runtime desired actual], :as context})
```
Function.

Lifecycle apply hook: converge managed jobs and wakes onto `:desired`.

  Accepts `{:runtime runtime :desired id->job :actual id->job-status}` from
  `scheduled-jobs`. It removes omitted jobs, applies changed jobs, restores
  missing wakes, and returns `{:reconciled :cron :jobs [sorted-ids...]}`. A
  failed change names its job, operation, declaration, wake key, and remedy.
<p><sub><a href="https://github.com/codethread/millhouse.spool/blob/main/spools/cron/src/millhouse/spools/cron.clj#L437-L457">Source</a></sub></p>

## <a name="millhouse.spools.cron/await-quiescent!">`await-quiescent!`</a>
``` clojure
(await-quiescent! runtime)
(await-quiescent! runtime {:keys [timeout-ms], :as opts})
```
Function.

Block until every offloaded cron job on `runtime` has finished, then return
  `runtime`.

  Because job bodies run off the event lane,
  `millstrand.test.alpha/await-quiescent!` returns before a Cron job necessarily
  completes. Deterministic tests join both surfaces in order:

  ```clojure
  (require '[millhouse.spools.cron :as cron]
           '[millstrand.test.alpha :as test-alpha])

  (test-alpha/advance! runtime (java.time.Duration/ofMinutes 10))
  (test-alpha/await-quiescent! runtime)
  (cron/await-quiescent! runtime)
  ```

  The in-flight latch is incremented on the event lane in `fire-wake` before
  submission, so once the lane has quiesced every submitted body is counted.
  Polling and timeout use the runtime Clock. `opts` accepts only positive-integer
  `:timeout-ms`, defaulting to 10000; timeout fails loudly with the remaining
  in-flight count.
<p><sub><a href="https://github.com/codethread/millhouse.spool/blob/main/spools/cron/src/millhouse/spools/cron.clj#L247-L283">Source</a></sub></p>

## <a name="millhouse.spools.cron/defjob">`defjob`</a>
``` clojure
(defjob id job)
(defjob id options job)
```
Macro.

Collect one Cron job declaration for the current runtime module.

  ```clojure
  (require '[millhouse.spools.cron :as cron])

  (cron/defjob :nightly-report
    {:interval-ms 86400000
     :jitter-ms 3600000
     :handler 'my.jobs/emit-report})
  ```

  `id` is the stable registry key. The closed `job` map takes positive
  `:interval-ms`, optional non-negative `:jitter-ms` (default `0`, sampled
  uniformly in `[-jitter, +jitter]`), and a fully qualified `:handler` symbol
  resolving at fire time to `(fn [runtime] ...)`.
  The optional options map accepts only boolean `:override?`; true marks
  same-id shadowing as intentional under registry layer rules. It remains
  collection metadata rather than part of the job value.

  Source evaluation schedules nothing. The form contributes under the current
  module owner; after publication Cron preserves unchanged wakes, replaces
  changed jobs, and removes declarations omitted by their owner.
<p><sub><a href="https://github.com/codethread/millhouse.spool/blob/main/spools/cron/src/millhouse/spools/cron.clj#L365-L392">Source</a></sub></p>

## <a name="millhouse.spools.cron/desired-jobs">`desired-jobs`</a>
``` clojure
(desired-jobs {:keys [runtime], :as context})
```
Function.

Lifecycle read hook: return the effective owner-published declarations as a
  normalized `id -> job` map.

  `scheduled-jobs` calls this with `{:runtime runtime}`; module authors do not.
<p><sub><a href="https://github.com/codethread/millhouse.spool/blob/main/spools/cron/src/millhouse/spools/cron.clj#L399-L410">Source</a></sub></p>

## <a name="millhouse.spools.cron/fire-wake">`fire-wake`</a>
``` clojure
(fire-wake {:keys [runtime payload]})
```
Function.

Scheduler callback for a `cron/<id>` wake; consumers do not call it directly.

  The scheduler supplies `{:runtime runtime :payload {:job id}}` on the shared
  event lane. Cron ignores stale wakes for unregistered jobs. For a live job it
  persists the next wake before submitting the resolved handler to Cron's
  execution executor, then returns so the lane never runs the job body. An
  executor rejection is recorded as an `:offload` failure rather than thrown
  onto the event lane.
<p><sub><a href="https://github.com/codethread/millhouse.spool/blob/main/spools/cron/src/millhouse/spools/cron.clj#L220-L245">Source</a></sub></p>

## <a name="millhouse.spools.cron/job-declaration">`job-declaration`</a>
``` clojure
(job-declaration id options job)
```
Function.

Build the validated registry value used by `defjob`; consumers normally call
  the macro instead.

  Attaches stable `id` to the closed `job` map, validates the optional
  `{:override? boolean}` declaration options, and returns the job value.
  `:override? true` marks same-id shadowing as intentional under registry layer
  rules; it remains collection metadata rather than part of the job value.
<p><sub><a href="https://github.com/codethread/millhouse.spool/blob/main/spools/cron/src/millhouse/spools/cron.clj#L352-L363">Source</a></sub></p>

## <a name="millhouse.spools.cron/job-kind">`job-kind`</a>




Registry kind `:millhouse.spools.cron/jobs`, targeted by `defjob` and the
  `scheduled-jobs` lifecycle declaration.
<p><sub><a href="https://github.com/codethread/millhouse.spool/blob/main/spools/cron/src/millhouse/spools/cron.clj#L38-L41">Source</a></sub></p>

## <a name="millhouse.spools.cron/jobs">`jobs`</a>
``` clojure
(jobs runtime)
```
Function.

Return Cron's managed jobs on `runtime` as status maps, sorted by id.

  Each map carries `:id`, `:interval-ms`, `:jitter-ms`, the `:handler` symbol,
  and (once fired) `:last-result`/`:last-fired-at`/`:last-error`. When a job next
  fires lives in its durable `cron/<id>` wake — read scheduler introspection
  (`millstrand.api.scheduler.alpha/pending`), the single timing view.
<p><sub><a href="https://github.com/codethread/millhouse.spool/blob/main/spools/cron/src/millhouse/spools/cron.clj#L484-L492">Source</a></sub></p>

## <a name="millhouse.spools.cron/recent-failures">`recent-failures`</a>
``` clojure
(recent-failures runtime)
```
Function.

Return up to 100 recorded failures for this runtime's weaver lifetime,
  oldest first.

  Each entry carries `:kind` (`:run` for a handler throw or `:offload` for an
  executor rejection), `:job`, `:message`, and `:at`. A `:run` failure also
  carries the handler exception's `:data` when present.
<p><sub><a href="https://github.com/codethread/millhouse.spool/blob/main/spools/cron/src/millhouse/spools/cron.clj#L111-L119">Source</a></sub></p>

## <a name="millhouse.spools.cron/register!">`register!`</a>
``` clojure
(register! runtime job)
```
Function.

Register or replace one job directly on `runtime`.

  ```clojure
  (require '[millhouse.spools.cron :as cron])

  (cron/register! runtime
    {:id :nightly-report
     :interval-ms 86400000
     :jitter-ms 3600000
     :handler 'my.jobs/emit-report})
  ```

  `job` is the same closed map documented by `defjob`, plus required `:id` as a
  keyword or non-blank string. Values and unknown keys are validated before the
  fully qualified handler symbol is resolved. Returns the normalized job status
  map.

  Re-registration preserves a pending `cron/<id>` wake when
  `[interval-ms jitter-ms handler]` is unchanged, including a fresh JVM adopting
  a durable wake. A changed tuple or missing wake arms a fresh wake at
  `now + interval + jitter`. Cron writes `fire-wake` as the scheduler callback;
  the job's `:handler` remains a function of the runtime.
<p><sub><a href="https://github.com/codethread/millhouse.spool/blob/main/spools/cron/src/millhouse/spools/cron.clj#L303-L350">Source</a></sub></p>

## <a name="millhouse.spools.cron/remove-jobs!">`remove-jobs!`</a>
``` clojure
(remove-jobs! {:keys [runtime], :as context})
```
Function.

Lifecycle removal hook: cancel every managed job and wake.

  `scheduled-jobs` calls this with `{:runtime runtime}` when its declaration is
  removed. Returns `{:reconciled :cron :jobs []}`.
<p><sub><a href="https://github.com/codethread/millhouse.spool/blob/main/spools/cron/src/millhouse/spools/cron.clj#L459-L470">Source</a></sub></p>

## <a name="millhouse.spools.cron/scheduled-jobs">`scheduled-jobs`</a>




Lifecycle declaration that keeps durable Cron wakes converged on the
  effective `job-kind` registry.

  Any owner publication for that kind triggers desired/actual reconciliation;
  removing this declaration invokes `remove-jobs!`.
<p><sub><a href="https://github.com/codethread/millhouse.spool/blob/main/spools/cron/src/millhouse/spools/cron.clj#L472-L482">Source</a></sub></p>

## <a name="millhouse.spools.cron/unregister!">`unregister!`</a>
``` clojure
(unregister! runtime id)
```
Function.

Remove job `id` from `runtime` and cancel its pending `cron/<id>` wake.

  `id` accepts the same keyword or non-blank string as `register!` and is
  normalized to a keyword. Returns `{:unregistered id}` when either managed
  configuration or a pending wake existed, otherwise `{:unregistered nil}`.
  A missing wake is tolerated; genuine scheduler cancellation failures surface.
<p><sub><a href="https://github.com/codethread/millhouse.spool/blob/main/spools/cron/src/millhouse/spools/cron.clj#L171-L186">Source</a></sub></p>
