// lang: Java, file: CrystalAura.java, target: Fabric MC 26.3
// Direct port of nyx AutoCrystal (com.cpvp.addon.modules) with Meteor framework calls
// replaced by mist equivalents. Live-inputs, friends, and Meteor-only modules dropped.
package com.veil.module.modules.combat;

import com.veil.Veil;
import com.veil.event.EventHandler;
import com.veil.event.events.AttackEntityEvent;
import com.veil.event.events.TickEvent;
import com.veil.module.Category;
import com.veil.module.Module;
import com.veil.module.ModuleManager;
import com.veil.setting.BoolSetting;
import com.veil.setting.FloatSetting;
import com.veil.util.DamageUtil;
import com.veil.util.HotbarSlots;
import com.veil.util.RotationUtil;
import com.veil.util.SessionFatigue;
import com.veil.util.SilentAimBase;
import com.veil.util.SilentAimState;
import com.veil.util.SilentRotationManager;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.tags.ItemTags;
import net.minecraft.util.Mth;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.boss.enderdragon.EndCrystal;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.component.SwingAnimation;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.BlockStateProperties;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.Vec3;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ThreadLocalRandom;

public final class CrystalAura extends Module {

    // === Settings — ported from nyx, Meteor types → mist types, tick delays → ms ===

    // General
    private final FloatSetting targetRange     = register(new FloatSetting("Target Range",      8.0f,   1.0f,  12.0f));
    private final FloatSetting placeRange      = register(new FloatSetting("Place Range",       4.5f,   1.0f,   6.0f));
    private final FloatSetting knockupWindow   = register(new FloatSetting("Knockup Window",  750.0f,  50.0f, 3000.0f)); // ms (nyx: 15 ticks)
    private final BoolSetting  useExisting     = register(new BoolSetting("Use Existing",      true));
    private final BoolSetting  placeObsidian   = register(new BoolSetting("Place Obsidian",    true));
    private final BoolSetting  avoidPath       = register(new BoolSetting("Avoid Path",        true));
    private final FloatSetting pathClearance   = register(new FloatSetting("Path Clearance",    1.0f,   0.5f,   3.0f));
    private final FloatSetting damageTolerance = register(new FloatSetting("Damage Tolerance",  3.0f,   0.0f,  20.0f));
    private final BoolSetting  rotate          = register(new BoolSetting("Rotate",             true));
    private final BoolSetting  silent          = register(new BoolSetting("Silent",             true));
    private final BoolSetting  swing           = register(new BoolSetting("Swing",              true));
    private final BoolSetting  swapBack        = register(new BoolSetting("Swap Back",          true));
    // AnchorAura
    private final BoolSetting  waitForAnchor   = register(new BoolSetting("Wait For Anchor",    true));
    private final BoolSetting  pacing          = register(new BoolSetting("Pacing",             true));
    private final BoolSetting  saccade         = register(new BoolSetting("Saccade",            true));
    // Macro
    private final BoolSetting  macroMode       = register(new BoolSetting("Macro Mode",         false));
    // Damage
    private final FloatSetting minDamage       = register(new FloatSetting("Min Damage",        6.0f,   0.0f,  36.0f));
    private final FloatSetting maxSelfDamage   = register(new FloatSetting("Max Self Damage",   8.0f,   0.0f,  36.0f));
    private final BoolSetting  antiSuicide     = register(new BoolSetting("Anti Suicide",       true));
    // Delays (ms; nyx originals in ticks: obsidian=1, crystal=1, cycle=2, timeout=15)
    private final FloatSetting obsidianDelay   = register(new FloatSetting("Obsidian Delay",   50.0f,   0.0f, 1000.0f));
    private final FloatSetting crystalDelay    = register(new FloatSetting("Crystal Delay",    50.0f,   0.0f, 1000.0f));
    private final FloatSetting cycleDelay      = register(new FloatSetting("Cycle Delay",     100.0f,   0.0f, 2000.0f));
    private final FloatSetting breakTimeout    = register(new FloatSetting("Break Timeout",   750.0f, 100.0f, 3000.0f));

    // === State machine (nyx enum + record, verbatim) ===

    private enum Stage { IDLE, PLACE_OBSIDIAN, PLACE_CRYSTAL, BREAK, COOLDOWN }

    /**
     * How long after a sword hit the target is still allowed to read as standing on the ground.
     *
     * <p>The client sees a hit player's knockback only once the server's movement updates for them
     * arrive, a tick or more later, so the first ticks after our own hit still read as grounded.
     */
    private static final long KNOCKUP_GRACE_MS = 250L;

    /** How long a base is parked after a give-up that says nothing about the base itself. */
    private static final long PARK_MS = 1000L;

    /** The target drifted off the plan: the base may be fine again shortly, so park it briefly. */
    private static final long PARK_STALE_MS = 500L;

    /** One tick: long enough for whatever the anchor sent this tick to be behind the crystal's click. */
    private static final long ANCHOR_HANDOFF_MS = 50L;

    /** What an aimed action did this tick: clicked, still waiting on the camera's turn, or refused. */
    private enum Click { MADE, TURNING, REFUSED }

    /** How many ticks a due click waits for the anchor before the hand is taken anyway. */
    private static final int ANCHOR_WAIT_TICKS = 2;

    private static final java.util.Random HR = new java.util.Random();

    private record Plan(Player target, BlockPos base, boolean needsObsidian, double damage, double turn) {}

    // === Instance fields (nyx fields translated to ms timestamps) ===

    private Stage    stage        = Stage.IDLE;
    private BlockPos base;
    private Player   target;
    private long     nextAction   = 0L;       // replaces nyx wait (ticks) → ms timestamp
    private long     stageStartMs = 0L;       // replaces nyx stageStart (ticks) → ms
    private boolean  actedThisTick;                 // an interaction has already gone out this tick
    private BlockPos lastBase;
    private Player   lastHit;
    private long     lastHitMs    = -50000L;  // replaces nyx lastHitTick → ms
    private final Map<BlockPos, Long> skipUntil = new HashMap<>(); // replaces Map<BlockPos,Integer>

    // === Humanized pacing — read by burstAllowsAction(), the skip in onTick() and the saccade in aim() ===
    /** Per-server timing preset; forCurrentServer() never returns null. */
    private SilentCrystalProfile crystalProfile = SilentCrystalProfile.DEFAULT;
    private final SilentAimState aimState = new SilentAimState();
    /** The angle this module last put on the wire; each silent step is measured from it. */
    private final SilentAimBase aimBase = new SilentAimBase();
    /** Game-tick stamp, so a visible turn advances at most one step per tick. */
    private int tickCounter;
    private int aimAdvancedAt = -1;
    private int     burstCounter;
    private int     idleCounter;
    private int     burstTarget = 8;
    private int     idleTarget  = 2;
    private boolean inBurst     = true;

