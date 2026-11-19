package com.veil.gui;

import com.veil.Veil;
import com.veil.module.Category;
import com.veil.module.Module;
import com.veil.module.ModuleManager;
import com.veil.setting.BoolSetting;
import com.veil.setting.FloatSetting;
import com.veil.setting.IntSetting;
import com.veil.setting.ModeSetting;
import com.veil.setting.Setting;
import com.mojang.blaze3d.platform.InputConstants;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.input.CharacterEvent;
import net.minecraft.client.input.KeyEvent;
import net.minecraft.client.input.MouseButtonEvent;
import net.minecraft.network.chat.Component;

import java.util.ArrayList;
import java.util.EnumMap;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/**
 * The Veil GUI: a category menu on the left and one floating panel per category, in the style of the
 * old Vape click GUI. Left-click a module to toggle it, right-click to open its settings.
 *
 * <p>26.3 note: Screen#render is gone; drawing goes through GuiGraphicsExtractor in
 * extractRenderState, and screen input takes {@link MouseButtonEvent} records because
 * {@code GuiEventListener} declares {@code mouseClicked(MouseButtonEvent, boolean)}. The old
 * {@code (double, double, int)} methods were replaced outright, not overloaded, so the record form
 * is the only implementation that receives clicks.
 *
 * <p>Animation note: every eased value here is driven by wall-clock time through {@link Anim}, not
 * by a frame counter, and every animated value is read by both the renderer and the hit tests. A
 * drawer that is drawn half open is only half clickable, so what the cursor can reach always matches
 * what is on screen.
 *
 * <p>Layout note: every piece of text that shares a row with something else is measured and
 * shortened with {@link #ellipsize} to the space left beside it, so a long setting name, key name,
 * suffix or mode option is cut with ".." instead of running into its neighbour. The layout helpers
 * ({@link #modeButtons}, {@link #rowHeight}, {@link #intZone}) are shared by the renderer and the
 * hit tests for the same reason the animations are.
 */
public final class ClickGUI extends Screen {

    // ── palette (delegated to ColorTheme) ─────────────────────────────────────
    private static final int DIM        = ColorTheme.DIM;
    private static final int MENU_BG    = ColorTheme.MENU_BG;
    private static final int MENU_HDR   = ColorTheme.MENU_HDR;
    private static final int PANEL_BG   = ColorTheme.PANEL_BG;
    private static final int PANEL_TOP  = ColorTheme.PANEL_TOP;
    private static final int ROW_OFF    = ColorTheme.ROW_OFF;
    private static final int ROW_HOVER  = ColorTheme.ROW_HOVER;
    private static final int TEXT_ON    = ColorTheme.TEXT_ON;
    private static final int TEXT_OFF   = ColorTheme.TEXT_OFF;
    private static final int TEXT_DIM   = ColorTheme.TEXT_DIM;
    private static final int SEPARATOR  = ColorTheme.SEPARATOR;
    private static final int DRAWER_BG  = ColorTheme.DRAWER_BG;
    private static final int WIDGET_BG  = ColorTheme.WIDGET_BG;
    private static final int KNOB_OFF   = ColorTheme.KNOB_OFF;
    private static final int BORDER     = ColorTheme.BORDER;
    private static final int SHADOW     = ColorTheme.SHADOW;
    private static final int TOOLTIP_BG = ColorTheme.TOOLTIP_BG;
    /** Secondary text on an enabled (accent-filled) module row. */
    private static final int TEXT_ON_META = 0xCCFFFFFF;

    // ── geometry ──────────────────────────────────────────────────────────────
    private static final int MENU_W     = 88;
    private static final int MENU_HDR_H = 18;
    private static final int PANEL_W    = 120;
    private static final int PANEL_HDR  = 16;
    private static final int PANEL_GAP  = 6;
    private static final int ROW_H      = 16;
    private static final int SEARCH_H   = 14;
    private static final int SET_ROW    = 14;
    private static final int SLIDE_ROW  = 22;
    /** Module rows are inset this far from the panel's sides; the right gutter holds the scrollbar. */
    private static final int ROW_INSET  = 4;
    /** Width of a module row and of its settings drawer. */
    private static final int CONTENT_W  = PANEL_W - 2 * ROW_INSET;
    /** Text inside a row starts this far from the row's left edge... */
    private static final int PAD_L      = 6;
    /** ...and ends this far from its right edge. */
    private static final int PAD_R      = 5;
    /** Width available to a slider bar or a row of mode buttons. */
    private static final int INNER_W    = CONTENT_W - 2 * PAD_L;
    /** Mode settings: the name line, then rows of option buttons that wrap when they run out of room. */
    private static final int MODE_LABEL_H = 13;
    private static final int MODE_BTN_H   = 11;
    private static final int MODE_BTN_GAP = 2;
    private static final int MODE_BTN_PAD = 6;
    private static final int MODE_BOTTOM  = 3;
    /** Glyph height of the default font without its line gap; used to centre text in a row. */
    private static final int GLYPH_H      = 7;
    private static final int TOOLTIP_W    = 150;
    private static final long TOOLTIP_DELAY_MS = 450L;

    // ── animation ─────────────────────────────────────────────────────────────
    /** How long the backdrop takes to reach full dim. */
    private static final long FADE_MS = 140L;
    /** Hover easing: roughly how many times a second the remaining difference is closed. */
    private static final float HOVER_RATE = 18f;
    /** Drawer easing, per second. */
    private static final float DRAWER_RATE = 30f;
    private static final float SLIDE_RATE = 14f;

    /**
     * Hover key for a module's bind row. Each drawer has its own: a single shared key made two open
     * drawers fight over one hover value every frame, so neither bind row could highlight properly.
     */
    private record BindRow(Module module) {}

    /** Hover key for one option button of a mode setting. */
    private record ModeOption(ModeSetting setting, String option) {}

    /** One option button of a mode setting, relative to the top-left of the button area. */
    private record ModeButton(String option, int x, int y, int w) {}

    /** One floating category panel. Positions are remembered across openings. */
    private static final class Panel {
        final Category category;
        int x, y, scroll;

        Panel(Category category) { this.category = category; }
    }

    private final Map<Category, Panel> panels = new EnumMap<>(Category.class);
    private final Set<Category> shown = new HashSet<>();
    private final Set<Module> open = new HashSet<>();
    /** Draw order of the panels, back to front. A panel that is clicked moves to the front. */
    private final List<Category> zOrder = new ArrayList<>(List.of(Category.values()));

    private int menuX = -1, menuY = 20;
    private boolean laidOut;
    private boolean draggingMenu, draggingPanel;
    private Panel draggedPanel;
    private int dragOffX, dragOffY;
    private FloatSetting draggingSlider;
    private Panel sliderPanel;
    private String search = "";
    /** Module waiting for a key press to bind, or null when nothing is being bound. */
    private Module bindingModule;
    /**
     * When a key press was last used up as a bind. A letter key is followed by a character event
     * for the same press, which would otherwise land in the search box right after being bound.
     */
    private long bindConsumedAt;

