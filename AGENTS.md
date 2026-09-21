# Agents

## Quality checks

- Run `make quality` before completing changes; it covers formatting, linting, conventions, reflection, docs, and the test suite.
- Use the focused `make` targets while iterating (`fmt-check`, `lint`, `reflect-check`, `docs-check`, `test`); `clojure -M:test --serial` is the diagnostic fallback for parallel test failures.

## Working here

- Run `strand prime kanban`, claim a feature card, and use its recorded worktree.
- Never edit `main` or push directly to `main`; feature-branch pushes are expected.
- Never stop the mill; only the user may stop it.
- Inspect `strand workflow show land` and `strand prime merge-queue`, then drive
  shared `land` for quality, one basic review, FIFO merge, card completion, and
  branch/worktree cleanup.

## Automatic assignments

- Read [the auto-run policy](docs/auto-run.md) and drive the exact workflow run
  created by the dispatcher; do not create a replacement run.
- `auto-human-review` stops at its human checkpoint. `auto-full-land` hands
  shared landing to an independent finisher before sign-off; the worker must not
  approve sign-off, merge, remove its worktree, or finish the card.
- Add `auto-run-failure` only when positive execution or validation evidence
  identifies the failed operation, concrete attempt and current delivery. Record
  the run/step, command, evidence, retained resources and merge reservation in an
  attributed note; a label is not proof or recovery authority.
- Record a design, scope or authority question with `needs-decision`, the exact
  nonblank `auto-run/decision-question`, and `auto-run/decision-role` limited to
  `human` or `operator`. The role is responsibility, not actor identity; preserve
  who raised and answered the question in attributed notes. Both signals may
  coexist and must be resolved independently without erasing history.
- Healthy waits, bounded await timeouts and ordinary human checkpoints remain
  waits. Unknown evidence authorizes no retry, gate reset, replacement, merge
  action or recovery. On a positively evidenced autonomous failure, stop without
  retrying or withdrawing a merge reservation.

## Testing

The default suite requires namespaces serially, then runs them concurrently with isolated output and summaries:

```text
clojure -M:test
clojure -M:test --serial
clojure -M:test millhouse.spools.workflow-test
clojure -M:test --stress 10
```

Focused runs are serial. Stress mode launches each parallel iteration in a fresh JVM so loaded fixture namespaces and other JVM-global state cannot leak between repetitions. Namespaces proven to require JVM-global isolation belong in the runner's documented serial island.

<!-- mill:millstrand-prime -->
## Millstrand / strand

This repo uses Millstrand strands to track work. Orientation ships in the `mill` CLI:

Start with `strand --help`. Run `mill prime millstrand` on demand when building on this repo's `.millstrand/` config or spools.
<!-- /mill:millstrand-prime -->
