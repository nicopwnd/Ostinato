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


package baritone.gui.model;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.Reader;
import java.nio.charset.StandardCharsets;
import java.util.Collections;
import java.util.HashMap;
import java.util.Locale;
import java.util.Map;

/**
 * Setting descriptions (from the Javadoc in {@code Settings.java}), shipped as
 * {@code assets/baritone/ostinato/setting-descriptions.json}. Regenerate with
 * {@code python scripts/gen_setting_descriptions.py}.
 */
public final class SettingDescriptions {

    public static final String RESOURCE = "/assets/baritone/ostinato/setting-descriptions.json";
    private static Map<String, String> cache;

    private SettingDescriptions() {}

    /** @return lower-case setting name -> description (empty map if the resource is missing) */
    public static synchronized Map<String, String> get() {
        if (cache == null) {
            Map<String, String> m = Collections.emptyMap();
            try (InputStream in = SettingDescriptions.class.getResourceAsStream(RESOURCE)) {
                if (in != null) {
                    m = parse(new InputStreamReader(in, StandardCharsets.UTF_8));
                }
            } catch (Exception ignored) {
            }
            cache = m;
        }
        return cache;
    }

    public static Map<String, String> parse(Reader reader) {
        JsonObject obj = new JsonParser().parse(reader).getAsJsonObject();
        Map<String, String> out = new HashMap<>();
        for (Map.Entry<String, JsonElement> e : obj.entrySet()) {
            out.put(e.getKey().toLowerCase(Locale.ROOT), e.getValue().getAsString());
        }
        return Collections.unmodifiableMap(out);
    }
}
