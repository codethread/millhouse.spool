# Millhouse Cron spool

`millhouse.spools.cron` publishes fixed-interval jobs over Millstrand's durable scheduler, with optional jitter and reloadable handlers.

## 1. Activation

Add this root to the workspace's `deps.edn`, then activate it from trusted
startup configuration:

```clojure
{:deps
 {millhouse.spools/cron
  {:git/url "https://github.com/codethread/millhouse.spool.git"
   :git/tag "v2"
   :deps/root "spools/cron"}}}
```

```clojure
(require '[millstrand.api.current.alpha :as current]
         '[millstrand.api.runtime.alpha :as runtime])

(def runtime (current/runtime))
(runtime/module! runtime :millhouse/cron
  {:ns 'millhouse.spools.cron
   :required? true})
```

Activation publishes Cron's job declaration kind and reconciliation lifecycle. Cron contributes no jobs itself.

## 2. Jobs

`defjob` is an inert declaration form: `(defjob name doc job)` defines a Var and contributes nothing. Select it with `use-job!`, optionally passing the closed `{:override? boolean}` selection map. `defjob!` defines and selects in one form, with either `(defjob! name doc job)` or `(defjob! name doc options job)`. Its generated API entry owns the complete job shape, options, and focused example.

Owner-complete publication drives the job lifecycle:

- a new declaration arms its `cron/<id>` scheduler wake;
- an unchanged interval, jitter, and handler preserves the pending wake;
- changing any of those values starts a new countdown from now;
- omitting a declaration cancels its wake;
- a missing wake is re-armed while its declaration remains effective.

Handlers receive the active runtime and must tolerate at-least-once delivery. `register!` is the lower-level explicit-runtime seam for trusted code and tests; its complete execution, status, failure, and quiescence contract lives in the generated API.

## 3. Millstrand state and APIs

| Surface | Identity | Consumer contract |
| --- | --- | --- |
| Job authoring | `use-job!` → `:millhouse.spools.cron/jobs` | Publishes selected owner-partitioned desired job declarations; `defjob!` combines definition and selection. |
| Durable timing | Scheduler wake `cron/<id>` | Holds the authoritative next-fire time and dispatches `millhouse.spools.cron/fire-wake`. |
| clj-kondo export | `resources/clj-kondo.exports/millhouse.spools/cron/config.edn` | Models `defjob`, `defjob!`, and `use-job!`; the Cron root must expose its `resources` path. |
