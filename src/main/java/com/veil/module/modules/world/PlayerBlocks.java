// lang: Java, file: PlayerBlocks.java, target: Fabric MC 26.3
// Port of nyx PlayerBlocks (com.nyx.modules.PlayerBlocks). Highlights blocks the world does not
// generate on its own, so anything shown was placed by a player.
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
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.gizmos.GizmoStyle;
import net.minecraft.gizmos.Gizmos;
import net.minecraft.resources.ResourceKey;
import net.minecraft.tags.BlockTags;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.monster.illager.AbstractIllager;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.chunk.LevelChunk;
import net.minecraft.world.level.chunk.LevelChunkSection;
import net.minecraft.world.level.chunk.status.ChunkStatus;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;

import java.util.ArrayList;
import java.util.EnumMap;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Player Blocks: outlines blocks that never generate naturally — concrete, shulker boxes, beacons,
 * enchanting tables, anvils, ender chests, respawn anchors, mineral blocks, redstone parts and more
 * — plus blocks that only generate in another dimension (end stone and purpur outside the End,
 * nether bricks outside the Nether).
 *
 * <p>Common building materials (planks, glass, wool, stone bricks, torches, beds…) are flagged too,
 * but only where the world would not have put them itself: not near a natural structure (recognised
 * by the blocks only a structure has, and by illagers, who live in outposts and mansions) and not in
 * a biome where they occur naturally (snow in snowy biomes, terracotta in badlands, prismarine in
 * oceans, shipwreck wood on beaches).
 *
 * <p>NyX's editable block list is replaced by the built-in lists: mist's click GUI has no block
 * picker, and a setting that cannot be edited is worse than none. Everything else — the marker
 * radii, the structure combinations, the biome rules — is the nyx logic.
 */
public final class PlayerBlocks extends Module {

    /** What kind of player-placed block it is. Kept for the labels the legend and the list use. */
    public enum Type {
        Concrete("Concrete", 0xFF38BDF8),
        ShulkerBoxes("Shulker Boxes", 0xFFF472B6),
        Valuables("Valuable Blocks", 0xFFFACC15),
        Utility("Utility Blocks", 0xFFA78BFA),
        Redstone("Redstone Parts", 0xFFF87171),
        Decoration("Decoration", 0xFF4ADE80),
        WrongDimension("Wrong Dimension", 0xFFFB923C),
        BuildingMaterials("Building Materials", 0xFF2DD4BF),
        NaturalStructures("Natural Structures (ignored)", 0xFF9CA3AF);

        public final String label;
        public final int colour;

        Type(String label, int colour) {
            this.label = label;
            this.colour = colour;
        }
    }

    /** One loud colour for everything a player placed, so it stands out from Stash ESP and the world. */
    private static final int PLACED_COLOUR = 0xFFFF00FF;

    private static final Map<Block, Type> TYPES = new HashMap<>();

    private static void put(Type type, List<Block> blocks) {
        for (Block block : blocks) TYPES.put(block, type);
    }

