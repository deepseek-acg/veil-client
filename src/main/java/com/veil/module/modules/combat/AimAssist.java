// lang: Java, file: AimAssist.java, target: Fabric MC 26.3
package com.veil.module.modules.combat;

import com.veil.Veil;
import com.veil.event.EventHandler;
import com.veil.event.events.TickEvent;
import com.veil.module.Category;
import com.veil.module.Module;
import com.veil.setting.BoolSetting;
import com.veil.setting.FloatSetting;
import com.veil.util.RotationUtil;
import com.veil.util.SilentAimBase;
import com.veil.util.SilentRotationManager;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.util.Mth;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.phys.Vec3;

import java.util.Random;

/**
 * AimAssist — smooth, mouse-grid-aligned rotation correction toward the nearest enemy.
 *
 * <p>Ported from promethean's {@code com.ghost.modules.combat.AimAssist}, keeping both of its modes
 * and the three ideas the whole thing rests on:
 *
 * <ul>
 *   <li><b>Mouse-grid quantization</b> — every correction is a whole multiple of the step the
 *       current sensitivity can actually produce ({@link RotationUtil#gcd()}), so the yaw/pitch
 *       deltas that leave the client look like mouse input rather than a synthetic float. A carried
 *       remainder keeps the average pull exact when a tick is worth less than one step.</li>
 *   <li><b>Rotation written at tick end</b> — {@code yRot}/{@code xRot} are set after the tick has
 *       already copied them into {@code yRotO}/{@code xRotO}, so the renderer interpolates from the
 *       old angle and the camera glides instead of stepping once per tick. The game then emits the
 *       rotation inside its own movement packet; this module never sends a packet itself.</li>
 *   <li><b>Upper-body aim point</b> — aiming at the exact centre of a hitbox leaves a flat,
 *       machine-perfect horizontal trace, so the default aim height is 80 % of the hitbox.</li>
 * </ul>
 *
 * <p>Silent mode layers on top of that: a velocity-predicted aim point, cubic easing with a 12 %
 * overshoot, near-lock acceleration, a burst/pause rhythm, velocity-angle spread, drifting pull and
 * pitch ratio, stochastic tick skip, an idle window, per-engagement aim scatter, micro-saccades and
 * grid-quantized Gaussian noise. Those constants are promethean's tuning, carried over unchanged —
 * they are guesses at what a human hand looks like, not measurements.
 *
 * <p>Nothing here has been observed on a server. The tuning in particular is unverified.
 */
public final class AimAssist extends Module {

    // ── settings ──────────────────────────────────────────────────────────────

    private final FloatSetting speed = register(new FloatSetting("Speed", 0.60f, 0.05f, 1.0f));
    private final FloatSetting fov = register(new FloatSetting("FOV", 90f, 10f, 180f));
    private final FloatSetting range = register(new FloatSetting("Range", 4.5f, 2.0f, 8.0f));
    private final FloatSetting maxAngle = register(new FloatSetting("Max Angle", 2.0f, 0.1f, 10f));
    private final FloatSetting aimHeight = register(new FloatSetting("Aim Height", 0.80f, 0f, 1f));
    private final BoolSetting  targetPlayers = register(new BoolSetting("Players", true));
    private final BoolSetting  targetMobs = register(new BoolSetting("Mobs", false));
    private final BoolSetting  gcdCorrect = register(new BoolSetting("GCD", true));
    private final BoolSetting  holdAttack = register(new BoolSetting("Hold Attack", false));
    private final BoolSetting  silentMode = register(new BoolSetting("Silent", false));
    private final BoolSetting  silentLmbGate = register(new BoolSetting("Silent LMB", true));
    private final FloatSetting silentPull = register(new FloatSetting("Silent Pull", 0.32f, 0.10f, 0.60f));

    // ── shared constants ──────────────────────────────────────────────────────

