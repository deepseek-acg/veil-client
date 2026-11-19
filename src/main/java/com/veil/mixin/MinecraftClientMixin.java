package com.veil.mixin;

import com.veil.Veil;
import com.veil.event.events.RenderWorldEvent;
import com.veil.event.events.TickEvent;
import net.minecraft.client.Minecraft;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Per-tick dispatch. 26.3 keeps the game loop in {@code Minecraft#tick}, so both edges bracket
 * {@code handleKeybinds}, entity ticking and {@code LocalPlayer.sendChanges}.
 */
@Mixin(Minecraft.class)
public abstract class MinecraftClientMixin {

    @Inject(method = "tick", at = @At("HEAD"))
    private void onTickPre(CallbackInfo ci) {
        if (Veil.mc == null) return;
        Veil.EVENTS.post(new TickEvent.Pre());
    }

    @Inject(method = "tick", at = @At("TAIL"))
    private void onTickPost(CallbackInfo ci) {
        if (Veil.mc == null) return;
        Veil.EVENTS.post(new TickEvent.Post());
    }

    /**
     * Per-frame world render dispatch.
     *
     * <p>{@code renderFrame} opens the frame's gizmo collector and calls {@code GameRenderer.extract}
     * inside it, with the camera already at this frame's position; posting just before that call means
     * shapes added by a handler are collected for the frame being drawn, not the next one. This is the
     * hook nyx uses, and the reason overlays can follow entities smoothly instead of stepping at the
     * twenty ticks a second a tick handler is limited to.
     */
    @Inject(method = "renderFrame", at = @At(value = "INVOKE",
            target = "Lnet/minecraft/client/renderer/GameRenderer;extract(Lnet/minecraft/client/DeltaTracker;Z)V"))
    private void onRenderWorld(boolean advanceGameTime, CallbackInfo ci) {
        if (Veil.mc == null || !Veil.inGame()) return;
        float partialTick = Veil.mc.getDeltaTracker().getGameTimeDeltaPartialTick(true);
        Veil.EVENTS.post(new RenderWorldEvent(partialTick));
    }
}
