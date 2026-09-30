/*
 * This file is part of Baritone.
 *
 * Baritone is free software: you can redistribute it and/or modify
 * it under the terms of the GNU Lesser General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * Baritone is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU Lesser General Public License for more details.
 *
 * You should have received a copy of the GNU Lesser General Public License
 * along with Baritone.  If not, see <https://www.gnu.org/licenses/>.
 */


package baritone.gui;

import baritone.Baritone;
import baritone.api.Settings;
import baritone.api.utils.SettingsUtil;
import baritone.gui.model.SettingsFileMerger;
import net.minecraft.client.Minecraft;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Persists only the settings the user changed through the GUI. The settings file is merged line by line
 * ({@link SettingsFileMerger}), so values another mod forced at runtime (TenorClef sets ~35 on startup) are not
 * written just because the GUI saved. {@code #set} keeps Baritone's own save-everything-modified behaviour.
 */
public final class GuiSettingsStore {

    private static final long DEBOUNCE_MS = 500;

    private final Set<Settings.Setting<?>> dirty = new LinkedHashSet<>();
    private long saveAt = -1;
    private String lastError;
    private long lastSaved;

    public synchronized void touch(Settings.Setting<?> setting) {
        dirty.add(setting);
        saveAt = System.currentTimeMillis() + DEBOUNCE_MS;
    }

    /** Called every client tick; writes once the debounce has elapsed. */
    public void tick() {
        long at;
        synchronized (this) {
            at = saveAt;
        }
        if (at > 0 && System.currentTimeMillis() >= at) {
            saveNow();
        }
    }

    public synchronized boolean hasPending() {
        return !dirty.isEmpty();
    }

    public synchronized String lastError() {
        return lastError;
    }

    public synchronized long lastSaved() {
        return lastSaved;
    }

    public synchronized void saveNow() {
        saveAt = -1;
        if (dirty.isEmpty()) {
            return;
        }
        Settings settings = Baritone.settings();
        Set<Settings.Setting> modified = new HashSet<>(SettingsUtil.modifiedSettings(settings));
        Map<String, String> updates = new LinkedHashMap<>();
        for (Settings.Setting<?> s : dirty) {
            if (s.isJavaOnly()) {
                continue;
            }
            updates.put(s.getName().toLowerCase(), modified.contains(s) ? SettingsUtil.settingToString(s) : null);
        }
        try {
            Path file = settingsFile();
            List<String> lines = Files.exists(file) ? Files.readAllLines(file, StandardCharsets.UTF_8) : Collections.<String>emptyList();
            List<String> merged = SettingsFileMerger.merge(new ArrayList<>(lines), updates);
            Files.createDirectories(file.getParent());
            Files.write(file, merged, StandardCharsets.UTF_8);
            dirty.clear();
            lastError = null;
            lastSaved = System.currentTimeMillis();
        } catch (Exception e) {
            lastError = e.getClass().getSimpleName() + ": " + e.getMessage();
            e.printStackTrace();
        }
    }

    private static Path settingsFile() {
        return Minecraft.getInstance().gameDirectory.toPath().resolve("baritone").resolve(SettingsUtil.SETTINGS_DEFAULT_NAME);
    }
}
