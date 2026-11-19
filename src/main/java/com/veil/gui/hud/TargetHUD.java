// lang: Java, file: TargetHUD.java, target: Fabric MC 26.3
// HUD element showing current combat target name, health, distance.
package com.veil.gui.hud;

import com.veil.Veil;
import com.veil.event.EventHandler;
import com.veil.event.events.HudRenderEvent;
import com.veil.gui.Anim;
import com.veil.gui.ColorTheme;
import com.veil.module.modules.combat.KillAura;
import net.minecraft.client.gui.Font;
import net.minecraft.world.entity.LivingEntity;

import java.util.Locale;

/**
 * Target readout under the arraylist: name, health and distance to whatever KillAura is fighting.
 *
 * <p>The box eases in rather than appearing whole — it slides in from the left, fades up and grows
 * its accent bar — and fades out over {@link #FADE_MS} once the target is gone, so the readout
 * tracks the fight instead of blinking with it.
 */
public final class TargetHUD {

    private static final int TEXT_PRI = 0xFFEEEEFF;
    private static final int TEXT_SEC = 0xFF8A8A99;
    private static final int BG       = 0xAA161619;
    private static final int BAR_BG   = 0xFF262630;
    private static final int HP_LOW   = 0xFFE04848;
    private static final int HP_HIGH  = 0xFF48D068;
    /** The box grows with the target's name between these widths; longer names are cut with "..". */
    private static final int MIN_W = 100;
    private static final int MAX_W = 150;

    private LivingEntity      lastTarget = null;
    private long              lastSeenMs = 0;
    private static final long FADE_MS    = 500;

    /** 0 while hidden, 1 once fully shown; eases between the two. */
    private float appear;
    private long  lastFrameNs;

    public TargetHUD() {
        Veil.EVENTS.subscribe(this);
    }

    @EventHandler
    private void onHudRender(HudRenderEvent event) {
        if (!Veil.inGame()) return;

        long now = System.currentTimeMillis();
        long frame = System.nanoTime();
        float dt = lastFrameNs == 0L ? 1f / 60f
                : Math.min(0.1f, (frame - lastFrameNs) / 1_000_000_000f);
        lastFrameNs = frame;

        LivingEntity target = KillAura.getTarget();
        if (target != null && target.isAlive()) {
            lastTarget = target;
            lastSeenMs = now;
        }

        if (lastTarget == null) {
            appear = 0f;
            return;
        }

        long elapsed = now - lastSeenMs;
        if (elapsed > FADE_MS) {
            lastTarget = null;
            appear = 0f;
            return;
        }

        appear = Anim.approach(appear, 1f, 16f, dt);

        // Fade-out over 500 ms, multiplied by the entry animation so the first frame is not opaque.
        float strength = appear * (1f - (float) elapsed / FADE_MS);
        // Not an exit: the first frame after a target appears is genuinely this faint, and
        // clearing lastTarget here would restart the entry animation every frame.
        if (strength <= 0.01f) return;

        var ctx  = event.ctx;
        Font font = Veil.mc.font;
        double dist = Veil.mc.player.distanceTo(lastTarget);

        String info = String.format(Locale.ROOT, "\u2665 %.1f  |  %.1fm", lastTarget.getHealth(), dist);
        String name = "\u2694 " + lastTarget.getName().getString();

        // Sized to the text instead of a fixed 100px, which a 16-character name overran.
        int bw = Math.max(MIN_W, Math.min(MAX_W, Math.max(font.width(name), font.width(info)) + 12));
        int bh = 29;
        int bx = 5 + Math.round((1f - appear) * 6);
        int by = 30;
        ctx.fill(bx, by, bx + bw, by + bh, Anim.alpha(BG, strength));
        ctx.fill(bx, by, bx + 2, by + Math.max(2, Math.round(bh * appear)),
                Anim.alpha(ColorTheme.accent(), strength));

        ctx.text(font, ellipsize(font, name, bw - 12), bx + 6, by + 4, Anim.alpha(TEXT_PRI, strength), false);
        ctx.text(font, ellipsize(font, info, bw - 12), bx + 6, by + 14, Anim.alpha(TEXT_SEC, strength), false);

        // Health bar along the bottom, red to green with the fraction of max health left.
        float max = Math.max(1f, lastTarget.getMaxHealth());
        float fraction = Anim.clamp01(lastTarget.getHealth() / max);
        int barX = bx + 6, barW = bw - 12, barY = by + bh - 5;
        ctx.fill(barX, barY, barX + barW, barY + 2, Anim.alpha(BAR_BG, strength));
        int fill = Math.round(barW * fraction);
        if (fill > 0) {
            ctx.fill(barX, barY, barX + fill, barY + 2,
                    Anim.alpha(Anim.mix(HP_LOW, HP_HIGH, fraction), strength));
        }
    }

    private static String ellipsize(Font font, String text, int maxWidth) {
        if (font.width(text) <= maxWidth) return text;
        int end = text.length();
        while (end > 0 && font.width(text.substring(0, end) + "..") > maxWidth) end--;
        return text.substring(0, end) + "..";
    }
}
