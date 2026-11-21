package net.neyrocraft.spell;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import io.papermc.paper.chat.ChatRenderer;
import io.papermc.paper.event.player.AsyncChatEvent;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.TextComponent;
import net.kyori.adventure.text.event.ClickEvent;
import net.kyori.adventure.text.event.HoverEvent;
import net.kyori.adventure.text.format.NamedTextColor;
import net.kyori.adventure.text.format.TextColor;
import net.kyori.adventure.text.format.TextDecoration;
import net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer;
import org.bukkit.Bukkit;
import org.bukkit.command.Command;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.plugin.java.JavaPlugin;

import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URI;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * NeyroSpell — spell checking for chat (Russian + English, Yandex Speller).
 * Mistakes are underlined in red for everybody, hovering shows the right spelling.
 * The author can click a mistake (or the "✎ исправить" button) to get the corrected
 * message put into their chat line, ready to send.
 */
public final class NeyroSpell extends JavaPlugin implements Listener {
    private static final TextColor ORANGE = TextColor.color(0xFF8C00);

    private record Mistake(int pos, int len, String word, List<String> suggestions) { }

    private String service;
    private int timeoutMs;
    private Set<String> ignored;
    private final Set<UUID> disabledFor = Collections.synchronizedSet(new HashSet<>());
    private final Map<String, List<Mistake>> cache = Collections.synchronizedMap(new LinkedHashMap<>(256, 0.75f, true) {
        @Override protected boolean removeEldestEntry(Map.Entry<String, List<Mistake>> e) { return size() > 500; }
    });

    @Override
    public void onEnable() {
        saveDefaultConfig();
        service = getConfig().getString("service", "https://speller.yandex.net/services/spellservice.json/checkText");
        timeoutMs = getConfig().getInt("timeout-ms", 1500);
        ignored = new HashSet<>();
        for (String w : getConfig().getStringList("ignore-words")) ignored.add(w.toLowerCase(Locale.ROOT));
        getServer().getPluginManager().registerEvents(this, this);
        getLogger().info("NeyroSpell ready (chat spell check, " + service.replaceAll("https?://([^/]+).*", "$1") + ")");
    }

    // ------------------------------------------------------------ chat

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onChat(AsyncChatEvent e) {
        if (!getConfig().getBoolean("enabled", true) || Bukkit.isPrimaryThread()) return;   // never block the main thread
        if (disabledFor.contains(e.getPlayer().getUniqueId())) return;
        String text = PlainTextComponentSerializer.plainText().serialize(e.message());
        if (text.isBlank() || text.length() > 256 || text.startsWith("/")) return;
        List<Mistake> found = check(text);
        if (found.isEmpty()) return;
        // typed with the wrong keyboard layout? ("ghbdtn" -> "привет", "руддщ" -> "hello")
        String switched = switchLayout(text);
        if (switched != null) {
            List<Mistake> foundSwitched = check(switched);
            if (foundSwitched.size() * 2 < found.size() || (foundSwitched.isEmpty() && found.size() >= 1)) {
                String fixed = foundSwitched.isEmpty() ? switched : corrected(switched, foundSwitched);
                ChatRenderer base = e.renderer();
                UUID author = e.getPlayer().getUniqueId();
                e.renderer((source, displayName, message, viewer) -> {
                    boolean own = viewer instanceof Player vp && vp.getUniqueId().equals(author);
                    Component rendered = base.render(source, displayName, message, viewer)
                            .append(Component.text("  (" + fixed + ")", NamedTextColor.GRAY).decorate(TextDecoration.ITALIC)
                                    .hoverEvent(HoverEvent.showText(Component.text("Похоже, набрано не в той раскладке", NamedTextColor.YELLOW))));
                    if (!own) return rendered;
                    return rendered.append(Component.text("  ⌨ раскладка", ORANGE)
                            .hoverEvent(HoverEvent.showText(Component.text()
                                    .append(Component.text("Нажми — текст в правильной раскладке появится в строке чата:\n", NamedTextColor.GRAY))
                                    .append(Component.text(fixed, NamedTextColor.WHITE)).build()))
                            .clickEvent(ClickEvent.suggestCommand(fixed)));
                });
                return;
            }
        }
        String corrected = corrected(text, found);
        ChatRenderer base = e.renderer();
        UUID author = e.getPlayer().getUniqueId();
        e.renderer((source, displayName, message, viewer) -> {
            boolean own = viewer instanceof Player vp && vp.getUniqueId().equals(author);
            Component rendered = base.render(source, displayName, highlight(text, found, own ? corrected : null), viewer);
            if (!own) return rendered;
            return rendered.append(Component.text("  ✎ исправить", ORANGE)
                    .hoverEvent(HoverEvent.showText(Component.text()
                            .append(Component.text("Нажми — исправленный текст появится в строке чата:\n", NamedTextColor.GRAY))
                            .append(Component.text(corrected, NamedTextColor.WHITE)).build()))
                    .clickEvent(ClickEvent.suggestCommand(corrected)));
        });
    }

    private Component highlight(String text, List<Mistake> found, String correctedForAuthor) {
        TextComponent.Builder b = Component.text();
        int at = 0;
        for (Mistake m : found) {
            if (m.pos() < at || m.pos() + m.len() > text.length()) continue;
            if (m.pos() > at) b.append(Component.text(text.substring(at, m.pos())));
            String hover = m.suggestions().isEmpty() ? "Похоже на ошибку" : "Правильно: " + String.join(", ", m.suggestions());
            Component word = Component.text(text.substring(m.pos(), m.pos() + m.len()), NamedTextColor.RED)
                    .decorate(TextDecoration.UNDERLINED);
            if (correctedForAuthor != null) {
                word = word.hoverEvent(HoverEvent.showText(Component.text(hover + "\nНажми, чтобы исправить сообщение", NamedTextColor.YELLOW)))
                        .clickEvent(ClickEvent.suggestCommand(correctedForAuthor));
            } else {
                word = word.hoverEvent(HoverEvent.showText(Component.text(hover, NamedTextColor.YELLOW)));
            }
            b.append(word);
            at = m.pos() + m.len();
        }
        if (at < text.length()) b.append(Component.text(text.substring(at)));
        return b.build();
    }

