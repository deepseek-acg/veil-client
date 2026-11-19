// lang: Java, file: AutoBed.java, target: Fabric MC 26.3
// Breaks the nearest bed, waiting a jittered delay between attempts.
// Silent rotation: queued in SilentRotationManager and injected by SilentRotationMixin.
// Falls back to visual rotation if mixin unavailable.
package com.veil.module.modules.combat;

import com.veil.Veil;
import com.veil.event.EventHandler;
import com.veil.event.events.TickEvent;
import com.veil.module.Category;
import com.veil.module.Module;
import com.veil.setting.BoolSetting;
import com.veil.setting.FloatSetting;
import com.veil.util.SilentRotationManager;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.network.protocol.game.ServerboundPlayerActionPacket;
import net.minecraft.world.level.block.BedBlock;
import net.minecraft.world.phys.Vec3;

import java.util.concurrent.ThreadLocalRandom;

public final class AutoBed extends Module {

    private final FloatSetting range     = register(new FloatSetting("Range",    4.5f, 2.0f, 6.0f));
    private final FloatSetting delay     = register(new FloatSetting("Delay",   250.0f, 100.0f, 800.0f));
    private final FloatSetting jitter    = register(new FloatSetting("Jitter",   0.25f, 0.0f, 0.5f));
    private final BoolSetting  visualRot = register(new BoolSetting("Visual Rot", false));

    // "Visual Rot" turns the camera; otherwise the aim goes out through SilentRotationManager, the
    // same queue every other silent-aiming module uses.

    private BlockPos targetBed = null;
    private long nextSwing = 0;

    public AutoBed() {
        super("AutoBed", "Breaks enemy beds automatically", Category.COMBAT);
    }

    @Override
    public void onDisable() {
        targetBed = null;
    }

    /** Another module took the input lock: drop the bed and the pending swing timer. */
    @Override
    public void onPause() {
        targetBed = null;
        nextSwing = 0L;
    }

    public static BlockPos getTarget() {
        // Bed pos is a BlockPos, not a LivingEntity; TargetHUD calls this for display
        return null;
    }

    @EventHandler
    private void onTick(TickEvent.Pre event) {
        if (!Veil.inGame() || Veil.mc.gameMode == null) return;
        LocalPlayer self = Veil.mc.player;

        targetBed = findBed(self);
        if (targetBed == null) return;

        long now = System.currentTimeMillis();
        if (now < nextSwing) return;

        Vec3 target = Vec3.atCenterOf(targetBed);
        Vec3 eyes   = self.getEyePosition();
        double dx   = target.x - eyes.x;
        double dy   = target.y - eyes.y;
        double dz   = target.z - eyes.z;
        double dist = Math.sqrt(dx * dx + dz * dz);
        float yaw   = (float)(Math.toDegrees(Math.atan2(-dx, dz)));
        float pitch = (float)(Math.toDegrees(-Math.atan2(dy, dist)));

        // FOV check: dot product of forward vec and target vec must be > 0 (within 90°)
        Vec3 forward  = self.getLookAngle();
        Vec3 toTarget = target.subtract(eyes).normalize();
        if (forward.dot(toTarget) <= 0) return;

        if (!visualRot.get()) {
            SilentRotationManager.setPending(yaw, pitch);
            // Send the rotation before the destroy packets instead of relying on a movement packet
            // having gone out earlier in this tick.
            SilentRotationManager.flush();
        } else {
            self.setYRot(yaw);
            self.setXRot(pitch);
        }

        Direction face = nearestFace(eyes, targetBed);
        if (Veil.mc.getConnection() != null) {
            Veil.mc.getConnection().send(new ServerboundPlayerActionPacket(
                    ServerboundPlayerActionPacket.Action.START_DESTROY_BLOCK,
                    targetBed, face));
            Veil.mc.getConnection().send(new ServerboundPlayerActionPacket(
                    ServerboundPlayerActionPacket.Action.STOP_DESTROY_BLOCK,
                    targetBed, face));
        }

        // Settings are boxed Floats: unbox to float, then widen.
        nextSwing = now + gaussianDelay((long) (float) delay.get(), jitter.get());

        if (Veil.mc.level.getBlockState(targetBed).isAir()) {
            targetBed = null;
        }
    }

    private BlockPos findBed(LocalPlayer self) {
        BlockPos origin = self.blockPosition();
        int r = 5;
        BlockPos best = null;
        double bestDist = Double.MAX_VALUE;

        for (int dx = -r; dx <= r; dx++) {
            for (int dy = -3; dy <= 3; dy++) {
                for (int dz = -r; dz <= r; dz++) {
                    BlockPos pos = origin.offset(dx, dy, dz);
                    if (!(Veil.mc.level.getBlockState(pos).getBlock() instanceof BedBlock)) continue;
                    double d = self.position().distanceTo(Vec3.atCenterOf(pos));
                    if (d > range.get()) continue;
                    if (d < bestDist) { bestDist = d; best = pos; }
                }
            }
        }
        return best;
    }

    private static Direction nearestFace(Vec3 eyes, BlockPos pos) {
        Vec3 center = Vec3.atCenterOf(pos);
        double dy = eyes.y - center.y;
        if (dy > 0.3) return Direction.UP;
        if (dy < -0.3) return Direction.DOWN;
        double dx = eyes.x - center.x;
        double dz = eyes.z - center.z;
        if (Math.abs(dx) > Math.abs(dz)) return dx > 0 ? Direction.EAST : Direction.WEST;
        return dz > 0 ? Direction.SOUTH : Direction.NORTH;
    }

    private static long gaussianDelay(long mean, float jit) {
        double g = mean + ThreadLocalRandom.current().nextGaussian() * (mean * jit);
        return (long) Math.max(mean / 2.0, Math.min(mean * 2.0, g));
    }

}
