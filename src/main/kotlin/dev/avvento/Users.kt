package dev.avvento

import io.ktor.server.application.ApplicationCall
import java.security.MessageDigest

data class User(val id: String, val name: String, val key: String)

/**
 * Per ora l'app e' solo per due persone: ognuna ha un link personale
 * con una chiave segreta (?k=...). Il browser la salva e la manda
 * nell'header "X-Key" a ogni richiesta.
 */
class UserDirectory(private val users: List<User>) {

    fun findByKey(key: String?): User? {
        if (key.isNullOrBlank()) return null
        // Confronto "a tempo costante": non rivela quanti caratteri sono giusti.
        return users.firstOrNull { MessageDigest.isEqual(it.key.toByteArray(), key.toByteArray()) }
    }

    fun other(user: User): User? = users.firstOrNull { it.id != user.id }
}

fun ApplicationCall.keyHeader(): String? = request.headers["X-Key"]
