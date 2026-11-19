// lang: Java, file: KillAura.java, target: Fabric MC 26.3
package com.veil.module.modules.combat;

import com.veil.Veil;
import com.veil.event.EventHandler;
import com.veil.event.events.TickEvent;
import com.veil.module.Category;
import com.veil.module.Module;
import com.veil.module.ModuleManager;
import com.veil.module.modules.combat.AimAssist;
import com.veil.setting.BoolSetting;
import com.veil.setting.FloatSetting;
import com.veil.setting.IntSetting;
import com.veil.setting.ModeSetting;
import com.veil.util.RotationUtil;
import com.veil.util.ServerDetector;
import com.veil.util.ServerProfile;
import com.veil.util.SilentRotationManager;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.network.protocol.game.ServerboundPlayerCommandPacket;
import net.minecraft.util.Mth;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.item.component.SwingAnimation;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.phys.Vec3;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Random;

public final class KillAura extends Module {

    private final FloatSetting cpsMin        = register(new FloatSetting("CPS Min",        8.0f,  1.0f, 20.0f));
    private final FloatSetting cpsMax        = register(new FloatSetting("CPS Max",        12.0f,  1.0f, 20.0f));
    private final FloatSetting reachDist     = register(new FloatSetting("Reach",           3.0f,  2.5f, 6.0f));
    private final ModeSetting  rotateMode    = register(new ModeSetting("Rotate", "Aim", new String[]{"Aim", "None"}));
    private final IntSetting   attackFov     = register(new IntSetting("FOV",              90,    10, 180));
    private final BoolSetting  wtap          = register(new BoolSetting("W-Tap",            true));
    private final BoolSetting  targetPlayers = register(new BoolSetting("Players",          true));
    private final BoolSetting  targetMobs    = register(new BoolSetting("Mobs",             false));
    private final FloatSetting attackCooldown= register(new FloatSetting("Cooldown",        0.95f,  0.5f, 1.0f));
    private final BoolSetting  silentMode    = register(new BoolSetting("Silent",           false));
    private final BoolSetting  conflictGuard = register(new BoolSetting("Conflict Guard",   true));
    private final ModeSetting  serverProfile = register(new ModeSetting("Server", "Auto",
            new String[]{"Auto", "Hypixel", "Grim", "Intave", "Default"}));

    private static final Random RANDOM = new Random();

    private static KillAura INSTANCE;

    private long nextAttackTime;
    private LivingEntity lockedTarget;
    private long targetLockTime;
    private static final long TARGET_LOCK_MS = 500;

    public KillAura() {
        super("KillAura", "Attacks the nearest target in reach", Category.COMBAT);
        INSTANCE = this;
    }

    public static LivingEntity getTarget() {
        return INSTANCE != null ? INSTANCE.lockedTarget : null;
    }

    @Override
    public void onEnable() {
        nextAttackTime = 0;
        lockedTarget = null;
    }

    @Override
    public void onDisable() {
        lockedTarget = null;
        SilentRotationManager.clear();
    }

    @Override
    public void onPause() {
        lockedTarget = null;
        nextAttackTime = 0L;
        SilentRotationManager.clear();
    }

    @EventHandler
    private void onTick(TickEvent.Pre event) {
        if (!Veil.inGame()) return;
        LocalPlayer self = Veil.mc.player;
        ServerProfile profile = ServerDetector.resolve(serverProfile.get());

        LivingEntity target = target(self, profile);
        if (target == null) return;

        double reach = Math.min(reachDist.get(), profile.reachCeiling() - 0.05f);
        if (self.distanceTo(target) > reach) return;

        float yaw   = yawTo(self, target);
        float pitch = pitchTo(self, target);

        Vec3 selfLook = self.getLookAngle();
        Vec3 toTarget = target.getEyePosition().subtract(self.getEyePosition()).normalize();
        double dot = selfLook.dot(toTarget);
        double threshold = Math.cos(Math.toRadians(attackFov.get()));
        if (dot < threshold) return;

        boolean silent = silentMode.get();
        boolean aimAssistActive = conflictGuard.get() && isAimAssistEnabled();

        if ("Aim".equals(rotateMode.get())) {
            float gcd    = RotationUtil.gcd();
            float dYaw   = RotationUtil.quantize(Mth.wrapDegrees(yaw   - self.getYRot()), gcd);
            float dPitch = RotationUtil.quantize(Mth.wrapDegrees(pitch - self.getXRot()), gcd);

            if (silent) {
                // Camera stays frozen; rotation reaches server via SilentRotationMixin.
                if (!aimAssistActive) {
                    SilentRotationManager.setPending(
                            self.getYRot() + dYaw,
                            Mth.clamp(self.getXRot() + dPitch, -90f, 90f));
                }
                // If conflictGuard is on and AimAssist is active, let AimAssist own the rotation.
            } else {
                self.setYRot(self.getYRot() + dYaw);
                self.setXRot(Mth.clamp(self.getXRot() + dPitch, -90f, 90f));
            }
        }

        if (self.getAttackStrengthScale(0.5f) < attackCooldown.get()) return;
        if (System.currentTimeMillis() < nextAttackTime) return;
        if (Veil.mc.gameMode == null) return;

        // Silent-mode behavioral skip: humans hesitate/misclick ~10–15% of eligible ticks.
        if (silent && RANDOM.nextFloat() < silentSkipChance(profile)) return;

        boolean wtapAllowed = wtap.get() && profile.wtapSafe();
        boolean wasSprinting = self.isSprinting();
        if (wtapAllowed && wasSprinting) sendSprint(self, false);

        Veil.mc.gameMode.attack(self, target);
        self.swing(InteractionHand.MAIN_HAND, SwingAnimation.DEFAULT, false);

        if (wtapAllowed && wasSprinting) sendSprint(self, true);

        // Gaussian CPS capped by server profile.
        float profileCpsCap = profile.cpsMax();
        float silentCpsCap  = (silent && profile == ServerProfile.INTAVE) ? 12f : profileCpsCap;
        float effectiveMax  = Math.min(cpsMax.get(), silentCpsCap);
        float effectiveMin  = Math.min(cpsMin.get(), effectiveMax);

        float mean  = (effectiveMin + effectiveMax) / 2f;
        float sigma = (effectiveMax - effectiveMin) / 4f;
        float cps   = mean + (float)(RANDOM.nextGaussian() * sigma);
        cps = Math.max(effectiveMin, Math.min(effectiveMax, cps));
        long interval = Math.max(50L, (long)(1000.0f / cps));
        nextAttackTime = System.currentTimeMillis() + interval;
    }

