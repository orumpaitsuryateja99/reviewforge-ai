FROM maven:3.9.11-eclipse-temurin-25 AS build
WORKDIR /workspace
COPY pom.xml .
RUN mvn -q -DskipTests dependency:go-offline
COPY src src
# Running the suite also resolves Surefire's dynamically selected JUnit Platform provider;
# dependency:go-offline alone does not include that runtime artifact.
RUN mvn -q package

# The runtime keeps a full Maven toolchain: the runner compiles and executes the patched project.
FROM maven:3.9.11-eclipse-temurin-25
RUN groupadd -r reviewforge && useradd -r -g reviewforge -m -d /home/reviewforge reviewforge
WORKDIR /app
COPY --from=build /workspace/target/reviewforge-runner-*.jar app.jar
# Dependencies are baked into the image and protected by the read-only root filesystem.
# Per-run HOME and source workspaces live on Compose's disposable /tmp tmpfs.
COPY --from=build /root/.m2/repository /opt/reviewforge-m2/repository
RUN mkdir -p /tmp/reviewforge-runs && chown -R reviewforge:reviewforge /tmp/reviewforge-runs /app
USER reviewforge
ENV MAVEN_CONFIG=/tmp/reviewforge-home/.m2
EXPOSE 8090
ENTRYPOINT ["java", "-XX:MaxRAMPercentage=75.0", "-jar", "/app/app.jar"]
