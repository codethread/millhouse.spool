# Consolidation and handoff

## Scope and authorization

Millhouse is the development home for Harnesses, Devflow, Codethread config, and
its existing spools. This is a source consolidation, not a runtime deployment or
permission to create new spool dependencies. Strict spool boundaries remain in
force; references/use between spools require explicit user authorization.

The user authorized copying code, independent review and merging without a
further checkpoint. The source repositories remain intact. **Ralph is excluded**:
Auto-run supersedes it. Millstrand core, Millstrand UI, Agents, Notes, Dresser,
and other external consumers stay separate. No database, session history,
secret, machine-local workspace setting, or deprecated agent-harness code is
imported. No Weaver restart, global plugin install, repository deletion, or
GitHub archival is part of this change.

## Import record

Files were copied from clean tracked snapshots, with modes preserved. History is
not rewritten or grafted: the source repositories retain the original commits,
issues, and board records. Mechanical copies were byte-checked before integration.

| Source | Snapshot | Destination |
| --- | --- | --- |
| [Harnesses](https://github.com/codethread/harnesses.spool) | `a67c8bfac1b2e11e0d05e8ab8b8db577d51040f4` | `spools/harnesses` (154 package files before integration) |
| [Devflow](https://github.com/codethread/devflow.spool) | `d335e62f456f51710bbfeda1eb321927cda97e4b` | `spools/devflow`, including nested `kanban-adapter` (45 files before integration) |
| [Codethread](https://github.com/codethread/codethread.spool) | `1dfbc9361db70da54961aaa1740133acb802076c` | `spools/config`, shared docs, reusable skills, verification scripts |
| Millhouse baseline | `114ccb0f870c60eeebfaf60739aabd21f6413f7f` | Existing packages retained |

Integration preserves namespaces and coordinates. The root catalog lists all
packages; Harnesses also retains its package-local `spool.edn`, used to locate
the maintenance Cursor plugin relative to its own package rather than the
monorepo. That manifest is a package boundary, not a second workspace.

## Dependency and activation boundaries

Internal Git pins are replaced by relative checkout-local dependencies. Each selected package resolves inside its developer or published Git checkout.
For multiple published roots, use the generated direct dependency closure below. Millstrand remains external, pinned to
`34f940ddb2e69898554bf76251749715b250ae15`, the Millhouse baseline's upstream.

Existing production relationships are preserved:

- Workflow, Chime, Cron, and Identity depend only on external core libraries.
- Kanban uses Identity; Land uses Kanban and Workflow.
- Harnesses uses Kanban and Workflow. Provider activation remains explicit.
- Auto-run uses Harnesses, Kanban, Workflow and Land; Auto-review uses Workflow.
- Devflow uses Workflow, not Kanban. Its optional adapter is the explicit bridge;
  consumers also select Devflow when activating that adapter.
- Opt-in Codethread config composes the existing catalog and optional workflow
  election; it is not a mandatory dependency of the other packages.

`millhouse.package-layout-test` records this production graph and checks every
internal manifest coordinate, including test aliases, resolves to the declared
local package root. A new relationship needs explicit authorization, not merely
an edit to that test. Neither dependencies nor namespace loading imply runtime
activation. The repository workspace selects its modules explicitly and no
longer selects Ralph.

## Tests, documentation, and skills

- Preserve package-local tests, resources, manifests, and classpaths. The root
  gate orchestrates imported suites in separate processes rather than placing
  all tests on one giant classpath. Existing Millhouse integration tests retain
  their runner and serial islands.
- Centralize dogfood workspace tests and landing policy at the Millhouse root.
  Do not copy three competing `.millstrand` configurations. Config's disposable
  activation/provenance tests and Millhouse's auto-run tests cover composition.
- Keep Devflow's semantic card-authoring equivalence check. Retire its old
  `compat-alarm` from this distribution: it reads Devflow's historical `v20` tag,
  not Millhouse history. The original script/history remain in the source repo.
- Package READMEs and authoring resources stay with their packages. Shared
  ecosystem procedures live in `docs/processes`; `docs/reports` remains dated
  historical evidence, not current operating policy.
- Deduplicate equivalent Clojure, Clojure-review, coordinator, and park skills
  using Codethread's versions. Keep its groom/voice-orchestrator skills and
  Harnesses' unique testing/writing-spools skills. Retain the existing Millhouse
  documentation skill. Update discovery paths to the consolidated source owner.
- Keep Harnesses' own JS lockfile and native plugin packaging. No mandatory
  monorepo JS package or merged plugin runtime is introduced.

## External consumers and native plugins

Consumers now pin `https://github.com/codethread/millhouse.spool.git` at one tested
revision and select the existing library names using these new roots:

| Coordinate | `:deps/root` |
| --- | --- |
| `ct.spools/harnesses` | `spools/harnesses` |
| `codethread/devflow` | `spools/devflow` |
| `codethread/devflow-kanban-adapter` | `spools/devflow/kanban-adapter` |
| `codethread/config` | `spools/config` |

For multiple Git roots, use `scripts/consumer-deps.sh SHA LIBRARY...` from the
checkout of that revision. The generator traverses only declared production
edges and emits the selected closure as direct Git dependencies. tools.deps uses
per-library Git checkouts, so transitive local roots from different checkouts
cannot be compared even at the same SHA. Direct dependencies win over those
transitives. This was reproduced by the published smoke; promoting the selected
closure resolves it without restoring internal Git pins or coupling optional
packages. See the [tools.deps expansion contract](https://clojure.org/reference/dep_expansion).

Existing Millhouse package roots are unchanged. Do not reuse a pre-consolidation
tag for an imported coordinate. Published verification starts in a temporary
consumer with remote Git coordinates, rejects old sibling sources on the
classpath, checks every package's resolved revision, activates the published
workspace in disposable worlds, and installs native Pi/Codex plugins into
isolated temporary homes. Optional consumer paths exercise their unchanged init
and workspace modules against the candidate; Millstrand UI remains separately
maintained and its live pins are not modified by this smoke.

Install Pi and Codex integrations from `spools/harnesses` after checking out the
tested Millhouse revision. The plugin remains separately installable; the
monorepo root is not a Pi package or Codex marketplace. See the Harnesses README.

CI runs the full root gate (isolated package checks plus composition) and the
published-revision/native-install smoke. Local focused targets are package-aware;
shared Land remains the only merge path, with independent review and FIFO turn.
Full suites use the shared lock; all workspace-backed fixtures are disposable.

## Board transition and active-work inventory

The original request is Codethread card `7662z`; Millhouse delivery card `gst5p`
is its explicitly linked source-work owner. No databases are merged. New source
work goes on the Millhouse board with component labels. Original cards, notes,
claims, receipts, and run history remain on their original boards.

Read-only inventory at import time found:

- Codethread's sibling rollout remained claimed, with the dogfood consumer slice
  pending and the BOM policy in refinement. Their live-runtime blockers are not
  resolved by this source migration; preserve those cards and handoffs.
- Harnesses' native-hook rollout remained claimed with a failed landing-quality
  gate and no live worker. Do not infer success from its assigned receipt.
- Devflow had a pending consumer rollout slice. Millhouse had pending source
  work but no active worker or dispatch receipt for this migration.
- Several historical failed runs and a stale Codethread ready run existed. They
  are not this migration's resources and are not reconciled or cancelled here.

Full inventory and exact card references were recorded on the delivery feature;
this document intentionally does not copy runtime databases or agent transcripts.
Owners finishing old source branches must compare against the imported snapshot,
then explicitly hand off remaining source changes to a labelled Millhouse card.
Do not silently close old work or run two active implementations of it.

## Cutover sequence

1. Record snapshots, active work, layout, dependency graph and authorization.
2. Copy tracked package source; integrate paths, package checks, docs and skills.
3. Verify package tests, disposable workspace composition and published candidate
   installation. Obtain independent review and merge through shared Land.
4. Direct **new development** to Millhouse. Old repositories stay present as
   provenance and active-runtime handoff records; no archive/delete is performed.
5. External consumer owners may explicitly update their pins and native plugin
   installations to a tested revision. Any live activation/restart is a separate
   authorized operation, never implied by this source delivery.
