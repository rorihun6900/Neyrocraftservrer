package net.neyrocraft.hub;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import net.kyori.adventure.text.format.TextColor;
import net.kyori.adventure.text.format.TextDecoration;
import org.bukkit.Color;
import org.bukkit.Location;
import org.bukkit.NamespacedKey;
import org.bukkit.command.CommandSender;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.Display;
import org.bukkit.entity.Entity;
import org.bukkit.entity.Interaction;
import org.bukkit.entity.Player;
import org.bukkit.entity.TextDisplay;
import org.bukkit.persistence.PersistentDataType;

import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import java.util.function.Consumer;

import net.neyrocraft.hub.Dialogue.Choice;
import net.neyrocraft.hub.Dialogue.Kind;
import net.neyrocraft.hub.Dialogue.Line;
import net.neyrocraft.hub.Dialogue.Session;
import net.neyrocraft.hub.XaeProjection.Pose;

/** Xae'Lira occupies the former Ami stations and keeps their existing travel callbacks. */
final class Ami {
    enum Station { MAIN, ORION }

    private static final TextColor CYAN = TextColor.color(0x7FE8FF);
    private final NeyroHub plugin;
    private final Location defaultBase;
    private final Station station;
    private final Consumer<Player> transferTo;
    private final NamespacedKey met;
    private final NamespacedKey survey;
    private final File file;
    private final Map<UUID, Session> sessions = new HashMap<>();
    private Map<String, Line> voices = Map.of();
    private YamlConfiguration settings;
    private Dialogue.Speaker speaker;
    private Location base;
    private XaeProjection projection;
    private TextDisplay label;
    private Interaction hitbox;

    Ami(NeyroHub plugin, Location base, Station station, Consumer<Player> transfer) {
        this.plugin = plugin;
        this.defaultBase = base.clone();
        this.station = station;
        this.transferTo = transfer;
        this.met = new NamespacedKey(plugin, "xaelira_met");
        this.survey = new NamespacedKey(plugin, "xaelira_orion_survey");
        this.file = new File(plugin.getDataFolder(), "xaelira.yml");
    }

    private String stationPath() {
        return "stations." + station.name().toLowerCase(Locale.ROOT);
    }

    private YamlConfiguration settings() {
        if (!file.isFile()) plugin.saveResource("xaelira.yml", false);
        return YamlConfiguration.loadConfiguration(file);
    }

    void spawn() {
        remove();
        settings = settings();
        if (!settings.getBoolean(stationPath() + ".enabled", true)) return;
        String name = settings.getString("name", "Ксей’Лира");
        String title = settings.getString("subtitle", "Имперский научный офицер · тейлек");
        speaker = new Dialogue.Speaker(name, title, 0x7FE8FF, 0xD70C2260);
        try { voices = XaeLines.load(plugin); }
        catch (IOException ex) { plugin.getLogger().warning("Xae'Lira lines: " + ex.getMessage()); }

        String position = stationPath() + ".position";
        base = defaultBase.clone();
        if (settings.isConfigurationSection(position)) {
            double x = settings.getDouble(position + ".x", base.getX());
            double y = settings.getDouble(position + ".y", base.getY());
            double z = settings.getDouble(position + ".z", base.getZ());
            if (Double.isFinite(x) && Double.isFinite(y) && Double.isFinite(z)
                    && Math.abs(x) < 29_000_000 && Math.abs(z) < 29_000_000
                    && y >= base.getWorld().getMinHeight() && y < base.getWorld().getMaxHeight() - 3) {
                base = new Location(defaultBase.getWorld(), x, y, z);
            }
        }
        projection = new XaeProjection(plugin, base);
        projection.spawn();
        label = base.getWorld().spawn(base.clone().add(0, 2.65, 0), TextDisplay.class, d -> {
            plugin.prepare(d);
            d.setBillboard(Display.Billboard.CENTER);
            d.setBackgroundColor(Color.fromARGB(125, 6, 22, 55));
            d.text(Component.text("◈ " + name.toUpperCase(Locale.ROOT) + " ◈", CYAN).decorate(TextDecoration.BOLD)
                    .append(Component.newline()).append(Component.text(title, NamedTextColor.GRAY))
                    .append(Component.newline()).append(Component.text("[ПКМ — поговорить]", NamedTextColor.YELLOW)));
        });
        hitbox = base.getWorld().spawn(base, Interaction.class, e -> {
            e.addScoreboardTag(NeyroHub.TAG);
            e.setPersistent(false);
            e.setInteractionWidth(1.1f);
            e.setInteractionHeight(2.45f);
            e.setResponsive(true);
        });
        plugin.getLogger().info("Xae'Lira: 3D projection ready at " + station.name());
    }

