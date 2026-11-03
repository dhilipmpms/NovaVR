/*
 * NovaVR - Your Smartphone. Your Virtual Workspace.
 * Copyright (C) 2026 NovaVR contributors
 * SPDX-License-Identifier: GPL-3.0-or-later
 */
package org.novavr.app.settings

import android.content.Context
import android.content.SharedPreferences
import androidx.core.content.edit
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update

/** Shape of the virtual screen shown in VR. */
enum class DisplayMode {
    /** Phone-like virtual screen using the device's portrait proportions. */
    PORTRAIT,

    /** Wide virtual screen using the device's landscape proportions. */
    LANDSCAPE;

    fun toggled(): DisplayMode = if (this == PORTRAIT) LANDSCAPE else PORTRAIT
}

/** How mouse / keyboard input reaches the mirrored app. */
enum class InputMode {
    /** Best available: Shizuku if authorised, else Accessibility, else VR only. */
    AUTO,

    /** Accessibility gestures and text actions (least privilege). */
    ACCESSIBILITY,

    /** Shizuku-injected native mouse events (+ Accessibility for typing). */
    SHIZUKU,

    /** Cursor and NovaVR shortcuts only; nothing is sent to other apps. */
    VR_ONLY,
}

/** Right mouse button in Accessibility mode (Shizuku sends a real right click). */
enum class RightClickAction { BACK, LONG_PRESS }

/** Modifier that turns a key into a NovaVR shortcut. */
enum class ShortcutModifier { CTRL_ALT, META, DISABLED }

/**
 * User-adjustable VR configuration. Immutable; persisted by [VRSettingsRepository].
 *
 * @property screenDiagonalInches Diagonal of the virtual screen (like a TV size).
 * @property screenDistanceMeters Distance from the viewer to the virtual screen.
 * @property lensSeparationMm Lens centre separation override, or null to use the
 *   headset profile's value.
 * @property renderScale Resolution of the eye buffer relative to the display.
 * @property preferHighRefreshRate Use the display's fastest refresh rate in VR.
 * @property showEnvironment Draw the floor grid around the virtual screen.
 * @property mirrorResolutionScale Capture resolution relative to the shared
 *   content. Content beyond what the headset can resolve only costs power.
 * @property followContentOrientation Switch Portrait/Landscape automatically
 *   when the mirrored content rotates.
 */
data class VRSettings(
    val displayMode: DisplayMode = DisplayMode.LANDSCAPE,
    val screenDiagonalInches: Float = DEFAULT_DIAGONAL_INCHES,
    val screenDistanceMeters: Float = DEFAULT_DISTANCE_METERS,
    val lensSeparationMm: Float? = null,
    val renderScale: Float = DEFAULT_RENDER_SCALE,
    val preferHighRefreshRate: Boolean = true,
    val showEnvironment: Boolean = true,
    val mirrorResolutionScale: Float = DEFAULT_MIRROR_SCALE,
    val followContentOrientation: Boolean = true,
    // Input (0.3)
    val inputMode: InputMode = InputMode.AUTO,
    val cursorVisible: Boolean = true,
    val cursorSensitivity: Float = 1f,
    val scrollSensitivity: Float = 1f,
    val cursorAutoHideSeconds: Int = 5,
    val rightClickAction: RightClickAction = RightClickAction.BACK,
    val shortcutModifier: ShortcutModifier = ShortcutModifier.CTRL_ALT,
) {
    /** Returns a copy with every value clamped to its supported range. */
    fun sanitized(): VRSettings = copy(
        screenDiagonalInches = screenDiagonalInches.coerceIn(DIAGONAL_RANGE),
        screenDistanceMeters = screenDistanceMeters.coerceIn(DISTANCE_RANGE),
        lensSeparationMm = lensSeparationMm?.coerceIn(LENS_SEPARATION_RANGE),
        renderScale = renderScale.coerceIn(RENDER_SCALE_RANGE),
        mirrorResolutionScale = mirrorResolutionScale.coerceIn(MIRROR_SCALE_RANGE),
        cursorSensitivity = cursorSensitivity.coerceIn(SENSITIVITY_RANGE),
        scrollSensitivity = scrollSensitivity.coerceIn(SENSITIVITY_RANGE),
        cursorAutoHideSeconds = cursorAutoHideSeconds.coerceIn(0, 60),
    )

    companion object {
        const val DEFAULT_DIAGONAL_INCHES = 70f
        const val DEFAULT_DISTANCE_METERS = 2.0f
        const val DEFAULT_RENDER_SCALE = 1.0f
        const val DEFAULT_LENS_SEPARATION_MM = 64f
        const val DEFAULT_MIRROR_SCALE = 0.75f

        /** Steps used by the in-VR size / distance controls. */
        const val DIAGONAL_STEP = 5f
        const val DISTANCE_STEP = 0.25f

        val DIAGONAL_RANGE = 20f..200f
        val DISTANCE_RANGE = 0.5f..6f
        val LENS_SEPARATION_RANGE = 50f..75f
        val RENDER_SCALE_RANGE = 0.5f..1.0f
        val MIRROR_SCALE_RANGE = 0.5f..1.0f
        val SENSITIVITY_RANGE = 0.25f..4f
        val AUTO_HIDE_CHOICES = listOf(0, 3, 5, 10)
    }
}

