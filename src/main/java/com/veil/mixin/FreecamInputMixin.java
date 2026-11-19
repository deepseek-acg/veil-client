// lang: Java, file: FreecamInputMixin.java, target: Fabric MC 26.3
// With Freecam on and the player frozen, the movement keys fly the camera, not the body.
package com.veil.mixin;

import com.veil.module.modules.render.Freecam;
import net.minecraft.client.player.KeyboardInput;
import net.minecraft.world.entity.player.Input;
import net.minecraft.world.phys.Vec2;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(KeyboardInput.class)
public abstract class FreecamInputMixin {

    /**
     * Clears both halves of the movement input after it has been read for this tick, so the same key
     * presses still reach Freecam (which reads them from the options) while the player gets nothing.
     *
     * <p>TAIL rather than HEAD: at HEAD the keys have not been sampled yet, so what gets cleared is
     * last tick's state and the first tick after switching on still walks. The move vector needs the
     * accessor because it is protected on {@code ClientInput}, and it is separate state from
     * {@code keyPresses}: clearing only the key flags still leaves a full WASD vector behind.
     */
    @Inject(method = "tick", at = @At("TAIL"), require = 0)
    private void mist$freecamInput(CallbackInfo ci) {
        Freecam freecam = Freecam.active();
        if (freecam == null || !freecam.freezePlayer()) return;
        KeyboardInput input = (KeyboardInput) (Object) this;
        input.keyPresses = Input.EMPTY;
        ((ClientInputAccessor) input).mist$setMoveVector(Vec2.ZERO);
    }
}