    /** Angular error at or below which no correction is applied at all. */
    private static final float DEAD_ZONE = 0.05f;

    private static final float NOISE_YAW   = 0.015f;
    private static final float NOISE_PITCH = 0.012f;

    // ── silent-mode constants ─────────────────────────────────────────────────

    /** Random-walk half-width around {@link #silentPull}. */
    private static final float SILENT_PULL_SPREAD = 0.10f;
    /** {@code speed} value at which the drifting pull is applied as-is. */
    private static final float SILENT_SPEED_REFERENCE = 0.60f;
    /** A pull of zero is a silent no-op, so the speed scaling may never reach it. */
    private static final float SILENT_PULL_FLOOR = 0.02f;
    private static final float SILENT_PULL_DRIFT = 0.0080f;

    /** Silent cone, full width: wide enough to hold a strafing opponent. */
    private static final float SILENT_FOV_MAX = 40.0f;

    private static final float SILENT_NOISE_YAW   = 0.018f;
    private static final float SILENT_NOISE_PITCH = 0.011f;

    private static final float SILENT_OVERSHOOT_PEAK = 1.12f;
    private static final float SILENT_OVERSHOOT_SCALE_DEG = 5.0f;

    private static final float SILENT_PITCH_RATIO       = 0.60f;
    private static final float SILENT_PITCH_RATIO_MIN   = 0.52f;
    private static final float SILENT_PITCH_RATIO_MAX   = 0.68f;
    private static final float SILENT_PITCH_RATIO_DRIFT = 0.0150f;

    private static final float SILENT_AIM_SCATTER_HORIZ = 0.12f;
    private static final float SILENT_AIM_SCATTER_VERT  = 0.06f;

    private static final float SILENT_SACCADE_PROB      = 0.03f;
    private static final float SILENT_SACCADE_AMP_YAW   = 0.080f;
    private static final float SILENT_SACCADE_AMP_PITCH = 0.050f;
    private static final int   SILENT_SACCADE_DURATION  = 3;

    private static final float SILENT_SKIP_CHANCE = 0.25f;
    private static final int   SILENT_ACTIVE_BURST = 60;
    private static final int   SILENT_IDLE_TICKS = 4;

    /** Ticks of lead in the aim point. Vertical lead is left out on purpose. */
    private static final float SILENT_LEAD_TICKS = 1.5f;
    /** Unclamped, a fast strafe asks for more than a hitbox width and the aim point misses. */
    private static final float SILENT_LEAD_MAX = 0.35f;

    private static final float SILENT_NEAR_LOCK_DEG = 2.0f;
    private static final float SILENT_NEAR_LOCK_BOOST = 2.2f;

    private static final int   SILENT_BURST_ON = 4;
    private static final int   SILENT_BURST_OFF = 1;
    /** Pause ticks keep a residual: a step down to zero is a discontinuity of its own. */
    private static final float SILENT_BURST_OFF_SCALE = 0.15f;

    private static final float SILENT_ALPHA_DRIFT = 0.0120f;
    private static final float SILENT_ALPHA_MAX = 0.045f;

    // ── state ─────────────────────────────────────────────────────────────────

    private static final Random RNG = new Random();

    /**
     * Sub-step remainder carried between ticks, per axis.
     *
     * <p>Rounding each correction on its own throws away whatever does not fill a whole mouse count:
     * a 0.4-step pull becomes zero every tick, a 0.6-step pull becomes a full step every tick. Either
     * way the emitted deltas stop matching the intended pull. Carrying the leftover keeps the
     * long-run average exact.
     */
    private float gcdRemainderYaw;
    private float gcdRemainderPitch;

    /** The angle this module last put on the wire; each silent step is measured from it. */
    private final SilentAimBase aimBase = new SilentAimBase();