    /** The AnchorAura instance this aura hands the hand over to, looked up once. */
    private AnchorAura anchorAura;

    /** The anchor whose sequence is held for the crystal in flight, or null when none is. */
    private AnchorAura heldAnchor;

    /** Ticks a due click has already waited for the anchor to reach a point it can be held at. */
    private int anchorDeferrals;

    /** The last place -> break gap, so a re-draw does not hand out the same number of ms twice. */
    private long lastPlaceBreakMs = -1L;

    // === Silent rotation — queued in SilentRotationManager, which SilentRotationMixin reads ===
    private static final float MAX_YAW_PER_TICK   = 45.0f;
    private static final float MAX_PITCH_PER_TICK = 30.0f;

    // aim() — walks the view onto the target along the same minimum-jerk trajectory in both modes:
    // into the outgoing rotations when Silent is on, and onto the camera when it is off. Every step is
    // quantised to the mouse GCD, so no single step exceeds what the sensitivity allows.
    // Silent callers follow it with SilentRotationManager.flush(), which puts the rotation on the wire
    // before the action, so there the click may go out on the same tick: the server already has the
    // aimed view by the time it arrives. With Silent off the camera is the view the click is aimed
    // with, so the return value says whether the turn has arrived and the click may go out this tick.
    private boolean aim(LocalPlayer self, Vec3 target) {
        if (!rotate.get()) return true;
        double dx = target.x - self.getX();
        double dz = target.z - self.getZ();
        double dy = target.y - self.getEyeY();
        double hDist = Math.sqrt(dx * dx + dz * dz);
        float targetYaw   = (float) Math.toDegrees(Math.atan2(-dx, dz));
        float targetPitch = (float) Math.toDegrees(-Math.atan2(dy, hDist));
        float gcd = RotationUtil.gcd();

        // The turn is walked in along a minimum-jerk trajectory rather than a capped step, in both
        // modes; a target that moves restarts the movement. Only the steps' destination differs.
        // Silent steps are measured from this module's own last step, not from whatever angle was
        // sent last: an anchor sequence aiming at the same time sends its own, and stepping from
        // that would drag every crystal turn onto the anchor's block.
        boolean silentAim = silent.get();
        boolean tracked   = silentAim && aimBase.has();
        float fromYaw   = tracked ? aimBase.yaw()   : self.getYRot();
        float fromPitch = tracked ? aimBase.pitch() : self.getXRot();

        if (!aimState.isMoving()
                || Math.abs(Mth.wrapDegrees(targetYaw - aimState.targetYaw())) > 1.0f
                || Math.abs(targetPitch - aimState.targetPitch()) > 1.0f) {
            aimState.start(fromYaw, fromPitch, targetYaw, targetPitch);
        }

        if (!silentAim) {
            // The move starts here, on the tick a click is due: take this tick's step now and leave the
            // rest of the turn to advanceCameraAim() on the ticks that follow. The click only goes out
            // once that turn has arrived.
            return advanceCameraAim(self);
        }

        float[] step = aimState.tick(gcd);
        if (step == null) {
            // Converged: the drift belongs on the finished view, not on one still travelling.
            step = saccadedView(targetYaw, targetPitch, gcd);
        }
        SilentRotationManager.setPending(step[0], step[1]);
        aimBase.record(step[0], step[1]);
        return true;
    }

    /**
     * Advances a visible turn one step, at most once per game tick, and writes the step to the camera.
     *
     * <p>Silent mode walks the same trajectory into the outgoing rotations from {@link #aim()}. With
     * Silent off there is no packet path to hide the turn on, so the camera itself has to be moved a
     * step at a time instead of being snapped to the target — and the click waits for the last of
     * those steps, because the face a placement names and the crystal a break attacks are picked
     * through the crosshair the camera is on.
     *
     * @return true once the camera has arrived on the target, false while the turn is still in flight
     */
    private boolean advanceCameraAim(LocalPlayer self) {
        // Nothing left to walk: the view is wherever the last turn left it.
        if (!aimState.isMoving()) return true;
        if (aimAdvancedAt != tickCounter) {
            aimAdvancedAt = tickCounter;
            float gcd = RotationUtil.gcd();
            float[] step = aimState.tick(gcd);
            if (step == null) {
                // Converged: land on the target with the same micro-saccade the silent path applies.
                float[] view = saccadedView(aimState.targetYaw(), aimState.targetPitch(), gcd);
                self.setYRot(view[0]);
                self.setXRot(view[1]);
                aimState.reset();
                // The step that just ended put the camera on the target, so the click may go out now.
                return true;
            }
            self.setYRot(step[0]);
            self.setXRot(step[1]);
        }
        // A step short of the target is still mid-turn: the click waits for the camera to arrive
        // rather than naming a spot the crosshair is not on yet.
        return aimState.isConverged(self.getYRot(), self.getXRot());
    }

    /**
     * The finished view: the target plus the micro-saccade, quantised to the mouse grid where the
     * server preset asks for it.
     */
    private float[] saccadedView(float yaw, float pitch, float gcd) {
        float maxSaccade = crystalProfile.microSaccadeMaxDeg;
        if (saccade.get() && maxSaccade > 0f) {
            float jitterYaw = Mth.clamp(
                    (float)(ThreadLocalRandom.current().nextGaussian() * maxSaccade * 0.4f),
                    -maxSaccade, maxSaccade);
            float jitterPitch = Mth.clamp(
                    (float)(ThreadLocalRandom.current().nextGaussian() * maxSaccade * 0.4f),
                    -maxSaccade, maxSaccade);
            if (crystalProfile.gcdEnforcedSaccade) {
                jitterYaw   = RotationUtil.quantize(jitterYaw,   gcd);
                jitterPitch = RotationUtil.quantize(jitterPitch, gcd);
            }
            yaw  += jitterYaw;
            pitch = Mth.clamp(pitch + jitterPitch, -90f, 90f);
        }
        return new float[]{ yaw, pitch };
    }

    public CrystalAura() {
        super("CrystalAura", "Places obsidian if needed, then crystals opponents you knock up or who stand near obsidian.", Category.COMBAT);
    }

    @Override
    public void onEnable() {
        stage        = Stage.IDLE;
        base         = null;
        target       = null;
        nextAction   = 0L;
        stageStartMs = 0L;
        lastHit      = null;
        lastHitMs    = -50000L;
        skipUntil.clear();
        SilentRotationManager.clear();
        crystalProfile = SilentCrystalProfile.forCurrentServer();
        aimState.configure(crystalProfile.aimNoiseK, crystalProfile.overshootProb, crystalProfile.ewmaAlpha);
        aimState.reset();
        aimBase.clear();
        burstCounter   = 0;
        idleCounter    = 0;
        burstTarget    = ThreadLocalRandom.current().nextInt(
                crystalProfile.burstTicksMin, crystalProfile.burstTicksMax + 1);
        idleTarget     = ThreadLocalRandom.current().nextInt(
                crystalProfile.idleTicksMin, crystalProfile.idleTicksMax + 1);
        inBurst        = true;
        heldAnchor      = null;
        anchorDeferrals = 0;
        SessionFatigue.get().reset();
        // Subscribing is Module.setEnabled's job; doing it here as well installed every handler twice.
    }

