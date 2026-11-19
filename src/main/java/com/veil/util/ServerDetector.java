// lang: Java, file: ServerDetector.java, target: Fabric MC 26.3
package com.veil.util;

import com.veil.Veil;
import net.minecraft.client.multiplayer.ServerData;

public final class ServerDetector {

    private ServerDetector() {}

    public static ServerProfile current() {
        if (Veil.mc == null) return ServerProfile.DEFAULT;
        ServerData server = Veil.mc.getCurrentServer();
        if (server == null) return ServerProfile.DEFAULT; // singleplayer or realms

        String host = server.ip.toLowerCase();
        // Strip port if present
        int colon = host.lastIndexOf(':');
        if (colon != -1) host = host.substring(0, colon);

        if (isHost(host, "hypixel.net")) return ServerProfile.HYPIXEL;
        if (isHost(host, "mcpvp.com") || isHost(host, "mcpvp.club")) return ServerProfile.GRIM;
        if (isHost(host, "minemen.club") || isHost(host, "minemen.net")
                || isHost(host, "minemenclub.net")) return ServerProfile.INTAVE;
        return ServerProfile.DEFAULT;
    }

    /**
     * Whether the host is the domain itself or a subdomain of it.
     *
     * <p>A bare {@code endsWith} also matches {@code notreallyhypixel.net}, which would put a player on
     * the Hypixel ceilings while on somebody else's server.
     */
    private static boolean isHost(String host, String domain) {
        return host.equals(domain) || host.endsWith("." + domain);
    }

    public static ServerProfile resolve(String modeSetting) {
        return switch (modeSetting) {
            case "Hypixel" -> ServerProfile.HYPIXEL;
            case "Grim"    -> ServerProfile.GRIM;
            case "Intave"  -> ServerProfile.INTAVE;
            case "Default" -> ServerProfile.DEFAULT;
            default        -> current(); // "Auto"
        };
    }
}
