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
- `auto-full-land`: perform the same preparation, then drive shared `land`
  through basic review and hand the run to an independent canonical-root grunt
  before sign-off. The grunt owns FIFO merge, cleanup, and card completion.

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
PR/head, run IDs, branch/worktree, and owned resources, then launch the tracked
finisher against the delivery handoff step. The finisher waits for the original
worker to settle before approving sign-off.

On an observed autonomous gate, handoff, or landing failure, add
`auto-run-failure`, note the command and evidence plus retained resources and
merge reservation, and stop with the card open. Do not automatically retry,
replace the worker, clear gate errors, or withdraw a merge turn. Normal bounded
queue-wait timeouts are not failures.

## Activation and verification

Run the disposable workspace test from `.millstrand` with `clojure -M:test` and
run repository `make quality`. The test activates the real init and policy in an
in-memory Weaver without labeling a card or launching a paid agent.

Changing the Codethread dependency basis requires the supported planned Weaver
restart. Source-only policy edits can use normal module refresh. Restart only
this repository's Weaver, never unrelated Weavers, then verify `strand auto-run
status`, `strand workflow show auto-human-review`, and `strand workflow show
auto-full-land` before opting in production work.
