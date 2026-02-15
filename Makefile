run:
	mkdir -p logs
	mvn spring-boot:run -Dspring-boot.run.arguments="--server.port=8080" 1> logs/server 2>&1 &

run-no-auth:
	mvn spring-boot:run -Dspring-boot.run.arguments="--server.port=0 --spring.profiles.active=dev"
