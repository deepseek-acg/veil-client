// lang: Java, file: PlayerESP.java, target: Fabric MC 26.3
// Port of nyx PlayerESP (com.nyx.modules.PlayerESP).
package com.veil.module.modules.render;

import com.veil.Veil;
import com.veil.event.EventHandler;
import com.veil.event.events.HudRenderEvent;
import com.veil.event.events.RenderWorldEvent;
import com.veil.module.Category;
import com.veil.module.Module;
import com.veil.setting.BoolSetting;
import com.veil.setting.FloatSetting;
import com.veil.setting.IntSetting;
import net.minecraft.client.Camera;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.gizmos.GizmoStyle;
import net.minecraft.gizmos.Gizmos;
import net.minecraft.gizmos.TextGizmo;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

/**
 * Player ESP, ported from nyx: a box around every other player (through walls), a tracer from the
 * crosshair to each one, and a name tag above them with their name, health and distance that stays
 * readable however far away they are, for every player within Range — plus an on-screen list of who is
 * nearby, closest first, with distance and coordinates.
 *
 * <p>Handled on {@link RenderWorldEvent} — once per frame, with the camera already at this frame's
 * position — rather than on the tick, and every box is moved by the entity's interpolated position for
 * the frame. That is the difference between an overlay that glides with the game and one that steps
 * twenty times a second.
 */
public final class PlayerESP extends Module {

    private static final int PURPLE = 0xFFB46CFF;

    // The list follows the arraylist's look: dark row, teal bar, one line per player.
    private static final int LIST_BG      = 0x8C0B0B0E;
    private static final int LIST_ACCENT  = 0xFF2BD9C0;
    private static final int LIST_TEXT    = 0xFFEEEEFF;
    private static final int LIST_DIM     = 0xFF8A8A99;
    private static final int LINE_H       = 10;
    private static final int LIST_TITLE_H = 13;
    /** Top-left, under the target readout, which ends at y 52. */
    private static final int LIST_LEFT_Y  = 56;

    /** One line of the list: who, what to say about them, and the swatch that matches their box. */
    private record Row(String name, String info, int colour) {}

    private final FloatSetting range   = register(new FloatSetting("Range", 64.0f, 8.0f, 256.0f));
    private final BoolSetting  boxes   = register(new BoolSetting("Boxes", true));
    private final IntSetting   fill    = register(new IntSetting("Fill Opacity", 45, 0, 255));
    private final BoolSetting  tracers = register(new BoolSetting("Tracers", true));
    private final FloatSetting lineWidth = register(new FloatSetting("Line Width", 2.0f, 0.5f, 6.0f));
    private final BoolSetting  colorByDistance = register(new BoolSetting("Color By Distance", true));
    private final BoolSetting  showInvisible   = register(new BoolSetting("Show Invisible", true));

    private final BoolSetting  nameTags  = register(new BoolSetting("Name Tags", true));
    private final BoolSetting  tagHealth = register(new BoolSetting("Tag Health", true));
    private final BoolSetting  tagDistance = register(new BoolSetting("Tag Distance", true));
    private final BoolSetting  tagCoords = register(new BoolSetting("Tag Coords", false));
    private final FloatSetting tagSize   = register(new FloatSetting("Tag Size", 1.0f, 0.5f, 3.0f));

    private final BoolSetting  list      = register(new BoolSetting("Show List", true));
    private final IntSetting   listMax   = register(new IntSetting("List Max", 8, 1, 30));
    private final BoolSetting  listRight = register(new BoolSetting("List On Right", false));

    public PlayerESP() {
        super("PlayerESP",
              "Boxes, tracers and name tags on other players so you always know where they are.",
              Category.RENDER);
    }

    /** Whether this player gets a box: not you, alive, not a spectator, in range, and visible if asked. */
    private boolean accepts(Player player) {
        if (player == Veil.mc.player || !player.isAlive() || player.isSpectator()) return false;
        if (player.isInvisible() && !showInvisible.get()) return false;
        return Veil.mc.player.distanceTo(player) <= range.get();
    }

    /** Red when close, through orange and yellow, to green when far. Off: everyone is purple. */
    private int colorFor(double distance) {
        if (!colorByDistance.get()) return PURPLE;
        if (distance < 8)  return 0xFFFF4040;
        if (distance < 20) return 0xFFFF9A2E;
        if (distance < 48) return 0xFFFFE04A;
        return 0xFF4ADE80;
    }