    /**
     * Another module took the input lock, so this cycle is abandoned rather than resumed.
     *
     * <p>A freeze leaves {@code stage}, the target and the {@code nextAction} timestamp stale, and
     * resuming on them would continue a crystal cycle chosen before the pause, against a world that
     * has moved on. The slot is put back and the queued rotation dropped for the same reason.
     */
    @Override
    public void onPause() {
        stage        = Stage.IDLE;
        base         = null;
        target       = null;
        nextAction   = 0L;
        stageStartMs = 0L;
        skipUntil.clear();
        SilentRotationManager.clear();
        releaseSlot();
        releaseAnchorHold();
        // The claim belongs to the fight that was in progress; a freeze of unknown length would
        // otherwise have AnchorAura standing down for a hit from before it once events resume.
        lastHit   = null;
        lastHitMs = -50000L;
    }

    @Override
    public void onDisable() {
        releaseSlot();
        releaseAnchorHold();
        SilentRotationManager.clear();
    }

    /**
     * A sword hit on another player opens the knock-up claim: the next cycle is theirs.
     *
     * <p>The hit is the trigger, not the airborne state — see {@link #knockupActive}. A hit that
     * lands on a dead or dying opponent claims nothing.
     */
    // nyx: @EventHandler(priority = EventPriority.HIGHEST) — mist EventHandler has no priority
    @EventHandler
    private void onAttack(AttackEntityEvent event) {
        if (Veil.mc.player == null) return;
        if (event.getEntity() instanceof Player p && p != Veil.mc.player
                && p.isAlive() && !p.isDeadOrDying()
                && Veil.mc.player.getMainHandItem().is(ItemTags.SWORDS)) {
            lastHit   = p;
            lastHitMs = System.currentTimeMillis();
            // A second hit inside the same window must not restart the answer to the first. Sword
            // hits land far faster than a place-and-break takes, and preempting on every hit would
            // keep throwing away the cycle that is already placing and popping at that opponent, so
            // nothing would ever finish.
            if (!answering(p)) preemptForKnockup();
        }
    }

    /**
     * Whether the cycle in flight is already an answer to {@code victim}: a crystal being placed or
     * broken for them right now.
     *
     * <p>{@code target} is only set between the plan being taken and the cycle finishing, and it is
     * the opponent that plan was chosen for, so this is exactly "a cycle running against them".
     */
    private boolean answering(Player victim) {
        return stage != Stage.IDLE && target == victim;
    }

    /**
     * Drops the cycle in flight so the claim can start one immediately.
     *
     * <p>A cycle chosen before the hit was planned against the world as it was, and the cooldown
     * that follows one would spend a good part of the knock-up window doing nothing at all — which
     * is how a hit could land and no crystal follow it. Both are abandoned, and the hotbar with them,
     * so the very next tick plans against the opponent who is now in the air.
     */
    private void preemptForKnockup() {
        stage        = Stage.IDLE;
        base         = null;
        target       = null;
        stageStartMs = 0L;
        nextAction   = 0L;
        SilentRotationManager.clear();
        releaseSlot();
    }

    /**
     * Whether the last sword hit still has a claim on this aura's next cycle.
     *
     * <p>Being airborne cannot be the trigger on its own: the client only learns about knockback
     * when the server's movement updates for the target arrive, so for the first ticks after a hit
     * the target still reads as grounded, and the old airborne-only test dropped exactly the hits it
     * existed for. The hit opens the claim instead; being airborne is required only after
     * {@link #KNOCKUP_GRACE_MS}, by which point a real knock-up has lifted them and one that has not
     * was not a knock-up.
     */
    private boolean knockupActive(long now) {
        if (lastHit == null || !lastHit.isAlive() || lastHit.isDeadOrDying()) return false;
        if (now - lastHitMs > (long) (float) knockupWindow.get()) return false;
        return !lastHit.onGround() || now - lastHitMs <= KNOCKUP_GRACE_MS;
    }

    /**
     * Whether a knock-up hit has an unanswered claim: this aura is about to place and pop a crystal,
     * so AnchorAura should not spend the window on an anchor sequence of its own.
     *
     * <p>False when there is nothing to place with — an anchor is then the only play left, and
     * AnchorAura is free to take it.
     */
    public boolean knockupClaimed() {
        if (!isEnabled() || Veil.mc.player == null || Veil.mc.level == null) return false;
        if (!knockupActive(System.currentTimeMillis())) return false;
        return Veil.mc.player.getOffhandItem().is(Items.END_CRYSTAL) || findCrystalSlot() != -1;
    }

    @EventHandler
    private void onTick(TickEvent.Pre event) {
        tickCounter++;
        if (!Veil.inGame() || Veil.mc.gameMode == null) return;
        if (Veil.mc.gui != null && Veil.mc.gui.screen() != null) { // MC 26.3: screen lives on Gui
            // Nothing clicks behind a screen, and an anchor left held through one would spend its own
            // timeout waiting for a cycle that cannot run.
            releaseAnchorHold();
            return;
        }
        // Non-silent: the turn is walked in a step per tick, so it has to keep moving on the ticks
        // between actions, not only on the tick a click starts it.
        if (!silent.get()) advanceCameraAim(Veil.mc.player);
        // Macro Mode: combat runs only while the module's bind key is held. The cycle in flight is
        // dropped rather than frozen, like onPause, because it was planned against the world from
        // before the key came up; the next hold starts a fresh one. The module itself stays enabled
        // while the key is up, so this branch runs every tick and returnToCamera below is what walks
        // the server's silent view back between holds. A tap never reaches this branch: it disarms
        // the module outright, so the press is not treated as a short fight.
        if (macroMode.get() && !macroHeld()) {
            if (stage != Stage.IDLE || heldAnchor != null) onPause();
            returnToCamera(Veil.mc.player);
            return;
        }
        // No cycle is in flight on these stages, so nothing of this aura's needs the aim: walk the
        // server's view back toward the camera. Placed before the early returns below, because those
        // are exactly the ticks on which nothing acts. Not while an anchor sequence is running, though:
        // it owns the view until its cycle ends, and its aim is a turn of several ticks whose steps a
        // return sent from here would cancel one after another.
        if ((stage == Stage.IDLE || stage == Stage.COOLDOWN) && !anchorSequenceRunning()) {
            returnToCamera(Veil.mc.player);
        }
        if (!burstAllowsAction()) {
            // An idle window: nothing is going out, so a held anchor must not stay frozen through it.
            releaseAnchorHold();
            return;
        }
        if (System.currentTimeMillis() < nextAction) return;
        // A dropped click: the cycle keeps its plan and simply does not act this tick. The anchor hold
        // is left alone, because the next tick carries on with the same crystal.
        if (pacing.get() && ThreadLocalRandom.current().nextFloat() < crystalProfile.skipProbability) return;
        if (!handoffAllowsAction()) return;

        // nyx: for (int i = 0; i < 4 && wait == 0 && !busy; i++)
        // One interaction per tick. nyx's loop could run four stages, and whether that meant two
        // clicks depended on every acting stage scheduling the next action; this makes it structural
        // instead, so no delay setting can put two interactions in the same tick.
        actedThisTick = false;
        for (int i = 0; i < 4 && !actedThisTick && System.currentTimeMillis() >= nextAction; i++) {
            Stage before = stage;
            step();
            if (stage == before) break;
        }
        // A hold only means something while a cycle is in flight. With nothing planned there is no
        // crystal for the anchor to wait for, and keeping it held would freeze it for its timeout.
        if (stage == Stage.IDLE || stage == Stage.COOLDOWN) releaseAnchorHold();
    }

