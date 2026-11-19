// lang: Java, file: Freecam.java, target: Fabric MC 26.3
// Port of nyx Freecam (com.nyx.modules.Freecam), minus its Silent Mode presets and freeze-packets
// option, which belong to nyx's rotation/anti-cheat layer that mist does not have.
package com.veil.module.modules.render;

import com.veil.Veil;
import com.veil.event.EventHandler;
import com.veil.event.events.TickEvent;
import com.veil.module.Category;
import com.veil.module.Module;
import com.veil.setting.BoolSetting;
import com.veil.setting.FloatSetting;
import com.veil.setting.IntSetting;
import net.minecraft.util.Mth;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.level.chunk.status.ChunkStatus;
import net.minecraft.world.phys.Vec3;

/**
 * Freecam: detaches the camera from your player so you can fly it around and look through the world
 * while your body stays where it is. Everything is client-side — the server only ever sees your real
 * player standing still.
 *
 * <p>Four pieces make it work, and all four are needed: {@code FreecamCameraMixin} puts the camera
 * where this module says and marks it detached, {@code FreecamEntityMixin} sends the mouse to the
 * camera instead of the player and keeps entities near your body drawn, {@code FreecamInputMixin}
 * clears the movement keys so WASD cannot walk the body, and this module pins the body's position
 * every tick so knockback, pistons and slow falling cannot carry it around either.
 */
public final class Freecam extends Module {

    private static Freecam instance;

    private final FloatSetting speed           = register(new FloatSetting("Speed", 1.0f, 0.05f, 10f));
    private final BoolSetting  freeze          = register(new BoolSetting("Freeze Player", true));
    private final BoolSetting  toggleOnDamage  = register(new BoolSetting("Disable On Damage", true));
    private final BoolSetting  keepEntities    = register(new BoolSetting("Keep Entities Visible", true));
    private final BoolSetting  spectate        = register(new BoolSetting("Spectate Mode", false));
    private final FloatSetting verticalSpeed   = register(new FloatSetting("Vertical Speed", 0.8f, 0.05f, 8f));
    private final BoolSetting  unlimited       = register(new BoolSetting("No Distance Limit", false));
    private final IntSetting   maxDistance     = register(new IntSetting("Max Distance", 128, 16, 512));

    private Vec3 pos = Vec3.ZERO;
    private Vec3 prevPos = Vec3.ZERO;
    private Vec3 anchor = Vec3.ZERO;
    private Vec3 frozenPos;
    private float yaw, pitch;
    private float lastHealth;
    private boolean savedSmartCull = true;

    public Freecam() {
        super("Freecam", "Fly the camera away from your player and look around freely.", Category.RENDER);
        instance = this;
    }

    /** The active Freecam module, or null when it is off. Called from mixins on the render thread. */
    public static Freecam active() {
        Freecam freecam = instance;
        return freecam != null && freecam.isEnabled() && Veil.inGame() ? freecam : null;
    }

    @Override
    public void onEnable() {
        if (!Veil.inGame()) {
            setEnabled(false);
            return;
        }
        pos = Veil.mc.gameRenderer.mainCamera().position();
        prevPos = pos;
        anchor = Veil.mc.player.position();
        frozenPos = anchor;
        yaw = Veil.mc.player.getYRot();
        pitch = Veil.mc.player.getXRot();
        lastHealth = Veil.mc.player.getHealth();
        // The game culls chunks it thinks you cannot see from where the camera is. With a free camera
        // (inside walls, underground, far from your body) that guess goes wrong and blocks vanish or
        // flicker, so draw them all while this is on.
        savedSmartCull = Veil.mc.smartCull;
        Veil.mc.smartCull = false;
    }

    @Override
    public void onDisable() {
        frozenPos = null;
        if (Veil.mc != null && !Veil.mc.smartCull && savedSmartCull) Veil.mc.smartCull = true;
    }

