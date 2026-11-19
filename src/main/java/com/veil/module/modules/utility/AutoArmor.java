// lang: Java, file: AutoArmor.java, target: Fabric MC 26.3
// Auto-equips best armor from inventory using QUICK_MOVE container clicks.
// Rate-limited to once per second to avoid packet spam.
package com.veil.module.modules.utility;

import com.veil.Veil;
import com.veil.event.EventHandler;
import com.veil.event.events.TickEvent;
import com.veil.module.Category;
import com.veil.module.Module;
import com.veil.setting.BoolSetting;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.core.component.DataComponents;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.entity.ai.attributes.Attributes;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.inventory.ContainerInput;
import net.minecraft.world.inventory.InventoryMenu;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.component.ItemAttributeModifiers;
import net.minecraft.world.item.equipment.Equippable;

/**
 * AutoArmor — moves the strongest armor of each slot from the inventory into the armor slots.
 *
 * <p>26.3 removed {@code ArmorItem} and {@code ClickType}: armor is now data. An item is armor when
 * it carries an {@link Equippable} component, its slot comes from {@code Equippable#slot()}, and its
 * protection is the armor attribute its {@code ATTRIBUTE_MODIFIERS} contribute to that slot — not a
 * hardcoded defense number. Container clicks use {@link ContainerInput#QUICK_MOVE}, which is what
 * shift-clicking a slot sends.
 *
 * <p>Inventory indices and menu slot ids are different spaces: hotbar indices 0–8 are menu slots
 * 36–44, and inventory indices 9–35 are menu slots 9–35, which is why the slot is translated before
 * the click.
 */
public final class AutoArmor extends Module {

    private static final EquipmentSlot[] ARMOR_SLOTS = {
            EquipmentSlot.HEAD, EquipmentSlot.CHEST, EquipmentSlot.LEGS, EquipmentSlot.FEET
    };

    /** Hotbar plus main inventory: inventory indices 0-35, which is also where the menu's 36 begins. */
    private static final int STORAGE_SLOTS = InventoryMenu.INV_SLOT_END;

    private final BoolSetting helmet     = register(new BoolSetting("Helmet",     true));
    private final BoolSetting chestplate = register(new BoolSetting("Chestplate", true));
    private final BoolSetting leggings   = register(new BoolSetting("Leggings",   true));
    private final BoolSetting boots      = register(new BoolSetting("Boots",      true));

    private int tickTimer;

    public AutoArmor() {
        super("AutoArmor", "Auto-equips best armor from inventory", Category.UTILITY);
    }

    @Override
    public void onEnable() {
        tickTimer = 0;
    }

    private BoolSetting settingFor(EquipmentSlot slot) {
        return switch (slot) {
            case HEAD  -> helmet;
            case CHEST -> chestplate;
            case LEGS  -> leggings;
            case FEET  -> boots;
            default    -> null;
        };
    }

    @EventHandler
    private void onTick(TickEvent.Pre event) {
        if (!Veil.inGame() || Veil.mc.gameMode == null) return;
        if (++tickTimer < 20) return;
        tickTimer = 0;

        LocalPlayer self = Veil.mc.player;
        Inventory inv = self.getInventory();

        for (EquipmentSlot slot : ARMOR_SLOTS) {
            BoolSetting enabled = settingFor(slot);
            if (enabled == null || !enabled.get()) continue;

            int equippedIndex = armorIndex(slot);
            if (equippedIndex < 0) continue;

            ItemStack current = inv.getItem(equippedIndex);
            double bestValue = armorValue(current, slot);
            int bestIndex = -1;

            // Hotbar and main inventory only; the armor slots (36+) are the destination.
            for (int i = 0; i < STORAGE_SLOTS; i++) {
                ItemStack candidate = inv.getItem(i);
                Equippable equippable = candidate.get(DataComponents.EQUIPPABLE);
                if (equippable == null || equippable.slot() != slot) continue;

                double value = armorValue(candidate, slot);
                if (value > bestValue) {
                    bestValue = value;
                    bestIndex = i;
                }
            }

            if (bestIndex < 0) continue;

            Veil.mc.gameMode.handleContainerInput(
                    self.containerMenu.containerId,
                    containerSlot(bestIndex),
                    0,
                    ContainerInput.QUICK_MOVE,
                    self);
            return;
        }
    }

    /**
     * Protection an item gives in a slot, read from its attribute modifiers. Empty stacks and
     * non-armor items contribute nothing.
     */
    private static double armorValue(ItemStack stack, EquipmentSlot slot) {
        if (stack.isEmpty()) return 0.0;
        ItemAttributeModifiers modifiers =
                stack.getOrDefault(DataComponents.ATTRIBUTE_MODIFIERS, ItemAttributeModifiers.EMPTY);
        return modifiers.compute(Attributes.ARMOR, 0.0, slot);
    }

    /** Inventory index of the armor slot worn on {@code slot}, from vanilla's own mapping. */
    private static int armorIndex(EquipmentSlot slot) {
        for (var entry : Inventory.EQUIPMENT_SLOT_MAPPING.int2ObjectEntrySet()) {
            if (entry.getValue() == slot) return entry.getIntKey();
        }
        return -1;
    }

    /** Inventory index to player-menu slot id: hotbar is the last row of the menu. */
    private static int containerSlot(int inventoryIndex) {
        if (inventoryIndex < Inventory.SELECTION_SIZE) {
            return InventoryMenu.USE_ROW_SLOT_START + inventoryIndex;
        }
        return inventoryIndex;
    }
}
