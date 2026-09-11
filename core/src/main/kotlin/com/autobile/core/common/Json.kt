package com.autobile.core.common

import kotlinx.serialization.json.Json

/**
 * Canonical JSON configuration for every persisted or transported Autobile document.
 *
 * Stored skills outlive the app version that wrote them, so reads are lenient
 * (`ignoreUnknownKeys`) to tolerate documents written by a newer build, and writes are
 * explicit (`encodeDefaults`) so a stored skill carries its full meaning instead of
 * inheriting whatever defaults happen to be current when it is read back.
 */
val AutobileJson: Json = Json {
    ignoreUnknownKeys = true
    encodeDefaults = true
    isLenient = true
    allowStructuredMapKeys = true
    classDiscriminator = "kind"
    prettyPrint = false
}

/** Pretty variant used for user-facing skill inspection and debug export. */
val AutobileJsonPretty: Json = Json(AutobileJson) {
    prettyPrint = true
}
