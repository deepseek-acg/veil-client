// lang: Java, file: ScreensharePanic.java, target: Fabric MC 26.3
// Panic module: disables all modules, reduces heap exposure, and scrubs log artifacts.
// Enable this module (via keybind) when accepting a screenshare.
package com.veil.module.modules.misc;

import com.veil.Veil;
import com.veil.module.Category;
import com.veil.module.Module;
import com.veil.module.ModuleManager;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.stream.Collectors;

public final class ScreensharePanic extends Module {

    public ScreensharePanic() {
        super("SSPanic", "Disables all modules and reduces screenshare exposure", Category.MISC);
    }

    @Override
    public void onEnable() {
        // 1. Disable all other modules; setEnabled(false) unsubscribes from EventBus + calls onDisable.
        for (Module m : ModuleManager.getModules()) {
            if (m == this) continue;
            if (m.isEnabled()) m.setEnabled(false);
        }

        // 2. Clear setting lists to remove string references from heap.
        for (Module m : ModuleManager.getModules()) {
            if (m == this) continue;
            m.getSettings().clear();
        }

        // 3. Request GC to flush detached string references before a heap scanner runs.
        System.gc();

        // 4. Scrub the game log.
        scrubLog();

        // 5. Notify in-game chat with an innocuous message.
        if (Veil.mc != null && Veil.mc.player != null) {
            Veil.mc.player.sendSystemMessage(
                    net.minecraft.network.chat.Component.literal("§7[Perf] GC cycle complete"));
        }

        // Immediately disable self so it shows as off in the module list.
        setEnabled(false);
    }

    private static void scrubLog() {
        if (Veil.mc == null) return;
        Path logFile = Veil.mc.gameDirectory.toPath().resolve("logs/latest.log");
        if (!Files.exists(logFile)) return;
        try {
            List<String> lines = Files.readAllLines(logFile, StandardCharsets.UTF_8);
            List<String> clean = lines.stream()
                    .filter(line -> !containsVeilSignature(line))
                    .collect(Collectors.toList());
            Files.write(logFile, clean, StandardCharsets.UTF_8);
        } catch (IOException ignored) {}
    }

    private static boolean containsVeilSignature(String line) {
        String lower = line.toLowerCase();
        return lower.contains("com.veil") || lower.contains("veilclient") || lower.contains("[veil]");
    }
}
