// lang: Java, file: AutoSafeAnchor.java, target: Fabric MC 26.3
// Port of nyx AutoSafeAnchor (com.nyx.modules.AutoSafeAnchor).
//
// Dropped from the nyx original because mist has no equivalent layer: live-input clicks
// (CombatHelper.Live), the camera glide, the SilentMode presets and the validation harness. What is
// kept is the sequence and every decision in it: where the anchor goes, when it is charged, where the
// glowstone shield has to sit so the blast misses you, the crouch-place case where the anchor itself
// is the only support, and the verify/retry passes for a dropped click.
//
// Rotation goes through SilentRotationManager and its explicit flush, the same queue every other
// silent-aiming module uses, so the server has the aim before the click instead of relying on tick
// ordering.
package com.veil.module.modules.combat;

import com.mojang.blaze3d.platform.InputConstants;
import com.veil.Veil;
import com.veil.event.EventHandler;
import com.veil.event.events.TickEvent;
import com.veil.module.Category;
import com.veil.module.Module;
import com.veil.setting.BoolSetting;
import com.veil.setting.IntSetting;
import com.veil.util.HotbarSlots;
import com.veil.util.RotationUtil;
import com.veil.util.SessionFatigue;
import com.veil.util.SilentAimBase;
import com.veil.util.SilentAimState;
import com.veil.util.SilentRotationManager;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.boss.enderdragon.EndCrystal;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.BlockItem;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.component.SwingAnimation;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.ButtonBlock;
import net.minecraft.world.level.block.DoorBlock;
import net.minecraft.world.level.block.EntityBlock;
import net.minecraft.world.level.block.FenceGateBlock;
import net.minecraft.world.level.block.LeverBlock;
import net.minecraft.world.level.block.RespawnAnchorBlock;
import net.minecraft.world.level.block.TrapDoorBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.BlockStateProperties;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.phys.Vec3;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.concurrent.ThreadLocalRandom;

/**
 * One press, one anchor: place a respawn anchor, charge it with glowstone, wall the blast off with a
 * glowstone shield, detonate, then turn itself off.
 *
 * <p>The sequence is the nyx one, stage for stage, on a tick clock: {@code wait} counts ticks before
 * the next stage, and each configured delay gets a random extra so the timing is not a fixed number.
 * Every action is a normal right-click on a block face — nothing is placed with hand-built packets.
 *
 * <p>If the only support for the shield is the anchor itself, it crouches for a tick so the click
 * places glowstone instead of charging the anchor, then stands back up before detonating (a crouched
 * click on an anchor does not detonate it).
 *
 * <p>If no shield spot exists at all it detonates straight after charging; if a spot existed but the
 * glowstone never went down, it cancels by default rather than take the blast.
 */
public class AutoSafeAnchor extends Module {

    // ---- Settings (delays are ticks) ----

    protected final IntSetting placeDelay     = register(new IntSetting("Place Delay",  1, 0, 20));
    protected final IntSetting chargeDelay    = register(new IntSetting("Charge Delay", 1, 0, 20));
    protected final IntSetting shieldDelay    = register(new IntSetting("Shield Delay", 1, 0, 20));
    protected final IntSetting randomExtra    = register(new IntSetting("Random Extra",  1, 0, 10));
    protected final BoolSetting rotate        = register(new BoolSetting("Rotate", true));
    protected final BoolSetting silent        = register(new BoolSetting("Silent",           true));
    protected final BoolSetting swing         = register(new BoolSetting("Swing", true));
    protected final BoolSetting swapBack      = register(new BoolSetting("Swap Back", true));
    protected final BoolSetting crouchPlace   = register(new BoolSetting("Crouch Place", true));
    protected final BoolSetting useExisting   = register(new BoolSetting("Use Existing", true));
    // Macro Mode: the bind key becomes a hold key rather than an on/off switch. AnchorAura inherits
    // this, so both anchor modules are held the same way CrystalAura is.
    protected final BoolSetting macroMode     = register(new BoolSetting("Macro Mode", false));
    protected final IntSetting  shieldRetries = register(new IntSetting("Shield Retries", 2, 0, 5));
    protected final BoolSetting detonateUnshielded =
            register(new BoolSetting("Detonate If Shield Fails", false));
    protected final IntSetting  verifyDelay   = register(new IntSetting("Detonate Check", 4, 2, 20));
    protected final IntSetting  timeout       = register(new IntSetting("Timeout", 60, 5, 200));

    /** Largest yaw/pitch change a single silent rotation may make, in degrees. */
    private static final float MAX_SILENT_YAW_STEP   = 45.0f;
    private static final float MAX_SILENT_PITCH_STEP = 30.0f;

    /**
     * How far the view the server has may still be from the target, in degrees, before a stage's click
     * is allowed out. Below half a mouse step, so a clicked block is the block that was aimed at.
     */
    private static final float AIM_CONVERGED_DEG = 0.5f;

    /** Walks the aim to a target over several ticks; per-server constants are applied by AnchorAura. */
    protected final SilentAimState aimState = new SilentAimState();
    /** The angle this module last put on the wire; each silent step is measured from it. */
    private final SilentAimBase aimBase = new SilentAimBase();

    protected enum Stage { PLACE, CHARGE, SHIELD, SNEAK_PLACE, UNSNEAK, SHIELD_CHECK, DETONATE, VERIFY, DONE }

    /**
     * What a stage's action managed to do.
     *
     * <p>{@code AIMING} is not a failure: the silent aim only turns a fraction of the way per tick, so
     * the stage stays where it is and the action is tried again once the aim has arrived. Clicking on
     * the tick the step went out would act on wherever the server last saw the player looking, which
     * on a turn larger than one step is short of the block that was meant.
     *
     * <p>{@code REFUSED} is the real failure: there is nothing to click with, or nowhere to click.
     */
    private enum Action { CLICKED, AIMING, REFUSED }

