// lang: Java, file: StashESP.java, target: Fabric MC 26.3
// Port of nyx StashESP (com.nyx.modules.StashESP). Boxes and tracers on anything that says "base":
// storage block entities and the entities people keep around a base.
package com.veil.module.modules.world;

import com.veil.Veil;
import com.veil.event.EventHandler;
import com.veil.event.events.HudRenderEvent;
import com.veil.event.events.RenderWorldEvent;
import com.veil.event.events.TickEvent;
import com.veil.gui.hud.Legend;
import com.veil.module.Category;
import com.veil.module.Module;
import com.veil.setting.BoolSetting;
import com.veil.setting.FloatSetting;
import com.veil.setting.IntSetting;
import net.minecraft.client.Camera;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.core.BlockPos;
import net.minecraft.gizmos.GizmoStyle;
import net.minecraft.gizmos.Gizmos;
import net.minecraft.tags.BlockTags;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.animal.equine.AbstractChestedHorse;
import net.minecraft.world.entity.decoration.ArmorStand;
import net.minecraft.world.entity.decoration.ItemFrame;
import net.minecraft.world.entity.npc.villager.Villager;
import net.minecraft.world.entity.vehicle.minecart.MinecartChest;
import net.minecraft.world.entity.vehicle.minecart.MinecartHopper;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.entity.AbstractFurnaceBlockEntity;
import net.minecraft.world.level.block.entity.BarrelBlockEntity;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.entity.BrewingStandBlockEntity;
import net.minecraft.world.level.block.entity.ChestBlockEntity;
import net.minecraft.world.level.block.entity.ChiseledBookShelfBlockEntity;
import net.minecraft.world.level.block.entity.CrafterBlockEntity;
import net.minecraft.world.level.block.entity.DecoratedPotBlockEntity;
import net.minecraft.world.level.block.entity.DispenserBlockEntity;
import net.minecraft.world.level.block.entity.DropperBlockEntity;
import net.minecraft.world.level.block.entity.EnchantingTableBlockEntity;
import net.minecraft.world.level.block.entity.EnderChestBlockEntity;
import net.minecraft.world.level.block.entity.HopperBlockEntity;
import net.minecraft.world.level.block.entity.ShelfBlockEntity;
import net.minecraft.world.level.block.entity.ShulkerBoxBlockEntity;
import net.minecraft.world.level.block.entity.SpawnerBlockEntity;
import net.minecraft.world.level.block.entity.TrialSpawnerBlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.chunk.LevelChunk;
import net.minecraft.world.level.chunk.LevelChunkSection;
import net.minecraft.world.level.chunk.status.ChunkStatus;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.EnumMap;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Stash ESP: outlines and tracers on chests, barrels, hoppers, ender chests, shulkers, furnaces,
 * brewing stands, enchanting tables, anvils, smithing tables, spawners and the entities that only
 * appear around a base — villagers, item frames, armour stands, storage minecarts, chested donkeys,
 * mules and llamas.
 *
 * <p>Loaded chunks are scanned a few at a time from a queue rather than all at once, so the first
 * frame after walking into a new area does not stall; the results are kept per chunk and reused
 * until that chunk is scanned again. Whatever falls past Max Shown is still drawn: everything left
 * in a chunk is merged into one box for that chunk, so a distant stash never disappears just because
 * your render distance is large.
 */
public final class StashESP extends Module {

    /** What is being looked for, and the colour it is drawn in. */
    public enum Target {
        Chests("Chests", 0xFFFFA62B),
        Barrels("Barrels", 0xFFC4813A),
        Hoppers("Hoppers", 0xFF9AA0A6),
        EnderChests("Ender Chests", 0xFFA855F7),
        EnchantingTables("Enchanting Tables", 0xFF22D3EE),
        Furnaces("Furnaces", 0xFFF87171),
        SmithingTables("Smithing Tables", 0xFF60A5FA),
        BrewingStands("Brewing Stands", 0xFFFDE047),
        Anvils("Anvils", 0xFF94A3B8),
        Droppers("Droppers", 0xFF4ADE80),
        ShulkerBoxes("Shulker Boxes", 0xFFF472B6),
        OtherStorage("Other Storage", 0xFF2DD4BF),
        Spawners("Spawners", 0xFFDC2626),
        Villagers("Villagers", 0xFF34D399),
        ItemFrames("Item Frames", 0xFFE5E7EB),
        ArmorStands("Armour Stands", 0xFFD6D3D1),
        StorageMinecarts("Storage Minecarts", 0xFFFB923C),
        ChestedAnimals("Chested Animals", 0xFFA3E635);

        public final String label;
        public final int colour;

        Target(String label, int colour) {
            this.label = label;
            this.colour = colour;
        }
    }

    /** One thing worth drawing: its box, already in world space, and what it is. */
    private record Found(AABB box, Target type) {}

    // ---- Settings ----