    /** When this screen was opened, for the backdrop fade. */
    private long openedAt;
    /** Previous frame's timestamp; 0 until the first frame of an opening. */
    private long lastFrameNs;
    /** Smoothed hover amount per row, keyed by the category, module or setting the row draws. */
    private final Map<Object, Float> hoverAnim = new HashMap<>();
    /** Smoothed drawer opening per module: 0 closed, 1 fully open. */
    private final Map<Module, Float> drawerAnim = new HashMap<>();
    /** Smoothed value per widget, so toggles glide and sliders follow instead of jumping. */
    private final Map<Object, Float> valueAnim = new HashMap<>();
    /** Per-module flash on enable: 1 when toggled on, decays to 0. */
    private final Map<Module, Float> pulseAnim = new HashMap<>();
    /** Slide-in progress for the menu, 0=hidden 1=fully in. Reset on each open. */
    private float menuSlide = 0f;
    /** Per-panel slide-in progress. Reset on each open. */
    private final Map<Category, Float> panelSlide = new EnumMap<>(Category.class);

    /** What the tooltip currently describes, and since when the cursor has rested on it. */
    private Object tipKey;
    private long tipSince;
    /** Tooltip requested by this frame's rows; drawn last so it sits on top of everything. */
    private Object frameTipKey;
    private String frameTipText;

    public ClickGUI() {
        super(Component.empty());
        for (Category c : Category.values()) {
            shown.add(c);
            panels.put(c, new Panel(c));
        }
    }

    @Override
    protected void init() {
        // init() runs on every open (and on resize), which is exactly when the fade should replay.
        openedAt = System.nanoTime();
        lastFrameNs = 0L;
        menuSlide = 0f;
        tipKey = null;
        for (Category c : Category.values()) panelSlide.put(c, 0f);
        if (menuX < 0) {
            menuX = 8;
            menuY = 20;
        }
        menuX = Math.max(0, Math.min(width - MENU_W, menuX));
        menuY = Math.max(0, Math.min(height - MENU_HDR_H, menuY));
        if (!laidOut) {
            layoutPanels();
            laidOut = true;
        }
        for (Panel p : panels.values()) {
            p.x = Math.max(0, Math.min(width - PANEL_W, p.x));
            // Leave the header and at least one row on screen; a panel dragged to the bottom edge
            // would otherwise keep every row below the window with no way to scroll to them.
            p.y = Math.max(0, Math.min(height - PANEL_HDR - ROW_H, p.y));
        }
    }

    @Override
    public boolean isPauseScreen() { return false; }

    // ── layout ────────────────────────────────────────────────────────────────

    /** Packs the panels left to right from the menu, wrapping to a new row when they run out of room. */
    private void layoutPanels() {
        int startX = menuX + MENU_W + PANEL_GAP;
        int x = startX, y = 20, rowH = 0;
        for (Category c : Category.values()) {
            Panel p = panels.get(c);
            int h = panelFullHeight(p);
            if (x + PANEL_W > width - 4 && x > startX) {
                x = startX;
                y += rowH + PANEL_GAP;
                rowH = 0;
            }
            p.x = x;
            p.y = y;
            rowH = Math.max(rowH, h);
            x += PANEL_W + PANEL_GAP;
        }
    }

    /**
     * Display name for a bound key.
     *
     * <p>Binds are stored as the SDL scancode the key events carry, so the label comes from
     * {@link InputConstants.Type#KEYBOARD} rather than from the raw number. An unknown code falls
     * back to the number so a stale config entry is visible instead of blank.
     */
    private static String keyLabel(int key) {
        if (key == Module.UNBOUND) return "none";
        try {
            return InputConstants.Type.KEYBOARD.getOrCreate(key).getDisplayName().getString();
        } catch (RuntimeException error) {
            return String.valueOf(key);
        }
    }

    private static String menuLabel(Category c) {
        return switch (c) {
            case COMBAT   -> "Combat";
            case MOVEMENT -> "Movement";
            case RENDER   -> "Render";
            case WORLD    -> "World";
            case PLAYER   -> "Player";
            case UTILITY  -> "Utility";
            case MISC     -> "Misc";
        };
    }

    private List<Module> modules(Category c) {
        String q = search.toLowerCase(Locale.ROOT);
        return ModuleManager.getByCategory(c).stream()
                .filter(m -> q.isEmpty() || m.getName().toLowerCase(Locale.ROOT).contains(q))
                .toList();
    }

    /** Shown, non-empty panels in draw order (back to front). */
    private List<Panel> visiblePanels() {
        List<Panel> out = new ArrayList<>();
        for (Category c : zOrder) {
            Panel p = panels.get(c);
            if (shown.contains(c) && !modules(c).isEmpty()) out.add(p);
        }
        return out;
    }

    private void bringToFront(Panel p) {
        zOrder.remove(p.category);
        zOrder.add(p.category);
    }

    /** The front-most panel under the cursor, or null when the cursor is not over any panel. */
    private Panel panelAt(List<Panel> list, int mx, int my) {
        for (int i = list.size() - 1; i >= 0; i--) {
            Panel p = list.get(i);
            if (inside(mx, my, p.x, p.y, PANEL_W, panelViewHeight(p))) return p;
        }
        return null;
    }

    private static Font font() { return Minecraft.getInstance().font; }

    /**
     * Lays out a mode's option buttons in rows no wider than {@link #INNER_W}.
     *
     * <p>Each button is sized to its label, and a row's spare width is shared out so every row ends
     * flush with the drawer. A mode with many or long options (the server profiles, PearlAim's target
     * priorities) wraps onto further rows instead of squeezing every label into a sliver of the
     * width, which is what used to make the labels run into each other.
     */
    private static List<ModeButton> modeButtons(Font font, ModeSetting mode) {
        List<ModeButton> out = new ArrayList<>();
        List<String> row = new ArrayList<>();
        List<Integer> widths = new ArrayList<>();
        int used = 0, y = 0;
        for (String opt : mode.getOptions()) {
            int w = Math.min(INNER_W, font.width(opt) + MODE_BTN_PAD);
            if (!row.isEmpty() && used + MODE_BTN_GAP + w > INNER_W) {
                flushModeRow(out, row, widths, used, y);
                y += MODE_BTN_H + MODE_BTN_GAP;
                row.clear();
                widths.clear();
                used = 0;
            }
            used += (row.isEmpty() ? 0 : MODE_BTN_GAP) + w;
            row.add(opt);
            widths.add(w);
        }
        if (!row.isEmpty()) flushModeRow(out, row, widths, used, y);
        return out;
    }