    /**
     * Macro Mode gates combat on the bind key, which {@code ModuleManager} holds down for as long as
     * it is pressed. With no key bound there is nothing to hold, so the gate never opens: that is
     * deliberate, because ungating an unbound module would let it fight with nobody holding a key.
     */
    @Override
    public boolean isMacroGated() { return macroMode.get(); }

    /**
     * Queues and sends one step of a return to the camera.
     *
     * <p>Only on a tick with no cycle in flight, and only while the player is not turning the camera
     * themselves — see {@link SilentRotationManager#stepBackToCamera}.
     */
    private void returnToCamera(LocalPlayer self) {
        if (!rotate.get() || !silent.get()) return;
        // A return step moves the view this module's base was measured from, so the base goes
        // with it and the next aim starts from the camera the return is walking back to.
        if (SilentRotationManager.stepBackToCamera(self, MAX_YAW_PER_TICK, MAX_PITCH_PER_TICK)) {
            aimBase.clear();
        }
    }

    private void step() {
        LocalPlayer self = Veil.mc.player;
        if (self.isDeadOrDying()) return;

        switch (stage) {
            case IDLE -> {
                Plan plan = findPlan(self);
                if (plan == null) return;
                target       = plan.target();
                base         = plan.base();
                lastBase     = base;
                stage        = plan.needsObsidian() ? Stage.PLACE_OBSIDIAN : Stage.PLACE_CRYSTAL;
                stageStartMs = System.currentTimeMillis();
            }
            case PLACE_OBSIDIAN -> {
                Click click = placeObsidianAt(base);
                if (click == Click.REFUSED) { giveUp(PARK_MS); return; }
                // The camera is still walking onto the face: the click waits for the turn to arrive,
                // and the stage keeps its plan for the next tick.
                if (click == Click.TURNING) return;
                stage        = Stage.PLACE_CRYSTAL;
                stageStartMs = System.currentTimeMillis();
                nextAction = System.currentTimeMillis() + (long)(RotationUtil.gaussianDelay(
                        (long)(float)obsidianDelay.get(), crystalProfile.jitterFraction)
                        * SessionFatigue.get().delayMultiplier());
            }
            case PLACE_CRYSTAL -> {
                if (base == null) { giveUp(PARK_MS); return; }
                if (findCrystalOn(base) != null) {
                    stage        = Stage.BREAK;
                    stageStartMs = System.currentTimeMillis();
                    return;
                }
                if (!isCrystalBase(Veil.mc.level.getBlockState(base))) {
                    // Obsidian not confirmed yet (network lag); wait, then give up
                    if (System.currentTimeMillis() - stageStartMs > (long)(float)breakTimeout.get()) {
                        giveUp(PARK_MS);
                    } else {
                        nextAction = System.currentTimeMillis() + 50L;
                    }
                    return;
                }
                // The plan predates the obsidian going down and the delays since; at melee range the
                // target has moved. Placing on a stale plan drops a crystal they have stepped out of —
                // a crystal that does nothing and has to be cleaned up before the next cycle.
                if (!stillWorthIt()) {
                    giveUp(PARK_STALE_MS);
                    return;
                }
                Click click = placeCrystalOn(base);
                if (click == Click.REFUSED) { giveUp(PARK_MS); return; }
                // Mid-turn: nothing has been clicked yet, so the cycle waits instead of moving on to
                // a break for a crystal it never placed.
                if (click == Click.TURNING) return;
                stage        = Stage.BREAK;
                stageStartMs = System.currentTimeMillis();
                nextAction = System.currentTimeMillis() + placeBreakDelay();
            }
            case BREAK -> {
                if (base == null) { giveUp(PARK_MS); return; }
                EndCrystal crystal = findCrystalOn(base);
                if (crystal == null) {
                    // A placement the server keeps refusing lands here: the crystal never shows up,
                    // so what the damage estimate says about this base has not changed and it would be
                    // chosen again on the next tick. Park it and let the next cycle try elsewhere.
                    if (System.currentTimeMillis() - stageStartMs > (long)(float)breakTimeout.get()) giveUp(PARK_MS);
                    else nextAction = System.currentTimeMillis() + 50L;
                    return;
                }
                if (antiSuicide.get() && DamageUtil.crystalDamage(self, crystal.position())
                        >= self.getHealth() + self.getAbsorptionAmount() - 1) {
                    // Refusing to pop it must not leave the planner pointed at it either — from here
                    // the crystal stands and stays the best spot on offer for as long as the opponent
                    // is next to it, so the base is parked like every other give-up.
                    giveUp(PARK_MS);
                    return;
                }
                // The hand goes back to the player for the hit: with the anchor held nobody else is
                // holding the hotbar, so this is what puts the sword back for the break.
                if (waitForAnchor.get()) releaseSlot();
                // A break sent mid-turn would go out on whatever the crosshair crosses while the
                // camera is still moving, so it waits for the turn to arrive too; the crystal is
                // still there on the next tick and the stage keeps its plan until then.
                if (breakCrystal(self, crystal) == Click.TURNING) return;
                finishCycle();
            }
            case COOLDOWN -> stage = Stage.IDLE;
        }
    }

    /**
     * Gives up on the spot in flight and parks that base for {@code parkMs}, so the next plan does
     * not walk straight back into it.
     *
     * <p>Parking is what separates a give-up from a stall. A base is given up on for a reason the
     * planner does not model — a crystal we will not pop, a placement the server keeps refusing, an
     * obsidian that never confirmed — and none of that changes what the damage estimate says about
     * the base: it stays the highest damage on offer and is chosen again on the very next tick. With
     * the base parked, the next cycle goes somewhere it can act. Every give-up comes through here
     * for that reason; only a cycle that did something ends in {@link #finishCycle()}.
     *
     * <p>{@code base} is null on some paths (a cycle torn down under it); parking nothing is then
     * the whole of it, and the cycle still ends.
     */
    private void giveUp(long parkMs) {
        if (base != null) skipUntil.put(base, System.currentTimeMillis() + parkMs);
        finishCycle();
    }

