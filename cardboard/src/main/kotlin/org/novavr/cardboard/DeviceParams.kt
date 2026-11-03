/*
 * NovaVR - Your Smartphone. Your Virtual Workspace.
 * Copyright (C) 2026 NovaVR contributors
 * SPDX-License-Identifier: GPL-3.0-or-later
 */
package org.novavr.cardboard

import com.google.cardboard.proto.CardboardDevice

/** Human readable summary of a headset (viewer) profile. */
data class HeadsetInfo(
    val vendor: String,
    val model: String,
    val interLensDistanceMm: Float,
    val screenToLensDistanceMm: Float,
)

/**
 * Helpers for the serialized `cardboard.DeviceParams` protobuf used by the SDK.
 *
 * NovaVR does not scan viewer QR codes (that needs camera + Play Services), so
 * instead it lets the user fine-tune the most important optical parameter,
 * the lens separation, on top of the selected base profile.
 */
object DeviceParams {

    fun describe(params: ByteArray): HeadsetInfo? = runCatching {
        val p = CardboardDevice.DeviceParams.parseFrom(params)
        HeadsetInfo(
            vendor = p.vendor.ifBlank { "Unknown" },
            model = p.model.ifBlank { "Cardboard viewer" },
            interLensDistanceMm = p.interLensDistance * 1000f,
            screenToLensDistanceMm = p.screenToLensDistance * 1000f,
        )
    }.getOrNull()

    /**
     * Returns [params] with the lens separation replaced. Returns the input
     * unchanged when [interLensDistanceMm] is null or parsing fails.
     */
    fun withInterLensDistance(params: ByteArray, interLensDistanceMm: Float?): ByteArray {
        if (interLensDistanceMm == null) return params
        return runCatching {
            CardboardDevice.DeviceParams.parseFrom(params)
                .toBuilder()
                .setInterLensDistance(interLensDistanceMm / 1000f)
                .build()
                .toByteArray()
        }.getOrDefault(params)
    }
}