    private static float silentSkipChance(ServerProfile profile) {
        // 10% base; slightly higher on stricter servers.
        return profile == ServerProfile.INTAVE ? 0.14f : 0.10f;
    }

    private static boolean isAimAssistEnabled() {
        for (var m : ModuleManager.getModules()) {
            if (m instanceof AimAssist && m.isEnabled()) return true;
        }
        return false;
    }

    // ── target selection ──────────────────────────────────────────────────────

    private LivingEntity target(LocalPlayer self, ServerProfile profile) {
        long now = System.currentTimeMillis();
        float effectiveReach = Math.min(reachDist.get(), profile.reachCeiling() - 0.05f);

        if (lockedTarget != null && lockedTarget.isAlive()
                && now - targetLockTime < TARGET_LOCK_MS
                && self.distanceTo(lockedTarget) <= effectiveReach + 0.5f) {
            return lockedTarget;
        }

        List<LivingEntity> candidates = new ArrayList<>();
        for (Entity e : Veil.mc.level.entitiesForRendering()) {
            if (!(e instanceof LivingEntity le) || le == self) continue;
            if (!le.isAlive()) continue;
            if (le instanceof Player && !targetPlayers.get()) continue;
            if (!(le instanceof Player) && !targetMobs.get()) continue;
            if (self.distanceTo(le) > effectiveReach) continue;
            candidates.add(le);
        }

        if (candidates.isEmpty()) {
            lockedTarget = null;
            return null;
        }

        lockedTarget = candidates.stream()
                .min(Comparator.comparingDouble(self::distanceTo))
                .orElse(null);
        targetLockTime = now;
        return lockedTarget;
    }

    // ── helpers ───────────────────────────────────────────────────────────────

    private static void sendSprint(LocalPlayer self, boolean start) {
        if (Veil.mc.getConnection() == null) return;
        ServerboundPlayerCommandPacket.Action action = start
                ? ServerboundPlayerCommandPacket.Action.START_SPRINTING
                : ServerboundPlayerCommandPacket.Action.STOP_SPRINTING;
        Veil.mc.getConnection().send(new ServerboundPlayerCommandPacket(self, action));
        self.setSprinting(start);
    }

    private static float yawTo(LocalPlayer self, Entity target) {
        double dx = target.getX() - self.getX();
        double dz = target.getZ() - self.getZ();
        return (float) Math.toDegrees(Math.atan2(dz, dx)) - 90f;
    }

    private static float pitchTo(LocalPlayer self, Entity target) {
        double dx = target.getX() - self.getX();
        double dy = (target.getY() + target.getBbHeight() * 0.5) - self.getEyeY();
        double dz = target.getZ() - self.getZ();
        double dist = Math.sqrt(dx * dx + dz * dz);
        return Mth.clamp((float) -Math.toDegrees(Math.atan2(dy, dist)), -90f, 90f);
    }

    @Override
    public String suffix() {
        ServerProfile profile = ServerDetector.resolve(serverProfile.get());
        String base = String.format("%.1f CPS", (cpsMin.get() + cpsMax.get()) / 2f);
        if (silentMode.get()) base += " §7[S]";
        return base;
    }
}
