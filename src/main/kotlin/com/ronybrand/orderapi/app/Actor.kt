package com.ronybrand.orderapi.app

/** Recorded in the audit columns when no user is attached (background work). */
const val SYSTEM_ACTOR = "system"

/**
 * The authenticated caller. The audit columns (created_by...) always come from here: only the application
 * knows who made the request.
 */
data class Actor(
    val id: String,
    val roles: Set<String> = emptySet(),
) {
    fun hasRole(role: String) = role in roles

    companion object {
        val SYSTEM = Actor(SYSTEM_ACTOR)
    }
}
