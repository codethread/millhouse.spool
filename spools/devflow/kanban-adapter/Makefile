KONDO = clj-kondo --repro

.PHONY: kondo kondo-import kondo-lint

kondo-import:
	@set -e; \
	mkdir -p .clj-kondo; \
	rm -rf .clj-kondo/imports; \
	classpath=$$(clojure -Srepro -Spath -M:test); \
	$(KONDO) --lint "$$classpath" --copy-configs --skip-lint

kondo-lint:
	@$(KONDO) --parallel --lint src test

kondo: kondo-import
	@$(MAKE) --no-print-directory kondo-lint

.PHONY: test
test:
	clojure -M:test
