// lang: Java, file: Velocity.java, target: Fabric MC 26.3
package com.veil.module.modules.combat;

import com.veil.Veil;
import com.veil.event.EventHandler;
import com.veil.event.events.PacketEvent;
import com.veil.module.Category;
import com.veil.module.Module;
import com.veil.setting.FloatSetting;
import com.veil.setting.ModeSetting;
import com.veil.util.ServerDetector;
import com.veil.util.ServerProfile;
import net.minecraft.network.protocol.game.ClientboundSetEntityMotionPacket;
import net.minecraft.world.phys.Vec3;

public final class Velocity extends Module {

    private final FloatSetting horizontal = register(new FloatSetting("Horizontal", 0.92f, 0.0f, 1.0f));
    private final FloatSetting vertical   = register(new FloatSetting("Vertical",   1.00f, 0.0f, 1.0f));
    private final ModeSetting  serverProfile = register(new ModeSetting("Server", "Auto",
            new String[]{"Auto", "Hypixel", "Grim", "Intave", "Default"}));

    public Velocity() {
        super("Velocity", "Scales incoming knockback", Category.COMBAT);
    }

    @EventHandler
    private void onPacket(PacketEvent.Receive event) {
        if (!Veil.inGame()) return;
        if (!(event.getPacket() instanceof ClientboundSetEntityMotionPacket pkt)) return;
        if (pkt.id() != Veil.mc.player.getId()) return;

        ServerProfile profile = ServerDetector.resolve(serverProfile.get());

        // Simulation-based ACs (Intave/Grim) will flag any horizontal reduction.
        // Hard-enforce the safe floor; the user setting cannot go below it on these servers.
        float hMult = (float) Math.min(1.0, horizontal.get());
        float vMult = (float) Math.min(1.0, vertical.get());

        if (profile == ServerProfile.INTAVE || profile == ServerProfile.GRIM) {
            hMult = Math.max(hMult, profile.velocityHorizontal());
        }

        event.setCancelled(true);

        Vec3 motion = pkt.movement();
        Veil.mc.player.setDeltaMovement(motion.x * hMult, motion.y * vMult, motion.z * hMult);
    }

    @Override
    public String suffix() {
        ServerProfile profile = ServerDetector.resolve(serverProfile.get());
        float display = (float) Math.min(1.0, horizontal.get());
        if ((profile == ServerProfile.INTAVE || profile == ServerProfile.GRIM)
                && display < profile.velocityHorizontal()) {
            return String.format("%.0f%% §c[floored %.0f%%]", display * 100f, profile.velocityHorizontal() * 100f);
        }
        return String.format("%.0f%%", display * 100f);
    }
}
