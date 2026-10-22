# syntax=docker/dockerfile:1
# The tag is pinned, not just the digest: the runtime stage below is Alpine, so the jlink runtime must come
# from the musl (Alpine) variant of the JDK. A runtime built from the glibc one has no ELF interpreter on
# Alpine and the container dies with "java: not found". The bare eclipse-temurin:latest tag has pointed at
# both variants (same JDK, different base OS), so naming the Alpine tag keeps a retag from breaking the image.
FROM eclipse-temurin:25-jdk-alpine@sha256:1e20ead9eec7234db293f1913438090d533ed47c0b9e12dc8c7627a802a44ed6 AS build
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
# The tag rides along with the digest: it names the image the apk call below expects, which a bare digest hides
# from anyone reading the file. No version in this comment: Dependabot bumps the FROM line, not the prose, so a
# number written here would silently go stale.
FROM alpine:3.24@sha256:71e9b7bae160ba167fa17414401b94cbcdaf3c32feeab9298bd794a2a0614c35
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
