# Kondo and LSP rollout

2026-09-13. Epic `qnmh1`, coordinator `alri9`, dependency propagation `ytciv`. Implementation and investigation were delegated to Terra and Sol agents; the coordinator reviewed changes and validation evidence before publication.

## Recommendation and shared procedure

Keep macro support with the package that owns the macro. Its `resources` classpath exports travel through `deps.edn`; consumers explicitly refresh them with `make kondo-import`. Runtime module activation is a separate concern. The old card `dce5t` was superseded: restoring the retired bump workflows or centralizing producer mappings in Codethread is unnecessary.

The maintained contract is [Clojure lint and editor configuration](../../processes/kondo-and-lsp.md). Codethread's [documentation index](../../README.md) establishes the shared process home. Each active sibling README links to the procedure, and each repository implements it according to its independent package roots.

## Delivered scope

Only Codethread, Millstrand (`skein-src`), Millhouse, Harnesses, and Devflow are included. Deprecated and experimental repositories were excluded when the user clarified scope. Their isolated work was not landed, and this rollout does not restart their workers or the unrelated shared development pool.

| Repository | Analysis roots | Resulting behavior |
| --- | --- | --- |
| Codethread | Workspace, Config, Ralph | Root Make dispatches to three independent consumers; Config imports the updated Harnesses export. |
| Millstrand | Root, workspace, Batteries, Unsafe Text Search | Existing public macro exports proved with real consumer forms; ordered import/lint and existing strict quality gates retained. |
| Millhouse | Root, workspace, Chime, Cron, Identity, Kanban, Workflow | Each package resolves its own basis; Chime, Cron, and Workflow retain ownership of their exports. |
| Harnesses | Root and workspace | Added the missing public `assignment/def-assign-policy` mapping; reviewer exports retained. |
| Devflow | Root, Kanban adapter, workspace | Adapter declares its own dependencies; generated imports are ignored throughout the repository. |

Every root Makefile exposes `kondo-import`, `kondo-lint`, and `kondo`. Imports complete before source lint under parallel Make. Resolver and nested Make failures propagate. Private test hooks stay local; pure consumers do not publish empty or duplicated macro configurations.

## Verification

The coordinator ran `make -j8 kondo` on all five published main checkouts after dependency propagation: all completed with zero Kondo errors and warnings. Every workspace imported the Harnesses assignment mapping from the effective dependency graph. The final tracked-file inventory confirms zero tracked generated imports across all five repositories. Millstrand’s three remaining generated files were removed from tracking after the final audit.

| Repository | Accepted test and quality evidence |
| --- | --- |
| Millstrand | 794 Clojure tests, 4,911 assertions; focused consumer checks 12 tests / 86 assertions; Go, E2E, formatting, strict lint, reflection, documentation and CI checks passed. |
| Millhouse | 395 tests, 2,982 assertions; focused consumer checks 3 tests / 34 assertions; ordinary quality passed. |
| Codethread | Config 5 tests / 41 assertions; Ralph 1 test / 11 assertions; Go formatting, vet, tests and build passed. |
| Harnesses | 77 tests, 472 assertions; ordinary check gate and Splint passed. |
| Devflow | Root suite 21 tests / 243 assertions and independent adapter suite 5 tests / 44 assertions passed; these suites may overlap. |

Focused consumer checks are included where relevant; do not add these figures to the broad suites as if they were necessarily distinct tests.

Headless clojure-lsp checked source/test/package bases and all five actual workspace bootstraps with zero errors and warnings. Informational findings remain reported separately. Positive diagnostics were paired with an unresolved sentinel appended to the actual `init.clj` in disposable copies.

Independent review found that `--filenames` alone can silently omit a file outside LSP source discovery. Explicit workspace source settings fixed this for deps-only workspaces; Millstrand already declared the necessary source path. The final negative checks reported `unresolved-symbol` for the sentinel. Preserve `.lsp/config.edn` when making disposable copies: removing it removes the source-discovery fix itself.

Tool versions were native clj-kondo `2026.08.04`, clojure-lsp `2026.07.06-14.34.19`, and its bundled clj-kondo `2026.05.26-SNAPSHOT`. These are distinct analyzer builds. Existing tools.deps external test-path deprecation messages in Millhouse are not Kondo diagnostic warnings.

## Published revisions and runtime evidence

The machine-readable [coordination record](coordination.json) records accepted revisions, tasks, and runtime checks. Dependency publication was ordered: Harnesses export `e8a26477852216bca2579b050a2356c86af132b7`, then Codethread Config `8f7d99354fef66b43693fcb2002c6ccdd5001267`, then the four sibling workspace pins. Harnesses keeps its intentional self-local dependency. Other compatible library pins were retained.

Initial supported restart probes exposed a separate Millstrand startup defect: the candidate dependency basis contained Codethread’s bootstrap, but startup evaluation used only the thread context loader while the outer evaluation already bound `Compiler/LOADER`. The resulting namespace-not-found exception prevented replacement. The failed probes kept all old generations serving. A focused core correction reuses the existing helper that binds both loaders; its regression exercises a startup `require` from a candidate-only dependency.

The correction is published as `0ce3725ee09f29e690623a10f1d86640af1d92ad`. The regression failed before the change; all 62 startup tests / 342 assertions passed afterward. A disposable Harnesses start and supported restart also passed before the shared-worker retries.

All five supported replacements then completed successfully. Each has a new PID and generation, and live `help`, `workflow list`, and `agent list` checks passed. `publish-spool-kondo` appears in every live workflow catalogue. The three other-rollout agents active at cutover stayed running with unchanged process handles, keys, attempts, and native sessions.

| Repository | Validated code/config revision | Old → new worker PID |
| ---------- | ------------------------------ | -------------------- |
| Codethread | `8f7d993`                      | 27566 → 53884        |
| Millstrand | `0ce3725e`                     | 67258 → 53498        |
| Millhouse  | `f3e2b726`                     | 17779 → 53316        |
| Harnesses  | `99d9a304`                     | 84016 → 51854        |
| Devflow    | `0c00b9c`                      | 25900 → 53105        |

These revisions are published on their remote main branches. The subsequent Codethread report commit changes documentation only. Full generation IDs, basis fingerprints and catalogue results are in [worker evidence](workers-after.json); the final Kondo/import/documentation checks are in [main checks](final-main-checks.json). Worker evidence is a dated snapshot: another coordinated rollout may later advance these repositories and replace their workers again.
