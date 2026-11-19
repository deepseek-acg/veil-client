// lang: Java, file: InputLock.java, target: Fabric MC 26.3
package com.veil.util;

import com.veil.module.Module;
import com.veil.module.ModuleManager;

/**
 * A global hold on player input, owned by one module at a time.
 *
 * <p>While held: every module except the owner stops receiving events (see {@code EventBus}), key
 * presses are not delivered to module keybinds, and the mouse mixin drops movement, button and
 * scroll input so the cursor cannot be moved and nothing can be clicked.
 *
 * <p>This exists for actions that drive the player's own inventory — open it, click a slot, close it.
 * Anything else acting during that window competes with it: another module attacking interrupts the
 * click sequence, and a stray mouse click moves or drops the stack about to be swapped.
 *
 * <p>The hold expires on its own after {@link #MAX_HOLD_MS}. A module that fails to release (an
 * exception mid-refill, a screen closed in a way the phase machine did not see) would otherwise
 * leave the player unable to move the mouse at all, which is far worse than the hold failing to
 * apply.
 */
public final class InputLock {

    /** Longest a hold may last before it is dropped regardless of the owner. */
    private static final long MAX_HOLD_MS = 5000L;

    private static Object owner;
    private static long acquiredAt;

    private InputLock() {}

    /** Take the hold for {@code holder}, replacing any previous owner (including this one). */
    public static void acquire(Object holder) {
        owner = holder;
        acquiredAt = System.currentTimeMillis();
        ModuleManager.pauseAllExcept(holder);
    }

    /** Release the hold, but only if {@code holder} is the current owner. */
    public static void release(Object holder) {
        if (owner != holder) return;
        owner = null;
        ModuleManager.resumeAllExcept(holder);
    }

    /** Whether input is currently held. Expires a stale hold as a side effect. */
    public static boolean held() {
        if (owner != null && System.currentTimeMillis() - acquiredAt > MAX_HOLD_MS) {
            Object stale = owner;
            owner = null;
            ModuleManager.resumeAllExcept(stale);
        }
        return owner != null;
    }

    /**
     * Whether events should be withheld from this subscriber: every module is paused except the one
     * that holds the lock. Non-module subscribers — the HUD, the keybind dispatcher — keep running,
     * so the screen and the overlay do not flicker or freeze.
     */
    public static boolean blocks(Object subscriber) {
        if (!held()) return false;
        return subscriber instanceof Module && subscriber != owner;
    }
}
