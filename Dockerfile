# Dashboard (Vue): built once, served by the API from the same address
FROM node:22-alpine AS web
WORKDIR /web
COPY web/package.json web/package-lock.json ./
RUN npm ci --no-audit --no-fund
COPY web/ ./
RUN npm run build

# API
FROM eclipse-temurin:21-jdk AS build
WORKDIR /src
COPY gradlew settings.gradle.kts build.gradle.kts ./
COPY gradle ./gradle
# The executable bit can be lost when the repo is committed from Windows.
RUN chmod +x gradlew && ./gradlew --no-daemon dependencies > /dev/null
COPY src ./src
COPY --from=web /web/dist ./web/dist
RUN ./gradlew --no-daemon bootJar -x test

# Run as a non-root user
FROM eclipse-temurin:21-jre
RUN useradd --system --uid 10001 --home /app tenantcore && mkdir -p /app/storage && chown tenantcore /app/storage
WORKDIR /app
COPY --from=build /src/build/libs/tenantcore.jar /app/tenantcore.jar
USER tenantcore
EXPOSE 8000
ENV JAVA_TOOL_OPTIONS="-XX:MaxRAMPercentage=75 -XX:+ExitOnOutOfMemoryError"
HEALTHCHECK --interval=10s --timeout=3s --start-period=40s --retries=5 \
  CMD ["bash", "-c", "exec 3<>/dev/tcp/127.0.0.1/8000 && printf 'GET /ready HTTP/1.0\\r\\n\\r\\n' >&3 && grep -q '200' <&3"]
ENTRYPOINT ["java", "-jar", "/app/tenantcore.jar"]
