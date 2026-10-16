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

# A runtime with only the JDK modules the application needs, instead of the whole JRE. jdeps lists what the code and its
# libraries use; the extra modules are the ones reached only by reflection or at run time: TLS to the broker, the mail
# server and PostgreSQL (jdk.crypto.ec), JMX and JNDI (connection pool, logging), the unsafe API that Netty uses,
# Kerberos and SASL in the PostgreSQL driver, and the zip file system.
FROM build AS runtime-image
RUN set -eu; \
    modules="$(jdeps --ignore-missing-deps --multi-release 25 --print-module-deps build/install/kotlin-order-api/lib/*.jar)"; \
    echo "modules found by jdeps: $modules"; \
    jlink \
      --add-modules "$modules,jdk.crypto.ec,jdk.unsupported,java.naming,java.management,java.security.jgss,java.security.sasl,jdk.zipfs,jdk.naming.dns" \
      --strip-debug --no-header-files --no-man-pages --compress zip-6 \
      --output /runtime

# Plain Alpine, the trimmed runtime and the jars; not root, and no build toolchain.
FROM alpine:3.24@sha256:294b683cb724975bec92580e1e685676bd4b50bda910ddb8c51d4cabeaec77e6
# api or worker: both processes ship in the same image, the entrypoint picks one.
ARG TARGET=api
ENV TARGET=${TARGET} \
    JAVA_HOME=/opt/java \
    PATH=/opt/java/bin:/usr/local/sbin:/usr/local/bin:/usr/sbin:/usr/bin:/sbin:/bin
RUN apk add --no-cache libstdc++ zlib \
    && addgroup -S app \
    && adduser -S app -G app
COPY --from=runtime-image /runtime /opt/java
WORKDIR /app
COPY --from=build /src/build/install/kotlin-order-api/lib ./lib
USER app
EXPOSE 8080
HEALTHCHECK --interval=30s --timeout=5s --start-period=40s --retries=3 \
  CMD [ "$TARGET" != "api" ] || wget -qO- http://127.0.0.1:8080/health > /dev/null || exit 1
ENTRYPOINT ["sh", "-c", "if [ \"$TARGET\" = worker ]; then main=com.ronybrand.orderapi.WorkerMain; else main=com.ronybrand.orderapi.ApiMain; fi; exec java -XX:MaxRAMPercentage=75 -XX:+ExitOnOutOfMemoryError -cp 'lib/*' \"$main\""]
