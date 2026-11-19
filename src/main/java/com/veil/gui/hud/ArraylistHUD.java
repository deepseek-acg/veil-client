package com.veil.gui.hud;

import com.veil.Veil;
import com.veil.event.EventHandler;
import com.veil.event.events.HudRenderEvent;
import com.veil.gui.Anim;
import com.veil.gui.ColorTheme;
import com.veil.module.Module;
import com.veil.module.ModuleManager;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.world.effect.MobEffectInstance;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Enabled modules in the top-right corner, most recently enabled first.
 *
 * <p>Fabric's {@code HudRenderCallback} is gone in 26.3, so this listens to {@link HudRenderEvent},
 * which is posted from the HUD pass with the frame's extractor.
 *
 * <p>Rows are animated: a module that switches on slides in from the right and fades up, one that
 * switches off fades out where it stood, and the rows around it glide to their new position instead
 * of snapping when the list reorders. Rows are kept in a map between frames for exactly that
 * reason — the list itself is rebuilt every frame, so it cannot hold the animation state.
 */
public final class ArraylistHUD {

    /** Per-row animation state, kept alive for a moment after the module is switched off. */
    private static final class Row {
        Module module;
        float alpha;
        float y;
        float offset;
        float width;
        boolean live;
    }

    private static final int LINE_H   = 10;
    private static final int MARGIN_X = 4;
    private static final int MARGIN_Y = 4;
    /** Vanilla's status effect icons: 24px squares, harmful ones on a second row 26px lower. */
    private static final int EFFECT_ICON = 24;
    private static final int EFFECT_ROW  = 26;
    // Matches the ClickGUI: dark rows with a teal accent bar, so the HUD and the menu read as one
    // theme.
    private static final int BG_ALPHA = 0x8C0B0B0E;
    private static final int TEXT     = 0xFFEEEEFF;
    private static final int TEXT_DIM = 0xFF8A8A99;

    private final Map<String, Row> rows = new HashMap<>();
    private long lastFrameNs;

    public ArraylistHUD() {
        Veil.EVENTS.subscribe(this);
    }

    @EventHandler
    private void onHudRender(HudRenderEvent event) {
        if (!Veil.inGame()) return;

        Minecraft mc = Veil.mc;
        if (mc.gui != null && mc.gui.hud.isHidden()) return;

        long now = System.nanoTime();
        float dt = lastFrameNs == 0L ? 1f / 60f
                : Math.min(0.1f, (now - lastFrameNs) / 1_000_000_000f);
        lastFrameNs = now;

        Font font = Minecraft.getInstance().font;
        List<Module> enabled = ModuleManager.getModules().stream()
                .filter(Module::isEnabled)
                .sorted(Comparator.comparingInt((Module m) -> font.width(label(m))).reversed())
                .toList();

        for (Row row : rows.values()) row.live = false;

        // Lay the live rows out first; their targets are where the finished list would put them.
        // The list starts under vanilla's effect icons, which share the top-right corner and were
        // otherwise drawn over; rows ease down (and back up) as effects come and go.
        float y = effectsBottom(mc) + MARGIN_Y;
        for (Module m : enabled) {
            float targetY = y;
            Row row = rows.computeIfAbsent(m.getName(), key -> newRow(m, targetY));
            row.module = m;
            row.live = true;
            row.y = Anim.approach(row.y, targetY, 16f, dt);
            row.alpha = Anim.approach(row.alpha, 1f, 14f, dt);
            row.offset = Anim.approach(row.offset, 0f, 14f, dt);
            row.width = Anim.approach(row.width, font.width(label(m)), 18f, dt);
            y += LINE_H + 1;
        }

        GuiGraphicsExtractor ctx = event.ctx;

        // Rows whose module went away fade out where they stood and are then dropped. They are
        // drawn before the live rows so a row on its way out never covers one that is staying.
        List<Row> finished = new ArrayList<>();
        for (Row row : rows.values()) {
            if (row.live) continue;
            row.alpha = Anim.approach(row.alpha, 0f, 16f, dt);
            row.offset = Anim.approach(row.offset, 6f, 16f, dt);
            if (row.alpha <= 0.02f) finished.add(row);
            else draw(ctx, font, row);
        }
        for (Row row : finished) rows.remove(row.module.getName());

        for (Row row : rows.values()) {
            if (row.live) draw(ctx, font, row);
        }
    }

    /**
     * Bottom edge of the status effect icons vanilla draws in the top-right corner, or 0 when none
     * are shown. Mirrors vanilla's layout: beneficial effects on the first row from y=1, harmful ones
     * on a second row below it.
     */
    private static int effectsBottom(Minecraft mc) {
        if (mc.player == null) return 0;
        boolean beneficial = false, harmful = false;
        for (MobEffectInstance effect : mc.player.getActiveEffects()) {
            if (!effect.showIcon()) continue;
            if (effect.getEffect().value().isBeneficial()) beneficial = true;
            else harmful = true;
        }
        if (harmful) return 1 + EFFECT_ROW + EFFECT_ICON;
        if (beneficial) return 1 + EFFECT_ICON;
        return 0;
    }

    private static Row newRow(Module module, float y) {
        Row row = new Row();
        row.module = module;
        // Start off to the right and invisible, so the first frame eases in from nothing.
        row.alpha = 0f;
        row.y = y;
        row.offset = 8f;
        row.width = 0f;
        return row;
    }

    private void draw(GuiGraphicsExtractor ctx, Font font, Row row) {
        if (row.alpha <= 0.02f) return;

        int width = Math.round(row.width);
        int x = Math.round(ctx.guiWidth() - width - MARGIN_X + row.offset);
        int y = Math.round(row.y);

        ctx.fill(x - 3, y - 1, x + width + 3, y + LINE_H, Anim.alpha(BG_ALPHA, row.alpha));
        ctx.fill(x - 5, y - 1, x - 3, y + LINE_H,
                Anim.alpha(row.module.color() == -1 ? ColorTheme.accent() : row.module.color(), row.alpha));

        // The box width eases toward the label width, so while a label grows (a module appearing,
        // a suffix getting longer) the text is wider than its box. Clip it to the box instead of
        // letting it spill past the background and off the edge of the screen.
        ctx.enableScissor(x - 3, y - 1, x + width + 3, y + LINE_H);
        ctx.text(font, row.module.getName(), x, y, Anim.alpha(TEXT, row.alpha), false);

        String suffix = row.module.suffix();
        if (suffix != null && !suffix.isEmpty()) {
            // Offset by the real width of "name " so the suffix sits where label() measured it.
            ctx.text(font, suffix, x + font.width(row.module.getName() + " "), y,
                    Anim.alpha(TEXT_DIM, row.alpha), false);
        }
        ctx.disableScissor();
    }

    private static String label(Module m) {
        String suffix = m.suffix();
        return suffix == null || suffix.isEmpty() ? m.getName() : m.getName() + " " + suffix;
    }
}
