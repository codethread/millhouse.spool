CLJ_KONDO := clj-kondo
CLJ_KONDO_VERSION := 2026.08.04
KONDO_PROJECTS := root millstrand chime cron identity kanban workflow

.PHONY: test test-local api-docs docs-prepare docs-site docs-serve docs-check \
	fmt-check lint lint-clj lint-root lint-millstrand lint-chime lint-cron lint-identity \
	lint-kanban lint-workflow lint-splint lint-conventions reflect-check \
	kondo-configs $(addprefix kondo-configs-,$(KONDO_PROJECTS)) \
	check-clj-kondo clean-kondo kanban-dash-check kanban-export kanban-serve quality

MILLSTRAND_OVERRIDE = -Sdeps '{:aliases {:millstrand-root {:extra-deps {io.millstrand/millstrand {:local/root "$(MILLSTRAND_ROOT)"}}}}}'
RUN_CHECK = python3 scripts/run_quality_check.py
TEST_NAMESPACES ?=

test:
	@$(RUN_CHECK) test clojure -M:test $(TEST_NAMESPACES)

test-local:
	@test -n "$(strip $(MILLSTRAND_ROOT))" || { echo "MILLSTRAND_ROOT is required (for example: make test-local MILLSTRAND_ROOT=/path/to/millstrand)" >&2; exit 2; }
	@$(RUN_CHECK) test-local clojure $(MILLSTRAND_OVERRIDE) -M:test:millstrand-root

api-docs:
	@$(RUN_CHECK) api-docs clojure -M:api-docs

docs-prepare:
	@python3 scripts/prepare_mkdocs.py

docs-site: docs-prepare
	@$(RUN_CHECK) docs-site uvx --from mkdocs --with mkdocs-material --with markdown-gfm-admonition mkdocs build --strict

docs-serve: docs-prepare
	uvx --from mkdocs --with mkdocs-material --with markdown-gfm-admonition mkdocs serve --dev-addr 0.0.0.0:8000

docs-check:
	$(MAKE) api-docs
	@git diff --quiet -- 'spools/*/*.api.md' || { echo "API docs are stale; run 'make api-docs' and commit the regenerated files." >&2; exit 1; }
	@$(RUN_CHECK) markdown-links python3 scripts/check_markdown_links.py
	$(MAKE) docs-site

fmt-check:
	@$(RUN_CHECK) format clojure -M:format

lint: lint-clj lint-splint lint-conventions

lint-clj: lint-root lint-millstrand lint-chime lint-cron lint-identity lint-kanban lint-workflow

lint-root: kondo-configs-root
	@$(RUN_CHECK) clj-kondo-root $(CLJ_KONDO) --repro --parallel --lint scripts test

lint-millstrand: kondo-configs-millstrand
	@$(RUN_CHECK) clj-kondo-millstrand sh -c 'cd .millstrand && $(CLJ_KONDO) --repro --parallel --lint init.clj'

lint-chime: kondo-configs-chime
	@$(RUN_CHECK) clj-kondo-chime sh -c 'cd spools/chime && $(CLJ_KONDO) --repro --parallel --lint src test'

lint-cron: kondo-configs-cron
	@$(RUN_CHECK) clj-kondo-cron sh -c 'cd spools/cron && $(CLJ_KONDO) --repro --parallel --lint src test'

lint-identity: kondo-configs-identity
	@$(RUN_CHECK) clj-kondo-identity sh -c 'cd spools/identity && $(CLJ_KONDO) --repro --parallel --lint src test'

lint-kanban: kondo-configs-kanban
	@$(RUN_CHECK) clj-kondo-kanban sh -c 'cd spools/kanban && $(CLJ_KONDO) --repro --parallel --lint src test'

lint-workflow: kondo-configs-workflow
	@$(RUN_CHECK) clj-kondo-workflow sh -c 'cd spools/workflow && $(CLJ_KONDO) --repro --parallel --lint src test'

kondo-configs: $(addprefix kondo-configs-,$(KONDO_PROJECTS))