    /**
     * Where the shield goes and how to click it: {@code supportSide} is the side of the block to place
     * against (null with {@code replace} means a full block already sits in the way),
     * {@code needsSneak} is the place-against-the-anchor case, {@code replace} is the
     * click-a-snow-layer case.
     */
    protected record ShieldPlan(BlockPos pos, Direction supportSide, boolean needsSneak, boolean replace) {}

    // ---- Sequence state ----

    protected Stage stage;
    protected BlockPos anchorPos;
    protected BlockHitResult aimHit;      // the face the player was looking at when the bind was pressed
    private ShieldPlan pendingShield;
    private int wait;
    private int ticksRunning;
    private boolean actedThisTick;        // an interaction has already gone out this tick
    /** Holds the sequence where it is for a CrystalAura handoff instead of dropping it. */
    private boolean heldForCrystal;
    /**
     * A step of the aim has gone out and the stage is waiting for the view to arrive before its click.
     *
     * <p>Mid-turn is not a pause. Nothing has been clicked, the stage has not advanced and no wait tick
     * is counting, so the sequence looks idle to anything that only asks whether a delay is running —
     * but the turn is the stage's whole operation so far. Taking the hand here would throw the steps
     * already sent away and rebuild the turn from the other module's aim, which is why a turn is
     * reported as work in flight rather than as a gap between operations.
     */
    private boolean turning;
    private boolean pressedSneak;
    private boolean forcedStand;          // a crouch of the player's own that we let go of
    private int standTick = -1;
    private int chargeAttempts;
    private int detonateAttempts;
    private boolean chargeClicked;        // the charge click has been sent
    private boolean chargeFailed;         // ...but it placed glowstone instead of charging
    private BlockPos shieldTarget;
    private int shieldAttempts;
    private int shieldClickTick = -1;     // player tick of the shield click (-1 = not yet)
    private int shieldCheckWaits;
    private final Set<BlockPos> failedShields = new HashSet<>();

    public AutoSafeAnchor() {
        this("AutoSafeAnchor",
             "One press: places a respawn anchor where you look, charges it, shields the blast with glowstone and detonates it.");
    }

    /** For the automatic variant, which reuses this whole sequence with its own placement logic. */
    protected AutoSafeAnchor(String name, String description) {
        super(name, description, Category.COMBAT);
    }

    // ---- Lifecycle ----

    @Override
    public void onEnable() {
        resetSequence();
        onActivated();
    }

    @Override
    public void onDisable() {
        endSequence();
    }

    @Override
    public void onPause() {
        endSequence();
    }

    /** Macro Mode holds this module (and AnchorAura) on the bind key instead of toggling it. */
    @Override
    public boolean isMacroGated() { return macroMode.get(); }

    protected final void resetSequence() {
        // A sequence cut short can still be standing here holding the sneak key down. Letting go is
        // what keeps that from surviving into the next sequence as a player who cannot stand up.
        releaseSneak();
        stage = null;
        anchorPos = null;
        aimHit = null;
        pendingShield = null;
        wait = 0;
        ticksRunning = 0;
        pressedSneak = false;
        forcedStand = false;
        heldForCrystal = false;
        turning = false;
        standTick = -1;
        chargeAttempts = 0;
        detonateAttempts = 0;
        chargeClicked = false;
        chargeFailed = false;
        shieldTarget = null;
        shieldAttempts = 0;
        shieldClickTick = -1;
        shieldCheckWaits = 0;
        failedShields.clear();
    }

    protected final boolean sequenceActive() {
        return stage != null && stage != Stage.DONE;
    }

    /**
     * Whether an anchor operation is in flight: a stage of the sequence is running.
     *
     * <p>CrystalAura asks this to decide whether its own click has to wait — an anchor half way through
     * place, charge, shield and detonate is holding the hotbar for the whole of it.
     */
    public final boolean anchorRunning() {
        return sequenceActive();
    }

    /**
     * Whether the sequence is in the middle of a turn: a step of the silent aim has gone out and the
     * click it is for has not.
     *
     * <p>A turn is short and bounded by this sequence's own timeout, unlike a stage that acts every
     * tick with no pause between operations, so a handoff can sensibly wait one out instead of counting
     * it against the ticks after which the hand is taken anyway.
     */
    public final boolean anchorTurning() {
        return sequenceActive() && turning;
    }

    /**
     * Whether nothing of the sequence's own is about to go out: a delay is counting down and no crouch
     * of its own is down.
     *
     * <p>Between operations is the only point where the hand can be taken from the sequence without
     * cutting a stage in half. Taking it while a stage is due drops that stage's operation, and taking
     * it while crouch-placing leaves a player held down inside a sequence that is not running.
     *
     * <p>Never true mid-turn, because a turn has no delay counting; {@link #anchorTurning()} is what
     * reports that state, and a caller that only asks this question takes a turn for a pause.
     */
    public final boolean anchorBetweenOperations() {
        return sequenceActive() && !heldForCrystal && wait > 0 && !pressedSneak;
    }

    /**
     * Holds the sequence where it is so CrystalAura can use the hand, and gives the hotbar back.
     *
     * <p>The sequence is held, not dropped: {@link #resumeAfterCrystal()} carries it on from the same
     * stage with the same attempt counters, which is what keeps a placed anchor from being abandoned
     * before it is charged. The hotbar is released so the sword the player is hitting crystals with is
     * back in hand for the crystal's own click.
     */
    public final void holdForCrystal() {
        if (!sequenceActive() || heldForCrystal) return;
        heldForCrystal = true;
        HotbarSlots.release(this, swapBack.get());
    }

    /** Lets a held sequence carry on from where it was held. */
    public final void resumeAfterCrystal() {
        heldForCrystal = false;
    }

