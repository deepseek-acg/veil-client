package com.veil.mixin;

import com.veil.module.modules.render.Grayscale;
import net.minecraft.client.DeltaTracker;
import net.minecraft.client.renderer.GameRenderer;
import net.minecraft.resources.Identifier;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import java.util.List;

/**
 * Puts {@code mist:grayscale} on the frame's post-effect list while the Grayscale module is on.
 *
 * <p>The entry goes on the list vanilla builds each frame and later turns into post chains, so the
 * effect is applied by the normal pass over the finished frame; one that fails to load is skipped and
 * reported by vanilla rather than taking the client down.
 *
 * <p>Added at the tail of {@code update}, which is where vanilla clears the list and fills in its own
 * requests for the frame — after that point {@code extract} hands it to the render state and
 * {@code render} applies it. The entry is added and removed per frame rather than once: the list is
 * vanilla's, rebuilt from its own effects every frame, so ours would not survive and is not ours to
 * keep.
 */
@Mixin(GameRenderer.class)
public class PostEffectMixin {

    @Inject(method = "update", at = @At("TAIL"))
    private void onUpdate(DeltaTracker deltaTracker, CallbackInfo ci) {
        List<Identifier> requested = ((GameRenderer) (Object) this).getRequestedPostEffects();
        if (Grayscale.active()) {
            if (!requested.contains(Grayscale.EFFECT)) requested.add(Grayscale.EFFECT);
        } else {
            requested.remove(Grayscale.EFFECT);
        }
    }
}
