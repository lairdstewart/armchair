run:
	mvn spring-boot:run -Dspring-boot.run.arguments="--server.port=8080"

run-no-auth:
	mvn spring-boot:run -Dspring-boot.run.arguments="--server.port=0 --spring.profiles.active=dev"