    /** Drives one tick of the sequence. Called by each concrete module's own tick handler. */
    protected final void sequenceTick() {
        if (!sequenceActive()) return;
        if (++ticksRunning > timeout.get()) {
            abort("timed out, sequence aborted");
            return;
        }
        // Held for a CrystalAura handoff: frozen here, not dropped. The timeout above keeps counting,
        // so a hold that is never given back ends the sequence instead of stranding it on this stage.
        if (heldForCrystal) return;
        // A dead player or an open screen cannot click; hold the sequence rather than spend it.
        if (Veil.mc.player.isDeadOrDying()) return;
        if (Veil.mc.gui != null && Veil.mc.gui.screen() != null) return;
        if (wait > 0) {
            wait--;
            // Nothing is clicked on a wait tick, so the server's view walks back toward the camera.
            returnRotation();
            return;
        }
        step();
    }

    // ---- Start ----

    protected final boolean atWorld() {
        return Veil.inGame() && Veil.mc.gameMode != null;
    }

    /** Anchors are inert in the Nether: the dimension check is the first thing every start does. */
    protected final boolean anchorsWorkHere() {
        return Veil.mc.level != null && Veil.mc.level.dimension() != Level.NETHER;
    }

    /**
     * Starts a sequence on {@code anchor}.
     *
     * <p>Runs items, reach, dimension and the first stage up front: a sequence that cannot finish is
     * not started at all, so the module never sits half-open on a missing item.
     *
     * @return true if the sequence started
     */
    protected final boolean beginAt(BlockPos anchor) {
        LocalPlayer self = Veil.mc.player;
        if (anchor == null || self == null || Veil.mc.level == null || Veil.mc.gameMode == null) return false;
        if (!anchorsWorkHere()) { abort("anchors do not explode in the Nether"); return false; }
        if (!inRange(anchor)) { abort("target is out of reach"); return false; }

        BlockState state = Veil.mc.level.getBlockState(anchor);
        boolean isAnchor = state.is(Blocks.RESPAWN_ANCHOR);
        boolean charged = isAnchor
                && state.getValue(BlockStateProperties.RESPAWN_ANCHOR_CHARGES) > 0;

        if (!isAnchor && !hasItem(Items.RESPAWN_ANCHOR)) {
            abort("no respawn anchor in the hotbar");
            return false;
        }
        if (!charged && !hasItem(Items.GLOWSTONE)) {
            abort("no glowstone in the hotbar");
            return false;
        }

        anchorPos = anchor;
        stage = charged ? Stage.SHIELD : (isAnchor ? Stage.CHARGE : Stage.PLACE);
        // A crouched click on an anchor charges or places the held item instead of using the anchor.
        standUp();
        step();
        return true;
    }

    // ---- Tick driver for the one-shot module ----

    @EventHandler
    private void onTick(TickEvent.Pre event) {
        if (!atWorld()) return;
        // Macro Mode: the key coming up drops the sequence instead of letting it finish on a plan
        // made before the key was released. Same reasoning as onPause, and the same reset.
        //
        // The module is armed once and stays enabled between holds — the GUI switch is what puts it
        // away — so this branch keeps running while the key is up, which is what lets the server's
        // silent view walk back to the camera instead of being left on the last anchor.
        if (macroMode.get() && !macroHeld()) {
            if (sequenceActive()) onPause();
            returnRotation();
            return;
        }
        if (!sequenceActive()) {
            if (macroMode.get()) {
                // Armed and held with nothing running: the last sequence finished, so this starts the
                // next one from where the player is looking. Switching the module off here and
                // letting the key poll arm it again a tick later would write the config on every
                // sequence and throw away the session state each time.
                onActivated();
                return;
            }
            onCycleEnded();
            return;
        }
        sequenceTick();
    }

    /** One press is one sequence: when it ends, the module turns itself off. */
    protected void onCycleEnded() {
        setEnabled(false);
    }

    private void startFromLook() {
        LocalPlayer self = Veil.mc.player;
        if (self == null || Veil.mc.level == null) return;
        if (!anchorsWorkHere()) { abort("anchors do not explode in the Nether"); return; }

        if (!(Veil.mc.hitResult instanceof BlockHitResult hit)
                || hit.getType() != HitResult.Type.BLOCK) {
            abort("you are not looking at a block");
            return;
        }

        BlockPos looked = hit.getBlockPos();
        BlockState state = Veil.mc.level.getBlockState(looked);
        aimHit = hit;

        BlockPos anchor;
        if (state.is(Blocks.RESPAWN_ANCHOR) && useExisting.get()) {
            anchor = looked;
        } else {
            anchor = state.canBeReplaced() ? looked : looked.relative(hit.getDirection());
        }
        beginAt(anchor);
    }

    /** One press runs straight from what the player is looking at; AnchorAura overrides this. */
    protected void onActivated() {
        startFromLook();
    }

    // ---- Stage machine (nyx step(), live-click branches removed) ----