    private static void flushModeRow(List<ModeButton> out, List<String> row, List<Integer> widths,
                                     int used, int y) {
        int n = row.size();
        int extra = Math.max(0, INNER_W - used);
        int x = 0;
        for (int i = 0; i < n; i++) {
            int w = widths.get(i) + extra / n + (i < extra % n ? 1 : 0);
            out.add(new ModeButton(row.get(i), x, y, w));
            x += w + MODE_BTN_GAP;
        }
    }

    private static int modeHeight(ModeSetting mode) {
        List<ModeButton> buttons = modeButtons(font(), mode);
        int rows = buttons.isEmpty() ? 1 : buttons.get(buttons.size() - 1).y() / (MODE_BTN_H + MODE_BTN_GAP) + 1;
        return MODE_LABEL_H + rows * MODE_BTN_H + (rows - 1) * MODE_BTN_GAP + MODE_BOTTOM;
    }

    private static int rowHeight(Setting<?> s) {
        if (s instanceof FloatSetting) return SLIDE_ROW;
        if (s instanceof ModeSetting mode) return modeHeight(mode);
        return SET_ROW;
    }

    /** Height of a settings drawer laid out fully open, including the padding under its last row. */
    private int drawerFullHeight(Module m) {
        int h = 2 + SET_ROW;
        for (Setting<?> s : m.getSettings()) h += rowHeight(s);
        return h;
    }

    /**
     * The drawer height currently being drawn.
     *
     * <p>Rows are always laid out at their full-open offsets and the drawer is clipped to this
     * height, so opening looks like the drawer sliding out from under the module row rather than the
     * rows being squashed. The value is 0 while closed, which is what lets the rows below it close
     * the gap smoothly instead of jumping.
     */
    private int drawerHeight(Module m) {
        float progress = drawerAnim.getOrDefault(m, 0f);
        if (progress <= 0.001f) return 0;
        if (progress >= 0.999f) return drawerFullHeight(m);
        return Math.round(drawerFullHeight(m) * progress);
    }

    /** Body height: 2px of padding above the first row and below the last. */
    private int panelBodyHeight(Panel p) {
        int h = 4;
        for (Module m : modules(p.category)) {
            h += ROW_H;
            h += drawerHeight(m);
        }
        return h;
    }

    private int panelFullHeight(Panel p) { return PANEL_HDR + panelBodyHeight(p); }

    /** Visible height: the panel is clipped to the screen, and scrolls when its content is taller. */
    private int panelViewHeight(Panel p) {
        return Math.max(PANEL_HDR + ROW_H, Math.min(panelFullHeight(p), height - p.y - 2));
    }

    private int maxScroll(Panel p) {
        return Math.max(0, panelFullHeight(p) - panelViewHeight(p));
    }

    /** The scroll offset the renderer is using, so hit-testing cannot disagree with what is drawn. */
    private int scrollOf(Panel p) {
        return Math.max(0, Math.min(maxScroll(p), p.scroll));
    }

    private int menuHeight() {
        return MENU_HDR_H + 3 + SEARCH_H + 4 + Category.values().length * ROW_H + 4;
    }

    /** Top of the first category row; shared by the renderer and the click handler. */
    private int menuRowsTop() {
        return menuY + MENU_HDR_H + 3 + SEARCH_H + 4;
    }

    // ── text helpers ──────────────────────────────────────────────────────────

    /** The y at which text sits vertically centred in a row starting at {@code top}. */
    private static int centerY(int top, int h) {
        return top + (h - GLYPH_H) / 2;
    }

    /**
     * Shortens {@code text} with a trailing ".." until it fits in {@code maxWidth} pixels.
     *
     * <p>Never cuts between a section sign and its formatting code: a dangling {@code §} would turn
     * the dots into a formatting code and leave the label visibly broken.
     */
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
     * Readout for a slider. Wide ranges drop decimals that would only add noise ("3000" rather than
     * "3000.00"), which also keeps the value from crowding out the setting name.
     */
    private static String formatFloat(FloatSetting f) {
        float range = f.getMax() - f.getMin();
        String pattern = range >= 100f ? "%.0f" : range >= 10f ? "%.1f" : "%.2f";
        return String.format(Locale.ROOT, pattern, f.get());
    }

    private static String intLabel(IntSetting setting) {
        return "◂ " + setting.get() + " ▸";
    }

    private static void outline(GuiGraphicsExtractor ctx, int x, int y, int w, int h, int color) {
        ctx.fill(x, y, x + w, y + 1, color);
        ctx.fill(x, y + h - 1, x + w, y + h, color);
        ctx.fill(x, y + 1, x + 1, y + h - 1, color);
        ctx.fill(x + w - 1, y + 1, x + w, y + h - 1, color);
    }

    /** Asks for a tooltip this frame. The first request wins, which is the front-most element. */
    private void requestTip(Object key, String text) {
        if (frameTipKey != null || text == null || text.isBlank()) return;
        frameTipKey = key;
        frameTipText = text;
    }

    // ── animation helpers ─────────────────────────────────────────────────────

    /** Eases this module's drawer toward its open or closed target. Called once per drawn frame. */
    private void animateDrawer(Module m, float dt) {
        float target = open.contains(m) ? 1f : 0f;
        drawerAnim.put(m, Anim.approach(drawerAnim.getOrDefault(m, target), target, DRAWER_RATE, dt));
    }

    /** Eases one row's hover amount toward {@code hovered ? 1 : 0}. */
    private float hover(Object key, boolean hovered, float dt) {
        float target = hovered ? 1f : 0f;
        float value = Anim.approach(hoverAnim.getOrDefault(key, target), target, HOVER_RATE, dt);
        hoverAnim.put(key, value);
        return value;
    }

    /** Eases a widget's drawn value toward the value the setting actually holds. */
    private float animateValue(Object key, float target, float rate, float dt) {
        float value = Anim.approach(valueAnim.getOrDefault(key, target), target, rate, dt);
        valueAnim.put(key, value);
        return value;
    }

    // ── render ────────────────────────────────────────────────────────────────

