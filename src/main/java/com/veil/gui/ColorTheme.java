// lang: Java, file: ColorTheme.java, target: Fabric MC 26.3
package com.veil.gui;

public final class ColorTheme {

    public enum Theme {
        TEAL, ROSE, VIOLET, GOLD, FROST
    }

    private static Theme current = Theme.VIOLET;

    public static Theme get() { return current; }
    public static void set(Theme t) { current = t; }
    public static void cycle() {
        Theme[] values = Theme.values();
        current = values[(current.ordinal() + 1) % values.length];
    }

    // Primary accent color (ARGB)
    public static int accent() {
        return switch (current) {
            case TEAL   -> 0xFF2BD9C0;
            case ROSE   -> 0xFFE05080;
            case VIOLET -> 0xFF9B6CF9;
            case GOLD   -> 0xFFD4A017;
            case FROST  -> 0xFF60B8E0;
        };
    }

    // Dimmed accent (darker shade)
    public static int accentDim() {
        return switch (current) {
            case TEAL   -> 0xFF178A79;
            case ROSE   -> 0xFF8A2040;
            case VIOLET -> 0xFF5C3BA0;
            case GOLD   -> 0xFF8A6A0A;
            case FROST  -> 0xFF3080A8;
        };
    }

    // Module row on-state background
    public static int rowOn() {
        return switch (current) {
            case TEAL   -> 0xFF1FC1AB;
            case ROSE   -> 0xFFB83060;
            case VIOLET -> 0xFF7050C8;
            case GOLD   -> 0xFFAA8010;
            case FROST  -> 0xFF4090B8;
        };
    }

    // Module row on+hover
    public static int rowOnHover() {
        return switch (current) {
            case TEAL   -> 0xFF27D8BF;
            case ROSE   -> 0xFFD04070;
            case VIOLET -> 0xFF8060D8;
            case GOLD   -> 0xFFBB9018;
            case FROST  -> 0xFF50A0CC;
        };
    }

    // Knob on-state (dark fill inside toggled pill)
    public static int knobOn() {
        return switch (current) {
            case TEAL   -> 0xFF07201C;
            case ROSE   -> 0xFF280810;
            case VIOLET -> 0xFF1A0C30;
            case GOLD   -> 0xFF201408;
            case FROST  -> 0xFF0C1C28;
        };
    }

    // Neutral constants (same across all themes)
    public static final int DIM        = 0x60000000;
    public static final int MENU_BG    = 0xFF0E0E11;
    public static final int MENU_HDR   = 0xFF08080A;
    public static final int PANEL_BG   = 0xFF151519;
    public static final int PANEL_TOP  = 0xFF0B0B0E;
    public static final int ROW_OFF    = 0xFF1A1A20;
    public static final int ROW_HOVER  = 0xFF24242E;
    public static final int TEXT_ON    = 0xFFFFFFFF;
    public static final int TEXT_OFF   = 0xFFB4B4BE;
    public static final int TEXT_DIM   = 0xFF70707C;
    public static final int SEPARATOR  = 0xFF0C0C0F;
    public static final int DRAWER_BG  = 0xFF0F0F13;
    public static final int WIDGET_BG  = 0xFF262630;
    public static final int KNOB_OFF   = 0xFF8A8A99;
    /** 1px frame around the menu, the panels and tooltips, so they stay distinct where they overlap. */
    public static final int BORDER     = 0xFF26262F;
    /** Soft drop shadow under floating boxes. */
    public static final int SHADOW     = 0x50000000;
    public static final int TOOLTIP_BG = 0xF00F0F13;

    /** Display name of the current theme, e.g. "Teal". */
    public static String name() {
        String raw = current.name();
        return raw.charAt(0) + raw.substring(1).toLowerCase(java.util.Locale.ROOT);
    }

    private ColorTheme() {}
}
