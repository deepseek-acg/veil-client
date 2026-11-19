// lang: Java, file: Scaffold.java, target: Fabric MC 26.3
// Places blocks below the player's feet while they are moving in midair.
package com.veil.module.modules.movement;

import com.veil.Veil;
import com.veil.event.EventHandler;
import com.veil.event.events.TickEvent;
import com.veil.module.Category;
import com.veil.module.Module;
import com.veil.setting.BoolSetting;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.item.BlockItem;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.Vec3;

/**
 * Scaffold — places a block below the player's feet each tick they are midair
 * while moving. Auto-switches to the first block item in hotbar.
 *
 * <p>Placement targets the UP face of the block one below the player's feet.
 * A temporary sneak state suppresses the downward-look requirement vanilla imposes.
 */
public final class Scaffold extends Module {

    private final BoolSetting autoSwitch = register(new BoolSetting("Auto Switch", true));
    private final BoolSetting tower      = register(new BoolSetting("Tower",       false));

    public Scaffold() {
        super("Scaffold", "Places blocks under your feet while moving", Category.MOVEMENT);
    }

    @EventHandler
    private void onTick(TickEvent.Pre event) {
        if (!Veil.inGame() || Veil.mc.gameMode == null) return;
        LocalPlayer self = Veil.mc.player;

        if (self.onGround() && !tower.get()) return;

        // Block directly at player feet must be air (the gap we need to fill)
        BlockPos below = BlockPos.containing(self.getX(), self.getY() - 0.1, self.getZ());
        if (!Veil.mc.level.getBlockState(below).isAir()) return;

        // The block one further below — this is what we place ON (must not be air)
        BlockPos target = below.below();
        if (Veil.mc.level.getBlockState(target).isAir()) return;

        // Auto-switch to a block item if the held item is not one
        if (!(self.getInventory().getSelectedItem().getItem() instanceof BlockItem)) {
            if (!autoSwitch.get()) return;
            boolean found = false;
            for (int i = 0; i < 9; i++) {
                if (self.getInventory().getItem(i).getItem() instanceof BlockItem) {
                    self.getInventory().setSelectedSlot(i);
                    found = true;
                    break;
                }
            }
            if (!found) return;
        }

        Vec3 hitVec = Vec3.atCenterOf(target).add(0, 0.5, 0);
        BlockHitResult hit = new BlockHitResult(hitVec, Direction.UP, target, false);

        boolean wasSneaking = self.isShiftKeyDown();
        if (!wasSneaking) self.setShiftKeyDown(true);
        Veil.mc.gameMode.useItemOn(self, InteractionHand.MAIN_HAND, hit);
        if (!wasSneaking) self.setShiftKeyDown(false);
    }
}