    @Override
    public void extractRenderState(GuiGraphicsExtractor ctx, int mouseX, int mouseY, float partialTick) {
        Font font = font();

        long now = System.nanoTime();
        float dt = lastFrameNs == 0L ? 1f / 60f
                : Math.min(0.1f, (now - lastFrameNs) / 1_000_000_000f);
        lastFrameNs = now;

        // The world dims in behind the GUI instead of snapping dark, and the panels settle a beat
        // later than the backdrop so the screen reads as opening rather than being pasted on.
        float fade = Anim.easeOutCubic(Anim.progress(openedAt, FADE_MS, now));
        ctx.fill(0, 0, width, height, Anim.alpha(DIM, fade));

        menuSlide = Anim.approach(menuSlide, 1f, SLIDE_RATE, dt);
        for (Category c : Category.values()) {
            panelSlide.put(c, Anim.approach(panelSlide.getOrDefault(c, 0f), 1f, SLIDE_RATE, dt));
        }

        renderHint(ctx, font, fade);

        // Only the front-most thing under the cursor reacts to it. Without this, a row in a panel
        // that is covered by another panel (or the menu under a panel) lit up through the panel on
        // top, so two overlapping rows highlighted at once.
        boolean dragging = draggingMenu || draggingPanel || draggingSlider != null;
        List<Panel> list = visiblePanels();
        Panel top = dragging ? null : panelAt(list, mouseX, mouseY);
        boolean menuLive = !dragging && top == null;

        // At most one element receives the real cursor position, so at most one asks for a tooltip.
        frameTipKey = null;
        frameTipText = null;
        renderMenu(ctx, font, menuLive ? mouseX : -1, menuLive ? mouseY : -1, dt);
        for (Panel p : list) {
            boolean live = p == top;
            renderPanel(ctx, font, p, live ? mouseX : -1, live ? mouseY : -1, dt);
        }

        renderEmptySearch(ctx, font, list);
        renderTooltip(ctx, font, mouseX, mouseY, dragging);
    }

    /** One line of controls along the bottom edge, or the bind prompt while a bind is pending. */
    private void renderHint(GuiGraphicsExtractor ctx, Font font, float fade) {
        String hint;
        int color;
        if (bindingModule != null) {
            hint = "Binding " + bindingModule.getName() + ": press a key  ·  Esc cancel  ·  Backspace clear";
            color = ColorTheme.accent();
        } else {
            hint = "Left-click toggle  ·  Right-click settings  ·  Type to search";
            color = TEXT_DIM;
        }
        hint = ellipsize(font, hint, width - 8);
        int x = (width - font.width(hint)) / 2;
        ctx.text(font, hint, x, height - 11, Anim.alpha(color, fade), false);
    }

    private void renderMenu(GuiGraphicsExtractor ctx, Font font, int mouseX, int mouseY, float dt) {
        int menuH = menuHeight();

        float ms = Anim.easeOutCubic(menuSlide);
        ctx.fill(menuX + 2, menuY + 2, menuX + MENU_W + 2, menuY + menuH + 2, Anim.alpha(SHADOW, ms));
        ctx.fill(menuX, menuY, menuX + MENU_W, menuY + menuH, Anim.alpha(MENU_BG, ms));
        ctx.fill(menuX, menuY, menuX + MENU_W, menuY + MENU_HDR_H, Anim.alpha(MENU_HDR, ms));
        ctx.fill(menuX, menuY, menuX + 3, menuY + MENU_HDR_H, Anim.alpha(ColorTheme.accent(), ms));
        ctx.fill(menuX, menuY + MENU_HDR_H - 1, menuX + MENU_W, menuY + MENU_HDR_H,
                Anim.alpha(ColorTheme.accentDim(), ms));
        int titleY = centerY(menuY, MENU_HDR_H - 1);
        ctx.text(font, "VEIL", menuX + 8, titleY, TEXT_ON, false);

        // Theme swatch: a small square of the accent, framed so it reads as a button.
        boolean swatchHover = inside(mouseX, mouseY, menuX + MENU_W - 28, menuY + 2, 12, MENU_HDR_H - 4);
        float sh = hover("swatch", swatchHover, dt);
        int swX = menuX + MENU_W - 26, swY = menuY + 5;
        outline(ctx, swX, swY, 8, 8, Anim.mix(BORDER, TEXT_OFF, sh));
        ctx.fill(swX + 1, swY + 1, swX + 7, swY + 7, ColorTheme.accent());
        if (swatchHover) requestTip("swatch", "Theme: " + ColorTheme.name() + " (click to cycle)");

        boolean closeHover = inside(mouseX, mouseY, menuX + MENU_W - 14, menuY + 2, 12, MENU_HDR_H - 4);
        float ch = hover("close", closeHover, dt);
        ctx.text(font, "x", menuX + MENU_W - 11, titleY - 1, Anim.mix(TEXT_DIM, TEXT_ON, ch), false);
        if (closeHover) requestTip("close", "Close (Esc)");

        // Search field. Text that does not fit scrolls left so the end being typed stays visible.
        int sx = menuX + 4, sy = menuY + MENU_HDR_H + 3, sw = MENU_W - 8;
        ctx.fill(sx, sy, sx + sw, sy + SEARCH_H, DRAWER_BG);
        outline(ctx, sx, sy, sw, SEARCH_H, search.isEmpty() ? BORDER : ColorTheme.accentDim());
        int textY = centerY(sy, SEARCH_H);
        int textRight = sx + sw - 4;
        if (!search.isEmpty()) {
            boolean clearHover = inside(mouseX, mouseY, sx + sw - 11, sy, 11, SEARCH_H);
            ctx.text(font, "x", sx + sw - 8, textY - 1, clearHover ? TEXT_ON : TEXT_DIM, false);
            textRight = sx + sw - 11;
        }
        int textLeft = sx + 4;
        String shownSearch = search;
        while (!shownSearch.isEmpty() && font.width(shownSearch) > textRight - textLeft - 2) {
            shownSearch = shownSearch.substring(1);
        }
        if (search.isEmpty()) {
            ctx.text(font, "Search...", textLeft, textY, TEXT_DIM, false);
        } else {
            ctx.text(font, shownSearch, textLeft, textY, TEXT_ON, false);
        }
        boolean caret = bindingModule == null && (System.currentTimeMillis() / 500L) % 2 == 0;
        if (caret) {
            int caretX = textLeft + font.width(shownSearch) + (search.isEmpty() ? -1 : 1);
            ctx.fill(caretX, sy + 3, caretX + 1, sy + SEARCH_H - 3, ColorTheme.accent());
        }

        int cy = menuRowsTop();
        for (Category c : Category.values()) {
            boolean hovered = inside(mouseX, mouseY, menuX, cy, MENU_W, ROW_H);
            boolean active = shown.contains(c);
            boolean matches = search.isEmpty() || !modules(c).isEmpty();
            float hv = hover(c, hovered, dt);
            if (hv > 0.01f) {
                ctx.fill(menuX + 1, cy, menuX + MENU_W - 1, cy + ROW_H, Anim.mix(MENU_BG, ROW_HOVER, hv));
            }
            if (active) ctx.fill(menuX, cy + 2, menuX + 2, cy + ROW_H - 2, ColorTheme.accent());

            int labelColor = active ? TEXT_ON : Anim.mix(TEXT_DIM, TEXT_OFF, hv);
            if (!matches) labelColor = Anim.alpha(labelColor, 0.45f);
            int rowTextY = centerY(cy, ROW_H);

            // Enabled-module count on the right, so a hidden category still shows it is doing work.
            int right = menuX + MENU_W - 7;
            int enabled = 0;
            for (Module m : ModuleManager.getByCategory(c)) if (m.isEnabled()) enabled++;
            if (enabled > 0) {
                String count = String.valueOf(enabled);
                right -= font.width(count);
                ctx.text(font, count, right, rowTextY, active ? ColorTheme.accent() : ColorTheme.accentDim(), false);
                right -= 4;
            }
            String label = ellipsize(font, menuLabel(c), right - (menuX + 9));
            ctx.text(font, label, menuX + 9, rowTextY, labelColor, false);
            cy += ROW_H;
        }

        outline(ctx, menuX, menuY, MENU_W, menuH, Anim.alpha(BORDER, ms));
    }

