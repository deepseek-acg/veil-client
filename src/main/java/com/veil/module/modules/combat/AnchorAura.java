// lang: Java, file: AnchorAura.java, target: Fabric MC 26.3
// AutoSafeAnchor with automatic placement: the same place -> charge -> shield -> detonate sequence, but
// the spot is chosen from the opponent's position and the damage it would take, and a spot that
// overlaps any entity's hitbox (the opponent's above all) is never considered.
package com.veil.module.modules.combat;

import com.veil.Veil;
import com.veil.event.EventHandler;
import com.veil.event.events.TickEvent;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ThreadLocalRandom;
import com.veil.module.Module;
import com.veil.module.ModuleManager;
import com.veil.setting.BoolSetting;
import com.veil.setting.FloatSetting;
import com.veil.setting.IntSetting;
import com.veil.util.DamageUtil;
import com.veil.util.SessionFatigue;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.core.BlockPos;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.boss.enderdragon.EndCrystal;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;

/**
 * AnchorAura: {@link AutoSafeAnchor}'s sequence, driven automatically at whichever nearby spot does
 * the most damage to the closest opponent.
 *
 * <p>A spot is only a candidate if the anchor there would be placeable, in reach, supported by a face
 * we can click, outside every entity hitbox — the opponent's included — and worth it: damage to them
 * at least Min Damage, damage to you at most Max Self Damage (and never lethal with Anti Suicide on).
 * Among the survivors it takes the best damage, minus half the damage it would do to you.
 *
 * <p>Damage is estimated with {@link DamageUtil} at the anchor's centre with the anchor's own block
 * treated as air, because the anchor is gone by the time the blast happens. That estimate ignores the
 * glowstone shield the sequence places before detonating, so it errs on the side of not firing when
 * you are standing in the blast.
 *
 * <p>A knock-up hit outranks all of that. When CrystalAura reports an unanswered claim — a sword hit
 * inside its Knockup Window with crystals to answer it with — any sequence running here is dropped and
 * no new one starts until the window closes. An anchor sequence is long: place, charge, shield,
 * detonate, with the aim and the hotbar held the whole time. Sitting on it while the opponent is in
 * the air is how the one hit that matters goes unanswered.
 *
 * <p>Crystals come first when they can do the job. If CrystalAura is switched on and the crystal it
 * would use against the same opponent deals at least what the anchor would, no anchor is started:
 * a crystal hits harder for the same exposure and skips the charge-and-shield steps. The anchor is
 * then the fallback for what a crystal cannot do — an opponent down a hole where the blast has less
 * of them to reach, a base the crystal cannot use, or a blast that would hurt you more than the
 * crystal would — rather than a second opinion on a spot the crystal already covers.
 *
 * <p>With CrystalAura's Wait For Anchor on (the default), a sequence running here is held between operations rather
 * than dropped: CrystalAura waits for a stage to finish, takes the hand for its own crystal, and lets
 * this carry on from the same stage afterwards. Without it the two modules take the hotbar from each
 * other every tick, and neither the sword nor the item either of them meant to click with stays in
 * hand.
 */
public final class AnchorAura extends AutoSafeAnchor {

    /** Respawn anchor explosion power, as used by vanilla's {@code RespawnAnchorBlock}. */
    private static final float ANCHOR_POWER = 5f;

    /** A spot an anchor could go on, and the damage it would deal to the opponent there. */
    private record Spot(BlockPos pos, float damage) {}

    private final FloatSetting targetRange   = register(new FloatSetting("Target Range", 4.5f, 2.0f, 6.0f));
    private final FloatSetting placeRange    = register(new FloatSetting("Place Range",  4.5f, 2.0f, 6.0f));
    private final FloatSetting minDamage     = register(new FloatSetting("Min Damage",   4.0f, 0.0f, 20.0f));
    private final FloatSetting maxSelfDamage = register(new FloatSetting("Max Self Damage", 6.0f, 0.0f, 20.0f));
    private final BoolSetting  antiSuicide   = register(new BoolSetting("Anti Suicide", true));
    private final BoolSetting  crystalsFirst = register(new BoolSetting("Crystals First", true));
    private final BoolSetting  humanize      = register(new BoolSetting("Humanize", true));
    private final IntSetting   rearmDelay    = register(new IntSetting("Re-arm Delay", 2, 0, 20));

