.PHONY: dev down logs test backend-test runner-test frontend-check benchmark smoke

dev:
	docker compose up --build

down:
	docker compose down

logs:
	docker compose logs -f backend runner

test: backend-test runner-test frontend-check

backend-test:
	cd backend && mvn verify

runner-test:
	cd runner && mvn verify

frontend-check:
	cd frontend && npm run check

benchmark:
	cd backend && mvn -q -Dtest=ValidationBenchmarkTest -DfailIfNoTests=false test

# Runs against a stack that is already up (make dev), including a real Maven run in the runner.
smoke:
	./scripts/smoke.sh

