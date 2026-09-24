# Changelog

One entry per release marker, newest first. Markers are annotated, ordered
`v<int>` tags; consumers pin the **peeled commit sha** recorded with each
entry, never the tag object. From `v1` published names are accretion-only —
breaks are the exception and each one is recorded here with its rationale,
its `bin/compat-alarm` result against the previous marker, and who authorized
it. (Older tag messages reference `release-exception.md` / `release-v16.md`;
those records are folded into this file.)

## v23 — tools.deps roots

Peeled sha: `90799b8c950b4509167137562fbf18853524d41c`

Devflow and its Kanban adapter use ordinary tools.deps coordinates and explicit module activation.

- `codethread/devflow` resolves from `https://github.com/codethread/devflow.spool.git` at `90799b8c950b4509167137562fbf18853524d41c`.
- `codethread/devflow-kanban-adapter` resolves from the same repository and commit with `:deps/root "kanban-adapter"`.
- Millstrand: `io.millstrand/millstrand` at `71c0ed3d80fcad090b74a704a8eb165a3fad996e`.
- Millhouse Workflow and Kanban: `f487eb42ea9523e8bd405e64a7c319013217d988`.
- The root test suite and published card-authoring equivalence proof resolve those coordinates directly. The adapter remains the only root that depends on Kanban.

## v22 — selectable authoring on the pinned Millstrand and Millhouse APIs

Peeled sha: `3a96415df0429c245191a22e66cc0bfc91524199`

The release keeps the Devflow domain and repository identity unchanged while pinning its published proof to the Millstrand selectable-authoring commit and the Millhouse Workflow commit consumed by the test and equivalence gates.

- Millstrand: `io.millstrand/millstrand` at `3bbe5dc15359975a8e8203ef47b3a7514177e75b`.
- Millhouse Workflow: `millhouse.spools/workflow` at `f1cdda3b46706b186f547251d285791be650d232`.
- Kanban remains at `v24`, peeled SHA `87f61bc2750e7026f3650235907db25f19b1536e`.
- The vendored Millstrand clj-kondo config and hook match the producer-owned exports at the pinned core commit.

## v20 — proposal guidance shows the change; query Vars match their registered names

Peeled sha: `b18b326fca39a513abdaa91a132c9c64fa4c4b2e`

Accretion under published names: no stage, query, attribute, or behavior
changes. Three source Var renames match Vars to the names they already
register, and the adapter root's kanban floor rises with kanban's own rename
release.

- The `proposal` guide gains an Examples section. It asks for the surface
  under sign-off in whatever medium carries it — command-line invocations
  with their output, request and response payloads, a UI usage snippet plus a
  mockup or wireframe, a mermaid diagram for a flow or state machine — and
  frames those examples as contract illustrations that freeze with the rest
  of the document at approval, not as implementation strategy. Exact flags,
  validation rules, and field-level API detail stay in the scope clauses and
  the spec deltas.
- The proposal template gains `PROP-<name>-<nnn>.P5 Examples`, moving Open
  questions to `.P6`. The template numbers the sections a new document starts
  from; documents already written keep their IDs.
- The guide's Write procedure, constraints, and validation checklist name the
  section, so agent review and sign-off can check it.

The `rfc`, `spec`, and `plan` guides and their templates are untouched, as is
the shared document-ownership table.

- The three `skein/defquery` Vars drop their `-query` suffix (`devflow-runs`,
  `devflow-ready`, `devflow-tasks`). Skein strips that suffix at registration
  today and PROP-Rgs-001.S6 removes the stripping, so the source name now
  matches the registered name; the public query names are byte-identical
  before and after, under both current and post-change Skein.
- The adapter root's `codethread/kanban` floor rises `v22` → `v23`, kanban's
  release of the same estate-wide rename (`spool.edn`, the adapter README,
  and the deps.edn test pin move together). Nothing in the adapter needs
  `v23`; the raise keeps the proven pairing on the release the estate ships
  together.