    static {
        put(Type.Concrete, Blocks.CONCRETE.asList());
        put(Type.Concrete, Blocks.CONCRETE_POWDER.asList());
        put(Type.Concrete, Blocks.CONCRETE_STAIRS.asList());
        put(Type.Concrete, Blocks.CONCRETE_SLAB.asList());
        put(Type.ShulkerBoxes, List.of(Blocks.SHULKER_BOX));
        put(Type.ShulkerBoxes, Blocks.DYED_SHULKER_BOX.asList());
        put(Type.Valuables, List.of(Blocks.BEACON, Blocks.NETHERITE_BLOCK, Blocks.DIAMOND_BLOCK, Blocks.EMERALD_BLOCK,
                Blocks.IRON_BLOCK, Blocks.LAPIS_BLOCK, Blocks.REDSTONE_BLOCK));
        put(Type.Utility, List.of(Blocks.ENCHANTING_TABLE, Blocks.ANVIL, Blocks.CHIPPED_ANVIL, Blocks.DAMAGED_ANVIL,
                Blocks.ENDER_CHEST, Blocks.RESPAWN_ANCHOR, Blocks.LODESTONE, Blocks.CONDUIT, Blocks.BEEHIVE,
                Blocks.SCAFFOLDING, Blocks.NOTE_BLOCK, Blocks.JUKEBOX));
        put(Type.Redstone, List.of(Blocks.OBSERVER, Blocks.COMPARATOR, Blocks.DAYLIGHT_DETECTOR, Blocks.TARGET,
                Blocks.SLIME_BLOCK, Blocks.HONEY_BLOCK, Blocks.HONEYCOMB_BLOCK));
        put(Type.Decoration, List.of(Blocks.TINTED_GLASS, Blocks.OCHRE_FROGLIGHT, Blocks.VERDANT_FROGLIGHT,
                Blocks.PEARLESCENT_FROGLIGHT, Blocks.QUARTZ_BLOCK, Blocks.SMOOTH_QUARTZ, Blocks.QUARTZ_PILLAR,
                Blocks.CHISELED_QUARTZ_BLOCK, Blocks.QUARTZ_BRICKS, Blocks.RED_NETHER_BRICKS, Blocks.CRIMSON_PLANKS,
                Blocks.WARPED_PLANKS, Blocks.DRIED_KELP_BLOCK));
    }

    /** Natural blocks that must never count as player-placed. */
    private static final Set<Block> NEVER_FLAG = Set.of(Blocks.MOSS_CARPET, Blocks.PALE_MOSS_CARPET);

    private static final Set<Block> JOB_SITES = Set.of(Blocks.COMPOSTER, Blocks.LECTERN, Blocks.FLETCHING_TABLE,
            Blocks.SMITHING_TABLE, Blocks.CARTOGRAPHY_TABLE, Blocks.LOOM, Blocks.STONECUTTER, Blocks.GRINDSTONE,
            Blocks.BLAST_FURNACE, Blocks.SMOKER, Blocks.BARREL, Blocks.CAULDRON, Blocks.BREWING_STAND);


    private record Hit(BlockPos pos, Type type) {}

    /** Everything found in one chunk. */
    private static final class ChunkData {
        final List<Hit> strong = new ArrayList<>();         // never natural: always shown
        final List<BlockPos> materials = new ArrayList<>();  // building materials not natural for the biome
        final List<BlockPos> markers = new ArrayList<>();    // blocks that prove a structure is here
        int structureRadius = -1;                            // chunks around this one in that structure
    }

    // ---- Settings ----

    private final BoolSetting  wrongDimension = register(new BoolSetting("Wrong Dimension Blocks", true));
    private final IntSetting   minPerChunk    = register(new IntSetting("Min Per Chunk", 1, 1, 64));
    private final BoolSetting  common         = register(new BoolSetting("Building Materials", true));
    private final IntSetting   commonMin      = register(new IntSetting("Materials Min Per Chunk", 4, 1, 64));
    private final BoolSetting  structureCheck = register(new BoolSetting("Skip Structures", true));
    private final BoolSetting  biomeCheck     = register(new BoolSetting("Check Biome", true));
    private final BoolSetting  showStructures = register(new BoolSetting("Show Structures", false));
    private final BoolSetting  fill           = register(new BoolSetting("Fill", false));
    private final IntSetting   maxShown       = register(new IntSetting("Max Shown", 400, 10, 3000));
    private final BoolSetting  legend         = register(new BoolSetting("Show Legend", true));
    private final FloatSetting lineWidth      = register(new FloatSetting("Line Width", 1.5f, 0.5f, 6.0f));

    // ---- Scan state ----

    private final Map<Long, ChunkData> byChunk = new HashMap<>();
    private final Map<Long, Integer> entityStructures = new HashMap<>();
    private final Map<Long, Boolean> structureCache = new HashMap<>();
    private final List<Long> queue = new ArrayList<>();
    private int queueIndex, total;