    private void step() {
        // At most one pass through the stages, so a 0/0/0 sequence resolves in a single tick without
        // looping forever when a stage cannot advance.
        // One interaction per tick: a stage that acts ends the loop whatever the delays are set to.
        actedThisTick = false;
        for (int i = 0; i < Stage.values().length && sequenceActive() && wait == 0
                && !actedThisTick; i++) {
            switch (stage) {
                case PLACE -> {
                    switch (placeAnchor()) {
                        case REFUSED -> { abort("couldn't place the anchor there"); return; }
                        // Still turning: stay on this stage and aim the rest of the way next tick.
                        case AIMING -> { return; }
                        case CLICKED -> {
                            stage = Stage.CHARGE;
                            wait = delay(placeDelay);
                        }
                    }
                }
                case CHARGE -> {
                    if (!readyStanding()) return;
                    switch (charge()) {
                        case REFUSED -> { abort("couldn't charge the anchor (out of glowstone?)"); return; }
                        case AIMING -> { return; }
                        case CLICKED -> {
                            // Counted only now: a tick spent turning is not a charge attempt.
                            chargeAttempts++;
                            stage = Stage.SHIELD;
                            wait = delay(chargeDelay);
                        }
                    }
                }
                case SHIELD -> {
                    if (chargeClicked) {
                        if (!Veil.mc.level.getBlockState(anchorPos).is(Blocks.RESPAWN_ANCHOR)) {
                            abort("the anchor isn't there any more");
                            return;
                        }
                        if (chargeFailed) {
                            if (chargeAttempts >= 3) { abort("couldn't charge the anchor"); return; }
                            chargeClicked = false;
                            chargeFailed = false;
                            stage = Stage.CHARGE;
                            wait = 1;
                            return;
                        }
                    }

                    ShieldPlan plan = hasItem(Items.GLOWSTONE) ? planShield() : null;
                    if (plan == null) {
                        if (shieldAttempts > 0 && !detonateUnshielded.get()) {
                            abort("couldn't place the glowstone shield, cancelled so you don't take the blast");
                            return;
                        }
                        // No room for a shield (or no glowstone left): detonate straight away.
                        stage = Stage.DETONATE;
                        wait = 0;
                    } else if (plan.supportSide() == null && !plan.replace()) {
                        // A full block already sits between the blast and you: nothing to place.
                        stage = Stage.DETONATE;
                        wait = delay(shieldDelay);
                    } else if (!plan.needsSneak()) {
                        // The plan is worked out again next tick if the aim is still on its way, which
                        // costs one pass and keeps the click tied to the spot it was planned for.
                        if (placeShield(plan) == Action.AIMING) return;
                        stage = Stage.SHIELD_CHECK;
                        wait = 0;
                    } else {
                        // Crouch first: the server only sees it after this tick's input goes out, so
                        // the placement has to happen on a later tick.
                        pressSneak();
                        pendingShield = plan;
                        stage = Stage.SNEAK_PLACE;
                        wait = 1;
                    }
                }
                case SNEAK_PLACE -> {
                    // The crouch stays down while the aim finishes walking over; the click waits for it.
                    if (placeShield(pendingShield) == Action.AIMING) return;
                    pendingShield = null;
                    // Stay crouched until the placement packet has gone out.
                    stage = Stage.UNSNEAK;
                    wait = 1;
                }
                case UNSNEAK -> {
                    releaseSneak();
                    standTick = ticksRunning;
                    stage = Stage.SHIELD_CHECK;
                    wait = 0;
                }
                case SHIELD_CHECK -> {
                    if (shieldClickTick < 0 || Veil.mc.player.tickCount <= shieldClickTick) {
                        if (++shieldCheckWaits > 6) shieldClickTick = -2;   // never clicked: failed
                        else { wait = 1; return; }
                    }
                    if (shieldTarget != null && shieldClickTick != -2 && isShielded(shieldTarget)) {
                        stage = Stage.DETONATE;
                        // A tick has already passed since the click, so take it off the delay.
                        wait = Math.max(0, delay(shieldDelay) - 1);
                    } else {
                        if (shieldTarget != null) failedShields.add(shieldTarget);
                        if (shieldAttempts > shieldRetries.get()) {
                            if (!detonateUnshielded.get()) {
                                abort("couldn't place the glowstone shield, cancelled so you don't take the blast");
                                return;
                            }
                            Veil.LOGGER.warn("anchor: shield failed after {} attempts, detonating anyway",
                                    shieldAttempts);
                            stage = Stage.DETONATE;
                            wait = 0;
                        } else {
                            stage = Stage.SHIELD;
                            wait = 0;
                        }
                    }
                }
                case DETONATE -> {
                    if (!readyStanding()) return;
                    if (!Veil.mc.level.getBlockState(anchorPos).is(Blocks.RESPAWN_ANCHOR)) {
                        finish();
                        return;
                    }
                    switch (detonate()) {
                        // Both of these end the tick where it is: nothing was clicked.
                        case REFUSED -> { return; }
                        case AIMING -> { return; }
                        case CLICKED -> {
                            detonateAttempts++;
                            // The anchor only disappears once the explosion reaches us: check back.
                            stage = Stage.VERIFY;
                            // A fixed detonate-check delay spaces consecutive checks identically; ±1
                            // tick when silent keeps them from lining up on the same beat.
                            int check = silent.get()
                                    ? verifyDelay.get() + (ThreadLocalRandom.current().nextInt(3) - 1)
                                    : verifyDelay.get();
                            wait = Math.max(2, check);
                        }
                    }
                }
                case VERIFY -> {
                    BlockState state = Veil.mc.level.getBlockState(anchorPos);
                    if (!state.is(Blocks.RESPAWN_ANCHOR)) { finish(); return; }   // it went off
                    if (detonateAttempts >= 3) {
                        Veil.LOGGER.warn("anchor: it did not go off after {} clicks", detonateAttempts);
                        finish();
                        return;
                    }
                    if (state.getValue(BlockStateProperties.RESPAWN_ANCHOR_CHARGES) == 0) {
                        // The server never registered the charge (lag): charge again, then detonate.
                        if (chargeAttempts >= 3) {
                            Veil.LOGGER.warn("anchor: it did not go off, and would not charge");
                            finish();
                            return;
                        }
                        switch (charge()) {
                            case REFUSED -> { abort("couldn't charge the anchor"); return; }
                            // Stay in VERIFY, finish the turn, then click on a later tick.
                            case AIMING -> { return; }
                            case CLICKED -> {
                                chargeAttempts++;
                                stage = Stage.DETONATE;
                                wait = Math.max(1, delay(chargeDelay));
                                return;
                            }
                        }
                    }
                    // Still charged and still there: the detonate click was dropped, try again.
                    stage = Stage.DETONATE;
                }
                default -> { }
            }
        }
    }

    // ---- Actions ----