    private final BoolSetting  boxes     = register(new BoolSetting("Boxes", true));
    private final BoolSetting  tracers   = register(new BoolSetting("Tracers", true));
    private final IntSetting   fill      = register(new IntSetting("Fill Opacity", 40, 0, 255));
    private final FloatSetting lineWidth = register(new FloatSetting("Line Width", 2.0f, 0.5f, 6.0f));
    private final IntSetting   maxShown  = register(new IntSetting("Max Shown", 500, 10, 2000));
    private final BoolSetting  legend    = register(new BoolSetting("Show Legend", true));

    private final Map<Target, BoolSetting> enabled = new EnumMap<>(Target.class);

    // ---- Scan state ----

    private final Map<Long, List<Found>> byChunk = new HashMap<>();
    private final List<Long> queue = new ArrayList<>();
    private int queueIndex;
    private int found;

    public StashESP() {
        super("StashESP",
              "Boxes and tracers on chests, shulkers, furnaces, spawners, villagers and other signs of a base.",
              Category.WORLD);
        for (Target target : Target.values()) {
            enabled.put(target, register(new BoolSetting(target.label, true)));
        }
    }

    @Override
    public void onEnable() {
        byChunk.clear();
        queue.clear();
        queueIndex = 0;
        found = 0;
    }

    private boolean on(Target target) {
        BoolSetting setting = enabled.get(target);
        return setting != null && setting.get();
    }

    // ---- Scanning ----

    @EventHandler
    private void onTick(TickEvent.Pre event) {
        if (!Veil.inGame()) return;

        if (queueIndex >= queue.size()) {
            queue.clear();
            queueIndex = 0;
            // Every chunk the game could have loaded, a little past render distance so a loaded
            // chunk is never skipped.
            int r = Math.max(2, Veil.mc.options.getEffectiveRenderDistance()) + 2;
            ChunkPos centre = Veil.mc.player.chunkPosition();
            for (int dx = -r; dx <= r; dx++) {
                for (int dz = -r; dz <= r; dz++) queue.add(ChunkPos.pack(centre.x() + dx, centre.z() + dz));
            }
            Set<Long> inRange = new HashSet<>(queue);
            byChunk.keySet().removeIf(key -> !inRange.contains(key));

            int total = 0;
            for (List<Found> list : byChunk.values()) total += list.size();
            found = total;
        }

        for (int n = 0; n < 40 && queueIndex < queue.size(); n++) scan(queue.get(queueIndex++));
    }

    private void scan(long key) {
        int cx = ChunkPos.getX(key);
        int cz = ChunkPos.getZ(key);
        LevelChunk chunk = Veil.mc.level.getChunkSource().getChunk(cx, cz, ChunkStatus.FULL, false);
        if (chunk == null) {
            byChunk.remove(key);
            return;
        }

        List<Found> list = new ArrayList<>();

        for (BlockEntity be : chunk.getBlockEntities().values()) {
            Target type = typeOf(be);
            if (type != null && on(type)) list.add(new Found(new AABB(be.getBlockPos()), type));
        }

        // Smithing tables and anvils have no block entity, so they only come out of the block scan.
        boolean smith = on(Target.SmithingTables);
        boolean anvil = on(Target.Anvils);
        if (smith || anvil) {
            LevelChunkSection[] sections = chunk.getSections();
            for (int i = 0; i < sections.length; i++) {
                LevelChunkSection section = sections[i];
                if (section == null || section.hasOnlyAir()) continue;
                if (!section.maybeHas(state -> (smith && state.is(Blocks.SMITHING_TABLE))
                        || (anvil && state.is(BlockTags.ANVIL)))) continue;
                int baseY = chunk.getSectionYFromSectionIndex(i) << 4;
                for (int y = 0; y < 16; y++) {
                    for (int z = 0; z < 16; z++) {
                        for (int x = 0; x < 16; x++) {
                            BlockState state = section.getBlockState(x, y, z);
                            BlockPos pos = new BlockPos((cx << 4) + x, baseY + y, (cz << 4) + z);
                            if (smith && state.is(Blocks.SMITHING_TABLE)) list.add(new Found(new AABB(pos), Target.SmithingTables));
                            else if (anvil && state.is(BlockTags.ANVIL)) list.add(new Found(new AABB(pos), Target.Anvils));
                        }
                    }
                }
            }
        }

        if (list.isEmpty()) byChunk.remove(key);
        else byChunk.put(key, list);
    }

    private static Target typeOf(BlockEntity be) {
        if (be instanceof ChestBlockEntity) return Target.Chests;
        if (be instanceof BarrelBlockEntity) return Target.Barrels;
        if (be instanceof HopperBlockEntity) return Target.Hoppers;
        if (be instanceof EnderChestBlockEntity) return Target.EnderChests;
        if (be instanceof EnchantingTableBlockEntity) return Target.EnchantingTables;
        if (be instanceof AbstractFurnaceBlockEntity) return Target.Furnaces;
        if (be instanceof BrewingStandBlockEntity) return Target.BrewingStands;
        if (be instanceof DropperBlockEntity) return Target.Droppers;
        if (be instanceof ShulkerBoxBlockEntity) return Target.ShulkerBoxes;
        if (be instanceof SpawnerBlockEntity || be instanceof TrialSpawnerBlockEntity) return Target.Spawners;
        if (be instanceof DispenserBlockEntity || be instanceof CrafterBlockEntity
                || be instanceof DecoratedPotBlockEntity || be instanceof ShelfBlockEntity
                || be instanceof ChiseledBookShelfBlockEntity) return Target.OtherStorage;
        return null;
    }