Compatibility alarm: `bin/compat-alarm v19` cannot run as written — its
`-Sdeps` omits `resources` and the adapter root's kanban pin, so the archived
suite fails to load `ct.spools.devflow-kanban-adapter-test`; that is the known
classpath artifact already recorded under v16 and v17, not this change. Run
with both restored, v19's suite is green against this candidate (13 tests, 105
assertions). The current suite is green at 14 tests, 112 assertions.

Authorization: kanban card `cczly` (task `6iqw3`) — add generic rich-examples
guidance to the proposal guide, deliberately scoped to that one guide
(2026-08-03) — and the approved defquery-unsuffix estate release plan
(PROP-Rgs-001.S6, human approval recorded 2026-08-03) for the renames and
the floor raise.

## v19 — adapter drops kanban's removed tracker seam

Peeled sha: `053d3b9a37bdedc1ea4e605b8df17410fa014486`

**Deliberate break under published names, adapter root only.** Kanban `v22`
removed its run-tracker seam (`kanban/set-tracker!` and the card-view tracker
join): the board keeps `kanban/run-id` as an opaque pointer and workflow
systems own run state. The adapter's write side of that seam goes with it:

- `devflow-projection` and `bind-devflow-tracker!` are removed. The `v18`
  adapter cannot compile beside kanban `v22` (it references the removed
  `set-tracker!` var), so there is no compatible pairing to preserve.
- Consumers drop the tracker lifecycle seed along with the bump; the
  `repoint-decompose!` seed and the rest of the adapter surface are untouched.
- The adapter root's `codethread/kanban` floor rises `v20` → `v22`
  (`spool.edn`, adapter README, and the test pin move together).

Compatibility alarm: `bin/compat-alarm v18` fires — the archived v18 adapter
suite fails to compile against the candidate (`No such var:
adapter/bind-devflow-tracker!`); that is this break being caught. The current
suite (13 tests, 105 assertions) is green against the kanban `v22` pin.

Authorization: the user's instruction to resolve the blocked kanban `v22`
adoption by shipping the devflow half of the tracker-seam removal (2026-08-03);
the kanban half landed as kanban.spool#14.

Consumers of the main `codethread/devflow` root are untouched.

## v18 — structure-agnostic card review; kanban adapter root

Peeled sha: `031bc04c288c0ac1e614d5a4f9442a8c87b5bd1d`

**Deliberate break under published names.** The cards route drops the
epic/feature card taxonomy — an external card-system shape that had leaked
into the core contract. Card-set structure now belongs to the caller:

- `review-cards` (and `::card-set-input`, the `:review` / `:review-again`
  choice input) takes one flat `:cards` vector of distinct refs. How a
  workspace groups cards (an epic, a parent strand, nothing) is its own
  convention, and the set-level cohesion review judges the supplied set
  rather than a designated epic card.
- Reviewer seats rename: `:feature-card-reviewer` → `:card-reviewer` and
  `:epic-card-reviewer` → `:card-set-reviewer` (required by `land-proposal`
  and `decompose`, optional at intake).
- Review gates rename: `feature-card-review` → `card-review` and
  `epic-card-review` → `card-set-review`; `devflow/review-scope` values are
  now `"card"` / `"card-set"`.
- Guidance and docs speak the card/card-set vocabulary throughout; no
  external card system's shape or name appears in the main root.

Compatibility alarm: `bin/compat-alarm v17` fires — 1 error
(`decompose-stage-defers-card-authoring-to-a-pluggable-target` starts the
stage with the old seat params, which the new `::decompose-params` rejects;
that is this break being caught) and 3 errors ("Missing devflow guidance
resource": the alarm classpath predates resource-path handling, the same
artifact recorded under v16 and v17).

Authorization: the user's instruction that the published devflow root must
not reference the kanban spool and that `review-cards` must not assume
epic/feature structure — the caller decides grouping — with the
kanban-specific shape moving to a shipped adapter root (2026-08-02).

Accretion:

- New root `codethread/devflow-kanban-adapter` (`kanban-adapter/`): the
  devflow↔kanban binding, so consumers of both stop re-inventing the same
  glue — the `author-kanban-cards` defer target (epic + feature cards on the
  board), the `decompose-kanban` bound variant, the `devflow-projection` /
  `bind-devflow-tracker!` tracker glue, and `repoint-decompose!`. Floors:
  `codethread/kanban` ≥ `v20`. Consumers of the main root are untouched;
  opt in by mapping the root and declaring the floor.

Fixes:

- `spool.edn` uses the validated map `:roots` form under the
  `codethread/devflow` coordinate every consumer actually approves (the
  bare-string form under `ct.spools/devflow` failed `strand spool add`).
- README's engine-pinning tip replaces the retired `:deps/root` key with a
  `:roots` mapping.

## v17 — pluggable decomposition over `workflow/defer`; tasks.yml retired

Peeled sha: `6810b5259dff23e291a2bf46071c0680cf9731b4`

**Deliberate break under published names.** The two decomposition points are
now `workflow/defer` selection points, and the `tasks/index.yml` file format
is removed outright:

- `tasks` replaces its `write-tasks` step with an `:author-tasks` defer;
  `decompose` replaces `author-cards` with an `:author-cards` defer. Both are
  bound to shipped strand-native targets (`author-task-strands`,
  `author-card-strands`), and the unbound templates (`tasks-open`,
  `decompose-open`) are published Vars so consumer code can bind its own
  card/task systems (issue trackers, ...) and re-point the stage
  names. Devflow deliberately ships no binding to any external system.
- Tasks are strands, not files: `devflow/task-type` (`afk`|`hitl`),
  `devflow/feature`, `depends-on` edges, `hitl=true` on HITL tasks, closure as
  completion. The `tasks/index.yml` schema, its template, and the per-task
  markdown files are gone from the guidance; the task body contract survives
  as the `task-strand.md` body template. The pre-Skein YAML queue existed
  because nothing could manage the graph; Skein can, so keeping a parallel
  file format would preserve a shape nobody wants maintained.
- `workflow/artifact` values change accordingly (`"tasks/index.yml"` →
  `"task strands"`, plus `"implementation cards"`).

Compatibility alarm: `bin/compat-alarm v16` fires — 1 failure
(`module-publishes-the-complete-devflow-workflow-catalogue` asserts the exact
v16 registration set, which the two new target names grow) and 3 errors
(v16's guidance tests; classpath artifact — the alarm classpath predates
resource-path handling, as already recorded under v16). That is this break
and the known artifact being caught, not regressions.

Authorization: the user's explicit instruction to make decomposition
pluggable via `workflow/defer`, drop the tasks.yml format completely, and
recommend a strands-based default with no coupling to any external card spool
(2026-08-01).

Known consumers: skein-src's devflow pin and this repo's own `.skein` world;
both move deliberately with the pin.

Accretion:

- New call-only definitions `author-task-strands` and `author-card-strands`;
  new published template Vars `tasks-open` and `decompose-open`.
- New named query `devflow-tasks`: the open strand queue with `strand list`,
  the runnable frontier with `strand ready`.
- Guidance: `tasks` and `afk` rewritten for the strand vocabulary;
  `decompose`, `overview`, `plan`, and `finish-archive` updated to match;
  new `task-strand.md` body template.

## v16 — guidance served as markdown over `strand devflow guidance`

Peeled sha: `1455dce2d65a6f81eea21a5aac2d8cac26e442bd`

**Deliberate break under a published name.** `(ct.spools.devflow/guidance)`
and `(guidance <key>)` now return markdown strings instead of EDN map trees.
The map shape was judged to have no value over prose for its one consumer
class (agents reading authoring rules), and keeping a map-returning twin
alive would preserve a shape nobody wants maintained. The knowledge itself is
unchanged; it moved verbatim into `resources/ct/spools/devflow/guidance/*.md`.

- Compatibility alarm: `bin/compat-alarm v15` fires (1 error in
  `guidance-and-artifact-metadata-remain-devflow-owned`). That is this break
  being caught, not a regression: the frozen suite asserts the old map shape
  and the old REPL-call instruction text, and its classpath predates the
  spool's `resources/` path.
