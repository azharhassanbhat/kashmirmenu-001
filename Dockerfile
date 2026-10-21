# syntax=docker/dockerfile:1

# ---- Build stage ----
FROM eclipse-temurin:21-jdk AS build
WORKDIR /workspace

COPY gradlew settings.gradle build.gradle ./
COPY gradle ./gradle
RUN ./gradlew --version

COPY src ./src
RUN ./gradlew bootJar --no-daemon -x test

# ---- Run stage ----
FROM eclipse-temurin:21-jre AS run
WORKDIR /app

RUN addgroup --system kashmirmenu && adduser --system --ingroup kashmirmenu kashmirmenu \
    && apt-get update \
    && apt-get install -y --no-install-recommends curl \
    && rm -rf /var/lib/apt/lists/*

COPY --from=build /workspace/build/libs/*.jar app.jar
# Canonical's Pebble service manager ships in the base image, is never run here, and carries its own Go
# library findings; removing it removes them.
RUN chown kashmirmenu:kashmirmenu app.jar \
    && rm -f /usr/bin/pebble
USER kashmirmenu

ENV JAVA_OPTS=""
# 8080 serves the API; 9091 serves Actuator and is deliberately never published.
EXPOSE 8080 9091

HEALTHCHECK --interval=30s --timeout=5s --start-period=40s --retries=3 \
    CMD curl -fs http://localhost:${MANAGEMENT_PORT:-9091}/actuator/health/readiness || exit 1

ENTRYPOINT ["sh", "-c", "java $JAVA_OPTS -jar app.jar"]