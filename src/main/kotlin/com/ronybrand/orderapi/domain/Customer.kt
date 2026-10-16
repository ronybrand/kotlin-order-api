package com.ronybrand.orderapi.domain

import com.ronybrand.orderapi.error.AppException
import java.time.Instant
import java.util.UUID

// Field limits shared by validation and the database schema (migration V1): the database refuses what the
// domain would refuse, and the numbers cannot drift apart silently.
const val MAX_NAME_LENGTH = 200
const val MAX_EMAIL_LENGTH = 320
const val MAX_TAX_ID_LENGTH = 20
const val MAX_PASSPORT_LENGTH = 9
const val MAX_ACTOR_LENGTH = 200
private const val MIN_TAX_ID_SIGNIFICANT_LENGTH = 5

private val TAX_ID_PATTERN = Regex("[A-Za-z0-9./-]{5,20}")
private val PASSPORT_PATTERN = Regex("[A-Z0-9]{6,9}")

/** The data a client supplies to create or replace a customer. */
data class CustomerInput(val name: String, val taxId: String, val passportNumber: String?, val email: String)

/**
 * A registered customer. The tax id, passport number and e-mail are personal data: they are never logged
 * ([toString] shows only the id) and never returned by the API.
 */
data class Customer(
    val id: UUID,
    val name: String,
    val taxId: String,
    val passportNumber: String?,
    val email: String,
    val version: Long,
    val createdAt: Instant,
    val updatedAt: Instant,
    val createdBy: String,
    val updatedBy: String,
    val deletedAt: Instant? = null,
    val deletedBy: String? = null,
) {
    val isDeleted: Boolean get() = deletedAt != null

    /** Validates the input and overwrites the customer's data (PUT semantics); the creation audit is kept. */
    fun replace(
        input: CustomerInput,
        actor: String,
        now: Instant,
    ): Customer {
        val valid = input.validated()
        return copy(
            name = valid.name,
            taxId = valid.taxId,
            passportNumber = valid.passportNumber,
            email = valid.email,
            updatedAt = now,
            updatedBy = actor,
        )
    }

    override fun toString() = "Customer(id=$id)"

    companion object {
        fun create(
            input: CustomerInput,
            actor: String,
            now: Instant,
            id: UUID = UUID.randomUUID(),
        ): Customer {
            val valid = input.validated()
            return Customer(
                id = id,
                name = valid.name,
                taxId = valid.taxId,
                passportNumber = valid.passportNumber,
                email = valid.email,
                version = 1,
                createdAt = now,
                updatedAt = now,
                createdBy = actor,
                updatedBy = actor,
            )
        }
    }
}

private fun CustomerInput.validated(): CustomerInput {
    if (name.isBlank()) throw AppException.invalidField("name", "name must not be blank")
    if (name.codePointCount(0, name.length) >
        MAX_NAME_LENGTH
    ) {
        throw AppException.invalidField("name", "name is too long")
    }

    if (!TAX_ID_PATTERN.matches(taxId)) throw AppException.invalidField("tax_id", "tax_id has an invalid format")
    val canonical = canonicalTaxId(taxId)
    if (canonical.length < MIN_TAX_ID_SIGNIFICANT_LENGTH) {
        throw AppException.invalidField("tax_id", "tax_id must contain at least 5 letters or digits")
    }

    val passport = passportNumber?.takeIf { it.isNotEmpty() }
    if (passport != null && !PASSPORT_PATTERN.matches(passport)) {
        throw AppException.invalidField("passport_number", "passport_number has an invalid format")
    }

    if (!EmailAddress.isValid(email)) throw AppException.invalidField("email", "email is invalid")
    return CustomerInput(name = name, taxId = canonical, passportNumber = passport, email = email)
}

/**
 * Keeps only ASCII letters and digits, upper-cased: "123.456-78" and "12345678" are the same document, so the
 * stored value (and the uniqueness check) always uses this form.
 */
fun canonicalTaxId(raw: String): String = raw.filter { it in 'A'..'Z' || it in 'a'..'z' || it in '0'..'9' }.uppercase()