    public PlayerBlocks() {
        super("PlayerBlocks",
              "Outlines blocks that never generate naturally, so anything it shows was placed by a player.",
              Category.WORLD);
    }

    @Override
    public void onEnable() {
        byChunk.clear();
        entityStructures.clear();
        structureCache.clear();
        queue.clear();
        queueIndex = 0;
        total = 0;
    }

    /** The "never natural" list, taken from nyx. Built once: it is read for every chunk scanned. */
    private static final Set<Block> STRONG = strongDefaults();

    /** Building materials, built once by scanning the whole block registry. */
    private static final Set<Block> MATERIALS = materialDefaults();

    private static Set<Block> strongDefaults() {
        List<Block> list = new ArrayList<>();
        list.addAll(Blocks.CONCRETE.asList());
        list.addAll(Blocks.CONCRETE_POWDER.asList());
        list.addAll(Blocks.CONCRETE_STAIRS.asList());
        list.addAll(Blocks.CONCRETE_SLAB.asList());
        list.add(Blocks.SHULKER_BOX);
        list.addAll(Blocks.DYED_SHULKER_BOX.asList());
        list.addAll(List.of(Blocks.BEACON, Blocks.ENCHANTING_TABLE, Blocks.ANVIL, Blocks.CHIPPED_ANVIL,
                Blocks.DAMAGED_ANVIL, Blocks.ENDER_CHEST, Blocks.RESPAWN_ANCHOR, Blocks.NETHERITE_BLOCK,
                Blocks.DIAMOND_BLOCK, Blocks.EMERALD_BLOCK, Blocks.IRON_BLOCK, Blocks.LAPIS_BLOCK,
                Blocks.REDSTONE_BLOCK, Blocks.HONEY_BLOCK, Blocks.HONEYCOMB_BLOCK, Blocks.SLIME_BLOCK,
                Blocks.OBSERVER, Blocks.COMPARATOR, Blocks.DAYLIGHT_DETECTOR, Blocks.TARGET, Blocks.LODESTONE,
                Blocks.BEEHIVE, Blocks.CONDUIT, Blocks.SCAFFOLDING, Blocks.DRIED_KELP_BLOCK, Blocks.NOTE_BLOCK,
                Blocks.JUKEBOX, Blocks.TINTED_GLASS, Blocks.OCHRE_FROGLIGHT, Blocks.VERDANT_FROGLIGHT,
                Blocks.PEARLESCENT_FROGLIGHT, Blocks.QUARTZ_BLOCK, Blocks.SMOOTH_QUARTZ, Blocks.QUARTZ_PILLAR,
                Blocks.CHISELED_QUARTZ_BLOCK, Blocks.QUARTZ_BRICKS, Blocks.RED_NETHER_BRICKS, Blocks.CRIMSON_PLANKS,
                Blocks.WARPED_PLANKS));
        return new HashSet<>(list);
    }