    /**
     * The place -> break gap in milliseconds.
     *
     * <p>Silent: floored at 100 ms, because a break sooner than that is a click the server has not
     * accepted the crystal for yet, and re-drawn when it lands within 50 ms of the previous cycle's
     * gap — the same interval twice in a row is a rhythm a click pattern check can see.
     */
    private long placeBreakDelay() {
        float jitter = crystalProfile.jitterFraction;
        long delay = RotationUtil.gaussianDelay((long)(float)crystalDelay.get(), jitter);
        delay = (long)(delay * SessionFatigue.get().delayMultiplier());
        if (!silent.get()) return delay;
        delay = Math.max(100L, delay);
        if (lastPlaceBreakMs >= 0 && Math.abs(delay - lastPlaceBreakMs) < 50L) {
            delay = Math.max(100L, RotationUtil.gaussianDelay((long)(float)crystalDelay.get(), jitter));
        }
        lastPlaceBreakMs = delay;
        return delay;
    }

    /** Ends a cycle that did something. A give-up goes through {@link #giveUp(long)} to park the base. */
    private void finishCycle() {
        stage    = Stage.COOLDOWN;
        base     = null;
        target   = null;
        // Silent: a hundred milliseconds is the floor here too, so a cycle boundary is not a click the
        // server has not finished the last crystal for.
        long floor = silent.get() ? 100L : 50L;
        long cycleMs = RotationUtil.gaussianDelay((long)(float)cycleDelay.get(), crystalProfile.jitterFraction);
        cycleMs = (long)(cycleMs * SessionFatigue.get().delayMultiplier());
        // Behavioral idle: about one cycle in seven waits an extra one to four seconds before the next
        // one starts, the way a player breaks off to watch where the opponent went instead of clicking
        // on a metronome.
        if (silent.get() && macroMode.get() && ThreadLocalRandom.current().nextFloat() < 0.15f) {
            cycleMs += 1000L + ThreadLocalRandom.current().nextInt(3000);
        }
        nextAction = System.currentTimeMillis() + Math.max(floor, cycleMs);
        releaseAnchorHold();
        releaseSlot();
    }

    /**
     * Lets go of the hotbar, putting the player's own slot back when Swap Back is on.
     *
     * <p>The slot lives in {@link HotbarSlots} rather than in a field here: AnchorAura and
     * AutoSafeAnchor hold the same hotbar, and a module restoring a slot it remembered itself would
     * put back a slot another module had only borrowed.
     */
    private void releaseSlot() {
        HotbarSlots.release(this, swapBack.get());
    }

    /**
     * Advances the burst/idle rhythm by one client tick and reports whether this tick is inside a
     * burst.
     *
     * <p>The windows are counted in ticks, so an idle window of two to four ticks is a pause of a
     * fifth of a second rather than "two to four clicks dropped" — a player's pauses are measured in
     * time, and counting only the ticks an action was due for made the same numbers mean something
     * different on every server.
     */
    private boolean burstAllowsAction() {
        if (!pacing.get()) return true;
        if (inBurst) {
            if (++burstCounter < burstTarget) return true;
            inBurst      = false;
            burstCounter = 0;
            idleTarget   = ThreadLocalRandom.current().nextInt(
                    crystalProfile.idleTicksMin, crystalProfile.idleTicksMax + 1);
            return false;
        }
        if (++idleCounter < idleTarget) return false;
        inBurst     = true;
        idleCounter = 0;
        burstTarget = ThreadLocalRandom.current().nextInt(
                crystalProfile.burstTicksMin, crystalProfile.burstTicksMax + 1);
        return true;
    }

    // ---- Handing the hand to and from AnchorAura ----

    /**
     * Whether this tick's crystal action may go out, taking the hand from AnchorAura first if that is
     * running.
     *
     * <p>Crystals and anchors share one hand. An anchor sequence selects its anchor or glowstone slot
     * for every stage of its sequence, and a crystal cycle selects a crystal or obsidian slot for its
     * own, so with both running the selected slot flips between them every tick: the sword the player
     * is hitting crystals with is in hand for neither the break nor the next anchor stage, which is
     * what makes a hitcrystal feel like it does not register.
     *
     * <p>With Wait For Anchor on, the crystal waits instead of competing. It holds the anchor where it
     * is as soon as the anchor has nothing of its own pending, clicks on the tick after that so the
     * two never act in the same tick, and lets the anchor carry on when the crystal cycle ends. The
     * anchor is held, not dropped, so a sequence half way through place, charge, shield and detonate
     * finishes on the far side of the crystal rather than starting over.
     *
     * <p>A turn is waited out rather than cut short. The anchor's silent aim turns a step at a time, so
     * a stage spends several ticks between two of its clicks with nothing counting down — which the
     * budget below cannot tell apart from a sequence configured with no delays, and it took the hand on
     * the third tick of a turn. Holding a turn away from the anchor throws away the steps it has already
     * sent and makes it rebuild the turn from the crystal's aim, cycle after cycle. A turn is short and
     * bounded by the anchor's own timeout, so it is waited out here and never counted against that
     * budget.
     *
     * <p>The wait is bounded for the other case: a sequence configured with no delays acts every tick
     * and is never seen between operations, and a click held for ever is worse than the two modules
     * taking turns.
     */
    private boolean handoffAllowsAction() {
        AnchorAura anchor = anchorAura();
        if (!waitForAnchor.get() || anchor == null || !anchor.isEnabled() || !anchor.anchorRunning()) {
            releaseAnchorHold();
            return true;
        }
        if (heldAnchor == anchor) return true;
        // Mid-turn: the anchor has work in flight and will be between operations in a tick or two, so
        // wait for it without spending the deferral budget — that budget exists for a sequence which
        // never pauses at all, and its two ticks are shorter than most turns.
        if (anchor.anchorTurning()) {
            nextAction = System.currentTimeMillis() + ANCHOR_HANDOFF_MS;
            return false;
        }
        if (++anchorDeferrals > ANCHOR_WAIT_TICKS || anchor.anchorBetweenOperations()) {
            heldAnchor = anchor;
            anchor.holdForCrystal();
        }
        // Even when the hand was taken just now, the click waits a tick: the anchor's own operation
        // for this tick, if it had one, is then already sent and its next stage cannot land beside ours.
        nextAction = System.currentTimeMillis() + ANCHOR_HANDOFF_MS;
        return false;
    }

