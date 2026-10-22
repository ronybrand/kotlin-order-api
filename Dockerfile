# syntax=docker/dockerfile:1
FROM eclipse-temurin@sha256:2d5d5e9b533342a565907c84f47a2ec298c40cde7b13a3ca85d4950fa456a641 AS build
WORKDIR /src
COPY gradlew ./
COPY gradle ./gradle
COPY settings.gradle.kts build.gradle.kts ./
# Resolves the dependencies in their own layer: a source change does not download them again.
RUN ./gradlew --no-daemon dependencies > /dev/null
COPY src/main ./src/main
RUN ./gradlew --no-daemon installDist -x test

# A runtime with only the JDK modules the application needs, instead of the whole JRE. jdeps follows the dependencies from
# the application jar through the classpath (scanning all the jars at once fails: some Netty jars are explicit modules
# that require libraries this application does not use). The extra modules are the ones reached only by reflection or at
# run time: logging, XML, TLS to the broker, the mail server and PostgreSQL (jdk.crypto.ec), JMX and JNDI, the unsafe
# API that Netty uses, Kerberos and SASL in the PostgreSQL driver, and the zip file system.
FROM build AS runtime-image
RUN set -eu; \
    cd build/install/kotlin-order-api; \
    found="$(jdeps --ignore-missing-deps --multi-release 25 --recursive --print-module-deps -cp 'lib/*' lib/kotlin-order-api-0.1.0.jar)"; \
    echo "modules found by jdeps: $found"; \
    jlink \
      --add-modules "$found,java.logging,java.xml,jdk.crypto.ec,jdk.unsupported,java.naming,java.management,java.security.jgss,java.security.sasl,jdk.zipfs,jdk.naming.dns" \
      --strip-debug --no-header-files --no-man-pages --compress zip-6 \
      --output /runtime

# Plain Alpine, the trimmed runtime and the jars; not root, and no build toolchain.
FROM alpine@sha256:71e9b7bae160ba167fa17414401b94cbcdaf3c32feeab9298bd794a2a0614c35
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
