# Managed gate execution

Code uses the Workflow-owned execution lifecycle. Shell, Agent and queue waiters
remain on their existing drivers until their respective conversions. Requiring a
provider is inert; select only its lifecycle resource:

```clojure
(ns app.code
  (:require [millhouse.executors.code :as code]
            [millstrand.api.lifecycle.alpha :as lifecycle]))

(lifecycle/use-resource! code/code-engine)
```

Activate this module after the Workflow engine. Workflow remains independently
consumable: its only new dependency is Fulcrologic Statecharts at Git SHA
`e59fa99fcdd789e8e4f3810a6ff0e333d0a0e92a`. Dependency changes require separately
authorized generation replacement; source delivery does not activate them.

## Attempts and repair

One UUID identifies each durable attempt. Its complete captured gate input,
projected request, descriptor revision and deadline are frozen. Even malformed
input creates a settled, never-started failed attempt and current token atomically.
Callbacks execute off the event lane. Nil succeeds; non-JSON values and thrown
exceptions fail. A common result commits with the ordinary Workflow gate close
and cascading procedure joins. Root-finalization housekeeping is separately
reconcilable through a retained pending marker; it is not part of that atomic
close. Finished, acknowledged attempts stay inspectable but leave the scheduler's
reconciliation query.

```nu
strand workflow execution RUN --step GATE
strand workflow retry RUN --step GATE --expected-attempt TOKEN --request-id repair-1 --reason 'Corrected input' --by-identity ACTOR
```

Repair the ordinary gate's request attributes before retrying. Retry captures the
corrected input once and authorizes exactly one new attempt. Exact request-key
and payload replay returns the original accepted action and frozen request, even
after further edits or completion. A conflicting payload refuses. `--dry-run`
writes nothing. Previous attempts remain retained; cleanup of their evidence
cannot change a replacement token. Validation-marked gates cannot bypass their
existing frozen-recipe policy through this operation.

Removing or blanking `gate/error` is **not** retry authority. Direct completion,
raw closure and executor/actor string spoofing cannot complete a managed gate.
Public burn paths retain managed evidence and its roots; callers cannot forge
attempt rows or seed execution authority on new strands.
Removing a descriptor does not remove persisted ownership, including on gates
that were never ready. Unregistered manual/external waiters are unchanged.

## Capacity, deadlines and settlement

Code retains eight invocation workers with no task queue. Start returns explicit
busy when no work was accepted. Busy retries offer the same attempt; nil is never
interpreted as busy. Ambiguous start is observed by correlation, not relaunched.
The deadline is fixed at **attempt creation** and includes capacity waiting. This
is a deliberate simplification: neither busy admission nor recovery resets it.

Stop before acceptance settles without invoking Code. After acceptance,
interruption is only a stop request. A stubborn callback keeps its worker until
it returns; timeout cannot free capacity or authorize overlapping retry. A late
success after durable stop intent becomes cancellation/timeout, not success.
`InterruptedException` no longer automatically rearms a gate.

Runtime handles and threads never enter durable snapshots. Losing a Code handle
without positive settlement is unknown, not proof that execution stopped. This
release has no force-settled escape hatch for a lost generation. Drain before
replacement. Activation refuses active legacy Code invocation/error markers;
there is no snapshot translation or implicit retry during cutover. Statecharts is the private synchronous decision core, **not** an
effect transaction or an exactly-once side-effect guarantee.

## Retire before routing away

A routed choice that would abandon managed gates refuses without partial mutation.
The Clojure API supplies the Code path for explicit retirement:

```clojure
(require '[millhouse.workflow.execution :as execution])

(def freeze (execution/quiesce-run! runtime run-id "Replace this root"))
(def receipt (execution/retire! runtime freeze))
;; Repeat retirement until :status is :settled; unknown is not permission.
(workflow/choose! run-id :move {} {:step checkpoint-id :retirement receipt})
```

Freeze covers live and not-yet-ready managed work. `resume-run!` consumes an exact
positive retirement receipt and removes only that freeze; it retries nothing.
`abandon-run!` can instead close the exact retired root and pour a supplied
replacement atomically. Its optional domain patches are data-only exact
before-image/update pairs on existing non-Workflow rows, never callbacks, creates,
edges or success patches. Success authority and abandonment authority are distinct.
The Shell slice owns cross-backend freeze and Land withdrawal integration; this
slice does not freeze legacy Shell/Agent/queue execution or authorize their cutover.

## Proof ownership

- `workflow-execution-chart-test`: pure real-library success/failure, busy,
  duplicate/stale events, stop/result ordering, full EDN restoration in fresh
  environments, interpreter errors and malformed interpreter returns. No Weaver.
- `workflow-execution-test`: one disposable file-backed runtime proves claim,
  completion and abandonment before-image refusal/no partial write, postcommit
  root-finalization recovery without callback replay, cascading
  joins, nearest nested root, unchanged refresh/removal/readoption, ordinary manual gates,
  protected unstarted work, forged creation/burn refusal, routed retirement and
  bounded domain-patch cutover with replacement definition/family identity.
- `executors.code-test`: public JSON/nil success, thrown/interrupted/non-JSON and
  malformed-input failure, corrected explicit retry/replay/conflict; real
  eight-worker saturation, never-accepted stop, frozen deadline and stubborn
  callback settlement. Manual clock and latches establish the boundaries.

The former Code scanner, root traversal, scalar token/result machinery, stalled
query/predicate and interruption-auto-retry tests are removed. Generic transitions
now belong to the common chart/store proofs rather than another Code matrix.
No process replacement, OS process-tree cancellation or live activation is claimed.