    /** Common blocks players build with (by name, so every wood type and colour is included). */
    private static Set<Block> materialDefaults() {
        List<Block> list = new ArrayList<>(List.of(Blocks.GLASS, Blocks.GLASS_PANE, Blocks.TORCH, Blocks.WALL_TORCH,
                Blocks.LANTERN, Blocks.SOUL_LANTERN, Blocks.COBBLESTONE, Blocks.MOSSY_COBBLESTONE, Blocks.STONE_BRICKS,
                Blocks.MOSSY_STONE_BRICKS, Blocks.CRACKED_STONE_BRICKS, Blocks.CHISELED_STONE_BRICKS, Blocks.BRICKS,
                Blocks.SMOOTH_STONE, Blocks.SMOOTH_STONE_SLAB, Blocks.POLISHED_ANDESITE, Blocks.POLISHED_DIORITE,
                Blocks.POLISHED_GRANITE, Blocks.IRON_BARS, Blocks.LADDER, Blocks.HAY_BLOCK, Blocks.SNOW_BLOCK,
                Blocks.PACKED_ICE, Blocks.BLUE_ICE, Blocks.PRISMARINE, Blocks.PRISMARINE_BRICKS, Blocks.DARK_PRISMARINE,
                Blocks.SEA_LANTERN, Blocks.MUD_BRICKS, Blocks.CUT_SANDSTONE, Blocks.SMOOTH_SANDSTONE,
                Blocks.CHISELED_SANDSTONE, Blocks.NETHERRACK, Blocks.TERRACOTTA));
        for (Block block : BuiltInRegistries.BLOCK) {
            String id = BuiltInRegistries.BLOCK.getKey(block).getPath();
            // Crimson, warped and nether brick blocks are natural in the Nether and already flagged.
            if (id.startsWith("crimson") || id.startsWith("warped") || id.startsWith("nether_brick")) continue;
            if (id.contains("moss_carpet")) continue;   // grows naturally (lush caves, pale gardens)
            if (id.endsWith("_planks") || id.endsWith("_wool") || id.endsWith("_carpet") || id.endsWith("_bed")
                    || id.endsWith("_stained_glass") || id.endsWith("_stained_glass_pane") || id.endsWith("_terracotta")
                    || id.endsWith("_fence") || id.endsWith("_fence_gate") || id.endsWith("_door")
                    || id.endsWith("_trapdoor") || id.endsWith("_sign") || id.endsWith("_banner") || id.endsWith("rail")
                    || id.equals("cobblestone_stairs") || id.equals("cobblestone_slab") || id.equals("cobblestone_wall")
                    || id.equals("stone_brick_stairs") || id.equals("stone_brick_slab") || id.equals("stone_brick_wall")
                    || id.equals("brick_stairs") || id.equals("brick_slab") || id.equals("brick_wall")) {
                if (!list.contains(block)) list.add(block);
            }
        }
        return new HashSet<>(list);
    }

    /** Blocks that only generate naturally in one dimension. */
    private static boolean wrongDim(BlockState state, ResourceKey<Level> dim) {
        if (dim != Level.END && (state.is(Blocks.END_STONE) || state.is(Blocks.END_STONE_BRICKS)
                || state.is(Blocks.PURPUR_BLOCK) || state.is(Blocks.PURPUR_PILLAR) || state.is(Blocks.END_ROD))) return true;
        return dim != Level.NETHER && state.is(Blocks.NETHER_BRICKS);
    }

    /** How many chunks around a single give-away block count as that structure (0 = not a give-away block). */
    private static int markerRadius(BlockState state, ResourceKey<Level> dim) {
        Block block = state.getBlock();
        if (block == Blocks.BELL) return 5;                                                  // village
        if (block == Blocks.END_PORTAL_FRAME) return 6;                                      // stronghold
        if (block == Blocks.INFESTED_STONE_BRICKS || block == Blocks.INFESTED_MOSSY_STONE_BRICKS
                || block == Blocks.INFESTED_CRACKED_STONE_BRICKS
                || block == Blocks.INFESTED_CHISELED_STONE_BRICKS) return 5;                 // stronghold
        if (block == Blocks.SPAWNER) return 2;                                               // dungeon / mineshaft / fortress
        if (block == Blocks.TRIAL_SPAWNER || block == Blocks.VAULT) return 4;                // trial chambers
        if (block == Blocks.REINFORCED_DEEPSLATE || block == Blocks.SCULK_SHRIEKER) return 7; // ancient city
        if (block == Blocks.SUSPICIOUS_SAND || block == Blocks.SUSPICIOUS_GRAVEL) return 3;  // ruins / pyramid / well
        if (block == Blocks.CRYING_OBSIDIAN) return 2;                                       // ruined portal
        if (block == Blocks.GILDED_BLACKSTONE) return 5;                                     // bastion
        if (dim == Level.END && (block == Blocks.PURPUR_BLOCK || block == Blocks.END_ROD)) return 4; // end city / ship
        return 0;
    }

