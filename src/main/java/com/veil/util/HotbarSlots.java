// lang: Java, file: HotbarSlots.java, target: Fabric MC 26.3
package com.veil.util;

import com.veil.Veil;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.network.protocol.game.ServerboundSetCarriedItemPacket;

import java.util.LinkedHashSet;
import java.util.Set;

/**
 * The player's selected hotbar slot, shared by every module that has to hold a particular item.
 *
 * <p>More than one module can need the hotbar at the same time — {@code CrystalAura} swaps to
 * obsidian and then to a crystal, {@code AnchorAura} and {@code AutoSafeAnchor} swap to an anchor,
 * glowstone and a detonate slot — and a module that remembers "the slot the player had" in its own
 * field puts back a slot another module had only borrowed.
 *
 * <p>Here the player's own slot is captured once, by the first module that claims the hotbar, and
 * restored once, when the last claim is released. Modules in between just select the slot they need.
 * Claims are keyed by the module instance, so claiming twice, or releasing a claim that is not held,
 * changes nothing — a module can be disabled mid-sequence on any stage without stranding the hotbar.
 *
 * <p>A claim is held across a whole cycle rather than per click: the modules re-select before every
 * click anyway, and keeping the claim alive is what stops an unrelated restore from landing in the
 * middle of somebody else's sequence.
 */
public final class HotbarSlots {

    /** Modules currently holding the hotbar. */
    private static final Set<Object> holders = new LinkedHashSet<>();

    /** The player the current claims were taken for, or null when nobody holds the hotbar. */
    private static LocalPlayer capturedFor;

    /** The slot that player had selected before the first claim, or -1 when nobody holds it. */
    private static int playerSlot = -1;

    private HotbarSlots() {}

    /** Selects {@code slot} for {@code user}, claiming the hotbar for it if nobody has yet. */
    public static void select(Object user, int slot) {
        LocalPlayer self = Veil.mc.player;
        if (self == null || slot < 0 || slot > 8) return;
        // A claim held since another world would restore a slot this player never had, so it is
        // dropped and re-captured rather than trusted.
        if (self != capturedFor) {
            holders.clear();
            capturedFor = self;
            playerSlot = self.getInventory().getSelectedSlot();
        }
        holders.add(user);
        apply(slot);
    }

    /**
     * Releases {@code user}'s claim.
     *
     * @param restore put the player's own slot back; false leaves whatever was selected last, which
     *                is what "Swap Back" off means. Nothing is restored while another module still
     *                holds the hotbar.
     */
    public static void release(Object user, boolean restore) {
        if (!holders.remove(user)) return;
        if (!holders.isEmpty()) return;
        int slot = restore ? playerSlot : -1;
        playerSlot = -1;
        capturedFor = null;
        if (slot >= 0) apply(slot);
    }

    private static void apply(int slot) {
        LocalPlayer self = Veil.mc.player;
        if (self == null || self.getInventory().getSelectedSlot() == slot) return;
        self.getInventory().setSelectedSlot(slot);
        if (Veil.mc.getConnection() != null) {
            Veil.mc.getConnection().send(new ServerboundSetCarriedItemPacket(slot));
        }
    }
}
