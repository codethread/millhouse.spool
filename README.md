# Millhouse spools

Millhouse is a development monorepo for independently consumable
[Millstrand](https://codethread.github.io/millstrand/) spools. It consolidates
Harnesses, Devflow, and Codethread configuration alongside the existing
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
| [Harnesses](spools/harnesses/README.md) | `ct.spools/harnesses` | Provider-neutral tracked agents, custody, Codex/Pi providers and independently installable native plugins |
| [Devflow](spools/devflow/README.md) | `codethread/devflow` | Feature-delivery workflows and authoring guidance; no Kanban dependency |
| [Devflow Kanban adapter](spools/devflow/kanban-adapter/README.md) | `codethread/devflow-kanban-adapter` | Optional explicit bridge between Devflow and Kanban |
| [Codethread config](spools/config/README.md) | `codethread/config` | Opt-in opinionated bootstrap, aliases, reviewers, and help |
| [Auto-review](spools/auto-review/README.md) | `millhouse.spools/auto-review` | Experimental provider-neutral review admission |
| [Auto-run](spools/auto-run/README.md) | `millhouse.spools/auto-run` | Opt-in feature admission and delivery handoff; supersedes Ralph |
| [Workflow](spools/workflow/README.md) | `millhouse.spools/workflow` | Workflow engine, CLI, executors, and reusable workflows |
| [Identity](spools/identity/README.md) | `millhouse.spools/identity` | Native-session identity and provenance |
| [Kanban](spools/kanban/README.md) | `millhouse.spools/kanban` | Work board, ownership, tasks, and dependency readiness |
| [Land](spools/land/README.md) | `millhouse.spools/land` | Review, exact-HEAD quality, FIFO merge and cleanup |
| [Chime](spools/chime/README.md) | `millhouse.spools/chime` | Experimental workspace-owned notification rules |
| [Cron](spools/cron/README.md) | `millhouse.spools/cron` | Experimental durable interval jobs |

Experimental packages remain optional and may change independently. Source
namespaces and library coordinates are unchanged by consolidation. Ralph is not
imported or activated.

## Consumption

Select a tested **single Millhouse commit** for the packages you need. Replace
`MILLHOUSE_SHA` with its full immutable SHA; pre-consolidation tags do not contain
the imported packages.

```clojure
{:deps
 {ct.spools/harnesses
  {:git/url "https://github.com/codethread/millhouse.spool.git"
   :git/sha "MILLHOUSE_SHA"
   :deps/root "spools/harnesses"}
  codethread/devflow
  {:git/url "https://github.com/codethread/millhouse.spool.git"
   :git/sha "MILLHOUSE_SHA"
   :deps/root "spools/devflow"}}}
```

The root [spool catalog](spool.edn) lists every coordinate and `:deps/root`.
Internal package dependencies use relative `:local/root` paths within that same
Git checkout, including when tools.deps installs it in the Git library cache.
Millstrand remains an independently pinned upstream dependency. Requiring source
or adding a dependency does **not** activate a module. Follow each package's
explicit activation contract; Codethread config is never a core default.

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

- Full gate: `make quality` (under the shared test lock).
- Existing Millhouse suite: `make test`.
- Focused imported gates: `make harnesses-check`, `make devflow-check`,
  `make config-check`.
- Repository activation in a disposable world: `make workspace-test`.
- Published revision and native install smoke:
  `scripts/verify-distribution.sh SHA [CONSUMER_CHECKOUT...]`.

The distribution smoke starts outside this checkout, resolves the remote Git
revision, checks package provenance and native installation in temporary homes,
and exercises disposable consumer activation. Supply Millstrand UI's checkout
to verify its unchanged workspace composition against the candidate without
updating the UI repository or its live runtime.
