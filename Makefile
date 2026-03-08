ENV_FILE := ../.env

check-env:
	@test -f $(ENV_FILE) || (echo "Error: $(ENV_FILE) not found. Copy .env.example to ../.env and fill in values." && exit 1)

run: check-env
	set -a && . $(ENV_FILE) && set +a && ./mvnw spring-boot:run -Dspring-boot.run.arguments="--server.port=8080"

run-no-auth: check-env
	PORT=$$(./scripts/find-port.sh) && \
	set -a && . $(ENV_FILE) && set +a && \
	./mvnw spring-boot:run -Dspring-boot.run.arguments="--server.port=$$PORT --spring.profiles.active=dev"

compile:
	./mvnw compile -q

test:
	./mvnw test

import-lists: check-env
	@test -n "$(FILE)" || (echo "Error: FILE is required. Usage: make import-lists FILE=/path/to/file.json" && exit 1)
	set -a && . $(ENV_FILE) && set +a && ./mvnw exec:java -Dexec.mainClass="armchair.tool.CuratedListImporter" -Dexec.args="$(FILE)"

docker-build:
	docker build -t armchair .

docker-up: check-env
	docker compose up --build
