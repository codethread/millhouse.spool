# Millhouse spools

Millhouse is a development monorepo for independently consumable
[Millstrand](https://codethread.github.io/millstrand/) spools. It consolidates
Harnesses, Devflow, and shared configuration alongside the existing
Millhouse packages. Millstrand core and Millstrand UI remain separate.

**Co-location is not permission to couple spools.** Preserve strict package and
activation boundaries. Spools may reference or use one another only when the
user explicitly authorizes that relationship. Existing declared dependencies
are preserved; no package becomes mandatory merely by joining this repository.

Read the [public documentation](https://codethread.github.io/millhouse.spool/)
and [migration, provenance, and board handoff](docs/consolidation.md).

## Packages

| Package | Coordinate | Purpose |
| --- | --- | --- |
| [Harnesses](spools/harnesses/README.md) | `millhouse/harnesses` | Provider-neutral tracked agents, custody, Codex/Pi providers and independently installable native plugins |
| [Devflow](spools/devflow/README.md) | `millhouse/devflow` | Feature-delivery workflows and authoring guidance; no Kanban dependency |
| [Devflow Kanban adapter](spools/devflow/kanban-adapter/README.md) | `millhouse/devflow-kanban-adapter` | Optional explicit bridge between Devflow and Kanban |
| [Config](spools/config/README.md) | `millhouse/config` | Opt-in opinionated bootstrap, aliases, reviewers, and help |
| [Auto-review](spools/auto-review/README.md) | `millhouse/auto-review` | Experimental provider-neutral review admission |
| [Auto-run](spools/auto-run/README.md) | `millhouse/auto-run` | Opt-in feature admission and delivery handoff; supersedes Ralph |
| [Workflow](spools/workflow/README.md) | `millhouse/workflow` | Workflow engine, CLI, executors, and reusable workflows |
| [Identity](spools/identity/README.md) | `millhouse/identity` | Native-session identity and provenance |
| [Kanban](spools/kanban/README.md) | `millhouse/kanban` | Work board, ownership, tasks, and dependency readiness |
| [Land](spools/land/README.md) | `millhouse/land` | Review, exact-HEAD quality, FIFO merge and cleanup |
| [Chime](spools/chime/README.md) | `millhouse/chime` | Experimental workspace-owned notification rules |
| [Cron](spools/cron/README.md) | `millhouse/cron` | Experimental durable interval jobs |

Experimental packages remain optional and may change independently. **v5 is a
breaking release:** all coordinates use `millhouse/<package>` and Clojure
namespaces use `millhouse.*`, without the former `spools` segment. No old-name
aliases are shipped. Ralph is not imported or activated.

## Consumption

Select a tested **single Millhouse commit** for the packages you need. Replace
`MILLHOUSE_SHA` with its full immutable SHA; pre-consolidation tags do not contain
the imported packages.

```clojure
{:deps
 {millhouse/harnesses
  {:git/url "https://github.com/codethread/millhouse.spool.git"
   :git/sha "MILLHOUSE_SHA"
   :deps/root "spools/harnesses"}}}
```

The root [spool catalog](spool.edn) lists every coordinate and `:deps/root`.
When selecting **multiple Git roots**, generate the selected production closure
from the checkout of that revision:

```text
scripts/consumer-deps.sh MILLHOUSE_SHA millhouse/config millhouse/auto-review
```

Use its printed `:deps` map in the consumer. It promotes only selected packages
and their declared internal dependencies to direct Git coordinates at that SHA.
This is required because tools.deps installs each Git library in a different
cache directory: two transitive `:local/root` paths to the same shared library
otherwise conflict. [Top-level dependencies win](https://clojure.org/reference/dep_expansion),
so the generated closure resolves without internal release pins or old checkouts.
A single package remains independently consumable as shown above. A local
monorepo checkout can compose arbitrary selected roots directly.

Internal package dependencies use relative `:local/root` paths within that same
Git checkout, including when tools.deps installs it in the Git library cache.
Millstrand remains an independently pinned upstream dependency. Requiring source
or adding a dependency does **not** activate a module. Follow each package's
explicit activation contract; shared config is never a core default.

Install native plugins from the separately installable
[Harnesses package](spools/harnesses/README.md#native-identity-plugins), not from
the monorepo root. Source delivery does not install plugins or update a running
Weaver.

## Development and delivery

Use the Millhouse board with component labels (`harnesses`, `devflow`, `config`,
or the existing spool name). New development and shared Land run here; old
repositories and boards retain their history and outstanding runtime work.
See [AGENTS.md](AGENTS.md) and [shared processes](docs/README.md).

Each spool and `.millstrand` is an independent tools.deps project. Configure
your editor to select the nearest `deps.edn`; do not create an aggregate editor
classpath. The root project owns repository scripts and cross-spool integration
tests. Imported suites run in separate processes, retaining package isolation.

Install native `clj-kondo` v2026.08.04 and the Harnesses toolchain recorded in its
`package.json`. Install its locked JS dependencies with
`pnpm --dir spools/harnesses install --frozen-lockfile`.

- Default gate: `make quality` (under the shared test lock): repository-wide
  static/docs checks, then affected tests and independent package gates.
- Affected tests: `make test`; inspect selection first with `make test-plan`.
- Override the comparison base for stacked branches: `make quality TEST_BASE=feature/parent`.
- Explicit full run (for example after a Millstrand update): `make quality-full`
  or tests/package gates only with `make test-full`.
- Focus one root-suite namespace: `make test TEST_NAMESPACES=millhouse.workflow-test`.
- Focused imported gates: `make harnesses-check`, `make devflow-check`,
  `make config-check`.
- Repository activation in a disposable world: `make workspace-test`.
- Published revision and native install smoke:
  `scripts/verify-distribution.sh SHA [CONSUMER_CHECKOUT...]`.

Selection compares the worktree with `git merge-base main HEAD` by default,
including committed, staged, unstaged and untracked changes. Fetch the base first
when needed; missing bases fail rather than silently skipping tests. Rename and
delete paths retain their original owners. `TEST_BASE` accepts a branch or SHA.

The selector reads `spool.edn` and package `deps.edn` files, including test-alias
libraries and nested test paths, then follows reverse dependencies. Integration
tests declare their inputs in `scripts/quality/affected.clj`; edits to an
individual root integration test select that namespace alone. Package processes
and activation boundaries stay independent. Shared build/test infrastructure or
unknown non-documentation paths select all suites. Root documentation-only and
empty diffs select no tests; files within a spool conservatively select its
suite, including shipped Markdown. Full mode does not require a Git base.

CI uses the same plan: PRs compare with their target base, main pushes compare
with the previous tip, and manual dispatch accepts a base and full-run switch.
Dispatch resolves branch names from the fetched `origin/` refs in its detached
checkout; SHAs and tags also work.
Only selected test/package jobs run; distribution smoke runs when components
are affected. Static/docs checks remain repository-wide. Shared Land and
auto-run use `.millstrand/land-quality.sh`, which calls `make quality` and owns
the test lock. It accepts Make overrides such as `TEST_BASE=feature/parent` or
`TEST_FULL=1`; do not wrap that script in another lock.

The distribution smoke starts outside this checkout, resolves the remote Git
revision, checks package provenance and native installation in temporary homes,
and exercises disposable consumer activation. Supply Millstrand UI's checkout
to verify its unchanged workspace composition against the candidate without
updating the UI repository or its live runtime.
