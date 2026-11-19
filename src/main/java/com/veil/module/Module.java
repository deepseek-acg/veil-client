package com.veil.module;

import com.veil.Veil;
import com.veil.config.Config;
import com.veil.setting.Setting;
import com.mojang.blaze3d.platform.InputConstants;

import java.util.ArrayList;
import java.util.List;

public abstract class Module {

    /**
     * No key bound.
     *
     * <p>26.3 reports keys as SDL scancodes through {@code InputConstants} (A is 4, Escape is 41,
     * right shift is 229), so a literal such as {@code -1} or {@code 0} is not a key at all. The
     * sentinel comes from vanilla so it stays correct if the numbering ever moves again.
     */
    public static final int UNBOUND = InputConstants.UNKNOWN.getValue();

    private final String name;
    private final String description;
    private final Category category;
    private boolean enabled;
    private int keybind;
    private long enableTime;

    protected final List<Setting<?>> settings = new ArrayList<>();

    protected Module(String name, String description, Category category, int keybind) {
        this.name        = name;
        this.description = description;
        this.category    = category;
        this.keybind     = keybind;
    }

    protected Module(String name, String description, Category category) {
        this(name, description, category, UNBOUND);
    }

    // ── Lifecycle ──────────────────────────────────────────────────────────

    public void onEnable()  {}
    public void onDisable() {}

    /**
     * Called when another module takes {@code InputLock} and this module has to stand down.
     *
     * <p>Modules that keep a state machine across ticks should reset it here rather than try to
     * resume: a freeze of unknown length leaves timers and stage transitions stale, and acting on
     * them the moment the lock is released is how a module does something it did not intend. The
     * default is a no-op for modules with no such state.
     */
    public void onPause() {}

    /** Called when the input lock is released. Default no-op; state was reset in onPause. */
    public void onResume() {}

    // ── State ──────────────────────────────────────────────────────────────

    public boolean isEnabled() { return enabled; }

    public void setEnabled(boolean value) {
        if (this.enabled == value) return;
        this.enabled = value;
        if (value) {
            enableTime = System.currentTimeMillis();
            Veil.EVENTS.subscribe(this);
            onEnable();
        } else {
            Veil.EVENTS.unsubscribe(this);
            onDisable();
        }
        Config.save();
    }

    public void toggle() { setEnabled(!enabled); }

    public long getEnableTime() { return enableTime; }

    // ── Meta ───────────────────────────────────────────────────────────────

    public String    getName()        { return name;        }
    public String    getDescription() { return description; }
    public Category  getCategory()    { return category;    }
    public int       getKeybind()     { return keybind;     }
    public void      setKeybind(int k){ keybind = k; Config.save(); }

    public List<Setting<?>> getSettings() { return settings; }

    /**
     * Whether this module's bind key is down.
     *
     * <p>26.3 keybinds are the SDL scancodes {@code InputConstants} already stores, and
     * {@code isKeyDown} indexes SDL's keyboard state by that same scancode, so the stored value can
     * be polled directly instead of rebuilding a key object every tick. An unbound module has
     * nothing to hold and reports false rather than matching whatever scancode the sentinel is.
     */
    protected final boolean bindHeld() {
        return keybind != UNBOUND && InputConstants.isKeyDown(keybind);
    }

    /** How long the macro key has to stay down before a press is a hold rather than a tap. */
    public static final long MACRO_HOLD_MS = 200L;

    private boolean macroDown;
    private long    macroDownAt;

    /**
     * Advances the macro key state. Called once per tick for every macro-gated module.
     *
     * <p>Holding is the fight control: the module arms once the press has lasted
     * {@link #MACRO_HOLD_MS} and stays armed when the key comes up. Tapping is how the module is put
     * away — the press never gets long enough to arm it, so a disarm tap cannot click on the way out,
     * and a tap on an armed module switches it off.
     *
     * @param down whether the key is down
     * @param live whether a release can be read as the player letting go. It is false while a screen
     *             is open: there the only safe reading is "not holding", and a key that comes up
     *             because chat opened must not disarm the module.
     */
    final void pollMacroKey(boolean down, boolean live, long now) {
        if (!down) {
            boolean wasDown = macroDown;
            macroDown = false;
            if (wasDown && live && now - macroDownAt < MACRO_HOLD_MS) setEnabled(false);
            return;
        }
        if (!macroDown) {
            macroDown = true;
            macroDownAt = now;
        }
        // Armed only once the press is long enough to be a hold, so a tap never gets to act.
        if (live && !enabled && macroHeld()) setEnabled(true);
    }

    /** Whether the macro key is down and has been down long enough to count as a hold. */
    protected final boolean macroHeld() {
        return macroDown && System.currentTimeMillis() - macroDownAt >= MACRO_HOLD_MS;
    }

    /**
     * Whether Macro Mode turns this module's bind key from an on/off switch into a hold key.
     *
     * <p>{@code ModuleManager} asks this on every press and release: a gated module is switched on
     * while its key is down and off when it comes up. That is the only way one key can both start
     * the module and stay held while it fights — as a toggle, the press that began the fight would
     * switch the module off again on its next use.
     */
    public boolean isMacroGated() { return false; }

    /** Text shown after the module name in the arraylist. */
    public String suffix() { return ""; }

    /** Per-module colour for the arraylist, or -1 for the global accent. */
    public int color() { return -1; }

    protected <T extends Setting<?>> T register(T setting) {
        settings.add(setting);
        return setting;
    }
}