    /** A note under the menu when the search hides every module, instead of an empty screen. */
    private void renderEmptySearch(GuiGraphicsExtractor ctx, Font font, List<Panel> list) {
        if (search.isEmpty() || !list.isEmpty()) return;
        String note = "No modules match";
        int y = menuY + menuHeight() + 5;
        if (y + 9 > height) y = menuY - 12;
        ctx.text(font, note, menuX + (MENU_W - font.width(note)) / 2, y, TEXT_DIM, false);
    }

    private void renderPanel(GuiGraphicsExtractor ctx, Font font, Panel p, int mouseX, int mouseY, float dt) {
        int viewH = panelViewHeight(p);
        p.scroll = scrollOf(p);

        float ps = Anim.easeOutCubic(panelSlide.getOrDefault(p.category, 1f));
        ctx.fill(p.x + 2, p.y + 2, p.x + PANEL_W + 2, p.y + viewH + 2, Anim.alpha(SHADOW, ps));
        ctx.fill(p.x, p.y, p.x + PANEL_W, p.y + viewH, Anim.alpha(PANEL_BG, ps));
        ctx.fill(p.x, p.y, p.x + PANEL_W, p.y + PANEL_HDR, Anim.alpha(PANEL_TOP, ps));
        ctx.fill(p.x, p.y + PANEL_HDR - 1, p.x + PANEL_W, p.y + PANEL_HDR, Anim.alpha(ColorTheme.accentDim(), ps));

        // Header: category name on the left, enabled/total on the right.
        List<Module> all = ModuleManager.getByCategory(p.category);
        int enabled = 0;
        for (Module m : all) if (m.isEnabled()) enabled++;
        String count = enabled + "/" + all.size();
        int countX = p.x + PANEL_W - 6 - font.width(count);
        int hdrY = centerY(p.y, PANEL_HDR - 1);
        ctx.text(font, count, countX, hdrY, enabled > 0 ? ColorTheme.accent() : TEXT_DIM, false);
        ctx.text(font, ellipsize(font, menuLabel(p.category), countX - 4 - (p.x + 6)),
                p.x + 6, hdrY, TEXT_ON, false);

        int bodyTop = p.y + PANEL_HDR;
        int bodyBottom = p.y + viewH;
        ctx.enableScissor(p.x, bodyTop, p.x + PANEL_W, bodyBottom);

        int cx = p.x + ROW_INSET, cw = CONTENT_W;
        int cy = bodyTop + 2 - p.scroll;

        for (Module m : modules(p.category)) {
            boolean hovered = insideClipped(mouseX, mouseY, cx, cy, cw, ROW_H, bodyTop, bodyBottom);
            boolean on = m.isEnabled();
            boolean expanded = open.contains(m);
            float hv = hover(m, hovered, dt);
            if (hovered) requestTip(m, m.getDescription());

            animateDrawer(m, dt);
            int drawerH = drawerHeight(m);

            ctx.fill(cx, cy, cx + cw, cy + ROW_H,
                    on ? Anim.mix(ColorTheme.rowOn(), ColorTheme.rowOnHover(), hv) : Anim.mix(ROW_OFF, ROW_HOVER, hv));
            ctx.fill(cx, cy + ROW_H - 1, cx + cw, cy + ROW_H, SEPARATOR);
            float pulse = Anim.approach(pulseAnim.getOrDefault(m, 0f), 0f, 4f, dt);
            pulseAnim.put(m, pulse);
            if (pulse > 0.001f) ctx.fill(cx, cy, cx + cw, cy + ROW_H, Anim.alpha(ColorTheme.accent(), pulse * 0.4f));

            renderModuleRow(ctx, font, m, cx, cy, cw, on, expanded);
            cy += ROW_H;

            if (drawerH > 0) {
                cy = renderSettings(ctx, font, m, cx, cy, cw, mouseX, mouseY, p, viewH, drawerH, dt);
            }
        }

        ctx.disableScissor();

        // Scrollbar in the right gutter, which the rows never reach, so it cannot cover their text.
        int max = maxScroll(p);
        if (max > 0) {
            int trackTop = bodyTop + 2, trackBottom = bodyBottom - 2;
            int trackH = Math.max(1, trackBottom - trackTop);
            int contentH = panelBodyHeight(p);
            int thumbH = Math.max(8, Math.round(trackH * (float) (viewH - PANEL_HDR) / contentH));
            int thumbY = trackTop + Math.round((trackH - thumbH) * (float) p.scroll / max);
            int barX = p.x + PANEL_W - 3;
            ctx.fill(barX, trackTop, barX + 2, trackBottom, WIDGET_BG);
            ctx.fill(barX, thumbY, barX + 2, thumbY + thumbH, ColorTheme.accentDim());
        }

        outline(ctx, p.x, p.y, PANEL_W, viewH, Anim.alpha(BORDER, ps));
    }

    /**
     * The text of one module row: name on the left; on the right, from the edge inwards, the
     * settings arrow, the bound key and the suffix.
     *
     * <p>The name always keeps room for a few characters, the key is dropped if it cannot fit, and
     * the suffix (often a target name, so of any length) gets whatever is left and is cut with "..".
     * Previously a long suffix could push the name's space below zero, at which point the name was
     * drawn uncut straight over the suffix.
     */
    private void renderModuleRow(GuiGraphicsExtractor ctx, Font font, Module m, int cx, int cy, int cw,
                                 boolean on, boolean expanded) {
        int left = cx + PAD_L;
        int right = cx + cw - PAD_R;
        int textY = centerY(cy, ROW_H - 1);
        int meta = on ? TEXT_ON_META : TEXT_DIM;

        // The arrow has a fixed slot on every row, so keys and suffixes line up down the panel.
        int arrowSlot = 7;
        if (!m.getSettings().isEmpty()) {
            String arrow = expanded ? "▾" : "▸";
            int ax = right - arrowSlot + (arrowSlot - font.width(arrow)) / 2;
            ctx.text(font, arrow, ax, textY, on ? TEXT_ON : TEXT_DIM, false);
        }
        right -= arrowSlot + 3;

        String name = m.getName();
        int minName = Math.min(font.width(name), 46);
        int budget = right - left - minName - 4;

        if (m.getKeybind() != Module.UNBOUND) {
            String key = keyLabel(m.getKeybind());
            int kw = font.width(key);
            if (kw + 4 <= budget) {
                int kx = right - kw - 2;
                ctx.fill(kx - 2, cy + 2, kx + kw + 2, cy + ROW_H - 3, on ? 0x33000000 : WIDGET_BG);
                ctx.text(font, key, kx, textY, meta, false);
                right = kx - 2 - 4;
                budget -= kw + 4 + 4;
            }
        }

        String suffix = m.suffix();
        if (suffix != null && !suffix.isEmpty() && budget >= 14) {
            String cut = ellipsize(font, suffix, budget);
            if (!cut.isEmpty()) {
                int sx = right - font.width(cut);
                ctx.text(font, cut, sx, textY, meta, false);
                right = sx - 4;
            }
        }

        ctx.text(font, ellipsize(font, name, right - left), left, textY, on ? TEXT_ON : TEXT_OFF, false);
    }

