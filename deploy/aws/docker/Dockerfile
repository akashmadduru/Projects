# =============================================================================
# One Dockerfile for every backend service image.
#
#   docker build -f deploy/aws/docker/Dockerfile --build-arg SERVICE=order-service \
#                -t <registry>/ecommerce/order-service:<tag> .
#
# BUILD CONTEXT IS THE REPOSITORY ROOT.
#
# WHY ONE FILE. The per-service Dockerfiles under services/ each recompile the whole Gradle tree
# (`COPY . .` + bootJar), so shipping 13 services means 13 full builds. Here the `build` stage
# does NOT depend on the SERVICE argument, so BuildKit builds every jar exactly once and each
# `docker build --build-arg SERVICE=...` only runs the cheap `runtime` stage on top of the cached
# result. The existing per-service Dockerfiles are untouched and still work for local compose.
#
# Image pattern is the same as the existing ones: layered jar, JRE-only alpine runtime, non-root,
# JVM as PID 1 (so SIGTERM reaches it and `server.shutdown: graceful` actually works).
# =============================================================================

# --- stage 1: compile every service once --------------------------------------------------------
FROM eclipse-temurin:25-jdk AS build

WORKDIR /workspace

COPY gradlew gradlew.bat gradle.properties ./
COPY gradle gradle
RUN ./gradlew --version

COPY . .

# Memory is capped so the build fits a default Docker Desktop VM. If it is OOM-killed, raise
# the Docker memory limit rather than this number.
ENV GRADLE_OPTS="-Dorg.gradle.jvmargs=-Xmx1536m -Dorg.gradle.parallel=false -Dorg.gradle.workers.max=2"
RUN --mount=type=cache,target=/root/.gradle \
    ./gradlew --no-daemon --no-configuration-cache bootJar -x test

# Split every executable jar into Spring Boot layers under /out/<service>/. The service name is
# the jar's file name minus the version and the "-app" suffix (auth-service-app-0.1.0-SNAPSHOT.jar
# becomes auth-service). Library, "-api", "-plain" and "-sources" jars are not executable.
RUN set -eux; \
    mkdir -p /out; \
    find services -path '*/build/libs/*.jar' \
         ! -name '*-plain.jar' ! -name '*-sources.jar' ! -name '*-api-*.jar' | sort | \
    while read -r jar; do \
      base="$(basename "$jar")"; \
      name="$(echo "$base" | sed -E 's/-[0-9]+\.[0-9]+\.[0-9]+.*\.jar$//; s/-app$//')"; \
      echo "extracting $name from $jar"; \
      java -Djarmode=tools -jar "$jar" extract --layers --launcher --destination "/out/$name"; \
    done; \
    ls /out

# --- stage 2: runtime ----------------------------------------------------------------------------
FROM eclipse-temurin:25-jre-alpine AS runtime

ARG SERVICE
RUN test -n "$SERVICE" || (echo "build-arg SERVICE is required" && false)

# Numeric UID/GID so Kubernetes can verify runAsNonRoot without resolving a name.
RUN addgroup -g 10001 -S app && adduser -u 10001 -S -G app app

WORKDIR /app

COPY --from=build --chown=10001:10001 /out/${SERVICE}/dependencies/ ./
COPY --from=build --chown=10001:10001 /out/${SERVICE}/spring-boot-loader/ ./
COPY --from=build --chown=10001:10001 /out/${SERVICE}/snapshot-dependencies/ ./
COPY --from=build --chown=10001:10001 /out/${SERVICE}/application/ ./

USER 10001:10001

# The port is set per service through SERVER_PORT (Helm); 8080-8092 are used.
EXPOSE 8080

# MaxRAMPercentage rather than a fixed -Xmx: changing the pod memory limit needs no flag change.
ENV JAVA_OPTS="-XX:MaxRAMPercentage=75.0 -XX:+ExitOnOutOfMemoryError -Djava.security.egd=file:/dev/urandom"

ENTRYPOINT ["sh", "-c", "exec java $JAVA_OPTS org.springframework.boot.loader.launch.JarLauncher"]
