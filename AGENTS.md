# Agents

## Monorepo purpose and strict spool boundaries

This monorepo exists to aid development, testing, and coordinated delivery. It is
not permission to couple its spools. **Enforce strict spool boundaries: spools
may reference or use one another only when explicitly authorized by the user.**
Preserve the existing declared dependencies and activation contracts; do not add
cross-spool imports, dependencies, shared internals, or implicit activation just
because the source is nearby. Ask before introducing a new relationship.

Each spool remains independently consumable, with its own manifest, classpath,
focused tests, and explicit module activation. Shared development scripts and
integration tests are not runtime APIs. Codethread configuration is opt-in;
Devflow's Kanban adapter is independently optional. Ralph is not part of this
repository: Auto-run supersedes it. Millstrand core and Millstrand UI remain
separate repositories.

New development for Harnesses, Devflow, Codethread config, and existing Millhouse
spools belongs on the Millhouse board with component labels. See
[the consolidation and handoff record](docs/consolidation.md) before moving old
work; old boards and repositories retain their history and are not merged or
silently retired.

## Safety

- Never restart a running Weaver without explicit user sign-off.
- Kill by exact PID only, never process-name patterns.
- Run workspace-backed tests in disposable worlds, never the shared `.millstrand`
  world. Creating source does not authorize live activation or plugin installation.
- Run full suites under `flock -w 180 /tmp/millstrand-test.lock`; focused tests do
  not need the lock. The landing quality contract owns its lock: do not nest it.

## Quality checks

- Run `make quality` before completing changes; it covers formatting, linting,
  conventions, reflection, docs, and affected tests/package gates. Selection uses
  the worktree diff against the merge-base with `main`, including dirty and
  untracked files. Use `TEST_BASE=feature/parent` for stacked branches and
  `make test-plan` to inspect the selection.
- Use `make quality-full` (or `make test-full` for tests/package gates only) for
  an explicit full run, such as after a Millstrand update. CI and the shared
  landing contract use the same affected-test selector.
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
- Follow Codethread's canonical agent blocker contract; repository policy adds
  only the full-land custody boundaries.

## Testing

`make test` runs affected namespaces and independent package gates. The direct
root-suite runner remains available for explicit full, diagnostic and focused
runs. Its full mode requires namespaces serially, then runs them concurrently
with isolated output and summaries:

```text
clojure -M:test
clojure -M:test --serial
clojure -M:test millhouse.workflow-test
clojure -M:test --stress 10
```

Focused runs are serial. Stress mode launches each parallel iteration in a fresh JVM so loaded fixture namespaces and other JVM-global state cannot leak between repetitions. Namespaces proven to require JVM-global isolation belong in the runner's documented serial island.

<!-- mill:millstrand-prime -->
## Millstrand / strand

This repo uses Millstrand strands to track work. Orientation ships in the `mill` CLI:

Start with `strand --help`. Run `mill prime millstrand` on demand when building on this repo's `.millstrand/` config or spools.
<!-- /mill:millstrand-prime -->