    private static boolean isMarkerCandidate(BlockState state) {
        Block block = state.getBlock();
        return block == Blocks.BELL || block == Blocks.END_PORTAL_FRAME || block == Blocks.INFESTED_STONE_BRICKS
                || block == Blocks.INFESTED_MOSSY_STONE_BRICKS || block == Blocks.INFESTED_CRACKED_STONE_BRICKS
                || block == Blocks.INFESTED_CHISELED_STONE_BRICKS || block == Blocks.SPAWNER
                || block == Blocks.TRIAL_SPAWNER || block == Blocks.VAULT || block == Blocks.REINFORCED_DEEPSLATE
                || block == Blocks.SCULK_SHRIEKER || block == Blocks.SUSPICIOUS_SAND || block == Blocks.SUSPICIOUS_GRAVEL
                || block == Blocks.CRYING_OBSIDIAN || block == Blocks.GILDED_BLACKSTONE || block == Blocks.PURPUR_BLOCK
                || block == Blocks.END_ROD || block == Blocks.DIRT_PATH || block == Blocks.COBWEB || block == Blocks.RAIL
                || block == Blocks.TNT || JOB_SITES.contains(block) || block == Blocks.MOSSY_STONE_BRICKS
                || block == Blocks.CRACKED_STONE_BRICKS || block == Blocks.STONE_BRICKS
                || block == Blocks.CHISELED_SANDSTONE || block == Blocks.MOSSY_COBBLESTONE
                || block == Blocks.CHISELED_STONE_BRICKS || block == Blocks.SNOW_BLOCK || state.is(BlockTags.BEDS);
    }

    // ---- Structures / biomes ----

    private boolean inStructure(int cx, int cz) {
        return structureCache.computeIfAbsent(ChunkPos.pack(cx, cz), key -> computeInStructure(cx, cz));
    }

    private boolean computeInStructure(int cx, int cz) {
        for (Map.Entry<Long, ChunkData> e : byChunk.entrySet()) {
            int r = e.getValue().structureRadius;
            if (r < 0) continue;
            if (Math.abs(ChunkPos.getX(e.getKey()) - cx) <= r && Math.abs(ChunkPos.getZ(e.getKey()) - cz) <= r) return true;
        }
        for (Map.Entry<Long, Integer> e : entityStructures.entrySet()) {
            int r = e.getValue();
            if (Math.abs(ChunkPos.getX(e.getKey()) - cx) <= r && Math.abs(ChunkPos.getZ(e.getKey()) - cz) <= r) return true;
        }
        return false;
    }

    private String biomeAt(BlockPos pos) {
        return Veil.mc.level.getBiome(pos).unwrapKey().map(key -> key.identifier().getPath()).orElse("");
    }

    private static boolean coldBiome(String biome) {
        return biome.contains("snow") || biome.contains("frozen") || biome.contains("ice") || biome.contains("grove")
                || biome.contains("peaks") || biome.contains("slopes") || biome.contains("iceberg");
    }

    private static boolean naturalForDimension(Block block, String id, ResourceKey<Level> dim) {
        if (dim == Level.NETHER) {
            return id.contains("netherrack") || id.contains("blackstone") || id.contains("basalt") || id.startsWith("soul_")
                    || id.contains("magma") || id.contains("glowstone") || id.contains("wart_block")
                    || id.contains("shroomlight") || id.contains("nylium") || id.endsWith("_stem")
                    || id.endsWith("_hyphae") || id.contains("nether_brick") || id.contains("quartz_ore")
                    || id.contains("obsidian") || block == Blocks.GOLD_BLOCK || id.endsWith("chain")
                    || block == Blocks.BONE_BLOCK || block == Blocks.GRAVEL || block == Blocks.LAVA
                    || id.contains("lantern");
        }
        if (dim == Level.END) {
            return id.contains("end_stone") || id.contains("purpur") || block == Blocks.END_ROD
                    || id.contains("obsidian") || id.endsWith("stained_glass") || id.endsWith("stained_glass_pane")
                    || block == Blocks.LADDER || id.endsWith("_banner");
        }
        return false;
    }

