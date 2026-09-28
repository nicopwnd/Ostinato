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

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Locale;

/** Named task lists stored as {@code <dir>/<name>.json} (the game uses {@code baritone/tasks}). */
public final class TaskFiles {

    private final Path dir;

    public TaskFiles(Path dir) {
        this.dir = dir;
    }

    public Path dir() {
        return dir;
    }

    /** Keep file names portable: letters, digits, '-', '_'; everything else becomes '-'. */
    public static String sanitize(String name) {
        String s = name == null ? "" : name.trim().replaceAll("[^A-Za-z0-9_\\-]+", "-").replaceAll("^-+|-+$", "");
        if (s.length() > 48) {
            s = s.substring(0, 48);
        }
        return s.isEmpty() ? "tasks" : s;
    }

    public Path fileFor(String name) {
        return dir.resolve(sanitize(name) + ".json");
    }

    public List<String> list() {
        List<String> out = new ArrayList<>();
        if (!Files.isDirectory(dir)) {
            return out;
        }
        try (DirectoryStream<Path> ds = Files.newDirectoryStream(dir, "*.json")) {
            for (Path p : ds) {
                String f = p.getFileName().toString();
                out.add(f.substring(0, f.length() - 5));
            }
        } catch (IOException ignored) {
            // an unreadable folder just lists nothing
        }
        Collections.sort(out, String.CASE_INSENSITIVE_ORDER);
        return out;
    }

    public boolean exists(String name) {
        return Files.exists(fileFor(name));
    }

    public TaskList load(String name) throws IOException {
        Path f = fileFor(name);
        if (!Files.exists(f)) {
            // allow case-insensitive names from chat
            for (String n : list()) {
                if (n.toLowerCase(Locale.ROOT).equals(sanitize(name).toLowerCase(Locale.ROOT))) {
                    f = fileFor(n);
                    break;
                }
            }
        }
        String json = new String(Files.readAllBytes(f), StandardCharsets.UTF_8);
        TaskList l = TaskJson.read(json, sanitize(name));
        l.setName(sanitize(l.name()));
        return l;
    }

    public Path save(TaskList list) throws IOException {
        list.setName(sanitize(list.name()));
        Files.createDirectories(dir);
        Path f = fileFor(list.name());
        Path tmp = dir.resolve(f.getFileName() + ".tmp");
        Files.write(tmp, TaskJson.write(list).getBytes(StandardCharsets.UTF_8));
        try {
            Files.move(tmp, f, java.nio.file.StandardCopyOption.REPLACE_EXISTING, java.nio.file.StandardCopyOption.ATOMIC_MOVE);
        } catch (IOException e) {
            Files.move(tmp, f, java.nio.file.StandardCopyOption.REPLACE_EXISTING);
        }
        return f;
    }
}
