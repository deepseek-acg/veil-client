// lang: Java, file: PearlAim.java, target: Fabric MC 26.3
package com.veil.module.modules.combat;

import com.veil.Veil;
import com.veil.event.EventHandler;
import com.veil.event.events.TickEvent;
import com.veil.module.Category;
import com.veil.module.Module;
import com.veil.setting.BoolSetting;
import com.veil.setting.FloatSetting;
import com.veil.setting.IntSetting;
import com.veil.setting.ModeSetting;
import com.veil.util.HotbarSlots;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.network.chat.Component;
import net.minecraft.util.Mth;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.Items;
import net.minecraft.world.phys.Vec3;

/**
 * PearlAim: one key press throws an ender pearl at an opponent, at the angle your own movement
 * needs.
 *
 * <p>Momentum is not a rounding error here. Vanilla adds your movement to a thrown projectile on
 * top of the 1.5 m/tick launch ({@code Projectile.shootFromRotation}), and drops the vertical part
 * while you are on the ground, so the same crosshair throws long when you are sprinting forward and
 * short when you are backpedalling — and while you are falling it lands somewhere else entirely.
 * This solves for the yaw and pitch whose launch vector, plus your movement, puts the pearl on the
 * target: four sweeps, each one narrowing the angles by a factor of four, down to a tenth of a
 * degree around the best.
 *
 * <p>Every candidate flight is cut short as soon as it can no longer win. Horizontal travel is a
 * straight line that drag only slows, so the horizontal distance to the target falls to one
 * minimum and then grows; once that has happened and the pearl is dropping below the aim, no later
 * tick can be closer and the rest of the flight is skipped. A candidate whose distance already
 * exceeds the best miss so far is abandoned on the spot. Most of a sweep therefore costs a few
 * ticks each instead of a full {@code Search Ticks} flight.
 *
 * <p>Target choice is a setting: nearest, closest to where you are already looking, or the lowest or
 * highest health. With Lead Target on, the aim point is moved along the opponent's own movement for
 * the flight time the solution came out at, so a strafing player stays on the line.
 *
 * <p>The pearl is thrown through the same call vanilla uses — select the pearl, then
 * {@code useItem} — so the server sees a normal item use carrying the rotation this set. A press
 * with no pearl or no one in range says so and puts the module back off, rather than leaving it
 * enabled with nothing to do.
 *
 * <p>What the simulation leaves out: terrain in the way. The angle is solved in open air, so a wall
 * between you and the target is hit like any other throw; and vanilla adds a small random spread
 * (about 1.5 % of the launch speed), which no client can predict, so a long shot can still miss a
 * moving player by half a block.
 */
public final class PearlAim extends Module {

    /** Vanilla's ender pearl throw: 1.5 m/tick, no vertical offset, 1.0 spread. */
    private static final double PEARL_SPEED   = 1.5;
    private static final double PEARL_DRAG    = 0.99;
    private static final double PEARL_GRAVITY = 0.03;
    /** The pearl spawns a tenth of a block under the eye, in {@code ThrowableProjectile}'s constructor. */
    private static final double SPAWN_DROP    = 0.1;

    /** Coarse yaw offsets from the straight bearing, nearest first so a good bound arrives early. */
    private static final double[] COARSE_YAW = {0, -10, 10, -20, 20, -30, 30, -40, 40};
    /** Coarse pitch samples: every 12° over the whole usable range, −89° (up) to 67° (down). */
    private static final double COARSE_PITCH_MIN     = -89.0;
    private static final double COARSE_PITCH_STEP    = 12.0;
    private static final int    COARSE_PITCH_SAMPLES = 14;
    /**
     * How far behind the best miss a flight is still worth finishing: {@link #better} takes a shot
     * up to five centimetres further out if it arrives sooner, so a candidate beyond that cannot win
     * and the rest of its flight is skipped.
     */
    private static final double PRUNE_MARGIN = 0.06;

    /** A candidate solution: the angles, how long the pearl is in the air, and how far it missed. */
    private record Shot(float yaw, float pitch, int ticks, double miss) {}

    private final FloatSetting range       = register(new FloatSetting("Range", 64.0f, 8.0f, 256.0f));
    private final ModeSetting  priority    = register(new ModeSetting("Target", "Distance",
            new String[]{"Distance", "Angle", "Lowest Health", "Highest Health"}));
    private final BoolSetting  lead        = register(new BoolSetting("Lead Target", true));
    private final BoolSetting  swapBack    = register(new BoolSetting("Swap Back", true));
    private final BoolSetting  feedback    = register(new BoolSetting("Chat Feedback", false));
    private final IntSetting   searchTicks = register(new IntSetting("Search Ticks", 200, 20, 400));

