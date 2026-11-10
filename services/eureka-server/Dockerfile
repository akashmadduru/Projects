# =============================================================================
# eureka-server container image.
#
# BUILD CONTEXT IS THE REPOSITORY ROOT, not this directory:
#   docker build -f services/eureka-server/Dockerfile -t ecommerce/eureka-server .
# (infra/docker-compose.yml already sets `context: ..` for this reason.)
# A Gradle multi-project build cannot be built from a single module directory -
# settings.gradle.kts and the version catalog live at the root.
#
# This is the first real service image in the platform; the pattern established
# here (JDK build stage -> layer-extracted JRE runtime stage, non-root, no
# Gradle in the shipped image) is the one every later service should copy.
#
# REQUIRES >= ~3GB AVAILABLE TO THE DOCKER ENGINE. The Gradle stage runs a real
# JVM build. On a Docker Desktop VM sized at the 2GB default - especially with
# other stacks already running in it - the Gradle daemon is OOM-killed by the
# kernel and the only symptom is the misleading "Gradle build daemon disappeared
# unexpectedly", with no OOM message and no hs_err file. If you hit that, raise
# Docker Desktop's memory (Settings -> Resources) rather than hunting the build.
# CI runners are not affected. See services/eureka-server/README.md.
# =============================================================================

# --- stage 1: build ----------------------------------------------------------
FROM eclipse-temurin:25-jdk AS build

WORKDIR /workspace

# Copied first and on their own: these change rarely, so the (large) Gradle
# distribution download lands in a layer that source edits do not invalidate.
COPY gradlew gradlew.bat gradle.properties ./
COPY gradle gradle
RUN ./gradlew --version

# The whole tree is needed because settings.gradle.kts includes every module;
# Gradle must be able to configure all of them even to build just one.
# .dockerignore keeps build/, .gradle/ and node_modules out of the context.
COPY . .

# `-x test` on purpose: tests are a CI gate, not an image-build gate. Running
# them here would silently double CI time and make image builds fail for
# reasons that have nothing to do with packaging.
#
# GRADLE_OPTS overrides gradle.properties' `org.gradle.jvmargs=-Xmx2g`, which is
# sized for a developer machine. 640m and single-threaded execution keep the
# build inside a modest container budget; a second worker would be a second
# 640m. The `--add-exports` flags from gradle.properties are deliberately not
# carried over - they exist only for Spotless/google-java-format, which does not
# run here.
#
# GRADLE_OPTS is split on whitespace, so each -D is its own arg and the *value*
# of org.gradle.jvmargs must itself be space-free - hence a single -Xmx flag.
#
# Configuration cache is off: it would be written into a layer and never read
# back, so the write is pure cost.
ENV GRADLE_OPTS="-Dorg.gradle.jvmargs=-Xmx640m -Dorg.gradle.parallel=false -Dorg.gradle.workers.max=1"
RUN ./gradlew --no-daemon --no-configuration-cache \
      :services:eureka-server:bootJar -x test

# Explode the fat jar into Boot's standard layers so Docker can cache the
# dependency layer independently of the application layer - a code-only change
# then rebuilds ~200KB instead of ~40MB.
RUN JAR=$(ls services/eureka-server/build/libs/eureka-server-*.jar | grep -v sources) \
    && java -Djarmode=tools -jar "$JAR" extract --layers --launcher --destination /workspace/extracted

# --- stage 2: runtime --------------------------------------------------------
FROM eclipse-temurin:25-jre-alpine AS runtime

# curl is here solely for the compose healthcheck (`/actuator/health`). Without
# it the healthcheck can never pass and every dependent service's
# `condition: service_healthy` blocks forever.
RUN apk add --no-cache curl \
    && addgroup -S spring \
    && adduser -S -G spring spring

WORKDIR /app

# Ordered least- to most-frequently-changed, so a code change invalidates only
# the last COPY.
COPY --from=build --chown=spring:spring /workspace/extracted/dependencies/ ./
COPY --from=build --chown=spring:spring /workspace/extracted/spring-boot-loader/ ./
COPY --from=build --chown=spring:spring /workspace/extracted/snapshot-dependencies/ ./
COPY --from=build --chown=spring:spring /workspace/extracted/application/ ./

USER spring:spring

EXPOSE 8761

# MaxRAMPercentage rather than a fixed -Xmx: the JVM then sizes the heap from
# the container's cgroup limit, so changing the compose/ECS memory limit does
# not require also remembering to change a flag in here.
ENV JAVA_OPTS="-XX:MaxRAMPercentage=75.0 -XX:+ExitOnOutOfMemoryError"

# `sh -c` is needed to expand $JAVA_OPTS, but `exec` replaces the shell so the
# JVM still ends up as PID 1 and receives SIGTERM directly - that is what makes
# `server.shutdown: graceful` actually work. Dropping the `exec` would leave the
# shell as PID 1, swallow the signal, and turn every stop into a 10s SIGKILL.
ENTRYPOINT ["sh", "-c", "exec java $JAVA_OPTS org.springframework.boot.loader.launch.JarLauncher"]
