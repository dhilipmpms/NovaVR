package org.novavr.app.input

import android.view.KeyEvent
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import org.novavr.app.settings.ShortcutModifier

class ShortcutsTest {
    private val ctrlAlt = KeyEvent.META_CTRL_ON or KeyEvent.META_ALT_ON

    @Test
    fun ctrlAltShortcutsMatchOnlyWithBothModifiers() {
        assertEquals(NovaCommand.RECENTER, Shortcuts.novaCommand(KeyEvent.KEYCODE_R, ctrlAlt, ShortcutModifier.CTRL_ALT))
        assertNull(Shortcuts.novaCommand(KeyEvent.KEYCODE_R, KeyEvent.META_CTRL_ON, ShortcutModifier.CTRL_ALT))
        assertNull(Shortcuts.novaCommand(KeyEvent.KEYCODE_R, 0, ShortcutModifier.CTRL_ALT))
    }

    @Test
    fun shiftIsIgnoredSoPlusWorks() {
        assertEquals(
            NovaCommand.BIGGER,
            Shortcuts.novaCommand(KeyEvent.KEYCODE_EQUALS, ctrlAlt or KeyEvent.META_SHIFT_ON, ShortcutModifier.CTRL_ALT),
        )
    }

    @Test
    fun metaModifierAndDisabled() {
        assertEquals(NovaCommand.HIDE_VR, Shortcuts.novaCommand(KeyEvent.KEYCODE_H, KeyEvent.META_META_ON, ShortcutModifier.META))
        assertNull(Shortcuts.novaCommand(KeyEvent.KEYCODE_H, ctrlAlt, ShortcutModifier.META))
        assertNull(Shortcuts.novaCommand(KeyEvent.KEYCODE_H, ctrlAlt, ShortcutModifier.DISABLED))
    }

    @Test
    fun systemKeys() {
        assertEquals(SystemAction.BACK, Shortcuts.systemAction(KeyEvent.KEYCODE_ESCAPE, 0))
        assertNull(Shortcuts.systemAction(KeyEvent.KEYCODE_ESCAPE, KeyEvent.META_CTRL_ON))
        assertEquals(SystemAction.RECENTS, Shortcuts.systemAction(KeyEvent.KEYCODE_TAB, KeyEvent.META_ALT_ON))
        assertNull(Shortcuts.systemAction(KeyEvent.KEYCODE_TAB, 0)) // plain Tab goes to the text field
        assertEquals(SystemAction.HOME, Shortcuts.systemAction(KeyEvent.KEYCODE_ENTER, KeyEvent.META_META_ON))
        assertEquals(SystemAction.NOTIFICATIONS, Shortcuts.systemAction(KeyEvent.KEYCODE_N, KeyEvent.META_META_ON))
    }
}
