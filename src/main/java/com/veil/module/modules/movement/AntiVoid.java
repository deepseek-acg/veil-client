// lang: Java, file: AntiVoid.java, target: Fabric MC 26.3
// Auto-places water bucket when falling into void below Y threshold.
package com.veil.module.modules.movement;

import com.veil.Veil;
import com.veil.event.EventHandler;
import com.veil.event.events.TickEvent;
import com.veil.module.Category;
import com.veil.module.Module;
import com.veil.setting.FloatSetting;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.item.Items;

public final class AntiVoid extends Module {

    private final FloatSetting yThreshold = register(new FloatSetting("Y Threshold", 0.0f, -64.0f, 60.0f));
    private final FloatSetting fallSpeed  = register(new FloatSetting("Fall Speed",  2.5f,  0.5f, 10.0f));

    private long lastPlaceTime = 0;
    private int  restoreSlot   = -1;
    private int  restoreDelay  = 0;

    public AntiVoid() {
        super("AntiVoid", "Places water bucket when falling into void", Category.MOVEMENT);
    }

    @EventHandler
    private void onTick(TickEvent.Pre event) {
        if (!Veil.inGame() || Veil.mc.gameMode == null) return;
        LocalPlayer self = Veil.mc.player;

        if (restoreDelay > 0) {
            restoreDelay--;
            if (restoreDelay == 0 && restoreSlot >= 0) {
                self.getInventory().setSelectedSlot(restoreSlot);
                restoreSlot = -1;
            }
            return;
        }

        if (self.getDeltaMovement().y >= -fallSpeed.get()) return;
        if (self.getY() >= yThreshold.get()) return;

        long now = System.currentTimeMillis();
        if (now - lastPlaceTime < 3000) return;

        int bucketSlot = -1;
        for (int i = 0; i < 9; i++) {
            if (self.getInventory().getItem(i).is(Items.WATER_BUCKET)) {
                bucketSlot = i;
                break;
            }
        }
        if (bucketSlot < 0) return;

        restoreSlot = self.getInventory().getSelectedSlot();
        self.getInventory().setSelectedSlot(bucketSlot);
        Veil.mc.gameMode.useItem(self, InteractionHand.MAIN_HAND);
        lastPlaceTime = now;
        restoreDelay = 2;
    }
}
