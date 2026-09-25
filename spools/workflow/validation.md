# Explicit guarded validation retry

This is a single-attempt Workflow primitive, not an automatic recovery policy,
gate reset, DAG rewind, or retry loop. It depends only on Core. Land, Git, PRs,
CI phases, Kanban ownership, eligibility and episode budgets belong to consumers.
No production recipe is selected by default. Human checkpoints and protected
queue hooks are unchanged.

## Configure a future gate

A consumer installs one closed configuration map from an explicit lifecycle
resource, before pouring opted-in workflows. Registration alone executes nothing.
Keys must be qualified keywords ending in `-v` plus digits. Each recipe map has
exactly one key, `:inspect`, naming a resolvable qualified callable symbol.
Change behavior under a **new recipe identity**; do not redefine an existing
version's semantics. Frozen configuration checks compare the callback symbol,
not a source-code hash.

```clojure
(ns acme.validation
  (:require [clojure.string :as str]
            [millhouse.workflow :as workflow]
            [millhouse.workflow.validation :as validation]
            [millstrand.api.lifecycle.alpha :as lifecycle]))

;; Disposable example only. Production recipes must check their own stop rules
;; and source eligibility at retry AND launch, then source identity at completion.
(defn inspect [_runtime {:keys [params]}]
  {:decision :allow
   :revision (str/trim (slurp (get params "candidate")))
   :reason "Read disposable candidate identity"
   :evidence []})

(defn open-recipes [{:keys [runtime]}]
  (validation/open! runtime
    {:recipes {:acme/check-v1 {:inspect 'acme.validation/inspect}}}))

(defn close-recipes [{:keys [runtime resource]}]
  (validation/close! runtime resource))

(lifecycle/defresource recipes
  "Own the explicitly selected validation recipes."
  {:open 'acme.validation/open-recipes
   :close 'acme.validation/close-recipes})
(lifecycle/use-resource! recipes)

(workflow/defworkflow check
  "Check a disposable candidate, then stop for a human."
  {:entrypoints #{:start}}
  (workflow/workflow "Check"
    (workflow/gate :check "Validate" :shell
      :attributes {"validation/recipe" "acme/check-v1"
                   "validation/params" {"candidate" "/tmp/acme-candidate"}
                   "shell/argv" ["sh" "-c" "test \"$(cat /tmp/acme-candidate)\" = repaired"]})
    (workflow/checkpoint :review "Human review" :kind :human
      :depends-on [:check] :choices [:accepted])))
```

Activate the normal Workflow engine and shell provider as described in the
[README](./README.md), then the consumer module. The example uses no forge or
Kanban; it is a producer unit example, not an end-to-end admission policy.
The real validation command must also bind its work to the candidate identity;
pre/post inspections do not make a mutable external filesystem transactional.

Pour freezes `validation/recipe`, `validation/params`, `validation/config`
(the inspector symbol), and `validation/request` (the normal shell request).
Only shell gates may opt in. Params are bounded to 16,384 printed characters:
string-keyed maps, vectors, strings, integers, booleans and nil. Retry cannot
supply replacement params or argv. Missing or changed registration/request is
unsupported, not permission to adopt new semantics.

## Inspector boundary

The trusted read-only callback receives `(inspect runtime request)` where request
has exactly `:stage` (`:retry`, `:launch`, `:complete`), `:run-id`, `:gate` (current
row), `:params` (frozen, string-keyed data), `:expected-revision` and
`:previous-attempt` (receipt or nil). It must not mutate gates, reserve retries,
launch work, or assert executor success. This is a trusted callback contract,
not a sandbox for hostile code.

The result is a closed map with required `:decision` (`:allow`, `:refuse`,
`:unknown`), nonblank `:reason`, vector `:evidence`, and optional nonblank
`:revision`. Allow requires a revision. Evidence uses the same data grammar as
params and is bounded to 16,384 printed characters. Initial launch has no
expected revision; allow captures it. Retry, reserved launch and completion must
match the explicit expected token. A zero exit with completion drift/refusal or
an inspection exception remains a failed validation, not success.

