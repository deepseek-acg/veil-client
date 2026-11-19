// lang: Java, file: Setting.java, target: Fabric MC 26.3
package com.veil.setting;

public abstract class Setting<T> {

    private final String name;
    protected T value;

    protected Setting(String name, T defaultValue) {
        this.name = name;
        this.value = defaultValue;
    }

    public String getName() { return name; }
    public T get()           { return value; }
    public void set(T v)     { this.value = v; }
}
