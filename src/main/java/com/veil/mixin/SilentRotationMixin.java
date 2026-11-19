// lang: Java, file: SilentRotationMixin.java, target: Fabric MC 26.3
// Intercepts outbound movement packets and injects the silent rotation queued by KillAura/AimAssist,
// so the server sees the aimed direction while the local camera stays where the player left it.
// Priority 900 — runs before the event-posting hook in ClientPlayNetworkHandlerMixin.
package com.veil.mixin;

import com.veil.Veil;
import com.veil.util.SilentRotationManager;
import net.minecraft.client.multiplayer.ClientCommonPacketListenerImpl;
import net.minecraft.network.protocol.Packet;
import net.minecraft.network.protocol.game.ServerboundMovePlayerPacket;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(value = ClientCommonPacketListenerImpl.class, priority = 900)
public abstract class SilentRotationMixin {

    private static final ThreadLocal<Boolean> INJECTING = ThreadLocal.withInitial(() -> Boolean.FALSE);

    @Inject(method = "send", at = @At("HEAD"), cancellable = true)
    private void onSend(Packet<?> packet, CallbackInfo ci) {
        if (INJECTING.get()) return;
        if (!(packet instanceof ServerboundMovePlayerPacket pkt)) return;
        // Only these three carry rotation fields. StatusOnly has none, so taking the queued rotation
        // for one would clear it and send the packet unchanged: the rotation would be gone, and the
        // click it was aimed at would reach the server pointed at the frozen camera. The rotation is
        // therefore taken only once a packet that can carry it is in hand.
        if (!(pkt instanceof ServerboundMovePlayerPacket.PosRot
                || pkt instanceof ServerboundMovePlayerPacket.Pos
                || pkt instanceof ServerboundMovePlayerPacket.Rot)) {
            return;
        }
        if (!SilentRotationManager.hasPendingRotation()) return;
        if (Veil.mc.getConnection() == null) return;

        float[] rot = SilentRotationManager.consume();
        ServerboundMovePlayerPacket rotated = buildWithRotation(pkt, rot[0], rot[1]);
        if (rotated == pkt) {
            // Nothing was built after all: put the rotation back for the next movement packet rather
            // than dropping it on the floor.
            SilentRotationManager.setPending(rot[0], rot[1]);
            return;
        }

        ci.cancel();
        INJECTING.set(Boolean.TRUE);
        try {
            Veil.mc.getConnection().send(rotated);
            // The server is now looking this way, not at the camera: remember it as the base the next
            // silent aim steps from and the angle a return step has to walk back from.
            SilentRotationManager.markSent(rot[0], rot[1]);
        } finally {
            INJECTING.set(Boolean.FALSE);
        }
    }

    private static ServerboundMovePlayerPacket buildWithRotation(
            ServerboundMovePlayerPacket pkt, float yaw, float pitch) {
        boolean ground    = pkt.isOnGround();
        boolean collision = pkt.horizontalCollision();

        if (pkt instanceof ServerboundMovePlayerPacket.PosRot posRot) {
            return new ServerboundMovePlayerPacket.PosRot(
                    posRot.getX(0), posRot.getY(0), posRot.getZ(0), yaw, pitch, ground, collision);
        }
        if (pkt instanceof ServerboundMovePlayerPacket.Pos pos) {
            // Promote position-only to position+rotation: vanilla sends Pos when player moves without
            // turning — the camera is frozen in silent mode, so this case is common.
            return new ServerboundMovePlayerPacket.PosRot(
                    pos.getX(0), pos.getY(0), pos.getZ(0), yaw, pitch, ground, collision);
        }
        if (pkt instanceof ServerboundMovePlayerPacket.Rot) {
            return new ServerboundMovePlayerPacket.Rot(yaw, pitch, ground, collision);
        }
        // StatusOnly carries no rotation field — nothing to inject.
        return pkt;
    }
}
