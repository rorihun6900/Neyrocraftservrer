package net.neyrocraft.hub;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.Map;

final class XaeLines {
    private XaeLines() {}

    static Map<String, Dialogue.Line> load(NeyroHub plugin) throws IOException {
        Map<String, Dialogue.Line> lines = new LinkedHashMap<>();
        try (var stream = plugin.getResource("voice/xae/lines.tsv")) {
            if (stream == null) return lines;
            try (var reader = new BufferedReader(new InputStreamReader(stream, StandardCharsets.UTF_8))) {
                for (String row; (row = reader.readLine()) != null; ) {
                    String[] fields = row.replace("\ufeff", "").split("\t", 3);
                    if (fields.length != 3 || !fields[0].matches("[a-z_]{1,24}") || fields[2].isBlank()) continue;
                    double seconds;
                    try { seconds = Double.parseDouble(fields[1]); }
                    catch (NumberFormatException ignored) { continue; }
                    if (!(seconds > 0 && seconds <= 120)) continue;
                    try (var sound = plugin.getResource("voice/xae/" + fields[0] + ".ogg")) {
                        if (sound == null) continue;
                    }
                    lines.put(fields[0], new Dialogue.Line("xaelira:" + fields[0], seconds, fields[2]));
                }
            }
        }
        return lines;
    }
}
