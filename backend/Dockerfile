FROM maven:3.9.11-eclipse-temurin-25-alpine AS build
WORKDIR /workspace
COPY pom.xml .
RUN mvn -q -DskipTests dependency:go-offline
COPY src src
RUN mvn -q -DskipTests package

FROM eclipse-temurin:25-jre-alpine
RUN addgroup -S reviewforge && adduser -S reviewforge -G reviewforge
WORKDIR /app
COPY --from=build /workspace/target/reviewforge-api-*.jar app.jar
USER reviewforge
EXPOSE 8080
ENTRYPOINT ["java", "-XX:MaxRAMPercentage=75.0", "-jar", "/app/app.jar"]

