# Mouse & keyboard in NovaVR 0.3: input routing and Android restrictions

This document explains how a physical mouse and keyboard reach the mirrored
app while NovaVR shows it in VR. It covers which Android rules shape the
design, and what was verified on the Motorola Edge 60 Fusion (Android 17).

## The routing problem

While mirroring, three things share one display:

```
 ┌───────────────────────────────┐  NovaVR VR overlay (full screen, focused)
 │  stereo view of the shared app│
 └───────────────────────────────┘
 ┌───────────────────────────────┐  shared app (resumed, drawing, captured)
 └───────────────────────────────┘
```

Android routes input by two rules:

* **Pointer events** (mouse, touch, injected gestures) go to the window *under
  the coordinates*.
* **Key events** go to the window that has *focus*.

MediaProjection only *captures* pixels. It doesn't let NovaVR send input back
to the captured app, so every path back has to be built explicitly.

## Design

| Concern | Mechanism | Why |
|---|---|---|
| Hide the system arrow, read the mouse | **Pointer capture** on the VR overlay | Android draws the arrow on the physical panel, so it would float in one eye. Capture hides it and delivers relative motion. It requires window focus. |
| Virtual cursor | Rendered in VR on the virtual screen plane, from one snapshot per frame | Both eyes draw the same cursor state. It is positioned in the fitted content rectangle, so it stays aligned at any size, distance or mode. |
| Clicks, drags, scroll into the app | **Accessibility `dispatchGesture`**: live touch strokes (down on press, continued segments on move, up on release). The wheel becomes short drags that end with a hold. | Least privilege. Works for any app that accepts touch. |
| Let injected touches reach the app | Overlay made **non-touchable** while a mouse drives it, **and** hosted as a **`TYPE_ACCESSIBILITY_OVERLAY`** | See "untrusted touch" below. |
| Typing | **Accessibility node actions** on the app's focused text field: `SET_TEXT`, `SET_SELECTION`, `COPY` / `CUT` / `PASTE`, `IME_ENTER`, focus search for Tab | Keys go to the *focused* window, which is NovaVR because pointer capture needs focus. Re-injected keys would come back to NovaVR too. |
| System navigation | Accessibility global actions (Back, Home, Recents, Notifications) | Works regardless of focus. |
| NovaVR's own UI | Keyboard shortcuts (configurable modifier) and a **VR dock** below the virtual screen, reached by moving the cursor past the bottom edge | Physical touch controls are unusable in a headset, and pass-through while a mouse is active. |
| Optional native mouse | **Shizuku** user service (shell uid) → `InputManagerGlobal.injectInputEvent` with `SOURCE_MOUSE` events | Real hover, right click and wheel for apps that support a mouse. |

### Event classification (`InputRouter`)

1. **NovaVR interface.** A shortcut such as `Ctrl+Alt+R`, or a click while the
   cursor is over the dock, is handled by NovaVR and consumed.
2. **System navigation.** Esc / keyboard Back / mouse Back button → Back.
   Meta+Enter → Home. Alt+Tab / Meta+Tab → Recents. Meta+N → Notifications.
3. **Mirrored app.**
   * Mouse events go to the pointer backend.
   * Keys go to the focused text field.
   * With no text field focused, Page Up/Down, Space and the arrow keys scroll
     the content under the cursor.
4. **Everything else** isn't consumed: volume and media keys keep their normal
   behaviour. Keys from the on-screen/system devices (not physical keyboards)
   are never routed.

## Android restrictions found during testing

1. **Untrusted touch occlusion (Android 12+).** A touch that passes *through*
   a non-touchable window of another app is dropped when that window is
   opaque. Verified on the device:
   `InputDispatcher: Dropping untrusted touch event due to /<NovaVR uid>`. The
   exception is accessibility overlays. NovaVR therefore hosts its VR window
   as `TYPE_ACCESSIBILITY_OVERLAY` whenever its Accessibility service is on,
   and re-hosts it automatically if the service is switched on or off during
   a session. With only "Display over other apps", VR viewing works but clicks
   can't reach the app.
2. **Pointer capture requires focus.** Hardware keys therefore go to NovaVR,
   and *injected* keys (including via Shizuku/`adb input`) would also go to
   NovaVR. So typing uses node actions, not key injection. Only keys the
   system handles before dispatch, Home and Recents, work through Shizuku
   key injection.
