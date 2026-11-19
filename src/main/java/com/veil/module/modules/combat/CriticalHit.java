// lang: Java, file: CriticalHit.java, target: Fabric MC 26.3
package com.veil.module.modules.combat;

import com.veil.Veil;
import com.veil.event.EventHandler;
import com.veil.event.events.TickEvent;
import com.veil.module.Category;
import com.veil.module.Module;
import com.veil.setting.ModeSetting;
import com.veil.util.ServerDetector;
import com.veil.util.ServerProfile;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.network.protocol.game.ServerboundMovePlayerPacket;

import java.util.concurrent.ThreadLocalRandom;

public final class CriticalHit extends Module {

    private final ModeSetting serverProfile = register(new ModeSetting("Server", "Auto",
            new String[]{"Auto", "Hypixel", "Grim", "Intave", "Default"}));

    private long lastCritTime = 0;
    private int  lastAttackTick = -1;

    private static final long RATE_LIMIT_MS = 350;

    public CriticalHit() {
        super("CriticalHit", "Spoofs onGround=false to register crits server-side", Category.COMBAT);
    }

    @EventHandler
    private void onTick(TickEvent.Pre event) {
        if (!Veil.inGame()) return;
        LocalPlayer self = Veil.mc.player;
        ServerProfile profile = ServerDetector.resolve(serverProfile.get());

        // Intave simulates movement server-side and reliably detects the StatusOnly timing pattern.
        // Ground-only crits with correct sprint-reset are safer on Intave; skip the spoof entirely.
        if (!profile.critSpoofSafe()) return;

        if (!self.onGround()) return;
        if (!Veil.mc.options.keyAttack.isDown()) return;

        long now = System.currentTimeMillis();
        if (now - lastCritTime < RATE_LIMIT_MS) return;

        // Gaussian jitter on rate limit: mean=400ms, sigma=60ms, clamped [320, 480].
        double jitterMs = 400 + ThreadLocalRandom.current().nextGaussian() * 60;
        jitterMs = Math.max(320, Math.min(480, jitterMs));
        if (now - lastCritTime < (long) jitterMs) return;

        // Stochastic activation: only 60–80% of eligible ticks send the spoof.
        // Avoids machine-perfect every-eligible-tick signatures.
        float sendProb = 0.65f + ThreadLocalRandom.current().nextFloat() * 0.15f;
        if (ThreadLocalRandom.current().nextFloat() > sendProb) return;

        if (Veil.mc.getConnection() == null) return;
        Veil.mc.getConnection().send(new ServerboundMovePlayerPacket.StatusOnly(false, false));
        lastCritTime = now;
        lastAttackTick = self.tickCount;
    }
}
