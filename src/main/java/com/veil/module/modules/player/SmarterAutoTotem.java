// lang: Java, file: SmarterAutoTotem.java, target: Fabric MC 26.3
// Port of nyx's smarter-autototem: a totem refill with a randomised reaction delay, adjusted by ping.
package com.veil.module.modules.player;

import com.veil.Veil;
import com.veil.event.EventHandler;
import com.veil.event.events.TickEvent;
import com.veil.module.Category;
import com.veil.module.Module;
import com.veil.setting.BoolSetting;
import com.veil.setting.FloatSetting;
import com.veil.setting.IntSetting;
import com.veil.setting.ModeSetting;
import com.veil.util.InputLock;
import net.minecraft.client.KeyMapping;
import net.minecraft.client.gui.screens.inventory.InventoryScreen;
import net.minecraft.client.multiplayer.ClientPacketListener;
import net.minecraft.client.multiplayer.PlayerInfo;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.world.inventory.ContainerInput;
import net.minecraft.world.inventory.InventoryMenu;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;

import java.util.concurrent.ThreadLocalRandom;

/**
 * SmarterAutoTotem — keeps a totem in the offhand, with a randomised reaction delay rather than a
 * fixed one.
 *
 * <p>The delay is re-rolled for every refill and shortened by part of the reported ping, on the
 * reasoning that a click already reaches the server that much later, so the total is roughly
 * constant whether the connection is fast or slow. Both the delay and the ping share are settings;
 * the compensation is a tuning value, not a measured correction.
 *
 * <p>With {@code Open Inventory} on, the module opens the player's inventory, moves the totem and
 * closes it again, each step after its own short random pause, so the click sequence matches doing
 * it by hand. For as long as that inventory is open the module holds {@link InputLock}: every other
 * module is paused, module keybinds do not fire, and mouse movement, buttons and scroll are dropped,
 * so the pointer cannot drift onto another slot and nothing can be clicked or dragged mid-swap. With
 * {@code Open Inventory} off, the totem is moved with a single swap click and the inventory is
 * closed immediately, because the server treats an inventory click as having opened the menu and
 * expects a close before movement is accepted again.
 *
 * <p>26.3 notes: {@code InvUtils} and {@code PlayerUtils} from nyx do not exist here, so item
 * searches are plain inventory loops, health is {@code getHealth() + getAbsorptionAmount()}, ping is
 * {@code ClientPacketListener#getPlayerInfo} and the offhand swap is
 * {@link ContainerInput#SWAP} with button 40 (vanilla's offhand index) on
 * {@link InventoryMenu#SHIELD_SLOT}.
 */
public final class SmarterAutoTotem extends Module {

    /** Vanilla's swap button for the offhand: 0-8 select a hotbar slot, 40 is the offhand. */
    private static final int OFFHAND_SWAP_BUTTON = 40;

    /** Inventory indices the player owns: hotbar 0-8, main inventory 9-35. */
    private static final int STORAGE_END = 36;
    private static final int MAIN_INVENTORY_START = 9;

    private enum Phase { IDLE, OPENED, CLOSING }

    // ── settings ──────────────────────────────────────────────────────────────
    private final ModeSetting mode = register(new ModeSetting("Mode", "Strict", new String[]{"Strict", "Smart"}));
    private final FloatSetting smartHealth = register(new FloatSetting("Smart Health", 14.0f, 1.0f, 36.0f));
    private final BoolSetting saveHotbar = register(new BoolSetting("Save Hotbar", true));

    private final IntSetting minDelay = register(new IntSetting("Min Delay", 60, 0, 1000));
    private final IntSetting maxDelay = register(new IntSetting("Max Delay", 160, 0, 1000));
    private final IntSetting pingComp = register(new IntSetting("Ping Comp", 50, 0, 100));
    private final FloatSetting instantHealth = register(new FloatSetting("Instant Health", 6.0f, 0.0f, 36.0f));

    private final BoolSetting openInventory = register(new BoolSetting("Open Inventory", false));
    private final IntSetting openMin = register(new IntSetting("Open To Click Min", 30, 0, 500));
    private final IntSetting openMax = register(new IntSetting("Open To Click Max", 90, 0, 500));
    private final IntSetting closeMin = register(new IntSetting("Click To Close Min", 40, 0, 500));
    private final IntSetting closeMax = register(new IntSetting("Click To Close Max", 110, 0, 500));