    /**
     * Places the anchor where a normal right-click would, on the face that was aimed at.
     *
     * <p>Reusing the aim face matters: a generic place helper picks its own side and can end up
     * clicking a replaceable neighbour such as grass or snow, which drops the anchor a block off.
     */
    private Action placeAnchor() {
        int slot = findItemSlot(Items.RESPAWN_ANCHOR);
        if (slot == -1) return Action.REFUSED;
        if (!canPlaceBlockAt(anchorPos, Veil.mc.level.getBlockState(anchorPos))) return Action.REFUSED;

        BlockHitResult click = null;
        if (aimHit != null && !isClickable(Veil.mc.level.getBlockState(aimHit.getBlockPos()).getBlock())) {
            click = new BlockHitResult(aimHit.getLocation(), aimHit.getDirection(),
                    aimHit.getBlockPos(), false);
        }
        if (click == null) {
            Direction side = findSupportSide(anchorPos);
            if (side == null) return Action.REFUSED;
            BlockPos neighbour = anchorPos.relative(side);
            Direction face = side.getOpposite();
            click = new BlockHitResult(faceCenter(neighbour, face), face, neighbour, false);
        }

        if (!aimReady(click.getLocation())) return Action.AIMING;
        HotbarSlots.select(this, slot);
        interact(click, true);
        return Action.CLICKED;
    }

    private Action charge() {
        int slot = findItemSlot(Items.GLOWSTONE);
        if (slot == -1) return Action.REFUSED;

        Direction face = nearestFace(Veil.mc.player.getEyePosition(), anchorPos);
        BlockPos facePos = anchorPos.relative(face);
        boolean glowstoneBefore = Veil.mc.level.getBlockState(facePos).is(Blocks.GLOWSTONE);

        if (!aimReady(Vec3.atCenterOf(anchorPos))) return Action.AIMING;
        chargeClicked = true;
        HotbarSlots.select(this, slot);
        interact(new BlockHitResult(hitScatter(anchorPos, face), face, anchorPos, false), true);

        // Glowstone on the clicked face means the click placed a block instead of charging.
        chargeFailed = !glowstoneBefore && Veil.mc.level.getBlockState(facePos).is(Blocks.GLOWSTONE);
        return Action.CLICKED;
    }

    private Action placeShield(ShieldPlan plan) {
        int slot = findItemSlot(Items.GLOWSTONE);
        if (plan == null || slot == -1) {
            // Nothing to place with, or nowhere left to put it: recorded as a failed attempt for the
            // check that follows, and there is nothing to wait for.
            shieldAttempts++;
            shieldClickTick = -2;
            shieldCheckWaits = 0;
            shieldTarget = plan == null ? null : plan.pos();
            return Action.REFUSED;
        }

        BlockPos shieldPos = plan.pos();
        BlockHitResult click;
        if (plan.replace()) {
            // Snow layer or grass in the spot: clicking it replaces it with the glowstone.
            Direction shieldFace = nearestFace(Veil.mc.player.getEyePosition(), shieldPos);
            click = new BlockHitResult(hitScatter(shieldPos, shieldFace), shieldFace, shieldPos, false);
        } else {
            BlockPos neighbour = shieldPos.relative(plan.supportSide());
            Direction face = plan.supportSide().getOpposite();
            click = new BlockHitResult(faceCenter(neighbour, face), face, neighbour, false);
        }

        // Turning for the shot is not an attempt: the counters start here, so a retry budget is not
        // spent on aim steps and the verification below only ever reads a click that went out.
        if (!aimReady(click.getLocation())) return Action.AIMING;

        shieldAttempts++;
        shieldClickTick = -1;
        shieldCheckWaits = 0;
        shieldTarget = shieldPos;
        HotbarSlots.select(this, slot);
        // While crouch-placing we must stay crouched, or the click charges the anchor instead.
        interact(click, !plan.needsSneak());
        shieldClickTick = Veil.mc.player.tickCount;
        return Action.CLICKED;
    }

    private Action detonate() {
        int slot = findDetonateSlot();
        if (slot == -1) { abort("no safe slot to detonate with"); return Action.REFUSED; }
        if (!aimReady(Vec3.atCenterOf(anchorPos))) return Action.AIMING;
        HotbarSlots.select(this, slot);
        interact(clickOn(anchorPos), true);
        return Action.CLICKED;
    }

    /**
     * Right-clicks a block face with the main hand, the same action as holding the use key there.
     *
     * @param standUp click as if standing, so clicking an anchor detonates it instead of placing the
     *                held item onto it
     */
    private void interact(BlockHitResult click, boolean standUp) {
        LocalPlayer self = Veil.mc.player;
        boolean wasSneaking = self.isShiftKeyDown();
        if (standUp) self.setShiftKeyDown(false);

        Veil.mc.gameMode.useItemOn(self, InteractionHand.MAIN_HAND, click);
        actedThisTick = true;
        if (swing.get()) self.swing(InteractionHand.MAIN_HAND, SwingAnimation.DEFAULT, false);

        if (standUp) self.setShiftKeyDown(wasSneaking);
    }

    // ---- Shield planning ----