kondo-configs-root: check-clj-kondo
	@rm -rf .clj-kondo/imports
	@classpath="$$(clojure -Spath -M:test)"; \
		$(CLJ_KONDO) --repro --lint "$$classpath" --copy-configs --skip-lint

kondo-configs-millstrand: check-clj-kondo
	@cd .millstrand && rm -rf .clj-kondo/imports && mkdir -p .clj-kondo && \
		classpath="$$(clojure -Spath)" && \
		$(CLJ_KONDO) --repro --lint "$$classpath" --copy-configs --skip-lint

kondo-configs-chime kondo-configs-cron kondo-configs-identity kondo-configs-kanban kondo-configs-workflow: kondo-configs-%: check-clj-kondo
	@cd spools/$* && rm -rf .clj-kondo/imports && mkdir -p .clj-kondo && \
		classpath="$$(clojure -Spath -M:test)" && \
		$(CLJ_KONDO) --repro --lint "$$classpath" --copy-configs --skip-lint

check-clj-kondo:
	@command -v $(CLJ_KONDO) >/dev/null 2>&1 || { echo "clj-kondo $(CLJ_KONDO_VERSION) is required" >&2; exit 1; }
	@actual="$$($(CLJ_KONDO) --version)"; expected="clj-kondo v$(CLJ_KONDO_VERSION)"; \
		if [ "$$actual" != "$$expected" ]; then echo "Expected $$expected, found $$actual" >&2; exit 1; fi

clean-kondo:
	rm -rf .clj-kondo/imports .clj-kondo/.cache \
		.millstrand/.clj-kondo/imports .millstrand/.clj-kondo/.cache \
		spools/*/.clj-kondo/imports spools/*/.clj-kondo/.cache

lint-splint:
	@$(RUN_CHECK) splint clojure -M:lint/splint

lint-conventions:
	@$(RUN_CHECK) conventions clojure -M:lint/conventions

reflect-check:
	@$(RUN_CHECK) reflect clojure -M:reflect-check

kanban-dash-check:
	@$(RUN_CHECK) kanban-dash-build go build -C spools/kanban/scripts/agent-dash ./...
	@$(RUN_CHECK) kanban-dash-vet go vet -C spools/kanban/scripts/agent-dash ./...
	@$(RUN_CHECK) kanban-dash-test go test -C spools/kanban/scripts/agent-dash ./...

kanban-export:
	@test -n "$(ID)" || { echo "make kanban-export: pass a card id, e.g. make kanban-export ID=abc12 [ARGS='--open']" >&2; exit 2; }
	bun install --cwd spools/kanban/scripts/kanban-export --silent
	bun spools/kanban/scripts/kanban-export/kanban-export.ts $(ID) $(ARGS)

KANBAN_EXPORT_DIR := /tmp/kanban-export
kanban-serve:
	@test -n "$(ID)" || { echo "make kanban-serve: pass a card id, e.g. make kanban-serve ID=abc12 [PORT=8000]" >&2; exit 2; }
	@bun install --cwd spools/kanban/scripts/kanban-export --silent
	@file="$(KANBAN_EXPORT_DIR)/kanban-$(ID).html"; \
	bun spools/kanban/scripts/kanban-export/kanban-export.ts $(ID) --out "$$file" $(ARGS); \
	ip="$$(ipconfig getifaddr en0 2>/dev/null || ipconfig getifaddr en1 2>/dev/null || hostname)"; \
	port="$(or $(PORT),8000)"; \
	printf '\n  file: %s\n  port: %s\n  url:  http://%s:%s/kanban-%s.html\n\n  serving %s — Ctrl-C to stop\n\n' \
		"$$file" "$$port" "$$ip" "$$port" "$(ID)" "$(KANBAN_EXPORT_DIR)"; \
	python3 -m http.server "$$port" --bind 0.0.0.0 --directory "$(KANBAN_EXPORT_DIR)"

quality: fmt-check lint reflect-check docs-check test kanban-dash-check
