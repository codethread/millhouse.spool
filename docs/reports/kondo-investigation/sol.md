# Sol investigation: disposition of dce5t

Date: 2026-09-13 Task: `7m8ne` Original card: `dce5t`

## Recommendation

Do not implement `dce5t` as written. Supersede or rewrite it as a narrowly scoped maintenance item only if the team wants to align stale dependency pins.

The card combines three concerns that now have different answers:

1. **Packaging/import:** producer-owned clj-kondo exports in each spool root are still the correct contract. `src` plus `resources` in `deps.edn` makes the export discoverable on the resolved classpath; clj-kondo still requires an explicit `--copy-configs` import. This does not require a Codethread runtime.
2. **Runtime activation:** dependencies never activate Millstrand modules. The Workflow engine and the convenience provider module remain explicitly activated. Current standard workspaces already do this, but each activates Millhouse's owner namespace directly rather than receiving it through a special Codethread exposure layer.
3. **Release coordination:** `bump-spool` and `bump-millstrand` were intentionally retired by the deps-native migration. Only the publisher checklist `publish-spool-kondo` remains useful, and Millhouse still owns it.

Mandating Codethread as the single runtime exposure point is not justified now. It would add indirection without changing resource discovery, clj-kondo import, or ownership. It also would not guarantee a common source revision: tools.deps resolution is determined by the complete consumer basis, and Millstrand already has a direct, divergent Millhouse Workflow pin.

## Original intent and chronology

`dce5t` was created on 2026-08-11. Its expanded requirement expected three workflows and a manifest-era coordinated bump/import path. Both the card and the original reusable workflow work predate the August 30 deps-native migration. Commit timestamps alone do not establish when a change landed.

Key history:

- Millhouse `a1eaa4bd79af4a2c2894d4fa76730078c80a4925` (2026-08-11 11:29 +0100), `feat: publish reusable clj-kondo workflows (#5)`, introduced the reusable publisher and bump workflows and consumer proof.
- Codethread `26fdf691649e8c92ae6366c627e0a77cd8219549` (2026-08-11 20:01 +0100), `feat: bootstrap producer-owned Kondo config`, adopted the producer workflow/import and deleted Codethread's duplicate `spool-bump`.
- Millhouse `f1cdda3b46706b186f547251d285791be650d232` (2026-08-25) consolidated workflow spools.
- Millhouse `f487eb42ea9523e8bd405e64a7c319013217d988` (2026-08-30), `feat: migrate Millhouse to deps-native spools`, explicitly retired obsolete manifest workflows while retaining `publish-spool-kondo`; it deleted `bump_spool.clj`, `bump_millstrand.clj`, bootstrap/consumer tooling, and their old API material.
- Codethread `356841d810cac6408cc4fb3cf6cca0094562d28e` (2026-08-30), `feat: cut over Codethread to deps-native spools`, replaced spool manifests with ordinary tools.deps roots while retaining explicit module activation.

Thus the card's core premise was overtaken by the August 30 deps-native design, not left accidentally unfinished.

## Current architecture

### Packaging and clj-kondo discovery

Millhouse Workflow publishes both source and resources:

- `/Users/ct/dev/projects/millhouse.spool/spools/workflow/deps.edn:1` declares `{:paths ["src" "resources"] ...}`.
- `/Users/ct/dev/projects/millhouse.spool/spools/workflow/workflow.cookbook.md:28-48` documents producer ownership, the `resources/clj-kondo.exports/<coordinate>/` layout, clean-classpath resource verification, and the remaining publisher workflow.
- `/Users/ct/dev/projects/millhouse.spool/spools/workflow/src/millhouse/spools/millstrand_workflows.clj:111-196` defines the remaining `publish-spool-kondo` checklist. It explicitly covers owner inspection, resource classpath, export, hooks, import boundary, tests, docs, and clean status. It does not edit or discover automatically.

Official clj-kondo documentation confirms the boundary:

- https://cljdoc.org/d/clj-kondo/clj-kondo/2026.08.04/doc/configuration#exporting-and-importing-configuration
- An export directory must be on the library classpath.
- Import happens only when a project has `.clj-kondo` and invokes clj-kondo with `--copy-configs`; the documented command is `clj-kondo --lint "$(clojure -Spath)" --copy-configs --skip-lint`.
- Imported configs are then activated at consumer discretion and are typically checked into version control.

Consequently, `resources` is necessary but not an automatic import trigger. Terra independently owns the packaging/import execution proof; this report does not duplicate that claim.

### Runtime discovery and activation

Millstrand's current contract is explicit:

- `/Users/ct/dev/projects/skein-src/devflow/specs/repl-api.md:161-179` shows an ordinary tools.deps coordinate followed by explicit `runtime/module!` and states: "A dependency does not activate code."
- `/Users/ct/dev/projects/skein-src/devflow/UBIQUITOUS-LANGUAGE.md:63-67` identifies a coordinate as availability and a module declaration as the activation unit.

Codethread's shared bootstrap activates only the base Workflow engine:

- `spools/config/src/ct/spools/codethread/bootstrap.clj:10-29` includes `:millhouse/spools-workflow` -> `millhouse.spools.workflow`.
- `.millstrand/init.clj:10-15` separately activates `millhouse.spools.workflow.spool` after the engine.
- `/Users/ct/dev/projects/millhouse.spool/spools/workflow/src/millhouse/spools/workflow/spool.clj:1-25` is the owner convenience module. It selects executor/query/op/lifecycle declarations and the sole workflow `workflows/publish-spool-kondo`.

All inspected standard coordination workspaces follow the same direct-owner activation shape after calling `codethread/register!`:

- Codethread `.millstrand/init.clj:10-15`
- Millhouse `/Users/ct/dev/projects/millhouse.spool/.millstrand/init.clj:15-23`
- Millstrand `/Users/ct/dev/projects/skein-src/.millstrand/init.clj:87-92`
- Harnesses `/Users/ct/dev/projects/harnesses.spool/.millstrand/init.clj:7-17`

Codethread's disposable-world contract test verifies the actual current surface: `spools/config/test/ct/spools/codethread/config_test.clj:121-144` expects exactly `intake`, `publish-spool-kondo`, and `ralph-iterate`. It does not expect either retired bump workflow.

## Actual pins versus current source

Read-only checks used `git cat-file -e <sha>^{commit}`, `git rev-list --left-right --count <pin>...HEAD`, `git branch -r --contains <sha>`, and `git ls-remote origin refs/heads/main`. The inspected local HEAD, `origin/main`, and advertised remote main agreed:

- Codethread: `252eeaee216a5e4d4e82c6b2948dd9eba1dafc9d`
- Millhouse: `1cca62b0b2c3014a0624611d24f5efb54d890339`
- Millstrand: `3c80a71083245743472e2cabef80532fc51c3c5b`

Load-bearing consumer pins:

- Codethread `spools/config/deps.edn:2-13` pins Millstrand `310368d...` and Millhouse Workflow/Identity/Kanban `f487eb4...`. The Workflow pin is 10 main commits behind Millhouse HEAD, but already contains the deps-native retirement and retained publisher workflow.
- Millhouse `/Users/ct/dev/projects/millhouse.spool/.millstrand/deps.edn:2-7,23-30` uses local Millhouse roots, pins Millstrand `310368d...`, and pins Codethread `252eeae...`.
- Harnesses `/Users/ct/dev/projects/harnesses.spool/.millstrand/deps.edn:2-17,26-30` pins Millstrand `310368d...`, Millhouse `f487eb4...`, and Codethread `252eeae...`.
- Millstrand `/Users/ct/dev/projects/skein-src/.millstrand/deps.edn:4-9` pins Codethread `252eeae...`, but directly pins Millhouse Workflow `3604419e406b3c426acd738bc2f0033cdb4edb25`. That Workflow pin is divergent: one pin-only commit versus five main-only commits and is contained by `origin/codex/land-withdrawal`, not main.

The direct Millstrand Workflow pin materially differs from current Millhouse HEAD in 14 Workflow files. This is a concrete pin-alignment issue, but it is not evidence that Codethread should become a central runtime facade. If current source convergence is desired, address explicit pins as ordinary dependency maintenance and test the resolved basis.

