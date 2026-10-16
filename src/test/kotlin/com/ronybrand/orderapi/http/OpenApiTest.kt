package com.ronybrand.orderapi.http

import com.ronybrand.orderapi.error.Codes
import org.yaml.snakeyaml.Yaml
import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** The OpenAPI document is a contract: a test fails when it and the router stop describing the same API. */
class OpenApiTest {
    private val document: Map<String, Any?> by lazy {
        @Suppress("UNCHECKED_CAST")
        Yaml().load<Map<String, Any?>>(File("api/openapi.yaml").readText()) as Map<String, Any?>
    }

    private val httpMethods = setOf("get", "put", "post", "delete", "patch")

    @Test
    fun `the document describes exactly the registered routes`() {
        @Suppress("UNCHECKED_CAST")
        val paths = document.getValue("paths") as Map<String, Map<String, Any?>>
        val documented =
            paths.flatMap { (path, operations) ->
                operations.keys.filter { it in httpMethods }.map { "${it.uppercase()} $path" }
            }.toSet()
        val registered = routeTable(ApiFixture().dependencies()).map { (method, path, _) ->
            "${method.value} $path"
        }.toSet()

        assertEquals(registered, documented)
    }

    @Test
    fun `every route that needs no authentication is documented as public and no other is`() {
        @Suppress("UNCHECKED_CAST")
        val paths = document.getValue("paths") as Map<String, Map<String, Map<String, Any?>>>
        val publicInDocument =
            paths.flatMap { (path, operations) ->
                operations
                    .filterKeys { it in httpMethods }
                    .filter { (_, operation) -> operation["security"] == emptyList<Any>() }
                    .map { (method, _) -> "${method.uppercase()} $path" }
            }.toSet()
        val publicInRouter =
            routeTable(ApiFixture().dependencies())
                .filter { (_, _, access) -> access == Access.PUBLIC }
                .map { (method, path, _) -> "${method.value} $path" }
                .toSet()

        assertEquals(publicInRouter, publicInDocument)
    }

    @Test
    fun `the error schema documents every code of the catalog`() {
        val text = File("api/openapi.yaml").readText()
        val codes = Codes::class.java.declaredFields.filter {
            it.type == String::class.java
        }.map { it.get(null) as String }

        val missing = codes.filterNot { text.contains(it) }

        assertTrue(missing.isEmpty(), "codes missing from api/openapi.yaml: $missing")
    }
}