    void remove() {
        for (Session session : List.copyOf(sessions.values())) session.end(session.player.isOnline());
        sessions.clear();
        if (projection != null) { projection.remove(); projection = null; }
        if (label != null) { label.remove(); label = null; }
        if (hitbox != null) { hitbox.remove(); hitbox = null; }
    }

    boolean isHitbox(Entity entity) { return hitbox != null && hitbox.equals(entity); }

    void animate() { if (projection != null) projection.tick(); }

    boolean configure(CommandSender sender, String action) {
        if (action.equals("reload")) {
            spawn();
            return true;
        }
        YamlConfiguration updated = settings();
        String path = stationPath();
        if (action.equals("remove")) {
            updated.set(path + ".enabled", false);
        } else if ((action.equals("move") || action.equals("create")) && sender instanceof Player player
                && player.getWorld().equals(defaultBase.getWorld())) {
            Location at = player.getLocation();
            updated.set(path + ".enabled", true);
            updated.set(path + ".position.x", at.getX());
            updated.set(path + ".position.y", at.getY());
            updated.set(path + ".position.z", at.getZ());
        } else return false;
        try { updated.save(file); }
        catch (IOException ex) {
            sender.sendMessage(Component.text("Не удалось записать xaelira.yml.", NamedTextColor.RED));
            return true;
        }
        spawn();
        sender.sendMessage(Component.text("Ксей’Лира · " + station.name() + " · " + action, CYAN));
        return true;
    }

    void interact(Player player) {
        Dialogue dialogue = plugin.dialogue();
        if (projection == null || dialogue == null || dialogue.talking(player)) return;
        dialogue.start(player, speaker, base.clone().add(0, 1.55, 0), session -> {
            UUID id = player.getUniqueId();
            sessions.put(id, session);
            session.onEnd(() -> {
                sessions.remove(id, session);
                if (plugin.voice() != null) plugin.voice().silence(player);
            });
            boolean repeat = player.getPersistentDataContainer().has(met, PersistentDataType.BYTE);
            player.getPersistentDataContainer().set(met, PersistentDataType.BYTE, (byte) 1);
            say(session, repeat ? "repeat" : "intro",
                    repeat ? "Ты снова здесь? Как приятно. Какие новости?"
                            : "Добро пожаловать. Я Ксей’Лира, научный офицер Империи. Чем могу помочь?",
                    Pose.GREETING, () -> menu(session, true));
        });
    }

    private Line line(String id, String fallback) {
        String custom = settings.getString("lines." + id + ".text");
        if (custom == null || custom.isBlank()) return voices.getOrDefault(id, Line.of(fallback));
        return customLine("lines." + id, custom);
    }

    private Line customLine(String path, String text) {
        Line recording = voices.get(settings.getString(path + ".audio", ""));
        return recording == null ? Line.of(text) : new Line(recording.sound(), recording.seconds(), text);
    }

    private void say(Session session, String id, String fallback, Pose pose, Runnable after) {
        speak(session, line(id, fallback), pose, after);
    }

    private void speak(Session s, Line line, Pose pose, Runnable after) {
        if (!s.active() || projection == null) return;
        boolean voiced = line.sound() != null && plugin.voice() != null && plugin.voice().hasVoice(s.player);
        long duration = voiced ? (long) Math.ceil(line.seconds() * 20) : Math.max(25, line.text().length());
        projection.react(pose, s.player, duration);
        s.say(line, after);
    }