    // ── state ─────────────────────────────────────────────────────────────────
    private Phase phase = Phase.IDLE;
    private boolean pending;
    private int wait;
    private boolean openedByUs;
    private int lastDelayMs = -1;

    public SmarterAutoTotem() {
        super("SmarterAutoTotem", "Keeps a totem in your offhand with a randomised delay", Category.PLAYER);
    }

    @Override
    public void onEnable() {
        phase = Phase.IDLE;
        pending = false;
        wait = 0;
        openedByUs = false;
        lastDelayMs = -1;
    }

    @Override
    public void onDisable() {
        // Never leave an inventory we opened hanging open, and never keep input held.
        if (openedByUs && Veil.mc.player != null && screenIsInventory()) closeInventory();
        InputLock.release(this);
        openedByUs = false;
        phase = Phase.IDLE;
    }

    // ── tick ──────────────────────────────────────────────────────────────────

    @EventHandler
    private void onTick(TickEvent.Pre event) {
        if (!Veil.inGame() || Veil.mc.gameMode == null) return;
        LocalPlayer self = Veil.mc.player;

        // Inventory open, waiting before closing it.
        if (phase == Phase.CLOSING) {
            if (!screenIsInventory()) { finish(); return; }   // closed by hand
            if (wait > 0) { wait--; return; }
            if (openedByUs) closeInventory();
            finish();
            return;
        }

        // Inventory open, waiting before the click.
        if (phase == Phase.OPENED) {
            if (!screenIsInventory()) { finish(); return; }   // closed by hand: start over later
            if (wait > 0) { wait--; return; }
            moveTotem();
            return;
        }

        // Only with no container open (the player's own inventory is fine) and nothing on the cursor.
        if (self.containerMenu != self.inventoryMenu) { pending = false; return; }
        if (Veil.mc.gui != null && Veil.mc.gui.screen() != null && !screenIsInventory()) return;
        if (!self.containerMenu.getCarried().isEmpty()) return;

        ItemStack offhand = self.getOffhandItem();
        if (offhand.is(Items.TOTEM_OF_UNDYING)) { pending = false; return; }

        float health = totalHealth(self);
        boolean wanted = "Strict".equals(mode.get()) || offhand.isEmpty() || health <= smartHealth.get();
        if (!wanted) { pending = false; return; }

        if (findTotem() < 0) { pending = false; return; }

        boolean urgent = instantHealth.get() > 0f && health <= instantHealth.get();
        if (!pending) {
            pending = true;
            wait = rollDelayTicks();
        }
        if (urgent) wait = 0;
        if (wait > 0) { wait--; return; }
        pending = false;

        if (!openInventory.get() || screenIsInventory()) {
            // Either physical opening is off, or the player already has the inventory open.
            openedByUs = false;
            phase = Phase.OPENED;
            moveTotem();
            return;
        }

        // Open the inventory as if pressing E, then click after a short random pause. The input lock
        // goes on before the screen does, so the first frame of the inventory is already frozen.
        InputLock.acquire(this);
        Veil.mc.gui.setScreen(new InventoryScreen(self));
        openedByUs = true;
        phase = Phase.OPENED;
        wait = urgent ? 0 : rollTicks(openMin.get(), openMax.get(), 0);
        if (wait == 0) moveTotem();
    }

    // ── actions ───────────────────────────────────────────────────────────────

    private void moveTotem() {
        LocalPlayer self = Veil.mc.player;
        int slot = findTotem();
        if (slot >= 0
                && !self.getOffhandItem().is(Items.TOTEM_OF_UNDYING)
                && self.containerMenu.getCarried().isEmpty()) {
            Veil.mc.gameMode.handleContainerInput(
                    self.inventoryMenu.containerId,
                    menuSlot(slot),
                    OFFHAND_SWAP_BUTTON,
                    ContainerInput.SWAP,
                    self);

            // With no inventory on screen nothing would ever close it, and the server keeps treating
            // the player as having a menu open, which blocks movement. Close in the same tick.
            if (Veil.mc.gui == null || Veil.mc.gui.screen() == null) closeInventory();
        }

        if (!openedByUs) { finish(); return; }
        phase = Phase.CLOSING;
        boolean urgent = instantHealth.get() > 0f && totalHealth(self) <= instantHealth.get();
        wait = urgent ? 1 : Math.max(1, rollTicks(closeMin.get(), closeMax.get(), 0));
    }