    /**
     * Candidate spots are the blocks the blast would cross on its way from the anchor to your body and
     * eyes, nearest the anchor first, so the shield really sits in the way rather than beside it.
     *
     * <p>The nyx version also checked that each face was visible from the camera, which only matters
     * for live clicks; that check is gone and the remaining rules are physical: the spot must be
     * placeable, in reach, not already shielded, not inside an entity, and not already failed.
     */
    private ShieldPlan planShield() {
        Vec3 anchorCenter = Vec3.atCenterOf(anchorPos);
        Vec3 body = Veil.mc.player.getBoundingBox().getCenter();
        Vec3 eyes = Veil.mc.player.getEyePosition();

        Set<BlockPos> ordered = new LinkedHashSet<>();
        for (int i = 1; i <= 60; i++) {
            double t = i / 60.0;
            for (Vec3 end : new Vec3[]{eyes, body}) {
                Vec3 p = anchorCenter.add(end.subtract(anchorCenter).scale(t));
                if (p.distanceTo(anchorCenter) > 3.0) continue;
                BlockPos bp = BlockPos.containing(p);
                if (!bp.equals(anchorPos)) ordered.add(bp);
            }
        }
        List<BlockPos> candidates = new ArrayList<>(ordered);
        candidates.sort((a, b) -> Double.compare(Vec3.atCenterOf(a).distanceToSqr(anchorCenter),
                                                Vec3.atCenterOf(b).distanceToSqr(anchorCenter)));

        for (BlockPos pos : candidates) {
            BlockState existing = Veil.mc.level.getBlockState(pos);
            if (!existing.canBeReplaced()
                    && Block.isShapeFullBlock(existing.getCollisionShape(Veil.mc.level, pos))) {
                return new ShieldPlan(pos, null, false, false);   // already shielded
            }
            if (failedShields.contains(pos)) continue;
            // Thick snow cannot be replaced by a block, so nothing can go in this spot.
            if (existing.is(Blocks.SNOW) && existing.getValue(BlockStateProperties.LAYERS) > 1) continue;
            if (!canPlaceBlockAt(pos, existing)) continue;
            if (!inRange(pos)) continue;
            if (blockedByEntity(pos)) continue;

            // Snow layer / grass / flowers: click the spot itself, which replaces it.
            if (existing.canBeReplaced() && existing.getFluidState().isEmpty()
                    && !existing.getShape(Veil.mc.level, pos).isEmpty()) {
                return new ShieldPlan(pos, null, false, true);
            }

            Direction support = findSupportSide(pos);
            if (support != null) return new ShieldPlan(pos, support, false, false);

            // Only the anchor is adjacent: crouch and place against the anchor itself.
            Direction toAnchor = null;
            for (Direction d : Direction.values()) {
                if (pos.relative(d).equals(anchorPos)) toAnchor = d;
            }
            if (toAnchor != null && crouchPlace.get()) {
                return new ShieldPlan(pos, toAnchor, true, false);
            }
        }
        return null;
    }

    /** True if the spot now holds something that blocks the blast. */
    private boolean isShielded(BlockPos pos) {
        BlockState state = Veil.mc.level.getBlockState(pos);
        if (state.is(Blocks.GLOWSTONE)) return true;
        return !state.canBeReplaced()
                && Block.isShapeFullBlock(state.getCollisionShape(Veil.mc.level, pos));
    }

    // ---- Crouching ----

    /**
     * Whether the sneak key is down on the keyboard, whatever this module has set the mapping to.
     *
     * <p>{@code KeyMapping#isDown} cannot answer that while a sequence is standing the player up:
     * the module has just forced it off, and nothing puts it back until the key is pressed again.
     *
     * <p>The key is read through {@code saveString} — the binding as options.txt stores it, so a
     * rebound sneak key is honoured — because {@code getDefaultKey} is the default, not the bound one.
     */
    private static boolean sneakHeldByPlayer() {
        InputConstants.Key bound = InputConstants.getKey(Veil.mc.options.keyShift.saveString());
        return bound != null && InputConstants.isKeyDown(bound.getValue());
    }

    /** Holds the sneak key, as if the player pressed it. Does nothing if already held. */
    private void pressSneak() {
        if (Veil.mc.options.keyShift.isDown()) return;
        Veil.mc.options.keyShift.setDown(true);
        Veil.mc.player.setShiftKeyDown(true);
        pressedSneak = true;
    }

    /**
     * Stands up and remembers to give the crouch back afterwards.
     *
     * <p>The key itself has to be released, not just the player flag: the client rebuilds its input
     * state from the key every tick, so a held key would put the crouch straight back.
     */
    private void standUp() {
        if (!Veil.mc.options.keyShift.isDown() && !Veil.mc.player.isShiftKeyDown()) return;
        if (!pressedSneak) forcedStand = true;
        Veil.mc.options.keyShift.setDown(false);
        Veil.mc.player.setShiftKeyDown(false);
        pressedSneak = false;
        standTick = ticksRunning;
    }

    /**
     * True once the server is guaranteed to see us standing. Otherwise stands up (or waits a tick) and
     * returns false so the caller retries.
     */
    private boolean readyStanding() {
        if (Veil.mc.options.keyShift.isDown() || Veil.mc.player.isShiftKeyDown()) standUp();
        if (ticksRunning <= standTick) {
            wait = 1;
            return false;
        }
        return true;
    }

    /** Lets go of sneak, but only if this module pressed it. */
    private void releaseSneak() {
        if (!pressedSneak) return;
        Veil.mc.options.keyShift.setDown(false);
        Veil.mc.player.setShiftKeyDown(false);
        pressedSneak = false;
    }

    // ---- Sequence end ----

    protected final void finish() {
        endSequence();
        onCycleEnded();
    }

    private void abort(String reason) {
        Veil.LOGGER.warn("anchor: {}", reason);
        finish();
    }

    private void endSequence() {
        releaseSneak();
        // Give back a crouch of the player's own — but only if they are still holding the key. The
        // flag was set when the sequence stood them up, several ticks ago; pressing it back on
        // someone who let go in the meantime is what left them crouched with no key held, which
        // nothing short of pressing crouch again clears.
        if (forcedStand && Veil.mc.player != null && sneakHeldByPlayer()) {
            Veil.mc.options.keyShift.setDown(true);
            Veil.mc.player.setShiftKeyDown(true);
        }
        forcedStand = false;
        // Hand the hotbar back to the player, or to whichever other module is still holding it.
        HotbarSlots.release(this, swapBack.get());
        pendingShield = null;
        heldForCrystal = false;
        turning = false;
        // The sequence gives up the view it was aiming in, so the next one starts from the camera.
        aimBase.clear();
        stage = Stage.DONE;
    }