## Acceptance mapping

| dce5t requirement | Disposition | Evidence/reason |
| --- | --- | --- |
| Millhouse is sole implementation/publisher | **Implemented for the surviving workflow** | Owner definition and selection are in Millhouse; Codethread duplicate was deleted in `26fdf69`. |
| Codethread pins the landed root | **Implemented, stale but semantically adequate** | `spools/config/deps.edn:5-7` pins `f487eb4`, the deps-native retirement commit. |
| Codethread activates all three workflows | **Obsolete as stated** | `bump-spool` and `bump-millstrand` were deliberately deleted; current test expects only `publish-spool-kondo` from this family. |
| Activate after base Workflow | **Implemented directly** | `.millstrand/init.clj:10-15`; owner module selects the publisher at Millhouse `workflow/spool.clj:8-25`. |
| Millstrand/Millhouse consume workflows through Codethread | **Missing, but not useful** | They call Codethread bootstrap for shared base config and directly activate the Millhouse owner module. This keeps ownership visible and avoids a facade. |
| Remove duplicate workflow registrations | **Implemented/obsolete** | Codethread duplicate removed in `26fdf69`; old Millhouse bump registrations removed in `f487eb4`. No current duplicate definitions found across the four inspected repos. |
| Keep macro lint rules at owners | **Implemented architecture** | Producer resource roots and cookbook preserve owner exports; no reason to move mappings into Codethread. Terra verifies package contents/import. |
| Drive bump-spool/bump-millstrand repository flow | **Obsolete** | Those manifest-era orchestration workflows no longer exist after explicit deps-native migration. |
| Retain publisher obligations | **Still useful optional workflow** | `publish-spool-kondo` remains an agent-facing checklist for a macro-owning root. It is guidance, not required discovery machinery. |
| Exactly one resolved-classpath `--copy-configs` import | **Still useful verification, not runtime exposure** | Official clj-kondo import contract requires explicit invocation; package resources alone do not copy config. Terra tests this separately. |
| Same current workflow/export contracts everywhere | **Missing if interpreted as same SHA** | Pins are not all current and Millstrand's direct Workflow pin diverges. Prefer explicit pin maintenance; Codethread transitivity cannot guarantee one revision. |
| Run every repository's full quality at final main | **Out of scope/obsolete for this investigation** | Appropriate for a real pin or export change, not evidence for retaining removed workflows or adding a facade. |
| Document future repositories to use Codethread workflow surface | **Not recommended** | Document producer roots, explicit module activation, and classpath import instead. |

## Pragmatic disposition

1. Close/supersede `dce5t` as overtaken by the deps-native migrations rather than implementing its three-workflow and central-facade acceptance text.
2. Retain `publish-spool-kondo` in Millhouse as optional, owner-side authoring guidance. Do not copy it to Codethread.
3. Keep every macro mapping and hook under its producer root with `resources` on that root's classpath. Keep one explicit resolved-classpath `--copy-configs` step in consumer setup/quality where imported configs are required.
4. Continue explicit activation of `millhouse.spools.workflow.spool` in each workspace that wants the full provider surface. A dependency is availability, not activation.
5. If practical convergence matters, open a small dependency-maintenance task to explain or replace Millstrand's divergent `3604419...` Workflow pin and align stale pins after ordinary disposable-runtime and quality verification. Do not describe that work as central Codethread exposure.

## Representative commands and limits

Commands used were read-only:

```text
rg -n --hidden -g '!**/.git/**' \
  'publish-spool-kondo|bump-spool|bump-millstrand|millstrand-workflows' \
  <four repositories>

git -C /Users/ct/dev/projects/millhouse.spool log --all -S'bump-millstrand'
git -C <repo> cat-file -e <pin>^{commit}
git -C <repo> rev-list --left-right --count <pin>...HEAD
git -C <repo> ls-remote origin refs/heads/main
```

No source edits, workflow runs, shared Weaver changes, quality runs, version bumps, or publication were performed. Pin comparison proves repository/source state, not runtime cutover authority. Terra's independent report is the source for packaging/import execution evidence.
