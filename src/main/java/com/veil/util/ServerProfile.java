// lang: Java, file: ServerProfile.java, target: Fabric MC 26.3
package com.veil.util;

public enum ServerProfile {
    HYPIXEL,
    GRIM,
    INTAVE,
    DEFAULT;

    /** Maximum safe reach in blocks (exclusive ceiling). */
    public float reachCeiling() {
        return switch (this) {
            case HYPIXEL -> 3.1f;
            case GRIM    -> 2.99f;
            case INTAVE  -> 2.95f;
            case DEFAULT -> 3.0f;
        };
    }

    /** Safe horizontal knockback multiplier. */
    public float velocityHorizontal() {
        return switch (this) {
            case HYPIXEL -> 0.92f;
            case GRIM    -> 1.0f;
            case INTAVE  -> 0.97f;
            case DEFAULT -> 0.92f;
        };
    }

    /** Safe vertical knockback multiplier. */
    public float velocityVertical() {
        return 1.0f;
    }

    /** Maximum safe CPS upper bound. */
    public float cpsMax() {
        return switch (this) {
            case HYPIXEL -> 16f;
            case GRIM    -> 14f;
            case INTAVE  -> 12f;
            case DEFAULT -> 14f;
        };
    }

    /** Whether W-Tap is safe on this server. */
    public boolean wtapSafe() {
        return this == HYPIXEL;
    }

    /** Whether a status-only crit spoof is safe here (Intave detects it reliably). */
    public boolean critSpoofSafe() {
        return this != INTAVE;
    }
}
