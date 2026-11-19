// lang: Java, file: FastPlace.java, target: Fabric MC 26.3
// Zeroes the client-side right-click delay to remove the vanilla placement throttle.
package com.veil.module.modules.utility;

import com.veil.Veil;
import com.veil.event.EventHandler;
import com.veil.event.events.TickEvent;
import com.veil.mixin.MinecraftAccessor;
import com.veil.module.Category;
import com.veil.module.Module;

/**
 * FastPlace — clears {@code Minecraft#rightClickDelay} every tick so block placement is not
 * throttled by the client's own item-use cooldown.
 *
 * <p>The field is private, so it is reached through {@link MinecraftAccessor}. The previous
 * implementation looked the field up by name with reflection and guessed between two names; when
 * neither matched the module did nothing and never said so.
 */
public final class FastPlace extends Module {

    public FastPlace() {
        super("FastPlace", "Removes client-side block placement cooldown", Category.UTILITY);
    }

    @EventHandler
    private void onTick(TickEvent.Pre event) {
        if (!Veil.inGame()) return;
        ((MinecraftAccessor) (Object) Veil.mc).setRightClickDelay(0);
    }
}
