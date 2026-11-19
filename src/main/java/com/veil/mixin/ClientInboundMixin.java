package com.veil.mixin;

import com.veil.Veil;
import com.veil.event.events.PacketEvent;
import net.minecraft.client.multiplayer.ClientPacketListener;
import net.minecraft.network.protocol.game.ClientboundAddEntityPacket;
import net.minecraft.network.protocol.game.ClientboundSetEntityMotionPacket;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Posts {@link PacketEvent.Receive} for the inbound packets the combat modules need.
 *
 * <p>26.3 dispatches inbound packets through one handler per packet type on
 * {@code ClientPacketListener}, so a receive hook has to name the handler it wants. Two are hooked:
 * entity spawn (CrystalAura watches for the crystal it just placed) and entity motion (Velocity
 * scales knockback before vanilla applies it).
 *
 * <p>Cancelling the event cancels the vanilla handler, so a module that cancels must apply the
 * substitute itself — Velocity does exactly that.
 */
@Mixin(ClientPacketListener.class)
public abstract class ClientInboundMixin {

    @Inject(method = "handleAddEntity", at = @At("HEAD"), cancellable = true)
    private void onAddEntity(ClientboundAddEntityPacket packet, CallbackInfo ci) {
        if (Veil.mc == null) return;
        if (Veil.EVENTS.post(new PacketEvent.Receive(packet)).isCancelled()) ci.cancel();
    }

    @Inject(method = "handleSetEntityMotion", at = @At("HEAD"), cancellable = true)
    private void onSetEntityMotion(ClientboundSetEntityMotionPacket packet, CallbackInfo ci) {
        if (Veil.mc == null) return;
        if (Veil.EVENTS.post(new PacketEvent.Receive(packet)).isCancelled()) ci.cancel();
    }
}
