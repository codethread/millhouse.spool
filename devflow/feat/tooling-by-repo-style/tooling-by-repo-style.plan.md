# Consumer tooling by repository style plan

**Document ID:** `PLAN-Tbr-001`
**Feature:** `tooling-by-repo-style`
**Proposal:** [proposal.md](./proposal.md)
**RFC:** none
**Root specs:** none
**Feature specs:** none
**Status:** Active
**Last Updated:** 2026-08-12

## PLAN-Tbr-001.P1 Goal and scope

Add the repository-style tooling contract approved in [the proposal](./proposal.md) to the Millstrand-workflows spool. Both spool and Millstrand bump paths will reach the same post-bootstrap, post-refresh agent choice and then run route-specific ordinary steps before cutover or pending-generation handover.

## PLAN-Tbr-001.P2 Approach

- **PLAN-Tbr-001.A1:** Add a focused namespace that owns a parent `configure-consumer-tooling` workflow and three `:continue` workflows for application, spool, and Clojure-application repositories.
- **PLAN-Tbr-001.A2:** Keep the parent selection as an `:agent` checkpoint. Build every route from `workflow/step` with waiter `:self`; do not add `workflow/gate` or an executor dependency.
- **PLAN-Tbr-001.A3:** Give every route the same visible phases: inspect effective world and repository conventions, align LSP, align lint and Kondo, align tests, prove the selected Weaver behavior, and hand over exact evidence. Each phase receives style-specific instructions.
- **PLAN-Tbr-001.A4:** Call the shared workflow after Kondo bootstrap and runtime refresh in `bump-spool` and the local Millstrand continuation. The pinned Millstrand continuation inherits the call through `bump-spool`.
- **PLAN-Tbr-001.A5:** Make cutover and pending-generation instructions consume the tooling result: an authorized cutover repeats the style-specific Weaver check, while a non-authorized path records that repeat check as unfinished.
- **PLAN-Tbr-001.A6:** Register all four definitions from the owner namespace so source refresh publishes an owner-complete workflow set.
- **PLAN-Tbr-001.A7:** Use stable choice keys and routes: `:app` selects `:configure-consumer-tooling-app`, `:spool` selects `:configure-consumer-tooling-spool`, and `:clojure-app` selects `:configure-consumer-tooling-clojure-app`. Choose `:app` when the product has no Clojure application source beyond Millstrand config, `:spool` when the repository publishes one or more spool roots, and `:clojure-app` when application Clojure source and Millstrand config coexist.

### PLAN-Tbr-001.P2.1 Route contract

| Phase | Application (`:app`) | Spool library (`:spool`) | Clojure application (`:clojure-app`) |
| --- | --- | --- | --- |
| Inspect | Record effective `spools.edn`/local overlay roots, refresh outcome, config files, and any existing project tooling without assuming a Clojure product. | Record effective roots, identify every owned spool root and its root `deps.edn`, and distinguish consumer approval from the author basis. | Record effective roots, the ordinary application basis, config sources, and existing dev/test aliases. |
| tools.deps view | Preserve `:paths []` or the existing non-Clojure product basis; expose Millstrand config, tests, Millstrand APIs, and required approved-root source only through development aliases. | Make owned spool source paths and tests visible in the author basis while retaining each root's own `deps.edn` and `spools.edn` approval as runtime authority. | Preserve the base application `:paths`/`:deps`; add Millstrand config, tests, APIs, and required approved-root source only through development aliases. |
| LSP | Select the Millstrand/test view explicitly and analyze config/tests plus required APIs with zero unresolved namespaces or diagnostics. | Select aliases/source paths that cover every owned spool root, config, and tests; confirm the committed LSP config is the one consumed. | Analyze application source in the base view and compose Millstrand/test aliases for config and config tests without hiding the base app. |
| Lint and Kondo | Import producer exports from the effective world, lint config/tests, and prove Millstrand authoring hooks were consumed. | Import Millstrand and sibling producer exports, lint owned spool/config/test namespaces, and reject self-import or duplicate consumer remaps. | Import producer exports and lint application, config, and tests while proving Millstrand macro hooks were consumed. |
| Tests | Run pure config tests and a disposable Weaver-world test that loads the workspace config and invokes an operation. | Run direct classpath/unit tests and a separate disposable-world test that approves the spool root and activates a `:spools`-guarded module. | Run ordinary application tests without Millstrand, then composed Millstrand config/runtime tests that reach application code. |
| Weaver | Against the selected workspace, record refresh/current/pending state and invoke a real configured operation. | Prove the selected workspace acquires the approved root, activates the guarded namespace module, and invokes a contributed operation. | Prove the selected workspace loads the config module and its operation reaches ordinary application code. |
| Evidence | Record source/classpath roots, changed config, exact commands and results, and whether pending generation blocks adopted-code proof. | Record owned/approved root identities, direct versus runtime test results, config, commands, and pending/adopted proof. | Record base isolation, composed view, changed config, commands, results, and pending/adopted proof. |