    /** Lets a held anchor sequence carry on. Safe to call when nothing is held. */
    private void releaseAnchorHold() {
        anchorDeferrals = 0;
        if (heldAnchor == null) return;
        heldAnchor.resumeAfterCrystal();
        heldAnchor = null;
    }

    /** The AnchorAura instance, if the module is installed. */
    private AnchorAura anchorAura() {
        if (anchorAura == null) {
            for (Module module : ModuleManager.getModules()) {
                if (module instanceof AnchorAura aura) { anchorAura = aura; break; }
            }
        }
        return anchorAura;
    }

    /**
     * Whether an anchor sequence is running anywhere, in which case it owns the server's view.
     *
     * <p>{@link #anchorAura()} is only the automatic one: the manual AutoSafeAnchor aims through the
     * same store, and a return step from here would cancel the turn either of them is in the middle of.
     */
    private boolean anchorSequenceRunning() {
        for (Module module : ModuleManager.getModules()) {
            if (module instanceof AutoSafeAnchor anchor && anchor.anchorRunning()) return true;
        }
        return false;
    }

    // ---- Planning (nyx findPlan, choose, collectSpots — verbatim logic, Meteor calls replaced) ----

    private Plan findPlan(LocalPlayer self) {
        boolean haveCrystals = self.getOffhandItem().is(Items.END_CRYSTAL) || findCrystalSlot() != -1;
        if (!haveCrystals) return null;

        // 1) Someone knocked up with a sword
        long now = System.currentTimeMillis();
        if (knockupActive(now) && validTarget(self, lastHit)) {
            boolean canObs = placeObsidian.get() && findObsidianSlot() != -1;
            List<Plan> spots = new ArrayList<>();
            collectSpots(self, lastHit, canObs, spots);
            Plan p = choose(spots);
            if (p != null) return p;
        }

        // 2) Anyone near existing obsidian/bedrock (nyx: useExisting path)
        if (!useExisting.get()) return null;
        List<Plan> spots = new ArrayList<>();
        for (Player p : Veil.mc.level.players()) {
            if (validTarget(self, p)) collectSpots(self, p, false, spots);
        }
        return choose(spots);
    }

    /**
     * The damage the crystal plan this aura would start against {@code target} deals right now, or 0
     * if it would not start one.
     *
     * <p>Read-only: it asks {@link #findPlan} the same question a cycle asks and touches no cycle
     * state, so asking does not disturb a cycle already in flight. AnchorAura uses it to decide
     * whether to sit a cycle out, and it has to be this plan rather than a scan of its own — the
     * knockup window, Use Existing, the skip list and the damage tolerance are what decide whether a
     * crystal is really on its way.
     */
    /** Who the running cycle is aimed at, or null when there is no cycle. Read by the target HUD. */
    public Player target() { return target; }

    public double plannedDamageAgainst(Player target) {
        LocalPlayer self = Veil.mc.player;
        if (self == null || target == null || Veil.mc.level == null || !isEnabled()) return 0;
        // A screen stops this aura acting at all (see onTick), so it must stop answering as if it
        // would: otherwise AnchorAura would sit out cycles while nothing was placing crystals.
        if (Veil.mc.gui != null && Veil.mc.gui.screen() != null) return 0;
        Plan plan = findPlan(self);
        return plan != null && plan.target() == target ? plan.damage() : 0;
    }

    /**
     * Picks the spot that hurts the most.
     *
     * <p>Damage leads. Damage Tolerance is the only allowance — spots more than that below the best
     * one are dropped outright — and within that allowance the most damage wins. The turn needed to
     * look at the spot and the obsidian it would need are tie-breakers, priced small enough that they
     * can never trade away a real hit: a full 180° turn costs under one damage, a placed obsidian a
     * quarter of one. The old cost function ranked by turn first and used damage as a coarse floor,
     * so it would pick a shielded spot a few degrees closer.
     *
     * <p>{@code lastBase} stays sticky so two equal spots do not flip the aim back and forth, but only
     * while it is still within one damage of the best: a stale favourite must not pin the aura to a
     * spot the target has since stepped out of.
     */
    private Plan choose(List<Plan> spots) {
        if (spots.isEmpty()) return null;
        double bestDmg = 0;
        for (Plan p : spots) bestDmg = Math.max(bestDmg, p.damage());
        double floor = bestDmg - damageTolerance.get();
        double stickyFloor = Math.max(floor, bestDmg - 1.0);

        for (Plan p : spots) {
            if (p.base().equals(lastBase) && p.damage() >= stickyFloor) return p;
        }

        Plan best = null;
        double bestScore = Double.NEGATIVE_INFINITY;
        for (Plan p : spots) {
            if (p.damage() < floor) continue;
            double score = p.damage() - p.turn() * 0.005 - (p.needsObsidian() ? 0.25 : 0.0);
            if (score > bestScore) { bestScore = score; best = p; }
        }
        return best;
    }

    private void collectSpots(LocalPlayer self, Player t, boolean allowObsidian, List<Plan> out) {
        BlockPos center = t.blockPosition();
        long now = System.currentTimeMillis();
        // nyx: skipUntil.values().removeIf(ts -> ts <= ticks)
        skipUntil.values().removeIf(ts -> ts <= now);

        for (int x = -3; x <= 3; x++) {
            for (int z = -3; z <= 3; z++) {
                for (int y = -3; y <= 1; y++) {
                    BlockPos pos = center.offset(x, y, z);
                    // nyx: CombatHelper.inReach(Vec3.atCenterOf(pos), placeRange) → eye-distance
                    if (self.getEyePosition().distanceTo(Vec3.atCenterOf(pos)) > placeRange.get()) continue;
                    Long until = skipUntil.get(pos);
                    if (until != null && until > now) continue;

                    BlockState state = Veil.mc.level.getBlockState(pos);
                    // nyx: CombatHelper.isCrystalBase(state)
                    boolean existing = isCrystalBase(state);
                    if (!existing) {
                        if (!allowObsidian) continue;
                        if (thickSnow(state)) continue;
                        // nyx: BlockUtils.canPlaceBlock(pos, true, Blocks.OBSIDIAN)
                        if (!canPlaceBlockAt(pos, state)) continue;
                        // nyx: replaceClick check and CombatHelper.supportSide check — the obsidian has
                        // to land on this exact block, so either the block here is replaced in place or
                        // the click goes on a solid neighbour (findSupportSide refuses the ones that
                        // swallow the click and shift the obsidian off this block).
                        if (!replaceClick(pos) && findSupportSide(pos) == null) continue;
                        // A block put into a body displaces it, or is refused by the server; either
                        // way the crystal ends up somewhere the estimate never covered.
                        if (blockedByEntity(pos)) continue;
                        if (avoidPath.get() && inLandingPath(t, pos)) continue;
                    }

                    BlockPos above = pos.above();
                    if (!Veil.mc.level.isEmptyBlock(above)) continue;
                    AABB box = new AABB(above.getX(), above.getY(), above.getZ(),
                                       above.getX() + 1, above.getY() + 2, above.getZ() + 1);
                    if (!Veil.mc.level.getEntities((Entity) null, box,
                            e -> e.isAlive() && !(e instanceof EndCrystal)).isEmpty()) continue;

                    Vec3 crystalPos = new Vec3(pos.getX() + 0.5, pos.getY() + 1, pos.getZ() + 0.5);
                    double dmg   = existing ? DamageUtil.crystalDamage(t, crystalPos)
                                            : DamageUtil.crystalDamage(t, crystalPos, false, pos);
                    if (dmg < minDamage.get()) continue;
                    double self2 = existing ? DamageUtil.crystalDamage(self, crystalPos)
                                            : DamageUtil.crystalDamage(self, crystalPos, false, pos);
                    if (self2 > maxSelfDamage.get()) continue;
                    // nyx: PlayerUtils.getTotalHealth() → getHealth() + absorption
                    if (antiSuicide.get() && self2 >= self.getHealth() + self.getAbsorptionAmount() - 1) continue;

                    // nyx: CombatHelper.angleTo(crystalPos)
                    out.add(new Plan(t, pos, !existing, dmg, angleTo(self, crystalPos)));
                }
            }
        }
    }

