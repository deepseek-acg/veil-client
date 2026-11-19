// lang: Java, file: ClientInputAccessor.java, target: Fabric MC 26.3
// ClientInput keeps its move vector protected; this exposes it to FreecamInputMixin, which has to
// zero it or the player keeps walking while the camera is detached.
package com.veil.mixin;

import net.minecraft.client.player.ClientInput;
import net.minecraft.world.phys.Vec2;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

@Mixin(ClientInput.class)
public interface ClientInputAccessor {

    @Accessor("moveVector")
    void mist$setMoveVector(Vec2 vector);
}
