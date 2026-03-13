ENV_FILE := ../.env

check-env:
	@test -f $(ENV_FILE) || (echo "Error: $(ENV_FILE) not found. Copy .env.example to ../.env and fill in values." && exit 1)

run: check-env
	set -a && . $(ENV_FILE) && set +a && ./mvnw spring-boot:run -Dspring-boot.run.arguments="--server.port=8080 --spring.profiles.active=dev"

run-no-auth: check-env
	PORT=$$(for p in $$(seq 9001 9010); do nc -z localhost $$p 2>/dev/null || { echo $$p; break; }; done) && \
	set -a && . $(ENV_FILE) && set +a && \
	./mvnw spring-boot:run -Dspring-boot.run.arguments="--server.port=$$PORT --spring.profiles.active=dev"

compile:
	./mvnw compile -q

test:
	./mvnw test

import-lists: check-env
	@test -n "$(FILE)" || (echo "Error: FILE is required. Usage: make import-lists FILE=/path/to/file.json [DB=VAR_NAME]" && exit 1)
	set -a && . $(ENV_FILE) && set +a && \
	DATABASE_URL=$${$(or $(DB),DATABASE_URL)} \
	./mvnw exec:java -Dexec.mainClass="armchair.tool.CuratedListImporter" -Dexec.args="$(FILE)"

docker-build:
	docker build -t armchair .

docker-up: check-env
	docker compose up --build
