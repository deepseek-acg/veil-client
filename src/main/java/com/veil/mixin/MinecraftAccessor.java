package com.veil.mixin;

import net.minecraft.client.Minecraft;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

/**
 * Accessor for {@code Minecraft#rightClickDelay}, the client-side item-use throttle.
 *
 * <p>FastPlace used to reach this field by name with reflection and silently did nothing when the
 * name did not match. A mixin accessor is resolved when the mixin is applied, so a rename fails
 * loudly at startup instead of disabling the module without a trace.
 */
@Mixin(Minecraft.class)
public interface MinecraftAccessor {

    @Accessor("rightClickDelay")
    void setRightClickDelay(int delay);
}
