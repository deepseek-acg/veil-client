// lang: Java, file: Trajectories.java, target: Fabric MC 26.3
package com.veil.module.modules.render;

import com.veil.Veil;
import com.veil.event.EventHandler;
import com.veil.event.events.RenderWorldEvent;
import com.veil.event.events.TickEvent;
import com.veil.module.Category;
import com.veil.module.Module;
import com.veil.setting.BoolSetting;
import com.veil.setting.FloatSetting;
import com.veil.setting.IntSetting;
import net.minecraft.core.Direction;
import net.minecraft.gizmos.GizmoStyle;
import net.minecraft.gizmos.Gizmos;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.entity.projectile.Projectile;
import net.minecraft.world.entity.projectile.ProjectileUtil;
import net.minecraft.world.entity.projectile.ThrowableProjectile;
import net.minecraft.world.entity.projectile.arrow.AbstractArrow;
import net.minecraft.world.entity.projectile.hurtingprojectile.AbstractHurtingProjectile;
import net.minecraft.world.level.ClipContext;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.EntityHitResult;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.phys.Vec3;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

/**
 * Trajectories: the flight path of every projectile around you, drawn as a line, and a plate on the
 * spot where it will land.
 *
 * <p>This follows projectiles that already exist rather than previewing a shot you have not taken:
 * their velocity is the server's, so the prediction is the flight that is actually happening — an
 * arrow dropping on you from a bow you cannot see, a pearl arcing over a wall, a fireball curving
 * around a corner. Block collision is the same voxel clip the game uses, so the landing plate sits on
 * the face the projectile will hit, and an entity in the way ends the line on that entity instead.
 *
 * <p>The simulation runs once per tick on {@link TickEvent.Pre} and the paths are only drawn per frame
 * on {@link RenderWorldEvent}, moved by the interpolated position like the ESP overlays: a hundred
 * voxel rays per projectile is fine twenty times a second and wasteful at a few hundred frames.
 *
 * <p>Not modelled: liquids (drag and buoyancy), bubbles, portals, and a mace's or trident's own
 * behaviour beyond plain gravity and drag. A shot that will end in water therefore reads as landing
 * where it first touches the surface, which for an arrow is where it would have landed anyway.
 */
public final class Trajectories extends Module {

    private static final int MINE  = 0xFF2BD9C0;   // your own shot
    private static final int OTHER = 0xFFFF7A45;   // someone else's
    private static final int LAND  = 0xFFFFE04A;   // the plate on the ground
    private static final int HIT   = 0xFFFF4040;   // the plate on whatever it will hit

    // Vanilla's per-tick motion for each family: position += velocity, then velocity loses drag and
    // gains gravity. Arrows fall faster than thrown items; fireballs and wind charges do not fall.
    private static final double ARROW_DRAG = 0.99, ARROW_GRAVITY = 0.05;
    private static final double THROW_DRAG = 0.99, THROW_GRAVITY = 0.03;
    private static final double BLAST_DRAG = 0.95, BLAST_GRAVITY = 0.0;

    /** One projectile's predicted flight, rebuilt every tick. */
    private static final class Shot {
        final Entity projectile;
        final List<Vec3> path;
        /** Where it stops, or null when it is still flying after Ticks Ahead. */
        final Vec3 impact;
        /** The block face it lands on; null for an entity hit or no impact. */
        final Direction face;
        /** The box of whoever it lands on; null for a block hit or no impact. */
        final AABB target;
        final boolean mine;

        Shot(Entity projectile, List<Vec3> path, Vec3 impact, Direction face, AABB target, boolean mine) {
            this.projectile = projectile;
            this.path = path;
            this.impact = impact;
            this.face = face;
            this.target = target;
            this.mine = mine;
        }
    }

    private final FloatSetting range      = register(new FloatSetting("Range", 96.0f, 8.0f, 256.0f));
    private final IntSetting   ticksAhead = register(new IntSetting("Ticks Ahead", 160, 20, 400));
    private final FloatSetting lineWidth  = register(new FloatSetting("Line Width", 1.5f, 0.5f, 6.0f));
    private final BoolSetting  highlight  = register(new BoolSetting("Highlight Landing", true));
    private final FloatSetting plateSize  = register(new FloatSetting("Highlight Size", 0.75f, 0.25f, 3.0f));
    private final BoolSetting  stopAtMobs = register(new BoolSetting("Stop At Entities", true));
    private final IntSetting   maxTracked = register(new IntSetting("Max Tracked", 6, 1, 16));

    private List<Shot> shots = List.of();

    public Trajectories() {
        super("Trajectories",
              "Lines along the flight path of nearby projectiles, with a plate where each one will land.",
              Category.RENDER);
    }

    @Override
    public void onDisable() {
        shots = List.of();
    }

    // ── Prediction ─────────────────────────────────────────────────────────

