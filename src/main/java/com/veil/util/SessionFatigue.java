// lang: Java, file: SessionFatigue.java, target: Fabric MC 26.3
// Session fatigue: the multiplier combat modules apply to their own delays, so a long session does
// not keep the rhythm it started with.
package com.veil.util;

/**
 * How much slower the combat modules act the longer the session has been running.
 *
 * <p>A multiplier on configured delays: 1.0 at the start, rising linearly to 1.4 at twenty minutes
 * and staying there. A player's clicks do drift apart over an hour of play, and a client that holds
 * the same millisecond rhythm from the first cycle to the last is the one thing no human does.
 *
 * <p>One clock, shared by every module that asks for it: the crystal and anchor modules hand the
 * same hotbar back and forth, and two independent ramps would drift apart within a cycle.
 * {@link #reset()} is called when a module is switched on fresh, so a re-enable starts from a
 * rested player again.
 */
public final class SessionFatigue {

    private static final SessionFatigue INSTANCE = new SessionFatigue();

    /** Time taken to reach the 1.4 ceiling. */
    private static final double RAMP_MS = 20.0 * 60_000.0;

    private long sessionStartMs = System.currentTimeMillis();

    private SessionFatigue() {}

    public static SessionFatigue get() { return INSTANCE; }

    /** Starts the ramp again from 1.0. */
    public void reset() { sessionStartMs = System.currentTimeMillis(); }

    /** 1.0 at session start, 1.4 after twenty minutes, capped there. */
    public float delayMultiplier() {
        double t = Math.min(1.0, (System.currentTimeMillis() - sessionStartMs) / RAMP_MS);
        return (float)(1.0 + t * 0.4);
    }
}
