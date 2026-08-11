# Agents

## Quality checks

- Run `make quality` before completing changes; it covers formatting, linting, conventions, reflection, docs, and the test suite.
- Use the focused `make` targets while iterating (`fmt-check`, `lint`, `reflect-check`, `docs-check`, `test`); `clojure -M:test --serial` is the diagnostic fallback for parallel test failures.

## Testing

The default suite requires namespaces serially, then runs them concurrently with isolated output and summaries:

```text
clojure -M:test
clojure -M:test --serial
clojure -M:test millhouse.spools.workflow-test
clojure -M:test --stress 10
```

Focused runs are serial. Stress mode launches each parallel iteration in a fresh JVM so loaded fixture namespaces and other JVM-global state cannot leak between repetitions. Namespaces proven to require JVM-global isolation belong in the runner's documented serial island.

<!-- mill:millstrand-prime -->
## Millstrand / strand

This repo uses Millstrand strands to track work. Orientation ships in the `mill` CLI:

- `mill strand prime` — the day-to-day strand workflow; run it before multi-step work.
- `mill millstrand prime` — read on demand, only when building on this repo's `.millstrand/` config or spools.
<!-- /mill:millstrand-prime -->