- Authorization: the user's explicit instruction to convert guidance to
  markdown and release (2026-08-01).
- Known consumers: skein-src's devflow pin and this repo's own `.skein` world
  (`:local/root`). Neither reads the guidance value structurally; both move
  deliberately with the pin.

Accretion:

- New `devflow` op: `strand devflow guidance [<guide>]`, a read-only static
  surface registered by the existing `:devflow` module activation. No run
  verbs; the generic `workflow` op remains the only run driver.
- `deps.edn` `:paths` gains `"resources"`; the spool loader honors declared
  paths, so consumers need no config change beyond the sha bump.
- Step `workflow/instruction` text now names the CLI command instead of the
  Clojure call. `devflow/guide` attribute semantics are unchanged.

## v15 — expose lifecycle definitions directly

Peeled sha: `ea9b3f00d505f8ec76ab6bb9216d1b20560c5210`

**Breaking:** remove Devflow's runtime facade. Start and drive intake and its
routed stages through Skein's generic workflow API and CLI instead.

Devflow now publishes `devflow-runs` and `devflow-ready` queries for
resumable runs and actionable work. The workspace activates the workflow and
batteries CLI modules to expose those surfaces.

Validation: current suite passed (6 tests, 51 assertions); real-weaver CLI
smoke test passed. `compat-alarm v14` fails as expected because v14's suite
calls the removed facade.

## v14 — reviewed card decomposition and authored Workflow activation

Peeled sha: `179efcc8531379e8ae6626348bfdbe028d7a1f90`

Contract change under published names: the `approved-to-cards` route now
requires `feature-card-reviewer` and `epic-card-reviewer` parameters, then
fans focused card reviews into an epic cohesion review before accepting the
decomposition. The new `review-cards` stage and card-review guidance are
accreted.

Compatibility fix: fresh test worlds activate the Workflow dependency from
source so authored declarations are collected before publication; this
supports Skein's forms-only Workflow module without an image callback
fallback.

`compat-alarm v13`: one failure and one error, both classified to the
card-review contract change above. v13's frozen stage list rejects the
accreted `review-cards` stage, and its old `approved-to-cards` call omits the
new reviewer parameters. The suite passed 35 tests and 240 assertions against
Skein main and the forms-only Workflow candidate.

## v13 — cards route (`approved-to-cards` → `land-proposal` → `decompose`)

Peeled sha: `c056282702c14feba84ea6b1538cdcc0f3043734`

Accretion under published names: new sign-off choice `approved-to-cards`, new
stages `land-proposal` and `decompose`, new `:decompose` guidance guide. The
old `approved` → spec-plan/AFK path is registered intact and sidelined.

`compat-alarm v12`: one classified failure, zero errors. v12's frozen
`route-symbols-cover-every-published-stage` pins `stage-workflows` keys
against its closed-world 11-entry list, so any accreted stage trips it;
classified as accretion per writing-shared-spools (no published name changed
behavior; the frozen `:approved` → `:spec-plan` assertion stays green).
`approved-to-cards` targets `land-proposal` by definition symbol so the
already-published proposal definition demands no new registration-set entry
from existing consumers.

## v12

Peeled sha: `8512b6a01891a2a5e7a4f440056672de0d9f1cb6`

No release notes were recorded for this marker.

## v11

Peeled sha: `c4a2dabc02c8649f5d3a9f71998baaf6fc909712`

No release notes were recorded for this marker.

## v10 — returning stage composition (defer-return cutover)

Peeled sha: `c77486955825e5d4918a4928914578cad61ed08f`

- Skein floor: the tested Skein checkout's HEAD must be
  `70a3c50e27ca0190f363d80d0b0cac72948dbacb` or a descendant. That merge
  removes the old `continue!` worker surface and makes defer runtime-selected
  returning composition whose target advertises `:call`. No Skein release
  marker contains that commit, so `:skein/min` cannot express the requirement
  and this release added none.
