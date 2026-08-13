# Millstrand-workflows cookbook

Recipes here combine multiple workflow families; parameter shapes and focused starts live in the generated API.

## Publish and adopt a producer export

**Situation.** A macro-owning spool has changed its public forms and a consumer must adopt the export without taking ownership of producer mappings.

**Composition.** Run `publish-spool-kondo` in the producer checkout, review its export and tests, then run `bootstrap-kondo` in the consumer. Select `greenfield` only for a missing local boundary; select `brownfield` to inventory and merge an existing one. The consumer bootstrap consumes the resolved dependency classpath, validates provenance and cache hygiene, and discovers the consumer's own quality checks.

**Why this shape.** Publication establishes one producer source of truth before adoption. Keeping the two workflows in separate worktrees makes ownership, self-import checks, and pending local work explicit.

## Bump a pinned family and adopt its exports

**Situation.** A consumer pins Millstrand or another spool family and needs the newest approved default-branch commit.

**Composition.** Give `bump-spool` family names plus the exact consumer worktree and workspace. It emits one `spool bump <family> --latest sha` request per family, accepts an already-current coordinate, calls `bootstrap-kondo`, and refreshes the selected runtime. Choose `app`, `spool`, or `clojure-app` when prompted. The chosen continuation asks the agent to align tools.deps, LSP, lint, tests, and Weaver behavior with the effective spool world before handover or an authorized cutover.

**Why this shape.** Family-only input prevents callers from inventing versions or SHAs. Reusing bootstrap keeps Kondo ownership and provenance identical after a bump. The repository-style steps stay manual because each consumer has different aliases, commands, and source ownership; they do not add a universal gate.

## Choose a local or pinned Millstrand update

**Situation.** A consumer's Millstrand coordinate may be a sibling checkout during development or a Git/SHA pin in a shared checkout.

**Composition.** Start `bump-millstrand`, inspect the exact `deps-file`, and route explicitly. The local route preserves the checkout, asks for a move-forward decision, then calls `bootstrap-kondo`; the pinned route delegates to `bump-spool`. Both routes refresh the selected runtime and ask for the same `app`, `spool`, or `clojure-app` tooling choice. When a generation is pending, record what works against the current generation and leave the chosen Weaver check unfinished. Hand over unless the direct user has authorized cutover; after cutover, repeat that check against the adopted generation.

**Why this shape.** Local development stays local, while pinned consumers use the remote default-branch SHA path. Both get the same tooling contract without pretending that `deps.edn` replaces spool approval. The explicit boundary prevents an agent or nested workflow from stopping or restarting a runtime.

## Configure tooling without changing a coordinate

**Situation.** A consumer already has the intended spool coordinates but its Clojure tools do not see the same source, config, or tests as its Weaver.

**Composition.** Call `configure-consumer-tooling` with the exact worktree, workspace, and invocation producer coordinate. Use a pinned remote `millhouse/spools` family with its exact URL and full SHA for ordinary consumers, or the exact local self root and `local/root` when Millhouse is the consumer. Inspect spool status and repository conventions, then choose one style. Before bootstrap, compare the supplied coordinate with `spools.edn` and every relevant Millhouse root in `deps.edn`. Manually align only the mismatched coordinates, preserve each `:deps/root`, and record the before and after values. Stop when metadata is missing or cannot be compared. Do not infer the running workflow SHA or automate the edits. Then work through the tools.deps, LSP, lint, test, and Weaver steps, recording the real commands and results.

**Why this shape.** Clojure tools consume a static tools.deps view, while the Weaver consumes approved spool roots. The workflow makes the two views agree where needed without merging their authority or imposing one repository layout.