    private void menu(Session s, boolean remind) {
        List<Choice> choices = new ArrayList<>();
        choices.add(Choice.of("Поговорить", Kind.NEUTRAL, x ->
                say(x, "talk", "Я изучаю разломы и сигналы с дальних орбит. Что тебя интересует?", Pose.HAPPY, () -> menu(x, false))));
        choices.add(Choice.of("Кто ты?", Kind.ASK, x ->
                say(x, "who", "Я Ксей’Лира, тейлек и научный офицер Империи. Сейчас перед тобой моя голограмма.", Pose.TALKING, () -> menu(x, false))));
        choices.add(Choice.of("Узнать о сервере", Kind.ASK, x ->
                say(x, "server", "На главной палубе есть порталы в выживание, Скайблок, анархию и лазертаг. Путеводитель открывается командой /hubinfo.",
                        Pose.TALKING, () -> menu(x, false))));
        choices.add(Choice.of("Получить задание", Kind.ACTION, this::survey));
        choices.add(Choice.of("Помощь новичку", Kind.LIGHT, x ->
                say(x, "help", "Открой /hubinfo и выбери маршрут. В выживании строй за отметкой пятьсот блоков от спавна.",
                        Pose.THINKING, () -> menu(x, false))));
        choices.add(Choice.of(station == Station.MAIN ? "На станцию «Орион»" : "В главный хаб", Kind.ACTION, x ->
                say(x, station == Station.MAIN ? "orion" : "hub", "Открываю маршрут. До встречи.", Pose.GOODBYE, () -> {
                    x.end(true);
                    transferTo.accept(x.player);
                })));
        if (settings.isConfigurationSection("topics") && !settings.getConfigurationSection("topics").getKeys(false).isEmpty())
            choices.add(Choice.of("Другие темы", Kind.ASK, this::topics));
        choices.add(Choice.of("До встречи", Kind.LEAVE, x ->
                say(x, "bye", "До следующей встречи, путешественник.", Pose.GOODBYE, () -> x.end(true))));
        s.choose(choices, remind ? () -> speak(s, Line.of("Что ты хотел спросить?"), Pose.THINKING, () -> menu(s, false)) : null);
    }

    private void topics(Session s) {
        List<Choice> choices = new ArrayList<>();
        var topics = settings.getConfigurationSection("topics");
        if (topics != null) for (String key : topics.getKeys(false)) {
            String path = "topics." + key;
            String title = settings.getString(path + ".label", "");
            String text = settings.getString(path + ".text", "");
            if (title.isBlank() || text.isBlank()) continue;
            choices.add(Choice.of(title, Kind.ASK, x -> speak(x, customLine(path, text), Pose.TALKING, () -> topics(x))));
            if (choices.size() == 8) break;
        }
        choices.add(Choice.of("Назад", Kind.LEAVE, x -> menu(x, false)));
        s.choose(choices);
    }

    private void survey(Session s) {
        var store = s.player.getPersistentDataContainer();
        byte progress = store.getOrDefault(survey, PersistentDataType.BYTE, (byte) 0);
        if (progress == 2) {
            s.say("Разведка станции уже отмечена в твоём журнале.", () -> menu(s, false));
            return;
        }
        Location destination = plugin.orionSpawn();
        if (destination == null) {
            s.say("«Орион» пока не отвечает. Вернись за заданием позже.", () -> menu(s, false));
            return;
        }
        if (station == Station.ORION && progress == 1) {
            store.set(survey, PersistentDataType.BYTE, (byte) 2);
            say(s, "complete", "Сигнал с «Ориона» принят. Разведка станции завершена.", Pose.HAPPY, () -> menu(s, false));
            return;
        }
        store.set(survey, PersistentDataType.BYTE, (byte) 1);
        say(s, "quest", "Замечательно. Я передаю тебе координаты. Не разочаруй меня, хорошо?", Pose.TALKING, () -> {
            s.player.sendMessage(Component.text("Разведка «Ориона» · /orion · прибытие: "
                    + destination.getBlockX() + ", " + destination.getBlockY() + ", " + destination.getBlockZ()
                    + ". Поговори с Ксей’Лирой на станции и снова выбери «Получить задание».", CYAN));
            menu(s, false);
        });
    }
}
