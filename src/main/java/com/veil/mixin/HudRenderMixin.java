package com.veil.mixin;

import com.veil.Veil;
import com.veil.event.events.HudRenderEvent;
import com.veil.gui.hud.Legend;
import net.minecraft.client.DeltaTracker;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.Hud;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Dispatches {@link HudRenderEvent} at the tail of the HUD frame.
 *
 * <p>26.3 no longer renders the HUD from {@code Gui}; the per-frame pass that hands out a
 * {@link GuiGraphicsExtractor} is {@code Hud#extractRenderState}, so that is the injection point.
 */
@Mixin(value = Hud.class, priority = 1500)
public class HudRenderMixin {

    @Inject(method = "extractRenderState", at = @At("TAIL"))
    private void onRenderPost(GuiGraphicsExtractor ctx, DeltaTracker deltaTracker, CallbackInfo ci) {
        if (Veil.mc == null) return;
        // Panels that stack in a corner start from that corner once per frame.
        Legend.beginFrame();
        Veil.EVENTS.post(new HudRenderEvent(ctx));
    }
}