    /** Ticks left before the hotbar is handed back and the module turns itself off. */
    private int restoreTicks;

    public PearlAim() {
        super("PearlAim",
              "On key press, throws an ender pearl at an opponent at the angle your momentum needs.",
              Category.COMBAT);
    }

    // ── One press, one pearl ───────────────────────────────────────────────

    @Override
    public void onEnable() {
        restoreTicks = 0;
        throwAtTarget();
    }

    @EventHandler
    private void onTick(TickEvent.Pre event) {
        if (restoreTicks > 0 && --restoreTicks == 0) {
            // The item use was sent two ticks ago; putting the slot back now cannot race it.
            HotbarSlots.release(this, swapBack.get());
            setEnabled(false);
        }
    }

    private void throwAtTarget() {
        if (!Veil.inGame() || Veil.mc.gameMode == null) {
            setEnabled(false);
            return;
        }

        LocalPlayer self = Veil.mc.player;
        Player victim = pickTarget(self);
        if (victim == null) {
            say("no opponent in range");
            setEnabled(false);
            return;
        }

        int slot = pearlSlot(self);
        if (slot < 0) {
            say("no ender pearl in the hotbar");
            setEnabled(false);
            return;
        }

        Shot shot = solve(self, victim);
        // Wrapped so the camera does not spin the long way round when the bearing came out past 180°.
        self.setYRot(Mth.wrapDegrees(shot.yaw()));
        self.setXRot(shot.pitch());

        // Rotation first, then the slot, then the use: the packet carries the angles above, and the
        // server spawns the pearl from whatever it sees selected when the use arrives.
        HotbarSlots.select(this, slot);
        Veil.mc.gameMode.useItem(self, InteractionHand.MAIN_HAND);
        restoreTicks = 2;

        if (feedback.get()) {
            say(String.format("pearl at %s — %.0f° yaw, %.0f° pitch, %.1f from the aim point",
                    victim.getName().getString(), shot.yaw(), shot.pitch(), shot.miss()));
        }
    }

    /** Failures always speak up; the success line is behind Chat Feedback. */
    private void say(String text) {
        Veil.mc.gui.hud.getChat().addClientSystemMessage(Component.literal("§5§lVeil §8» §7" + text));
    }

    private static int pearlSlot(LocalPlayer self) {
        for (int i = 0; i < 9; i++) {
            if (self.getInventory().getItem(i).is(Items.ENDER_PEARL)) return i;
        }
        return -1;
    }

    // ── Who to throw at ────────────────────────────────────────────────────

    private Player pickTarget(LocalPlayer self) {
        Player best = null;
        double bestScore = Double.MAX_VALUE;
        double reach = range.get();
        Vec3 look = self.getLookAngle();
        Vec3 eye = self.getEyePosition();
        String mode = priority.get();

        for (Player candidate : Veil.mc.level.players()) {
            if (candidate == self || !candidate.isAlive() || candidate.isSpectator()) continue;
            double distance = self.distanceTo(candidate);
            if (distance > reach) continue;

            double score = switch (mode) {
                case "Angle" -> {
                    Vec3 to = candidate.getBoundingBox().getCenter().subtract(eye);
                    double length = to.length();
                    // 0 for dead centre; the dot product is clamped because rounding can push it past 1.
                    yield length < 1.0E-4 ? 0.0
                          : Math.toDegrees(Math.acos(Math.max(-1.0, Math.min(1.0, look.dot(to.scale(1.0 / length))))));
                }
                case "Lowest Health"  -> candidate.getHealth() + candidate.getAbsorptionAmount();
                case "Highest Health" -> -(candidate.getHealth() + candidate.getAbsorptionAmount());
                default -> distance;
            };

            if (score < bestScore) {
                bestScore = score;
                best = candidate;
            }
        }
        return best;
    }

    // ── The throw ──────────────────────────────────────────────────────────

