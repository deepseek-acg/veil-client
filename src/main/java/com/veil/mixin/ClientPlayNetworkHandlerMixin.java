package com.veil.mixin;

import com.veil.Veil;
import com.veil.event.events.PacketEvent;
import net.minecraft.client.multiplayer.ClientCommonPacketListenerImpl;
import net.minecraft.network.protocol.Packet;
import net.minecraft.network.protocol.common.ServerboundCustomPayloadPacket;
import net.minecraft.resources.Identifier;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Anti-fingerprint — channel registration and outbound packet events.
 *
 * <p>Fabric accumulates the network channels of every loaded mod and sends them as a
 * {@code minecraft:register} custom payload on join. That packet is a complete list of installed
 * mods, so it is dropped: a vanilla client registers nothing.
 *
 * <p>Everything else is posted as {@link PacketEvent.Send}, which is what a module cancels to
 * suppress a packet — the same hook NoFall-style modules use.
 *
 * <p>26.3 note: the send method lives on {@code ClientCommonPacketListenerImpl}, the parent of
 * {@code ClientPacketListener}, not on the listener itself.
 */
@Mixin(ClientCommonPacketListenerImpl.class)
public abstract class ClientPlayNetworkHandlerMixin {

    private static final Identifier REGISTER_CHANNEL   = Identifier.fromNamespaceAndPath("minecraft", "register");
    private static final Identifier UNREGISTER_CHANNEL = Identifier.fromNamespaceAndPath("minecraft", "unregister");

    @Inject(method = "send", at = @At("HEAD"), cancellable = true)
    private void interceptOutbound(Packet<?> packet, CallbackInfo ci) {
        if (Veil.mc == null) return;

        // A higher-priority injector (silent rotation, priority 900) may already have replaced this
        // packet. Posting it again would let a module act on a packet that is never sent.
        if (ci.isCancelled()) return;

        if (packet instanceof ServerboundCustomPayloadPacket custom) {
            Identifier channel = custom.payload().type().id();
            if (REGISTER_CHANNEL.equals(channel) || UNREGISTER_CHANNEL.equals(channel)) {
                ci.cancel();
                return;
            }
        }

        if (Veil.EVENTS.post(new PacketEvent.Send(packet)).isCancelled()) {
            ci.cancel();
        }
    }
}
