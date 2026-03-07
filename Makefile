ENV_FILE := ../.env

check-env:
	@test -f $(ENV_FILE) || (echo "Error: $(ENV_FILE) not found. Copy .env.example to ../.env and fill in values." && exit 1)

run: check-env
	set -a && . $(ENV_FILE) && set +a && ./mvnw spring-boot:run -Dspring-boot.run.arguments="--server.port=8080"

run-no-auth: check-env
	set -a && . $(ENV_FILE) && set +a && ./mvnw spring-boot:run -Dspring-boot.run.arguments="--server.port=0 --spring.profiles.active=dev"

compile:
	./mvnw compile -q

docker-build:
	docker build -t armchair .

docker-up: check-env
	docker compose up --build
