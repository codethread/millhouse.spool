# Agents

## Quality checks

- Run `make quality` before completing changes; it covers formatting, linting, conventions, reflection, docs, and the test suite.
- Use the focused `make` targets while iterating (`fmt-check`, `lint`, `reflect-check`, `docs-check`, `test`); `clojure -M:test --serial` is the diagnostic fallback for parallel test failures.

<!-- mill:millstrand-prime -->
## Millstrand / strand

This repo uses Millstrand strands to track work. Orientation ships in the `mill` CLI:

- `mill strand prime` — the day-to-day strand workflow; run it before multi-step work.
- `mill millstrand prime` — read on demand, only when building on this repo's `.millstrand/` config or spools.
<!-- /mill:millstrand-prime -->
