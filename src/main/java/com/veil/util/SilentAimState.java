// lang: Java, file: SilentAimState.java, target: Fabric MC 26.3
// Minimum-jerk + Fitts's Law + Harris-Wolpert motor noise + EWMA camera smoothing.
package com.veil.util;

import java.util.concurrent.ThreadLocalRandom;

/**
 * Drives one camera movement with biomechanically plausible kinematics.
 *
 * <p>Call {@link #start} when a new target rotation is needed, then call {@link #tick} once per
 * game tick and feed the returned [yaw, pitch] pair to SilentRotationManager. The returned angles are
 * always GCD-compliant; the caller supplies the current GCD value.
 *
 * <p>No Minecraft API surface is used directly — the class is pure Java. Import and instantiate one
 * instance per combat module that needs smooth rotations.
 */
public final class SilentAimState {

    // Profile constants — overridden by configure()
    private double aimNoiseK     = 0.08;
    private double overshootProb = 0.20;
    private double ewmaAlpha     = 0.65;

    // Fitts's Law fixed constants
    private static final double FITTS_A  = 60.0;   // ms intercept
    private static final double FITTS_B  = 120.0;  // ms/bit slope
    private static final double TARGET_W = 10.0;   // effective target width in degrees

    // Corrective sub-movement
    private static final int CORRECT_TICKS = 2;

    private static final float MAX_DEG_PER_TICK = 18.0f;

    // Movement state
    private float  startYaw, startPitch;
    private float  targetYaw, targetPitch;
    private float  overshootTargetYaw, overshootTargetPitch;
    private float  lastSentYaw, lastSentPitch;
    private int    totalTicks;
    private int    ticksElapsed;
    private double overshootDeg;

    /** Per-tick share of the move: EWMA-smoothed, then normalised so it sums to exactly one. */
    private double[] profile = new double[0];

    // Corrective sub-movement state
    private boolean correcting;
    private int     correctTick;
    private float   correctStartYaw, correctStartPitch;

    private boolean moving;

    // -------------------------------------------------------------------------
    // Public API
    // -------------------------------------------------------------------------

    /** Override profile-specific constants before calling start(). */
    public void configure(double aimNoiseK, double overshootProb, double ewmaAlpha) {
        this.aimNoiseK     = aimNoiseK;
        this.overshootProb = overshootProb;
        this.ewmaAlpha     = Math.max(0.05, Math.min(1.0, ewmaAlpha));
    }

    /** Reset to idle. Safe to call at any time. */
    public void reset() {
        moving       = false;
        correcting   = false;
        ticksElapsed = 0;
        correctTick  = 0;
    }

    /**
     * Begin a new movement from (fromYaw, fromPitch) to (toYaw, toPitch).
     * A movement already in progress is discarded.
     */
    public void start(float fromYaw, float fromPitch, float toYaw, float toPitch) {
        startYaw    = fromYaw;
        startPitch  = fromPitch;
        targetYaw   = toYaw;
        targetPitch = toPitch;
        lastSentYaw   = fromYaw;
        lastSentPitch = fromPitch;

        double dYaw   = wrapDegrees(toYaw - fromYaw);
        double dPitch = toPitch - fromPitch;
        double D      = Math.sqrt(dYaw * dYaw + dPitch * dPitch);

        totalTicks   = fittsLawTicks(D);
        ticksElapsed = 0;
        correcting   = false;
        correctTick  = 0;

        // Overshoot roll: the primary movement is aimed past the target, and the two-tick corrective
        // sub-movement below brings it back. Short moves are precise enough not to need it.
        if (D > 12.0 && ThreadLocalRandom.current().nextDouble() < overshootProb) {
            double raw = 2.0 + ThreadLocalRandom.current().nextGaussian() * 1.5;
            overshootDeg = Math.max(2.0, Math.min(6.0, raw));
            overshootTargetYaw   = (float)(toYaw   + (dYaw   / D) * overshootDeg);
            overshootTargetPitch = (float)(toPitch + (dPitch / D) * overshootDeg);
        } else {
            overshootDeg         = 0.0;
            overshootTargetYaw   = toYaw;
            overshootTargetPitch = toPitch;
        }

        profile = buildProfile(totalTicks);
        moving  = true;
    }

