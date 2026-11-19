// lang: Java, file: Grayscale.java, target: Fabric MC 26.3
// Black-and-white picture: a post effect that strips the colour out of every frame.
package com.veil.module.modules.render;

import com.veil.module.Category;
import com.veil.module.Module;
import com.veil.module.ModuleManager;
import net.minecraft.resources.Identifier;

/**
 * Grayscale — removes all colour from what is drawn, keeping each block's and entity's natural
 * brightness: the picture reads like a black-and-white texture pack without replacing a single
 * texture.
 *
 * <p>It does that with a post effect, not with textures: {@code assets/veil/post_effect/grayscale.json}
 * runs one full-screen pass over the finished frame that replaces every pixel with its luma, then blits
 * the result back. The module itself only decides whether that effect is on the frame — see
 * {@code PostEffectMixin}.
 */
public final class Grayscale extends Module {

    /** The post effect shipped in this mod's own assets. */
    public static final Identifier EFFECT = Identifier.fromNamespaceAndPath("veil", "grayscale");

    public Grayscale() {
        super("Grayscale",
              "Removes all colour from the world, leaving a black-and-white picture.",
              Category.RENDER);
    }

    /** Whether the effect should be on the frame right now; asked by the per-frame mixin. */
    public static boolean active() {
        for (Module module : ModuleManager.getModules()) {
            if (module instanceof Grayscale grayscale && grayscale.isEnabled()) return true;
        }
        return false;
    }
}
