// lang: Java, file: SilentAimBase.java, target: Fabric MC 26.3
package com.veil.util;

/**
 * The angle one module last put on the wire: the base its next silent step is measured from.
 *
 * <p>Silent rotation leaves the camera where the player put it, so a module that measured its next
 * step from the camera would read the same frozen offset every tick and never close on its target.
 * The base therefore has to be the angle the server was actually given.
 *
 * <p>It is kept per module rather than read from one shared store. Two modules aiming at the same
 * time — a crystal cycle and an anchor sequence, say — would otherwise each take the other's last
 * step as their base, and every step either of them sent would drag the other's turn onto its own
 * target; the two would fight over the view for as long as both ran. Each module walks its own aim.
 * What {@link SilentRotationManager} records is only the last angle of whichever module sent one,
 * which is all the return-to-camera step needs.
 *
 * <p>Not thread-safe and not meant to be shared: one instance per aiming module.
 */
public final class SilentAimBase {

    private boolean has;
    private float   yaw;
    private float   pitch;

    /** Whether this module has put a rotation on the wire to measure the next step from. */
    public boolean has() { return has; }

    public float yaw()   { return yaw;   }
    public float pitch() { return pitch; }

    /** Records the angle this module has just sent. */
    public void record(float yaw, float pitch) {
        this.yaw   = yaw;
        this.pitch = pitch;
        this.has   = true;
    }

    /**
     * Forgets the base, so the next step is measured from the camera again.
     *
     * <p>Called when something other than this module moves the view it was measured from: a return
     * step walking the server's view back to the camera, or a sequence that ended and gave the view
     * up.
     */
    public void clear() { has = false; }
}
