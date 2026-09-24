CLJ_KONDO := clj-kondo

.PHONY: kondo kondo-import kondo-lint clean-kondo

kondo:
	$(MAKE) kondo-import
	$(MAKE) kondo-lint

kondo-import:
	@echo "==> spools/config clj-kondo imports"
	@rm -rf .clj-kondo/imports
	@mkdir -p .clj-kondo
	@classpath="$$(clojure -Srepro -Spath -M:test)" && \
	$(CLJ_KONDO) --repro --lint "$$classpath" --copy-configs --skip-lint

kondo-lint:
	@echo "==> spools/config clj-kondo"
	@$(CLJ_KONDO) --repro --parallel --lint src test

clean-kondo:
	rm -rf .clj-kondo/imports .clj-kondo/.cache

.PHONY: test check
test:
	clojure -M:test

check: kondo test
