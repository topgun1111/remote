package com.remotecontrol.shared

import java.security.MessageDigest
import java.security.SecureRandom

/** Tiny persistence abstraction so the pairing logic stays pure JVM (and unit-testable). */
interface KeyValueStore {
    fun get(key: String): String?
    fun put(key: String, value: String?)
}

sealed class AuthResult {
    /** [newToken] is non-null only right after a successful PIN pairing. */
    data class Accepted(val newToken: String?) : AuthResult()
    data class Rejected(val reason: String, val needsPairing: Boolean) : AuthResult()
}

/**
 * Agent-side authority: owns the pairing PIN and the set of paired-controller tokens.
 *
 * - PIN is 6 digits, single use (regenerated after each successful pairing), and regenerated
 *   after [maxPinAttempts] consecutive wrong guesses.
 * - On a correct PIN a random 256-bit token is issued. Only its SHA-256 is stored on the Agent.
 * - Later connections authenticate with the token alone.
 */
class PairingAuthority(
    private val store: KeyValueStore,
    private val random: SecureRandom = SecureRandom(),
    private val maxPinAttempts: Int = 5,
) {
    private var failedPinAttempts = 0

    @Synchronized
    fun currentPin(): String = store.get(KEY_PIN) ?: regeneratePin()

    @Synchronized
    fun regeneratePin(): String {
        val pin = random.nextInt(1_000_000).toString().padStart(6, '0')
        store.put(KEY_PIN, pin)
        failedPinAttempts = 0
        return pin
    }

    @Synchronized
    fun pairedCount(): Int = hashes().size

    @Synchronized
    fun revokeAll() {
        store.put(KEY_TOKENS, null)
        regeneratePin()
    }

    @Synchronized
    fun authenticate(hello: Hello): AuthResult {
        if (hello.protocolVersion != PROTOCOL_VERSION) {
            return AuthResult.Rejected(
                "Protocol version mismatch (agent=$PROTOCOL_VERSION, controller=${hello.protocolVersion}). Update both apps.",
                needsPairing = false,
            )
        }

        hello.token?.let { token ->
            val hash = sha256(token)
            val known = hashes().any { MessageDigest.isEqual(it.toByteArray(), hash.toByteArray()) }
            return if (known) AuthResult.Accepted(null)
            else AuthResult.Rejected("This phone is no longer paired. Pair again with the PIN.", needsPairing = true)
        }

        val pin = hello.pin ?: return AuthResult.Rejected("PIN required.", needsPairing = true)
        val expected = currentPin()
        if (MessageDigest.isEqual(pin.toByteArray(), expected.toByteArray())) {
            val token = newToken()
            store.put(KEY_TOKENS, (hashes() + sha256(token)).joinToString(","))
            regeneratePin() // single-use PIN
            return AuthResult.Accepted(token)
        }

        failedPinAttempts++
        return if (failedPinAttempts >= maxPinAttempts) {
            regeneratePin()
            AuthResult.Rejected("Too many wrong PINs. A new PIN was generated on the Agent.", needsPairing = true)
        } else {
            AuthResult.Rejected("Wrong PIN.", needsPairing = true)
        }
    }

    private fun hashes(): List<String> =
        store.get(KEY_TOKENS)?.split(",")?.filter { it.isNotBlank() } ?: emptyList()

    private fun newToken(): String {
        val bytes = ByteArray(32).also { random.nextBytes(it) }
        return bytes.joinToString("") { "%02x".format(it) }
    }

    private fun sha256(value: String): String =
        MessageDigest.getInstance("SHA-256").digest(value.toByteArray())
            .joinToString("") { "%02x".format(it) }

    private companion object {
        const val KEY_PIN = "pairing_pin"
        const val KEY_TOKENS = "token_hashes"
    }
}
