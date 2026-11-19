package com.veil.mixin;

import com.veil.Veil;
import com.veil.event.events.KeyPressEvent;
import com.veil.util.InputLock;
import net.minecraft.client.KeyboardHandler;
import net.minecraft.client.input.KeyEvent;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Dispatches {@link KeyPressEvent} from the native keyboard callback.
 *
 * <p>26.3 changed {@code KeyboardHandler#keyPress} to {@code (long window, int action, KeyEvent)}
 * — the old five-argument GLFW form is gone, so a mixin written against it would silently never
 * inject. SDL delivers key state through this callback rather than a per-frame query buffer, which
 * is why the GUI toggle listens here instead of polling.
 *
 * <p>{@code event.key()} is an SDL scancode, the space of {@code InputConstants.KEY_*}, and
 * {@code action} is one of {@code InputConstants.PRESS / RELEASE / REPEAT}. Both are forwarded
 * unchanged so callers compare against those constants.
 */
@Mixin(value = KeyboardHandler.class, priority = 1500)
public class KeyInputMixin {

    @Inject(method = "keyPress", at = @At("HEAD"))
    private void onKeyPress(long window, int action, KeyEvent event, CallbackInfo ci) {
        if (Veil.mc == null) return;
        // Held input: module keybinds stay quiet while a module drives the inventory. The event is
        // simply not posted rather than cancelled, because cancelling would also stop the open
        // screen from receiving keys and the player could no longer close it with Escape or E.
        if (InputLock.held()) return;
        Veil.EVENTS.post(new KeyPressEvent(event.key(), action));
    }
}
