# stocks developer commands (ARCHITECTURE §24.6, §26). Works with the GNU Make 3.81 that ships with macOS.
# CI (.github/workflows/ci.yml) calls `make openapi` and `make e2e`; keep the target names stable.

SHELL := /bin/bash
.DEFAULT_GOAL := help

MVNW := ./mvnw
# `make image` settings: IMAGE=ghcr.io/<owner>/stocks TAG=dev PLATFORMS=linux/amd64,linux/arm64 PUSH=1
IMAGE ?= stocks
TAG ?= local
PLATFORMS ?=
PUSH ?=
# `make openapi` contract with the backend (ARCHITECTURE §26): this test class boots the `test` profile, GETs
# /v3/api-docs as an authenticated user and writes the JSON to the path given in -Dstocks.openapi.output.
OPENAPI_EXPORT_TEST ?= OpenApiExportTest
OPENAPI_TMP := $(CURDIR)/backend/target/openapi/openapi.json
# `make e2e` settings. The ports default to the app's (8080/8081); override them when those are taken locally.
# The `e2e` profile (application-e2e.yml) creates this admin at boot, and Playwright signs in with it.
E2E_PORT ?= 8080
E2E_MANAGEMENT_PORT ?= 8081
E2E_USER ?= e2e_admin
E2E_PASSWORD ?= e2e-admin-password

.PHONY: help dev backend frontend test test-pg e2e openapi jar image compose-check clean

help: ## List the targets
	@grep -E '^[a-zA-Z0-9_-]+:.*## ' $(MAKEFILE_LIST) | awk -F':.*## ' '{ printf "  make %-14s %s\n", $$1, $$2 }'

frontend/node_modules: frontend/package-lock.json
	cd frontend && npm ci
	@touch frontend/node_modules

dev: frontend/node_modules ## Backend (profile dev, :8080) + Vite (:5173) together; Ctrl-C stops both
	@echo "backend: http://localhost:8080 (profile dev, H2, SIM)   SPA: http://localhost:5173   Ctrl-C stops both"
	@trap 'kill $$be $$fe 2>/dev/null; wait' INT TERM EXIT; \
	(cd backend && exec $(MVNW) -q spring-boot:run) & be=$$!; \
	(cd frontend && exec npm run dev) & fe=$$!; \
	wait

backend: ## Backend only (./mvnw spring-boot:run, profile dev)
	cd backend && $(MVNW) spring-boot:run

frontend: frontend/node_modules ## Vite dev server only (proxies /api, /ws, /mcp to :8080)
	cd frontend && npm run dev

test: frontend/node_modules ## Backend verify on H2 + frontend unit tests
	cd backend && $(MVNW) -B verify
	cd frontend && npm test

test-pg: ## Backend verify on embedded PostgreSQL 18.6 (no Docker)
	cd backend && $(MVNW) -B verify -Ppg-it

e2e: jar ## Playwright against the built jar (profiles test,e2e on :8080); `cd frontend && npm run e2e` = mock mode
	@log=backend/target/e2e-backend.log; \
	if curl -s -o /dev/null --max-time 2 http://localhost:$(E2E_MANAGEMENT_PORT)/ \
	  || curl -s -o /dev/null --max-time 2 http://localhost:$(E2E_PORT)/; then \
	  echo "make e2e: port $(E2E_PORT) or $(E2E_MANAGEMENT_PORT) is already in use; stop that process or pass" \
	    "E2E_PORT=... E2E_MANAGEMENT_PORT=..." >&2; exit 1; fi; \
	E2E_USER='$(E2E_USER)' E2E_PASSWORD='$(E2E_PASSWORD)' java -Duser.timezone=UTC -jar backend/target/stocks.jar \
	  --spring.profiles.active=test,e2e --server.port=$(E2E_PORT) --management.server.port=$(E2E_MANAGEMENT_PORT) \
	  > "$$log" 2>&1 & pid=$$!; \
	trap 'kill $$pid 2>/dev/null; wait $$pid 2>/dev/null; true' EXIT INT TERM; \
	echo "waiting for the backend (profiles test,e2e, :$(E2E_PORT); log: $$log)"; \
	for i in $$(seq 1 120); do \
	  curl -fsS http://localhost:$(E2E_MANAGEMENT_PORT)/actuator/health/readiness > /dev/null 2>&1 && break; \
	  kill -0 $$pid 2>/dev/null || { echo "backend exited:" >&2; tail -n 60 "$$log" >&2; exit 1; }; \
	  sleep 1; \
	done; \
	curl -fsS http://localhost:$(E2E_MANAGEMENT_PORT)/actuator/health/readiness > /dev/null \
	  || { echo "backend not ready after 120 s:" >&2; tail -n 60 "$$log" >&2; exit 1; }; \
	cd frontend && E2E_BASE_URL=http://localhost:$(E2E_PORT) E2E_USER='$(E2E_USER)' E2E_PASSWORD='$(E2E_PASSWORD)' \
	  npm run e2e

openapi: frontend/node_modules ## Regenerate docs/openapi.json (test profile) and frontend/src/lib/api/schema.d.ts
	@rm -f "$(OPENAPI_TMP)"
	cd backend && $(MVNW) -B -q test -Dtest='$(OPENAPI_EXPORT_TEST)' -Dsurefire.failIfNoSpecifiedTests=false \
	  -Dstocks.openapi.output="$(OPENAPI_TMP)"
	@test -s "$(OPENAPI_TMP)" || { \
	  echo "make openapi: the backend did not write $(OPENAPI_TMP)." >&2; \
	  echo "  Expected test class $(OPENAPI_EXPORT_TEST): boots profile 'test', GETs /v3/api-docs as an" >&2; \
	  echo "  authenticated user and writes it to -Dstocks.openapi.output (ARCHITECTURE §26)." >&2; \
	  exit 1; }
	node -e 'const fs = require("fs"); const [src, dst] = process.argv.slice(1); fs.writeFileSync(dst, JSON.stringify(JSON.parse(fs.readFileSync(src, "utf8")), null, 2) + "\n")' "$(OPENAPI_TMP)" docs/openapi.json
	cd frontend && npm run gen:api
	@echo "wrote docs/openapi.json and frontend/src/lib/api/schema.d.ts"

jar: frontend/node_modules ## Vite build → backend static/ → backend/target/stocks.jar (tests skipped; use make test)
	cd frontend && npm run build
	rm -rf backend/src/main/resources/static
	mkdir -p backend/src/main/resources/static
	cp -R frontend/dist/. backend/src/main/resources/static/
	cd backend && $(MVNW) -B -DskipTests package
	@echo "built backend/target/stocks.jar"

image: ## Docker image via buildx (host platform, loaded locally; PLATFORMS=... PUSH=1 for multi-arch)
	docker buildx build $(if $(PLATFORMS),--platform $(PLATFORMS),) $(if $(PUSH),--push,--load) -t $(IMAGE):$(TAG) .

compose-check: ## Validate docker-compose.yml + .env.example (Compose and CasaOS rules; no Docker daemon needed)
	.github/scripts/check-compose.sh docker-compose.yml .env.example

clean: ## Remove build outputs (keeps node_modules and backend/.data)
	rm -rf backend/target frontend/dist backend/src/main/resources/static
