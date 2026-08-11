# Millstrand-workflows cookbook

## Publish one macro-owning root

Start `publish-spool-kondo` with the owning root's public identity and an explicit vector of macro-to-hook mappings. Keep the producer's `resources/clj-kondo.exports/<coordinate>/` directory on its classpath and use that directory as the one source for each mapping. Review external imports separately, remove generated self-imports, reject overlapping consumer remaps and tracked `.clj-kondo/.cache` files, then run focused tests and update the contract/cookbook/API docs.

## Bootstrap Kondo in a consumer

Start `bootstrap-kondo` with explicit paths:

```clojure
{:worktree "/abs/path/to/consumer-worktree"
 :workspace "/abs/path/to/consumer-worktree/.millstrand"}
```

Answer the first checkpoint before changing the route:

- `greenfield`: create `.clj-kondo/config.edn` only if absent and ensure `.clj-kondo/.cache/` is ignored by repository configuration. Leave producer mappings to dependency exports.
- `brownfield`: inventory the existing config, imported configs, hooks, and ignore rules, then ensure `.clj-kondo/.cache/` is ignored by repository configuration. Merge only missing local settings, preserve existing ownership, and never duplicate a producer-owned hook or replace it with a consumer remap.

Both routes must merge `:copy-kondo-configs? false` into `.lsp/config.edn`, creating it only when absent and preserving all other LSP settings. This makes explicit bootstrap the sole Kondo import owner for every consumer.

Both routes then use one provider-neutral agent step to run `strand --workspace <workspace> spool status` and inspect the live resolved world. Require every intended installed root reported by status to resolve successfully, record each exact root identity and its reported `sync.root`, read that root's `deps.edn`, and resolve its `:paths` relative to `sync.root`. Match Millstrand's runtime rule: absent `:paths` defaults to `["src"]`, while explicit `:paths []` remains empty. Combine those directories with the consumer Clojure classpath and record the exact roots and final classpath. The consumer's plain `clojure -Spath` alone is insufficient and must be explicitly rejected, including when its `deps.edn` has `:paths []`; fail loudly on unresolved roots or an empty installed-spool contribution. Run exactly one command with the resulting classpath: `clj-kondo --lint RESOLVED_CLASSPATH --dependencies --parallel --copy-configs --skip-lint`. Do not require GitHub, GitLab, or `jq`.

Validate every imported Millstrand and installed sibling spool export against its producer `clj-kondo.exports` path. Record one provenance source per config and hook, verify `.lsp/config.edn` records `:copy-kondo-configs? false`, reject duplicate or overlapping mappings, and identify the consumer repository's producer namespace/path coordinates from its metadata and exports before rejecting only self-imports under those coordinates. Legitimate Millhouse and other producer imports are expected in consumers. Ensure no tracked `.clj-kondo/.cache` file exists. Prove and record the consumer self-import result before, during, and after quality. Inspect the consumer's own Makefile, docs, scripts, and CI configuration to discover appropriate local quality checks; record the commands and results rather than assuming `make quality` or a CI service. Finish with a handover containing paths, mode, provenance, duplicate decisions, LSP setting, cache status, consumer self-import results, quality results, and pending local work.

## Bump spool families

Use family names only:

```clojure
{:families ["io.millstrand/millstrand" "millhouse/spools"]
 :worktree "/abs/path/to/consumer-worktree"
 :workspace "/abs/path/to/consumer-worktree/.millstrand"
 :direct-user-request false}
```

Each family emits the explicit command below, which asks Millstrand to resolve and record the remote default-branch HEAD SHA:

```text
strand --workspace <workspace> spool bump <family> --latest sha
```

If the family is already current, record that outcome and continue. After all family requests, reuse `bootstrap-kondo` so Kondo adoption, provenance, duplicates, cache hygiene, local quality discovery, and handover stay identical for first-time and bump workflows. Keep `:direct-user-request` false for agent, scheduled, or nested calls; only a direct user may authorize runtime cutover.

## Bump Millstrand with local-coordinate handling

Use exactly one Millstrand family:

```clojure
{:families ["io.millstrand/millstrand"]
 :worktree "/abs/path/to/consumer-worktree"
 :workspace "/abs/path/to/consumer-worktree/.millstrand"
 :direct-user-request false
 :deps-file "deps.edn"}
```

Inspect the exact `deps-file` and choose `:local-checkout` or `:git-sha-pinned`. For a sibling checkout such as `../millstrand` or `../skein-src`, choose `:move-forward` only after confirming the local path; bootstrap then preserves it and never invents a SHA. For a Git/SHA pin, the continuation calls `bump-spool` with the family only, so it automatically emits `strand --workspace <workspace> spool bump io.millstrand/millstrand --latest sha`.