    private boolean validTarget(LocalPlayer self, Player p) {
        if (p == self || !p.isAlive() || p.isSpectator() || p.isCreative()) return false;
        return self.distanceTo(p) <= targetRange.get();
    }

    // nyx: inLandingPath — verbatim (uses Mojmap getDeltaMovement(), xo/zo fields)
    private boolean inLandingPath(Player t, BlockPos pos) {
        double rx = pos.getX() + 0.5 - t.getX();
        double rz = pos.getZ() + 0.5 - t.getZ();
        double clearance = pathClearance.get();

        Vec3 vel = t.getDeltaMovement();
        double speed = Math.sqrt(vel.x * vel.x + vel.z * vel.z);
        if (speed < 0.05) {
            vel = new Vec3(t.getX() - t.xo, 0, t.getZ() - t.zo);
            speed = Math.sqrt(vel.x * vel.x + vel.z * vel.z);
        }
        if (speed < 0.05) return rx * rx + rz * rz < clearance * clearance;

        double dx = vel.x / speed, dz = vel.z / speed;
        double along  = rx * dx + rz * dz;
        double across = Math.abs(rx * dz - rz * dx);
        if (rx * rx + rz * rz < clearance * clearance) return true;
        return along > -0.5 && across < clearance;
    }

    // ---- Actions (nyx placeObsidianAt, placeCrystalOn, breakCrystal — live-inputs branch dropped) ----

    private Click placeObsidianAt(BlockPos pos) {
        int obsSlot = findObsidianSlot();
        if (obsSlot == -1) return Click.REFUSED;
        BlockState state = Veil.mc.level.getBlockState(pos);
        if (!canPlaceBlockAt(pos, state)) return Click.REFUSED;

        BlockHitResult click;
        if (replaceClick(pos)) {
            // nyx: snow layer / grass: click directly (replace semantics)
            click = new BlockHitResult(Vec3.atCenterOf(pos).add(0, 0.5, 0), Direction.UP, pos, false);
        } else {
            // nyx: CombatHelper.supportSide(pos) + CombatHelper.placeClick(pos, side)
            Direction side = findSupportSide(pos);
            if (side == null) return Click.REFUSED;
            BlockPos neighbor = pos.relative(side);
            Direction clickFace = side.getOpposite();
            Vec3 hitVec = Vec3.atCenterOf(neighbor).add(
                clickFace.getStepX() * 0.5, clickFace.getStepY() * 0.5, clickFace.getStepZ() * 0.5);
            click = new BlockHitResult(hitVec, clickFace, neighbor, false);
        }

        // Turn first, slot second: a click that has to wait several ticks for the camera would
        // otherwise leave the hotbar on the obsidian for the whole turn.
        if (!aim(Veil.mc.player, click.getLocation())) return Click.TURNING;
        HotbarSlots.select(this, obsSlot);
        Veil.mc.gameMode.useItemOn(Veil.mc.player, InteractionHand.MAIN_HAND, click);
        actedThisTick = true;
        if (swing.get()) Veil.mc.player.swing(InteractionHand.MAIN_HAND, SwingAnimation.DEFAULT, false);
        return Click.MADE;
    }

    private Click placeCrystalOn(BlockPos pos) {
        // The base was confirmed when the plan was made, but the click happens after the delays and
        // the rotation: an EnderEye or anything else that replaced the block in between would have
        // the crystal go down on nothing. Re-check the support immediately before the click.
        if (!isCrystalBase(Veil.mc.level.getBlockState(pos))) return Click.REFUSED;
        // nyx: InvUtils.testInOffHand → offhand item check; InvUtils.findInHotbar → slot scan
        boolean offhand = Veil.mc.player.getOffhandItem().is(Items.END_CRYSTAL);
        int crystalSlot = findCrystalSlot();
        if (!offhand && crystalSlot == -1) return Click.REFUSED;

        double sx = pos.getX() + 0.5 + (HR.nextDouble() * 0.3 - 0.15);
        double sy = pos.getY() + 1.0;
        double sz = pos.getZ() + 0.5 + (HR.nextDouble() * 0.3 - 0.15);
        BlockHitResult click = new BlockHitResult(new Vec3(sx, sy, sz), Direction.UP, pos, false);

        int slot;
        if (offhand) {
            // nyx: harmlessSlot() — slot that won't right-click-use on a block. The crystal goes out
            // on the offhand, so the main hand is never used for this click: when no harmless slot
            // is free, the selected one is no worse. Refusing there instead made the aura cycle
            // forever on a plan it could not place, which now also parks AnchorAura.
            slot = harmlessSlot();
            if (slot == -1) slot = Veil.mc.player.getInventory().getSelectedSlot();
        } else {
            slot = crystalSlot;
        }

        // Turn first, slot second: a click that has to wait several ticks for the camera would
        // otherwise leave the hotbar on the crystal for the whole turn.
        if (!aim(Veil.mc.player, click.getLocation())) return Click.TURNING;
        HotbarSlots.select(this, slot);
        InteractionHand hand = offhand ? InteractionHand.OFF_HAND : InteractionHand.MAIN_HAND;
        Veil.mc.gameMode.useItemOn(Veil.mc.player, hand, click);
        actedThisTick = true;
        if (swing.get()) Veil.mc.player.swing(hand, SwingAnimation.DEFAULT, false);
        return Click.MADE;
    }

