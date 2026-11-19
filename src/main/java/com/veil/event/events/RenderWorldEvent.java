// lang: Java, file: RenderWorldEvent.java, target: Fabric MC 26.3
// Posted once per frame, inside the frame's gizmo scope, just before the world is extracted.
package com.veil.event.events;

/**
 * Fired once per frame with the camera already moved into this frame's position, while the frame's
 * gizmo collector is open — so anything added with {@code Gizmos} here is drawn this frame, at
 * interpolated positions, instead of being queued for the next tick.
 *
 * <p>This is the hook that makes overlays smooth: a tick handler runs twenty times a second and its
 * shapes then sit still between ticks, which reads as jitter against anything the game draws.
 */
public final class RenderWorldEvent {

    /** 0–1 between the last tick and this frame, for interpolating entity positions. */
    public final float partialTick;

    public RenderWorldEvent(float partialTick) {
        this.partialTick = partialTick;
    }
}
