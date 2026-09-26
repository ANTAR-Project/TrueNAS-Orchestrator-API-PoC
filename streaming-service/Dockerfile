# BUILD TOOL - maven
FROM maven:3.9.9-eclipse-temurin-21 AS builder

WORKDIR /app

COPY streaming-service/pom.xml .

RUN mvn dependency:go-offline -B # caching the dependencies on install

COPY streaming-service/src ./src
COPY proto /proto

RUN mvn clean package -DskipTests # packaging the code into .jar

# RUNTIME - JDK
FROM eclipse-temurin:21-jdk AS runner

WORKDIR /app

COPY --from=builder ./app/target/streaming-service-0.0.1-SNAPSHOT.jar ./app.jar

EXPOSE 8083

ENTRYPOINT ["java", "-jar", "app.jar"]