    @EventHandler
    private void onTick(TickEvent.Pre event) {
        if (!Veil.inGame()) {
            setEnabled(false);
            return;
        }
        if (Veil.mc.player.getHealth() <= 0f) {
            setEnabled(false);
            return;
        }
        if (toggleOnDamage.get() && Veil.mc.player.getHealth() < lastHealth) {
            lastHealth = Veil.mc.player.getHealth();
            setEnabled(false);
            return;
        }
        lastHealth = Veil.mc.player.getHealth();

        // Pin the body: suppressing the keys stops self-driven movement, but not knockback.
        if (freeze.get() && frozenPos != null) {
            Veil.mc.player.setPos(frozenPos.x, frozenPos.y, frozenPos.z);
            Veil.mc.player.setDeltaMovement(Vec3.ZERO);
        }

        prevPos = pos;

        // A server teleport or setback is real movement: in spectate mode the camera follows it rather
        // than being left behind outside the newly loaded area.
        Vec3 actual = Veil.mc.player.position();
        if (spectate.get() && actual.distanceToSqr(anchor) > 16) {
            Vec3 shift = actual.subtract(anchor);
            pos = pos.add(shift);
            prevPos = prevPos.add(shift);
        }
        anchor = actual;

        if (Veil.mc.gui != null && Veil.mc.gui.screen() != null) return;

        double forward = (Veil.mc.options.keyUp.isDown() ? 1 : 0) - (Veil.mc.options.keyDown.isDown() ? 1 : 0);
        double strafe = (Veil.mc.options.keyLeft.isDown() ? 1 : 0) - (Veil.mc.options.keyRight.isDown() ? 1 : 0);
        double vertical = (Veil.mc.options.keyJump.isDown() ? 1 : 0) - (Veil.mc.options.keyShift.isDown() ? 1 : 0);
        double step = speed.get() * (Veil.mc.options.keySprint.isDown() ? 2.5 : 1);

        double radians = Math.toRadians(yaw);
        double sin = Math.sin(radians);
        double cos = Math.cos(radians);
        double dx = -sin * forward + cos * strafe;
        double dz = cos * forward + sin * strafe;
        double length = Math.sqrt(dx * dx + dz * dz);
        if (length > 1) {
            dx /= length;
            dz /= length;
        }

        if (dx == 0 && dz == 0 && vertical == 0) return;

        if (spectate.get()) {
            pos = clamp(pos.add(dx * step, vertical * verticalSpeed.get(), dz * step));
        } else {
            pos = pos.add(dx * step, vertical * step, dz * step);
        }
    }

    /** Spectate mode: keep the camera inside loaded chunks and within reach of your body. */
    private Vec3 clamp(Vec3 target) {
        double x = target.x;
        double z = target.z;
        if (!isChunkLoaded(x, pos.z)) x = pos.x;
        if (!isChunkLoaded(x, z)) z = pos.z;
        Vec3 clamped = new Vec3(x, target.y, z);
        if (unlimited.get()) return clamped;

        double limit = Math.min(maxDistance.get(), renderDistanceBlocks());
        Vec3 offset = clamped.subtract(anchor);
        double distance = offset.length();
        if (distance > limit) clamped = anchor.add(offset.scale(limit / distance));
        return clamped;
    }

    private double renderDistanceBlocks() {
        return Math.max(16.0, Veil.mc.options.getEffectiveRenderDistance() * 16.0 - 16.0);
    }

    private boolean isChunkLoaded(double x, double z) {
        if (Veil.mc.level == null) return false;
        int chunkX = (int) Math.floor(x) >> 4;
        int chunkZ = (int) Math.floor(z) >> 4;
        return Veil.mc.level.getChunkSource().getChunk(chunkX, chunkZ, ChunkStatus.FULL, false) != null;
    }

    /**
     * Vanilla stops drawing an entity once it is too far from the camera, so flying away made the
     * mobs, players and items around your body vanish even though the server still sends them. While
     * this is on, anything within your render distance of your body stays drawn, measured from the
     * entity's own position. Your own player model is always drawn, even with this off, so you can
     * still see where your body is standing.
     */
    public boolean keepsVisible(Entity entity) {
        if (Veil.mc.player == null) return false;
        if (entity == Veil.mc.player) return true;
        if (!keepEntities.get()) return false;
        double limit = renderDistanceBlocks();
        return entity.distanceToSqr(Veil.mc.player) <= limit * limit;
    }

    /** Camera position for this frame, interpolated so it glides instead of stepping at 20 Hz. */
    public Vec3 cameraPos(float partialTick) {
        return prevPos.lerp(pos, Mth.clamp(partialTick, 0f, 1f));
    }

    /** Mouse movement, with the same scaling as turning your player. */
    public void turn(double yRot, double xRot) {
        yaw += (float) yRot * 0.15f;
        pitch = Mth.clamp(pitch + (float) xRot * 0.15f, -90f, 90f);
    }

    public float yaw() {
        return yaw;
    }

    public float pitch() {
        return pitch;
    }

    public boolean freezePlayer() {
        return freeze.get();
    }

    @Override
    public String suffix() {
        if (!Veil.inGame()) return null;
        String distance = String.format("%.0fm", Math.sqrt(pos.distanceToSqr(Veil.mc.player.getEyePosition())));
        return spectate.get() ? "Spectate " + distance : distance;
    }
}