    private Shot solve(LocalPlayer self, Player victim) {
        Vec3 from = new Vec3(self.getX(), self.getEyeY() - SPAWN_DROP, self.getZ());

        // Vanilla adds the shooter's tracked movement to the launch, without the vertical part while
        // the shooter is on the ground. The tracked value is what the server reads too; fall back to
        // the tick's movement if it has not been filled in yet.
        Vec3 momentum = self.getKnownMovement();
        if (momentum.lengthSqr() < 1.0E-8) momentum = self.getDeltaMovement();
        double mx = momentum.x;
        double my = self.onGround() ? 0.0 : momentum.y;
        double mz = momentum.z;

        Vec3 aim = victim.getBoundingBox().getCenter();
        Shot shot = search(from, aim, mx, my, mz);

        if (lead.get()) {
            Vec3 walk = victim.getKnownMovement();
            if (walk.lengthSqr() > 1.0E-8) {
                aim = aim.add(walk.scale(shot.ticks()));
                shot = search(from, aim, mx, my, mz);
            }
        }
        return shot;
    }

    /**
     * Four sweeps, each narrowing by a factor of four: 10° / 12° over the whole range, then 2.5° /
     * 3°, then half a degree, then a tenth. A stage's radius is twice the next stage's step, so the
     * winner it hands on is always inside the next stage's coverage.
     *
     * <p>Yaw is swept as an offset from the straight bearing rather than as an absolute angle: the
     * momentum correction is never more than a few degrees, and this keeps the sweep centred on the
     * target instead of on where the player happens to face.
     */
    private Shot search(Vec3 from, Vec3 aim, double mx, double my, double mz) {
        double bearing = Math.toDegrees(Math.atan2(-(aim.x - from.x), aim.z - from.z));
        int ticks = searchTicks.get();

        double flat = Math.sqrt((aim.x - from.x) * (aim.x - from.x)
                              + (aim.z - from.z) * (aim.z - from.z));
        double[] pitches = coarsePitches(guessPitch(flat, aim.y - from.y));

        Shot best = null;
        for (double dYaw : COARSE_YAW) {
            for (double pitch : pitches) {
                best = consider(best, from, aim, (float) (bearing + dYaw), (float) pitch,
                                mx, my, mz, ticks);
            }
        }
        if (best == null) {
            // Every candidate was cut short, which cannot happen while the bound is infinite; the
            // straight bearing is then the only answer left to give.
            return new Shot((float) bearing, 0.0f, ticks, Double.MAX_VALUE);
        }

        best = refine(best, from, aim, mx, my, mz, ticks, 4, 2.5, 3.0);
        best = refine(best, from, aim, mx, my, mz, ticks, 4, 0.5, 0.5);
        best = refine(best, from, aim, mx, my, mz, ticks, 3, 0.1, 0.1);
        return best;
    }

    /**
     * One stage: {@code radius} candidates either side of the winner on each axis. Every candidate
     * prunes against the best miss so far, which is already tight by the second stage.
     */
    private static Shot refine(Shot current, Vec3 from, Vec3 aim, double mx, double my, double mz,
                               int ticks, int radius, double yawStep, double pitchStep) {
        Shot best = current;
        float yaw0 = current.yaw();
        float pitch0 = current.pitch();
        for (int i = -radius; i <= radius; i++) {
            double yaw = yaw0 + i * yawStep;
            for (int j = -radius; j <= radius; j++) {
                best = consider(best, from, aim, (float) yaw, (float) (pitch0 + j * pitchStep),
                                mx, my, mz, ticks);
            }
        }
        return best;
    }

    /** Flies one candidate, abandoning it once it cannot beat {@code best}, and keeps the winner. */
    private static Shot consider(Shot best, Vec3 from, Vec3 aim, float yaw, float pitch,
                                 double mx, double my, double mz, int ticks) {
        double bound = best == null ? Double.MAX_VALUE : best.miss() + PRUNE_MARGIN;
        return better(best, simulate(from, aim, yaw, pitch, mx, my, mz, ticks, bound));
    }

    /**
     * Coarse pitch samples, ordered by how close they are to the flat-arc guess.
     *
     * <p>Coverage is the same whatever the order, but visiting the likeliest angle first gives the
     * rest of the sweep a tight miss to prune against from the second candidate on. Fourteen values,
     * so the insertion sort costs nothing next to a single flight.
     */
    private static double[] coarsePitches(double guess) {
        double[] values = new double[COARSE_PITCH_SAMPLES];
        for (int i = 0; i < values.length; i++) {
            values[i] = COARSE_PITCH_MIN + i * COARSE_PITCH_STEP;
        }
        for (int i = 1; i < values.length; i++) {
            double value = values[i];
            double key = Math.abs(value - guess);
            int j = i - 1;
            while (j >= 0 && Math.abs(values[j] - guess) > key) {
                values[j + 1] = values[j];
                j--;
            }
            values[j + 1] = value;
        }
        return values;
    }

