// lang: Java, file: RotationUtil.java, target: Fabric MC 26.3
// GCD quantization and Gaussian timing for AC-safe rotations and delays.
package com.veil.util;

import net.minecraft.client.Minecraft;
import java.util.concurrent.ThreadLocalRandom;

public final class RotationUtil {

    private RotationUtil() {}

    /**
     * Returns the GCD for the local player's current mouse sensitivity.
     * Rotation deltas are expressed as multiples of this value, which is the granularity the
     * sensitivity allows a mouse to produce.
     */
    public static float gcd() {
        double sens = Minecraft.getInstance().options.sensitivity().get();
        float g = (float)(sens * 0.6 + 0.2);
        g = g * g * g * 1.2f;
        return g;
    }

    /**
     * Quantize a rotation delta to the nearest multiple of gcd.
     * Pass 0 or negative gcd to skip quantization (sensitivity unknown).
     */
    public static float quantize(float delta, float gcd) {
        if (gcd <= 0.0001f) return delta;
        return Math.round(delta / gcd) * gcd;
    }

    /**
     * Returns a delay in milliseconds drawn from a Gaussian distribution.
     *
     * @param mean   base delay in ms
     * @param jitter fraction of mean to use as σ (e.g. 0.25 → σ = mean * 0.25)
     * @return delay clamped to [mean/2, mean*2]
     */
    public static long gaussianDelay(long mean, float jitter) {
        double sigma = mean * jitter;
        double raw   = mean + ThreadLocalRandom.current().nextGaussian() * sigma;
        return (long) Math.max(mean / 2.0, Math.min(mean * 2.0, raw));
    }
}
