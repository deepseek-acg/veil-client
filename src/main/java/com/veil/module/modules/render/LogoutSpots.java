// lang: Java, file: LogoutSpots.java, target: Fabric MC 26.3
// Port of nyx LogoutSpots (com.nyx.modules.LogoutSpots).
package com.veil.module.modules.render;

import com.veil.Veil;
import com.veil.event.EventHandler;
import com.veil.event.events.RenderWorldEvent;
import com.veil.event.events.TickEvent;
import com.veil.module.Category;
import com.veil.module.Module;
import com.veil.setting.BoolSetting;
import com.veil.setting.FloatSetting;
import com.veil.setting.IntSetting;
import net.minecraft.client.Camera;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.gizmos.GizmoStyle;
import net.minecraft.gizmos.Gizmos;
import net.minecraft.gizmos.TextGizmo;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceKey;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Logout Spots: when a player you could see leaves the server, their spot is marked with a box and a
 * tag showing name, health, how long ago and how far away. The marker goes away when they come back
 * online, when it ages out, or when you join a different server.
 *
 * <p>A player who merely walks out of view is not a logout: they are held for three seconds and only
 * marked once they are also gone from the player list, which is what the server's tab list says.
 */
public final class LogoutSpots extends Module {

    private record Snap(String name, AABB box, float health, ResourceKey<Level> dim, int tick) {}

    private record Spot(UUID id, String name, AABB box, float health, ResourceKey<Level> dim, long time) {}

    private static final int COLOUR = 0xFFFF9A2E;

    private final BoolSetting  notify   = register(new BoolSetting("Notify", true));
    private final BoolSetting  tracers  = register(new BoolSetting("Tracers", false));
    private final FloatSetting tagSize  = register(new FloatSetting("Tag Size", 1.0f, 0.5f, 3.0f));
    private final IntSetting   maxAge   = register(new IntSetting("Forget After", 0, 0, 600));

    private final Map<UUID, Snap> seen = new HashMap<>();
    private final Map<UUID, Snap> gone = new HashMap<>();
    private final List<Spot> spots = new ArrayList<>();
    private Object lastConnection;
    private int tick;

    public LogoutSpots() {
        super("LogoutSpots", "Marks where players were standing when they logged out.", Category.RENDER);
    }

    @Override
    public void onEnable() {
        seen.clear();
        gone.clear();
        spots.clear();
        lastConnection = null;
        tick = 0;
    }

    private static void info(String text) {
        if (Veil.mc == null || Veil.mc.gui == null || Veil.mc.gui.hud == null) return;
        Veil.mc.gui.hud.getChat().addClientSystemMessage(Component.literal("§5§lVeil §8» §7" + text));
    }

    @EventHandler
    private void onTick(TickEvent.Pre event) {
        if (!Veil.inGame() || Veil.mc.getConnection() == null) return;
        tick++;

        // A different server: spots from the last one mean nothing here.
        if (lastConnection != Veil.mc.getConnection()) {
            lastConnection = Veil.mc.getConnection();
            spots.clear();
            seen.clear();
            gone.clear();
        }

        ClientLevel level = Veil.mc.level;
        ResourceKey<Level> dim = level.dimension();

        Map<UUID, Snap> now = new HashMap<>();
        for (Player player : level.players()) {
            if (player == Veil.mc.player) continue;
            now.put(player.getUUID(), new Snap(player.getName().getString(), player.getBoundingBox(),
                    player.getHealth() + player.getAbsorptionAmount(), dim, tick));
        }

        // Players that just disappeared from view.
        for (Map.Entry<UUID, Snap> e : seen.entrySet()) {
            if (!now.containsKey(e.getKey()) && e.getValue().dim() == dim) gone.put(e.getKey(), e.getValue());
        }

        // Gone from the player list too? Give the list update a couple of seconds to arrive.
        for (Iterator<Map.Entry<UUID, Snap>> it = gone.entrySet().iterator(); it.hasNext(); ) {
            Map.Entry<UUID, Snap> e = it.next();
            if (now.containsKey(e.getKey())) {
                it.remove();
                continue;
            }
            if (Veil.mc.getConnection().getPlayerInfo(e.getKey()) == null) {
                Snap snap = e.getValue();
                spots.removeIf(spot -> spot.id().equals(e.getKey()));
                spots.add(new Spot(e.getKey(), snap.name(), snap.box(), snap.health(), snap.dim(),
                        System.currentTimeMillis()));
                if (notify.get()) {
                    Vec3 centre = snap.box().getBottomCenter();
                    info(snap.name() + " logged out at " + (int) Math.floor(centre.x) + " "
                            + (int) Math.floor(centre.y) + " " + (int) Math.floor(centre.z) + " ("
                            + Math.round(Veil.mc.player.position().distanceTo(centre)) + "m away).");
                }
                it.remove();
            } else if (tick - e.getValue().tick() > 60) {
                it.remove();    // still online: they walked or teleported out of view
            }
        }

        seen.clear();
        seen.putAll(now);

        // Back online: drop the spot.
        long nowMs = System.currentTimeMillis();
        for (Iterator<Spot> it = spots.iterator(); it.hasNext(); ) {
            Spot spot = it.next();
            if (Veil.mc.getConnection().getPlayerInfo(spot.id()) != null) {
                if (notify.get()) info(spot.name() + " logged back in.");
                it.remove();
            } else if (maxAge.get() > 0 && nowMs - spot.time() > maxAge.get() * 60_000L) {
                it.remove();
            }
        }
    }

    @EventHandler
    private void onRender(RenderWorldEvent event) {
        if (!Veil.inGame() || spots.isEmpty()) return;

        Camera camera = Veil.mc.gameRenderer.mainCamera();
        Vec3 camPos = camera.position();
        Vec3 start = camPos.add(Vec3.directionFromRotation(camera.xRot(), camera.yRot()).scale(0.6));
        ResourceKey<Level> dim = Veil.mc.level.dimension();
        long nowMs = System.currentTimeMillis();

        for (Spot spot : spots) {
            if (spot.dim() != dim) continue;

            Gizmos.cuboid(spot.box(), GizmoStyle.strokeAndFill(COLOUR, 2f, 0x40FF9A2E)).setAlwaysOnTop();
            if (tracers.get()) Gizmos.line(start, spot.box().getCenter(), COLOUR, 1.5f).setAlwaysOnTop();

            Vec3 top = new Vec3(spot.box().getCenter().x, spot.box().maxY, spot.box().getCenter().z);
            double camDistance = camPos.distanceTo(top);
            // Grow with distance so the tag keeps roughly the same size on screen.
            float scale = (float) (TextGizmo.Style.DEFAULT_SCALE * tagSize.get()
                    * Math.max(1.0, camDistance / 6.0));
            String text = spot.name() + "  " + Math.round(spot.health()) + "❤  " + ago(nowMs - spot.time()) + "  "
                    + Math.round(Veil.mc.player.position().distanceTo(spot.box().getBottomCenter())) + "m";
            Gizmos.billboardText(text, top.add(0, 0.35 + scale * 0.4, 0),
                    TextGizmo.Style.forColorAndCentered(COLOUR).withScale(scale)).setAlwaysOnTop();
        }
    }

    private static String ago(long ms) {
        long seconds = ms / 1000;
        if (seconds < 60) return "logged out " + seconds + "s ago";
        long minutes = seconds / 60;
        if (minutes < 60) return "logged out " + minutes + "m ago";
        return "logged out " + (minutes / 60) + "h " + (minutes % 60) + "m ago";
    }

    @Override
    public String suffix() {
        return spots.isEmpty() ? null : String.valueOf(spots.size());
    }
}