    @EventHandler
    private void onRender(RenderWorldEvent event) {
        if (!Veil.inGame()) return;

        Camera camera = Veil.mc.gameRenderer.mainCamera();
        Vec3 cameraPos = camera.position();
        Vec3 start = cameraPos.add(Vec3.directionFromRotation(camera.xRot(), camera.yRot()).scale(0.6));
        float width = lineWidth.get();
        int alpha = fill.get();

        for (Player player : Veil.mc.level.players()) {
            if (!accepts(player)) continue;

            // Move the box by how far the player has travelled into this frame, or it snaps at 20 Hz.
            Vec3 lerp = player.getPosition(event.partialTick).subtract(player.position());
            AABB box = player.getBoundingBox().move(lerp.x, lerp.y, lerp.z);
            double distance = Veil.mc.player.distanceTo(player);
            int colour = colorFor(distance);

            if (boxes.get()) {
                GizmoStyle style = alpha > 0
                        ? GizmoStyle.strokeAndFill(colour, width, (alpha << 24) | (colour & 0xFFFFFF))
                        : GizmoStyle.stroke(colour, width);
                Gizmos.cuboid(box, style).setAlwaysOnTop();
            }

            if (tracers.get()) {
                Vec3 end = new Vec3(box.getCenter().x, box.minY + box.getYsize() * 0.6, box.getCenter().z);
                Gizmos.line(start, end, colour, width).setAlwaysOnTop();
            }

            if (nameTags.get()) {
                // Grow with distance so the tag keeps roughly the same size on screen.
                double camDistance = cameraPos.distanceTo(new Vec3(box.getCenter().x, box.maxY, box.getCenter().z));
                float scale = (float) (TextGizmo.Style.DEFAULT_SCALE * tagSize.get()
                        * Math.max(1.0, camDistance / 6.0));
                double lineHeight = scale * 0.6;   // roughly the world height of one text line

                StringBuilder top = new StringBuilder(player.getName().getString());
                if (tagHealth.get()) {
                    top.append("  ").append(Math.round(player.getHealth() + player.getAbsorptionAmount())).append("❤");
                }
                if (tagDistance.get()) top.append("  ").append(Math.round(distance)).append("m");
                if (player.isInvisible()) top.append("  (invisible)");

                Vec3 at = new Vec3(box.getCenter().x,
                                   box.maxY + 0.35 + lineHeight * (tagCoords.get() ? 1.6 : 0.6),
                                   box.getCenter().z);
                Gizmos.billboardText(top.toString(), at,
                        TextGizmo.Style.forColorAndCentered(colour).withScale(scale)).setAlwaysOnTop();

                if (tagCoords.get()) {
                    String coords = (int) Math.floor(player.getX()) + " "
                            + (int) Math.floor(player.getY()) + " "
                            + (int) Math.floor(player.getZ());
                    Gizmos.billboardText(coords, at.add(0, -lineHeight * 1.1, 0),
                            TextGizmo.Style.forColorAndCentered(0xFFD0D0D0).withScale(scale * 0.8f))
                            .setAlwaysOnTop();
                }
            }
        }
    }

    /**
     * The nearby-player list, on mist's own HUD pass rather than nyx's Legend widget.
     *
     * <p>Same content — everyone in range, closest first, with distance and coordinates, and the
     * swatch in front of each name drawn in the colour that player's box has — laid out as one panel:
     * top-left under the target readout, or stacked in the bottom-right corner to stay clear of chat
     * and of the module list.
     */
    @EventHandler
    private void onHud(HudRenderEvent event) {
        if (!Veil.inGame() || !list.get()) return;
        if (Veil.mc.gui != null && Veil.mc.gui.hud.isHidden()) return;

        GuiGraphicsExtractor ctx = event.ctx;
        boolean right = listRight.get();

        // Filter by Range first, then order: the list says who is nearby, not who is listed.
        List<Player> nearby = new ArrayList<>();
        for (Player player : Veil.mc.level.players()) {
            if (accepts(player)) nearby.add(player);
        }
        if (nearby.isEmpty()) return;
        nearby.sort(Comparator.comparingDouble(player -> Veil.mc.player.distanceToSqr(player)));

        Font font = Veil.mc.font;
        String title = "Players (" + nearby.size() + ")";
        // Rows that would not fit are dropped rather than drawn off the top of the screen: the panel
        // grows downwards from under the target readout, or upwards from the bottom-right corner.
        int room = right ? ctx.guiHeight() - 8 : ctx.guiHeight() - LIST_LEFT_Y - 4;
        int shown = Math.min(Math.min(nearby.size(), listMax.get()), Math.max(1, room / LINE_H));
        int width = font.width(title);

        List<Row> rows = new ArrayList<>(shown);
        for (int i = 0; i < shown; i++) {
            Player player = nearby.get(i);
            double distance = Veil.mc.player.distanceTo(player);
            Row row = new Row(player.getName().getString(), info(player, distance), colorFor(distance));
            rows.add(row);
            width = Math.max(width, font.width(row.name()) + 4 + font.width(row.info()));
        }

        int panelWidth = width + 17;
        int panelHeight = LIST_TITLE_H + rows.size() * LINE_H + 2;
        int x = right ? ctx.guiWidth() - 4 - panelWidth : 4;
        int y = right ? ctx.guiHeight() - 4 - panelHeight : LIST_LEFT_Y;

        ctx.fill(x, y, x + panelWidth, y + panelHeight, LIST_BG);
        ctx.fill(x, y, x + 2, y + panelHeight, LIST_ACCENT);
        ctx.text(font, title, x + 7, y + 3, LIST_TEXT, false);

        int rowY = y + LIST_TITLE_H;
        for (Row row : rows) {
            ctx.fill(x + 6, rowY + 2, x + 10, rowY + 6, row.colour());
            ctx.text(font, row.name(), x + 13, rowY, LIST_TEXT, false);
            ctx.text(font, row.info(), x + 13 + font.width(row.name()) + 4, rowY, LIST_DIM, false);
            rowY += LINE_H;
        }
    }

    /** "64m  12 70 -9", the line nyx prints beside each name. */
    private static String info(Player player, double distance) {
        return Math.round(distance) + "m  "
                + (int) Math.floor(player.getX()) + " "
                + (int) Math.floor(player.getY()) + " "
                + (int) Math.floor(player.getZ());
    }

    /** How many players are on screen, like nyx's info string. */
    @Override
    public String suffix() {
        if (!Veil.inGame()) return null;
        int count = 0;
        for (Player player : Veil.mc.level.players()) {
            if (accepts(player)) count++;
        }
        return count > 0 ? String.valueOf(count) : null;
    }
}
