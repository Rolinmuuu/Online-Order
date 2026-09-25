# syntax=docker/dockerfile:1.7
# One image with the API and the frontend it serves. Build from the repository root:
#   docker build -t online-order .

# ── 1. Frontend ────────────────────────────────────────────────────────────────────────────
FROM node:22-alpine AS web
WORKDIR /web
COPY doordash-app/package.json doordash-app/package-lock.json ./
RUN --mount=type=cache,target=/root/.npm npm ci --no-audit --no-fund
COPY doordash-app/ ./
RUN npm run build

# ── 2. Backend, with the frontend packaged into the jar ──────────────────────────────────
FROM eclipse-temurin:21-jdk AS build
WORKDIR /src
# Wrapper and build script first: dependency downloads are cached until they change.
COPY OnlineOrder/gradlew OnlineOrder/settings.gradle OnlineOrder/build.gradle ./
COPY OnlineOrder/gradle ./gradle
RUN --mount=type=cache,target=/root/.gradle ./gradlew --no-daemon -q dependencies > /dev/null
COPY OnlineOrder/src ./src
COPY --from=web /web/build /frontend
RUN --mount=type=cache,target=/root/.gradle \
    ./gradlew --no-daemon -q bootJar -PfrontendDist=/frontend \
 && java -Djarmode=tools -jar build/libs/OnlineOrder-*.jar extract --layers --launcher --destination /app

# ── 3. Runtime ───────────────────────────────────────────────────────────────────────────
FROM eclipse-temurin:21-jre-alpine AS runtime
RUN addgroup -S app && adduser -S -G app -u 10001 app
WORKDIR /app
# Layers from least to most often changed, so a code change re-ships only the last one.
COPY --from=build --chown=app:app /app/dependencies/ ./
COPY --from=build --chown=app:app /app/spring-boot-loader/ ./
COPY --from=build --chown=app:app /app/snapshot-dependencies/ ./
COPY --from=build --chown=app:app /app/application/ ./
USER app
ENV JAVA_TOOL_OPTIONS="-XX:MaxRAMPercentage=75 -XX:+ExitOnOutOfMemoryError" \
    LOGGING_STRUCTURED_FORMAT_CONSOLE=ecs \
    APP_DEMO=false
# 8080: public traffic. 8081: health and metrics, for the orchestrator and Prometheus only.
EXPOSE 8080 8081
HEALTHCHECK --interval=10s --timeout=3s --start-period=60s --retries=3 \
  CMD wget -qO- http://localhost:8081/actuator/health/readiness > /dev/null || exit 1
ENTRYPOINT ["java", "org.springframework.boot.loader.launch.JarLauncher"]
