// lang: Java, file: SilentCrystalProfile.java, target: Fabric MC 26.3
package com.veil.module.modules.combat;

import com.veil.util.ServerDetector;

/**
 * Per-server timing and range preset for the crystal and anchor modules.
 *
 * <p>This is not a bypass ceiling: {@link com.veil.util.ServerProfile} already carries the reach,
 * velocity and CPS limits. This is only how fast and how evenly the work goes out — how long a run
 * of clicks lasts before a pause, how often an eligible click is dropped, and how far the aim may
 * drift. The ranges and damage limits are here so AnchorAura can start from something reasonable for
 * the server instead of one fixed set everywhere.
 *
 * <p>The presets are keyed off the same {@link com.veil.util.ServerProfile} the combat modules use,
 * so there is one answer to "which server is this" rather than one per feature.
 */
public final class SilentCrystalProfile {

    public final int     burstTicksMin;
    public final int     burstTicksMax;
    public final int     idleTicksMin;
    public final int     idleTicksMax;
    public final float   skipProbability;
    public final float   targetRange;
    public final float   placeRange;
    public final float   minDamage;
    public final float   maxSelfDamage;
    public final int     rearmDelayTicks;
    public final float   microSaccadeMaxDeg;
    public final boolean gcdEnforcedSaccade;
    public final float   jitterFraction;
    public final double  aimNoiseK;
    public final double  overshootProb;
    public final double  ewmaAlpha;

    private SilentCrystalProfile(Builder b) {
        this.burstTicksMin      = b.burstTicksMin;
        this.burstTicksMax      = b.burstTicksMax;
        this.idleTicksMin       = b.idleTicksMin;
        this.idleTicksMax       = b.idleTicksMax;
        this.skipProbability    = b.skipProbability;
        this.targetRange        = b.targetRange;
        this.placeRange         = b.placeRange;
        this.minDamage          = b.minDamage;
        this.maxSelfDamage      = b.maxSelfDamage;
        this.rearmDelayTicks    = b.rearmDelayTicks;
        this.microSaccadeMaxDeg = b.microSaccadeMaxDeg;
        this.gcdEnforcedSaccade = b.gcdEnforcedSaccade;
        this.jitterFraction     = b.jitterFraction;
        this.aimNoiseK          = b.aimNoiseK;
        this.overshootProb      = b.overshootProb;
        this.ewmaAlpha          = b.ewmaAlpha;
    }

    public static final SilentCrystalProfile HYPIXEL = new Builder()
            .burst(8, 14).idle(2, 5).skip(0.08f)
            .reach(4.0f, 4.2f, 5.0f, 7.0f).rearm(3)
            .saccade(1.5f, false).jitter(0.25f)
            .aim(0.08, 0.20, 0.65).build();

    public static final SilentCrystalProfile GRIM = new Builder()
            .burst(10, 18).idle(1, 3).skip(0.05f)
            .reach(4.5f, 4.5f, 4.0f, 8.0f).rearm(2)
            .saccade(1.2f, false).jitter(0.20f)
            .aim(0.06, 0.18, 0.70).build();

    public static final SilentCrystalProfile INTAVE = new Builder()
            .burst(6, 10).idle(3, 6).skip(0.15f)
            .reach(4.0f, 4.0f, 4.5f, 6.0f).rearm(4)
            .saccade(1.8f, true).jitter(0.35f)
            .aim(0.10, 0.22, 0.60).build();

    public static final SilentCrystalProfile DEFAULT = new Builder()
            .burst(10, 16).idle(2, 4).skip(0.08f)
            .reach(4.5f, 4.5f, 4.0f, 6.0f).rearm(2)
            .saccade(1.5f, false).jitter(0.25f)
            .aim(0.08, 0.20, 0.65).build();

    /** The preset for the server the player is on, or {@link #DEFAULT} when none is recognised. */
    public static SilentCrystalProfile forCurrentServer() {
        return switch (ServerDetector.current()) {
            case HYPIXEL -> HYPIXEL;
            case GRIM    -> GRIM;
            case INTAVE  -> INTAVE;
            case DEFAULT -> DEFAULT;
        };
    }

    public static final class Builder {
        int     burstTicksMin = 10;
        int     burstTicksMax = 16;
        int     idleTicksMin  = 2;
        int     idleTicksMax  = 4;
        float   skipProbability = 0.08f;
        float   targetRange     = 4.5f;
        float   placeRange      = 4.5f;
        float   minDamage       = 4.0f;
        float   maxSelfDamage   = 6.0f;
        int     rearmDelayTicks = 2;
        float   microSaccadeMaxDeg = 1.5f;
        boolean gcdEnforcedSaccade = false;
        /** Standard deviation of a delay, as a fraction of its configured mean. */
        float   jitterFraction     = 0.25f;

        // Aim trajectory: signal-dependent noise, how often a long move overshoots and is corrected
        // back, and how strongly the per-tick velocity is smoothed.
        double  aimNoiseK     = 0.08;
        double  overshootProb = 0.20;
        double  ewmaAlpha     = 0.65;

        public Builder burst(int min, int max) { burstTicksMin = min; burstTicksMax = max; return this; }
        public Builder idle(int min, int max)  { idleTicksMin = min; idleTicksMax = max; return this; }
        public Builder skip(float p)           { skipProbability = p; return this; }
        public Builder rearm(int ticks)        { rearmDelayTicks = ticks; return this; }

        public Builder reach(float target, float place, float minDamage, float maxSelfDamage) {
            this.targetRange    = target;
            this.placeRange     = place;
            this.minDamage      = minDamage;
            this.maxSelfDamage  = maxSelfDamage;
            return this;
        }

        public Builder saccade(float maxDegrees, boolean gcdQuantised) {
            this.microSaccadeMaxDeg = maxDegrees;
            this.gcdEnforcedSaccade = gcdQuantised;
            return this;
        }

        public Builder jitter(float fraction) {
            this.jitterFraction = fraction;
            return this;
        }

        public Builder aim(double noiseK, double overshootProbability, double alpha) {
            this.aimNoiseK     = noiseK;
            this.overshootProb = overshootProbability;
            this.ewmaAlpha     = alpha;
            return this;
        }

        public SilentCrystalProfile build() { return new SilentCrystalProfile(this); }
    }
}
