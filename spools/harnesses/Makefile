CLJ := clojure
CLJ_KONDO := clj-kondo
CLJ_KONDO_VERSION := 2026.08.04
NODE_BIN := $(dir $(shell node -p 'process.execPath'))
export PATH := $(NODE_BIN):$(PATH)

.PHONY: test test-plugins format format-fix lint lint-splint check check-clj-kondo \
	kondo kondo-import kondo-import-root \
	kondo-lint kondo-lint-root

test:
	clojure -M:test

test-plugins:
	pnpm check:plugins

format:
	clojure -M:format

format-fix:
	clojure -M:format/fix

lint: kondo

kondo: kondo-import
	$(MAKE) kondo-lint

kondo-import: check-clj-kondo
	$(MAKE) kondo-import-root

kondo-import-root:
	mkdir -p .clj-kondo
	rm -rf .clj-kondo/imports
	set -e; classpath="$$($(CLJ) -Srepro -Spath -M:test)"; \
		$(CLJ_KONDO) --repro --lint "$$classpath" --copy-configs --skip-lint

kondo-lint:
	$(MAKE) kondo-lint-root

kondo-lint-root:
	$(CLJ_KONDO) --repro --parallel --lint src test

check-clj-kondo:
	@command -v $(CLJ_KONDO) >/dev/null 2>&1 || { \
		echo "clj-kondo $(CLJ_KONDO_VERSION) is required" >&2; \
		exit 1; \
	}
	@actual="$$($(CLJ_KONDO) --version)"; \
	expected="clj-kondo v$(CLJ_KONDO_VERSION)"; \
	if [ "$$actual" != "$$expected" ]; then \
		echo "Expected $$expected, found $$actual" >&2; \
		exit 1; \
	fi

lint-splint:
	clojure -M:lint/splint

check: format kondo lint-splint test test-plugins