    // ---- Helpers ----

    /**
     * Turns toward a point: silently through SilentRotationManager, or by turning the camera.
     *
     * <p>Silent aiming turns at most one step per tick, because a rotation no hand could produce is
     * what gives the aim away. That makes a large turn a multi-tick affair, and a click sent on the
     * tick a step went out would be acted on wherever the server last saw the player looking — up to
     * a whole step short of the block that was meant. So the caller is told whether the aim has
     * arrived and holds its click until it has, instead of clicking short and hoping.
     *
     * @return true once the view the server is given is on the target; false if only a step of the
     *         turn has gone out and the click has to wait for the next tick
     */
    protected final boolean aimAt(Vec3 target) {
        if (!rotate.get() || Veil.mc.player == null) return true;
        Vec3 eyes = Veil.mc.player.getEyePosition();
        double dx = target.x - eyes.x;
        double dy = target.y - eyes.y;
        double dz = target.z - eyes.z;
        float yaw = (float) Math.toDegrees(Math.atan2(-dx, dz));
        float pitch = (float) Math.toDegrees(-Math.atan2(dy, Math.sqrt(dx * dx + dz * dz)));
        pitch = net.minecraft.util.Mth.clamp(pitch, -90f, 90f);

        float gcd = RotationUtil.gcd();
        // Measured from this module's own last step, not from whatever angle went out last: a
        // crystal cycle aiming at the same time sends its own, and stepping from that would drag
        // the anchor's turn onto the crystal.
        boolean silentAim = silent.get();
        boolean tracked   = silentAim && aimBase.has();
        float baseYaw   = tracked ? aimBase.yaw()   : Veil.mc.player.getYRot();
        float basePitch = tracked ? aimBase.pitch() : Veil.mc.player.getXRot();
        float deltaYaw   = net.minecraft.util.Mth.wrapDegrees(yaw - baseYaw);
        float deltaPitch = pitch - basePitch;

        // Already looking there: nothing to send, and the click may go out as it stands.
        if (Math.abs(deltaYaw) <= AIM_CONVERGED_DEG && Math.abs(deltaPitch) <= AIM_CONVERGED_DEG) {
            return true;
        }

        // A turn is walked in over several ticks along the same minimum-jerk trajectory CrystalAura
        // uses, instead of a constant capped step with jitter: the step grows and fades the way a
        // hand moving a mouse does. A redirect to a different target restarts the movement, so the
        // path always ends on the block being clicked now rather than on the previous aim.
        if (!aimState.isMoving()
                || Math.abs(net.minecraft.util.Mth.wrapDegrees(yaw - aimState.targetYaw())) > 1.0f
                || Math.abs(pitch - aimState.targetPitch()) > 1.0f) {
            aimState.start(baseYaw, basePitch, yaw, pitch);
        }

        float[] step = aimState.tick(gcd);
        if (step == null) {
            // The trajectory is done: land on the target itself, on the grid this sensitivity's mouse
            // could produce, so the click goes out on the view the server has (Silent) or on the
            // camera the player is looking through (not Silent).
            if (silentAim) {
                float endYaw   = baseYaw + RotationUtil.quantize(deltaYaw, gcd);
                float endPitch = net.minecraft.util.Mth.clamp(
                        basePitch + RotationUtil.quantize(deltaPitch, gcd), -90f, 90f);
                SilentRotationManager.setPending(endYaw, endPitch);
                aimBase.record(endYaw, endPitch);
            } else {
                Veil.mc.player.setYRot(yaw);
                Veil.mc.player.setXRot(pitch);
            }
            aimState.reset();
            return true;
        }

        if (silentAim) {
            SilentRotationManager.setPending(step[0], step[1]);
            aimBase.record(step[0], step[1]);
        } else {
            // Not Silent: the camera is the view the click is judged on, so the step goes to it.
            Veil.mc.player.setYRot(step[0]);
            Veil.mc.player.setXRot(step[1]);
        }
        return aimState.isConverged(step[0], step[1]);
    }

    /**
     * Aims at {@code target} for a stage's click, recording whether the stage is now waiting on the turn.
     *
     * <p>The state is per stage, not per tick: a stage that has sent a step has work in flight — a click
     * it has not made — so neither a handoff nor a return-to-camera step may interrupt it.
     *
     * @return true when the view has arrived and the click may go out this tick
     */
    private boolean aimReady(Vec3 target) {
        turning = !aimAt(target);
        return !turning;
    }

    /**
     * Steps the server's view back toward the camera while the sequence has nothing to click.
     *
     * <p>Not {@link #aimAt}: there is no target, just the walk back, so a sequence that ends or pauses
     * does not leave the server still looking at the last anchor.
     */
    protected final void returnRotation() {
        if (!rotate.get() || !silent.get() || Veil.mc.player == null) return;
        // A return step moves the view this module's base was measured from, so the base goes
        // with it and the next aim starts from the camera the return is walking back to.
        if (SilentRotationManager.stepBackToCamera(
                Veil.mc.player, MAX_SILENT_YAW_STEP, MAX_SILENT_PITCH_STEP)) {
            aimBase.clear();
        }
    }

    protected final boolean hasItem(Item item) {
        return findItemSlot(item) != -1;
    }

    protected final int findItemSlot(Item item) {
        if (Veil.mc.player == null) return -1;
        for (int i = 0; i < 9; i++) {
            if (Veil.mc.player.getInventory().getItem(i).is(item)) return i;
        }
        return -1;
    }

    protected final boolean inRange(BlockPos pos) {
        return Veil.mc.player != null && Veil.mc.player.isWithinBlockInteractionRange(pos, 0.5);
    }

    /** Air or a replaceable, fluid-free block: the same test CrystalAura places obsidian with. */
    protected static boolean canPlaceBlockAt(BlockPos pos, BlockState state) {
        return state.isAir() || (state.canBeReplaced() && state.getFluidState().isEmpty());
    }