    private Click breakCrystal(LocalPlayer self, EndCrystal crystal) {
        if (!crystal.isAlive()) return Click.REFUSED;
        if (!aim(self, crystal.getBoundingBox().getCenter())) return Click.TURNING;
        // nyx: mc.gameMode.attack + SwingMode.Both → swing first, then gameMode.attack
        if (swing.get()) self.swing(InteractionHand.MAIN_HAND, SwingAnimation.DEFAULT, false);
        Veil.mc.gameMode.attack(self, crystal);
        actedThisTick = true;
        return Click.MADE;
    }

    // ---- Helpers (nyx: replaceClick, thickSnow, harmlessSlot, findCrystalOn — verbatim) ----

    // nyx: replaceClick — snow layers, grass etc.: catch crosshair, click directly
    private boolean replaceClick(BlockPos pos) {
        BlockState state = Veil.mc.level.getBlockState(pos);
        return state.canBeReplaced() && state.getFluidState().isEmpty()
            && !state.getShape(Veil.mc.level, pos).isEmpty();
    }

    private static boolean thickSnow(BlockState state) {
        return state.is(Blocks.SNOW) && state.getValue(BlockStateProperties.LAYERS) > 1;
    }

    // nyx: CombatHelper.isCrystalBase
    private static boolean isCrystalBase(BlockState state) {
        return state.is(Blocks.OBSIDIAN) || state.is(Blocks.BEDROCK);
    }

    // nyx: BlockUtils.canPlaceBlock (simplified: air or replaceable non-fluid)
    private static boolean canPlaceBlockAt(BlockPos pos, BlockState state) {
        return state.isAir() || (state.canBeReplaced() && state.getFluidState().isEmpty());
    }

    /**
     * A solid neighbour to click so the obsidian lands on {@code pos} itself.
     *
     * <p>Blocks that would swallow the click are skipped: a replaceable neighbour (grass, a snow layer)
     * is replaced by the held block, so the obsidian lands on that neighbour instead of against it, and
     * one whose right-click opens or toggles something (chest, door, crafter) spends the click without
     * placing anything. Both used to cost a whole cycle — the crystal then waits on a base that never
     * became obsidian. Neighbours that only replace themselves while standing are not filtered here;
     * the base check in the place stage covers a miss.
     */
    private static Direction findSupportSide(BlockPos pos) {
        for (Direction dir : Direction.values()) {
            BlockState nState = Veil.mc.level.getBlockState(pos.relative(dir));
            if (nState.isAir() || nState.canBeReplaced()) continue;
            if (!nState.getFluidState().isEmpty()) continue;
            if (AutoSafeAnchor.isClickable(nState.getBlock())) continue;
            return dir;
        }
        return null;
    }

    /** True if a block at {@code pos} would overlap an entity: a placement to skip, not to plan around. */
    private static boolean blockedByEntity(BlockPos pos) {
        AABB box = new AABB(pos);
        for (Entity e : Veil.mc.level.getEntities((Entity) null, box.inflate(1.0),
                entity -> entity.isAlive() && !entity.isSpectator()
                        && (entity instanceof LivingEntity || entity instanceof EndCrystal))) {
            if (e.getBoundingBox().intersects(box)) return true;
        }
        return false;
    }

    /**
     * Re-checks the chosen spot at the moment the crystal would go down: the target is still there and
     * still worth hurting, and the blast is still safe for us.
     *
     * <p>Everything in here was true when the plan was made, one obsidian placement and several delays
     * ago; nothing guarantees it is true now.
     */
    private boolean stillWorthIt() {
        LocalPlayer self = Veil.mc.player;
        Player t = target;
        if (t == null || base == null) return false;
        if (!t.isAlive() || t.isDeadOrDying() || t.isSpectator() || t.isCreative()) return false;
        if (self.distanceTo(t) > targetRange.get()) return false;

        Vec3 crystalPos = new Vec3(base.getX() + 0.5, base.getY() + 1, base.getZ() + 0.5);
        double dealt = DamageUtil.crystalDamage(t, crystalPos);
        if (dealt < minDamage.get()) return false;

        double taken = DamageUtil.crystalDamage(self, crystalPos);
        if (taken > maxSelfDamage.get()) return false;
        return !antiSuicide.get() || taken < self.getHealth() + self.getAbsorptionAmount() - 1;
    }

    // nyx: CombatHelper.angleTo — angular distance from player's current view to target
    private static double angleTo(LocalPlayer self, Vec3 target) {
        double dx    = target.x - self.getX();
        double dz    = target.z - self.getZ();
        double dy    = target.y - self.getEyeY();
        double hDist = Math.sqrt(dx * dx + dz * dz);
        float yaw    = (float) Math.toDegrees(Math.atan2(-dx, dz));
        float pitch  = (float) Math.toDegrees(-Math.atan2(dy, hDist));
        float dYaw   = Math.abs(Mth.wrapDegrees(yaw - self.getYRot()));
        float dPitch = Math.abs(pitch - self.getXRot());
        return Math.sqrt(dYaw * dYaw + dPitch * dPitch);
    }

    // nyx: InvUtils.findInHotbar(Items.END_CRYSTAL)
    private int findCrystalSlot() {
        for (int i = 0; i < 9; i++) {
            if (Veil.mc.player.getInventory().getItem(i).is(Items.END_CRYSTAL)) return i;
        }
        return -1;
    }

    // nyx: InvUtils.findInHotbar(Items.OBSIDIAN)
    private int findObsidianSlot() {
        for (int i = 0; i < 9; i++) {
            if (Veil.mc.player.getInventory().getItem(i).is(Items.OBSIDIAN)) return i;
        }
        return -1;
    }

    // nyx: harmlessSlot — slot whose item won't right-click-use on a block (empty or sword)
    private int harmlessSlot() {
        int current = Veil.mc.player.getInventory().getSelectedSlot();
        net.minecraft.world.item.ItemStack held = Veil.mc.player.getInventory().getItem(current);
        if (held.isEmpty() || held.is(ItemTags.SWORDS)) return current;
        for (int i = 0; i < 9; i++) {
            net.minecraft.world.item.ItemStack stack = Veil.mc.player.getInventory().getItem(i);
            if (stack.isEmpty() || stack.is(ItemTags.SWORDS)) return i;
        }
        return -1;
    }

    // nyx: InvUtils.swap → HotbarSlots, which keeps the player's own slot across every module that
    // is holding the hotbar at once.

    // nyx: findCrystalOn — verbatim (entity scan above base block)
    private EndCrystal findCrystalOn(BlockPos pos) {
        if (pos == null) return null;
        BlockPos above = pos.above();
        AABB box = new AABB(above.getX(), above.getY(), above.getZ(),
                            above.getX() + 1, above.getY() + 2, above.getZ() + 1);
        for (Entity e : Veil.mc.level.getEntities((Entity) null, box, e -> e instanceof EndCrystal && e.isAlive())) {
            return (EndCrystal) e;
        }
        return null;
    }

    @Override
    public String suffix() {
        return target == null ? null : target.getName().getString();
    }
}