    @EventHandler
    private void onTick(TickEvent.Pre event) {
        if (!Veil.inGame() || Veil.mc.level == null || Veil.mc.player == null) {
            shots = List.of();
            return;
        }

        Player self = Veil.mc.player;
        double reach = range.get();
        List<Entity> found = new ArrayList<>();
        for (Entity entity : Veil.mc.level.entitiesForRendering()) {
            if (!isProjectile(entity)) continue;
            if (entity.distanceToSqr(self) > reach * reach) continue;
            // A landed arrow keeps its velocity at zero; there is no flight left to draw.
            if (entity.getDeltaMovement().lengthSqr() < 1.0E-6) continue;
            found.add(entity);
        }
        if (found.isEmpty()) {
            shots = List.of();
            return;
        }

        found.sort(Comparator.comparingDouble(entity -> entity.distanceToSqr(self)));
        int tracked = Math.min(found.size(), maxTracked.get());
        List<Shot> built = new ArrayList<>(tracked);
        for (int i = 0; i < tracked; i++) built.add(predict(found.get(i)));
        shots = built;
    }

    private static boolean isProjectile(Entity entity) {
        return entity instanceof AbstractArrow
            || entity instanceof ThrowableProjectile
            || entity instanceof AbstractHurtingProjectile;
    }

    private Shot predict(Entity projectile) {
        Level level = Veil.mc.level;
        Vec3 pos = projectile.position();
        Vec3 vel = projectile.getDeltaMovement();

        double drag = THROW_DRAG;
        double gravity = THROW_GRAVITY;
        if (projectile instanceof AbstractArrow) {
            drag = ARROW_DRAG;
            gravity = ARROW_GRAVITY;
        } else if (projectile instanceof AbstractHurtingProjectile) {
            drag = BLAST_DRAG;
            gravity = BLAST_GRAVITY;
        }

        int ticks = ticksAhead.get();
        // Roughly sixty points whatever the budget, so a long prediction is not sixty lines per tick
        // of flight and a short one is not a visible chain of straight hops.
        int step = Math.max(1, ticks / 60);
        boolean mine = projectile instanceof Projectile shot && shot.getOwner() == Veil.mc.player;

        List<Vec3> path = new ArrayList<>();
        path.add(pos);

        for (int tick = 1; tick <= ticks; tick++) {
            Vec3 next = pos.add(vel);

            BlockHitResult block = level.clip(new ClipContext(
                    pos, next, ClipContext.Block.COLLIDER, ClipContext.Fluid.NONE, projectile));
            boolean landed = block.getType() != HitResult.Type.MISS;
            Vec3 end = landed ? block.getLocation() : next;

            EntityHitResult entityHit = null;
            if (!landed && stopAtMobs.get() && projectile instanceof Projectile shot) {
                entityHit = ProjectileUtil.getEntityHitResult(level, shot, pos, end,
                        shot.getBoundingBox().expandTowards(vel).inflate(1.0),
                        candidate -> candidate != shot && candidate != shot.getOwner()
                                  && candidate.isAlive() && !candidate.isSpectator());
                if (entityHit != null) {
                    landed = true;
                    end = entityHit.getLocation();
                }
            }

            if (landed || tick % step == 0) path.add(end);
            pos = end;

            if (landed) {
                Direction face = entityHit == null && block.getType() != HitResult.Type.MISS
                        ? block.getDirection() : null;
                AABB target = entityHit == null ? null : entityHit.getEntity().getBoundingBox();
                return new Shot(projectile, path, end, face, target, mine);
            }

            vel = new Vec3(vel.x * drag, vel.y * drag - gravity, vel.z * drag);
        }

        return new Shot(projectile, path, null, null, null, mine);
    }

    // ── Drawing ────────────────────────────────────────────────────────────

    @EventHandler
    private void onRender(RenderWorldEvent event) {
        if (!Veil.inGame() || shots.isEmpty()) return;

        float width = lineWidth.get();
        for (Shot shot : shots) {
            if (shot.projectile.isRemoved()) continue;

            // The path is worked out at the tick position; shift it by what the projectile has since
            // travelled, or the whole line stutters backwards every frame.
            Vec3 shift = shot.projectile.getPosition(event.partialTick).subtract(shot.projectile.position());
            int colour = shot.mine ? MINE : OTHER;

            List<Vec3> path = shot.path;
            for (int i = 1; i < path.size(); i++) {
                Gizmos.line(path.get(i - 1).add(shift), path.get(i).add(shift), colour, width)
                        .setAlwaysOnTop();
            }

            if (!highlight.get() || shot.impact == null) continue;

            if (shot.target != null) {
                Gizmos.cuboid(shot.target, GizmoStyle.strokeAndFill(HIT, width, 0x40FF4040))
                        .setAlwaysOnTop();
            } else if (shot.face != null) {
                plate(shot.impact.add(shift), shot.face, width);
            }
        }
    }

    /** A flat square on the face the projectile lands on, a hair above it so it is not z-fighting. */
    private void plate(Vec3 impact, Direction face, float width) {
        double size = plateSize.get();
        double thin = 0.01;
        Vec3 step = new Vec3(face.getStepX(), face.getStepY(), face.getStepZ()).scale(thin);
        Vec3 centre = impact.add(step);

        Vec3 half = switch (face.getAxis()) {
            case Y -> new Vec3(size, thin, size);
            case X -> new Vec3(thin, size, size);
            default -> new Vec3(size, size, thin);
        };

        Gizmos.rect(centre.subtract(half), centre.add(half), face,
                    GizmoStyle.strokeAndFill(LAND, width, 0x66FFE04A)).setAlwaysOnTop();
    }
}