    private float silentPullCurrent = 0.32f;
    private float silentPitchRatioCurrent = SILENT_PITCH_RATIO;
    private int silentActiveTicks;
    private int silentIdleTicks;
    private LivingEntity silentLastTarget;
    private float silentAimScatterX;
    private float silentAimScatterY;
    private float silentAimScatterZ;
    private int silentSaccadeTicks;
    private float silentSaccadeCorrYaw;
    private float silentSaccadeCorrPitch;
    private Vec3 silentLastTargetPos;
    private float silentTargetVelX;
    private float silentTargetVelZ;
    private int silentBurstTick;
    private float silentAlphaBias;

    /** Last target the module acted on, purely so {@link #suffix()} can report it. */
    private LivingEntity lastTarget;

    public AimAssist() {
        super("AimAssist", "Smooth, mouse-grid-aligned aim correction toward the nearest target",
                Category.COMBAT);
    }

    // ── lifecycle ─────────────────────────────────────────────────────────────

    @Override
    public void onEnable() { reset(); }

    @Override
    public void onDisable() { reset(); }

    /**
     * An input lock (another module driving the inventory) pauses event delivery for a while of
     * unknown length. Resuming a half-finished burst or a stale target velocity would act on
     * positions that no longer hold, so the state starts over instead.
     */
    @Override
    public void onPause() { reset(); }

    private void reset() {
        aimBase.clear();
        gcdRemainderYaw = 0f;
        gcdRemainderPitch = 0f;
        silentActiveTicks = 0;
        silentIdleTicks = 0;
        silentPullCurrent = silentPull.get();
        silentPitchRatioCurrent = SILENT_PITCH_RATIO;
        silentLastTarget = null;
        silentAimScatterX = 0f;
        silentAimScatterY = 0f;
        silentAimScatterZ = 0f;
        silentSaccadeTicks = 0;
        silentSaccadeCorrYaw = 0f;
        silentSaccadeCorrPitch = 0f;
        silentLastTargetPos = null;
        silentTargetVelX = 0f;
        silentTargetVelZ = 0f;
        silentBurstTick = 0;
        silentAlphaBias = 0f;
        lastTarget = null;
    }

    /**
     * Why silent mode is quiet, shown next to the module name: holding attack, bleeding an idle
     * window, or nothing in the cone.
     */
    @Override
    public String suffix() {
        if (!silentMode.get() || !Veil.inGame()) return "";
        if (silentLmbGate.get() && !Veil.mc.options.keyAttack.isDown()) return "hold attack";
        if (silentIdleTicks > 0) return "idle " + silentIdleTicks;
        return lastTarget != null ? "tracking" : "no target";
    }

    // ── tick ──────────────────────────────────────────────────────────────────

    @EventHandler
    private void onTick(TickEvent.Post event) {
        if (!Veil.inGame()) return;
        LocalPlayer self = Veil.mc.player;

        // A screen owns the cursor, so the view must not move: without this the camera keeps
        // turning behind the click GUI, the inventory or chat. The carried remainder goes too, so
        // the first tick back does not spend it.
        if (Veil.mc.gui != null && Veil.mc.gui.screen() != null) {
            clearRemainder();
            return;
        }
        if (!self.isAlive()) {
            clearRemainder();
            return;
        }

        float yaw = self.getYRot();
        float pitch = self.getXRot();

        if (silentMode.get()) tickSilent(self, yaw, pitch);
        else tickNormal(self, yaw, pitch);
    }

    private void clearRemainder() {
        gcdRemainderYaw = 0f;
        gcdRemainderPitch = 0f;
    }

    // ── normal mode ───────────────────────────────────────────────────────────

