package com.veil;

import com.veil.config.Config;
import com.veil.event.EventBus;
import com.veil.event.EventHandler;
import com.veil.event.events.KeyPressEvent;
import com.veil.event.events.TickEvent;
import com.veil.gui.ClickGUI;
import com.veil.gui.hud.ArraylistHUD;
import com.veil.gui.hud.TargetHUD;
import com.veil.module.ModuleManager;
import com.mojang.blaze3d.platform.InputConstants;
import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientLifecycleEvents;
import net.minecraft.client.Minecraft;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class Veil implements ClientModInitializer {

    public static final Logger LOGGER = LoggerFactory.getLogger("Veil");
    public static final EventBus EVENTS = new EventBus();

    public static Minecraft mc;

    public static ClickGUI clickGUI;
    public static ArraylistHUD arraylistHUD;
    public static TargetHUD targetHUD;

    /**
     * Right Shift toggles the GUI.
     *
     * <p>26.3 reports keys as SDL scancodes (right shift is 229, escape is 41, A is 4) rather than
     * GLFW key codes, so this and every other key comparison in the client is taken from
     * {@link InputConstants} instead of a literal.
     */
    public static final int GUI_KEY = InputConstants.KEY_RSHIFT;

    public static boolean inGame() {
        return mc != null && mc.player != null && mc.level != null;
    }

    @Override
    public void onInitializeClient() {
        mc = Minecraft.getInstance();

        ModuleManager.init();
        // Restore before anything can act on a module: enabled state and settings come from disk.
        Config.load();
        // And write once more on the way out, so the last change before quitting is not lost.
        ClientLifecycleEvents.CLIENT_STOPPING.register(client -> Config.save());

        clickGUI     = new ClickGUI();
        arraylistHUD = new ArraylistHUD();
        targetHUD    = new TargetHUD();

        EVENTS.subscribe(this);

        LOGGER.info("Veil client initialized.");
    }

    /**
     * Keys are handled from the native callback rather than polled each tick: 26.3's SDL backend
     * delivers key state through events, and polling misses presses shorter than a frame.
     */
    @EventHandler
    private void onKeyPress(KeyPressEvent event) {
        if (!event.isPress()) return;

        if (event.key == GUI_KEY) {
            toggleGUI();
            return;
        }

        // Module binds only fire in the world with nothing open, so typing in a menu or chat
        // cannot toggle a module.
        if (inGame() && mc.gui != null && mc.gui.screen() == null) {
            ModuleManager.onKey(event.key);
        }
    }

    /**
     * Macro Mode is read from the keyboard's own state every tick, not from key events.
     *
     * <p>{@code KeyInputMixin} drops key events while a module holds {@link com.veil.util.InputLock},
     * which an inventory-driving action takes — precisely when a macro key is being pressed. A macro
     * module therefore cannot rely on having seen the press that arms it, so the key is polled here
     * instead. A screen open means the keyboard is not game input any more, so holding a letter in
     * chat only counts as "not holding" and never arms or disarms a module.
     */
    @EventHandler
    private void onTick(TickEvent.Pre event) {
        boolean keysLive = inGame() && mc.gui != null && mc.gui.screen() == null;
        ModuleManager.pollMacroGates(keysLive);
    }

    public static void toggleGUI() {
        if (mc == null || mc.gui == null) return;

        if (mc.gui.screen() instanceof ClickGUI) {
            mc.gui.setScreen(null);
            // Closing the GUI is the natural point to persist anything changed in it.
            Config.save();
            return;
        }
        // Never open over another screen: the world is paused-free but a menu on top of a menu
        // leaves the GUI unable to receive input or to be closed.
        if (mc.gui.screen() == null) {
            mc.gui.setScreen(clickGUI);
        }
    }
}
