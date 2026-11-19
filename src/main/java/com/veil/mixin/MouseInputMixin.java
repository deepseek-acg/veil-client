// lang: Java, file: MouseInputMixin.java, target: Fabric MC 26.3
// Drops mouse input while a module holds the input lock, so a screen the module opened cannot be
// disturbed by the player's mouse.
package com.veil.mixin;

import com.veil.util.InputLock;
import net.minecraft.client.MouseHandler;
import net.minecraft.client.input.MouseButtonInfo;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Swallows mouse movement, buttons and scroll while {@link InputLock} is held.
 *
 * <p>26.3 names the callbacks {@code onMove(long, double, double, double, double)},
 * {@code onButton(long, MouseButtonInfo, int)} and {@code onScroll(long, double, double)} on
 * {@code MouseHandler} — all public, so the injections target them by name and the build-time
 * {@code verifyMixinTargets} task checks the descriptors against the jar.
 *
 * <p>Cancelling {@code onMove} is what freezes the cursor: the cursor position used by screens is
 * updated from these callbacks, so without them the pointer stays where it was. Keyboard input is
 * left alone, so Escape and the inventory key still close the screen normally.
 */
@Mixin(MouseHandler.class)
public abstract class MouseInputMixin {

    @Inject(method = "onMove", at = @At("HEAD"), cancellable = true)
    private void onMove(long window, double x, double y, double deltaX, double deltaY, CallbackInfo ci) {
        if (InputLock.held()) ci.cancel();
    }

    @Inject(method = "onButton", at = @At("HEAD"), cancellable = true)
    private void onButton(long window, MouseButtonInfo button, int action, CallbackInfo ci) {
        if (InputLock.held()) ci.cancel();
    }

    @Inject(method = "onScroll", at = @At("HEAD"), cancellable = true)
    private void onScroll(long window, double deltaX, double deltaY, CallbackInfo ci) {
        if (InputLock.held()) ci.cancel();
    }
}
