// lang: Java, file: AutoEat.java, target: Fabric MC 26.3
// Auto-eats best food when hunger below threshold. 32-tick eat animation wait.
package com.veil.module.modules.utility;

import com.veil.Veil;
import com.veil.event.EventHandler;
import com.veil.event.events.TickEvent;
import com.veil.module.Category;
import com.veil.module.Module;
import com.veil.setting.BoolSetting;
import com.veil.setting.FloatSetting;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.core.component.DataComponents;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.food.FoodProperties;
import net.minecraft.world.item.ItemStack;

public final class AutoEat extends Module {

    private final FloatSetting threshold  = register(new FloatSetting("Threshold", 15.0f, 1.0f, 20.0f));
    private final BoolSetting  returnSlot = register(new BoolSetting("Return Slot", true));

    private int     savedSlot = -1;
    private int     eatTick   = 0;
    private boolean eating    = false;

    public AutoEat() {
        super("AutoEat", "Auto-eats food when hunger drops below threshold", Category.UTILITY);
    }

    @EventHandler
    private void onTick(TickEvent.Pre event) {
        if (!Veil.inGame() || Veil.mc.gameMode == null) return;
        LocalPlayer self = Veil.mc.player;

        if (eating) {
            eatTick++;
            if (eatTick >= 32) {
                eating  = false;
                eatTick = 0;
                if (returnSlot.get() && savedSlot >= 0) {
                    self.getInventory().setSelectedSlot(savedSlot);
                    savedSlot = -1;
                }
            }
            return;
        }

        // Float settings are boxed; unbox before narrowing so the comparison is an int, not a cast
        // of the boxed object.
        if (self.getFoodData().getFoodLevel() >= (int) (float) threshold.get()) return;

        int bestSlot      = -1;
        int bestNutrition = 0;
        for (int i = 0; i < 9; i++) {
            ItemStack stack = self.getInventory().getItem(i);
            // 26.3 moved food data to a component on the stack; Item no longer knows about it.
            FoodProperties food = stack.get(DataComponents.FOOD);
            if (food == null) continue;
            if (food.nutrition() > bestNutrition) {
                bestNutrition = food.nutrition();
                bestSlot = i;
            }
        }
        if (bestSlot < 0) return;

        savedSlot = self.getInventory().getSelectedSlot();
        self.getInventory().setSelectedSlot(bestSlot);
        Veil.mc.gameMode.useItem(self, InteractionHand.MAIN_HAND);
        eating  = true;
        eatTick = 0;
    }
}
