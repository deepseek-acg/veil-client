// lang: Java, file: BoolSetting.java, target: Fabric MC 26.3
package com.veil.setting;

public class BoolSetting extends Setting<Boolean> {

    public BoolSetting(String name, boolean defaultValue) {
        super(name, defaultValue);
    }

    public void toggle() { value = !value; }
}
