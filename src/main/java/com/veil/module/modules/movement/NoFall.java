// lang: Java, file: NoFall.java, target: Fabric MC 26.3
// Prevents fall damage by spoofing onGround in the outbound movement packet.
package com.veil.module.modules.movement;

import com.veil.Veil;
import com.veil.event.EventHandler;
import com.veil.event.events.PacketEvent;
import com.veil.module.Category;
import com.veil.module.Module;
import com.veil.setting.FloatSetting;
import com.veil.util.MovePacketUtil;
import net.minecraft.network.protocol.game.ServerboundMovePlayerPacket;

/**
 * NoFall — reports onGround=true in outbound movement packets while falling faster than the
 * threshold, so the server never accumulates fall distance to damage from.
 *
 * <p>The previous implementation reflected into a field named {@code onGround}/{@code ground} on the
 * packet. That cannot work in 26.3: the field is {@code protected final} on
 * {@code ServerboundMovePlayerPacket}, so reflection throws {@link IllegalAccessException} and the
 * handler ended up printing a stack trace for every packet without ever changing anything. The
 * packet is cancelled and an equivalent one is re-sent with the flag replaced instead.
 *
 * <p>{@code ClientPlayNetworkHandlerMixin} fires {@link PacketEvent.Send} for every outbound packet,
 * so no extra mixin is needed. The replacement packet has onGround set, so it passes straight
 * through this handler and cannot loop.
 */
public final class NoFall extends Module {

    private final FloatSetting threshold = register(
            new FloatSetting("Threshold", 0.5f, 0.1f, 5.0f));

    public NoFall() {
        super("NoFall", "Prevents fall damage via packet spoofing", Category.MOVEMENT);
    }

    @EventHandler
    private void onPacketSend(PacketEvent.Send event) {
        if (!Veil.inGame()) return;
        if (!(event.getPacket() instanceof ServerboundMovePlayerPacket pkt)) return;
        if (pkt.isOnGround()) return;
        if (Veil.mc.player.getDeltaMovement().y > -threshold.get()) return;
        if (Veil.mc.getConnection() == null) return;

        ServerboundMovePlayerPacket spoofed = MovePacketUtil.withGround(pkt, true);
        if (spoofed == pkt) return;

        event.setCancelled(true);
        Veil.mc.getConnection().send(spoofed);
    }

    @Override
    public String suffix() {
        return String.format("%.1f", threshold.get());
    }
}