    private static String corrected(String text, List<Mistake> found) {
        StringBuilder sb = new StringBuilder();
        int at = 0;
        for (Mistake m : found) {
            if (m.pos() < at || m.pos() + m.len() > text.length()) continue;
            sb.append(text, at, m.pos());
            sb.append(m.suggestions().isEmpty() ? m.word() : m.suggestions().get(0));
            at = m.pos() + m.len();
        }
        sb.append(text.substring(at));
        return sb.toString();
    }

    // ------------------------------------------------------------ speller

    private List<Mistake> check(String text) {
        List<Mistake> cached = cache.get(text);
        if (cached != null) return cached;
        List<Mistake> out = new ArrayList<>();
        try {
            HttpURLConnection c = (HttpURLConnection) URI.create(service).toURL().openConnection();
            c.setConnectTimeout(timeoutMs);
            c.setReadTimeout(timeoutMs);
            c.setRequestMethod("POST");
            c.setDoOutput(true);
            c.setRequestProperty("Content-Type", "application/x-www-form-urlencoded; charset=UTF-8");
            // 2 ignore digits, 4 ignore URLs, 512 ignore capitalization
            String body = "lang=ru,en&options=518&text=" + URLEncoder.encode(text, StandardCharsets.UTF_8);
            try (OutputStream os = c.getOutputStream()) { os.write(body.getBytes(StandardCharsets.UTF_8)); }
            if (c.getResponseCode() != 200) return out;
            String json;
            try (InputStream in = c.getInputStream()) { json = new String(in.readAllBytes(), StandardCharsets.UTF_8); }
            Set<String> names = new HashSet<>();
            for (Player p : Bukkit.getOnlinePlayers()) names.add(p.getName().toLowerCase(Locale.ROOT));
            JsonArray arr = JsonParser.parseString(json).getAsJsonArray();
            for (JsonElement el : arr) {
                JsonObject o = el.getAsJsonObject();
                String word = o.get("word").getAsString();
                String lw = word.toLowerCase(Locale.ROOT);
                if (word.length() < 2 || ignored.contains(lw) || names.contains(lw)) continue;
                List<String> sug = new ArrayList<>();
                for (JsonElement s : o.getAsJsonArray("s")) if (sug.size() < 3 && !s.getAsString().equalsIgnoreCase(word)) sug.add(s.getAsString());
                out.add(new Mistake(o.get("pos").getAsInt(), o.get("len").getAsInt(), word, sug));
            }
            out.sort((a, b) -> Integer.compare(a.pos(), b.pos()));
        } catch (Exception ex) {
            return out;                       // speller unreachable: the message just goes through unchanged
        }
        cache.put(text, out);
        return out;
    }

    // ------------------------------------------------------------ keyboard layout (QWERTY <-> ЙЦУКЕН)

    private static final String EN = "qwertyuiop[]asdfghjkl;'zxcvbnm,.`QWERTYUIOP{}ASDFGHJKL:\"ZXCVBNM<>~";
    private static final String RU = "йцукенгшщзхъфывапролджэячсмитьбюёЙЦУКЕНГШЩЗХЪФЫВАПРОЛДЖЭЯЧСМИТЬБЮЁ";

    /** The same keys pressed in the other layout, or null if the text mixes alphabets / has no letters. */
    static String switchLayout(String text) {
        int lat = 0, cyr = 0;
        for (char c : text.toCharArray()) {
            if ((c >= 'a' && c <= 'z') || (c >= 'A' && c <= 'Z')) lat++;
            else if (Character.UnicodeBlock.of(c) == Character.UnicodeBlock.CYRILLIC) cyr++;
        }
        if (lat + cyr < 3 || (lat > 0 && cyr > 0)) return null;
        boolean toRu = lat > 0;
        String from = toRu ? EN : RU, to = toRu ? RU : EN;
        StringBuilder out = new StringBuilder(text.length());
        for (int i = 0; i < text.length(); i++) {
            char c = text.charAt(i);
            int idx = from.indexOf(c);
            if (idx < 0) { out.append(c); continue; }
            if (!Character.isLetter(c)) {
                // punctuation keys only count as letters inside a word ("e;t" -> "уже"), not at the end of a sentence
                boolean before = i > 0 && Character.isLetter(text.charAt(i - 1));
                boolean after = i + 1 < text.length() && Character.isLetter(text.charAt(i + 1));
                if (!(before && after)) { out.append(c); continue; }
            }
            out.append(to.charAt(idx));
        }
        return out.toString();
    }

    // ------------------------------------------------------------ /spell

    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        if (!(sender instanceof Player p)) { sender.sendMessage("Только для игроков"); return true; }
        boolean off = !disabledFor.remove(p.getUniqueId());
        if (off) disabledFor.add(p.getUniqueId());
        p.sendMessage(Component.text("NeyroSpell: проверка твоих сообщений ", NamedTextColor.GRAY)
                .append(Component.text(off ? "выключена" : "включена", off ? NamedTextColor.RED : NamedTextColor.GREEN)));
        return true;
    }
}
