.PHONY: build test integration lint verify run-api run-worker

build:
	./gradlew assemble

# Lint, unit tests and the coverage floor (80%). No database, no broker, no network.
test:
	./gradlew ktlintCheck test koverVerify

# A real PostgreSQL (embedded, downloaded once from Maven Central); the broker test needs a Docker daemon.
integration:
	./gradlew integrationTest

lint:
	./gradlew ktlintCheck

# The single local gate, same as CI.
verify:
	./gradlew check koverVerify

# Needs a PostgreSQL: docker compose up -d postgres
run-api:
	./gradlew run

run-worker:
	./gradlew runWorker
