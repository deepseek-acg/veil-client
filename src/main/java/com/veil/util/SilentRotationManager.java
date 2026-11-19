// lang: Java, file: SilentRotationManager.java, target: Fabric MC 26.3
package com.veil.util;

import com.veil.Veil;
import java.util.concurrent.ThreadLocalRandom;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.network.protocol.game.ServerboundMovePlayerPacket;
import net.minecraft.util.Mth;

/**
 * The one place a rotation the camera does not show is queued.
 *
 * <p>{@code SilentRotationMixin} reads this store when a movement packet goes out and rewrites the
 * packet with the queued yaw/pitch, so the server registers the aimed direction while the player's
 * camera stays where they left it. Every module that aims silently goes through here: a second store
 * would need a second hook on the same send, and two hooks can consume each other's rotation.
 */
public final class SilentRotationManager {

    private SilentRotationManager() {}

    private static final ThreadLocal<float[]> PENDING = ThreadLocal.withInitial(() -> null);

    /**
     * The angle last put on the wire. Every silent rotation leaves through this class — either from
     * {@link #flush()} or from the packet rewrite in {@code SilentRotationMixin} — so this is where the
     * server's idea of the player's facing is kept. The return step below is what needs it: it walks
     * the server's view back to the camera from where the server was left. An aiming module keeps its
     * own base instead (see {@link SilentAimBase}), so two modules aiming at once cannot step from
     * each other's rotation.
     */
    private static volatile boolean hasSent;
    private static volatile float   sentYaw;
    private static volatile float   sentPitch;

    /** The camera as of the previous return step, to tell a player who is turning from one who is not. */
    private static boolean hasLastCamera;
    private static float   lastCameraYaw;
    private static float   lastCameraPitch;

    /** How close to the camera counts as back: below this the return has nothing left to do. */
    private static final float RETURN_DONE_DEG = 1.0f;

    public static void setPending(float yaw, float pitch) {
        PENDING.set(new float[]{yaw, pitch});
    }

    public static boolean hasPendingRotation() {
        return PENDING.get() != null;
    }

    /** Returns [yaw, pitch] and clears pending state. Caller must check hasPendingRotation() first. */
    public static float[] consume() {
        float[] val = PENDING.get();
        PENDING.set(null);
        return val;
    }

    public static void clear() {
        PENDING.set(null);
    }

    /** Records the rotation a movement packet was rewritten with. Called by SilentRotationMixin. */
    public static void markSent(float yaw, float pitch) {
        sentYaw   = yaw;
        sentPitch = pitch;
        hasSent   = true;
    }

    /**
     * Queues and sends one step of a return to the camera, and reports whether it did.
     *
     * <p>Silent rotation leaves the server looking where the last crystal or anchor was; the camera
     * never moved, so the client has nothing to send and the two only agree again once the player
     * turns far enough for vanilla to emit a rotation packet of its own. This walks the server's view
     * back on the ticks nothing is being clicked, so the disagreement is closed deliberately rather
     * than by the player's next mouse movement.
     *
     * <p>Nothing is queued while the player is turning the camera themselves: their own rotation
     * packet is already carrying the truth, and a step here would race it.
     *
     * @param maxYawStep   largest yaw change in one step, in degrees
     * @param maxPitchStep largest pitch change in one step, in degrees
     * @return true if a rotation was queued and sent
     */
    public static boolean stepBackToCamera(LocalPlayer self, float maxYawStep, float maxPitchStep) {
        if (self == null) return false;

        float camYaw   = self.getYRot();
        float camPitch = self.getXRot();
        boolean turning = hasLastCamera
                && (Math.abs(Mth.wrapDegrees(camYaw - lastCameraYaw)) > 0.01f
                    || Math.abs(camPitch - lastCameraPitch) > 0.01f);
        lastCameraYaw   = camYaw;
        lastCameraPitch = camPitch;
        hasLastCamera   = true;
        if (turning) return false;

        if (!hasSent) return false;
        float rawDeltaYaw   = Mth.wrapDegrees(camYaw - sentYaw);
        float rawDeltaPitch = camPitch - sentPitch;
        if (Math.abs(rawDeltaYaw) < RETURN_DONE_DEG && Math.abs(rawDeltaPitch) < RETURN_DONE_DEG) {
            return false;
        }

        float gcd      = RotationUtil.gcd();
        float stepYaw   = Mth.clamp(rawDeltaYaw,   -maxYawStep,   maxYawStep);
        float stepPitch = Mth.clamp(rawDeltaPitch, -maxPitchStep, maxPitchStep);
        // A whole number of mouse steps with ±1 step of jitter: the same grid the aim is quantised to,
        // so the return is a movement the current sensitivity could have produced.
        float jitterYaw   = (ThreadLocalRandom.current().nextInt(3) - 1) * gcd;
        float jitterPitch = (ThreadLocalRandom.current().nextInt(3) - 1) * gcd;
        setPending(sentYaw + RotationUtil.quantize(stepYaw, gcd) + jitterYaw,
                   Mth.clamp(sentPitch + RotationUtil.quantize(stepPitch, gcd) + jitterPitch, -90f, 90f));
        return true;
    }

    /** Rotation is now injected via the movement-packet mixin. Standalone flush packets are a Grim flag. */
    @Deprecated
    public static void flush() {}
}