    /**
     * Draws a module's settings drawer, revealed to {@code drawerH}.
     *
     * <p>The clip is a nested scissor: vanilla's {@code GuiGraphicsExtractor} keeps a
     * {@code ScissorStack}, so the panel's own clip is restored by the matching
     * {@code disableScissor}.
     */
    private int renderSettings(GuiGraphicsExtractor ctx, Font font, Module m, int cx, int drawerTop,
                               int cw, int mouseX, int mouseY, Panel panel, int viewH, int drawerH,
                               float dt) {
        int full = drawerFullHeight(m);
        ctx.enableScissor(cx, drawerTop, cx + cw, drawerTop + drawerH);

        ctx.fill(cx, drawerTop, cx + cw, drawerTop + full, DRAWER_BG);
        ctx.fill(cx, drawerTop, cx + 2, drawerTop + full, ColorTheme.accentDim());

        int top = panel.y + PANEL_HDR;
        int limit = Math.min(panel.y + viewH, drawerTop + drawerH);
        int left = cx + PAD_L;
        int right = cx + cw - PAD_R;
        int cy = drawerTop;

        // Bind row: click to listen for a key, right-click to clear.
        boolean bindHovered = insideClipped(mouseX, mouseY, cx, cy, cw, SET_ROW, top, limit);
        float bindHover = hover(new BindRow(m), bindHovered, dt);
        if (bindHovered) requestTip(new BindRow(m), "Click, then press a key. Right-click clears.");
        if (bindHover > 0.01f) {
            ctx.fill(cx + 2, cy, cx + cw, cy + SET_ROW, Anim.mix(0x00000000, ROW_HOVER, bindHover));
        }
        boolean listening = bindingModule == m;
        int rowY = centerY(cy, SET_ROW);
        String bindLabel = listening ? "press a key..." : keyLabel(m.getKeybind());
        bindLabel = ellipsize(font, bindLabel, right - left - font.width("Bind") - 6);
        ctx.text(font, "Bind", left, rowY, Anim.mix(TEXT_OFF, TEXT_ON, bindHover), false);
        ctx.text(font, bindLabel, right - font.width(bindLabel), rowY,
                listening ? ColorTheme.accent() : Anim.mix(TEXT_DIM, TEXT_OFF, bindHover), false);
        cy += SET_ROW;

        for (Setting<?> s : m.getSettings()) {
            int h = rowHeight(s);
            boolean rowHovered = insideClipped(mouseX, mouseY, cx, cy, cw, h, top, limit);
            float hv = hover(s, rowHovered, dt);
            if (hv > 0.01f) {
                ctx.fill(cx + 2, cy, cx + cw, cy + h, Anim.mix(0x00000000, ROW_HOVER, hv));
            }
            int nameColor = Anim.mix(TEXT_OFF, TEXT_ON, hv);

            if (s instanceof BoolSetting b) {
                int pillW = 16, pillH = 8;
                int pillX = right - pillW;
                int pillY = cy + (SET_ROW - pillH) / 2;
                String name = ellipsize(font, s.getName(), pillX - 4 - left);
                if (rowHovered && !name.equals(s.getName())) requestTip(s, s.getName());
                ctx.text(font, name, left, centerY(cy, SET_ROW), nameColor, false);
                // The knob glides between the two ends instead of teleporting.
                float k = animateValue(s, b.get() ? 1f : 0f, 22f, dt);
                ctx.fill(pillX, pillY, pillX + pillW, pillY + pillH, Anim.mix(WIDGET_BG, ColorTheme.accent(), k));
                int knobX = pillX + 1 + Math.round(k * (pillW - 8));
                ctx.fill(knobX, pillY + 1, knobX + 6, pillY + pillH - 1, Anim.mix(KNOB_OFF, ColorTheme.knobOn(), k));

            } else if (s instanceof FloatSetting f) {
                String value = formatFloat(f);
                int valueX = right - font.width(value);
                String name = ellipsize(font, s.getName(), valueX - 5 - left);
                if (rowHovered && !name.equals(s.getName())) requestTip(s, s.getName());
                ctx.text(font, name, left, cy + 3, nameColor, false);
                ctx.text(font, value, valueX, cy + 3, ColorTheme.accent(), false);

                int barX = left, barW = INNER_W, barY = cy + 15;
                ctx.fill(barX, barY, barX + barW, barY + 2, WIDGET_BG);
                int fill = Math.round(barW * animateValue(s, f.getFraction(), 30f, dt));
                if (fill > 0) ctx.fill(barX, barY, barX + fill, barY + 2, ColorTheme.accent());
                int knobX = Math.max(barX, Math.min(barX + barW - 3, barX + fill - 1));
                ctx.fill(knobX, barY - 2, knobX + 3, barY + 4, TEXT_ON);

            } else if (s instanceof ModeSetting mode) {
                ctx.text(font, ellipsize(font, s.getName(), right - left), left, cy + 3, nameColor, false);

                int btnTop = cy + MODE_LABEL_H;
                for (ModeButton btn : modeButtons(font, mode)) {
                    int bx = left + btn.x(), by = btnTop + btn.y();
                    boolean active = btn.option().equals(mode.get());
                    boolean btnHovered = insideClipped(mouseX, mouseY, bx, by, btn.w(), MODE_BTN_H, top, limit);
                    float bh = hover(new ModeOption(mode, btn.option()), btnHovered, dt);
                    int bg = active ? ColorTheme.accent() : Anim.mix(WIDGET_BG, ROW_HOVER, bh);
                    ctx.fill(bx, by, bx + btn.w(), by + MODE_BTN_H, bg);
                    String label = ellipsize(font, btn.option(), btn.w() - 4);
                    if (btnHovered && !label.equals(btn.option())) requestTip(new ModeOption(mode, btn.option()), btn.option());
                    int tx = bx + (btn.w() - font.width(label)) / 2;
                    int color = active ? ColorTheme.knobOn() : Anim.mix(TEXT_DIM, TEXT_ON, bh);
                    ctx.text(font, label, tx, centerY(by, MODE_BTN_H) + 1, color, false);
                }

            } else if (s instanceof IntSetting i) {
                String value = intLabel(i);
                int valueX = right - font.width(value);
                String name = ellipsize(font, s.getName(), valueX - 5 - left);
                if (rowHovered && !name.equals(s.getName())) requestTip(s, s.getName());
                ctx.text(font, name, left, centerY(cy, SET_ROW), nameColor, false);
                ctx.text(font, value, valueX, centerY(cy, SET_ROW), ColorTheme.accent(), false);
            }

            cy += h;
        }

        ctx.disableScissor();
        return drawerTop + drawerH;
    }

