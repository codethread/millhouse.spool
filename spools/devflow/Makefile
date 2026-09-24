KONDO = clj-kondo --repro
KONDO_CHILDREN = kanban-adapter

.PHONY: kondo kondo-import kondo-lint

# Refresh this root's imports from its test basis, then each independent package
# root. Keep these as one ordered recipe so a failed resolver or child Make
# invocation stops the aggregate target immediately.
kondo-import:
	@set -e; \
	mkdir -p .clj-kondo; \
	rm -rf .clj-kondo/imports; \
	classpath=$$(clojure -Srepro -Spath -M:test); \
	$(KONDO) --lint "$$classpath" --copy-configs --skip-lint; \
	for package in $(KONDO_CHILDREN); do \
		$(MAKE) --no-print-directory -C "$$package" kondo-import; \
	done

# Lint source and tests against the imports already refreshed by kondo-import.
kondo-lint:
	@set -e; \
	$(KONDO) --parallel --lint src test scripts; \
	for package in $(KONDO_CHILDREN); do \
		$(MAKE) --no-print-directory -C "$$package" kondo-lint; \
	done

# A prerequisite, rather than sibling recipes, keeps import before lint under
# `make -j kondo`.
kondo: kondo-import
	@$(MAKE) --no-print-directory kondo-lint

.PHONY: test check
test:
	clojure -M:test
	$(MAKE) -C kanban-adapter test

check: kondo test
	./bin/identity-check
	./bin/verify-card-authoring-equivalence