    /** How long a spot that has just been detonated is left off the candidate list. */
    private static final long SPOT_COOLDOWN_MS = 8_000L;

    /**
     * Recently detonated spots and the time each becomes usable again.
     *
     * <p>Access-ordered and bounded at ten entries: the map is asked about every candidate every
     * tick, so it must not grow with the session, and the spots that matter are the last few.
     */
    private final Map<BlockPos, Long> spotCooldown = new LinkedHashMap<>(16, 0.75f, true) {
        @Override
        protected boolean removeEldestEntry(Map.Entry<BlockPos, Long> eldest) {
            return size() > 10;
        }
    };

    private int rearmTicks;
    private Player target;

    /** The preset whose numbers the range and damage settings were last filled from. */
    private SilentCrystalProfile appliedProfile = SilentCrystalProfile.DEFAULT;

    public AnchorAura() {
        super("AnchorAura",
              "Places, charges, shields and detonates respawn anchors on whichever spot hurts the nearest opponent most, never inside their hitbox.");
    }

    @Override
    public void onEnable() {
        super.onEnable();
        applyProfile(SilentCrystalProfile.forCurrentServer());
        // The session's delay ramp starts when the module is switched on. It used to be reset from
        // onActivated(), which Macro Mode calls once per sequence, so the ramp was pinned near 1.0 and
        // never grew over the session it exists to model.
        SessionFatigue.get().reset();
    }

    /**
     * Fills the range and damage settings from the server's preset without touching a value the
     * player has chosen.
     *
     * <p>A setting still holding the number the previous preset put there is treated as untouched and
     * moved to the new preset's number; anything else was set by hand and is left alone. This has to
     * run when the module is enabled rather than when it is constructed: modules are built before the
     * client has joined anything, so a lookup there can only ever find DEFAULT.
     */
    private void applyProfile(SilentCrystalProfile next) {
        if (next == appliedProfile) return;
        targetRange.set(keep(targetRange.get(), appliedProfile.targetRange, next.targetRange));
        placeRange.set(keep(placeRange.get(), appliedProfile.placeRange, next.placeRange));
        minDamage.set(keep(minDamage.get(), appliedProfile.minDamage, next.minDamage));
        maxSelfDamage.set(keep(maxSelfDamage.get(), appliedProfile.maxSelfDamage, next.maxSelfDamage));
        rearmDelay.set(keep(rearmDelay.get(), appliedProfile.rearmDelayTicks, next.rearmDelayTicks));
        aimState.configure(next.aimNoiseK, next.overshootProb, next.ewmaAlpha);
        appliedProfile = next;
    }

    private static float keep(float current, float previous, float next) {
        return Float.compare(current, previous) == 0 ? next : current;
    }

    private static int keep(int current, int previous, int next) {
        return current == previous ? next : current;
    }

    @Override
    protected void onActivated() {
        rearmTicks = 0;
        target = null;
        spotCooldown.clear();
    }

    @Override
    public void onDisable() {
        super.onDisable();
        target = null;
        spotCooldown.clear();
    }

    @Override
    public void onPause() {
        super.onPause();
        target = null;
        rearmTicks = 0;
    }

    /** A finished cycle re-arms instead of switching the module off. */
    @Override
    protected void onCycleEnded() {
        if (!silent.get()) {
            rearmTicks = rearmDelay.get();
            return;
        }
        // Silent: the configured value is the mean and the server preset's jitter fraction is the
        // spread, so the gap is drawn from a distribution rather than a fixed ±1 tick window. The
        // whole gap then stretches with session fatigue, and about one cycle in seven waits an extra
        // second to four on top.
        int    mean  = Math.max(1, rearmDelay.get());
        double sigma = Math.max(1.0, mean * appliedProfile.jitterFraction);
        int    gauss = (int)(mean + ThreadLocalRandom.current().nextGaussian() * sigma);
        gauss = Math.max(2, (int)(gauss * SessionFatigue.get().delayMultiplier()));
        if (ThreadLocalRandom.current().nextFloat() < 0.15f) {
            gauss += 20 + ThreadLocalRandom.current().nextInt(60); // 1-4 s at 20 ticks/s
        }
        rearmTicks = gauss;
    }

