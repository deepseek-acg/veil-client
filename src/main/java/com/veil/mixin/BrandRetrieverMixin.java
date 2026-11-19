package com.veil.mixin;

import net.minecraft.client.ClientBrandRetriever;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * Anti-fingerprint — brand channel.
 *
 * <p>On join the client reports a brand string through the {@code minecraft:brand} channel. Vanilla
 * sends {@code "vanilla"}; Fabric rewrites it to include the loader and every loaded mod, which is a
 * complete inventory of the client visible in server logs. Returning at {@code HEAD} at priority
 * 1500 replaces whatever Fabric's own mixin put in place.
 */
@Mixin(value = ClientBrandRetriever.class, priority = 1500)
public abstract class BrandRetrieverMixin {

    @Inject(method = "getClientModName", at = @At("HEAD"), cancellable = true)
    private static void spoofBrand(CallbackInfoReturnable<String> cir) {
        cir.setReturnValue(ClientBrandRetriever.VANILLA_NAME);
    }
}