    /**
     * Advance one game tick.
     *
     * @param gcd current sensitivity GCD (from RotationUtil.gcd()); pass 0 to skip quantization
     * @return [yaw, pitch] to send this tick, or null when the movement is complete
     */
    public float[] tick(float gcd) {
        if (!moving) return null;

        if (correcting) return tickCorrection(gcd);

        if (ticksElapsed >= totalTicks) {
            if (overshootDeg > 0) {
                startCorrection();
                return tickCorrection(gcd);
            }
            moving = false;
            return null;
        }

        double totalDYaw   = wrapDegrees(overshootTargetYaw - startYaw);
        double totalDPitch = overshootTargetPitch - startPitch;

        double stepYaw   = totalDYaw   * profile[ticksElapsed];
        double stepPitch = totalDPitch * profile[ticksElapsed];

        // Quantise the step, so every step is a movement this sensitivity's mouse could produce, and
        // add the motor noise on the same grid — a sub-step error added before the quantiser would
        // simply be rounded away again.
        float qDeltaYaw   = RotationUtil.quantize((float) stepYaw,   gcd)
                          + noiseSteps(gcd, Math.abs(stepYaw));
        float qDeltaPitch = RotationUtil.quantize((float) stepPitch, gcd)
                          + noiseSteps(gcd, Math.abs(stepPitch));
        qDeltaYaw   = Math.signum(qDeltaYaw)   * Math.min(Math.abs(qDeltaYaw),   MAX_DEG_PER_TICK);
        qDeltaPitch = Math.signum(qDeltaPitch) * Math.min(Math.abs(qDeltaPitch), MAX_DEG_PER_TICK);

        float newYaw   = lastSentYaw + qDeltaYaw;
        float newPitch = clamp(lastSentPitch + qDeltaPitch, -90f, 90f);
        lastSentYaw   = newYaw;
        lastSentPitch = newPitch;

        ticksElapsed++;
        return new float[]{ newYaw, newPitch };
    }

    /** True while a movement (including corrective sub-movement) is in progress. */
    public boolean isMoving() {
        return moving;
    }

    /**
     * True when the given rotation is within 0.5° of the target this movement was started for.
     * Always returns false when not moving.
     */
    public boolean isConverged(float yaw, float pitch) {
        if (!moving) return false;
        double dYaw   = Math.abs(wrapDegrees(yaw - targetYaw));
        double dPitch = Math.abs(pitch - targetPitch);
        return dYaw < 0.5 && dPitch < 0.5;
    }

    public float targetYaw()   { return targetYaw;   }
    public float targetPitch() { return targetPitch; }

    // -------------------------------------------------------------------------
    // Internals
    // -------------------------------------------------------------------------

    /**
     * The minimum-jerk velocity profile, smoothed by the EWMA and then normalised to sum to one.
     *
     * <p>Normalising is what makes the trajectory arrive. Smoothing the velocity fractions on its own
     * throws away the first tick's share of the displacement, so the movement stopped short of its
     * aim — and when an overshoot had been rolled, that shortfall was larger than the overshoot on
     * short moves and swallowed it, which is why a configured 20% of moves overshot only about 10% of
     * the time. The EWMA still shapes when the movement accelerates and when it fades; the
     * normalisation only puts the total back to one, so the movement ends where it was aimed.
     */
    private double[] buildProfile(int ticks) {
        double[] shares = new double[ticks];
        double   smoothed = 0.0;
        for (int i = 0; i < ticks; i++) {
            double tau     = ticks > 1 ? (double) i / (ticks - 1) : 1.0;
            double tauPrev = (i > 0 && ticks > 1) ? (double) (i - 1) / (ticks - 1) : 0.0;
            smoothed = ewmaAlpha * (mjDisplacement(tau) - mjDisplacement(tauPrev))
                     + (1.0 - ewmaAlpha) * smoothed;
            shares[i] = smoothed;
        }
        double sum = 0.0;
        for (double share : shares) sum += share;
        if (sum > 1.0e-9) {
            for (int i = 0; i < ticks; i++) shares[i] /= sum;
        } else {
            shares[ticks - 1] = 1.0;
        }
        return shares;
    }

