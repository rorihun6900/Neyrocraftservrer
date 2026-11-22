package net.neyrocraft.hub;

import io.papermc.paper.scoreboard.numbers.NumberFormat;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import net.kyori.adventure.text.format.TextColor;
import net.kyori.adventure.text.format.TextDecoration;
import net.kyori.adventure.text.serializer.gson.GsonComponentSerializer;
import org.bukkit.Bukkit;
import org.bukkit.NamespacedKey;
import org.bukkit.Statistic;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.Player;
import org.bukkit.GameMode;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.entity.EntityDeathEvent;
import org.bukkit.event.entity.PlayerDeathEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.persistence.PersistentDataType;
import org.bukkit.scoreboard.Criteria;
import org.bukkit.scoreboard.DisplaySlot;
import org.bukkit.scoreboard.Objective;
import org.bukkit.scoreboard.Scoreboard;
import org.bukkit.scoreboard.Team;

import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Info panel on the right of the screen: server name in the brand colours, the current mode,
 * the player, their StarPass title and level, statistics of the mode they are in (wins, kills, time),
 * and at the bottom ping and server TPS in blue digits.
 * Each player has their own scoreboard; the nameplate teams of NeyroRanks are mirrored into it.
 * LaserTag and the sword arena keep their own match scoreboards.
 */
final class HudBoard implements Listener {
    private static final int ORANGE = NeyroHub.ORANGE, BLUE = 0x4FC3FF, CYAN = 0x7FE8FF, GOLD = 0xFFD54A, DIM = 0x8A8FA8;
    private static final String ANARCHY_TEAM = "neyro_anarchy";

    enum Mode {
        MAIN("Главная палуба", "hub"), ORION("Станция «Орион»", "hub"), SURVIVAL("Выживание", "survival"),
        SKYBLOCK("SkyBlock", "skyblock"), RETRO("Minecraft 1.0", "retro"), RACE("Гонки · Терракс", "race"), CINEMA("Космокино", "hub"),
        ANARCHY("☠ АНАРХИЯ ☠", "anarchy"), HUB3("Храм · Hub 3", "hub3"), OTHER("Мир", "other");
        final String title, key;
        Mode(String title, String key) { this.title = title; this.key = key; }
    }

    private final NeyroHub plugin;
    private final File dir;
    private final Map<UUID, YamlConfiguration> stats = new HashMap<>();
    private final Map<UUID, Scoreboard> boards = new HashMap<>();
    private static final Map<UUID, Long> noticeUntil = new java.util.concurrent.ConcurrentHashMap<>();
    private final NamespacedKey titleKey = new NamespacedKey("starpass", "title");
    private final NamespacedKey rankKey = new NamespacedKey("neyroranks", "prefix");
    private long tick;

    HudBoard(NeyroHub plugin) {
        this.plugin = plugin;
        this.dir = new File(plugin.getDataFolder(), "stats");
        dir.mkdirs();
    }

    void start() {
        Bukkit.getScheduler().runTaskTimer(plugin, this::update, 40L, 20L);
        Bukkit.getScheduler().runTaskTimer(plugin, this::saveAll, 6000L, 6000L);
    }

    void shutdown() {
        noticeUntil.clear();
        saveAll();
        for (Player p : Bukkit.getOnlinePlayers())
            if (boards.containsKey(p.getUniqueId()) && p.getScoreboard() == boards.get(p.getUniqueId()))
                p.setScoreboard(Bukkit.getScoreboardManager().getMainScoreboard());
    }

    // ------------------------------------------------------------ statistics

    private YamlConfiguration st(UUID id) {
        return stats.computeIfAbsent(id, u -> YamlConfiguration.loadConfiguration(new File(dir, u + ".yml")));
    }

    void add(Player p, String key, long n) {
        if (p == null || n == 0) return;
        YamlConfiguration y = st(p.getUniqueId());
        y.set(key, y.getLong(key, 0) + n);
    }

    long get(Player p, String key) {
        return st(p.getUniqueId()).getLong(key, 0);
    }

    private void save(UUID id) {
        YamlConfiguration y = stats.get(id);
        if (y == null) return;
        try { y.save(new File(dir, id + ".yml")); } catch (IOException e) { plugin.getLogger().warning("Stats: " + e); }
    }