    private static boolean naturalForBiome(BlockState state, String biome) {
        Block block = state.getBlock();
        String id = BuiltInRegistries.BLOCK.getKey(block).getPath();
        boolean ocean = biome.contains("ocean") || biome.contains("beach");
        if (block == Blocks.SNOW_BLOCK || block == Blocks.POWDER_SNOW) return coldBiome(biome);
        if (block == Blocks.ICE || block == Blocks.PACKED_ICE || block == Blocks.BLUE_ICE) {
            return coldBiome(biome) || biome.contains("river");
        }
        if (id.endsWith("terracotta") && !id.endsWith("glazed_terracotta")) return biome.contains("badlands");
        if (id.contains("prismarine") || block == Blocks.SEA_LANTERN) return biome.contains("ocean");
        // Shipwrecks and ocean ruins: wood, wooden doors/trapdoors/fences and stone bricks in oceans and on beaches.
        return ocean && (id.endsWith("_planks") || id.endsWith("_fence") || id.endsWith("_door")
                || id.endsWith("_trapdoor") || id.contains("stone_brick") || block == Blocks.CHISELED_STONE_BRICKS);
    }

    // ---- Scanning ----

    @EventHandler
    private void onTick(TickEvent.Pre event) {
        if (!Veil.inGame()) return;

        if (queueIndex >= queue.size()) {
            queue.clear();
            queueIndex = 0;
            int r = Math.max(2, Veil.mc.options.getEffectiveRenderDistance());
            ChunkPos centre = Veil.mc.player.chunkPosition();
            for (int dx = -r; dx <= r; dx++) {
                for (int dz = -r; dz <= r; dz++) queue.add(ChunkPos.pack(centre.x() + dx, centre.z() + dz));
            }
            Set<Long> inRange = new HashSet<>(queue);
            byChunk.keySet().removeIf(key -> !inRange.contains(key));
            entityStructures.keySet().removeIf(key -> !inRange.contains(key));
            structureCache.clear();

            // Illagers live in outposts and mansions; players do not keep them around.
            for (Entity entity : Veil.mc.level.entitiesForRendering()) {
                if (entity instanceof AbstractIllager) {
                    entityStructures.merge(ChunkPos.pack(entity.blockPosition().getX() >> 4,
                            entity.blockPosition().getZ() >> 4), 4, Math::max);
                }
            }

            total = 0;
            for (Map.Entry<Long, ChunkData> e : byChunk.entrySet()) total += shown(e.getKey(), e.getValue()).size();
        }

        int perTick = common.get() ? 12 : 24;   // reading materials looks at far more blocks
        for (int n = 0; n < perTick && queueIndex < queue.size(); n++) scan(queue.get(queueIndex++));
    }

