.PHONY: test test-local api-docs docs-site docs-serve docs-check fmt-check lint lint-clj lint-splint lint-conventions reflect-check kanban-dash-check kanban-export kanban-serve quality

MILLSTRAND_OVERRIDE = -Sdeps '{:aliases {:millstrand-root {:extra-deps {io.millstrand/millstrand {:local/root "$(MILLSTRAND_ROOT)"}}}}}'

test:
	clojure -M:test

test-local:
	@test -n "$(strip $(MILLSTRAND_ROOT))" || { echo "MILLSTRAND_ROOT is required (for example: make test-local MILLSTRAND_ROOT=/path/to/millstrand)" >&2; exit 2; }
	clojure $(MILLSTRAND_OVERRIDE) -M:test:millstrand-root

api-docs:
	clojure -M:api-docs

docs-site:
	uvx --from mkdocs --with mkdocs-material --with markdown-gfm-admonition mkdocs build --strict

docs-serve:
	uvx --from mkdocs --with mkdocs-material --with markdown-gfm-admonition mkdocs serve --dev-addr 0.0.0.0:8000

docs-check:
	$(MAKE) api-docs
	git diff --exit-code -- 'spools/*/*.api.md'
	python3 scripts/check_markdown_links.py
	$(MAKE) docs-site

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

kanban-dash-check:
	go build -C spools/kanban/scripts/agent-dash ./...
	go vet -C spools/kanban/scripts/agent-dash ./...
	go test -C spools/kanban/scripts/agent-dash ./...

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
