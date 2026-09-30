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


package baritone.gui.tasks;

import com.google.gson.GsonBuilder;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import java.util.Map;

/**
 * JSON form of a task list:
 * <pre>{"name":"iron-run","loop":true,"steps":[{"type":"MINE","params":{"blocks":"iron_ore","count":"16"}}]}</pre>
 */
public final class TaskJson {

    private TaskJson() {}

    public static String write(TaskList list) {
        JsonObject o = new JsonObject();
        o.addProperty("version", 1);
        o.addProperty("name", list.name());
        o.addProperty("loop", list.loop());
        JsonArray steps = new JsonArray();
        for (TaskStep s : list.steps()) {
            JsonObject so = new JsonObject();
            so.addProperty("type", s.type().name());
            JsonObject p = new JsonObject();
            for (Map.Entry<String, String> e : s.params().entrySet()) {
                p.addProperty(e.getKey(), e.getValue());
            }
            so.add("params", p);
            steps.add(so);
        }
        o.add("steps", steps);
        return new GsonBuilder().setPrettyPrinting().create().toJson(o);
    }

    /** @throws IllegalArgumentException for malformed files or unknown step types */
    public static TaskList read(String json, String fallbackName) {
        JsonElement root;
        try {
            root = new JsonParser().parse(json);
        } catch (RuntimeException e) {
            throw new IllegalArgumentException("not valid JSON: " + e.getMessage());
        }
        if (!root.isJsonObject()) {
            throw new IllegalArgumentException("expected a JSON object");
        }
        JsonObject o = root.getAsJsonObject();
        String name = o.has("name") && o.get("name").isJsonPrimitive() ? o.get("name").getAsString() : fallbackName;
        TaskList list = new TaskList(name == null || name.trim().isEmpty() ? fallbackName : name.trim());
        list.setLoop(o.has("loop") && o.get("loop").isJsonPrimitive() && o.get("loop").getAsBoolean());
        if (o.has("steps") && o.get("steps").isJsonArray()) {
            for (JsonElement e : o.getAsJsonArray("steps")) {
                if (!e.isJsonObject()) {
                    continue;
                }
                JsonObject so = e.getAsJsonObject();
                StepType t = so.has("type") ? StepType.parse(so.get("type").getAsString()) : null;
                if (t == null) {
                    throw new IllegalArgumentException("unknown step type " + so.get("type"));
                }
                TaskStep s = new TaskStep(t);
                if (so.has("params") && so.get("params").isJsonObject()) {
                    for (Map.Entry<String, JsonElement> p : so.getAsJsonObject("params").entrySet()) {
                        if (p.getValue().isJsonPrimitive()) {
                            s.set(p.getKey(), p.getValue().getAsString());
                        }
                    }
                }
                list.add(s);
            }
        }
        return list;
    }
}
