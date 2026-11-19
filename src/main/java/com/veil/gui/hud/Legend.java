// lang: Java, file: Legend.java, target: Fabric MC 26.3
// Shared colour key for modules that draw several kinds of thing in the world.
package com.veil.gui.hud;

import com.veil.gui.ColorTheme;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphicsExtractor;

import java.util.List;

/**
 * A small see-through key in the bottom-right corner: one coloured square per kind of thing a module
 * highlights, with a label and, when there is something to count, how many are around.
 *
 * <p>Several modules can each draw one. They stack upwards from the corner instead of overlapping,
 * which needs the stack to be reset once per frame — {@link #beginFrame()} is called from the HUD
 * hook before any module draws. Nyx does the same through an event priority; mist has no priority,
 * so the reset is an explicit call rather than an ordering assumption.
 */
public final class Legend {

    /** One line of the key: what it is, the colour it is drawn in, and an optional count. */
    public record Entry(String label, int colour, String count) {}

    private static final int BG         = 0x900E0F15;
    private static final int TEXT       = 0xFFEEEEFF;
    private static final int TEXT_DIM   = 0xFF8A8A99;
    private static final int TEXT_FAINT = 0xFF5A5A66;
    private static final int ROW_H      = 10;
    private static final int TITLE_H    = 12;
    private static final int MARGIN     = 4;

    /** Bottom edge available to the next panel; -1 until the first panel of the frame is placed. */
    private static int nextBottom = -1;

    private Legend() {}

    /** Resets the stack. Called once per HUD frame, before the modules draw. */
    public static void beginFrame() {
        nextBottom = -1;
    }

    /** Draws one key panel, above the ones already drawn this frame. */
    public static void draw(GuiGraphicsExtractor ctx, Font font, String title, List<Entry> entries) {
        if (entries.isEmpty()) return;

        // The title is drawn 6px in, so it needs that much padding on both sides to stay inside.
        int width = 6 + font.width(title) + 6;
        for (Entry e : entries) {
            int row = 16 + font.width(e.label()) + (e.count() == null ? 0 : 6 + font.width(e.count())) + 6;
            width = Math.max(width, row);
        }
        int height = TITLE_H + entries.size() * ROW_H + 3;

        int x = ctx.guiWidth() - MARGIN - width;
        int bottom = nextBottom < 0 ? ctx.guiHeight() - MARGIN : nextBottom;
        int y = bottom - height;
        // No room left above the panels already stacked here: this one is not drawn at all, rather
        // than overlapping the one below it.
        if (y < MARGIN) return;
        nextBottom = y - 4;

        ctx.fill(x, y, x + width, y + height, BG);
        ctx.fill(x, y, x + 2, y + height, ColorTheme.accent());
        ctx.text(font, title, x + 6, y + 3, TEXT, false);

        int rowY = y + TITLE_H;
        for (Entry e : entries) {
            ctx.fill(x + 6, rowY + 2, x + 12, rowY + 8, e.colour() | 0xFF000000);
            ctx.text(font, e.label(), x + 16, rowY, TEXT_DIM, false);
            if (e.count() != null) {
                ctx.text(font, e.count(), x + 16 + font.width(e.label()) + 6, rowY, TEXT_FAINT, false);
            }
            rowY += ROW_H;
        }
    }
}
