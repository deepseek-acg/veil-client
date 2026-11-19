// lang: Java, file: PacketEvent.java, target: Fabric MC 26.3
// Wraps inbound and outbound packets. Set cancelled = true to drop (send only).
package com.veil.event.events;

import net.minecraft.network.protocol.Packet;

public class PacketEvent {

    private final Packet<?> packet;
    private boolean cancelled;

    public PacketEvent(Packet<?> packet) {
        this.packet = packet;
    }

    public Packet<?> getPacket() { return packet; }

    public boolean isCancelled() { return cancelled; }
    public void setCancelled(boolean cancelled) { this.cancelled = cancelled; }

    /** Fired before a packet is sent to the server. */
    public static class Send extends PacketEvent {
        public Send(Packet<?> packet) { super(packet); }
    }

    /** Fired when a packet is received from the server. */
    public static class Receive extends PacketEvent {
        public Receive(Packet<?> packet) { super(packet); }
    }
}
