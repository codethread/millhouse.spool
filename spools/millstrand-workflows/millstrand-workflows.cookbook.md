# Millstrand-workflows cookbook

Recipes here combine multiple workflow families; parameter shapes and focused starts live in the generated API.

## Publish and adopt a producer export

**Situation.** A macro-owning spool has changed its public forms and a consumer must adopt the export without taking ownership of producer mappings.

**Composition.** Run `publish-spool-kondo` in the producer checkout, review its export and tests, then run `bootstrap-kondo` in the consumer. Select `greenfield` only for a missing local boundary; select `brownfield` to inventory and merge an existing one. The consumer bootstrap consumes the resolved dependency classpath, validates provenance and cache hygiene, and discovers the consumer's own quality checks.

**Why this shape.** Publication establishes one producer source of truth before adoption. Keeping the two workflows in separate worktrees makes ownership, self-import checks, and pending local work explicit.

## Bump a pinned family and adopt its exports

**Situation.** A consumer pins Millstrand or another spool family and needs the newest approved default-branch commit.

**Composition.** Give `bump-spool` family names plus the exact consumer worktree and workspace. It emits one `spool bump <family> --latest sha` request per family, accepts an already-current coordinate, then calls `bootstrap-kondo` and hands over the refreshed runtime state.

**Why this shape.** Family-only input prevents callers from inventing versions or SHAs. Reusing bootstrap keeps dependency resolution, Kondo ownership, provenance, and quality discovery identical after a bump.

## Choose a local or pinned Millstrand update

**Situation.** A consumer's Millstrand coordinate may be a sibling checkout during development or a Git/SHA pin in a shared checkout.

**Composition.** Start `bump-millstrand`, inspect the exact `deps-file`, and route explicitly. The local route preserves the checkout, asks for a move-forward decision, and then calls `bootstrap-kondo`; the pinned route delegates to `bump-spool`. Both routes refresh the selected runtime and hand over unless the direct user has authorized cutover.

**Why this shape.** Local development stays local, while pinned consumers use the remote default-branch SHA path. The explicit boundary prevents an agent or nested workflow from stopping or restarting a runtime.