    @EventHandler
    private void onTick(TickEvent.Pre event) {
        if (!atWorld()) return;

        // Macro Mode: no sequence, no re-arm and no new target while the key is up. Dropped through
        // onPause rather than frozen, because a half-finished place-charge-shield was planned
        // against the world as it was before the key came up.
        //
        // Armed once and left enabled between holds, so this branch runs on every idle tick and can
        // walk the server's silent view back to the camera — the same return the re-arm branch does.
        if (macroMode.get() && !macroHeld()) {
            if (sequenceActive() || target != null || rearmTicks > 0) onPause();
            returnRotation();
            return;
        }

        // Stand down for a knock-up claim before anything else, including the sequence that is already
        // running. Dropped rather than frozen, like onPause, and for the same reason: it was planned
        // against the world from before the hit. Nothing re-arms here until the window closes; the
        // first tick after that finds no sequence and starts a fresh one.
        if (crystalsTookOver()) {
            if (sequenceActive() || target != null) onPause();
            return;
        }

        if (sequenceActive()) {
            sequenceTick();
            return;
        }
        if (rearmTicks > 0) {
            rearmTicks--;
            // Re-arming is dead time: nothing is clicked, so the server's view walks back.
            if (silent.get()) returnRotation();
            return;
        }
        if (!anchorsWorkHere()) return;
        if (!hasItem(net.minecraft.world.item.Items.RESPAWN_ANCHOR)) return;
        if (!hasItem(net.minecraft.world.item.Items.GLOWSTONE)) return;

        Player opponent = findOpponent();
        if (opponent == null) { target = null; return; }
        target = opponent;

        Spot spot = bestSpot(opponent);
        if (spot == null) return;
        // Nothing is latched: asked again next tick, so the anchor takes over the moment the crystal
        // stops being the better play.
        if (crystalsFirst.get() && crystalCovered(opponent, spot.damage())) return;
        // No sequence is running here, so starting from a clean one only throws away what belongs to
        // the last cycle: the attempt counters and the failed-shield set. Carried over, they make a
        // later cycle refuse to charge or to shield a spot that is perfectly fine.
        resetSequence();
        beginAt(spot.pos());
        // The spot just went off: coming straight back to it means placing, charging and shielding an
        // anchor on ground the opponent has already been knocked off, for a blast that reaches them off
        // nothing but the edge of the box.
        spotCooldown.put(spot.pos(), System.currentTimeMillis() + SPOT_COOLDOWN_MS);
    }

    /** The closest living player inside Target Range, other than you. */
    /** Who the running sequence is aimed at, or null when there is none. Read by the target HUD. */
    public Player target() { return target; }

    private Player findOpponent() {
        LocalPlayer self = Veil.mc.player;
        Player best = null;
        double bestDist = targetRange.get();
        for (Player candidate : Veil.mc.level.players()) {
            if (candidate == self || candidate.isSpectator() || candidate.isCreative()) continue;
            if (!candidate.isAlive() || candidate.isDeadOrDying()) continue;
            double distance = self.distanceTo(candidate);
            if (distance <= bestDist) { bestDist = distance; best = candidate; }
        }
        return best;
    }

