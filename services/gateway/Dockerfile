# =============================================================================
# gateway container image.
#
# BUILD CONTEXT IS THE REPOSITORY ROOT, not this directory:
#   docker build -f services/gateway/Dockerfile -t ecommerce/gateway .
# (infra/docker-compose.yml already sets `context: ..` for this reason.)
#
# Follows the pattern established by services/eureka-server/Dockerfile: JDK build
# stage -> layer-extracted JRE runtime stage, non-root, no Gradle in the shipped
# image. See that file for the Docker-memory caveat on the Gradle stage.
#
# NO SECRETS IN THIS IMAGE, and none are needed: the gateway holds no private key
# and no database credential. It fetches PUBLIC keys from auth-service's JWKS
# endpoint at runtime. That is a deliberate property of the design - the one
# process exposed to the internet is also the one with nothing worth stealing
# from its filesystem.
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
      :services:gateway:bootJar -x test

RUN JAR=$(ls services/gateway/build/libs/gateway-*.jar | grep -v sources) \
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

EXPOSE 8080

# MaxRAMPercentage rather than a fixed -Xmx, so changing the container memory
# limit does not also require changing a flag in here.
#
# SIZING NOTE: a reactive gateway's memory is dominated by Netty's direct
# ByteBuf arenas, which live OUTSIDE the Java heap. 75% heap is therefore
# generous; if this container starts getting OOM-killed while the heap looks
# healthy, the direct buffers are the place to look, not -Xmx.
ENV JAVA_OPTS="-XX:MaxRAMPercentage=75.0 -XX:+ExitOnOutOfMemoryError"

# `exec` so the JVM is PID 1 and receives SIGTERM directly - that is what makes
# `server.shutdown: graceful` actually work. At the edge that matters more than
# anywhere else: without it, every deploy severs in-flight requests from real
# users rather than letting them finish.
ENTRYPOINT ["sh", "-c", "exec java $JAVA_OPTS org.springframework.boot.loader.launch.JarLauncher"]
