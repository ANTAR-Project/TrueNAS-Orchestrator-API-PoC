# BUILD TOOL - maven
FROM maven:3.9.9-eclipse-temurin-21 AS builder

WORKDIR /app

COPY nas-orchestrator/pom.xml .

RUN mvn dependency:go-offline -B # caching the dependencies on install

COPY nas-orchestrator/src ./src
COPY proto /proto

RUN mvn clean package -DskipTests # packaging the code into .jar

# RUNTIME - JDK
FROM eclipse-temurin:21-jdk AS runner

WORKDIR /app

COPY --from=builder ./app/target/nasorchestrator-0.0.1-SNAPSHOT.jar ./app.jar

EXPOSE 8081

ENTRYPOINT ["java", "-Xms256m", "-Xmx512m", "-jar", "app.jar"]