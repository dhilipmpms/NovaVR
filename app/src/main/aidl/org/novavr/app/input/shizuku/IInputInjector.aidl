// NovaVR - Shizuku user service contract (runs as the shell uid).
// SPDX-License-Identifier: GPL-3.0-or-later
package org.novavr.app.input.shizuku;

interface IInputInjector {
    /** Called by Shizuku when the service is unbound. */
    void destroy() = 16777114;

    /** uid of the service process (2000 = shell). */
    int uid() = 1;

    /**
     * Injects a mouse MotionEvent at display coordinates (x, y).
     * action: MotionEvent action; buttonState / actionButton: MotionEvent button flags.
     * Returns false if the system rejected the event.
     */
    boolean injectMouse(int action, float x, float y, int buttonState, int actionButton,
            float vScroll, float hScroll, long downTime) = 2;

    /** Injects a key event (goes to the focused window, or is handled by the system). */
    boolean injectKey(int action, int keyCode, int metaState) = 3;

    /** Adds NovaVR's accessibility service to the enabled list. Returns an error or null. */
    String enableAccessibilityService(String flattenedComponent) = 4;
}