    private void scan(long key) {
        int cx = ChunkPos.getX(key);
        int cz = ChunkPos.getZ(key);
        LevelChunk chunk = Veil.mc.level.getChunkSource().getChunk(cx, cz, ChunkStatus.FULL, false);
        if (chunk == null) {
            byChunk.remove(key);
            return;
        }

        ResourceKey<Level> dim = Veil.mc.level.dimension();
        boolean wd = wrongDimension.get(), com = common.get(), bio = biomeCheck.get();
        Set<Block> strong = STRONG;
        Set<Block> materials = com ? MATERIALS : Set.of();
        ChunkData data = new ChunkData();

        Set<Block> jobSites = new HashSet<>();
        boolean path = false, cobweb = false, rail = false, tnt = false, chiseledSand = false,
                mossyCobble = false, chiseledBricks = false, snow = false, bed = false;
        boolean[] deepBricks = new boolean[3];
        Map<Long, String> biomeCache = new HashMap<>();

        LevelChunkSection[] sections = chunk.getSections();
        for (int i = 0; i < sections.length; i++) {
            LevelChunkSection section = sections[i];
            if (section == null || section.hasOnlyAir()) continue;
            if (!section.maybeHas(state -> strong.contains(state.getBlock())
                    || (wd && wrongDim(state, dim))
                    || (com && materials.contains(state.getBlock()))
                    || isMarkerCandidate(state))) continue;

            int baseY = chunk.getSectionYFromSectionIndex(i) << 4;
            for (int y = 0; y < 16; y++) {
                for (int z = 0; z < 16; z++) {
                    for (int x = 0; x < 16; x++) {
                        BlockState state = section.getBlockState(x, y, z);
                        if (state.isAir()) continue;
                        Block block = state.getBlock();
                        BlockPos pos = new BlockPos((cx << 4) + x, baseY + y, (cz << 4) + z);

                        int radius = markerRadius(state, dim);
                        if (radius > 0) {
                            data.markers.add(pos);
                            data.structureRadius = Math.max(data.structureRadius, radius);
                        }
                        if (JOB_SITES.contains(block)) jobSites.add(block);
                        if (block == Blocks.DIRT_PATH) path = true;
                        if (block == Blocks.COBWEB) cobweb = true;
                        if (block == Blocks.RAIL) rail = true;
                        if (block == Blocks.TNT) tnt = true;
                        if (block == Blocks.CHISELED_SANDSTONE) chiseledSand = true;
                        if (block == Blocks.MOSSY_COBBLESTONE) mossyCobble = true;
                        if (block == Blocks.CHISELED_STONE_BRICKS) chiseledBricks = true;
                        if (block == Blocks.SNOW_BLOCK) snow = true;
                        if (state.is(BlockTags.BEDS)) bed = true;
                        if (pos.getY() < 40) {
                            if (block == Blocks.STONE_BRICKS) deepBricks[0] = true;
                            if (block == Blocks.MOSSY_STONE_BRICKS) deepBricks[1] = true;
                            if (block == Blocks.CRACKED_STONE_BRICKS) deepBricks[2] = true;
                        }

                        if (strong.contains(block)) {
                            data.strong.add(new Hit(pos, TYPES.getOrDefault(block, Type.Utility)));
                        } else if (wd && wrongDim(state, dim)) {
                            data.strong.add(new Hit(pos, Type.WrongDimension));
                        } else if (com && materials.contains(block) && !NEVER_FLAG.contains(block)) {
                            String id = BuiltInRegistries.BLOCK.getKey(block).getPath();
                            if (bio && naturalForDimension(block, id, dim)) continue;
                            if (bio) {
                                // Biomes are stored per 4x4x4 cell, so one lookup serves that cell.
                                long cell = BlockPos.asLong(pos.getX() >> 2, pos.getY() >> 2, pos.getZ() >> 2);
                                String biome = biomeCache.computeIfAbsent(cell, c -> biomeAt(pos));
                                if (naturalForBiome(state, biome)) continue;
                            }
                            data.materials.add(pos);
                        }
                    }
                }
            }
        }

        // Structures recognised by combinations of blocks rather than one give-away block.
        if (path && jobSites.size() >= 2) data.structureRadius = Math.max(data.structureRadius, 4);   // village
        if (rail && cobweb) data.structureRadius = Math.max(data.structureRadius, 3);                  // mineshaft
        if (tnt && chiseledSand) data.structureRadius = Math.max(data.structureRadius, 2);             // desert pyramid
        if (mossyCobble && chiseledBricks) data.structureRadius = Math.max(data.structureRadius, 2);   // jungle temple
        if (snow && bed && biomeAt(new BlockPos(cx << 4, 64, cz << 4)).contains("snow")) {
            data.structureRadius = Math.max(data.structureRadius, 1);                                  // igloo
        }
        if (deepBricks[0] && deepBricks[1] && deepBricks[2]) data.structureRadius = Math.max(data.structureRadius, 4); // stronghold

        if (data.strong.isEmpty() && data.materials.isEmpty() && data.structureRadius < 0) {
            byChunk.remove(key);
            return;
        }
        ChunkData before = byChunk.put(key, data);
        if (data.structureRadius >= 0 || (before != null && before.structureRadius >= 0)) structureCache.clear();
    }

