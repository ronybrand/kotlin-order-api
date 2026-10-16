package com.ronybrand.orderapi

import java.io.File
import kotlin.test.Test
import kotlin.test.assertTrue

/** The dependency rule of docs/adr/0001: the arrows point inwards, and a test fails when one points outwards. */
class ArchitectureTest {
    private val root = File("src/main/kotlin/com/ronybrand/orderapi")

    private fun importsOf(layer: String): Map<String, List<String>> =
        root
            .resolve(layer)
            .walkTopDown()
            .filter { it.isFile && it.extension == "kt" }
            .associate { file ->
                file.name to file.readLines().filter { it.startsWith("import ") }.map { it.removePrefix("import ") }
            }

    private fun assertNoImports(
        layer: String,
        forbidden: List<String>,
    ) {
        val violations =
            importsOf(layer).flatMap { (file, imports) ->
                imports.filter { import -> forbidden.any { import.startsWith(it) } }.map { "$layer/$file imports $it" }
            }
        assertTrue(violations.isEmpty(), violations.joinToString("\n"))
    }

    @Test
    fun `the source tree is where the test expects it`() {
        assertTrue(root.resolve("domain").isDirectory, "run the tests from the project root")
    }

    @Test
    fun `domain depends on nothing but the error model`() {
        assertNoImports(
            "domain",
            listOf(
                "com.ronybrand.orderapi.app",
                "com.ronybrand.orderapi.persistence",
                "com.ronybrand.orderapi.http",
                "com.ronybrand.orderapi.messaging",
                "io.ktor",
                "org.jetbrains.exposed",
                "com.zaxxer",
                "org.flywaydb",
                "org.postgresql",
                "com.rabbitmq",
            ),
        )
    }

    @Test
    fun `use cases know the domain but no infrastructure`() {
        assertNoImports(
            "app",
            listOf(
                "com.ronybrand.orderapi.persistence",
                "com.ronybrand.orderapi.http",
                "com.ronybrand.orderapi.messaging",
                "io.ktor",
                "org.jetbrains.exposed",
                "com.zaxxer",
                "org.flywaydb",
                "org.postgresql",
                "com.rabbitmq",
            ),
        )
    }
}