/**
 * SharedPreferences backed settings store exposing a [StateFlow].
 * Kept dependency-free (no DataStore) on purpose: the data is tiny.
 */
class VRSettingsRepository(context: Context) {

    private val prefs: SharedPreferences =
        context.applicationContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    private val _settings = MutableStateFlow(load())
    val settings: StateFlow<VRSettings> = _settings.asStateFlow()

    val current: VRSettings get() = _settings.value

    fun update(transform: (VRSettings) -> VRSettings) {
        _settings.update { old ->
            transform(old).sanitized().also { if (it != old) save(it) }
        }
    }

    fun resetToDefaults() = update { VRSettings() }

    private fun load(): VRSettings {
        val defaults = VRSettings()
        return VRSettings(
            displayMode = prefs.getString(KEY_MODE, null)
                ?.let { runCatching { DisplayMode.valueOf(it) }.getOrNull() }
                ?: defaults.displayMode,
            screenDiagonalInches = prefs.getFloat(KEY_DIAGONAL, defaults.screenDiagonalInches),
            screenDistanceMeters = prefs.getFloat(KEY_DISTANCE, defaults.screenDistanceMeters),
            lensSeparationMm = if (prefs.contains(KEY_LENS)) prefs.getFloat(KEY_LENS, 0f) else null,
            renderScale = prefs.getFloat(KEY_RENDER_SCALE, defaults.renderScale),
            preferHighRefreshRate = prefs.getBoolean(KEY_HIGH_REFRESH, defaults.preferHighRefreshRate),
            showEnvironment = prefs.getBoolean(KEY_ENVIRONMENT, defaults.showEnvironment),
            mirrorResolutionScale = prefs.getFloat(KEY_MIRROR_SCALE, defaults.mirrorResolutionScale),
            followContentOrientation = prefs.getBoolean(KEY_FOLLOW_CONTENT, defaults.followContentOrientation),
            inputMode = enumPref(KEY_INPUT_MODE, defaults.inputMode),
            cursorVisible = prefs.getBoolean(KEY_CURSOR_VISIBLE, defaults.cursorVisible),
            cursorSensitivity = prefs.getFloat(KEY_CURSOR_SENS, defaults.cursorSensitivity),
            scrollSensitivity = prefs.getFloat(KEY_SCROLL_SENS, defaults.scrollSensitivity),
            cursorAutoHideSeconds = prefs.getInt(KEY_CURSOR_HIDE, defaults.cursorAutoHideSeconds),
            rightClickAction = enumPref(KEY_RIGHT_CLICK, defaults.rightClickAction),
            shortcutModifier = enumPref(KEY_SHORTCUT_MOD, defaults.shortcutModifier),
        ).sanitized()
    }

    private fun save(s: VRSettings) {
        prefs.edit {
            putString(KEY_MODE, s.displayMode.name)
            putFloat(KEY_DIAGONAL, s.screenDiagonalInches)
            putFloat(KEY_DISTANCE, s.screenDistanceMeters)
            if (s.lensSeparationMm != null) putFloat(KEY_LENS, s.lensSeparationMm) else remove(KEY_LENS)
            putFloat(KEY_RENDER_SCALE, s.renderScale)
            putBoolean(KEY_HIGH_REFRESH, s.preferHighRefreshRate)
            putBoolean(KEY_ENVIRONMENT, s.showEnvironment)
            putFloat(KEY_MIRROR_SCALE, s.mirrorResolutionScale)
            putBoolean(KEY_FOLLOW_CONTENT, s.followContentOrientation)
            putString(KEY_INPUT_MODE, s.inputMode.name)
            putBoolean(KEY_CURSOR_VISIBLE, s.cursorVisible)
            putFloat(KEY_CURSOR_SENS, s.cursorSensitivity)
            putFloat(KEY_SCROLL_SENS, s.scrollSensitivity)
            putInt(KEY_CURSOR_HIDE, s.cursorAutoHideSeconds)
            putString(KEY_RIGHT_CLICK, s.rightClickAction.name)
            putString(KEY_SHORTCUT_MOD, s.shortcutModifier.name)
        }
    }

    private inline fun <reified E : Enum<E>> enumPref(key: String, default: E): E =
        prefs.getString(key, null)?.let { name -> enumValues<E>().firstOrNull { it.name == name } } ?: default

    private companion object {
        const val PREFS_NAME = "novavr_settings"
        const val KEY_MODE = "display_mode"
        const val KEY_DIAGONAL = "screen_diagonal_in"
        const val KEY_DISTANCE = "screen_distance_m"
        const val KEY_LENS = "lens_separation_mm"
        const val KEY_RENDER_SCALE = "render_scale"
        const val KEY_HIGH_REFRESH = "prefer_high_refresh"
        const val KEY_ENVIRONMENT = "show_environment"
        const val KEY_MIRROR_SCALE = "mirror_resolution_scale"
        const val KEY_FOLLOW_CONTENT = "follow_content_orientation"
        const val KEY_INPUT_MODE = "input_mode"
        const val KEY_CURSOR_VISIBLE = "cursor_visible"
        const val KEY_CURSOR_SENS = "cursor_sensitivity"
        const val KEY_SCROLL_SENS = "scroll_sensitivity"
        const val KEY_CURSOR_HIDE = "cursor_auto_hide_s"
        const val KEY_RIGHT_CLICK = "right_click_action"
        const val KEY_SHORTCUT_MOD = "shortcut_modifier"
    }
}