## PLAN-Tbr-001.P3 Affected areas

| ID | Area | Expected change |
| --- | --- | --- |
| PLAN-Tbr-001.AA1 | `spools/workflow` | New shared tooling workflows, bump composition, registration, docs, and API output. |
| PLAN-Tbr-001.AA2 | Millstrand-workflows tests | Pure definition and compile assertions for routing, ordering, route obligations, and absence of gates. |

## PLAN-Tbr-001.P4 Contract and migration impact

- **PLAN-Tbr-001.CM1:** Existing workflow parameters remain unchanged. Compiled bump runs gain an agent checkpoint and route-specific ordinary steps after refresh.
- **PLAN-Tbr-001.CM2:** Existing run definitions already poured before the update remain unchanged. New runs use the expanded workflow graph.
- **PLAN-Tbr-001.CM3:** `spools.edn` remains the runtime authority. Instructions may add or adapt tools.deps, LSP, Kondo, and test views without treating them as a second approval source.

## PLAN-Tbr-001.P5 Implementation phases

### PLAN-Tbr-001.PH1 Shared workflow contract

Outcome: registered parent and continuation definitions compile for all three repository styles with only agent-owned ordinary setup steps.

### PLAN-Tbr-001.PH2 Bump integration and handover

Outcome: spool, local Millstrand, and pinned Millstrand paths reach tooling setup in the correct order and preserve refresh/cutover authority.

### PLAN-Tbr-001.PH3 Documentation and verification

Outcome: the README, cookbook, generated API, focused tests, and repository quality gate describe and prove the shipped behavior.

## PLAN-Tbr-001.P6 Validation strategy

- **PLAN-Tbr-001.V1:** Definition tests assert the three choice routes, route-specific instructions, phase ordering, and zero `workflow/gate` attributes in the new definitions.
- **PLAN-Tbr-001.V2:** Compile tests assert that `bump-spool` and both Millstrand coordinate paths reach the tooling checkpoint after refresh and before cutover or handover.
- **PLAN-Tbr-001.V3:** The activated-module test resolves every new registered workflow through a disposable unpublished Weaver world.
- **PLAN-Tbr-001.V4:** Run the focused Millstrand-workflows tests, generated API task, and `make quality`.

## PLAN-Tbr-001.P7 Risks and open questions

- **PLAN-Tbr-001.R1:** Instructions may accidentally imply one universal project layout. Mitigation: state invariants and required evidence while requiring the agent to inspect repository-native files and commands.
- **PLAN-Tbr-001.R2:** A pending generation can make “configured” look like “proved.” Mitigation: require separate prepared/current/adopted evidence and an explicit unfinished repeat check when cutover is not authorized.
- **PLAN-Tbr-001.R3:** The existing Kondo bootstrap contains an executor gate. This feature does not add or modify that older contract; the no-gate rule applies to the new repository-style tooling workflows.

## PLAN-Tbr-001.P8 Task context

- **PLAN-Tbr-001.TC1:** Work on card `8wxen`, with design task `dhygb` and implementation task `e4olb`.
- **PLAN-Tbr-001.TC2:** Preserve the dirty canonical Millhouse checkout. All edits belong in `/Users/ct/dev/projects/millhouse.spool__tooling-by-repo-style`.
- **PLAN-Tbr-001.TC3:** Follow existing `defworkflow`, registration, focused compile-test, docs, and API-generation patterns in the Millstrand-workflows root.

## PLAN-Tbr-001.P9 Developer Notes

### PLAN-Tbr-001.DN1 Task dhygb: placement decision — 2026-08-12

- The shared tooling choice runs after bootstrap and refresh. This lets every route see the refresh outcome and distinguish current Weaver evidence from a pending generation.
