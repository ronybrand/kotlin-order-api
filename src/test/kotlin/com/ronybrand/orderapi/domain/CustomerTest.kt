package com.ronybrand.orderapi.domain

import com.ronybrand.orderapi.NOW
import com.ronybrand.orderapi.assertCode
import com.ronybrand.orderapi.error.Codes
import java.time.Duration
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class CustomerTest {
    private fun validInput() =
        CustomerInput(
            name = "Ada Lovelace",
            taxId = "AB123456",
            passportNumber = null,
            email = "ada@example.com",
        )

    @Test
    fun `a valid input builds a customer with its audit fields`() {
        val customer = Customer.create(validInput(), "admin-1", NOW)

        assertEquals("admin-1", customer.createdBy)
        assertEquals("admin-1", customer.updatedBy)
        assertEquals(NOW, customer.createdAt)
        assertEquals(NOW, customer.updatedAt)
        assertEquals(1L, customer.version)
        assertNull(customer.deletedAt)
    }

    @Test
    fun `the tax id is stored in its canonical form`() {
        val expected =
            mapOf("123.456-78" to "12345678", "ab-12.cd/9" to "AB12CD9", "abcde" to "ABCDE", "AB123456" to "AB123456")

        for ((raw, canonical) in expected) {
            assertEquals(canonical, Customer.create(validInput().copy(taxId = raw), "a", NOW).taxId, raw)
        }
    }

    @Test
    fun `formatting differences are the same document`() {
        val first = Customer.create(validInput().copy(taxId = "123.456-78"), "a", NOW)
        val second = Customer.create(validInput().copy(taxId = "12345678"), "a", NOW)

        assertEquals(first.taxId, second.taxId)
    }

    @Test
    fun `invalid input is VALIDATION-01 on the offending field`() {
        val cases: Map<String, Pair<CustomerInput, String>> =
            mapOf(
                "blank name" to (validInput().copy(name = "  \t") to "name"),
                "name above the column" to (validInput().copy(name = "a".repeat(201)) to "name"),
                "tax id too short" to (validInput().copy(taxId = "1234") to "tax_id"),
                "tax id too long" to (validInput().copy(taxId = "1".repeat(21)) to "tax_id"),
                "tax id with a trailing newline" to (validInput().copy(taxId = "12345678\n") to "tax_id"),
                "tax id with only punctuation" to (validInput().copy(taxId = ".....") to "tax_id"),
                "tax id with fewer than 5 alphanumerics" to (validInput().copy(taxId = "A.B-C") to "tax_id"),
                "lower-case passport" to (validInput().copy(passportNumber = "ab123456") to "passport_number"),
                "short passport" to (validInput().copy(passportNumber = "AB123") to "passport_number"),
                "empty email" to (validInput().copy(email = "") to "email"),
                "email without domain" to (validInput().copy(email = "ada") to "email"),
                "email with a display name" to (validInput().copy(email = "Ada <ada@example.com>") to "email"),
                "email list" to (validInput().copy(email = "a@example.com, b@example.com") to "email"),
                "email with a line break" to (validInput().copy(email = "a@example.com\r\nBcc: x@y.com") to "email"),
                "email above the column" to (validInput().copy(email = "a".repeat(310) + "@example.com") to "email"),
                "email with an empty label" to (validInput().copy(email = "a@example..com") to "email"),
            )

        for ((name, case) in cases) {
            val (input, field) = case
            val error = assertCode(Codes.INVALID_REQUEST) { Customer.create(input, "a", NOW) }
            assertEquals(field, error.params["field"], name)
        }
    }

    @Test
    fun `ordinary e-mail addresses are accepted`() {
        for (email in listOf("ada@example.com", "ada.lovelace+orders@sub.example.co.uk", "a_b-c@example.io")) {
            assertEquals(email, Customer.create(validInput().copy(email = email), "a", NOW).email)
        }
    }

    @Test
    fun `the passport is optional and an empty one counts as absent`() {
        assertNull(Customer.create(validInput().copy(passportNumber = null), "a", NOW).passportNumber)
        assertNull(Customer.create(validInput().copy(passportNumber = ""), "a", NOW).passportNumber)
        assertEquals(
            "AB123456",
            Customer.create(validInput().copy(passportNumber = "AB123456"), "a", NOW).passportNumber,
        )
    }

    @Test
    fun `replace keeps the creation audit and stamps the editor`() {
        val customer = Customer.create(validInput(), "creator", NOW)
        val later = NOW.plus(Duration.ofHours(1))

        val replaced = customer.replace(validInput().copy(name = "New Name"), "editor", later)

        assertEquals("New Name", replaced.name)
        assertEquals("creator", replaced.createdBy)
        assertEquals(NOW, replaced.createdAt)
        assertEquals("editor", replaced.updatedBy)
        assertEquals(later, replaced.updatedAt)
        assertEquals(customer.id, replaced.id)
        assertEquals(customer.version, replaced.version)
    }

    @Test
    fun `replace with invalid input fails and the original is untouched`() {
        val customer = Customer.create(validInput(), "creator", NOW)

        assertCode(Codes.INVALID_REQUEST) { customer.replace(validInput().copy(name = ""), "editor", NOW) }

        assertEquals("Ada Lovelace", customer.name)
        assertEquals("creator", customer.updatedBy)
    }

    @Test
    fun `an active customer is not deleted`() {
        val customer = Customer.create(validInput(), "a", NOW)

        assertFalse(customer.isDeleted)
        assertTrue(customer.copy(deletedAt = NOW, deletedBy = "a").isDeleted)
    }

    @Test
    fun `toString exposes only the id because the rest is personal data`() {
        val customer = Customer.create(validInput().copy(passportNumber = "AB123456"), "a", NOW)

        val text = customer.toString()

        assertTrue(text.contains(customer.id.toString()))
        for (secret in listOf("ada@example.com", "AB123456", "Ada Lovelace")) {
            assertFalse(text.contains(secret), "toString leaked $secret")
        }
        assertNotNull(customer.id)
    }
}