## Request and results

```text
strand workflow retry-validation RUN --step GATE --request-id KEY \
  --expected-revision TOKEN --reason TEXT --by-identity ACTOR --dry-run
```

Remove `--dry-run` for **one explicitly authorized attempt**. The Clojure API is
`workflow/retry-validation!` with the corresponding closed request map:
`:run-id`, `:step`, `:request-id`, `:expected-revision`, `:reason`, `:by-identity`
are required nonblank strings; optional `:dry-run` is boolean and `:episode-ref`
is a nonblank external episode/action reference. Actor is attribution, never an
executor-success claim or substitute for a consumer's ownership checks. An
automatic consumer reserves its budget before invoking this operation.

Results use string `:state`:

- `eligible` / `refused`: plan includes `:old-attempt`, `:recipe`, `:revision`,
  `:frontier`, and `:reasons`. Dry-run performs no writes or acknowledgement.
- `accepted`: `:action` identifies the authorization, original request, old
  terminal receipt, old error, recipe and inspection evidence. **Not success.**
- `replayed`: the same run-scoped key and payload return the original action,
  even after a newer failure or completion. Different payload under that key
  returns `refused` with the original action and a conflict reason.

Accepted actions are separate retained rows indexed by
`validation/action-run-id` and `validation/action-request-id`, holding
`validation/action`; the gate also appends `validation/actions`. Action fields
are string-keyed on the wire: `id`, `request`, `old-attempt`, `error`, `recipe`,
`evidence`. Reconcile an ambiguous response using the **same key and payload**;
never select a new key or refund an unknown effect. Replay is supported while
records remain, not promised after data loss or deletion.

Malformed boundaries and unsupported registrations throw `ExceptionInfo` with
`:reason` values `:workflow/validation-request`, `-config`, `-result`,
`-unsupported` (each with the `workflow/validation` prefix). Fencing/protection
errors use `:workflow/validation-stale`, `-frozen`, `-executor-owned`;
launch refusal uses `:workflow/validation-launch-refused`. Core wraps hook errors
as `hook/failed`, retaining the original exception data. Unknown roots/gates or
unavailable custody reads remain ordinary loud Core errors, not eligible plans.

## Custody, history and permitted loss

Shell owns attempt identity, Mill custody, output and completion. An opted-in
terminal attempt retains `validation/receipt` before acknowledgement: string
keys `attempt-id`, `custody-handle`, `terminal-observed`, `settled`,
`acknowledgement`, `revision`, `recipe`, `exit`, `output`, `error`. Output is the
normal bounded 16 KiB combined tail. Acknowledgement starts as `unknown` and is
marked `confirmed` only after the actual acknowledgement returns. Nil active
custody fields do **not** prove acknowledgement.

Known settled terminal observation plus missing acknowledgement confirmation
permits an explicitly authorized retry, while retaining `unknown` in its old
receipt. Actual running, uncertain cancellation, mismatched or unknown current
custody refuses. A missing receipt or source token also refuses. Interrupted
bookkeeping can lose history or leave orphaned retained Mill evidence. This is
best-effort resumability, **not crash-proof receipt storage or exactly-once
execution**; no Core tombstone service is required.

Retry holds the shell scan/terminal monitor, then the Workflow run guard (the
same order as terminal completion). It re-reads the gate, active root, readiness,
recipe and custody; batch precommit compares exact root/gate before-images.
The action append and clearing of the eligible active error/outcome/custody
projection commit together. Old receipt becomes `validation/previous-attempt`;
`validation/revision` reserves the next revision. Normal scanning owns the new
launch. Retained old observers cannot stamp a new attempt. Queue hooks still
run, and no labels such as `auto-run-failure` or `human-attention` are cleared.