    /**
     * First guess at the pitch: the no-drag arc that walks {@code flat} blocks while dropping
     * {@code dy}, solved as a quadratic in tan(pitch). Drag and momentum are ignored because this
     * only decides which coarse sample is tried first; the sweeps find the real angle. Minecraft's
     * pitch is positive downwards, so the up-arc comes back negative.
     */
    private static double guessPitch(double flat, double dy) {
        if (flat < 1.0E-3) return dy >= 0 ? -89.0 : 67.0;   // straight up / straight down
        double k = 0.5 * PEARL_GRAVITY * flat * flat / (PEARL_SPEED * PEARL_SPEED);
        double disc = flat * flat - 4.0 * k * (k + dy);
        if (disc < 0) return 0.0;                            // out of range without drag; let the sweep decide
        double tan = (flat - Math.sqrt(disc)) / (2.0 * k);    // the low arc of the two
        return Math.max(-89.0, Math.min(67.0, -Math.toDegrees(Math.atan(tan))));
    }

    /**
     * Closer wins; within five centimetres the shorter flight wins, so a flat shot is preferred over
     * the lob that happens to pass through the same point later.
     */
    private static Shot better(Shot current, Shot candidate) {
        if (current == null) return candidate;
        if (candidate.miss() < current.miss() - 0.05) return candidate;
        if (candidate.miss() < current.miss() + 0.05 && candidate.ticks() < current.ticks()) return candidate;
        return current;
    }

    /**
     * One candidate flight, in plain doubles, stopping as soon as it cannot come closer than
     * {@code bound}.
     *
     * <p>Horizontal travel is a straight line — drag scales both horizontal axes by the same factor,
     * so it never bends — which makes the horizontal distance to the aim a single dip followed by a
     * climb. Once that dip is behind us and the pearl is below the aim and still falling, both the
     * horizontal and vertical gaps only grow, so the flight is finished and the closest point is
     * already known.
     *
     * <p>It also stops once the distance is past {@code bound} on a single axis — below the aim and
     * falling, or past the aim and receding — because every later tick is then further out than the
     * best shot already in hand. A flight that stops that way can report a wider miss than its true
     * minimum; that minimum was too wide to win either way, and {@link #better} turns it down.
     */
    private static Shot simulate(Vec3 from, Vec3 aim, float yaw, float pitch, double mx, double my, double mz,
                          int ticks, double bound) {
        Vec3 look = Vec3.directionFromRotation(pitch, yaw);
        double vx = look.x * PEARL_SPEED + mx;
        double vy = look.y * PEARL_SPEED + my;
        double vz = look.z * PEARL_SPEED + mz;

        double x = from.x;
        double y = from.y;
        double z = from.z;

        double bestMissSqr = Double.MAX_VALUE;
        int bestTick = 0;
        double boundSqr = bound * bound;
        double previousHorizSqr = (x - aim.x) * (x - aim.x) + (z - aim.z) * (z - aim.z);

        for (int tick = 1; tick <= ticks; tick++) {
            // Vanilla's order: move, then lose drag and gain gravity.
            x += vx;
            y += vy;
            z += vz;
            vx *= PEARL_DRAG;
            vy = vy * PEARL_DRAG - PEARL_GRAVITY;
            vz *= PEARL_DRAG;

            double dx = x - aim.x;
            double dy = y - aim.y;
            double dz = z - aim.z;
            double horizSqr = dx * dx + dz * dz;
            double missSqr = horizSqr + dy * dy;
            if (missSqr < bestMissSqr) {
                bestMissSqr = missSqr;
                bestTick = tick;
            }

            boolean passed = horizSqr > previousHorizSqr;
            previousHorizSqr = horizSqr;

            // Past the aim and dropping: both gaps only grow from here, so nothing later can be closer.
            if (passed && vy < 0 && y < aim.y) break;
            // Falling below the aim: that gap only grows, and it is already wider than the best miss
            // on its own, so no later tick can win.
            if (vy < 0 && aim.y - y > bound) break;
            // Past the aim and receding: the same argument, horizontally.
            if (passed && horizSqr > boundSqr) break;
        }

        return new Shot(yaw, pitch, bestTick, Math.sqrt(bestMissSqr));
    }
}
