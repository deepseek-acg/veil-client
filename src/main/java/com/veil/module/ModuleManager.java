// lang: Java, file: ModuleManager.java, target: Fabric MC 26.3
// Holds and initializes all modules. Key-press dispatch lives here too.
package com.veil.module;

import com.veil.module.modules.combat.AnchorAura;
import com.veil.module.modules.combat.AutoSafeAnchor;
import com.veil.module.modules.combat.CrystalAura;

import java.util.*;

public class ModuleManager {

    private static final List<Module> modules = new ArrayList<>();

    public static void init() {
        // Called once from Veil. A second call would register a second copy of every module, and
        // then a keybind press would toggle both copies.
        if (!modules.isEmpty()) return;
        // Veil carries the crystal and anchor stack only; everything else in the client is the
        // support those three need (settings, HUD, GUI), not a module of its own.
        register(new CrystalAura());
        register(new AutoSafeAnchor());
        register(new AnchorAura());
    }

    public static void register(Module module) {
        modules.add(module);
    }

    public static List<Module> getModules() {
        return Collections.unmodifiableList(modules);
    }

    public static List<Module> getByCategory(Category category) {
        return modules.stream()
                      .filter(m -> m.getCategory() == category)
                      .toList();
    }

    /**
     * Toggles modules whose keybind matches a pressed key.
     *
     * <p>{@code keyCode} is an SDL scancode as reported by {@code InputConstants.KEY_*}; only
     * bound modules are considered, so the unbound sentinel can never match a real key.
     */
    /**
     * Tells every enabled module except the lock holder to stand down.
     *
     * <p>Called from {@code InputLock}: the bus already stops delivering events to these modules, and
     * this is the explicit half of the same pause, so a module can drop stale state (a half-finished
     * crystal cycle, a target lock) instead of resuming with it.
     */
    public static void pauseAllExcept(Object holder) {
        for (Module module : modules) {
            if (module != holder && module.isEnabled()) module.onPause();
        }
    }

    /** Counterpart to {@link #pauseAllExcept}; the holder itself is never paused or resumed. */
    public static void resumeAllExcept(Object holder) {
        for (Module module : modules) {
            if (module != holder && module.isEnabled()) module.onResume();
        }
    }

    /**
     * A bound key being pressed.
     *
     * <p>Macro-gated modules are skipped on purpose. Their key is a hold key, and it is read from the
     * keyboard every tick by {@link #pollMacroGates()} rather than taken from this event: key events
     * are dropped while a module holds {@code InputLock}, and pressing the key of a module that is
     * already running must not switch it off. Every other module keeps the toggle it has always had.
     */
    public static void onKey(int keyCode) {
        if (keyCode == Module.UNBOUND) return;

        for (Module module : modules) {
            if (module.getKeybind() != keyCode) continue;
            if (module.isMacroGated()) continue;
            module.toggle();
        }
    }

    /**
     * Feeds every macro-gated module the state of its key, once per tick.
     *
     * <p>Holding arms the module and keeps it fighting; letting go leaves it armed but idle, which is
     * what keeps Silent working across holds — the module's idle ticks walk the server's view back to
     * the camera instead of the module being switched off and its queued rotation dropped. A tap
     * puts it away, and the GUI switch does the same thing.
     *
     * @param keysLive false while a screen is open, when the keyboard cannot be read as game input
     */
    public static void pollMacroGates(boolean keysLive) {
        long now = System.currentTimeMillis();
        for (Module module : modules) {
            if (!module.isMacroGated()) continue;
            module.pollMacroKey(keysLive && module.bindHeld(), keysLive, now);
        }
    }
}
