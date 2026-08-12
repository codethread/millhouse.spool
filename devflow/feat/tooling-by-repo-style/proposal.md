# Consumer tooling by repository style proposal

**Document ID:** `PROP-Tbr-001`
**Status:** Approved
**Approved:** 2026-08-12
**Related RFCs:** None
**Related root specs:** None

Once approved this document is frozen. It records the intent agreed at sign-off, not what was later built. Implementation change belongs in the plan and code.

## PROP-Tbr-001.P1 Problem

The Millstrand bump workflows update runtime coordinates and bootstrap clj-kondo, but they do not lead the driving agent through the full development-tool setup. A consumer may be a non-Clojure application, a spool library, or a Clojure application. Each style needs a different tools.deps view for LSP, lint, tests, and Weaver verification. Without an explicit choice, agents must reconstruct those differences each time.

## PROP-Tbr-001.P2 Goals

- **PROP-Tbr-001.G1:** Make the repository style an explicit agent choice in the spool and Millstrand bump flows.
- **PROP-Tbr-001.G2:** Give each style clear LSP, lint, test, Weaver, and handover obligations.
- **PROP-Tbr-001.G3:** Keep `spools.edn` as the runtime approval authority while adapting standard Clojure tools to the effective spool world.
- **PROP-Tbr-001.G4:** Leave repository-specific commands and edits to the driving agent.

## PROP-Tbr-001.P3 Non-goals

- **PROP-Tbr-001.NG1:** Do not add shell, code, CI, human, or external-system gates for tooling setup.
- **PROP-Tbr-001.NG2:** Do not prescribe one generated `deps.edn`, LSP configuration, test runner, or quality command for every repository.
- **PROP-Tbr-001.NG3:** Do not change spool acquisition, runtime refresh, pending-generation, or cutover semantics.
- **PROP-Tbr-001.NG4:** Do not replace `spools.edn` with tools.deps coordinates.

## PROP-Tbr-001.P4 Proposed scope

- **PROP-Tbr-001.S1:** Both bump flows must reach an agent-owned choice between application, spool library, and Clojure application repository styles.
- **PROP-Tbr-001.S2:** Each choice must lead through ordinary manual workflow steps covering LSP, lint and clj-kondo, tests, real Weaver verification, and a precise handover.
- **PROP-Tbr-001.S3:** Instructions must preserve existing repository configuration where possible, discover local conventions, and record exact commands and results.
- **PROP-Tbr-001.S4:** The application path must preserve a non-Clojure repository's empty or existing product classpath, expose only Millstrand config and tests through development aliases, make those aliases explicit to LSP, import Kondo exports from the effective spool world, run config and disposable-Weaver tests, and invoke a real operation in the selected workspace.
- **PROP-Tbr-001.S5:** The spool-library path must make owned spool roots visible to tools.deps, LSP, and Kondo while retaining `spools.edn` approval for runtime loading. It must distinguish direct classpath tests from a production-style disposable world that approves the root and activates a guarded module.
- **PROP-Tbr-001.S6:** The Clojure-application path must preserve the ordinary application basis, add Millstrand config and tests through development aliases, direct LSP and Kondo to the composed view, keep base application tests usable without selecting Millstrand, and prove that a selected-workspace module reaches application code.
- **PROP-Tbr-001.S7:** Every path must start by recording the effective approved roots and existing repository conventions, then record the resulting source/classpath view, configuration files, exact LSP, lint, test, and Weaver commands, their outcomes, and any mismatch or unproved behavior.
- **PROP-Tbr-001.S8:** The shared repository-style choice must run after the existing Kondo bootstrap and runtime refresh in both bump paths, and before cutover or pending-generation handover. When refresh reports a pending generation, the route must distinguish configuration that was prepared from behavior proved against the current Weaver. An authorized cutover must repeat the route's Weaver check against the adopted generation; a workflow without cutover authority must hand that repeat check over explicitly as unfinished work.

## PROP-Tbr-001.P5 Examples

- **PROP-Tbr-001.E1:** After a family bump, Kondo bootstrap, and runtime refresh, the agent selects the consumer shape before cutover or pending-generation handover.

  ```text
  Choose repository style
  ├── app          → LSP → lint → tests → Weaver → handover
  ├── spool        → LSP → lint → tests → Weaver → handover
  └── clojure-app  → LSP → lint → tests → Weaver → handover
  ```

- **PROP-Tbr-001.E2:** A spool repository keeps `spools.edn` as the runtime approval source while its development tools use a matching tools.deps-visible classpath.

  ```text
  spools.edn + spool status  → effective runtime roots
                              ↓ agent aligns
  deps.edn / .lsp / Kondo / tests
  ```

## PROP-Tbr-001.P6 Open questions

None. The user selected agent-owned checkpoints and ordinary manual steps, with no tooling gates.
