# syntax=docker/dockerfile:1

# ---------------------------------------------------------------- build
FROM maven:3.9-eclipse-temurin-21 AS build
WORKDIR /build

# Dependencies first, as their own layer. pom.xml changes rarely and the
# dependency download is the slow part; copying sources first would re-resolve
# every dependency on every source edit.
COPY pom.xml .
RUN --mount=type=cache,target=/root/.m2 mvn -B -q dependency:go-offline

COPY src ./src

# Tests are NOT run here. They need Docker themselves — AbstractPostgresIT starts a
# Testcontainers Postgres — so running them inside the build would need a socket mounted
# into the builder. Run them on the host with `./mvnw test`; this image only packages.
RUN --mount=type=cache,target=/root/.m2 mvn -B -q -DskipTests package

# ---------------------------------------------------------------- runtime
FROM eclipse-temurin:21-jre-alpine AS runtime
WORKDIR /app

# Non-root. The app writes only to logs/, which is created and chowned below.
RUN addgroup -S autwit && adduser -S autwit -G autwit

# logs/ is a mount point in compose so an integration log survives the container and
# can be committed. Created here so the directory exists with the right owner even when
# nothing is mounted over it.
RUN mkdir -p /app/logs && chown -R autwit:autwit /app

COPY --from=build --chown=autwit:autwit /build/target/*.jar /app/app.jar

USER autwit
EXPOSE 8080

# Container-aware heap sizing rather than a fixed -Xmx: an artifact body may be 8MB
# (SKILL_CONTRACT §6.1) and several can be in flight, so headroom matters, but pinning a
# number here would be wrong on every machine that is not the one it was picked on.
ENV JAVA_OPTS="-XX:MaxRAMPercentage=75"

ENTRYPOINT ["sh", "-c", "exec java $JAVA_OPTS -jar /app/app.jar"]
