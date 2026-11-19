// lang: Java, file: DamageUtil.java, target: Fabric MC 26.3
// Accurate crystal explosion damage estimator ported from nyx DamageUtils.
// Accounts for distance falloff, block exposure (ray sampling), difficulty,
// armor + toughness reduction, resistance potion, and Protection enchantment.
package com.veil.util;

import net.minecraft.core.BlockPos;
import net.minecraft.world.Difficulty;
import net.minecraft.world.effect.MobEffectInstance;
import net.minecraft.world.effect.MobEffects;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.ClipContext;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.phys.Vec3;

import static com.veil.Veil.mc;

/**
 * Estimates end crystal explosion damage the same way vanilla does:
 * distance falloff × exposure × difficulty × (1 - armor reduction) × (1 - resistance) × (1 - prot).
 */
public final class DamageUtil {

    private static final float CRYSTAL_POWER = 6f;
    private static final EquipmentSlot[] ARMOR_SLOTS = {
        EquipmentSlot.HEAD, EquipmentSlot.CHEST, EquipmentSlot.LEGS, EquipmentSlot.FEET
    };

    private DamageUtil() {}

    public static float crystalDamage(LivingEntity target, Vec3 crystalPos) {
        return crystalDamage(target, crystalPos, false, null);
    }

    /**
     * @param obsidianPos if not null, treat this block as solid (for obsidian that isn't placed yet)
     */
    public static float crystalDamage(LivingEntity target, Vec3 crystalPos, boolean predictMovement, BlockPos obsidianPos) {
        return explosionDamage(target, crystalPos, CRYSTAL_POWER, obsidianPos);
    }

    public static float explosionDamage(LivingEntity target, Vec3 center, float power, BlockPos extraSolid) {
        return explosionDamage(target, center, power, extraSolid, null);
    }

    /**
     * @param extraSolid    if not null, treat this block as solid (for a block that is not placed yet)
     * @param ignoredSolid  if not null, treat this block as air. Used for anchors: the anchor block
     *                      is gone when its blast happens, and a blast centred inside it would
     *                      otherwise report zero exposure against its own collision shape
     */
    public static float explosionDamage(LivingEntity target, Vec3 center, float power,
                                        BlockPos extraSolid, BlockPos ignoredSolid) {
        if (target == null || mc.level == null) return 0;
        if (target instanceof Player p && (p.isCreative() || p.isSpectator())) return 0;

        double radius = power * 2;
        double dist = Math.sqrt(target.distanceToSqr(center)) / radius;
        if (dist > 1) return 0;

        double exposure = seenPercent(center, target.getBoundingBox(), extraSolid, ignoredSolid);
        double impact   = (1 - dist) * exposure;
        float damage    = (float) ((impact * impact + impact) / 2.0 * 7.0 * radius + 1);

        // Difficulty scaling
        if (target instanceof Player) {
            Difficulty d = mc.level.getDifficulty();
            if (d == Difficulty.PEACEFUL) return 0;
            if (d == Difficulty.EASY)     damage = Math.min(damage / 2 + 1, damage);
            if (d == Difficulty.HARD)     damage = damage * 3 / 2;
        }

        // Armor + toughness
        float armor     = target.getArmorValue();
        float toughness = (float) target.getAttributeValue(net.minecraft.world.entity.ai.attributes.Attributes.ARMOR_TOUGHNESS);
        float f         = 2 + toughness / 4;
        float effective = Math.max(armor * 0.2f, Math.min(armor - damage / f, 20));
        damage *= 1 - effective / 25f;

        // Resistance potion
        MobEffectInstance res = target.hasEffect(MobEffects.RESISTANCE) ? target.getEffect(MobEffects.RESISTANCE) : null;
        if (res != null) damage *= Math.max(0, 1 - (res.getAmplifier() + 1) * 0.2f);

        // Protection enchantment: assume all worn armor is Prot IV if enchanted
        int epf = 0;
        for (EquipmentSlot slot : ARMOR_SLOTS) {
            ItemStack stack = target.getItemBySlot(slot);
            if (!stack.isEmpty() && stack.isEnchanted()) epf += 4;
        }
        damage *= 1 - Math.min(20, epf) / 25f;

        return Math.max(0, damage);
    }

    /**
     * Returns the fraction (0–1) of AABB sample points with a clear line of sight to {@code source}.
     * {@code extraSolid}: an AABB of a not-yet-placed block treated as solid during ray casts.
     * {@code ignoredSolid}: a block treated as air, for a blast centred inside the block that is
     * about to be removed.
     */
    private static double seenPercent(Vec3 source, AABB box, BlockPos extraSolid, BlockPos ignoredSolid) {
        double sx = 1.0 / ((box.maxX - box.minX) * 2 + 1);
        double sy = 1.0 / ((box.maxY - box.minY) * 2 + 1);
        double sz = 1.0 / ((box.maxZ - box.minZ) * 2 + 1);
        double ox = (1 - Math.floor(1.0 / sx) * sx) / 2;
        double oz = (1 - Math.floor(1.0 / sz) * sz) / 2;
        if (sx < 0 || sy < 0 || sz < 0) return 0;

        AABB solidBox = extraSolid == null ? null : new AABB(extraSolid);
        int seen = 0, total = 0;

        for (double x = 0; x <= 1; x += sx) {
            for (double y = 0; y <= 1; y += sy) {
                for (double z = 0; z <= 1; z += sz) {
                    Vec3 p = new Vec3(
                        box.minX + (box.maxX - box.minX) * x + ox,
                        box.minY + (box.maxY - box.minY) * y,
                        box.minZ + (box.maxZ - box.minZ) * z + oz);
                    total++;
                    if (solidBox != null && solidBox.clip(p, source).isPresent()) continue;
                    HitResult hit = mc.level.clip(new ClipContext(
                        p, source,
                        ClipContext.Block.COLLIDER,
                        ClipContext.Fluid.NONE,
                        mc.player));
                    if (hit.getType() == HitResult.Type.MISS) seen++;
                    else if (ignoredSolid != null && hit instanceof BlockHitResult blockHit
                            && blockHit.getBlockPos().equals(ignoredSolid)) seen++;
                }
            }
        }
        return total == 0 ? 0.0 : (double) seen / total;
    }
}
