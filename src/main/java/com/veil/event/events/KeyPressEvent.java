package com.veil.event.events;

import com.mojang.blaze3d.platform.InputConstants;

/**
 * Fired once per key-state change from the native keyboard callback.
 *
 * <p>26.3 delivers keys through {@code KeyboardHandler#keyPress(long, int, KeyEvent)}. The key code
 * on the event is an SDL scancode, the same space as {@code InputConstants.KEY_*} (A is 4, Escape is
 * 41, right shift is 229) — not a GLFW key code and not an ordinal, so literal numbers must never be
 * compared against it. The action is one of {@link InputConstants#PRESS},
 * {@link InputConstants#RELEASE} or {@link InputConstants#REPEAT}.
 */
public final class KeyPressEvent {

    public final int key;
    public final int action;

    public KeyPressEvent(int key, int action) {
        this.key    = key;
        this.action = action;
    }

    /** True only on the key-down edge, so holding a key does not repeat an action. */
    public boolean isPress() { return action == InputConstants.PRESS; }

    public boolean isRelease() { return action == InputConstants.RELEASE; }

    public boolean isRepeat() { return action == InputConstants.REPEAT; }
}
