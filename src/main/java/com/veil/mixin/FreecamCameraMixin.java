// lang: Java, file: FreecamCameraMixin.java, target: Fabric MC 26.3
// Puts the camera where Freecam says, at the end of the frame's camera update.
package com.veil.mixin;

import com.veil.module.modules.render.Freecam;
import net.minecraft.client.Camera;
import net.minecraft.client.DeltaTracker;
import net.minecraft.world.phys.Vec3;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(Camera.class)
public abstract class FreecamCameraMixin {

    @Shadow private boolean detached;

    @Shadow protected abstract void setPosition(Vec3 pos);

    @Shadow protected abstract void setRotation(float yRot, float xRot);

    /**
     * Runs at the end of the camera update, so everything vanilla has just worked out from the
     * player's real position is replaced rather than fed back into the camera's own maths.
     *
     * <p>{@code detached} is what tells the entity renderer the camera is not the player's eyes, so
     * your own model is drawn while you look around.
     */
    @Inject(method = "update", at = @At("TAIL"), require = 0)
    private void mist$freecam(DeltaTracker deltaTracker, CallbackInfo ci) {
        Freecam freecam = Freecam.active();
        if (freecam == null) return;
        float partialTick = deltaTracker.getGameTimeDeltaPartialTick(true);
        setPosition(freecam.cameraPos(partialTick));
        setRotation(freecam.yaw(), freecam.pitch());
        detached = true;
    }
}
