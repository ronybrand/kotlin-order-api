package com.ronybrand.orderapi.domain

/**
 * Accepts only a bare address (`local@domain`). A display name, a comment, a quoted local part or a list of
 * addresses are not an e-mail here, and control characters would allow header injection when the address ends
 * up in a message.
 */
object EmailAddress {
    private const val MAX_LOCAL_LENGTH = 64
    private const val MAX_DOMAIN_LENGTH = 255
    private const val MAX_LABEL_LENGTH = 63

    private val LOCAL_ATOM = Regex("[A-Za-z0-9!#$%&'*+/=?^_`{|}~-]+")
    private val DOMAIN_LABEL = Regex("[A-Za-z0-9]([A-Za-z0-9-]*[A-Za-z0-9])?")

    fun isValid(raw: String): Boolean {
        if (raw.isEmpty() || raw.length > MAX_EMAIL_LENGTH) return false
        val at = raw.lastIndexOf('@')
        if (at < 1 || at == raw.length - 1) return false
        val local = raw.substring(0, at)
        val domain = raw.substring(at + 1)
        return local.length <= MAX_LOCAL_LENGTH &&
            local.split('.').all { LOCAL_ATOM.matches(it) } &&
            domain.length <= MAX_DOMAIN_LENGTH &&
            domain.split('.').all { it.length <= MAX_LABEL_LENGTH && DOMAIN_LABEL.matches(it) }
    }
}
