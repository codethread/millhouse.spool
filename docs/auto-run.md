# Automatic feature delivery

Millhouse uses Codethread's shared dispatcher to pick up graph-ready pending
features carrying the `auto-run` label. Epics and refinement cards are not
eligible, and existing dependencies must close before dispatch.

## Repository policy

`.millstrand/me/auto_run.clj` owns admission: two concurrent workers, a
15-second scan interval, `wktree` preparation, and Sol/low with
`auto-human-review` by default. The only allowed workflows are:

- `auto-human-review`: implement, run `make quality`, publish a ready PR, wait
  for CI, move the card to review, and stop at an explicit human checkpoint.
- `auto-full-land`: perform the same preparation, then call shared
  `millhouse.spools.land.autonomous/autonomous-land`. Its worker step drives
  `land` through basic review and accepts an independent canonical-root grunt
  against a **separate dependent finisher step** before sign-off. The grunt owns
  FIFO merge, cleanup, and card completion.

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

## Ownership and failure policy

The assigned worker claims the supplied card and drives the dispatcher-created
workflow. Human-review workers never approve their own checkpoint. Full-land
workers never approve shared-land sign-off themselves: they record the exact
PR/head, run IDs, branch/worktree, and owned resources, then accept the tracked
finisher against the step marked `auto-run/role=finisher`, never the worker's own
step. Record `auto-run/worker-run-id` and `auto-run/finisher-run-id` on that target
before completing the worker step. The accepted finisher is blocked until that
completion; it then waits for the original worker to settle successfully. The
worker returns immediately and never completes the finisher step.

On an observed autonomous gate, handoff, or landing failure, add
`auto-run-failure`, note the command and evidence plus retained resources and
merge reservation, and stop with the card open. Do not automatically retry,
replace the worker, clear gate errors, or withdraw a merge turn. Normal bounded
queue-wait timeouts are not failures.

## Authorized recovery

Inspect the target's role **before** launching a continuation. A recovery worker
serves the card or `handoff-worker` step, not the finisher step. A run assigned to
the finisher step must be the independent canonical-root finisher and must never
launch another finisher. Do not rewrite the delivery-worker receipt to name it.

After an interrupted handoff, inspect the exact immutable
`auto-land-finisher/FINISHER_STEP_ID` request before doing anything else. A run may
already be accepted but blocked because the worker did not record its receipt or
close its step. Retain it for explicit reconciliation; do not replace it or vary
the key/payload. A missing successful worker settlement still prevents sign-off.

Existing single-step workflow runs keep their poured instructions after refresh.
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
verifies separate target acceptance, blocked launch readiness, receipt ordering,
request idempotency, immutable-payload conflicts and the unchanged single-writer
guard. It does not simulate successful external review or GitHub merging.

Changing the Codethread dependency basis requires the supported planned Weaver
restart. Source-only policy edits can use normal module refresh. Restart only
this repository's Weaver, never unrelated Weavers, then verify `strand auto-run
status`, `strand workflow show auto-human-review`, and `strand workflow show
auto-full-land` before opting in production work.