    /** Any entity whose hitbox overlaps this block: the client should not place a block inside one. */
    protected final boolean blockedByEntity(BlockPos pos) {
        AABB box = new AABB(pos);
        for (Entity e : Veil.mc.level.getEntities((Entity) null, box.inflate(1.0),
                entity -> entity.isAlive() && !entity.isSpectator()
                        && (entity instanceof LivingEntity || entity instanceof EndCrystal))) {
            if (e.getBoundingBox().intersects(box)) return true;
        }
        return false;
    }

    /**
     * A solid neighbour to place against. Never the anchor itself (clicking it with glowstone would
     * charge it) and never a block whose right-click does something else.
     */
    protected final Direction findSupportSide(BlockPos pos) {
        Vec3 eyes = Veil.mc.player.getEyePosition();
        Direction best = null;
        double bestDist = Double.MAX_VALUE;
        for (Direction side : Direction.values()) {
            BlockPos neighbour = pos.relative(side);
            if (neighbour.equals(anchorPos)) continue;
            BlockState state = Veil.mc.level.getBlockState(neighbour);
            if (state.isAir() || state.canBeReplaced()) continue;
            if (!state.getFluidState().isEmpty()) continue;
            if (isClickable(state.getBlock())) continue;
            double dist = eyes.distanceToSqr(Vec3.atCenterOf(neighbour));
            if (dist < bestDist) { bestDist = dist; best = side; }
        }
        return best;
    }

    /** Blocks whose right-click opens or toggles something instead of placing against them. */
    protected static boolean isClickable(Block block) {
        return block instanceof EntityBlock || block instanceof DoorBlock
                || block instanceof TrapDoorBlock || block instanceof FenceGateBlock
                || block instanceof ButtonBlock || block instanceof LeverBlock
                || block instanceof RespawnAnchorBlock;
    }

    protected static Vec3 faceCenter(BlockPos pos, Direction face) {
        return new Vec3(
            pos.getX() + 0.5 + face.getStepX() * 0.5,
            pos.getY() + 0.5 + face.getStepY() * 0.5,
            pos.getZ() + 0.5 + face.getStepZ() * 0.5);
    }

    private static final java.util.Random HR = new java.util.Random();
    protected static Vec3 hitScatter(BlockPos pos, Direction face) {
        Vec3 center = faceCenter(pos, face);
        double ox = face.getStepX() == 0 ? (HR.nextDouble() * 0.3 - 0.15) : 0;
        double oy = face.getStepY() == 0 ? (HR.nextDouble() * 0.3 - 0.15) : 0;
        double oz = face.getStepZ() == 0 ? (HR.nextDouble() * 0.3 - 0.15) : 0;
        return center.add(ox, oy, oz);
    }

    protected static BlockHitResult clickOn(BlockPos pos) {
        Direction face = nearestFace(Veil.mc.player.getEyePosition(), pos);
        return new BlockHitResult(hitScatter(pos, face), face, pos, false);
    }

    /** The face of {@code pos} that points at {@code from}. */
    protected static Direction nearestFace(Vec3 from, BlockPos pos) {
        Vec3 center = Vec3.atCenterOf(pos);
        double dy = from.y - center.y;
        if (dy > 0.3) return Direction.UP;
        if (dy < -0.3) return Direction.DOWN;
        double dx = from.x - center.x;
        double dz = from.z - center.z;
        if (Math.abs(dx) > Math.abs(dz)) return dx > 0 ? Direction.EAST : Direction.WEST;
        return dz > 0 ? Direction.SOUTH : Direction.NORTH;
    }

    /**
     * Picks what to detonate with. An empty slot is safest; otherwise a slot that will neither place a
     * block nor be "used" (throw a pearl, eat, teleport) if the server has not registered the charge.
     */
    private int findDetonateSlot() {
        int fallback = -1;
        for (int i = 0; i < 9; i++) {
            ItemStack stack = Veil.mc.player.getInventory().getItem(i);
            if (stack.isEmpty()) return i;
            if (stack.is(Items.GLOWSTONE)) continue;
            if (fallback == -1 && isSafeToClickWith(stack)) fallback = i;
        }
        if (fallback != -1) return fallback;
        for (int i = 0; i < 9; i++) {
            if (!Veil.mc.player.getInventory().getItem(i).is(Items.GLOWSTONE)) return i;
        }
        return -1;
    }

    private static boolean isSafeToClickWith(ItemStack stack) {
        if (stack.getItem() instanceof BlockItem) return false;
        return !stack.is(Items.ENDER_PEARL)
                && !stack.is(Items.EXPERIENCE_BOTTLE)
                && !stack.is(Items.SPLASH_POTION)
                && !stack.is(Items.LINGERING_POTION)
                && !stack.is(Items.END_CRYSTAL)
                && !stack.is(Items.FIREWORK_ROCKET)
                && !stack.is(Items.GOLDEN_APPLE)
                && !stack.is(Items.ENCHANTED_GOLDEN_APPLE)
                && !stack.is(Items.CHORUS_FRUIT);
    }

    /**
     * A configured delay plus a random 0..random-extra ticks, stretched by session fatigue.
     *
     * <p>The multiplier starts at 1.0 and reaches 1.4 after twenty minutes of session, so a sequence
     * run late in a session is not paced exactly like the first one.
     */
    protected final int delay(IntSetting base) {
        int extra = randomExtra.get();
        int raw   = base.get() + (extra > 0 ? ThreadLocalRandom.current().nextInt(extra + 1) : 0);
        return Math.max(raw, (int)(raw * SessionFatigue.get().delayMultiplier()));
    }

    @Override
    public String suffix() {
        return stage == null || stage == Stage.DONE ? null : stage.name().toLowerCase(Locale.ROOT);
    }
}