    /** Draws the tooltip requested this frame once the cursor has rested on its element. */
    private void renderTooltip(GuiGraphicsExtractor ctx, Font font, int mouseX, int mouseY, boolean dragging) {
        long now = System.currentTimeMillis();
        if (!Objects.equals(frameTipKey, tipKey)) {
            tipKey = frameTipKey;
            tipSince = now;
        }
        if (dragging || bindingModule != null || frameTipKey == null) return;
        if (now - tipSince < TOOLTIP_DELAY_MS) return;

        List<String> lines = wrap(font, frameTipText, TOOLTIP_W);
        if (lines.isEmpty()) return;
        int w = 0;
        for (String line : lines) w = Math.max(w, font.width(line));
        w += 8;
        int h = lines.size() * 10 + 5;

        // Below-right of the cursor, flipped to the other side wherever it would leave the screen.
        int x = mouseX + 10, y = mouseY + 6;
        if (x + w > width - 2) x = mouseX - w - 6;
        if (y + h > height - 2) y = mouseY - h - 4;
        x = Math.max(2, Math.min(width - w - 2, x));
        y = Math.max(2, Math.min(height - h - 2, y));

        ctx.fill(x, y, x + w, y + h, TOOLTIP_BG);
        outline(ctx, x, y, w, h, BORDER);
        ctx.fill(x, y, x + 1, y + h, ColorTheme.accent());
        for (int i = 0; i < lines.size(); i++) {
            ctx.text(font, lines.get(i), x + 4, y + 3 + i * 10, TEXT_OFF, false);
        }
    }

    // ── input ─────────────────────────────────────────────────────────────────

    private static boolean inside(int mx, int my, int x, int y, int w, int h) {
        return mx >= x && mx < x + w && my >= y && my < y + h;
    }

    /**
     * Hit test against the part of a row that is actually on screen.
     *
     * <p>Rows are drawn through a scissor, so a row scrolled under the panel header or past its
     * bottom edge is still partly visible. Testing the whole row rectangle would let a click on the
     * visible sliver hit a row whose body is clipped away, and rejecting partly visible rows would
     * leave the sliver dead while still being drawn. Clipping the rectangle to the body matches the
     * draw.
     */
    private static boolean insideClipped(int mx, int my, int x, int y, int w, int h,
                                         int clipTop, int clipBottom) {
        int top = Math.max(y, clipTop);
        int bottom = Math.min(y + h, clipBottom);
        return bottom > top && my >= top && my < bottom && mx >= x && mx < x + w;
    }

    /**
     * Which half of an int setting's {@code ◂ value ▸} control the cursor is on: -1 for the left
     * glyph, +1 for the right, 0 for anywhere else on the row. Uses the same layout the renderer
     * draws, instead of splitting the whole row and letting a click on the name change the value.
     */
    private static int intZone(IntSetting setting, int mx, int cx, int cw) {
        Font font = font();
        String value = intLabel(setting);
        int valueX = cx + cw - PAD_R - font.width(value);
        int downW = font.width("◂") + 2;
        int upX = valueX + font.width(value) - font.width("▸") - 2;
        if (mx >= valueX - 2 && mx < valueX + downW) return -1;
        if (mx >= upX && mx < upX + font.width("▸") + 4) return 1;
        return 0;
    }

    @Override
    public boolean mouseClicked(MouseButtonEvent event, boolean doubleClick) {
        int mx = (int) event.x();
        int my = (int) event.y();
        // 26.3 reports mouse buttons with SDL numbering, not the GLFW 0/1 pair:
        // InputConstants.MOUSE_BUTTON_LEFT is 1 and MOUSE_BUTTON_RIGHT is 3.
        boolean left  = event.button() == InputConstants.MOUSE_BUTTON_LEFT;
        boolean right = event.button() == InputConstants.MOUSE_BUTTON_RIGHT;

        // Only the front-most panel under the cursor takes the click, and it comes to the front.
        Panel hit = panelAt(visiblePanels(), mx, my);
        if (hit != null) {
            bringToFront(hit);
            handlePanelClick(hit, mx, my, left, right);
            return true;
        }

        // theme swatch
        if (left && inside(mx, my, menuX + MENU_W - 28, menuY + 2, 12, MENU_HDR_H - 4)) {
            ColorTheme.cycle();
            return true;
        }

        // close button
        if (inside(mx, my, menuX + MENU_W - 14, menuY + 2, 12, MENU_HDR_H - 4)) {
            Veil.mc.gui.setScreen(null);
            return true;
        }

        // menu header drag
        if (inside(mx, my, menuX, menuY, MENU_W, MENU_HDR_H)) {
            draggingMenu = true;
            dragOffX = mx - menuX;
            dragOffY = my - menuY;
            return true;
        }

        // search field: the "x" clears it, right-click anywhere on it clears it too
        int sx = menuX + 4, sy = menuY + MENU_HDR_H + 3, sw = MENU_W - 8;
        if (inside(mx, my, sx, sy, sw, SEARCH_H)) {
            if (!search.isEmpty() && (right || inside(mx, my, sx + sw - 11, sy, 11, SEARCH_H))) search = "";
            return true;
        }

        // category rows
        int cy = menuRowsTop();
        for (Category c : Category.values()) {
            if (inside(mx, my, menuX, cy, MENU_W, ROW_H)) {
                if (left) {
                    if (!shown.remove(c)) {
                        shown.add(c);
                        Panel p = panels.get(c);
                        bringToFront(p);
                        if (panelFullHeight(p) > height - p.y - 2) layoutPanels();
                    }
                }
                return true;
            }
            cy += ROW_H;
        }

        return super.mouseClicked(event, doubleClick);
    }