    private void tickNormal(LocalPlayer self, float yaw, float pitch) {
        if (holdAttack.get() && !Veil.mc.options.keyAttack.isDown()) return;

        // fov is a full width; the cone test wants a half-angle.
        LivingEntity target = findTarget(self, fov.get() * 0.5f);
        if (target == null) return;

        Vec3 eye = self.getEyePosition();
        float[] needed = rotationsTo(eye, aimPoint(target, false));
        float needYaw = Mth.wrapDegrees(needed[0] - yaw);
        float needPitch = needed[1] - pitch;

        // Already on target — no correction, and above all no noise on top of it.
        if (Math.abs(needYaw) < DEAD_ZONE && Math.abs(needPitch) < DEAD_ZONE) return;

        float limit = maxAngle.get();
        float pull = speed.get();
        float dYaw = Mth.clamp(needYaw * pull, -limit, limit);
        float dPitch = Mth.clamp(needPitch * pull, -limit, limit);

        // Jitter goes on before quantization: noise added afterwards leaves the delta a few
        // thousandths off the mouse grid, which is exactly the signature the grid exists to avoid.
        dYaw += (float) (RNG.nextGaussian() * NOISE_YAW);
        dPitch += (float) (RNG.nextGaussian() * NOISE_PITCH);

        if (gcdCorrect.get()) {
            float step = RotationUtil.gcd();
            dYaw = quantizeCarry(dYaw, step, true, limit);
            dPitch = quantizeCarry(dPitch, step, false, limit);
        } else {
            dYaw = Mth.clamp(dYaw, -limit, limit);
            dPitch = Mth.clamp(dPitch, -limit, limit);
        }

        apply(self, yaw + dYaw, Mth.clamp(pitch + dPitch, -90f, 90f));
    }

    // ── silent mode ───────────────────────────────────────────────────────────

