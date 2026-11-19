// lang: Java, file: MovePacketUtil.java, target: Fabric MC 26.3
package com.veil.util;

import net.minecraft.network.protocol.game.ServerboundMovePlayerPacket;

/**
 * Rebuilds outbound movement packets with a substituted flag.
 *
 * <p>26.3's movement packets keep their fields {@code protected final}, so a packet cannot be
 * edited in place — not by a setter and not by reflection. The only way to change what the server
 * receives is to drop the original and send an equivalent packet built from it, which is what this
 * class produces.
 */
public final class MovePacketUtil {

    private MovePacketUtil() {}

    /**
     * Returns {@code packet} with its on-ground flag replaced, preserving position, rotation and the
     * horizontal-collision flag. Returns the same instance for packet shapes that carry no flag to
     * replace.
     */
    public static ServerboundMovePlayerPacket withGround(ServerboundMovePlayerPacket packet, boolean onGround) {
        boolean collision = packet.horizontalCollision();

        if (packet instanceof ServerboundMovePlayerPacket.PosRot posRot) {
            return new ServerboundMovePlayerPacket.PosRot(
                    posRot.getX(0), posRot.getY(0), posRot.getZ(0),
                    posRot.getYRot(0), posRot.getXRot(0), onGround, collision);
        }
        if (packet instanceof ServerboundMovePlayerPacket.Pos pos) {
            return new ServerboundMovePlayerPacket.Pos(
                    pos.getX(0), pos.getY(0), pos.getZ(0), onGround, collision);
        }
        if (packet instanceof ServerboundMovePlayerPacket.Rot rot) {
            return new ServerboundMovePlayerPacket.Rot(
                    rot.getYRot(0), rot.getXRot(0), onGround, collision);
        }
        if (packet instanceof ServerboundMovePlayerPacket.StatusOnly) {
            return new ServerboundMovePlayerPacket.StatusOnly(onGround, collision);
        }
        return packet;
    }
}