    private void startCorrection() {
        correcting        = true;
        correctTick       = 0;
        correctStartYaw   = lastSentYaw;
        correctStartPitch = lastSentPitch;
    }

    /** Minimum-jerk corrective sub-movement — no noise, no overshoot. */
    private float[] tickCorrection(float gcd) {
        if (correctTick >= CORRECT_TICKS) {
            correcting = false;
            moving     = false;
            return null;
        }

        double tau = CORRECT_TICKS > 1
                     ? (double) correctTick / (CORRECT_TICKS - 1) : 1.0;
        double tauPrev = (correctTick > 0 && CORRECT_TICKS > 1)
                         ? (double) (correctTick - 1) / (CORRECT_TICKS - 1) : 0.0;

        double dYaw   = wrapDegrees(targetYaw   - correctStartYaw);
        double dPitch = targetPitch - correctStartPitch;

        double vRaw = mjDisplacement(tau) - mjDisplacement(tauPrev);
        float qDeltaYaw   = RotationUtil.quantize((float)(dYaw   * vRaw), gcd);
        float qDeltaPitch = RotationUtil.quantize((float)(dPitch * vRaw), gcd);

        float newYaw   = lastSentYaw + qDeltaYaw;
        float newPitch = clamp(lastSentPitch + qDeltaPitch, -90f, 90f);
        lastSentYaw   = newYaw;
        lastSentPitch = newPitch;

        correctTick++;
        return new float[]{ newYaw, newPitch };
    }

    /**
     * Harris-Wolpert signal-dependent noise, drawn in whole mouse steps.
     *
     * <p>The error grows with the size of the step, but a sub-step error cannot survive the
     * quantisation every angle has to satisfy: clamping it to ±gcd, adding it to the step and
     * rounding the sum rounded the noise away on every tick small enough to matter, so it never
     * moved the view at all. Drawing it in grid steps keeps both properties — every angle sent is a
     * movement the mouse could produce, and a large step genuinely carries the error the model asks
     * for (about 8% of the step, so roughly a step or two on the fast part of a turn, nothing on the
     * small ticks at either end).
     */
    private float noiseSteps(float gcd, double stepDegrees) {
        if (aimNoiseK <= 0.0 || gcd <= 1.0e-4f) return 0.0f;
        double sigmaSteps = aimNoiseK * stepDegrees / gcd;
        if (sigmaSteps <= 0.0) return 0.0f;
        long steps = Math.round(ThreadLocalRandom.current().nextGaussian() * sigmaSteps);
        if (steps == 0L) return 0.0f;
        // A couple of steps either way is a hand tremor; more would be a miss rather than a wobble.
        steps = Math.max(-2L, Math.min(2L, steps));
        return (float)(steps * gcd);
    }

    // -------------------------------------------------------------------------
    // Math helpers (no Minecraft imports)
    // -------------------------------------------------------------------------

    /** Minimum-jerk cumulative displacement: s(τ) = 10τ³ − 15τ⁴ + 6τ⁵, clamped to [0,1]. */
    private static double mjDisplacement(double tau) {
        if (tau <= 0.0) return 0.0;
        if (tau >= 1.0) return 1.0;
        double t2 = tau * tau;
        double t3 = t2  * tau;
        double t4 = t3  * tau;
        double t5 = t4  * tau;
        return 10*t3 - 15*t4 + 6*t5;
    }

    /**
     * Fitts's Law movement time converted to game ticks.
     * MT = A + B × log₂(D/W + 1), ticks = round(MT / 50ms), min 1.
     */
    private static int fittsLawTicks(double D) {
        double mt = FITTS_A + FITTS_B * (Math.log(D / TARGET_W + 1.0) / Math.log(2.0));
        return Math.max(1, (int) Math.round(mt / 50.0));
    }

    /** Wraps degrees to [−180, 180). */
    private static double wrapDegrees(double deg) {
        deg %= 360.0;
        if (deg >= 180.0) deg -= 360.0;
        else if (deg < -180.0) deg += 360.0;
        return deg;
    }

    private static float clamp(float val, float min, float max) {
        return Math.max(min, Math.min(max, val));
    }
}
