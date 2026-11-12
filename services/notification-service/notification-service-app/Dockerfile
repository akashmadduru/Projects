# =============================================================================
# notification-service container image.
#
# BUILD CONTEXT IS THE REPOSITORY ROOT, not this directory:
#   docker build -f services/notification-service/notification-service-app/Dockerfile -t ecommerce/notification-service .
# (infra/docker-compose.yml already sets `context: ..` for this reason.)
#
# Follows the pattern established by services/eureka-server/Dockerfile: JDK build
# stage -> layer-extracted JRE runtime stage, non-root, no Gradle in the shipped
# image. See that file for the Docker-memory caveat on the Gradle stage.
#
# NO SECRETS AND NO MOUNTS. Like product-service, this service holds no signing
# key - it only verifies signatures, using public keys fetched at runtime from
# auth-service's JWKS endpoint. Its one credential is the database password,
# which arrives from the environment.
# =============================================================================

# --- stage 1: build ----------------------------------------------------------
FROM eclipse-temurin:25-jdk AS build

WORKDIR /workspace

COPY gradlew gradlew.bat gradle.properties ./
COPY gradle gradle
RUN ./gradlew --version

COPY . .

ENV GRADLE_OPTS="-Dorg.gradle.jvmargs=-Xmx640m -Dorg.gradle.parallel=false -Dorg.gradle.workers.max=1"
RUN ./gradlew --no-daemon --no-configuration-cache \
      :services:notification-service:notification-service-app:bootJar -x test

RUN JAR=$(ls services/notification-service/notification-service-app/build/libs/notification-service-app-*.jar | grep -v sources) \
    && java -Djarmode=tools -jar "$JAR" extract --layers --launcher --destination /workspace/extracted

# --- stage 2: runtime --------------------------------------------------------
FROM eclipse-temurin:25-jre-alpine AS runtime

# curl is here solely for the compose healthcheck.
RUN apk add --no-cache curl \
    && addgroup -S spring \
    && adduser -S -G spring spring

WORKDIR /app

COPY --from=build --chown=spring:spring /workspace/extracted/dependencies/ ./
COPY --from=build --chown=spring:spring /workspace/extracted/spring-boot-loader/ ./
COPY --from=build --chown=spring:spring /workspace/extracted/snapshot-dependencies/ ./
COPY --from=build --chown=spring:spring /workspace/extracted/application/ ./

USER spring:spring

EXPOSE 8090

# MaxRAMPercentage rather than a fixed -Xmx, so changing the container memory
# limit does not also require changing a flag in here.
ENV JAVA_OPTS="-XX:MaxRAMPercentage=75.0 -XX:+ExitOnOutOfMemoryError"

# `exec` so the JVM is PID 1 and receives SIGTERM directly - that is what makes
# `server.shutdown: graceful` actually work.
ENTRYPOINT ["sh", "-c", "exec java $JAVA_OPTS org.springframework.boot.loader.launch.JarLauncher"]