    private static Target entityType(Entity entity) {
        if (entity instanceof Villager) return Target.Villagers;
        if (entity instanceof ItemFrame) return Target.ItemFrames;
        if (entity instanceof ArmorStand stand && !stand.isMarker()) return Target.ArmorStands;
        if (entity instanceof MinecartChest || entity instanceof MinecartHopper) return Target.StorageMinecarts;
        if (entity instanceof AbstractChestedHorse horse && horse.hasChest()) return Target.ChestedAnimals;
        return null;
    }

    // ---- Rendering ----

    @EventHandler
    private void onRender(RenderWorldEvent event) {
        if (!Veil.inGame()) return;

        Camera camera = Veil.mc.gameRenderer.mainCamera();
        Vec3 camPos = camera.position();
        Vec3 start = camPos.add(Vec3.directionFromRotation(camera.xRot(), camera.yRot()).scale(0.6));
        float width = lineWidth.get();
        int alpha = fill.get();

        List<Found> all = new ArrayList<>();
        for (List<Found> list : byChunk.values()) {
            for (Found f : list) if (on(f.type())) all.add(f);
        }
        for (Entity entity : Veil.mc.level.entitiesForRendering()) {
            Target type = entityType(entity);
            if (type == null || !on(type) || !entity.isAlive()) continue;
            // Moved by how far the entity has travelled into this frame, or the box snaps at 20 Hz.
            Vec3 lerp = entity.getPosition(event.partialTick).subtract(entity.position());
            all.add(new Found(entity.getBoundingBox().move(lerp.x, lerp.y, lerp.z), type));
        }
        if (all.isEmpty()) return;

        all.sort(Comparator.comparingDouble(f -> f.box().getCenter().distanceToSqr(camPos)));
        int limit = Math.min(all.size(), maxShown.get());

        // Past the cap: one box per chunk for everything left in it, so distance never hides a stash.
        if (all.size() > limit) {
            Map<Long, AABB> merged = new HashMap<>();
            Map<Long, Target> mergedType = new HashMap<>();
            for (int i = limit; i < all.size(); i++) {
                Found f = all.get(i);
                long key = ChunkPos.pack((int) Math.floor(f.box().getCenter().x) >> 4,
                                         (int) Math.floor(f.box().getCenter().z) >> 4);
                merged.merge(key, f.box(), AABB::minmax);
                mergedType.merge(key, f.type(), (a, b) -> isStorage(b) && !isStorage(a) ? b : a);
            }
            for (Map.Entry<Long, AABB> e : merged.entrySet()) {
                int colour = mergedType.get(e.getKey()).colour;
                drawShape(e.getValue(), colour, width, alpha);
                if (tracers.get()) {
                    Gizmos.line(start, e.getValue().getCenter(), colour, width).setAlwaysOnTop();
                }
            }
        }

        for (int i = 0; i < limit; i++) {
            Found f = all.get(i);
            drawShape(f.box(), f.type().colour, width, alpha);
            if (tracers.get()) Gizmos.line(start, f.box().getCenter(), f.type().colour, width).setAlwaysOnTop();
        }
    }

    private void drawShape(AABB box, int colour, float width, int alpha) {
        if (!boxes.get()) return;
        GizmoStyle style = alpha > 0
                ? GizmoStyle.strokeAndFill(colour, width, (alpha << 24) | (colour & 0xFFFFFF))
                : GizmoStyle.stroke(colour, width);
        Gizmos.cuboid(box, style).setAlwaysOnTop();
    }

    private static boolean isStorage(Target type) {
        return type == Target.Chests || type == Target.Barrels || type == Target.ShulkerBoxes
                || type == Target.EnderChests || type == Target.Hoppers;
    }

    @EventHandler
    private void onHud(HudRenderEvent event) {
        if (!Veil.inGame() || !legend.get()) return;
        if (Veil.mc.gui != null && Veil.mc.gui.hud.isHidden()) return;

        Map<Target, Integer> counts = new EnumMap<>(Target.class);
        for (List<Found> list : byChunk.values()) {
            for (Found f : list) counts.merge(f.type(), 1, Integer::sum);
        }
        for (Entity entity : Veil.mc.level.entitiesForRendering()) {
            Target type = entityType(entity);
            if (type != null && entity.isAlive()) counts.merge(type, 1, Integer::sum);
        }

        List<Legend.Entry> entries = new ArrayList<>();
        for (Target target : Target.values()) {
            if (!on(target)) continue;
            int n = counts.getOrDefault(target, 0);
            entries.add(new Legend.Entry(target.label, target.colour, n > 0 ? String.valueOf(n) : null));
        }

        GuiGraphicsExtractor ctx = event.ctx;
        Font font = Veil.mc.font;
        Legend.draw(ctx, font, "Stash ESP", entries);
    }

    @Override
    public String suffix() {
        return found > 0 ? String.valueOf(found) : null;
    }
}
