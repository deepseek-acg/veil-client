// lang: Java, file: SilentAimStateTest.java, target: JVM unit test
package com.veil.util;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

/**
 * Checks the shape of the trajectories {@link SilentAimState} produces.
 *
 * <p>These run against the plain JVM: the class it drives is pure Java apart from the shared GCD
 * quantiser, so the properties that decide whether a turn looks like a hand moving a mouse — where
 * it peaks, whether it arrives, how often it overshoots and whether every step is a movement the
 * sensitivity could produce — can be checked without a client.
 */
final class SilentAimStateTest {

    private static final float GCD = 0.5f;
    /** One whole mouse step at the GCD every test uses. */
    private static final float STEP = GCD;

    // ------------------------------------------------------------------
    // Where the movement peaks: late enough to look like an accelerate-and-fade turn
    // ------------------------------------------------------------------

    @Test
    void peakVelocityIsInTheSecondHalfOfTheMovement() {
        SilentAimState state = new SilentAimState();
        state.configure(0.0, 0.0, 0.65);   // noise and overshoot off, so the shape is deterministic
        state.start(0f, 0f, 45f, 0f);

        float previous = 0f;
        float peakDelta = -1f;
        int   peakTick  = -1;
        int   tick      = 0;

        float[] out;
        while ((out = state.tick(GCD)) != null) {
            float delta = Math.abs(out[0] - previous);
            if (delta > peakDelta) {
                peakDelta = delta;
                peakTick  = tick;
            }
            previous = out[0];
            tick++;
        }

        assertTrue(peakTick == 3 || peakTick == 4,
                "peak velocity should land at tick 3 or 4 of a 45° turn, was tick " + peakTick);
    }

    // ------------------------------------------------------------------
    // Whether it arrives: the smoothed shares must still add up to the whole move
    // ------------------------------------------------------------------

    @Test
    void movementLandsOnTheTarget() {
        SilentAimState state = new SilentAimState();
        state.configure(0.0, 0.0, 1.0);    // no smoothing lag, no noise, no overshoot
        state.start(0f, 0f, 45f, 0f);

        float previous = 0f;
        float travelled = 0f;

        float[] out;
        while ((out = state.tick(GCD)) != null) {
            travelled += Math.abs(out[0] - previous);
            previous = out[0];
        }

        // One GCD step of slack covers rounding across the ticks.
        assertEquals(45f, travelled, 1.0f,
                "a 45° turn should travel 45° in total, travelled " + travelled);
        assertEquals(45f, previous, 1.0f, "the last angle sent should be the target");
    }

    @Test
    void smoothedMovementAlsoLandsOnTheTarget() {
        // The smoothing the default profile uses must not leave the turn short of its aim; that
        // shortfall is what used to swallow the overshoot rolled below.
        SilentAimState state = new SilentAimState();
        state.configure(0.0, 0.0, 0.65);
        state.start(0f, 0f, 45f, 0f);

        float last = 0f;
        float[] out;
        while ((out = state.tick(GCD)) != null) {
            last = out[0];
        }

        assertEquals(45f, last, 1.5f, "the smoothed 45° turn should still end on the target, ended at " + last);
    }

    // ------------------------------------------------------------------
    // How often it overshoots, and that the correction brings it back
    // ------------------------------------------------------------------

    @Test
    void overshootHappensAtTheConfiguredRate() {
        final int runs = 1000;
        final float to = 20f;

        int overshoots = 0;
        for (int i = 0; i < runs; i++) {
            SilentAimState state = new SilentAimState();
            state.configure(0.0, 0.20, 0.65);
            state.start(0f, 0f, to, 0f);

            float maxYaw = 0f;
            float[] out;
            while ((out = state.tick(GCD)) != null) {
                maxYaw = Math.max(maxYaw, out[0]);
            }
            // A rolled overshoot aims 2–6° past the target. The comparison cannot be against the
            // target itself: a trajectory that lands exactly on it still rounds to within half a
            // mouse step of 20°, which would read as an overshoot every single time.
            if (maxYaw > to + 1.0f) overshoots++;
        }

        double probability = (double) overshoots / runs;
        assertTrue(probability >= 0.15 && probability <= 0.25,
                "a configured 0.20 overshoot rate should show up as 0.15–0.25, measured " + probability);
    }

