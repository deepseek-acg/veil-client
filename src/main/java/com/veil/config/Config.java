// lang: Java, file: Config.java, target: Fabric MC 26.3
// Saves and restores module state and settings so a restart does not reset the client.
package com.veil.config;

import com.google.gson.GsonBuilder;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.veil.Veil;
import com.veil.gui.ColorTheme;
import com.veil.module.Module;
import com.veil.module.ModuleManager;
import com.veil.module.modules.combat.AutoSafeAnchor;
import com.veil.setting.BoolSetting;
import com.veil.setting.FloatSetting;
import com.veil.setting.IntSetting;
import com.veil.setting.ModeSetting;
import com.veil.setting.Setting;
import net.fabricmc.loader.api.FabricLoader;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

/**
 * Module state on disk: which modules are enabled, their keybinds, and every setting value.
 *
 * <p>The file lives in Fabric's config directory as {@code veil-client.json}, keyed by module name
 * and then by setting name. Settings are written with their natural JSON type and restored by type,
 * so an unreadable or renamed entry is skipped rather than corrupting the rest of the file.
 *
 * <p>A setting that was renamed with the opposite meaning is read from its old name when the current
 * one is absent ({@link #applyLegacy}), so an existing file keeps behaving as it did and the next
 * save writes the new name only.
 *
 * <p>{@link #loading} suppresses saves while {@link #load()} is applying values: applying a
 * setting goes through the same setters the GUI uses, and those save.
 */
public final class Config {

    private static final String FILE_NAME = "veil-client.json";

    private static boolean loading;

    private Config() {}

    public static Path path() {
        return FabricLoader.getInstance().getConfigDir().resolve(FILE_NAME);
    }

    /** True while a load is in progress, so setters do not write the file back out. */
    public static boolean isLoading() {
        return loading;
    }

    public static void load() {
        Path file = path();
        if (!Files.exists(file)) return;

        loading = true;
        try {
            String text = Files.readString(file, StandardCharsets.UTF_8);
            JsonObject root = JsonParser.parseString(text).getAsJsonObject();
            applyTheme(root);
            JsonObject modules = root.getAsJsonObject("modules");
            if (modules == null) return;

            for (Module module : ModuleManager.getModules()) {
                JsonObject entry = modules.getAsJsonObject(module.getName());
                if (entry == null) continue;

                if (entry.has("keybind")) {
                    module.setKeybind(entry.get("keybind").getAsInt());
                }

                JsonObject settings = entry.getAsJsonObject("settings");
                if (settings != null) {
                    for (Setting<?> setting : module.getSettings()) {
                        if (settings.has(setting.getName())) {
                            apply(setting, settings.get(setting.getName()));
                        } else {
                            applyLegacy(module, settings, setting);
                        }
                    }
                }

                // Enable last: onEnable may read settings that were just restored.
                if (entry.has("enabled")) {
                    module.setEnabled(entry.get("enabled").getAsBoolean());
                }
            }
            Veil.LOGGER.info("Loaded configuration from {}", file);
        } catch (Exception error) {
            Veil.LOGGER.warn("Could not read {}; keeping defaults", file, error);
        } finally {
            loading = false;
        }
    }

    /**
     * Restores the GUI palette. An unknown name — a newer file, or one edited by hand — keeps the
     * default rather than failing the whole load.
     */
    private static void applyTheme(JsonObject root) {
        if (!root.has("theme")) return;
        try {
            ColorTheme.set(ColorTheme.Theme.valueOf(root.get("theme").getAsString()));
        } catch (IllegalArgumentException ignored) {
            // Unknown palette name: keep whatever is current.
        }
    }

    public static void save() {
        if (loading) return;

        JsonObject root = new JsonObject();
        JsonObject modules = new JsonObject();

        for (Module module : ModuleManager.getModules()) {
            JsonObject entry = new JsonObject();
            entry.addProperty("enabled", module.isEnabled());
            entry.addProperty("keybind", module.getKeybind());

            JsonObject settings = new JsonObject();
            for (Setting<?> setting : module.getSettings()) {
                if (setting instanceof BoolSetting bool) {
                    settings.addProperty(setting.getName(), bool.get());
                } else if (setting instanceof FloatSetting number) {
                    settings.addProperty(setting.getName(), number.get());
                } else if (setting instanceof IntSetting number) {
                    settings.addProperty(setting.getName(), number.get());
                } else if (setting instanceof ModeSetting mode) {
                    settings.addProperty(setting.getName(), mode.get());
                }
            }
            entry.add("settings", settings);
            modules.add(module.getName(), entry);
        }

        root.add("modules", modules);
        // A display preference rather than a module setting, so it sits beside the modules.
        root.addProperty("theme", ColorTheme.get().name());

        try {
            Path file = path();
            Files.createDirectories(file.getParent());
            Files.writeString(file,
                    new GsonBuilder().setPrettyPrinting().create().toJson(root),
                    StandardCharsets.UTF_8);
        } catch (IOException error) {
            Veil.LOGGER.warn("Could not write {}", path(), error);
        }
    }

    /**
     * Reads a retired setting name into the setting that replaced it.
     *
     * <p>AutoSafeAnchor's {@code Visual Rot} became {@code Silent} with the opposite meaning, so a
     * file written before the rename has no {@code Silent} key at all: the setting would keep its
     * default and someone who had asked for camera movement would silently get silent aim instead.
     * The inverse is taken only when the current name is absent, so a file with both uses the newer
     * key. AnchorAura inherits both settings and stores its own copy of them, so this keys off the
     * module type rather than the module name.
     */
    private static void applyLegacy(Module module, JsonObject settings, Setting<?> setting) {
        if (!(module instanceof AutoSafeAnchor) || !(setting instanceof BoolSetting bool)) return;
        if (!"Silent".equals(setting.getName()) || !settings.has("Visual Rot")) return;
        try {
            bool.set(!settings.get("Visual Rot").getAsBoolean());
        } catch (RuntimeException ignored) {
            // Wrong shape in a hand-edited file: keep the default.
        }
    }

    /** Restore one setting, ignoring a value of the wrong shape or outside the setting's range. */
    private static void apply(Setting<?> setting, JsonElement element) {
        try {
            if (setting instanceof BoolSetting bool) {
                bool.set(element.getAsBoolean());
            } else if (setting instanceof FloatSetting number) {
                number.set(element.getAsFloat());
            } else if (setting instanceof IntSetting number) {
                number.set(element.getAsInt());
            } else if (setting instanceof ModeSetting mode) {
                mode.set(element.getAsString());
            }
        } catch (RuntimeException ignored) {
            // A renamed or hand-edited entry is not worth failing the whole load over.
        }
    }
}