- Devflow contract: every lifecycle stage reached by authored checkpoint
  `:next` continues to advertise `:continue`, because Skein deliberately
  retains that entrypoint for root transfer. Those stages now also advertise
  `:call`, so fixed calls and runtime-selected defers may execute them as
  returning procedures. `intake` remains the `:start` definition and
  `agent-review` remains a call-only procedure. Registered names, checkpoint
  routes, parameters, stage molecules, and user-visible lifecycle behavior
  were unchanged.
- Engine cutover: this release must be installed only after the workspace
  follows Skein's `docs/spools/defer-return-cutover.md`. The clean break is
  in the workflow engine's removed worker operations and persisted defer
  meaning; it does not delete devflow's authored checkpoint-transfer
  capability.
- Compatibility alarm: `bin/compat-alarm v9` passed. The devflow change is
  additive under its published names, and the frozen v9 suite exercises the
  retained checkpoint routes against the candidate source.
- Authorization: the user's explicit defer-return rollout instruction.
- Decision: no false `:continue` removal. Replacing it with `:call` would
  make every checkpoint `:next` reference fail registration under Skein
  `70a3c50e`; advertising both entrypoints preserves the stage graph while
  adding returning composition.

## v9

Peeled sha: `499c9d6c51f28cd3a5d6de28718df082118ff4cc`

No release notes were recorded for this marker.

## v8 — static defworkflow stage definitions

Peeled sha: `980961cf0d0d730741d5ba65330f589dfcb1d88d`

**Breaking.** The nine stage constructor fns and
`ct.spools.devflow/contribute` are removed, along with the `reconcile` and
`spool` vars: devflow's module contribution is now the entries its
`defworkflow` forms collect, and a module may not both collect authoring
forms and supply `:contribute` (skein SPEC-004.C46).

Every stage is a static Var named for its route, with a whole-map
`:param-spec` and `:defaults`, and both checkpoint choice inputs name a spec
rather than a per-key vector. The AFK stage's manual-vs-delegated decision is
now a checkpoint choice (PROP-Wcd-001.EX6) across `run-afk-loop`,
`run-afk-manual` and `run-afk-delegated`.

Skein floor `ae0888433f369dbd314ac7ab33d9d275748750f3` or a descendant.

## v7 — engine outcome cutover

Peeled sha: `20c2850dca7918810aa276f2f2dd1f484dc9fe7b`

Adapts to the workflow engine dropping `:notes` from `complete!`/`advance!`.
Stage outcome vocabulary rides `:attributes`. Compatible with Skein before
and after the cutover; the v6 compat alarm fires on the engine's removed
argument, not on a devflow contract change.

## v6 — adopt the public def spool convention

Peeled sha: `7e86aa9d65546b7ca795202411e464bde5b39baf`

## v5 — retire install!; module lifecycle is the one activation path

Peeled sha: `98ecdd8a2fe15e4deebc83ec94596337162b46a1`

## v4 — test-only compatibility release for the install! retirement epic

Peeled sha: `7135d8c296ec712ff48ec8cc48c5ff0e058e2088`

Converts the suite's workflow activation from
`skein.spools.workflow/install!` to image-mode `module!` so the suite passes
pinned against skein-src with the in-tree installers deleted. No `src/`
changes; exports unchanged (compat-alarm vs v3 clean). The full devflow
install! retirement shipped in the next marker.

## v3 — owner-scoped live refresh

Peeled sha: `b966e540624a19c547093b6f2468fa3bb5d71103`

## v2 — register stage workflows under a runtime, not at ns-load

Peeled sha: `1e65e1bc5ce43cbea462a33f51b24b669928ef4b`

Skein's workflow registry moved to runtime-owned spool-state, so
`register-workflow!` now needs an active runtime. Drop the namespace-load
registration (no runtime exists there) and register only from `install!`;
the test fixture registers per disposable world so named `:next` routes
resolve against the runtime-owned registry.

## v1 — first promise

Peeled sha: `9b0296a37b7ad8968c4630bbe676c3a4a0cf5df5`

Accretion-only under these names from here; breaks ship as new names.
