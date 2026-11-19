// lang: Java, file: AutoSprint.java, target: Fabric MC 26.3
// Keeps the player sprinting while they are moving.
package com.veil.module.modules.movement;

import com.veil.Veil;
import com.veil.event.EventHandler;
import com.veil.event.events.TickEvent;
import com.veil.module.Category;
import com.veil.module.Module;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.network.protocol.game.ServerboundPlayerCommandPacket;
import net.minecraft.world.phys.Vec2;

/**
 * AutoSprint — keeps the player sprinting whenever they are moving.
 *
 * <p>Sends START_SPRINTING each tick the player has forward/strafe input but is
 * not yet sprinting. Respects vanilla food-level threshold.
 *
 * <p>Input access: 26.3 keeps movement input in {@code LocalPlayer#input}, whose
 * {@code getMoveVector()} returns the forward/strafe pair. The old {@code zza}/{@code xxa} fields
 * no longer exist on the entity, so reading them would not compile.
 *
 * <p>The sprint packet is rate limited: vanilla accepts one start-sprint per transition, and
 * repeating it every tick while the server refuses (low food, blocked path) is packet spam.
 */
public final class AutoSprint extends Module {

    private static final long RESEND_MS = 250L;

    private long lastPacket;

    public AutoSprint() {
        super("AutoSprint", "Always sprints while moving", Category.MOVEMENT);
    }

    @Override
    public void onEnable() {
        lastPacket = 0;
    }

    @EventHandler
    private void onTick(TickEvent.Pre event) {
        if (!Veil.inGame()) return;
        LocalPlayer self = Veil.mc.player;
        if (self.isSprinting() || self.isUnderWater()) return;
        if (self.getFoodData().getFoodLevel() <= 6) return;

        Vec2 move = self.input == null ? Vec2.ZERO : self.input.getMoveVector();
        boolean moving = move.x * move.x + move.y * move.y > 1.0e-4f;
        if (!moving) return;

        long now = System.currentTimeMillis();
        if (now - lastPacket < RESEND_MS) return;
        lastPacket = now;

        if (Veil.mc.getConnection() == null) return;
        Veil.mc.getConnection().send(new ServerboundPlayerCommandPacket(
                self, ServerboundPlayerCommandPacket.Action.START_SPRINTING));
        self.setSprinting(true);
    }
}
