// lang: Java, file: ModeSetting.java, target: Fabric MC 26.3
// Dropdown/radio setting. Holds a String[] of options and tracks the current selection index.
package com.veil.setting;

public class ModeSetting extends Setting<String> {

    private final String[] options;
    private int index;

    public ModeSetting(String name, String defaultOption, String[] options) {
        super(name, defaultOption);
        this.options = options;
        int idx = indexOf(defaultOption);
        this.index = idx < 0 ? 0 : idx;
        this.value = options[this.index];
    }

    public String[] getOptions() { return options; }

    public int getIndex() { return index; }

    public void setIndex(int i) {
        this.index = Math.max(0, Math.min(options.length - 1, i));
        this.value = options[this.index];
    }

    public void cycle() {
        setIndex((index + 1) % options.length);
    }

    @Override
    public void set(String v) {
        int idx = indexOf(v);
        if (idx < 0) return;
        this.index = idx;
        this.value = options[idx];
    }

    /**
     * Index of {@code option}, or -1 when the mode has no such option.
     *
     * <p>Not 0: the config loader relies on an unrecognised value (a mode renamed since the file was
     * written, or a hand-edited entry) being rejected, and answering 0 turned that rejection into a
     * silent reset to the first option of every mode it touched.
     */
    private int indexOf(String option) {
        for (int i = 0; i < options.length; i++) {
            if (options[i].equalsIgnoreCase(option)) return i;
        }
        return -1;
    }
}
