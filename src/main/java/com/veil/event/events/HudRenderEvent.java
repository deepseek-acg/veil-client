package com.veil.event.events;

import net.minecraft.client.gui.GuiGraphicsExtractor;

/**
 * Fired once per HUD frame with the extractor vanilla is drawing with, so overlays can be drawn on
 * top of the HUD without their own mixin.
 *
 * <p>Fabric's {@code HudRenderCallback} no longer exists in 26.3 — {@code Gui} stopped rendering and
 * the per-frame pass moved to {@code Hud#extractRenderState}.
 */
public final class HudRenderEvent {

    public final GuiGraphicsExtractor ctx;

    public HudRenderEvent(GuiGraphicsExtractor ctx) {
        this.ctx = ctx;
    }
}
