# Millhouse spools

Millhouse is the experimental arm of Millstrand. This first pass copies four spools out of the main repository without removing their originals or changing their Clojure namespaces.

| Root | Namespace | Documentation |
| --- | --- | --- |
| `spools/chime` | `millstrand.spools.chime` | [contract](spools/chime/README.md) · [cookbook](spools/chime/chime.cookbook.md) · [API](spools/chime/chime.api.md) |
| `spools/cron` | `millstrand.spools.cron` | [contract](spools/cron/README.md) · [cookbook](spools/cron/cron.cookbook.md) · [API](spools/cron/cron.api.md) |
| `spools/code-executor` | `millstrand.spools.executors.code` | [contract](spools/code-executor/README.md) · [API](spools/code-executor/code.api.md) |
| `spools/shell-executor` | `millstrand.spools.executors.shell` | [contract](spools/shell-executor/README.md) · [cookbook](spools/shell-executor/shell.cookbook.md) · [API](spools/shell-executor/shell.api.md) |

Each entry is an independent root with its own `deps.edn` and `src` tree. The executor roots require Millstrand's workflow spool and must activate after it. `spool.edn` cannot declare a `:requires` floor while that source root is unmarked; add the floor when Workflow has a release marker.

The copied contract and cookbook prose still describes the in-tree Millstrand layout. Namespace, family, and remaining activation changes belong to the next pass. Chime and Cron also retain the source repository's current quality boundary: conventions, reflection, and runtime tests cover them, while cljfmt, clj-kondo, and Splint do not.
