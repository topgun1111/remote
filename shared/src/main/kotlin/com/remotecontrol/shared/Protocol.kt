package com.remotecontrol.shared

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/** v2 (Session 2): HELLO carries pin/token. v3 (Session 3): CAPTURE_CONFIG / CAPTURE_STATUS added. */
const val PROTOCOL_VERSION = 3
const val DEFAULT_PORT = 8765

@Serializable
sealed class Message

/**
 * Controller -> Agent. Exactly one of [pin] (first-time pairing) or [token] (already paired) is set.
 */
@Serializable
@SerialName("HELLO")
data class Hello(
    val protocolVersion: Int = PROTOCOL_VERSION,
    val deviceName: String,
    val pin: String? = null,
    val token: String? = null,
) : Message()

/**
 * Agent -> Controller. When pairing succeeds, [token] holds the long-lived secret the Controller
 * must store and present on future connections. [needsPairing] tells the Controller that
 * retrying with the same credentials is pointless.
 */
@Serializable
@SerialName("HELLO_ACK")
data class HelloAck(
    val accepted: Boolean,
    val deviceName: String,
    val token: String? = null,
    val error: String? = null,
    val needsPairing: Boolean = false,
) : Message()

@Serializable
@SerialName("SCREEN_FRAME")
data class ScreenFrame(
    val width: Int,
    val height: Int,
    val jpegBase64: String,
    val timestampMs: Long = 0,
) : Message()

/** Controller -> Agent. Tunes screen streaming; the Agent clamps values to sane ranges. */
@Serializable
@SerialName("CAPTURE_CONFIG")
data class CaptureConfig(
    val fps: Int = 5,
    val quality: Int = 60,
    val maxDimension: Int = 720,
) : Message()

/** Agent -> Controller. Sent after HELLO_ACK and whenever capture starts, stops or is reconfigured. */
@Serializable
@SerialName("CAPTURE_STATUS")
data class CaptureStatus(
    val active: Boolean,
    val width: Int = 0,
    val height: Int = 0,
    val fps: Int = 0,
    val quality: Int = 0,
) : Message()

/** Coordinates are normalized 0..1 of the frame. [kind]: tap, long_press, swipe, scroll, key, text. */
@Serializable
@SerialName("INPUT_EVENT")
data class InputEvent(
    val kind: String,
    val x: Float = 0f,
    val y: Float = 0f,
    val x2: Float = 0f,
    val y2: Float = 0f,
    val durationMs: Long = 0,
    val text: String? = null,
    val key: String? = null,
) : Message()

@Serializable
@SerialName("WAKE")
data class Wake(val unlock: Boolean = true) : Message()

@Serializable
@SerialName("LAUNCH_APP")
data class LaunchApp(val packageName: String) : Message()

object Protocol {
    val json = Json {
        classDiscriminator = "type"
        ignoreUnknownKeys = true
        encodeDefaults = true
    }

    fun encode(message: Message): String = json.encodeToString(Message.serializer(), message)
    fun decode(text: String): Message = json.decodeFromString(Message.serializer(), text)
}