    /**
     * Closes the inventory we opened and re-asserts the keys the player is still holding: opening a
     * screen releases every key and closing it does not press them again, so without this the player
     * stops dead until they press W again.
     */
    private void closeInventory() {
        if (Veil.mc.player != null) Veil.mc.player.closeContainer();
        KeyMapping.setAll();
    }

    private void finish() {
        InputLock.release(this);
        phase = Phase.IDLE;
        openedByUs = false;
        pending = false;
    }

    // ── helpers ───────────────────────────────────────────────────────────────

    private boolean screenIsInventory() {
        return Veil.mc.gui != null && Veil.mc.gui.screen() instanceof InventoryScreen;
    }

    /** Inventory index of the totem to move, or -1. Main inventory first when saving the hotbar. */
    private int findTotem() {
        LocalPlayer self = Veil.mc.player;
        if (self == null) return -1;

        if (saveHotbar.get()) {
            for (int i = MAIN_INVENTORY_START; i < STORAGE_END; i++) {
                if (self.getInventory().getItem(i).is(Items.TOTEM_OF_UNDYING)) return i;
            }
        }
        for (int i = 0; i < STORAGE_END; i++) {
            if (self.getInventory().getItem(i).is(Items.TOTEM_OF_UNDYING)) return i;
        }
        return -1;
    }

    private static int countTotems(LocalPlayer self) {
        int count = 0;
        for (int i = 0; i < STORAGE_END; i++) {
            ItemStack stack = self.getInventory().getItem(i);
            if (stack.is(Items.TOTEM_OF_UNDYING)) count += stack.getCount();
        }
        ItemStack offhand = self.getOffhandItem();
        if (offhand.is(Items.TOTEM_OF_UNDYING)) count += offhand.getCount();
        return count;
    }

    /** Inventory index to menu slot id: hotbar indices 0-8 are the last row of the menu. */
    private static int menuSlot(int inventoryIndex) {
        if (inventoryIndex < 9) return InventoryMenu.USE_ROW_SLOT_START + inventoryIndex;
        return inventoryIndex;
    }

    private static float totalHealth(LocalPlayer self) {
        return self.getHealth() + self.getAbsorptionAmount();
    }

    /** Ping in ms as the server reports it; 0 in singleplayer or when the player is unknown. */
    private static int ping() {
        LocalPlayer self = Veil.mc.player;
        ClientPacketListener connection = Veil.mc.getConnection();
        if (self == null || connection == null) return 0;
        PlayerInfo info = connection.getPlayerInfo(self.getUUID());
        return info == null ? 0 : info.getLatency();
    }

    /** A fresh random delay for this refill, shortened by (part of) the ping, in ticks. */
    private int rollDelayTicks() {
        return rollTicks(minDelay.get(), maxDelay.get(), pingComp.get());
    }

    /**
     * Random time between {@code a} and {@code b} ms, minus {@code pingPercent} of the ping, as ticks.
     *
     * <p>The remainder is rounded randomly rather than truncated: a 75 ms delay is one tick or two,
     * averaging 1.5, so the tick grid does not collapse every roll onto the same value.
     */
    private int rollTicks(int a, int b, int pingPercent) {
        int lo = Math.min(a, b);
        int hi = Math.max(a, b);
        double ms = lo + ThreadLocalRandom.current().nextDouble() * (hi - lo);
        if (pingPercent > 0) {
            ms -= ping() * pingPercent / 100.0;
            if (ms < 0) ms = 0;
        }
        lastDelayMs = (int) Math.round(ms);

        double ticks = ms / 50.0;
        int whole = (int) Math.floor(ticks);
        if (ThreadLocalRandom.current().nextDouble() < ticks - whole) whole++;
        return whole;
    }

    @Override
    public String suffix() {
        LocalPlayer self = Veil.mc.player;
        if (self == null) return null;
        return lastDelayMs >= 0
                ? countTotems(self) + " (" + lastDelayMs + "ms)"
                : String.valueOf(countTotems(self));
    }
}
