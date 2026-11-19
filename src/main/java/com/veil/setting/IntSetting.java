// lang: Java, file: IntSetting.java, target: Fabric MC 26.3
package com.veil.setting;

public class IntSetting extends Setting<Integer> {

    private final int min;
    private final int max;

    public IntSetting(String name, int defaultValue, int min, int max) {
        super(name, defaultValue);
        this.min = min;
        this.max = max;
    }

    public int getMin() { return min; }
    public int getMax() { return max; }

    @Override
    public void set(Integer v) {
        this.value = Math.max(min, Math.min(max, v));
    }

    public void increment() { set(value + 1); }
    public void decrement() { set(value - 1); }
}
