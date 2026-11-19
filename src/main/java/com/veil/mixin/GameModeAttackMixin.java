// lang: Java, file: GameModeAttackMixin.java, target: Fabric MC 26.3
package com.veil.mixin;

import com.veil.Veil;
import com.veil.event.events.AttackEntityEvent;
import net.minecraft.client.multiplayer.MultiPlayerGameMode;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.player.Player;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Posts {@link AttackEntityEvent} before the client attacks.
 *
 * <p>26.3's target is {@code attack(Player, Entity)}: the first parameter is the base
 * {@link Player}, not {@code LocalPlayer}. The descriptor must match exactly — Mixin rejects the
 * injection at apply time with an {@code InvalidInjectionException}, which surfaces as a failed
 * class transform the first time {@code MultiPlayerGameMode} loads (i.e. when joining a world), not
 * as a build error.
 */
@Mixin(MultiPlayerGameMode.class)
public abstract class GameModeAttackMixin {

    @Inject(method = "attack(Lnet/minecraft/world/entity/player/Player;Lnet/minecraft/world/entity/Entity;)V",
            at = @At("HEAD"))
    private void onAttack(Player player, Entity target, CallbackInfo ci) {
        if (Veil.EVENTS != null) Veil.EVENTS.post(new AttackEntityEvent(target));
    }
}
