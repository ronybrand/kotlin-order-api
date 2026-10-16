plugins {
    alias(libs.plugins.kotlin.jvm)
    alias(libs.plugins.kotlin.serialization)
    alias(libs.plugins.kover)
    alias(libs.plugins.ktlint)
    application
}

group = "com.ronybrand"
version = "0.1.0"

kotlin {
    jvmToolchain(25)
    compilerOptions {
        // A warning that is not fixed is a warning nobody reads.
        allWarningsAsErrors.set(true)
    }
}

application {
    mainClass.set("com.ronybrand.orderapi.ApiMainKt")
}

repositories {
    mavenCentral()
}

dependencies {
    implementation(libs.ktor.server.core)
    implementation(libs.ktor.server.netty)
    implementation(libs.ktor.server.content.negotiation)
    implementation(libs.ktor.server.status.pages)
    implementation(libs.ktor.server.body.limit)
    implementation(libs.ktor.server.cors)
    implementation(libs.ktor.serialization.json)
    implementation(libs.serialization.json)
    implementation(libs.coroutines.core)
    implementation(libs.exposed.core)
    implementation(libs.exposed.jdbc)
    implementation(libs.exposed.java.time)
    implementation(libs.flyway.core)
    runtimeOnly(libs.flyway.postgresql)
    implementation(libs.hikari)
    runtimeOnly(libs.postgresql)
    implementation(libs.java.jwt)
    implementation(libs.amqp.client)
    implementation(libs.angus.mail)
    implementation(libs.logback)

    testImplementation(libs.kotlin.test.junit5)
    testImplementation(libs.ktor.server.test.host)
}

testing {
    suites {
        // Unit and HTTP tests: no database, no broker, no network. This is what the coverage floor measures.
        val test by getting(JvmTestSuite::class) {
            useJUnitJupiter(libs.versions.junit)
        }
        // Needs a real PostgreSQL (embedded, downloaded once) and, for the broker test, a Docker daemon.
        val integrationTest by registering(JvmTestSuite::class) {
            useJUnitJupiter(libs.versions.junit)
            dependencies {
                implementation(project())
                implementation(libs.kotlin.test.junit5)
                implementation(libs.ktor.server.test.host)
                implementation(libs.embedded.postgres)
                implementation(libs.testcontainers.rabbitmq)
                implementation(libs.testcontainers.junit)
                implementation(libs.exposed.core)
                implementation(libs.exposed.jdbc)
                implementation(libs.hikari)
                implementation(libs.java.jwt)
                implementation(libs.serialization.json)
                implementation(libs.coroutines.core)
                implementation(libs.amqp.client)
            }
            targets.all {
                testTask.configure {
                    shouldRunAfter(test)
                }
            }
        }
    }
}

tasks.named("check") {
    dependsOn(testing.suites.named("integrationTest"))
}

ktlint {
    version.set("1.8.0")
}

kover {
    reports {
        filters.excludes {
            // Process entry points only wire the pieces together; the integration tests exercise them.
            classes("com.ronybrand.orderapi.ApiMainKt", "com.ronybrand.orderapi.WorkerMainKt")
        }
        verify {
            rule {
                minBound(80)
            }
        }
    }
}