    @Test
    void correctionReturnsToTheTargetAfterAnOvershoot() {
        SilentAimState state = new SilentAimState();
        state.configure(0.0, 1.0, 0.65);   // always overshoot, so the corrective path is exercised
        state.start(0f, 0f, 20f, 0f);

        float previous = 0f;
        float maxYaw = 0f;
        float last = 0f;
        float[] out;
        while ((out = state.tick(GCD)) != null) {
            maxYaw = Math.max(maxYaw, out[0]);
            last = out[0];
            previous = out[0];
        }

        assertTrue(maxYaw > 20f, "an overshoot should pass the target, reached " + maxYaw);
        assertEquals(20f, last, 1.0f, "the correction should bring the view back to the target, ended at " + last);
        assertTrue(Float.isFinite(previous));
    }

    // ------------------------------------------------------------------
    // The smoothing itself: velocity rises before it fades
    // ------------------------------------------------------------------

    @Test
    void smoothedVelocityRisesBeforeItFalls() {
        SilentAimState state = new SilentAimState();
        state.configure(0.0, 0.0, 0.65);
        state.start(0f, 0f, 45f, 0f);

        float previous = 0f;
        float first = -1f;
        float second = -1f;
        int tick = 0;

        float[] out;
        while ((out = state.tick(GCD)) != null && tick < 2) {
            float delta = Math.abs(out[0] - previous);
            if (tick == 0) first = delta;
            else second = delta;
            previous = out[0];
            tick++;
        }

        assertTrue(second > first,
                "the second tick should move further than the first (" + second + " vs " + first + ")");
    }

    // ------------------------------------------------------------------
    // Every step is a movement the mouse could produce at this sensitivity
    // ------------------------------------------------------------------

    @Test
    void noiseMovesTheViewInWholeMouseSteps() {
        // The signal-dependent noise has to reach the view, not be rounded away by the quantiser: the
        // first step of a long turn should differ from the noise-free one, by whole mouse steps, at
        // least some of the time.
        float[] clean = trajectory(0.0);
        int differed = 0;

        for (int i = 0; i < 200; i++) {
            float[] noisy = trajectory(0.08);
            assertEquals(clean.length, noisy.length,
                    "noise must not change how many ticks a turn takes");
            boolean anyDifferent = false;
            for (int tick = 0; tick < clean.length; tick++) {
                float difference = noisy[tick] - clean[tick];
                assertWholeSteps(difference, "yaw", new float[]{0, 0, 180, 0});
                if (Math.abs(difference) > 1.0e-4f) anyDifferent = true;
            }
            if (anyDifferent) differed++;
        }

        assertTrue(differed > 0,
                "the configured noise should move the view by a whole step on at least one of 200 moves,"
                        + " it never did");
    }

    /** The per-tick yaw deltas of a 180° turn, with the given noise constant. */
    private static float[] trajectory(double aimNoiseK) {
        SilentAimState state = new SilentAimState();
        state.configure(aimNoiseK, 0.0, 1.0);   // no overshoot, no smoothing lag
        state.start(0f, 0f, 180f, 0f);

        java.util.List<Float> deltas = new java.util.ArrayList<>();
        float previous = 0f;
        float[] out;
        while ((out = state.tick(GCD)) != null) {
            deltas.add(out[0] - previous);
            previous = out[0];
        }

        float[] result = new float[deltas.size()];
        for (int i = 0; i < result.length; i++) result[i] = deltas.get(i);
        return result;
    }

    @Test
    void everyStepIsAWholeNumberOfMouseSteps() {
        float[][] moves = { {0, 0, 45, 0}, {0, 0, 5, 20}, {0, 0, 180, -45}, {0, 0, 20, 0} };

        for (float[] move : moves) {
            SilentAimState state = new SilentAimState();
            state.configure(0.08, 0.20, 0.65);   // noise and overshoot on: exercise every path
            state.start(move[0], move[1], move[2], move[3]);

            float previousYaw   = move[0];
            float previousPitch = move[1];
            float[] out;
            while ((out = state.tick(GCD)) != null) {
                assertWholeSteps(out[0] - previousYaw, "yaw", move);
                assertWholeSteps(out[1] - previousPitch, "pitch", move);
                previousYaw   = out[0];
                previousPitch = out[1];
            }
        }
    }

    private static void assertWholeSteps(float delta, String axis, float[] move) {
        float steps = Math.abs(delta) / STEP;
        assertEquals(Math.round(steps), steps, 1.0e-3f,
                axis + " moved " + delta + "°, which is not a whole number of " + STEP
                        + "° steps, on move " + java.util.Arrays.toString(move));
    }
}
