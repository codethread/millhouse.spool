# Automatic feature delivery

Millhouse uses Codethread's shared dispatcher to pick up graph-ready pending
features carrying the `auto-run` label. Epics and refinement cards are not
eligible, and existing dependencies must close before dispatch.

## Repository policy

`.millstrand/me/auto_run.clj` owns admission: two concurrent workers, a
15-second scan interval, `wktree` preparation, and Sol/low with
`auto-human-review` by default. The only allowed workflows are:

- `auto-human-review`: implement, run `make quality`, publish a ready PR, wait
  for CI, move the card to `in_review` for human attention, and stop at an explicit human checkpoint.
- `auto-full-land`: perform the same preparation, then call shared
  `millhouse.auto-run-land/autonomous-land`. Its worker phases record
  review, frozen handoff, accepted finisher and release separately. One independent
  canonical-root grunt serves a **separate finisher custody target** across its
  settlement wait, executor verification, signoff and landing-observation phases.
  The custody target stays open throughout; phases never create new assignments.
  The grunt owns
  FIFO merge, cleanup, and card completion. The card stays `claimed` (in progress)
  throughout agent review and authorized landing; no human-review lane transition
  is needed. `in_review` indicates a human approval, blocker, or pending decision,
  not that work has reached a later stage.

Per-card overrides use `auto-run/seat`, `auto-run/effort`, and
`auto-run/workflow`. For example:

```clojure
{:auto-run/seat "astra"
 :auto-run/effort "low"
 :auto-run/workflow "auto-full-land"}
```

Inspect admission with `strand auto-run status` and request an immediate scan
with `strand auto-run scan`. A durable `assigned` receipt is not a live-agent
status; inspect its exact Harnesses run separately.

## Quality lock ownership

Both automatic delivery and shared Land invoke `.millstrand/land-quality.sh`.
That script alone acquires `/tmp/millstrand-test.lock` with `flock -w 180` before
running `make quality`; do not wrap that script in another lock. The gate uses
Git changes against the merge-base with `main` to select affected tests and
independent package gates; static/docs checks remain repository-wide. Inspect
with `make test-plan`, override with `TEST_BASE=feature/parent`, or request all
suites with `TEST_FULL=1` (`make quality-full` outside the contract). These Make
overrides can also be passed to the contract script. CI uses the same selector.
Direct full suite commands still need the shared lock. Focused tests remain exempt. A lock
acquisition failure fails the gate without starting quality; apply the normal
explicit recovery policy rather than clearing the gate or retrying implicitly.

## Ownership and failure policy

The assigned worker claims the supplied card and drives the dispatcher-created
workflow. Human-review workers never approve their own checkpoint. Full-land
workers never approve shared-land sign-off themselves: they record the exact
PR/head, run IDs, branch/worktree, and owned resources, then accept the tracked
finisher against the step marked `auto-run/role=finisher`, never the worker's own
step. Record `auto-run/worker-run-id` and `auto-run/finisher-run-id` on that target
before completing the worker step. The accepted finisher is blocked until that
release; it then waits for the frozen current worker to settle successfully.
The code gate independently checks actual settled/completed/exit-zero evidence,
current-worker agreement and the accepted canonical-root finisher target. The
worker returns immediately and never completes any finisher phase. The finisher
uses explicit --step selectors because its custody anchor remains ready beside
its current phase; it closes that anchor last, after verified landing.

Follow the shared [agent blocker contract](processes/auto-run.md#agent-blocker-contract). Full-land failures leave
the delivery open and retain owned resources and merge reservations; do not retry,
replace workers, clear gate errors, or withdraw a merge turn without explicit
recovery authorization. Normal bounded queue waits are not failures.

## Authorized recovery

Inspect the target's role **before** launching a continuation. A recovery worker
serves the card or `handoff-worker` step, not the finisher step. A run assigned to
the finisher step must be the independent canonical-root finisher and must never
launch another finisher. Do not rewrite the delivery-worker receipt to name it.

Before handoff freezes, an authorized coordinator may accept a worker
continuation after prior workers settle, then register it with the supported
operation (inspect its live help first):

```nu
strand auto-run register-worker --card CARD_ID --worker ACCEPTED_RUN_ID --expected-current-worker PREDECESSOR_ID --reason 'Authorized recovery reason' --by-identity COORDINATOR_ID
```

The operation verifies a unique published continuation path from the recorded
worker to the accepted head, settlement of every predecessor (including cancelled
intermediate workers), unchanged task/logical root/worktree ownership, the expected current
receipt and absence of a frozen or accepted finisher. It records the reason and
actor with `auto-run/run-id` in one card update. Exact request replay is a no-op;
a changed request is not a replay. It does not launch or claim anything, clear
blockers, or grant recovery authorization. It requires Harnesses' public
`call-with-run-publication-lock` boundary to serialize validation and registration
with run acceptance. This is not protection against arbitrary raw graph edits.
The workspace and Auto-run library select the local Harnesses package providing
that API at the same Millhouse revision. Production activation remains a separate coordinator-owned action.
The worker must verify the reconciled receipt before accepting a finisher; a
stale receipt is an actionable stop, not permission to await the earlier worker. Harnesses requests and their lineage remain immutable.

After an interrupted handoff, inspect the exact immutable
`auto-land-finisher/FINISHER_STEP_ID` request before doing anything else. A run may
already be accepted but blocked because the worker did not record its receipt or
close its step. Retain it for explicit reconciliation; do not replace it or vary
the key/payload. Do not start a new worker or rewrite the accepted finisher's
frozen worker ID. After successful settlement of the recorded worker, a coordinator
can reconcile the exact request, missing receipts and worker-step completion.
Failed or uncertain settlement needs a new explicit recovery decision and must
not be relabeled as success.

Existing single-step and two-step workflow runs keep their poured instructions
and topology after refresh.
Do not replace or repour them. For an explicitly authorized recovery already at
reviewed sign-off, an independent canonical-root finisher may serve that old
handoff step directly after successful worker settlement and exact review/PR
verification. Its complete prompt must supersede the old worker instructions:
**finish, do not delegate again**. Record the exact request, receipts and retained
resources. Otherwise stop with an actionable request for coordinator intervention.

## Activation and verification

Run the disposable workspace test from `.millstrand` with `clojure -M:test` and
run repository `make quality`. The test activates the real init and policy in an
in-memory Weaver without labeling a card or launching a paid agent. It reproduces
the combined-step recovery collision using real Harnesses publication, then
verifies separate target acceptance and the shared quality entry point. Library
integration tests drive the new ready phases with one persistent target, verify
actual successful worker settlement, reject stale or foreign recovery lineage,
and exercise accepted-finisher recovery before receipts exist, exact request
idempotency and immutable-payload conflicts. It does not simulate successful external review or GitHub merging.

Changing the loaded dependency basis requires separate operator-authorized
activation. Never restart a running Weaver without explicit user sign-off. Source-only policy edits can use normal module refresh. Restart only
this repository's Weaver, never unrelated Weavers, then verify `strand auto-run
status`, `strand workflow show auto-human-review`, and `strand workflow show
auto-full-land` before opting in production work.
