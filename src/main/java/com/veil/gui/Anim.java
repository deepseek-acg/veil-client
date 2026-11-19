// lang: Java, file: Anim.java, target: Fabric MC 26.3
package com.veil.gui;

/**
 * Time-based easing shared by the click GUI and the HUD.
 *
 * <p>Everything here is driven by wall-clock time rather than by a frame counter. The GUI is drawn
 * only while its screen is open and the HUD is drawn every frame, so the two see completely
 * different frame rates; a counter-based animation would run at a different speed in each and
 * would stall entirely while the screen is closed.
 *
 * <p>{@link #approach} is the workhorse for values that follow a target over time. It steps a
 * fraction of the remaining distance that depends on the elapsed time, so a single slow frame
 * catches up instead of leaving the value permanently behind.
 */
public final class Anim {

    private Anim() {}

    public static float clamp01(float value) {
        return value < 0f ? 0f : (value > 1f ? 1f : value);
    }

    /** Fraction of a {@code durationMs} window that has passed since {@code startNanos}, 0..1. */
    public static float progress(long startNanos, long durationMs, long nowNanos) {
        if (startNanos <= 0L || durationMs <= 0L) return 1f;
        return clamp01((nowNanos - startNanos) / (durationMs * 1_000_000f));
    }

    /** Decelerating ease: fast at the start, settling softly. Used for anything that appears. */
    public static float easeOutCubic(float t) {
        float u = 1f - clamp01(t);
        return 1f - u * u * u;
    }

    /**
     * Moves {@code current} toward {@code target}.
     *
     * <p>{@code rate} is roughly "how many times per second the remaining gap is closed", so 20 is
     * a quick settle and 6 is a slow drift. The step is exponential in {@code dt}, which keeps the
     * animation speed independent of the frame rate.
     */
    public static float approach(float current, float target, float rate, float dt) {
        if (current == target) return target;
        float step = 1f - (float) Math.exp(-rate * Math.max(0f, Math.min(0.25f, dt)));
        float next = current + (target - current) * step;
        // Snap once the remainder is invisible, so values settle exactly on their target instead of
        // leaving a residue that keeps every later frame slightly dirty.
        return Math.abs(target - next) < 0.001f ? target : next;
    }

    /** Straight-line interpolation between two ARGB colours, including alpha. */
    public static int mix(int from, int to, float t) {
        float k = clamp01(t);
        int a = channel(from, 24, to, k);
        int r = channel(from, 16, to, k);
        int g = channel(from, 8, to, k);
        int b = channel(from, 0, to, k);
        return (a << 24) | (r << 16) | (g << 8) | b;
    }

    private static int channel(int from, int shift, int to, float t) {
        int one = (from >> shift) & 0xFF;
        int two = (to >> shift) & 0xFF;
        return Math.round(one + (two - one) * t) & 0xFF;
    }

    /** Scales a colour's existing alpha by {@code factor}, leaving its RGB alone. */
    public static int alpha(int color, float factor) {
        int a = Math.round(((color >>> 24) & 0xFF) * clamp01(factor));
        return (a << 24) | (color & 0x00FFFFFF);
    }
}
