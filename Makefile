.PHONY: build test integration lint verify run-api run-worker

build:
	./gradlew assemble

# Lint, static analysis and the unit tests: fast, no database, no broker, no network.
test:
	./gradlew ktlintCheck detekt test

# A real PostgreSQL (embedded, downloaded once from Maven Central); the broker test needs a Docker daemon.
integration:
	./gradlew integrationTest

lint:
	./gradlew ktlintCheck detekt

# The single local gate, same as CI: lint, static analysis, every test, and the 80% coverage floor.
verify:
	./gradlew ktlintCheck detekt check koverVerify

# Needs a PostgreSQL: docker compose up -d postgres
run-api:
	./gradlew run

run-worker:
	./gradlew runWorker
