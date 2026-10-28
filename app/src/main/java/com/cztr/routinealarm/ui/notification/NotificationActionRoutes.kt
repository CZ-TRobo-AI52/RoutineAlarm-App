package com.cztr.routinealarm.ui.notification

import java.net.URI
import java.nio.ByteBuffer
import java.nio.CharBuffer
import java.nio.charset.CharacterCodingException
import java.nio.charset.CodingErrorAction
import java.security.MessageDigest
import java.util.Base64

data class NotificationActionRoute(
    val deliveryDataUri: String,
    val scopeKey: String,
    val planRevision: String,
    val stage: MenuStage,
    val choice: MenuChoice,
)
/** UI transport only; URI possession and deterministic key never authorize a Core action. */
object NotificationActionRoutes {
    private const val PREFIX = "routinealarm-ui://notification/v1/"
    private const val MAX_FIELD_BYTES = 8192
    private const val MAX_ROUTE_CHARS = 32768
    private val alphabet = Regex("[A-Za-z0-9_-]+")

    fun encode(route: NotificationActionRoute): String {
        require(listOf(route.deliveryDataUri, route.scopeKey, route.planRevision).all {
            it.length <= MAX_FIELD_BYTES
        }) { "Route field is too large before parsing" }
        val target = try { URI(route.deliveryDataUri) } catch (error: Exception) {
            throw IllegalArgumentException("Invalid delivery URI", error)
        }
        require(target.isAbsolute && !target.isOpaque && !target.rawAuthority.isNullOrBlank())
        require(NotificationMenu.select(route.stage, route.choice, NotificationOperation.entries.toSet())
            != MenuSelection.Rejected) { "Choice does not belong to menu stage" }
        val fields = listOf(route.deliveryDataUri, route.scopeKey, route.planRevision).map(::encodeField)
        return (PREFIX + (fields + route.stage.name + route.choice.name).joinToString("/")).also {
            require(it.length <= MAX_ROUTE_CHARS) { "Notification route is too large" }
        }
    }

    fun decode(data: String): NotificationActionRoute? {
        if (data.length > MAX_ROUTE_CHARS) return null
        return try {
            val uri = URI(data)
            require(uri.scheme == "routinealarm-ui" && uri.rawAuthority == "notification")
            require(uri.rawQuery == null && uri.rawFragment == null)
            val parts = uri.rawPath.split('/')
            require(parts.size == 7 && parts[0].isEmpty() && parts[1] == "v1")
            val result = NotificationActionRoute(decodeField(parts[2]), decodeField(parts[3]),
                decodeField(parts[4]), MenuStage.valueOf(parts[5]), MenuChoice.valueOf(parts[6]))
            require(encode(result) == data) { "Noncanonical notification route" }
            result
        } catch (_: Exception) {
            null
        }
    }

    /** A reproducible transport identity, not a durable receipt, signature or authority. */
    fun requestKey(route: NotificationActionRoute): String {
        val digest = MessageDigest.getInstance("SHA-256").digest(encode(route).toByteArray(Charsets.UTF_8))
        val hex = "0123456789abcdef"
        val encoded = CharArray(digest.size * 2)
        for (index in digest.indices) {
            val value = digest[index].toInt() and 255
            encoded[index * 2] = hex[value ushr 4]
            encoded[index * 2 + 1] = hex[value and 15]
        }
        return "r52-ui-v1-" + String(encoded)
    }

    fun selection(route: NotificationActionRoute, allowed: Set<NotificationOperation>): MenuSelection {
        try { encode(route) } catch (_: IllegalArgumentException) { return MenuSelection.Rejected }
        return NotificationMenu.select(route.stage, route.choice, allowed)
    }

    private fun encodeField(value: String): String {
        require(value.length <= MAX_FIELD_BYTES) { "Route field is too large before encoding" }
        require(value.isNotBlank()) { "Blank route field" }
        val bytes = try {
            Charsets.UTF_8.newEncoder().onMalformedInput(CodingErrorAction.REPORT)
                .onUnmappableCharacter(CodingErrorAction.REPORT).encode(CharBuffer.wrap(value))
        } catch (error: CharacterCodingException) {
            throw IllegalArgumentException("Malformed Unicode route field", error)
        }
        require(bytes.remaining() <= MAX_FIELD_BYTES) { "Route field is too large" }
        val raw = ByteArray(bytes.remaining()).also { bytes.get(it) }
        return Base64.getUrlEncoder().withoutPadding().encodeToString(raw)
    }

    private fun decodeField(value: String): String {
        require(alphabet.matches(value)) { "Invalid route alphabet" }
        val raw = Base64.getUrlDecoder().decode(value)
        require(raw.size <= MAX_FIELD_BYTES)
        val text = Charsets.UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPORT)
            .onUnmappableCharacter(CodingErrorAction.REPORT).decode(ByteBuffer.wrap(raw)).toString()
        require(encodeField(text) == value) { "Noncanonical route field" }
        return text
    }
}