3. **A captured mouse changes its input source.** While captured, Android
   reports the mouse as `SOURCE_MOUSE_RELATIVE` instead of `SOURCE_MOUSE`.
   Device detection must count both; otherwise capture "removes" the mouse and
   releases itself in a loop. This happened in testing and is fixed.
4. **Keyboard classification (Android 15+).** New keyboards may be reported as
   `KEYBOARD_TYPE_NON_ALPHABETIC` until letters have been typed. NovaVR detects
   keyboards by whether they have letter keys. Combo devices (keyboard +
   touchpad/mouse) appear as **one** merged input device.
5. **Accessibility window bounds can be in app space.** For apps under OEM
   resolution downscaling (seen with Google Clock on the Motorola), the bounds
   were 91 % of the real size, which misplaced every click by 9 %. The
   captured size from MediaProjection is authoritative; window bounds are used
   only to locate the region, and only when the sizes match.
6. **Secure content.** Apps with `FLAG_SECURE` (banking, DRM video) are black
   in the capture. Accessibility gestures still work on them, but you can't see
   what you click. NovaVR doesn't bypass this.
7. **What Accessibility typing can't do.**
   * Non-text-field keys aren't delivered as key events: game controls,
     terminal shortcuts, Ctrl+Z in most apps.
   * IME composition (CJK input methods) and dead keys aren't supported.
   * Password fields only expose their length, so NovaVR edits its own copy
     of what you typed in that field.
8. **Restricted settings (Android 13+).** Sideloaded apps may need App info →
   ⋮ → *Allow restricted settings* before their Accessibility switch can be
   turned on.

## Shizuku (optional)

* NovaVR detects whether Shizuku is **installed** (`<queries>` package
  visibility), whether its **service is running** (binder ping), and the
  **permission** state (granted / required / denied). It also handles binder
  death and reconnection.
* Shizuku is never required, and root is never used. The shell-uid user
  service is bound only while VR mirroring with a mouse needs it. It is
  unbound afterwards, and its process exits.
* Capabilities used:
  * mouse-event injection (`INJECT_EVENTS`, held by the shell user)
  * Home/Recents key injection
  * writing the secure setting that enables NovaVR's Accessibility service
    ("Enable Accessibility via Shizuku")
* Limitations are the same as for `adb shell input`:
  * Injected *keys* go to the focused window (NovaVR), so they're not used
    for typing.
  * Injected pointer events are subject to the same untrusted-touch rule. Use
    the accessibility-hosted overlay with Shizuku too, which Auto mode does
    when both are available.

## Verification

Test setup: Motorola Edge 60 Fusion, Android 17 (API 37). Mouse/keyboard
tests used a virtual HID keyboard+mouse created with Android's `hid` tool over
uhid, which is the same kernel path Bluetooth HID devices use (reported bus
`0x0005` = Bluetooth). The real Toad II plus Bluetooth keyboard/mouse was also
paired and recognised by NovaVR.

| # | Test | Result |
|---|---|---|
| 1 | Bluetooth mouse connection | ✓ detected; real Toad II recognised and used (pointer capture) |
| 2 | USB OTG mouse | not tested (no OTG device). It uses the same Android input path as BT HID |
| 3 | Cursor movement | ✓ exact positioning (400,200 → 400,200; 1000,600 → 1000,600) |
| 4 | Left click | ✓ Clock: Stopwatch and Alarms tabs selected through the mirrored view |
| 4 | Right click | Back (Accessibility default) is implemented; not separately verified on device |
| 5 | Scrolling | ✓ wheel scrolled Clock's alarm list by two rows |
| 6 | Bluetooth keyboard connection | ✓ detected (merged keyboard+mouse device) |
| 7 | Text input | implemented (Accessibility node actions); device test interrupted when wireless adb disconnected |
| 8 | Keyboard shortcuts | ✓ Ctrl+Alt+H hid VR from the keyboard |
| 9/10 | Portrait / landscape mapping | ✓ by design (cursor in content space, unit-tested region mapping); landscape verified on device |
| 11 | Cursor in both eyes | ✓ one snapshot per frame for both eyes |
| 12 | NovaVR foreground | ✓ test-pattern VR: cursor, dock and shortcuts work; nothing is sent to other apps |
| 13 | Another app foreground | ✓ (Clock / Settings mirrored and controlled) |
| 14 | Accessibility / Shizuku permissions | ✓ Accessibility enable/bind and overlay re-hosting; Shizuku detection = "not installed" |