    private void handlePanelClick(Panel p, int mx, int my, boolean left, boolean right) {
        int viewH = panelViewHeight(p);

        if (inside(mx, my, p.x, p.y, PANEL_W, PANEL_HDR)) {
            draggingPanel = true;
            draggedPanel = p;
            dragOffX = mx - p.x;
            dragOffY = my - p.y;
            return;
        }

        int cx = p.x + ROW_INSET, cw = CONTENT_W;
        int cy = p.y + PANEL_HDR + 2 - scrollOf(p);
        int top = p.y + PANEL_HDR;
        int limit = p.y + viewH;
        Font font = font();

        for (Module m : modules(p.category)) {
            if (insideClipped(mx, my, cx, cy, cw, ROW_H, top, limit)) {
                if (left) {
                    m.toggle();
                    if (m.isEnabled()) pulseAnim.put(m, 1f);
                } else if (right && !m.getSettings().isEmpty()) {
                    if (!open.remove(m)) {
                        open.add(m);
                        // Start the drawer closed so it eases open rather than appearing at full
                        // height; a closing drawer keeps its current value and eases down to 0.
                        drawerAnim.put(m, 0f);
                    }
                }
                return;
            }
            cy += ROW_H;

            int drawerH = drawerHeight(m);
            if (drawerH <= 0) continue;

            // Mirror renderSettings: the drawer is clipped to its animated height, so a row below
            // that edge is not under the cursor even though it will be once the drawer is open.
            int drawerTop = cy;
            int drawerLimit = Math.min(limit, drawerTop + drawerH);

            // Must mirror renderSettings: the bind row comes before the settings.
            if (insideClipped(mx, my, cx, cy, cw, SET_ROW, top, drawerLimit)) {
                if (left) {
                    bindingModule = m;
                } else if (right) {
                    m.setKeybind(Module.UNBOUND);
                    bindingModule = null;
                }
                return;
            }
            cy += SET_ROW;

            for (Setting<?> s : m.getSettings()) {
                int h = rowHeight(s);
                if (insideClipped(mx, my, cx, cy, cw, h, top, drawerLimit)) {
                    if (s instanceof BoolSetting b && left) {
                        b.toggle();
                    } else if (s instanceof ModeSetting mode && left) {
                        // Only the buttons count: the row's first line is the setting name, and a
                        // click on it must not select whichever option happens to sit underneath.
                        int btnTop = cy + MODE_LABEL_H;
                        for (ModeButton btn : modeButtons(font, mode)) {
                            if (inside(mx, my, cx + PAD_L + btn.x(), btnTop + btn.y(), btn.w(), MODE_BTN_H)) {
                                mode.set(btn.option());
                                break;
                            }
                        }
                    } else if (s instanceof FloatSetting f && left) {
                        draggingSlider = f;
                        sliderPanel = p;
                        slideTo(f, mx, p);
                    } else if (s instanceof IntSetting i && (left || right)) {
                        int zone = intZone(i, mx, cx, cw);
                        // Right-click anywhere on the row steps down, so the value can be changed
                        // without aiming at the small arrow glyphs.
                        if (right) zone = zone == 0 ? -1 : zone;
                        if (zone < 0) i.decrement();
                        else if (zone > 0) i.increment();
                    }
                    return;
                }
                cy += h;
            }
            // The next module row starts where the drawer is drawn to end, not where it would end
            // fully open; the two only differ mid-animation, but a click then must still land on
            // the row that is drawn under the cursor.
            cy = drawerTop + drawerH;
        }
    }

    @Override
    public boolean keyPressed(KeyEvent event) {
        // A module waiting for a bind swallows the key, including Escape (which cancels the bind
        // rather than closing the GUI, so a mistaken click does not throw the user out).
        if (bindingModule != null) {
            Module module = bindingModule;
            bindingModule = null;
            bindConsumedAt = System.nanoTime();
            if (event.key() == InputConstants.KEY_ESCAPE) return true;
            module.setKeybind(event.key() == InputConstants.KEY_BACKSPACE
                    ? Module.UNBOUND
                    : event.key());
            return true;
        }

        if (event.key() == InputConstants.KEY_BACKSPACE && !search.isEmpty()) {
            search = search.substring(0, search.length() - 1);
            return true;
        }
        if (event.key() == InputConstants.KEY_ESCAPE) {
            // Escape clears a search first, then closes, so a typo does not cost the whole screen.
            if (!search.isEmpty()) {
                search = "";
                return true;
            }
            Veil.mc.gui.setScreen(null);
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
        if (bindingModule != null) return true;
        if (bindConsumedAt != 0L && System.nanoTime() - bindConsumedAt < 100_000_000L) {
            bindConsumedAt = 0L;
            return true;
        }
        if (search.length() >= 32) return true;
        search += event.codepointAsString();
        return true;
    }

    @Override
    public boolean mouseDragged(MouseButtonEvent event, double dragX, double dragY) {
        int mx = (int) event.x();
        int my = (int) event.y();

        if (draggingSlider != null) {
            // The panel can be hidden (category toggled off, or filtered out by the search) between
            // press and drag; drop the drag rather than keep writing a value the user cannot see.
            if (sliderPanel == null || !visiblePanels().contains(sliderPanel)) {
                draggingSlider = null;
                sliderPanel = null;
            } else {
                slideTo(draggingSlider, mx, sliderPanel);
                return true;
            }
        }

        if (draggingPanel && draggedPanel != null) {
            Panel p = draggedPanel;
            p.x = Math.max(0, Math.min(width - PANEL_W, mx - dragOffX));
            p.y = Math.max(0, Math.min(height - PANEL_HDR - ROW_H, my - dragOffY));
            return true;
        }

        if (draggingMenu) {
            menuX = Math.max(0, Math.min(width - MENU_W, mx - dragOffX));
            menuY = Math.max(0, Math.min(height - MENU_HDR_H, my - dragOffY));
            return true;
        }

        return super.mouseDragged(event, dragX, dragY);
    }

    @Override
    public boolean mouseReleased(MouseButtonEvent event) {
        draggingMenu = false;
        draggingPanel = false;
        draggedPanel = null;
        draggingSlider = null;
        sliderPanel = null;
        return super.mouseReleased(event);
    }

    @Override
    public boolean mouseScrolled(double mouseX, double mouseY, double horizontalAmount, double verticalAmount) {
        Panel p = panelAt(visiblePanels(), (int) mouseX, (int) mouseY);
        if (p != null) {
            int stepped = scrollOf(p) - (int) (verticalAmount * ROW_H);
            p.scroll = Math.max(0, Math.min(maxScroll(p), stepped));
            return true;
        }
        return super.mouseScrolled(mouseX, mouseY, horizontalAmount, verticalAmount);
    }

    private void slideTo(FloatSetting f, int mx, Panel p) {
        int barX = p.x + ROW_INSET + PAD_L;
        int barW = Math.max(1, INNER_W);
        // The bar is what the value follows, not the whole row: dragging past either end pins to the
        // ends instead of overshooting the setting's range.
        float fraction = (float) (mx - barX) / barW;
        f.setFraction(Math.max(0f, Math.min(1f, fraction)));
    }
}