    private void tickSilent(LocalPlayer self, float yaw, float pitch) {
        // 1. Pull magnitude and pitch ratio drift as bounded random walks, so no two ticks apply the
        //    same fraction and a fixed-ratio pattern never appears in the rotation stream.
        silentPullCurrent = Mth.clamp(
                silentPullCurrent + (float) (RNG.nextGaussian() * SILENT_PULL_DRIFT),
                silentPull.get() - SILENT_PULL_SPREAD, silentPull.get() + SILENT_PULL_SPREAD);
        silentPitchRatioCurrent = Mth.clamp(
                silentPitchRatioCurrent + (float) (RNG.nextGaussian() * SILENT_PITCH_RATIO_DRIFT),
                SILENT_PITCH_RATIO_MIN, SILENT_PITCH_RATIO_MAX);

        // 2. Burst rhythm and the perpendicular bias walk.
        silentBurstTick = (silentBurstTick + 1) % (SILENT_BURST_ON + SILENT_BURST_OFF);
        boolean inBurst = silentBurstTick < SILENT_BURST_ON;
        silentAlphaBias = Mth.clamp(
                silentAlphaBias + (float) (RNG.nextGaussian() * SILENT_ALPHA_DRIFT),
                -SILENT_ALPHA_MAX, SILENT_ALPHA_MAX);

        // 3. Attack-intent gate. A velocity carried into the next fight would aim behind it.
        if (silentLmbGate.get() && !Veil.mc.options.keyAttack.isDown()) {
            silentLastTargetPos = null;
            silentTargetVelX = 0f;
            silentTargetVelZ = 0f;
            return;
        }

        // 4. Idle window: short enough not to drop a fight, long enough for a counter to decay.
        if (silentIdleTicks > 0) {
            silentIdleTicks--;
            return;
        }

        // 5. Stochastic skip, on burst-on ticks only: skipping suppressed ticks as well would widen
        //    the distribution without changing its shape.
        if (inBurst && RNG.nextFloat() < SILENT_SKIP_CHANCE) return;

        // 6. Target, velocity estimate, per-engagement aim scatter.
        LivingEntity target = findTarget(self, Math.min(fov.get(), SILENT_FOV_MAX) * 0.5f);
        lastTarget = target;
        if (target == null) {
            silentLastTarget = null;
            silentLastTargetPos = null;
            silentTargetVelX = 0f;
            silentTargetVelZ = 0f;
            return;
        }

        Vec3 position = new Vec3(target.getX(), target.getY(), target.getZ());
        if (silentLastTargetPos != null && target == silentLastTarget) {
            silentTargetVelX = (float) (position.x - silentLastTargetPos.x);
            silentTargetVelZ = (float) (position.z - silentLastTargetPos.z);
        } else {
            // New engagement: a prediction left over from the previous target points at it.
            silentTargetVelX = 0f;
            silentTargetVelZ = 0f;
        }
        silentLastTargetPos = position;

        if (target != silentLastTarget) {
            silentLastTarget = target;
            // Re-rolled per engagement so the aim point is not always the same spot on the body.
            silentAimScatterX = (RNG.nextFloat() * 2f - 1f) * SILENT_AIM_SCATTER_HORIZ * target.getBbWidth();
            silentAimScatterZ = (RNG.nextFloat() * 2f - 1f) * SILENT_AIM_SCATTER_HORIZ * target.getBbWidth();
            silentAimScatterY = (RNG.nextFloat() * 2f - 1f) * SILENT_AIM_SCATTER_VERT;
        }

        // 7. Aim point, led forward. Vertical velocity is left out: jump arcs and knockback make it
        //    unreliable, and a bad pitch prediction widens the pitch distribution more than it helps.
        Vec3 eye = self.getEyePosition();
        Vec3 aim = aimPoint(target, true);
        double leadX = silentTargetVelX * SILENT_LEAD_TICKS;
        double leadZ = silentTargetVelZ * SILENT_LEAD_TICKS;
        double leadLength = Math.sqrt(leadX * leadX + leadZ * leadZ);
        if (leadLength > SILENT_LEAD_MAX) {
            double shrink = SILENT_LEAD_MAX / leadLength;
            leadX *= shrink;
            leadZ *= shrink;
        }
        if (leadX != 0 || leadZ != 0) {
            aim = new Vec3(aim.x + leadX, aim.y, aim.z + leadZ);
        }

        // 8. Eased pull with a slight overshoot that decays as the aim converges. The error is
        //    measured from the angle this module last put on the wire, not from the camera: silent
        //    mode never moves the camera, so measuring from it re-read the same frozen offset every
        //    tick and queued the same part-way angle again and again, and the aim never arrived.
        //    The base is this module's own, so a crystal cycle or an anchor sequence aiming at the
        //    same time cannot walk this aim onto its own target.
        boolean tracked = aimBase.has();
        float baseYaw   = tracked ? aimBase.yaw()   : yaw;
        float basePitch = tracked ? aimBase.pitch() : pitch;
        float[] needed = rotationsTo(eye, aim);
        float needYaw = Mth.wrapDegrees(needed[0] - baseYaw);
        float needPitch = needed[1] - basePitch;

        // On target already: skip entirely, so the pull walk, burst rhythm, saccades and bias cannot
        // shimmer a crosshair that is where it should be.
        if (Math.abs(needYaw) < DEAD_ZONE && Math.abs(needPitch) < DEAD_ZONE) return;

        float ease = Mth.clamp(1f - Math.abs(needYaw) / SILENT_OVERSHOOT_SCALE_DEG, 0f, 1f);
        float smoothEase = ease * ease * (3f - 2f * ease);
        float overshoot = SILENT_OVERSHOOT_PEAK + smoothEase * (1f - SILENT_OVERSHOOT_PEAK);

        float speedScale = Mth.clamp(speed.get() / SILENT_SPEED_REFERENCE, 0.05f, 2f);
        float pull = Mth.clamp(silentPullCurrent * speedScale, SILENT_PULL_FLOOR, 1f);

        float limit = maxAngle.get();
        float pitchLimit = limit * silentPitchRatioCurrent;
        float dYaw = Mth.clamp(needYaw * pull * overshoot, -limit, limit);
        float dPitch = Mth.clamp(needPitch * pull * overshoot * silentPitchRatioCurrent,
                -pitchLimit, pitchLimit);

        // 9. Near-lock acceleration: the last couple of degrees are where a low fixed gain visibly
        //    never arrives, and a small extra push there is still far below a snap threshold.
        if (Math.abs(needYaw) < SILENT_NEAR_LOCK_DEG) {
            dYaw = Mth.clamp(dYaw * SILENT_NEAR_LOCK_BOOST, -limit, limit);
            dPitch = Mth.clamp(dPitch * SILENT_NEAR_LOCK_BOOST, -pitchLimit, pitchLimit);
        }

        // 10. Burst pause keeps a residual instead of stopping dead.
        if (!inBurst) {
            dYaw *= SILENT_BURST_OFF_SCALE;
            dPitch *= SILENT_BURST_OFF_SCALE;
        }

        // 11. Micro-saccade: a short overshoot that is paid back over the next few ticks.
        if (silentSaccadeTicks > 0) {
            dYaw += silentSaccadeCorrYaw;
            dPitch += silentSaccadeCorrPitch;
            silentSaccadeTicks--;
        } else if (RNG.nextFloat() < SILENT_SACCADE_PROB) {
            float saccadeYaw = (RNG.nextFloat() * 2f - 1f) * SILENT_SACCADE_AMP_YAW;
            float saccadePitch = (RNG.nextFloat() * 2f - 1f) * SILENT_SACCADE_AMP_PITCH;
            dYaw += saccadeYaw;
            dPitch += saccadePitch;
            silentSaccadeCorrYaw = -saccadeYaw / SILENT_SACCADE_DURATION;
            silentSaccadeCorrPitch = -saccadePitch / SILENT_SACCADE_DURATION;
            silentSaccadeTicks = SILENT_SACCADE_DURATION;
        }

        // 12. Velocity-angle spread. Corrections are yaw-dominant at this cone width, so the
        //     perpendicular bias goes on yaw.
        dYaw += silentAlphaBias;

        // 13. Grid alignment, then noise quantized on the same grid, then a bound that is a whole
        //     number of steps — clamping to anything else would put the deltas back off the grid.
        float step = RotationUtil.gcd();
        dYaw = quantizeCarry(dYaw, step, true, limit);
        dPitch = quantizeCarry(dPitch, step, false, pitchLimit);

        dYaw += RotationUtil.quantize((float) (RNG.nextGaussian() * SILENT_NOISE_YAW), step);
        dPitch += RotationUtil.quantize((float) (RNG.nextGaussian() * SILENT_NOISE_PITCH), step);

        float yawBound = alignedLimit(limit, step);
        float pitchBound = alignedLimit(pitchLimit, step);
        dYaw = Mth.clamp(dYaw, -yawBound, yawBound);
        dPitch = Mth.clamp(dPitch, -pitchBound, pitchBound);

        float newYaw   = baseYaw + dYaw;
        float newPitch = Mth.clamp(basePitch + dPitch, -90f, 90f);
        // Queued and sent here rather than left for the next movement packet: a player standing
        // still sends nothing that carries a rotation, so the queue never left the client and silent
        // mode did nothing at all. The packet is rotation-only, which does not take the one
        // position-carrying slot the server accepts per tick.
        SilentRotationManager.setPending(newYaw, newPitch);
        SilentRotationManager.flush();
        aimBase.record(newYaw, newPitch);

        // 14. Only burst-on ticks count against the idle budget; off ticks barely correct anything.
        if (inBurst && ++silentActiveTicks >= SILENT_ACTIVE_BURST) {
            silentActiveTicks = 0;
            silentIdleTicks = SILENT_IDLE_TICKS;
        }
    }

