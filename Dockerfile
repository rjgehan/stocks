# syntax=docker/dockerfile:1.7
#
# stocks production image (ARCHITECTURE §24.1). One multi-arch image: linux/amd64 + linux/arm64.
#
#   docker buildx build --platform linux/amd64,linux/arm64 -t ghcr.io/<owner>/stocks:<tag> --push .
#   make image                      # host platform only, loaded into the local image store
#
# The `web` and `build` stages run on the BUILD platform ($BUILDPLATFORM): their outputs (frontend/dist and the
# Spring Boot jar) are platform-independent, so a cross-build never runs npm or Maven under QEMU. Only the small
# runtime stage is built per target platform. The root .dockerignore is required (it keeps host node_modules,
# backend/target, backend/.data, backups and .env out of the build context).

FROM --platform=$BUILDPLATFORM node:22-alpine AS web
WORKDIR /src/frontend
COPY frontend/package.json frontend/package-lock.json ./
# The lockfile carries the §2.2 TypeScript override; `npm ci` fails if it drifts from package.json.
RUN --mount=type=cache,target=/root/.npm npm ci --no-audit --no-fund
COPY frontend/ ./
RUN npm run build

FROM --platform=$BUILDPLATFORM eclipse-temurin:21-jdk AS build
WORKDIR /src/backend
COPY backend/.mvn .mvn
COPY backend/mvnw backend/pom.xml ./
RUN --mount=type=cache,target=/root/.m2 chmod +x mvnw && ./mvnw -B -q dependency:go-offline
COPY backend/src src
# Never ship whatever a local `make jar` left in static/: the SPA always comes from the web stage.
RUN rm -rf src/main/resources/static
COPY --from=web /src/frontend/dist src/main/resources/static
RUN --mount=type=cache,target=/root/.m2 ./mvnw -B -q -DskipTests package \
 && java -Djarmode=tools -jar target/stocks.jar extract --layers --launcher --destination target/extracted

FROM eclipse-temurin:21-jre
LABEL org.opencontainers.image.title="stocks" \
      org.opencontainers.image.description="Multiplayer paper-trading league (Spring Boot API + React SPA, one origin)"
# curl only serves the HEALTHCHECK; the base image's security updates come with each rebuild.
# hadolint ignore=DL3008
RUN apt-get update && apt-get install -y --no-install-recommends curl && rm -rf /var/lib/apt/lists/* \
 && groupadd -r -g 10001 stocks && useradd -r -g stocks -u 10001 -M -d /app -s /usr/sbin/nologin stocks
WORKDIR /app
# Least-changing layers first, so a code-only release re-pushes only the small application layer.
COPY --from=build /src/backend/target/extracted/dependencies/ ./
COPY --from=build /src/backend/target/extracted/spring-boot-loader/ ./
COPY --from=build /src/backend/target/extracted/snapshot-dependencies/ ./
COPY --from=build /src/backend/target/extracted/application/ ./
USER 10001:10001
# 8080: HTTP + WebSocket (the only port behind the tunnel). 8081: actuator, never published or tunneled.
EXPOSE 8080 8081
# JAVA_TOOL_OPTIONS is the baseline; add flags at runtime with JDK_JAVA_OPTIONS (docker-compose.yml), which the
# java launcher applies after it, so they win without having to repeat the baseline.
ENV SPRING_PROFILES_ACTIVE=prod \
    JAVA_TOOL_OPTIONS="-XX:MaxRAMPercentage=75 -XX:+ExitOnOutOfMemoryError -Duser.timezone=UTC"
# Shell form on purpose: `|| exit 1` maps every curl failure to Docker's "unhealthy" status.
# hadolint ignore=DL3025
HEALTHCHECK --interval=15s --timeout=3s --start-period=90s --retries=5 \
  CMD curl -fsS http://127.0.0.1:8081/actuator/health/readiness || exit 1
ENTRYPOINT ["java","org.springframework.boot.loader.launch.JarLauncher"]
