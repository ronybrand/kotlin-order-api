# syntax=docker/dockerfile:1
FROM eclipse-temurin:25-jdk-alpine AS build
WORKDIR /src
COPY gradlew ./
COPY gradle ./gradle
COPY settings.gradle.kts build.gradle.kts ./
# Resolves the dependencies in their own layer: a source change does not download them again.
RUN ./gradlew --no-daemon dependencies > /dev/null
COPY src/main ./src/main
RUN ./gradlew --no-daemon installDist -x test

# Slim runtime: a JRE, no build toolchain, not root.
FROM eclipse-temurin:25-jre-alpine
# api or worker: both processes ship in the same image, the entrypoint picks one.
ARG TARGET=api
ENV TARGET=${TARGET}
RUN addgroup -S app && adduser -S app -G app
WORKDIR /app
COPY --from=build /src/build/install/kotlin-order-api/lib ./lib
USER app
EXPOSE 8080
HEALTHCHECK --interval=30s --timeout=5s --start-period=40s --retries=3 \
  CMD [ "$TARGET" != "api" ] || wget -qO- http://127.0.0.1:8080/health > /dev/null || exit 1
ENTRYPOINT ["sh", "-c", "if [ \"$TARGET\" = worker ]; then main=com.ronybrand.orderapi.WorkerMain; else main=com.ronybrand.orderapi.ApiMain; fi; exec java -XX:MaxRAMPercentage=75 -XX:+ExitOnOutOfMemoryError -cp 'lib/*' \"$main\""]
