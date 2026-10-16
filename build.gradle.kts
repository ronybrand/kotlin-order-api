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
    target {
        // The integration tests check internals too (the search whitelist against the real schema).
        val main = compilations.getByName("main")
        compilations.configureEach {
            if (name == "integrationTest") associateWith(main)
        }
    }
}

application {
    mainClass.set("com.ronybrand.orderapi.ApiMain")
}

repositories {
    mavenCentral()
}

// The notification worker ships in the same artifact as the API; `./gradlew runWorker` starts it locally.
tasks.register<JavaExec>("runWorker") {
    group = "application"
    description = "Runs the notification worker (needs BROKER_URL and SMTP_HOST/SMTP_FROM)."
    classpath = sourceSets["main"].runtimeClasspath
    mainClass.set("com.ronybrand.orderapi.WorkerMain")
}

dependencies {
    implementation(libs.ktor.server.core)
    implementation(libs.ktor.server.netty)
    implementation(libs.ktor.server.status.pages)
    implementation(libs.ktor.server.body.limit)
    implementation(libs.ktor.server.cors)
    implementation(libs.serialization.json)
    implementation(libs.coroutines.core)
    implementation(libs.exposed.core)
    implementation(libs.exposed.jdbc)
    implementation(libs.exposed.java.time)
    implementation(libs.flyway.core)
    runtimeOnly(libs.flyway.postgresql)
    implementation(libs.hikari)
    implementation(libs.postgresql)
    implementation(libs.java.jwt)
    implementation(libs.amqp.client)
    implementation(libs.angus.mail)
    implementation(libs.logback)

    testImplementation(libs.kotlin.test.junit5)
    testImplementation(libs.ktor.server.test.host)
    testImplementation(libs.snakeyaml)
}

testing {
    suites {
        // Unit and HTTP tests: no database, no broker, no network. This is what the coverage floor measures.
        named<JvmTestSuite>("test") {
            useJUnitJupiter(libs.versions.junit)
        }
        // Needs a real PostgreSQL (embedded, downloaded once) and, for the broker test, a Docker daemon.
        register<JvmTestSuite>("integrationTest") {
            useJUnitJupiter(libs.versions.junit)
            dependencies {
                implementation(project())
                implementation(libs.kotlin.test.junit5)
                implementation(libs.ktor.server.test.host)
                implementation(libs.embedded.postgres)
                implementation(libs.greenmail)
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
                    shouldRunAfter(tasks.named("test"))
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
            classes("com.ronybrand.orderapi.ApiMain", "com.ronybrand.orderapi.WorkerMain")
        }
        verify {
            rule {
                minBound(80)
            }
        }
    }
}