    // ── shared helpers ────────────────────────────────────────────────────────

    private static void apply(LocalPlayer self, float yaw, float pitch) {
        self.setYRot(yaw);
        self.setXRot(pitch);
    }

    private LivingEntity findTarget(LocalPlayer self, float halfFovDeg) {
        if (Veil.mc.level == null) return null;

        float maxRange = range.get();
        Vec3 look = self.getLookAngle();
        double threshold = Math.cos(Math.toRadians(halfFovDeg));

        LivingEntity best = null;
        double bestDot = threshold;
        for (Entity entity : Veil.mc.level.entitiesForRendering()) {
            if (!(entity instanceof LivingEntity candidate)) continue;
            if (!validTarget(self, candidate, maxRange)) continue;

            Vec3 direction = candidate.getEyePosition().subtract(self.getEyePosition());
            if (direction.lengthSqr() < 1.0E-4) continue;
            // A greater dot is a smaller angle, so this picks the target closest to the crosshair.
            double dot = look.dot(direction.normalize());
            if (dot < bestDot) continue;
            bestDot = dot;
            best = candidate;
        }
        return best;
    }

    private boolean validTarget(LocalPlayer self, LivingEntity target, float maxRange) {
        if (target == self || !target.isAlive() || target.isInvisible() || target.isSpectator()) return false;
        if (target instanceof Player ? !targetPlayers.get() : !targetMobs.get()) return false;
        return self.distanceTo(target) <= maxRange;
    }