    private void saveAll() {
        for (UUID id : new ArrayList<>(stats.keySet())) save(id);
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onQuit(PlayerQuitEvent e) {
        UUID id = e.getPlayer().getUniqueId();
        save(id);
        stats.remove(id);
        boards.remove(id);
        noticeUntil.remove(id);
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onKill(EntityDeathEvent e) {
        Player k = e.getEntity().getKiller();
        if (k == null || e.getEntity() == k || k.getGameMode() == GameMode.CREATIVE) return;
        Mode m = mode(k);
        if ((m == Mode.ANARCHY && k.getGameMode() == GameMode.SURVIVAL)
                || m == Mode.SURVIVAL || m == Mode.SKYBLOCK || m == Mode.RETRO)
            add(k, m.key + (e.getEntity() instanceof Player ? ".pvp" : ".mobs"), 1);
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onDeath(PlayerDeathEvent e) {
        if (e.getEntity().getGameMode() == GameMode.CREATIVE
                && !e.getEntity().getWorld().getName().equals(DarkAnarchy.WORLD_NAME)) return;
        Mode m = mode(e.getEntity());
        if (m == Mode.ANARCHY || m == Mode.SURVIVAL || m == Mode.SKYBLOCK || m == Mode.RETRO)
            add(e.getEntity(), m.key + ".deaths", 1);
    }

    // ------------------------------------------------------------ mode

    /** Mode shown on the panel, or null when the panel is off (login capsule, a match with its own board, cutscene). */
    Mode mode(Player p) {
        if (!plugin.authed(p)) return null;
        if (plugin.laserInGame(p) || plugin.sabersInGame(p) || plugin.starfightInGame(p)) return null;
        if (plugin.race() != null && plugin.race().in(p)) return Mode.RACE;
        if (p.getWorld().getName().equals(DarkAnarchy.WORLD_NAME)) return Mode.ANARCHY;
        if (plugin.inRetro(p)) return Mode.RETRO;
        if (plugin.inSkyblock(p)) return Mode.SKYBLOCK;
        if (plugin.inSurvival(p)) return Mode.SURVIVAL;
        if (p.getWorld().getName().equals("neyro_hub3")) return Mode.HUB3;
        if (plugin.inHub(p)) return plugin.inCinema(p) ? Mode.CINEMA : plugin.inOrion(p) ? Mode.ORION : Mode.MAIN;
        return p.getGameMode() == GameMode.CREATIVE ? Mode.OTHER : null;
    }

    // ------------------------------------------------------------ panel

    private void update() {
        tick++;
        for (Player p : Bukkit.getOnlinePlayers()) {
            try {
                Mode m = mode(p);
                if (m != null) {
                    if (p.getGameMode() == GameMode.CREATIVE) add(p, "time.creative", 1);
                    else if (m != Mode.ANARCHY || p.getGameMode() == GameMode.SURVIVAL) add(p, "time." + m.key, 1);
                }
                Scoreboard mine = boards.get(p.getUniqueId());
                Scoreboard cur = p.getScoreboard();
                Scoreboard main = Bukkit.getScoreboardManager().getMainScoreboard();
                if (m == null) {
                    if (mine != null && cur == mine) p.setScoreboard(main);
                    continue;
                }
                if (cur != main && cur != mine) continue;      // a mini-game's own board
                if (mine == null) {
                    mine = Bukkit.getScoreboardManager().getNewScoreboard();
                    boards.put(p.getUniqueId(), mine);
                }
                mirrorTeams(main, mine, m == Mode.ANARCHY
                        ? p.getWorld().getPlayers().stream().map(Player::getName).collect(java.util.stream.Collectors.toSet())
                        : java.util.Set.of());
                render(p, mine, m);
                if (cur != mine) p.setScoreboard(mine);
                boolean blink = tick % 8 == Math.floorMod(p.getUniqueId().hashCode(), 8);
                if (renderPortrait(p, m, blink) && blink) {
                    Bukkit.getScheduler().runTaskLater(plugin, () -> {
                        if (p.isOnline() && mode(p) == m) renderPortrait(p, m, false);
                    }, 3L);
                }
            } catch (RuntimeException ex) {
                plugin.getLogger().warning("HUD for " + p.getName() + ": " + ex);
            }
        }
    }

    /** Gameplay and dialogue hints temporarily take priority over the profile strip. */
    static void notice(Player player, Component message) {
        noticeUntil.put(player.getUniqueId(), System.nanoTime() + 3_000_000_000L);
        player.sendActionBar(message);
    }

    private static Component portrait(Player player) {
        var profile = player.getPlayerProfile();
        var head = profile.hasTextures()
                ? net.kyori.adventure.text.object.ObjectContents.playerHead(profile)
                : net.kyori.adventure.text.object.ObjectContents.playerHead(player.getName());
        return Component.object(head);
    }

    private boolean renderPortrait(Player player, Mode mode, boolean blink) {
        if (mode == Mode.RETRO || mode == Mode.RACE || mode == Mode.CINEMA || mode == Mode.OTHER) return false;
        if (plugin.voice() == null || !plugin.voice().hasPortrait(player)) return false;
        if (plugin.inCutscene(player) || plugin.dialogue().talking(player)
                || player.isDead() || player.getOpenInventory().getTopInventory().getType() != org.bukkit.event.inventory.InventoryType.CRAFTING
                || noticeUntil.getOrDefault(player.getUniqueId(), 0L) > System.nanoTime()) return false;
        int level = player.getLevel();
        if (level > HudPortrait.MAX_LEVEL) return false;
        player.sendActionBar(Component.text(HudPortrait.overlay(level, blink))
                .font(net.kyori.adventure.key.Key.key("neyrocraft", "pilot_hud")));
        return true;
    }


    /** Copies the nameplate teams (rank prefixes) of the main scoreboard into a personal one. */
    private static void mirrorTeams(Scoreboard main, Scoreboard mine, java.util.Set<String> darkNames) {
        for (Team t : main.getTeams()) {
            if (t.getName().equals(ANARCHY_TEAM)) continue;
            Team c = mine.getTeam(t.getName());
            if (c == null) c = mine.registerNewTeam(t.getName());
            if (!c.prefix().equals(t.prefix())) c.prefix(t.prefix());
            if (!c.suffix().equals(t.suffix())) c.suffix(t.suffix());
            if (t.hasColor() && (!c.hasColor() || !c.color().equals(t.color())))
                c.color(NamedTextColor.nearestTo(t.color()));
            for (Team.Option o : Team.Option.values()) if (c.getOption(o) != t.getOption(o)) c.setOption(o, t.getOption(o));
            for (String e : t.getEntries()) if (!darkNames.contains(e) && !c.hasEntry(e)) c.addEntry(e);
            for (String e : new ArrayList<>(c.getEntries())) if (!t.hasEntry(e) || darkNames.contains(e)) c.removeEntry(e);
        }
        for (Team c : new ArrayList<>(mine.getTeams()))
            if (!c.getName().equals(ANARCHY_TEAM) && main.getTeam(c.getName()) == null) c.unregister();
        Team dark = mine.getTeam(ANARCHY_TEAM);
        if (darkNames.isEmpty()) {
            if (dark != null) dark.unregister();
            return;
        }
        if (dark == null) {
            dark = mine.registerNewTeam(ANARCHY_TEAM);
            dark.color(NamedTextColor.RED);
        }
        for (String name : darkNames) if (!dark.hasEntry(name)) dark.addEntry(name);
        for (String name : new ArrayList<>(dark.getEntries())) if (!darkNames.contains(name)) dark.removeEntry(name);
    }

    private Component title() {
        boolean a = tick % 2 == 0;
        return Component.text()
                .append(Component.text("✦ ", TextColor.color(a ? ORANGE : BLUE)))
                .append(Component.text("Neyro", TextColor.color(ORANGE)).decorate(TextDecoration.BOLD))
                .append(Component.text("Craft", TextColor.color(BLUE)).decorate(TextDecoration.BOLD))
                .append(Component.text(" ✦", TextColor.color(a ? BLUE : ORANGE)))
                .build();
    }

    private void render(Player p, Scoreboard sb, Mode m) {
        Objective o = sb.getObjective("neyrohud");
        if (o == null) {
            o = sb.registerNewObjective("neyrohud", Criteria.DUMMY, title());
            o.numberFormat(NumberFormat.blank());
            o.setDisplaySlot(DisplaySlot.SIDEBAR);
        } else {
            o.displayName(title());
        }
        List<Component> lines = lines(p, m);
        for (int i = 0; i < 15; i++) {
            String entry = "§" + Integer.toHexString(i) + "§r";
            if (i < lines.size()) {
                org.bukkit.scoreboard.Score s = o.getScore(entry);
                if (s.getScore() != 15 - i) s.setScore(15 - i);
                Component c = lines.get(i);
                if (!c.equals(s.customName())) s.customName(c);
            } else if (o.getScore(entry).isScoreSet()) {
                o.getScore(entry).resetScore();
            }
        }
    }

    private static Component t(String s, int rgb) { return Component.text(s, TextColor.color(rgb)); }

    private static int mixRgb(int a, int b, double f) {
        int r = (int) Math.round(((a >> 16) & 255) * (1 - f) + ((b >> 16) & 255) * f);
        int g = (int) Math.round(((a >> 8) & 255) * (1 - f) + ((b >> 8) & 255) * f);
        int bl = (int) Math.round((a & 255) * (1 - f) + (b & 255) * f);
        return (r << 16) | (g << 8) | bl;
    }

    private static Component kv(String key, String value, int rgb) {
        return Component.text().append(t(" " + key + " ", DIM)).append(t(value, rgb).decorate(TextDecoration.BOLD)).build();
    }

    private static String time(long sec) {
        long h = sec / 3600, m = (sec / 60) % 60;
        return h > 0 ? h + " ч " + m + " м" : m + " м";
    }

    private static Component activity(Player player, int color) {
        String label = switch (player.getGameMode()) {
            case SURVIVAL -> "Режим выживания";
            case CREATIVE -> "Творческий режим";
            case ADVENTURE -> "Режим приключений";
            case SPECTATOR -> "Режим наблюдения";
        };
        return t("◆ " + label, color).decorate(TextDecoration.BOLD);
    }

    private List<Component> lines(Player p, Mode m) {
        Component rank = null;
        String rj = p.getPersistentDataContainer().get(rankKey, PersistentDataType.STRING);
        if (rj != null) try { rank = GsonComponentSerializer.gson().deserialize(rj); } catch (RuntimeException ignored) { }
        if (rank == null) rank = t(p.isOp() ? "Администратор" : "Игрок", p.isOp() ? ORANGE : DIM);
        int nextXp = Math.max(1, p.getExpToLevel());
        int experience = Math.round(p.getExp() * nextXp);
        Component name = portrait(p).append(t("  " + p.getName(), m == Mode.ANARCHY ? 0xFF6060 : 0xFFFFFF)
                .decorate(TextDecoration.BOLD));
        Component xp = kv("Ур. " + p.getLevel() + " · XP", experience + "/" + nextXp, GOLD);
        if (m == Mode.ANARCHY) {
            long kills = get(p, "anarchy.pvp");
            long mobs = get(p, "anarchy.mobs");
            EvilPass pass = plugin.evilPass();
            return List.of(name, rank, xp, activity(p, 0xFF6060), classLine(p), t(m.title, 0xFF3030),
                    t(" Evil-Pass · ур. " + pass.level(p), 0xFF3030),
                    t(" До уровня: " + pass.untilNextLevel(p) + " XP", 0xFF9999),
                    t(" PvP: " + kills + " · Мобы: " + mobs, 0xFF9999),
                    t(" Награды · /evilpass", 0xFF7070));
        }
        List<Component> l = new ArrayList<>();
        l.add(name);
        l.add(rank);
        l.add(xp);
        l.add(activity(p, m == Mode.HUB3 ? 0xFF6060 : CYAN));
        Component title = null;
        String json = p.getPersistentDataContainer().get(titleKey, PersistentDataType.STRING);
        if (json != null) try { title = GsonComponentSerializer.gson().deserialize(json); } catch (RuntimeException ignored) { }
        l.add(Component.text().append(t(" Титул ", DIM)).append(title != null ? title : t("нет", 0x666A80)).build());
        StarPass sp = plugin.starPass();
        if (sp != null) {
            int stars = sp.stars(p), lvl = StarPass.levelOf(stars);
            l.add(Component.text().append(t(" ★ ", GOLD)).append(t("Star", 0x8A5CFF).decorate(TextDecoration.BOLD)).append(t("Pass ", 0x4F8CFF).decorate(TextDecoration.BOLD))
                    .append(t("ур. " + lvl, NamedTextColor.WHITE.value())).append(t("  " + stars + "★", GOLD)).build());
        }
        // the light/dark side: a little scale from blue to red with a marker, and the number
        NeyroKarma karma = plugin.karma();
        if (karma != null) {
            int k = karma.get(p), cells = 11;
            int at = (int) Math.round((NeyroKarma.MAX - k) / (double) (NeyroKarma.MAX - NeyroKarma.MIN) * (cells - 1));
            var bar = Component.text().append(t(" ◈ ", NeyroKarma.color(k)));
            for (int i = 0; i < cells; i++) {
                double f = i / (double) (cells - 1);
                int rgb = f < 0.5 ? mixRgb(NeyroKarma.LIGHT_RGB, 0x7A3CC8, f * 2) : mixRgb(0x7A3CC8, NeyroKarma.DARK_RGB, f * 2 - 1);
                bar.append(i == at ? t("◆", 0xFFFFFF) : t("■", rgb));
            }
            bar.append(t(" " + NeyroKarma.signed(k), NeyroKarma.color(k)).decorate(TextDecoration.BOLD));
            l.add(bar.build());
        }
        String worldLabel = m == Mode.SURVIVAL ? "Мир выживания" : m == Mode.OTHER ? p.getWorld().getName() : m.title;
        l.add(t(" " + worldLabel, m == Mode.HUB3 ? 0xFF6060 : ORANGE).decorate(TextDecoration.BOLD));
        if (m == Mode.SURVIVAL) l.add(classLine(p));
        // Keep the build coordinates while showing the actual world on the section heading.
        if (p.getGameMode() == GameMode.CREATIVE) {
            l.add(kv("⌖ X / Z", p.getLocation().getBlockX() + " / " + p.getLocation().getBlockZ(), CYAN));
            l.add(kv("↕ Высота", String.valueOf(p.getLocation().getBlockY()), 0x9CFF6B));
            l.add(kv("◉ Игроков", String.valueOf(p.getWorld().getPlayers().size()), GOLD));
            l.add(kv("⌚ В мире", time(get(p, "time." + m.key)), CYAN));
        } else switch (m) {
            case MAIN, ORION, CINEMA -> {
                l.add(kv("⚡ Лазертаг", get(p, "lasertag.wins") + " поб · " + get(p, "lasertag.kills") + " уб", 0xFF6B6B));
                l.add(kv("⚔ Мечи", get(p, "sabers.wins") + " поб · " + get(p, "sabers.kills") + " уб", CYAN));
                PlanetRace r = plugin.race();
                long best = r == null ? 0 : r.best(p.getUniqueId());
                l.add(kv("🏁 Гонки", best > 0 ? PlanetRace.fmt(best) : "—", 0xFFA040));
                l.add(kv("🚀 Бои", get(p, "starfight.wins") + " поб · " + get(p, "starfight.kills") + " сбито", 0x7FE8FF));
            }
            case SURVIVAL, SKYBLOCK, RETRO -> {
                l.add(kv("⚔ Мобов", String.valueOf(get(p, m.key + ".mobs")), 0x9CFF6B));
                l.add(kv("☠ Игроков", String.valueOf(get(p, m.key + ".pvp")), 0xFF6B6B));
                l.add(kv("✝ Смертей", String.valueOf(get(p, m.key + ".deaths")), 0xC0C0C0));
                l.add(kv("⌚ В режиме", time(get(p, "time." + m.key)), CYAN));
            }
            case RACE -> {
                PlanetRace r = plugin.race();
                UUID id = p.getUniqueId();
                l.add(kv("🏁 Рекорд", r != null && r.best(id) > 0 ? PlanetRace.fmt(r.best(id)) : "—", GOLD));
                l.add(kv("⟳ Лучший круг", r != null && r.bestLap(id) > 0 ? PlanetRace.fmt(r.bestLap(id)) : "—", CYAN));
                l.add(kv("✔ Заездов", String.valueOf(r == null ? 0 : r.runs(id)), 0x9CFF6B));
                l.add(kv("⌚ В режиме", time(get(p, "time.race")), CYAN));
            }
            case HUB3 -> l.add(t(" ПКМ по Тёмному Лорду · /hub", 0xFF9999));
            case ANARCHY, OTHER -> { }
        }
        long played = p.getStatistic(Statistic.PLAY_ONE_MINUTE) / 20;
        l.add(kv("⌚ Всего в игре", time(played), 0xE0E0F0));
        // ping and server TPS in blue digits
        double tps = Math.min(20.0, Bukkit.getTPS()[0]);
        l.add(Component.text()
                .append(t(" 📶 ", DIM)).append(t(p.getPing() + " мс", BLUE).decorate(TextDecoration.BOLD))
                .append(t("  ⚡ TPS ", DIM)).append(t(String.format("%.1f", tps), BLUE).decorate(TextDecoration.BOLD)).build());
        return l;
    }

    private Component classLine(Player p) {
        return t(" Класс · ", DIM).append(plugin.classes().label(p));
    }
}