    /**
     * The spot near {@code opponent} that scores best — damage dealt, minus half the damage taken —
     * together with the damage dealt there, which is what {@link #crystalCovered} compares against.
     *
     * <p>Iteration is a box around the opponent rather than around you, because that is where the
     * damage is: an anchor next to them exposes far more of their hitbox than one next to you.
     *
     * <p>The column searched runs three blocks up and two down from their feet rather than one up
     * and two down. That is what makes it work on ground that is not level: an opponent two or three
     * blocks down in a hole can only be reached at or above the rim, and one standing on a ledge
     * above you only from below. The old window stopped at their head, so in a hole every spot it
     * looked at was a wall or the block they occupy, and the aura found nothing and did nothing.
     */
    private Spot bestSpot(Player opponent) {
        LocalPlayer self = Veil.mc.player;
        BlockPos origin = opponent.blockPosition();
        int radius = Math.max(2, (int) Math.ceil(placeRange.get()));

        Spot best = null;
        double bestScore = Double.NEGATIVE_INFINITY;
        List<Spot> candidates = new ArrayList<>();

        for (BlockPos cursor : BlockPos.betweenClosed(
                origin.offset(-radius, -2, -radius), origin.offset(radius, 3, radius))) {
            BlockPos pos = cursor.immutable();
            if (!Veil.mc.level.isLoaded(pos)) continue;
            Long coolUntil = spotCooldown.get(pos);
            if (coolUntil != null && System.currentTimeMillis() < coolUntil) continue;

            BlockState state = Veil.mc.level.getBlockState(pos);
            if (!canPlaceBlockAt(pos, state)) continue;
            if (state.is(Blocks.RESPAWN_ANCHOR)) continue;
            if (!inRange(pos)) continue;
            // The anchor is clicked on a face: without a support there is nothing to click.
            if (findSupportSide(pos) == null) continue;
            // Never inside a hitbox: the opponent's first, but not only theirs.
            if (blockedByEntity(pos)) continue;

            Vec3 centre = Vec3.atCenterOf(pos);
            float dealt = DamageUtil.explosionDamage(opponent, centre, ANCHOR_POWER, null, pos);
            if (dealt < minDamage.get()) continue;

            float taken = DamageUtil.explosionDamage(self, centre, ANCHOR_POWER, null, pos);
            if (taken > maxSelfDamage.get()) continue;
            if (antiSuicide.get() && taken >= self.getHealth() + self.getAbsorptionAmount() - 1f) continue;

            double score = dealt - taken * 0.5;
            Spot candidate = new Spot(pos, dealt);
            candidates.add(candidate);
            if (score > bestScore) { bestScore = score; best = candidate; }
        }

        // Humanize: a player does not take the same mathematically best spot every cycle. Roughly once
        // in seven, one of the spots within 1.5 damage of the best is taken instead — close enough to
        // still be the right play, different enough that two cycles do not look identical.
        if (best != null && humanize.get() && silent.get()
                && candidates.size() > 1
                && ThreadLocalRandom.current().nextFloat() < 0.15f) {
            float bestDamage = best.damage();
            List<Spot> near = new ArrayList<>();
            for (Spot candidate : candidates) {
                if (bestDamage - candidate.damage() <= 1.5f) near.add(candidate);
            }
            if (near.size() > 1) return near.get(ThreadLocalRandom.current().nextInt(near.size()));
        }
        return best;
    }

    /**
     * Whether CrystalAura would hit this opponent at least as hard as {@code anchorDamage} right now.
     *
     * <p>Asked of the module rather than worked out again here: what matters is the plan CrystalAura
     * would actually start, and that depends on its knockup window, its Use Existing switch, its skip
     * list and its damage tolerance. A second copy of those rules would drift away from the first.
     */
    private boolean crystalCovered(Player opponent, float anchorDamage) {
        CrystalAura crystals = crystals();
        return crystals != null && crystals.isEnabled()
                && crystals.plannedDamageAgainst(opponent) >= anchorDamage;
    }

    /** Whether CrystalAura has an unanswered knock-up claim, which outranks this cycle. */
    private boolean crystalsTookOver() {
        CrystalAura crystals = crystals();
        return crystals != null && crystals.knockupClaimed();
    }

    /** The CrystalAura instance, if the module is installed. */
    private static CrystalAura crystals() {
        for (Module module : ModuleManager.getModules()) {
            if (module instanceof CrystalAura aura) return aura;
        }
        return null;
    }

    @Override
    public String suffix() {
        String stage = super.suffix();
        if (stage != null) return stage;
        return target == null ? null : target.getName().getString();
    }
}
