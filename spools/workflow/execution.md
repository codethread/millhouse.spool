# Managed gate execution

Code, Shell and the optional downstream Harnesses Agent adapter use the
Workflow-owned execution lifecycle. Queue waiters retain their existing driver
until their conversion. Requiring a
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

Observation recording fences the complete attempt image and exact current gate
token, merging only its phase projection. Unrelated gate/root metadata edits do
not discard a known busy response or rewrite frozen input. Claims, dispatch
intent, stop/control changes and terminal delivery retain their full-image
fences. Recording an observation is not completion authority. Reconciliation
continues while custody or acknowledgement is unknown, but unchanged uncertainty
does not generate new database writes or graph events. Returning to a known
observation clears the warning even when that observation matches the last known
one before custody was lost.

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
frozen-recipe policy: add `--expected-revision REVISION` to the same command.
Inspection exposes `:retry-action` separately from the current `:result`; an
accepted action is authorization, not proof of successful validation.

Removing or blanking `gate/error` is **not** retry authority. Direct completion,
raw closure and executor/actor string spoofing cannot complete a managed gate.
Public burn paths retain managed evidence, retry actions and their roots; callers cannot forge
attempt rows, seed execution authority, or create an already-closed managed gate.
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
The Clojure API supplies explicit retirement for managed work:

```clojure
(require '[millhouse.workflow.execution :as execution])

(def freeze (execution/quiesce-run! runtime run-id "Replace this root"))
(def receipt (execution/retire! runtime freeze))
;; Repeat retirement until :status is :settled; unknown is not permission.
(workflow/choose! run-id :move {} {:step checkpoint-id :retirement receipt})
```

Freeze covers live and not-yet-ready managed work belonging to that nearest root.
Independent nested roots require their own retirement and cutover: even a retired
child cannot have its active managed gates closed by an ancestor's receipt. The
final transaction rechecks nearest-root membership; planned row images do not
stand in for parent-edge evidence. `resume-run!` consumes an exact positive
retirement receipt and removes only that freeze; it retries nothing.
`abandon-run!` can instead close the exact retired root and pour a supplied
replacement atomically. Its optional domain patches are data-only exact
before-image/update pairs on existing non-Workflow rows, never callbacks, creates,
edges or success patches. Success authority and abandonment authority are distinct.
Land withdraws only after this retirement, outside its queue lock. It then
revalidates and atomically releases its exact reservation/lock and abandons into
abort. May-have-started irreversible work refuses even after local cancellation.
Legacy queue join/grant/release transactions fence this public root image before
their separate adapter conversion. Source cutover is not permission to abandon
unknown legacy execution.

## Retained Shell processes

Select `shell/shell-engine`. The adapter projects shell-free argv and cwd, then
uses Mill's stable owner/attempt key. A lost launch response is observed by that
same key; missing custody or a new Mill lifetime stays unknown, never relaunched.
The original attempt deadline survives replacement. Exit 124 is an ordinary
failure, not a timeout signal. Cancellation requires positive settlement.

Inspection exposes `:reference`, common `:result`, and acknowledgement uncertainty.
Shell result values contain `:exit-code` and a 16 KiB stdout-then-stderr `:output`
tail. When stop or revision policy overrides the backend outcome, the original
value remains under result evidence `backend-result`. Terminal data commits before
acknowledgement. A lost acknowledgement response permits a settled failed retry
without relabeling old cleanup as confirmed; old attempts own their own cleanup.
Planned runtime shutdown detaches observation without stopping Mill commands.
Module removal instead records stop intent, retaining unknown work.

## Agent runs and opaque provenance

The optional Harnesses adapter freezes its complete request at attempt creation,
including explicit prompt or instruction/description/title fallback and every
gate overlay. Malformed explicit prompts become inspectable never-started
failures. Corrected ordinary retry captures a new image and publishes a new
request-bound run; it never retries the old run in place. Direct gate-owned
Harnesses retry/resume refuses. Harnesses remains independently usable without
selecting this adapter, and Workflow has no Harnesses dependency.

Terminal observations may carry `:executor-run-id`, an explicit opaque nonblank
string. Absence is valid; nil, blank and nonstring values are invalid. No other
observation variant permits it. The common result retains it through failure,
stop overrides and acknowledgement recomputation. Successful delivery supplies
that committed result field to the existing completion planner in the same
conditional result/gate/join batch. Transaction refusal writes no partial
provenance, result or close; retained evidence can be redelivered.

The kernel never derives this ID from backend reference/value, resolves it to an
actor, or uses it as authorization. Executor identity still comes from the
managed descriptor. The Agent value is `{:run-id ID :result FINDINGS}` and agrees
with the exact Harnesses ID in provenance. Success requires actual settlement,
successful completion and nonblank findings. Failed/stopped/unknown runs keep
the gate blocked. Findings do not approve the next review decision.

## Proof ownership

- `workflow-execution-chart-test`: pure real-library success/failure, busy,
  duplicate/stale events, stop/result ordering, full EDN restoration in fresh
  environments, interpreter errors and malformed interpreter returns. No Weaver.
- `workflow-execution-test`: one disposable file-backed runtime proves claim,
  completion and abandonment before-image refusal/no partial write, observation
  persistence across independent gate/root metadata writes, postcommit
  root-finalization recovery without callback replay, cascading
  joins, optional opaque provenance without actor attribution, nearest-root authority (including a separate edge-only writer), unchanged
  refresh/removal/readoption, ordinary manual gates, protected unstarted work,
  forged creation/burn refusal, routed retirement and
  bounded domain-patch cutover with replacement definition/family identity.
- `workflow-execution-observation-test`: a disposable file-backed runtime proves
  repeated unknown observations and acknowledgement failures produce no new
  attempt writes, while changed evidence and recovery still persist.
- `executors.code-test`: public JSON/nil success, thrown/interrupted/non-JSON and
  malformed-input failure, corrected explicit retry/replay/conflict; real
  eight-worker saturation, never-accepted stop, frozen deadline and stubborn
  callback settlement. Manual clock and latches establish the boundaries.
- `executors.shell-test`: short real commands with simulated custody prove adapter
  mapping, lost launch response, commit-before-ack, unknown stop and revision policy.
- `executors.shell-replacement-test`: a built disposable Mill and actual Weaver
  replacement retain the same attempt/handle and reach the next frontier.
- Harnesses `executors.agent-test`: pure request projection plus nonexecuting
  provider publication/adoption, frozen-input repair with a new correlated run,
  exact stop, settlement mapping and an unapproved subsequent checkpoint.
- `land.merge-queue-test` and `land.withdrawal-test`: FIFO/domain protection,
  retirement/abort, deterministic freeze-versus-grant and no partial stale cutover.

The former Code scanner, root traversal, scalar token/result machinery, stalled
query/predicate and interruption-auto-retry tests are removed. Generic transitions
now belong to the common chart/store proofs rather than another Code matrix.
Only the dedicated replacement test claims real process custody. No OS process-tree
internals or live activation are tested.
