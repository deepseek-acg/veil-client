package com.veil.gui;

import com.veil.module.Module;
import com.veil.module.ModuleManager;
import com.veil.setting.BoolSetting;
import com.veil.setting.FloatSetting;
import com.veil.setting.IntSetting;
import com.veil.setting.ModeSetting;
import com.veil.setting.Setting;
import com.mojang.blaze3d.platform.InputConstants;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.input.CharacterEvent;
import net.minecraft.client.input.KeyEvent;
import net.minecraft.client.input.MouseButtonEvent;
import net.minecraft.network.chat.Component;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * The Veil GUI: a frosted sheet over the world, the wordmark, one search field and a card per
 * module. Clicking a card opens that module's settings; clicking its switch toggles it.
 *
 * <p>Blur is vanilla's, not ours: {@link #extractBlurredBackground} is the same hook the pause and
 * options screens use, so the world frosts exactly as much as the player's Menu Background
 * Blurriness setting asks for, including not at all. That replaces the post-effect pass the old
 * Grayscale module used to need — no shader ships with this screen.
 *
 * <p>26.3 note: Screen#render is gone; drawing goes through GuiGraphicsExtractor in
 * extractRenderState, and screen input takes {@link MouseButtonEvent} records because
 * {@code GuiEventListener} declares {@code mouseClicked(MouseButtonEvent, boolean)}.
 *
 * <p>Layout note: both views are measured once per frame — cards into {@link #cards}, settings
 * rows into {@link #sheetRows} — and the renderer and the hit tests read those same records. What
 * the cursor can reach therefore cannot drift from what is drawn, and a mode's wrapped buttons are
 * measured once instead of once per reader. Each region scrolls inside a clip that its hit tests
 * use too.
 */
public final class ClickGUI extends Screen {

    // ── geometry ──────────────────────────────────────────────────────────────
    private static final int MARGIN = 16;
    private static final int LINE = 10;
    /** Glyph height of the default font; used to centre text in a row. */
    private static final int GLYPH_H = 7;

    private static final int CARD_W     = 232;
    private static final int CARD_H     = 96;
    private static final int CARD_GAP   = 12;
    private static final int CARD_R     = 12;
    /** Text inset inside a card, and the gap between a card's switch and its edge. */
    private static final int CARD_PAD   = 14;

    private static final int SEARCH_W_MAX = 360;
    private static final int SEARCH_H     = 24;
    private static final int SEARCH_R     = 12;
    private static final int SEARCH_PAD   = 12;
    /** Width of the "x" that clears the search, reachable from the field's right edge. */
    private static final int SEARCH_CLEAR = 18;

    private static final int SHEET_W_MAX  = 420;
    private static final int SHEET_R      = 12;
    private static final int SHEET_PAD    = 14;
    /** Height of the sheet's fixed header; the scissored body starts here. */
    private static final int SHEET_HEADER = 34;
    /** How far a hovered row reaches past the text inset, on each side. */
    private static final int ROW_INSET    = 6;

    private static final int SET_ROW      = 16;
    private static final int SLIDE_ROW    = 24;
    private static final int MODE_LABEL_H = 11;
    private static final int MODE_BTN_H   = 11;
    private static final int MODE_BTN_GAP = 3;
    private static final int MODE_BTN_PAD = 10;
    private static final int MODE_BOTTOM  = 3;

    private static final int SWITCH_W = 26;
    private static final int SWITCH_H = 12;
    private static final int SWITCH_PAD = 2;
    private static final int SWITCH_KNOB = SWITCH_H - 2 * SWITCH_PAD;
    /** How far the knob travels inside its track, so it sits {@link #SWITCH_PAD} in at either end. */
    private static final int SWITCH_TRAVEL = SWITCH_W - SWITCH_KNOB - 2 * SWITCH_PAD;
    /** Top of a card's switch, relative to the card. */
    private static final int SWITCH_DY = 11;

    private static final int WORD_SCALE = 3;
    private static final int WORD_GAP   = 5;

    private static final int DOT     = 6;
    private static final int DOT_GAP = 8;
    /** Half-width of a theme dot's click target, in each direction. */
    private static final int DOT_HIT = 4;
    private static final int FOOTER_PAD = 12;

    // ── palette ───────────────────────────────────────────────────────────────
    /** Violet wash over the blurred world: top fades in, bottom settles nearly opaque. */
    private static final int WASH_TOP    = 0x66170F2E;
    private static final int WASH_BOTTOM = 0xC80A0714;
    private static final int CARD_BG     = 0x18FFFFFF;
    private static final int CARD_BG_ON  = 0x26FFFFFF;
    private static final int CARD_LINE   = 0x1CFFFFFF;
    private static final int CARD_LINE_ON= 0x55FFFFFF;
    private static final int SHEET_BG    = 0xE80F0A1C;
    private static final int SHEET_LINE  = 0x33FFFFFF;
    private static final int SEARCH_BG   = 0x1AFFFFFF;
    private static final int SEARCH_LINE = 0x2AFFFFFF;
    private static final int SEARCH_LINE_HOT = 0x44FFFFFF;
    private static final int MODE_BTN_BG = 0x1AFFFFFF;
    private static final int CHIP_BG     = 0x1AFFFFFF;
    private static final int DOT_OFF     = 0x44FFFFFF;
    private static final int LINE_SOFT   = 0x1AFFFFFF;
    private static final int TITLE       = 0xFFFFFFFF;
    private static final int SUB         = 0xFFC9BFDD;
    private static final int DIM         = 0xFF9185AE;
    private static final int FAINT       = 0xFF6A6080;
    private static final int TRACK       = 0x33FFFFFF;
    private static final int KNOB        = 0xFFFFFFFF;
    private static final int HOVER       = 0x1AFFFFFF;

    // ── animation ─────────────────────────────────────────────────────────────
    private static final long FADE_MS = 160L;
    private static final float HOVER_RATE = 18f;

    private static final ColorTheme.Theme[] THEMES = ColorTheme.Theme.values();
    /** Animation key for the bind row; identity, so it never collides with a module or setting. */
    private static final Object BIND_ROW = new Object();

    /** One module's card, in screen coordinates for the frame it was laid out for. */
    private record Card(Module module, int x, int y, int w, int h) {

        int toggleX() { return x + w - CARD_PAD - SWITCH_W; }
        int toggleY() { return y + SWITCH_DY; }

        boolean onToggle(int mx, int my) {
            return inside(mx, my, toggleX(), toggleY(), SWITCH_W, SWITCH_H);
        }
    }

    /** One option button of a mode setting, relative to the settings column. */
    private record ModeButton(String option, int x, int y, int w) {}

    /** Animation key for one option button, so equal option text in two modes stays apart. */
    private record OptionKey(ModeSetting setting, String option) {}

    /**
     * One interactive row of the settings sheet. Its geometry is measured once per frame in
     * {@link #layout()}; {@code y} is absolute by the time the renderer or a hit test reads it.
     */
    private static final class SheetRow {
        final Setting<?> setting;
        final int h;
        final List<ModeButton> buttons;
        /** Left edge of an int setting's "◂ n ▸" readout, or -1 for settings without one. */
        final int valueX;
        int y;

        SheetRow(Setting<?> setting, int y, int h, List<ModeButton> buttons, int valueX) {
            this.setting = setting;
            this.y = y;
            this.h = h;
            this.buttons = buttons;
            this.valueX = valueX;
        }
    }

    private String search = "";
    /** Module whose settings sheet is open, or null while the cards are showing. */
    private Module selected;
    /** Module waiting for a key press to bind, or null when nothing is being bound. */
    private Module binding;
    /** When a key press was last used up as a bind; see {@link #charTyped}. */
    private long bindConsumedAt;

    private long openedAt;
    private long lastFrameNs;

    private final Map<Object, Float> hoverAnim = new HashMap<>();
    private final Map<Object, Float> valueAnim = new HashMap<>();

    private FloatSetting draggingSlider;
    private int dragBarX, dragBarW;

    private float cardScroll, sheetScroll;

    // per-frame layout, shared by the renderer and the hit tests
    private final List<Card> cards = new ArrayList<>();
    private final List<SheetRow> sheetRows = new ArrayList<>();
    /** The open module's description, wrapped to the sheet's two lines. */
    private List<String> sheetDesc = List.of();
    private int markTop, subY, searchX, searchY, searchW, cardsTop, cardsViewH, cardsContentH;
    private int sheetX, sheetY, sheetW, sheetH, sheetContentH, bindRowY;

    public ClickGUI() {
        super(Component.empty());
    }

    @Override
    protected void init() {
        // init() runs on every open (and on resize), which is exactly when the fade should replay.
        openedAt = System.nanoTime();
        lastFrameNs = 0L;
        cardScroll = 0f;
        sheetScroll = 0f;
        draggingSlider = null;
        selected = null;
        binding = null;
        layout();
    }

    @Override
    public boolean isPauseScreen() { return false; }

    // ── layout ────────────────────────────────────────────────────────────────

    /** Shown modules, in registration order, after the search filter. */
    private List<Module> visible() {
        String q = search.toLowerCase(Locale.ROOT);
        List<Module> out = new ArrayList<>();
        for (Module m : ModuleManager.getModules()) {
            if (q.isEmpty() || m.getName().toLowerCase(Locale.ROOT).contains(q)) out.add(m);
        }
        return out;
    }

    private void layout() {
        int wordH = GLYPH_H * WORD_SCALE + 6;
        searchW = Math.min(SEARCH_W_MAX, width - 2 * MARGIN);
        searchX = (width - searchW) / 2;

        cards.clear();
        List<Module> shown = visible();
        int cols = Math.max(1, Math.min(3, (width - 2 * MARGIN + CARD_GAP) / (CARD_W + CARD_GAP)));
        int cardW = Math.min(CARD_W, (width - 2 * MARGIN - (cols - 1) * CARD_GAP) / cols);
        int rows = Math.max(1, (shown.size() + cols - 1) / cols);
        cardsContentH = rows * CARD_H + (rows - 1) * CARD_GAP;

        // The wordmark, the search field and one row of cards are laid out as one block and
        // centred together. When the cards need more height than the screen leaves, the block
        // starts at the top and the cards scroll rather than being pushed off the bottom edge.
        int chrome = wordH + 6 + LINE + 14 + SEARCH_H + 20;
        cardsViewH = Math.min(cardsContentH, Math.max(CARD_H, height - chrome - 28));
        markTop = Math.max(10, (height - (chrome + cardsViewH)) / 2);
        subY = markTop + wordH + 6;
        searchY = subY + LINE + 14;
        cardsTop = searchY + SEARCH_H + 20;
        cardScroll = Math.max(0f, Math.min(cardScrollMax(), cardScroll));

        int totalW = cols * cardW + (cols - 1) * CARD_GAP;
        int startX = (width - totalW) / 2;
        int scroll = Math.round(cardScroll);

        for (int i = 0; i < shown.size(); i++) {
            int col = i % cols, row = i / cols;
            cards.add(new Card(shown.get(i),
                    startX + col * (cardW + CARD_GAP),
                    cardsTop + row * (CARD_H + CARD_GAP) - scroll,
                    cardW, CARD_H));
        }

        if (selected != null) layoutSheet(selected);
    }

    /**
     * Measures the open sheet body. The settings are walked once at body-relative heights so the
     * content height is known, the scroll is clamped against it, and then every row is shifted to
     * where this frame draws it — after which the renderer and the hit tests agree by construction.
     */
    private void layoutSheet(Module module) {
        sheetW = Math.min(SHEET_W_MAX, width - 2 * MARGIN);
        sheetX = (width - sheetW) / 2;
        sheetDesc = describe(font, module.getDescription(), innerW(), 2);
        int bodyTop = SHEET_HEADER + sheetDesc.size() * LINE + 6;

        int y = bodyTop + SET_ROW + 4;
        sheetRows.clear();
        for (Setting<?> s : module.getSettings()) {
            SheetRow row = sheetRow(s, y);
            sheetRows.add(row);
            y += row.h;
        }
        sheetContentH = y + 10;
        sheetH = Math.min(sheetContentH, height - 2 * MARGIN);
        sheetY = Math.max(MARGIN, (height - sheetH) / 2);
        sheetScroll = Math.max(0f, Math.min(Math.max(0, sheetContentH - sheetH), sheetScroll));

        int offset = sheetY + bodyTop - Math.round(sheetScroll);
        bindRowY = offset;
        for (SheetRow row : sheetRows) row.y += offset;
    }

    /** Measures one settings row, including any mode buttons it has to wrap. */
    private SheetRow sheetRow(Setting<?> s, int top) {
        if (s instanceof FloatSetting) {
            return new SheetRow(s, top, SLIDE_ROW, List.of(), -1);
        }
        if (s instanceof IntSetting in) {
            return new SheetRow(s, top, SET_ROW, List.of(), sheetRight() - font.width(intLabel(in)));
        }
        if (s instanceof ModeSetting mode) {
            List<ModeButton> buttons = modeButtons(mode);
            int rows = buttons.isEmpty() ? 1
                    : buttons.get(buttons.size() - 1).y() / (MODE_BTN_H + MODE_BTN_GAP) + 1;
            int h = MODE_LABEL_H + rows * MODE_BTN_H + (rows - 1) * MODE_BTN_GAP + MODE_BOTTOM;
            return new SheetRow(s, top, h, buttons, -1);
        }
        // Bool, and any future single-line setting.
        return new SheetRow(s, top, SET_ROW, List.of(), -1);
    }

    private int cardScrollMax() {
        return Math.max(0, cardsContentH - cardsViewH);
    }

    // ── sheet geometry ────────────────────────────────────────────────────────

    private int innerW() { return sheetW - 2 * SHEET_PAD; }
    private int sheetLeft() { return sheetX + SHEET_PAD; }
    private int sheetRight() { return sheetX + sheetW - SHEET_PAD; }

    private int rowX() { return sheetLeft() - ROW_INSET; }
    private int rowW() { return sheetW - 2 * (SHEET_PAD - ROW_INSET); }

    /** Whether a point is inside a sheet row, cut by the scrolling body's clip. */
    private boolean overRow(int mx, int my, int y, int h) {
        return insideClipped(mx, my, rowX(), y, rowW(), h, sheetY + SHEET_HEADER, sheetY + sheetH - 1);
    }

    /** The "&lt;" glyph that goes back to the cards. */
    private boolean backHit(int mx, int my) {
        return inside(mx, my, sheetLeft() - 4, sheetY + 6, 18, 18);
    }

    /** The "x" glyph that closes the sheet. */
    private boolean closeHit(int mx, int my) {
        return inside(mx, my, sheetRight() - 14, sheetY + 6, 18, 18);
    }

    /** Wraps a mode's options into as many evenly stretched rows as the column needs. */
    private List<ModeButton> modeButtons(ModeSetting mode) {
        int inner = innerW();
        List<ModeButton> out = new ArrayList<>();
        List<String> row = new ArrayList<>();
        List<Integer> widths = new ArrayList<>();
        int used = 0, y = 0;
        for (String opt : mode.getOptions()) {
            int w = Math.min(inner, font.width(opt) + MODE_BTN_PAD);
            if (!row.isEmpty() && used + MODE_BTN_GAP + w > inner) {
                flushModeRow(out, row, widths, used, y, inner);
                y += MODE_BTN_H + MODE_BTN_GAP;
                row.clear();
                widths.clear();
                used = 0;
            }
            used += (row.isEmpty() ? 0 : MODE_BTN_GAP) + w;
            row.add(opt);
            widths.add(w);
        }
        if (!row.isEmpty()) flushModeRow(out, row, widths, used, y, inner);
        return out;
    }

    private static void flushModeRow(List<ModeButton> out, List<String> row, List<Integer> widths,
                                     int used, int y, int inner) {
        int n = row.size();
        int extra = Math.max(0, inner - used);
        int x = 0;
        for (int i = 0; i < n; i++) {
            int w = widths.get(i) + extra / n + (i < extra % n ? 1 : 0);
            out.add(new ModeButton(row.get(i), x, y, w));
            x += w + MODE_BTN_GAP;
        }
    }

    // ── text helpers ──────────────────────────────────────────────────────────

    private static int centerY(int top, int h) {
        return top + (h - GLYPH_H) / 2;
    }

    /** Shortens {@code text} with a trailing ".." until it fits in {@code maxWidth} pixels. */
    private static String ellipsize(Font font, String text, int maxWidth) {
        if (text == null || text.isEmpty()) return "";
        if (font.width(text) <= maxWidth) return text;
        String dots = "..";
        int dotsW = font.width(dots);
        if (maxWidth <= dotsW) return "";
        int end = text.length();
        while (end > 0 && font.width(text.substring(0, end)) + dotsW > maxWidth) end--;
        while (end > 0 && text.charAt(end - 1) == '§') end--;
        return end == 0 ? "" : text.substring(0, end) + dots;
    }

    /** Word-wraps {@code text} to lines no wider than {@code maxWidth}. */
    private static List<String> wrap(Font font, String text, int maxWidth) {
        List<String> lines = new ArrayList<>();
        StringBuilder line = new StringBuilder();
        for (String word : text.trim().split("\\s+")) {
            String candidate = line.isEmpty() ? word : line + " " + word;
            if (font.width(candidate) <= maxWidth) {
                line.setLength(0);
                line.append(candidate);
                continue;
            }
            if (!line.isEmpty()) lines.add(line.toString());
            line.setLength(0);
            line.append(font.width(word) > maxWidth ? ellipsize(font, word, maxWidth) : word);
        }
        if (!line.isEmpty()) lines.add(line.toString());
        return lines;
    }

    /**
     * Wraps to at most {@code maxLines} lines. When the text was cut, the last visible line is the
     * remainder of the paragraph ellipsized, so it ends in a single ".." rather than a bare stop.
     */
    private static List<String> describe(Font font, String text, int maxWidth, int maxLines) {
        List<String> lines = wrap(font, text, maxWidth);
        if (lines.size() <= maxLines) return lines;
        List<String> out = new ArrayList<>(lines.subList(0, maxLines));
        out.set(maxLines - 1,
                ellipsize(font, String.join(" ", lines.subList(maxLines - 1, lines.size())), maxWidth));
        return out;
    }

    /**
     * Readout for a slider. Wide ranges drop decimals that would only add noise ("3000" rather than
     * "3000.00"), which also keeps the value from crowding out the setting name.
     */
    private static String formatFloat(FloatSetting f) {
        float range = f.getMax() - f.getMin();
        String pattern = range >= 100f ? "%.0f" : range >= 10f ? "%.1f" : "%.2f";
        return String.format(Locale.ROOT, pattern, f.get());
    }

    /** The stepper readout of an int setting; also the widest its row ever has to reserve. */
    private String intLabel(IntSetting in) {
        return "◂ " + in.get() + " ▸";
    }

    /** The plain value a card previews for a setting; the sheet has richer editors for the same. */
    private static String valueLabel(Setting<?> s) {
        return switch (s) {
            case FloatSetting f -> formatFloat(f);
            case IntSetting in -> String.valueOf(in.get());
            case ModeSetting mode -> mode.get();
            case BoolSetting b -> b.get() ? "on" : "off";
            default -> "";
        };
    }

    private static String keyLabel(int key) {
        if (key == Module.UNBOUND) return "none";
        try {
            return InputConstants.Type.KEYBOARD.getOrCreate(key).getDisplayName().getString();
        } catch (RuntimeException error) {
            return String.valueOf(key);
        }
    }

    // ── drawing primitives ────────────────────────────────────────────────────

    /**
     * A filled rounded rectangle, built from per-row spans at the corners and one fill through the
     * middle. There is no rounded-rect primitive in the extractor, and a full row-per-pixel fill
     * would be four times the quads for the same picture.
     */
    private static void roundRect(GuiGraphicsExtractor ctx, int x, int y, int w, int h, int r, int color) {
        if (w <= 0 || h <= 0) return;
        r = Math.max(0, Math.min(r, Math.min(w / 2, h / 2)));
        if (r == 0) {
            ctx.fill(x, y, x + w, y + h, color);
            return;
        }
        for (int row = 0; row < r; row++) {
            int dy = r - row - 1;
            int inset = r - (int) Math.round(Math.sqrt((double) (r * r - dy * dy)));
            ctx.fill(x + inset, y + row, x + w - inset, y + row + 1, color);
            ctx.fill(x + inset, y + h - 1 - row, x + w - inset, y + h - row, color);
        }
        ctx.fill(x, y + r, x + w, y + h - r, color);
    }

    /** A rounded panel: fill with a 1px frame of {@code line} around it. */
    private static void panel(GuiGraphicsExtractor ctx, int x, int y, int w, int h, int r, int fill, int line) {
        roundRect(ctx, x, y, w, h, r, line);
        roundRect(ctx, x + 1, y + 1, w - 2, h - 2, Math.max(0, r - 1), fill);
    }

    /**
     * The wordmark: "veil" in the game font, scaled up and letter-spaced. The scale goes through the
     * extractor's pose matrix because the bitmap font only draws at its own size; the baseline is
     * divided back out so the caller still works in screen pixels.
     */
    private static void wordmark(GuiGraphicsExtractor ctx, Font font, int centerX, int top, int color) {
        String word = "veil";
        int width = 0;
        for (int i = 0; i < word.length(); i++) {
            width += font.width(String.valueOf(word.charAt(i)));
            if (i < word.length() - 1) width += WORD_GAP;
        }
        float x = (centerX - (width * WORD_SCALE) / 2f) / WORD_SCALE;
        float y = (float) top / WORD_SCALE;
        var pose = ctx.pose();
        pose.pushMatrix();
        pose.scale((float) WORD_SCALE, (float) WORD_SCALE);
        for (int i = 0; i < word.length(); i++) {
            String glyph = String.valueOf(word.charAt(i));
            ctx.text(font, glyph, Math.round(x), Math.round(y), color, false);
            x += font.width(glyph) + WORD_GAP;
        }
        pose.popMatrix();
    }

    /** A small switch: track plus knob. {@code k} is the animated 0..1 position of the knob. */
    private static void switchPill(GuiGraphicsExtractor ctx, int x, int y, float k) {
        roundRect(ctx, x, y, SWITCH_W, SWITCH_H, SWITCH_H / 2, Anim.mix(TRACK, ColorTheme.accent(), k));
        int knobX = x + SWITCH_PAD + Math.round(Anim.clamp01(k) * SWITCH_TRAVEL);
        roundRect(ctx, knobX, y + SWITCH_PAD, SWITCH_KNOB, SWITCH_KNOB, SWITCH_KNOB / 2,
                Anim.mix(KNOB, ColorTheme.knobOn(), k));
    }

    /** Slider track and knob for a float setting. */
    private static void sliderBar(GuiGraphicsExtractor ctx, float fraction, int x, int y, int w) {
        ctx.fill(x, y, x + w, y + 2, TRACK);
        int fill = Math.round(w * Anim.clamp01(fraction));
        if (fill > 0) ctx.fill(x, y, x + fill, y + 2, ColorTheme.accent());
        int knobX = Math.max(x, Math.min(x + w - 3, x + fill - 1));
        roundRect(ctx, knobX, y - 3, 4, 8, 2, KNOB);
    }

    // ── render ────────────────────────────────────────────────────────────────

    @Override
    public void extractRenderState(GuiGraphicsExtractor ctx, int mouseX, int mouseY, float partialTick) {
        long now = System.nanoTime();
        float dt = lastFrameNs == 0L ? 1f / 60f
                : Math.min(0.1f, (now - lastFrameNs) / 1_000_000_000f);
        lastFrameNs = now;

        layout();

        // Vanilla's menu blur, then our own violet wash over it. The blur applies to everything
        // drawn before this stratum — the world and the HUD — which is exactly the screen behind us.
        extractBlurredBackground(ctx);
        ctx.fillGradient(0, 0, width, height, WASH_TOP, WASH_BOTTOM);

        float fade = Anim.easeOutCubic(Anim.progress(openedAt, FADE_MS, now));

        if (selected == null) {
            drawBrand(ctx, fade);
            drawCards(ctx, mouseX, mouseY, dt, fade);
        } else {
            drawSheet(ctx, mouseX, mouseY, dt, fade);
        }
        drawFooter(ctx, fade);
    }

    private void drawBrand(GuiGraphicsExtractor ctx, float fade) {
        wordmark(ctx, font, width / 2, markTop, Anim.alpha(TITLE, fade));
        String sub = "COMBAT  ·  " + enabledCount() + " OF " + ModuleManager.getModules().size() + " ON";
        ctx.text(font, sub, (width - font.width(sub)) / 2, subY, Anim.alpha(DIM, fade), false);
    }

    private int enabledCount() {
        int on = 0;
        for (Module m : ModuleManager.getModules()) if (m.isEnabled()) on++;
        return on;
    }

    private void drawCards(GuiGraphicsExtractor ctx, int mouseX, int mouseY, float dt, float fade) {
        boolean searchLive = inside(mouseX, mouseY, searchX, searchY, searchW, SEARCH_H);
        panel(ctx, searchX, searchY, searchW, SEARCH_H, SEARCH_R, SEARCH_BG,
                search.isEmpty() ? (searchLive ? SEARCH_LINE_HOT : SEARCH_LINE) : ColorTheme.accentDim());

        int textY = centerY(searchY, SEARCH_H);
        int textLeft = searchX + SEARCH_PAD;
        int textRight = searchX + searchW - SEARCH_PAD;
        String shown = search;
        if (!search.isEmpty()) {
            boolean clearHover = inside(mouseX, mouseY, searchX + searchW - SEARCH_CLEAR, searchY,
                    SEARCH_CLEAR, SEARCH_H);
            ctx.text(font, "x", searchX + searchW - 15, textY - 1, clearHover ? TITLE : DIM, false);
            textRight -= SEARCH_CLEAR;
        }
        // A long query is read from its tail: the caret marks where typing continues.
        while (!shown.isEmpty() && font.width(shown) > textRight - textLeft - 2) {
            shown = shown.substring(1);
        }
        if (search.isEmpty()) {
            ctx.text(font, "Search modules", textLeft, textY, Anim.alpha(FAINT, fade), false);
        } else {
            ctx.text(font, shown, textLeft, textY, TITLE, false);
        }
        if (binding == null && (System.currentTimeMillis() / 500L) % 2 == 0) {
            int caretX = textLeft + font.width(shown) + (search.isEmpty() ? -1 : 1);
            ctx.fill(caretX, searchY + 7, caretX + 1, searchY + SEARCH_H - 7, ColorTheme.accent());
        }

        if (cards.isEmpty()) {
            String note = "No modules match";
            ctx.text(font, note, (width - font.width(note)) / 2, cardsTop + 8, DIM, false);
            return;
        }

        int clipTop = cardsTop;
        int clipBottom = cardsTop + cardsViewH;
        ctx.enableScissor(0, clipTop, width, clipBottom);
        for (Card card : cards) {
            if (card.y() + card.h() < clipTop || card.y() > clipBottom) continue;
            drawCard(ctx, card, mouseX, mouseY, dt);
        }
        ctx.disableScissor();

        if (cardScrollMax() > 0) {
            drawScrollBar(ctx, cardsTop, cardsViewH, cardsContentH, Math.round(cardScroll),
                    cardScrollMax(), true);
        }
    }

    private void drawCard(GuiGraphicsExtractor ctx, Card card, int mouseX, int mouseY, float dt) {
        Module module = card.module();
        boolean on = module.isEnabled();
        float hv = hover(module, insideClipped(mouseX, mouseY, card.x(), card.y(), card.w(), card.h(),
                cardsTop, cardsTop + cardsViewH), dt);

        int line = Anim.mix(on ? CARD_LINE_ON : CARD_LINE, ColorTheme.accentDim(), hv * 0.8f);
        if (on) line = Anim.mix(line, ColorTheme.accent(), 0.55f);
        panel(ctx, card.x(), card.y(), card.w(), card.h(), CARD_R, on ? CARD_BG_ON : CARD_BG, line);
        if (hv > 0.01f) {
            roundRect(ctx, card.x(), card.y(), card.w(), card.h(), CARD_R, Anim.alpha(HOVER, hv * 0.6f));
        }

        int left = card.x() + CARD_PAD;
        int right = card.x() + card.w() - CARD_PAD;

        // Bound key sits just left of the switch, where it does not push the name around.
        int nameRight = card.toggleX() - 6;
        if (module.getKeybind() != Module.UNBOUND) {
            String key = ellipsize(font, keyLabel(module.getKeybind()), 34);
            int kw = font.width(key);
            roundRect(ctx, nameRight - kw - 8, card.y() + 10, kw + 6, SWITCH_H, 3, CHIP_BG);
            ctx.text(font, key, nameRight - 5 - kw, centerY(card.y() + 10, SWITCH_H), DIM, false);
            nameRight -= kw + 12;
        }
        ctx.text(font, ellipsize(font, module.getName(), nameRight - left), left, card.y() + 12,
                on ? TITLE : SUB, false);

        switchPill(ctx, card.toggleX(), card.toggleY(), animateValue(module, on ? 1f : 0f, 22f, dt));

        List<String> desc = describe(font, module.getDescription(), card.w() - 2 * CARD_PAD, 2);
        for (int i = 0; i < desc.size(); i++) {
            ctx.text(font, desc.get(i), left, card.y() + 26 + i * LINE, DIM, false);
        }

        // The two settings the module is tuned by most: value shown, edited in the sheet.
        List<Setting<?>> settings = module.getSettings();
        for (int i = 0; i < Math.min(2, settings.size()); i++) {
            Setting<?> s = settings.get(i);
            int rowY = card.y() + 50 + i * 20;
            String value = valueLabel(s);
            ctx.text(font, ellipsize(font, s.getName(), card.w() - 2 * CARD_PAD - font.width(value) - 8),
                    left, rowY, SUB, false);
            ctx.text(font, value, right - font.width(value), rowY, ColorTheme.accent(), false);
            if (s instanceof FloatSetting f) {
                sliderBar(ctx, f.getFraction(), left, rowY + 11, card.w() - 2 * CARD_PAD);
            } else if (s instanceof BoolSetting b) {
                switchPill(ctx, right - SWITCH_W, rowY + 2, animateValue(s, b.get() ? 1f : 0f, 22f, dt));
            }
        }
    }

    private void drawSheet(GuiGraphicsExtractor ctx, int mouseX, int mouseY, float dt, float fade) {
        // The wordmark only has room above the sheet when the sheet is not using the whole screen.
        if (sheetY >= 56) wordmark(ctx, font, width / 2, sheetY - 32, Anim.alpha(TITLE, fade * 0.9f));

        panel(ctx, sheetX, sheetY, sheetW, sheetH, SHEET_R, SHEET_BG, SHEET_LINE);
        // Violet hairline under the header, the one strong accent on the sheet.
        ctx.fill(sheetX + 1, sheetY + SHEET_HEADER - 2, sheetX + sheetW - 1, sheetY + SHEET_HEADER - 1,
                ColorTheme.accentDim());

        ctx.text(font, "<", sheetLeft(), sheetY + 12, backHit(mouseX, mouseY) ? TITLE : DIM, false);
        ctx.text(font, ellipsize(font, selected.getName(), innerW() - 40), sheetLeft() + 14, sheetY + 12,
                TITLE, false);
        ctx.text(font, "x", sheetRight() - 7, sheetY + 12, closeHit(mouseX, mouseY) ? TITLE : DIM, false);

        // The header stays put; only the body scrolls, so the body is the only scissored part.
        int clipTop = sheetY + SHEET_HEADER;
        int clipBottom = sheetY + sheetH - 1;
        ctx.enableScissor(sheetX + 1, clipTop, sheetX + sheetW - 1, clipBottom);

        int descTop = clipTop - Math.round(sheetScroll);
        for (int i = 0; i < sheetDesc.size(); i++) {
            ctx.text(font, sheetDesc.get(i), sheetLeft(), descTop + i * LINE, DIM, false);
        }

        drawBindRow(ctx, mouseX, mouseY, dt);
        for (SheetRow row : sheetRows) {
            if (row.y + row.h <= clipTop || row.y >= clipBottom) continue;
            drawRow(ctx, row, mouseX, mouseY, dt);
        }

        ctx.disableScissor();

        if (sheetContentH > sheetH) {
            drawScrollBar(ctx, clipTop, sheetH - SHEET_HEADER - 2, sheetContentH,
                    Math.round(sheetScroll), sheetContentH - sheetH, false);
        }
    }

    private void drawBindRow(GuiGraphicsExtractor ctx, int mouseX, int mouseY, float dt) {
        float hv = hover(BIND_ROW, overRow(mouseX, mouseY, bindRowY, SET_ROW), dt);
        if (hv > 0.01f) roundRect(ctx, rowX(), bindRowY, rowW(), SET_ROW, 6, Anim.alpha(HOVER, hv));

        boolean listening = binding == selected;
        int textY = centerY(bindRowY, SET_ROW);
        ctx.text(font, "Bind", sheetLeft(), textY, Anim.mix(DIM, TITLE, hv), false);
        String value = ellipsize(font, listening ? "press a key..." : keyLabel(selected.getKeybind()),
                innerW() - font.width("Bind") - 12);
        ctx.text(font, value, sheetRight() - font.width(value), textY,
                listening ? ColorTheme.accent() : Anim.mix(FAINT, SUB, hv), false);
    }

    private void drawRow(GuiGraphicsExtractor ctx, SheetRow row, int mouseX, int mouseY, float dt) {
        Setting<?> s = row.setting;
        float hv = hover(s, overRow(mouseX, mouseY, row.y, row.h), dt);
        if (hv > 0.01f) roundRect(ctx, rowX(), row.y, rowW(), row.h, 6, Anim.alpha(HOVER, hv));
        int nameColor = Anim.mix(SUB, TITLE, hv);

        if (s instanceof BoolSetting b) {
            ctx.text(font, ellipsize(font, s.getName(), innerW() - SWITCH_W - 8), sheetLeft(),
                    centerY(row.y, SET_ROW), nameColor, false);
            switchPill(ctx, sheetRight() - SWITCH_W, row.y + 2,
                    animateValue(s, b.get() ? 1f : 0f, 22f, dt));

        } else if (s instanceof FloatSetting f) {
            String value = formatFloat(f);
            ctx.text(font, ellipsize(font, s.getName(), innerW() - font.width(value) - 10), sheetLeft(),
                    row.y + 2, nameColor, false);
            ctx.text(font, value, sheetRight() - font.width(value), row.y + 2, ColorTheme.accent(), false);
            sliderBar(ctx, animateValue(s, f.getFraction(), 30f, dt), sheetLeft(), row.y + 14, innerW());

        } else if (s instanceof IntSetting in) {
            String value = intLabel(in);
            ctx.text(font, ellipsize(font, s.getName(), innerW() - font.width(value) - 10), sheetLeft(),
                    centerY(row.y, SET_ROW), nameColor, false);
            ctx.text(font, value, row.valueX, centerY(row.y, SET_ROW), ColorTheme.accent(), false);

        } else if (s instanceof ModeSetting mode) {
            ctx.text(font, ellipsize(font, s.getName(), innerW()), sheetLeft(), row.y + 2, nameColor, false);
            int btnTop = row.y + MODE_LABEL_H;
            for (ModeButton btn : row.buttons) {
                int bx = sheetLeft() + btn.x(), by = btnTop + btn.y();
                boolean active = btn.option().equals(mode.get());
                float bhov = hover(new OptionKey(mode, btn.option()), insideClipped(mouseX, mouseY, bx, by,
                        btn.w(), MODE_BTN_H, sheetY + SHEET_HEADER, sheetY + sheetH - 1), dt);
                roundRect(ctx, bx, by, btn.w(), MODE_BTN_H, 3,
                        active ? ColorTheme.accent() : Anim.mix(MODE_BTN_BG, HOVER, bhov));
                String label = ellipsize(font, btn.option(), btn.w() - 6);
                ctx.text(font, label, bx + (btn.w() - font.width(label)) / 2, centerY(by, MODE_BTN_H),
                        active ? ColorTheme.knobOn() : Anim.mix(DIM, TITLE, bhov), false);
            }
        }
    }

    /** A 2px scrollbar at the right edge of a scrolling region. */
    private void drawScrollBar(GuiGraphicsExtractor ctx, int top, int viewH, int contentH,
                               int scroll, int maxScroll, boolean atScreenEdge) {
        if (maxScroll <= 0 || viewH <= 0) return;
        int x = atScreenEdge ? width - 6 : sheetX + sheetW - 7;
        int trackTop = top + 2, trackBottom = top + viewH - 2;
        if (trackBottom - trackTop < 6) return;
        int thumbH = Math.max(10, Math.round((trackBottom - trackTop) * (viewH / (float) contentH)));
        int thumbY = trackTop + Math.round((trackBottom - trackTop - thumbH) * (scroll / (float) maxScroll));
        ctx.fill(x, trackTop, x + 2, trackBottom, LINE_SOFT);
        ctx.fill(x, thumbY, x + 2, thumbY + thumbH, ColorTheme.accentDim());
    }

    private void drawFooter(GuiGraphicsExtractor ctx, float fade) {
        String hint = binding != null
                ? "Binding " + binding.getName() + ": press a key  ·  Esc cancel  ·  Backspace clear"
                : selected == null
                    ? "Click a module to configure  ·  Left-click the switch to toggle"
                    : "Esc back  ·  Scroll for more";
        ctx.text(font, ellipsize(font, hint, dotX(0) - 24), 12, height - 16, Anim.alpha(FAINT, fade), false);
        for (int i = 0; i < THEMES.length; i++) {
            boolean active = THEMES[i] == ColorTheme.get();
            roundRect(ctx, dotX(i), dotY(), DOT, DOT, DOT / 2, active ? ColorTheme.accent() : DOT_OFF);
        }
    }

    // ── theme dots (one geometry for drawing and for clicking) ────────────────

    private int dotsWidth() {
        return THEMES.length * DOT + (THEMES.length - 1) * DOT_GAP;
    }

    private int dotX(int index) {
        return width - FOOTER_PAD - dotsWidth() + index * (DOT + DOT_GAP);
    }

    private int dotY() {
        return height - 15;
    }

    /** Index of the theme dot under the cursor, or -1. */
    private int themeDotAt(int mx, int my) {
        for (int i = 0; i < THEMES.length; i++) {
            if (inside(mx, my, dotX(i) - DOT_HIT, dotY() - DOT_HIT,
                    DOT + 2 * DOT_HIT, DOT + 2 * DOT_HIT)) {
                return i;
            }
        }
        return -1;
    }

    // ── animation helpers ─────────────────────────────────────────────────────

    private float hover(Object key, boolean hovered, float dt) {
        float target = hovered ? 1f : 0f;
        float value = Anim.approach(hoverAnim.getOrDefault(key, target), target, HOVER_RATE, dt);
        hoverAnim.put(key, value);
        return value;
    }

    private float animateValue(Object key, float target, float rate, float dt) {
        float value = Anim.approach(valueAnim.getOrDefault(key, target), target, rate, dt);
        valueAnim.put(key, value);
        return value;
    }

    // ── input ─────────────────────────────────────────────────────────────────

    private static boolean inside(int mx, int my, int x, int y, int w, int h) {
        return mx >= x && mx < x + w && my >= y && my < y + h;
    }

    private static boolean insideClipped(int mx, int my, int x, int y, int w, int h,
                                         int clipTop, int clipBottom) {
        int top = Math.max(y, clipTop);
        int bottom = Math.min(y + h, clipBottom);
        return bottom > top && my >= top && my < bottom && mx >= x && mx < x + w;
    }

    @Override
    public boolean mouseClicked(MouseButtonEvent event, boolean doubleClick) {
        int mx = (int) event.x();
        int my = (int) event.y();
        boolean left = event.button() == InputConstants.MOUSE_BUTTON_LEFT;
        boolean right = event.button() == InputConstants.MOUSE_BUTTON_RIGHT;

        // Theme dots are reachable from both views.
        int dot = themeDotAt(mx, my);
        if (left && dot >= 0) {
            ColorTheme.set(THEMES[dot]);
            return true;
        }

        if (selected != null) return clickSheet(mx, my, left, right);

        // Search field first: it sits above the cards and shares their horizontal band.
        if (inside(mx, my, searchX, searchY, searchW, SEARCH_H)) {
            if (!search.isEmpty() && (right || inside(mx, my, searchX + searchW - SEARCH_CLEAR,
                    searchY, SEARCH_CLEAR, SEARCH_H))) {
                search = "";
                layout();
            }
            return true;
        }

        for (Card card : cards) {
            if (!insideClipped(mx, my, card.x(), card.y(), card.w(), card.h(),
                    cardsTop, cardsTop + cardsViewH)) continue;
            if (!left) return true;
            if (card.onToggle(mx, my)) card.module().toggle();
            else openSheet(card.module());
            return true;
        }
        return super.mouseClicked(event, doubleClick);
    }

    private boolean clickSheet(int mx, int my, boolean left, boolean right) {
        // Anywhere outside the sheet, and both header glyphs, go back to the cards.
        boolean outside = !inside(mx, my, sheetX, sheetY, sheetW, sheetH);
        if (left && (outside || backHit(mx, my) || closeHit(mx, my))) {
            closeSheet();
            return true;
        }
        if (outside) return true;

        if (overRow(mx, my, bindRowY, SET_ROW)) {
            if (left) binding = selected;
            else if (right) {
                selected.setKeybind(Module.UNBOUND);
                binding = null;
            }
            return true;
        }

        for (SheetRow row : sheetRows) {
            if (!overRow(mx, my, row.y, row.h)) continue;
            clickRow(row, mx, my, left, right);
            return true;
        }
        return true;
    }

    /** Applies a click to one settings row; the caller has already claimed the click. */
    private void clickRow(SheetRow row, int mx, int my, boolean left, boolean right) {
        if (row.setting instanceof BoolSetting b) {
            if (left) b.toggle();
            return;
        }
        if (row.setting instanceof FloatSetting f) {
            if (!left) return;
            draggingSlider = f;
            dragBarX = sheetLeft();
            dragBarW = Math.max(1, innerW());
            slideTo(f, mx);
            return;
        }
        if (row.setting instanceof IntSetting in) {
            if (!(left || right)) return;
            int labelW = font.width(intLabel(in));
            // A click left of the readout only opens the row; the arrows themselves step it.
            if (mx < row.valueX - 2) return;
            if (right || mx < row.valueX + labelW / 2) in.decrement();
            else in.increment();
            return;
        }
        if (row.setting instanceof ModeSetting mode && left) {
            int btnTop = row.y + MODE_LABEL_H;
            for (ModeButton btn : row.buttons) {
                if (inside(mx, my, sheetLeft() + btn.x(), btnTop + btn.y(), btn.w(), MODE_BTN_H)) {
                    mode.set(btn.option());
                    return;
                }
            }
        }
    }

    private void openSheet(Module module) {
        selected = module;
        binding = null;
        sheetScroll = 0f;
        layout();
    }

    /** Back to the cards, dropping a bind that was being listened for on the sheet. */
    private void closeSheet() {
        selected = null;
        binding = null;
        sheetScroll = 0f;
        layout();
    }

    private void slideTo(FloatSetting f, int mx) {
        float fraction = (float) (mx - dragBarX) / dragBarW;
        f.setFraction(Math.max(0f, Math.min(1f, fraction)));
    }

    @Override
    public boolean mouseDragged(MouseButtonEvent event, double dragX, double dragY) {
        if (draggingSlider != null) {
            slideTo(draggingSlider, (int) event.x());
            return true;
        }
        return super.mouseDragged(event, dragX, dragY);
    }

    @Override
    public boolean mouseReleased(MouseButtonEvent event) {
        draggingSlider = null;
        return super.mouseReleased(event);
    }

    @Override
    public boolean mouseScrolled(double mouseX, double mouseY, double horizontalAmount, double verticalAmount) {
        if (selected != null) {
            int max = Math.max(0, sheetContentH - sheetH);
            sheetScroll = Math.max(0f, Math.min(max, sheetScroll - (float) verticalAmount * 14f));
            return true;
        }
        cardScroll = Math.max(0f, Math.min(cardScrollMax(), cardScroll - (float) verticalAmount * 14f));
        return true;
    }

    @Override
    public boolean keyPressed(KeyEvent event) {
        // A module waiting for a bind swallows the key, including Escape (which cancels the bind
        // rather than closing the GUI, so a mistaken click does not throw the user out).
        if (binding != null) {
            Module module = binding;
            binding = null;
            bindConsumedAt = System.nanoTime();
            if (event.key() == InputConstants.KEY_ESCAPE) return true;
            module.setKeybind(event.key() == InputConstants.KEY_BACKSPACE
                    ? Module.UNBOUND
                    : event.key());
            return true;
        }

        if (event.key() == InputConstants.KEY_ESCAPE) {
            // Escape leaves the settings sheet first, then clears a search, then closes the screen.
            if (selected != null) {
                closeSheet();
                return true;
            }
            if (!search.isEmpty()) {
                search = "";
                layout();
                return true;
            }
            onClose();
            return true;
        }
        if (event.key() == InputConstants.KEY_BACKSPACE && !search.isEmpty()) {
            search = search.substring(0, search.length() - 1);
            layout();
            return true;
        }
        return super.keyPressed(event);
    }

    /**
     * Appends typed characters to the search box. 26.3 delivers these as a codepoint record rather
     * than the old {@code (char, int)} pair, so the character has to be rebuilt from the codepoint.
     */
    @Override
    public boolean charTyped(CharacterEvent event) {
        if (event.codepoint() < 32) return true;
        if (binding != null) return true;
        if (bindConsumedAt != 0L && System.nanoTime() - bindConsumedAt < 100_000_000L) {
            bindConsumedAt = 0L;
            return true;
        }
        if (search.length() >= 32) return true;
        search += event.codepointAsString();
        layout();
        return true;
    }
}