    /** What to show for one chunk right now: materials depend on structures found nearby since. */
    private List<Hit> shown(long key, ChunkData data) {
        List<Hit> out = new ArrayList<>();
        if (data.strong.size() >= minPerChunk.get()) out.addAll(data.strong);
        if (common.get() && data.materials.size() >= commonMin.get()
                && !(structureCheck.get() && inStructure(ChunkPos.getX(key), ChunkPos.getZ(key)))) {
            for (BlockPos pos : data.materials) out.add(new Hit(pos, Type.BuildingMaterials));
        }
        if (showStructures.get()) {
            for (BlockPos pos : data.markers) out.add(new Hit(pos, Type.NaturalStructures));
        }
        return out;
    }

    // ---- Rendering ----

    @EventHandler
    private void onRender(RenderWorldEvent event) {
        if (!Veil.inGame() || byChunk.isEmpty()) return;

        Camera camera = Veil.mc.gameRenderer.mainCamera();
        Vec3 camPos = camera.position();
        List<Hit> all = new ArrayList<>();
        for (Map.Entry<Long, ChunkData> e : byChunk.entrySet()) all.addAll(shown(e.getKey(), e.getValue()));
        if (all.isEmpty()) return;

        all.sort((a, b) -> Double.compare(Vec3.atCenterOf(a.pos()).distanceToSqr(camPos),
                                          Vec3.atCenterOf(b.pos()).distanceToSqr(camPos)));
        int limit = Math.min(all.size(), maxShown.get());
        boolean shade = fill.get();
        float width = lineWidth.get();

        for (int i = 0; i < limit; i++) {
            Hit hit = all.get(i);
            int colour = hit.type() == Type.NaturalStructures ? hit.type().colour : PLACED_COLOUR;
            GizmoStyle style = shade
                    ? GizmoStyle.strokeAndFill(colour, width, (45 << 24) | (colour & 0xFFFFFF))
                    : GizmoStyle.stroke(colour, width);
            Gizmos.cuboid(new AABB(hit.pos()), style).setAlwaysOnTop();
        }
    }

    @EventHandler
    private void onHud(HudRenderEvent event) {
        if (!Veil.inGame() || !legend.get()) return;
        if (Veil.mc.gui != null && Veil.mc.gui.hud.isHidden()) return;

        Map<Type, Integer> counts = new EnumMap<>(Type.class);
        for (Map.Entry<Long, ChunkData> e : byChunk.entrySet()) {
            for (Hit hit : shown(e.getKey(), e.getValue())) counts.merge(hit.type(), 1, Integer::sum);
        }
        int placed = 0;
        for (Map.Entry<Type, Integer> e : counts.entrySet()) {
            if (e.getKey() != Type.NaturalStructures) placed += e.getValue();
        }

        List<Legend.Entry> entries = new ArrayList<>();
        entries.add(new Legend.Entry("Player-placed", PLACED_COLOUR, placed > 0 ? String.valueOf(placed) : null));
        if (showStructures.get() && common.get()) {
            int n = counts.getOrDefault(Type.NaturalStructures, 0);
            entries.add(new Legend.Entry(Type.NaturalStructures.label, Type.NaturalStructures.colour,
                    n > 0 ? String.valueOf(n) : null));
        }

        GuiGraphicsExtractor ctx = event.ctx;
        Legend.draw(ctx, Veil.mc.font, "Player Blocks", entries);
    }

    @Override
    public String suffix() {
        return total > 0 ? String.valueOf(total) : null;
    }
}