    /** Point on the target to aim at: a fraction of its height, with silent mode's scatter. */
    private Vec3 aimPoint(LivingEntity target, boolean silent) {
        float fraction = aimHeight.get();
        double scatterX = 0.0;
        double scatterZ = 0.0;

        if (silent) {
            fraction = Mth.clamp(fraction + silentAimScatterY, 0.05f, 0.95f);
            scatterX = silentAimScatterX;
            scatterZ = silentAimScatterZ;
        }

        return new Vec3(target.getX() + scatterX,
                target.getY() + target.getBbHeight() * fraction,
                target.getZ() + scatterZ);
    }

    private static float[] rotationsTo(Vec3 from, Vec3 to) {
        double dx = to.x - from.x;
        double dy = to.y - from.y;
        double dz = to.z - from.z;
        double horizontal = Math.sqrt(dx * dx + dz * dz);
        float yaw = (float) Math.toDegrees(Math.atan2(dz, dx)) - 90f;
        float pitch = (float) -Math.toDegrees(Math.atan2(dy, horizontal));
        return new float[]{yaw, pitch};
    }

    // ── mouse grid ────────────────────────────────────────────────────────────

    /**
     * Snaps {@code delta} onto the mouse grid, carrying the fractional part into the next call.
     *
     * <p>A non-zero correction is never rounded to zero: if the carried total is under half a step
     * it still moves one step in the intended direction, and the overshoot is subtracted from the
     * remainder so the next tick compensates. The remainder is clamped to a step, so a pause cannot
     * bank a visible jump.
     *
     * <p>{@code maxAbs} bounds the result in whole steps, so the caller never has to clamp
     * afterwards: a limit narrower than one step yields no correction, since none would fit.
     */
    private float quantizeCarry(float delta, float step, boolean yaw, float maxAbs) {
        if (step <= 0f) return Mth.clamp(delta, -maxAbs, maxAbs);

        float carried = delta + (yaw ? gcdRemainderYaw : gcdRemainderPitch);
        float units = Math.round(carried / step);
        if (units == 0f) units = Math.signum(delta);

        float maxUnits = (float) Math.floor(maxAbs / step);
        units = Mth.clamp(units, -maxUnits, maxUnits);

        float out = units * step;
        float rest = Mth.clamp(carried - out, -step, step);
        if (yaw) gcdRemainderYaw = rest;
        else gcdRemainderPitch = rest;
        return out;
    }

    /** {@code limit} rounded down to a whole number of mouse steps. */
    private static float alignedLimit(float limit, float step) {
        if (step <= 0f) return limit;
        return Math.max(0f, (float) Math.floor(limit / step) * step);
    }
}
