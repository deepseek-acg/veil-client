// lang: Java, file: FloatSetting.java, target: Fabric MC 26.3
package com.veil.setting;

public class FloatSetting extends Setting<Float> {

    private final float min;
    private final float max;

    public FloatSetting(String name, float defaultValue, float min, float max) {
        super(name, defaultValue);
        this.min = min;
        this.max = max;
    }

    public float getMin() { return min; }
    public float getMax() { return max; }

    @Override
    public void set(Float v) {
        // NaN and the infinities pass through Math.min/max untouched, so a single bad value (a
        // hand-edited config entry, a slider dragged on a zero-width panel) would stick to this
        // setting and to everything that reads it. Keep the last good value instead.
        if (v == null || !Float.isFinite(v)) return;
        this.value = Math.max(min, Math.min(max, v));
    }

    /** Returns 0.0–1.0 fraction of current value across [min, max]. */
    public float getFraction() {
        if (max == min) return 0f;
        return (value - min) / (max - min);
    }

    /** Set value from a 0.0–1.0 fraction. */
    public void setFraction(float fraction) {
        set(min + fraction * (max - min));
    }
}
