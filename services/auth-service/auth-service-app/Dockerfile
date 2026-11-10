# =============================================================================
# auth-service container image.
#
# BUILD CONTEXT IS THE REPOSITORY ROOT, not this directory:
#   docker build -f services/auth-service/auth-service-app/Dockerfile -t ecommerce/auth-service .
# (infra/docker-compose.yml already sets `context: ..` for this reason.)
#
# Follows the pattern established by services/eureka-server/Dockerfile: JDK build
# stage -> layer-extracted JRE runtime stage, non-root, no Gradle in the shipped
# image. See that file for the Docker-memory caveat on the Gradle stage.
#
# THE SIGNING KEYSTORE IS NOT IN THIS IMAGE AND MUST NEVER BE. It is mounted at
# runtime (see the compose service). A private key baked into a layer survives in
# the image history even if a later layer deletes it, and gets pushed to whatever
# registry the image lands in. `.dockerignore` already excludes infra/secrets/.
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
      :services:auth-service:auth-service-app:bootJar -x test

RUN JAR=$(ls services/auth-service/auth-service-app/build/libs/auth-service-app-*.jar | grep -v sources) \
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

EXPOSE 8081

# MaxRAMPercentage rather than a fixed -Xmx, so changing the container memory
# limit does not also require changing a flag in here.
#
# SIZING NOTE: Argon2id holds ~19 MiB per in-flight verification and is CPU-bound
# (virtual threads do NOT help it). Peak memory is roughly
# 19 MiB x concurrent logins on top of the normal heap, which is why the per-IP
# throttle and the per-account lockout are also capacity controls, not just
# security ones.
ENV JAVA_OPTS="-XX:MaxRAMPercentage=75.0 -XX:+ExitOnOutOfMemoryError"

# `exec` so the JVM is PID 1 and receives SIGTERM directly - that is what makes
# `server.shutdown: graceful` actually work.
ENTRYPOINT ["sh", "-c", "exec java $JAVA_OPTS org.springframework.boot.loader.launch.JarLauncher"]
