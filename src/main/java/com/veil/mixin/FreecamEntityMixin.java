// lang: Java, file: FreecamEntityMixin.java, target: Fabric MC 26.3
// Mouse look and entity culling for Freecam.
package com.veil.mixin;

import com.veil.Veil;
import com.veil.module.modules.render.Freecam;
import net.minecraft.world.entity.Entity;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

@Mixin(Entity.class)
public abstract class FreecamEntityMixin {

    /**
     * With Freecam on, the mouse turns the free camera instead of your player.
     *
     * <p>Only the local player is intercepted: {@code turn} is on every entity, and cancelling it for
     * anyone else would freeze mobs and other players in place.
     */
    @Inject(method = "turn", at = @At("HEAD"), cancellable = true, require = 0)
    private void mist$freecamTurn(double yRot, double xRot, CallbackInfo ci) {
        if (Veil.mc == null || (Object) this != Veil.mc.player) return;
        Freecam freecam = Freecam.active();
        if (freecam == null) return;
        freecam.turn(yRot, xRot);
        ci.cancel();
    }

    /**
     * With Freecam on, entities near your body stay drawn even when the camera is far from them.
     *
     * <p>The arguments are the camera position; the distance check is against your player instead.
     */
    @Inject(method = "shouldRender", at = @At("HEAD"), cancellable = true, require = 0)
    private void mist$freecamShouldRender(double camX, double camY, double camZ, CallbackInfoReturnable<Boolean> cir) {
        Freecam freecam = Freecam.active();
        if (freecam != null && freecam.keepsVisible((Entity) (Object) this)) cir.setReturnValue(true);
    }
}
