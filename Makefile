ENV_FILE := ../.env
LOCAL_DB_URL := jdbc:postgresql://localhost:5432/armchair?user=armchair&password=armchair

check-env:
	@test -f $(ENV_FILE) || (echo "Error: $(ENV_FILE) not found. Copy .env.example to ../.env and fill in values." && exit 1)

db-up:
	docker compose -f docker-compose.dev.yml up -d

db-down:
	docker compose -f docker-compose.dev.yml down

run: check-env db-up
	set -a && . $(ENV_FILE) && set +a && DATABASE_URL="$(LOCAL_DB_URL)" ./mvnw spring-boot:run -Dspring-boot.run.arguments="--server.port=8080 --spring.profiles.active=dev"

run-no-auth: check-env db-up
	PORT=$$(for p in $$(seq 9001 9010); do nc -z localhost $$p 2>/dev/null || { echo $$p; break; }; done) && \
	set -a && . $(ENV_FILE) && set +a && \
	DATABASE_URL="$(LOCAL_DB_URL)" ./mvnw spring-boot:run -Dspring-boot.run.arguments="--server.port=$$PORT --spring.profiles.active=dev"

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
