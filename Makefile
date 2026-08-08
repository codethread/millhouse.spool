.PHONY: test test-local api-docs docs-check fmt-check lint lint-clj lint-splint lint-conventions reflect-check quality

MILLSTRAND_OVERRIDE = -Sdeps '{:aliases {:millstrand-root {:extra-deps {io.millstrand/millstrand {:local/root "$(MILLSTRAND_ROOT)"}}}}}'

test:
	clojure -M:test

test-local:
	@test -n "$(strip $(MILLSTRAND_ROOT))" || { echo "MILLSTRAND_ROOT is required (for example: make test-local MILLSTRAND_ROOT=/path/to/millstrand)" >&2; exit 2; }
	clojure $(MILLSTRAND_OVERRIDE) -M:test:millstrand-root

api-docs:
	clojure -M:api-docs

docs-check:
	clojure -M:api-docs
	git diff --exit-code -- 'spools/*/*.api.md'
	python3 scripts/check_markdown_links.py

fmt-check:
	clojure -M:format

lint: lint-clj lint-splint lint-conventions

lint-clj:
	clojure -M:lint/clj-kondo

lint-splint:
	clojure -M:lint/splint

lint-conventions:
	clojure -M:lint/conventions

reflect-check:
	clojure -M:reflect-check

quality: fmt-check lint reflect-check docs-check test
