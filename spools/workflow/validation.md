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
has exactly `:stage` (`:retry`, `:launch`, `:complete`), `:run-id`, `:gate` (captured
input row), `:params` (frozen, string-keyed data), `:expected-revision` and
`:previous-attempt` (common result envelope or nil). It must not mutate gates, reserve retries,
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

The retained command delegates directly to common attempt authorization; it is
not a parallel Shell lifecycle. Results retain string `:state` (`eligible`,
`accepted`, `replayed`, `refused`) and use the common `:action`. The action records
`:request`, `:attempt-id`, `:previous-attempt`, `:previous-result` and
`:frozen-request`. A refusal includes `:reasons`. Dry-run writes nothing. Accepted
is authorization, never validation success. Exact run-scoped request replay
returns the original action; a conflicting payload refuses.

Inspect with `workflow execution RUN --step GATE`. The common result records
`:validation-revision`, outcome, settlement, value and acknowledgement. Shell
value contains exit code and bounded output. When policy rejects a zero exit,
original backend output is retained in result evidence `backend-result`.
Old attempts/actions are independent durable rows, not growing gate histories.
No old running/attempt/custody aliases or `validation/receipt` remain.

## Settlement and authority

Common execution owns identity, intent, result delivery and retry. Shell only
launches/observes/stops/acknowledges Mill custody. Terminal delivery commits
before external acknowledgement. Known settlement with lost acknowledgement
confirmation permits explicit retry, preserving `unknown` on the old attempt.
Missing handles, ambiguous launch or cancellation never prove settlement.

Retry takes the execution monitor then the Workflow guard and fences the exact
root/gate/prior attempt in its transaction. The common completion scope protects
success, including launch and completion revision inspections. Frozen recipe,
configuration, params and request cannot be replaced. Launch validation failure
is an inspectable never-accepted settled failure with an attempt token.

This is best-effort coordination, not complete-process durability or exactly-once
execution. No queue, human checkpoint, budget or custody policy is bypassed.
