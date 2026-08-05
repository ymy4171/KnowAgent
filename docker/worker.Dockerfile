FROM maven:3.9.11-eclipse-temurin-21 AS build

WORKDIR /workspace
COPY . .
RUN mvn -pl knowagent-worker -am -DskipTests package

FROM eclipse-temurin:21-jre-alpine

RUN addgroup -S knowagent && adduser -S knowagent -G knowagent
WORKDIR /app
COPY --from=build /workspace/knowagent-worker/target/knowagent-worker-0.1.0-SNAPSHOT.jar app.jar
USER knowagent

ENTRYPOINT ["java", "-XX:MaxRAMPercentage=75.0", "-jar", "/app/app.jar"]
