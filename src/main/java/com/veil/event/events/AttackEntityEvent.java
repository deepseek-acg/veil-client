// lang: Java, file: AttackEntityEvent.java, target: Fabric MC 26.3
package com.veil.event.events;

import net.minecraft.world.entity.Entity;

/** Fired when the local player attacks an entity via MultiPlayerGameMode.attack(). */
public class AttackEntityEvent {
    private final Entity entity;

    public AttackEntityEvent(Entity entity) {
        this.entity = entity;
    }

    public Entity getEntity() { return entity; }
}
