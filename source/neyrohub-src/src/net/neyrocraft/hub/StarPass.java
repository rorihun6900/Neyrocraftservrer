package net.neyrocraft.hub;

import net.kyori.adventure.key.Key;
import net.kyori.adventure.sound.Sound;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.TextComponent;
import net.kyori.adventure.text.format.NamedTextColor;
import net.kyori.adventure.text.format.TextColor;
import net.kyori.adventure.text.format.TextDecoration;
import net.kyori.adventure.text.serializer.gson.GsonComponentSerializer;
import net.kyori.adventure.title.Title;
import org.bukkit.Bukkit;
import org.bukkit.Color;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.Particle;
import org.bukkit.World;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.Display;
import org.bukkit.entity.Entity;
import org.bukkit.entity.Interaction;
import org.bukkit.entity.Player;
import org.bukkit.entity.TextDisplay;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.inventory.InventoryCloseEvent;
import org.bukkit.event.inventory.InventoryDragEvent;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.InventoryHolder;
import org.bukkit.inventory.ItemFlag;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.persistence.PersistentDataType;
import org.bukkit.util.Transformation;
import org.joml.AxisAngle4f;
import org.joml.Vector3f;

import java.io.File;
import java.io.IOException;
import java.time.Duration;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.Set;
import java.util.UUID;

/**
 * StarPass — the season pass of NeyroCraft. Stars (★) come from the daily login (with a streak bonus),
 * time online and victories in the modes; every 250 ★ opens a level with a reward:
 * chat titles, hub trails, energy-sword colours and useful survival items.
 */
final class StarPass implements Listener {
    static final int STAR = 0x5B2AA6, PASS = 0x2F6BFF;        // "Star" dark violet, "Pass" blue
    static final int COST = 250, LEVELS = 30, PER_PAGE = 7;
    private static final String SEASON = "Сезон 1 · Туманность";

    enum Kind { TITLE, ITEM, TRAIL, SABER }

    record Reward(int level, Kind kind, String name, Material icon, String value, int amount, int color) { }

    static final List<Reward> REWARDS = List.of(
            r(1, Kind.TITLE, "Титул «Новобранец»", Material.NAME_TAG, "Новобранец", 1, 0x9FB8E8),
            r(2, Kind.ITEM, "16 железных слитков", Material.IRON_INGOT, "IRON_INGOT", 16, 0xDDDDDD),
            r(3, Kind.TRAIL, "След «Звёздная пыль»", Material.GLOWSTONE_DUST, "stardust", 1, 0xFFF4C8),
            r(4, Kind.SABER, "Оранжевый клинок", Material.BLAZE_POWDER, "orange", 1, 0xFF8A1E),
            r(5, Kind.ITEM, "4 золотых яблока", Material.GOLDEN_APPLE, "GOLDEN_APPLE", 4, 0xFFD54A),
            r(6, Kind.TITLE, "Титул «Пилот»", Material.NAME_TAG, "Пилот", 1, 0x7FE8FF),
            r(7, Kind.ITEM, "3 алмаза", Material.DIAMOND, "DIAMOND", 3, 0x6FF0FF),
            r(8, Kind.TRAIL, "След «Туманность»", Material.AMETHYST_SHARD, "nebula", 1, 0xC08CFF),
            r(9, Kind.SABER, "Бирюзовый клинок", Material.PRISMARINE_CRYSTALS, "cyan", 1, 0x2AF5FF),
            r(10, Kind.ITEM, "32 пузырька опыта", Material.EXPERIENCE_BOTTLE, "EXPERIENCE_BOTTLE", 32, 0x9CFF6A),
            r(11, Kind.ITEM, "8 жемчужин Края", Material.ENDER_PEARL, "ENDER_PEARL", 8, 0x3FD8B0),
            r(12, Kind.TITLE, "Титул «Звездочёт»", Material.NAME_TAG, "Звездочёт", 1, 0xC08CFF),
            r(13, Kind.ITEM, "5 алмазов", Material.DIAMOND, "DIAMOND", 5, 0x6FF0FF),
            r(14, Kind.TRAIL, "След «Хвост кометы»", Material.FIRE_CHARGE, "comet", 1, 0xFFB347),
            r(15, Kind.ITEM, "Незеритовый лом", Material.NETHERITE_SCRAP, "NETHERITE_SCRAP", 1, 0x8C6A5A),
            r(16, Kind.TITLE, "Титул «Капитан»", Material.NAME_TAG, "Капитан", 1, 0xFFB347),
            r(17, Kind.ITEM, "32 золотые морковки", Material.GOLDEN_CARROT, "GOLDEN_CARROT", 32, 0xFFC94A),
            r(18, Kind.TRAIL, "След «Полярное сияние»", Material.GLOW_BERRIES, "aurora", 1, 0x5AFFB0),
            r(19, Kind.ITEM, "8 алмазов", Material.DIAMOND, "DIAMOND", 8, 0x6FF0FF),
            r(20, Kind.SABER, "Белый клинок", Material.QUARTZ, "white", 1, 0xF2F2FF),
            r(21, Kind.ITEM, "2 панциря шалкера", Material.SHULKER_SHELL, "SHULKER_SHELL", 2, 0xB48CC8),
            r(22, Kind.TITLE, "Титул «Исследователь туманности»", Material.NAME_TAG, "Исследователь туманности", 1, 0xB04BFF),
            r(23, Kind.ITEM, "64 пузырька опыта", Material.EXPERIENCE_BOTTLE, "EXPERIENCE_BOTTLE", 64, 0x9CFF6A),
            r(24, Kind.TRAIL, "След «Галактика»", Material.ENDER_EYE, "galaxy", 1, 0x7F6BFF),
            r(25, Kind.ITEM, "Тотем бессмертия", Material.TOTEM_OF_UNDYING, "TOTEM_OF_UNDYING", 1, 0xFFE070),
            r(26, Kind.TITLE, "Титул «Адмирал»", Material.NAME_TAG, "Адмирал", 1, 0x2F6BFF),
            r(27, Kind.ITEM, "Незеритовый слиток", Material.NETHERITE_INGOT, "NETHERITE_INGOT", 1, 0x8C6A5A),
            r(28, Kind.TRAIL, "След «Сверхновая»", Material.NETHER_STAR, "supernova", 1, 0xFFFFFF),
            r(29, Kind.ITEM, "2 алмазных блока", Material.DIAMOND_BLOCK, "DIAMOND_BLOCK", 2, 0x6FF0FF),
            r(30, Kind.TITLE, "Титул «Легенда NeyroCraft»", Material.DRAGON_HEAD, "Легенда NeyroCraft", 1, 0xFF8C00));

    private static Reward r(int l, Kind k, String n, Material i, String v, int a, int c) { return new Reward(l, k, n, i, v, a, c); }

    static final Map<String, String> TRAIL_NAMES = Map.of("stardust", "Звёздная пыль", "nebula", "Туманность", "comet", "Хвост кометы",
            "aurora", "Полярное сияние", "galaxy", "Галактика", "supernova", "Сверхновая");

    private static final class Data {
        int stars, streak, onlineMinutes, onlineStarsToday;
        String lastDaily = "", onlineDay = "";
        final Set<Integer> claimed = new HashSet<>();
        final Set<String> unlocks = new HashSet<>();
        String trail = "", title = "";
    }

    private static final class Menu implements InventoryHolder {
        final Player player; int page; Inventory inv;
        Menu(Player p, int page) { this.player = p; this.page = page; }
        @Override public Inventory getInventory() { return inv; }
    }

    private final NeyroHub plugin;
    private final File dir;
    private final NamespacedKey titleKey;
    private final Map<UUID, Data> data = new HashMap<>();
    private final Map<UUID, Menu> open = new HashMap<>();
    private final List<Entity> terminal = new ArrayList<>();
    private final Random rnd = new Random();
    private TextDisplay terminalLabel;
    private Interaction terminalHit;
    private long tick;

    StarPass(NeyroHub plugin) {
        this.plugin = plugin;
        this.dir = new File(plugin.getDataFolder(), "starpass");
        this.titleKey = new NamespacedKey("starpass", "title");
        dir.mkdirs();
    }

    void start(World hub, Location terminalAt) {
        for (Player p : Bukkit.getOnlinePlayers()) load(p);
        spawnTerminal(terminalAt);
        Bukkit.getScheduler().runTaskTimer(plugin, this::tick, 20L, 3L);
        Bukkit.getScheduler().runTaskTimer(plugin, this::minute, 1200L, 1200L);
        Bukkit.getScheduler().runTaskTimer(plugin, () -> { for (UUID id : data.keySet()) save(id); }, 2400L, 2400L);
    }

    void shutdown() {
        for (Menu m : new ArrayList<>(open.values())) m.player.closeInventory();
        for (UUID id : new ArrayList<>(data.keySet())) save(id);
        for (Entity e : terminal) if (e.isValid()) e.remove();
        terminal.clear();
    }

    boolean isTerminal(Entity e) { return terminalHit != null && terminalHit.equals(e); }

    // ------------------------------------------------------------ data

    private Data d(Player p) { return data.computeIfAbsent(p.getUniqueId(), id -> read(id)); }

    private Data read(UUID id) {
        Data x = new Data();
        File f = new File(dir, id + ".yml");
        if (!f.exists()) return x;
        YamlConfiguration y = YamlConfiguration.loadConfiguration(f);
        x.stars = y.getInt("stars"); x.streak = y.getInt("streak");
        x.lastDaily = y.getString("last-daily", ""); x.onlineDay = y.getString("online-day", "");
        x.onlineMinutes = y.getInt("online-minutes"); x.onlineStarsToday = y.getInt("online-stars-today");
        x.claimed.addAll(y.getIntegerList("claimed"));
        x.unlocks.addAll(y.getStringList("unlocks"));
        x.trail = y.getString("trail", ""); x.title = y.getString("title", "");
        return x;
    }

    private void load(Player p) { d(p); applyTitle(p); }

    private void save(UUID id) {
        Data x = data.get(id);
        if (x == null) return;
        YamlConfiguration y = new YamlConfiguration();
        y.set("stars", x.stars); y.set("streak", x.streak); y.set("last-daily", x.lastDaily);
        y.set("online-day", x.onlineDay); y.set("online-minutes", x.onlineMinutes); y.set("online-stars-today", x.onlineStarsToday);
        y.set("claimed", new ArrayList<>(x.claimed)); y.set("unlocks", new ArrayList<>(x.unlocks));
        y.set("trail", x.trail); y.set("title", x.title);
        try { y.save(new File(dir, id + ".yml")); } catch (IOException e) { plugin.getLogger().warning("StarPass save failed: " + e); }
    }

    int stars(Player p) { return d(p).stars; }

    static int levelOf(int stars) { return Math.min(LEVELS, stars / COST); }

    boolean unlocked(Player p, String unlock) { return d(p).unlocks.contains(unlock); }

    // ------------------------------------------------------------ earning stars

    /** Adds stars with a little notice; announces new levels. */
    void award(Player p, int amount, String reason) {
        if (p == null || !p.isOnline() || amount <= 0) return;
        Data x = d(p);
        int before = levelOf(x.stars);
        x.stars += amount;
        int after = levelOf(x.stars);
        HudBoard.notice(p, Component.text()
                .append(Component.text("+" + amount + " ★ ", TextColor.color(0xFFE08A)).decorate(TextDecoration.BOLD))
                .append(brand(-1)).append(Component.text("  · " + reason, NamedTextColor.GRAY)).build());
        p.playSound(Sound.sound(Key.key("minecraft", "entity.experience_orb.pickup"), Sound.Source.MASTER, 0.5f, 1.6f));
        if (after > before) {
            p.showTitle(Title.title(brand(-1).decorate(TextDecoration.BOLD),
                    Component.text("Уровень " + after + "! Награда ждёт — /pass", TextColor.color(0xFFE08A)),
                    Title.Times.times(Duration.ofMillis(200), Duration.ofMillis(2400), Duration.ofMillis(600))));
            p.playSound(Sound.sound(Key.key("minecraft", "ui.toast.challenge_complete"), Sound.Source.MASTER, 0.7f, 1.3f));
        }
    }

    private void daily(Player p) {
        Data x = d(p);
        String today = LocalDate.now().toString();
        if (today.equals(x.lastDaily)) return;
        x.streak = LocalDate.now().minusDays(1).toString().equals(x.lastDaily) ? x.streak + 1 : 1;
        x.lastDaily = today;
        int bonus = Math.min(200, (x.streak - 1) * 20);
        award(p, 100 + bonus, "вход на сервер · серия " + x.streak + " дн.");
        p.sendMessage(Component.text()
                .append(brand(-1)).append(Component.text(" ➜ ", NamedTextColor.DARK_GRAY))
                .append(Component.text("ежедневная награда: +" + (100 + bonus) + " ★", TextColor.color(0xFFE08A)))
                .append(Component.text(" (серия " + x.streak + " дн.)  ", NamedTextColor.GRAY))
                .append(Component.text("[открыть /pass]", TextColor.color(PASS))
                        .clickEvent(net.kyori.adventure.text.event.ClickEvent.runCommand("/pass"))).build());
    }

    /** Every minute: +10 ★ for each 10 minutes online (up to 120 ★ a day). */
    private void minute() {
        String today = LocalDate.now().toString();
        for (Player p : Bukkit.getOnlinePlayers()) {
            if (!plugin.authed(p)) continue;
            Data x = d(p);
            if (!today.equals(x.onlineDay)) { x.onlineDay = today; x.onlineMinutes = 0; x.onlineStarsToday = 0; }
            x.onlineMinutes++;
            if (x.onlineMinutes % 10 == 0 && x.onlineStarsToday < 120) { x.onlineStarsToday += 10; award(p, 10, "время в игре"); }
        }
    }

    @EventHandler
    public void onJoin(PlayerJoinEvent e) { load(e.getPlayer()); }

    @EventHandler
    public void onQuit(PlayerQuitEvent e) {
        open.remove(e.getPlayer().getUniqueId());
        save(e.getPlayer().getUniqueId());
        data.remove(e.getPlayer().getUniqueId());
    }

    // ------------------------------------------------------------ the menu

    static Component brand(int shine) {
        TextComponent.Builder b = Component.text();
        String w = "StarPass";
        for (int i = 0; i < w.length(); i++) {
            int base = i < 4 ? STAR : PASS;
            int glow = i < 4 ? 0xD9B8FF : 0xB8D4FF;
            int dd = Math.abs(i - shine);
            double t = shine < 0 ? 0 : dd == 0 ? 1 : dd == 1 ? 0.5 : 0;
            b.append(Component.text(String.valueOf(w.charAt(i)), TextColor.color(SpaceDecor.mix(base, glow, t))));
        }
        return b.build();
    }

    void openMenu(Player p, int page) {
        Menu m = new Menu(p, Math.max(0, Math.min((LEVELS - 1) / PER_PAGE, page)));
        Inventory inv = Bukkit.createInventory(m, 54, Component.text()
                .append(Component.text("✦ ", TextColor.color(0x7F6BFF)))
                .append(Component.text("Star", TextColor.color(STAR)).decorate(TextDecoration.BOLD))
                .append(Component.text("Pass", TextColor.color(PASS)).decorate(TextDecoration.BOLD))
                .append(Component.text(" ✦ " + SEASON, TextColor.color(0x5A5A7A))).build());
        m.inv = inv;
        render(m);
        open.put(p.getUniqueId(), m);
        p.openInventory(inv);
        p.playSound(Sound.sound(Key.key("minecraft", "block.beacon.power_select"), Sound.Source.MASTER, 0.6f, 1.8f));
    }

    private ItemStack icon(Material m, Component name, List<Component> lore, boolean glint) {
        ItemStack it = new ItemStack(m);
        ItemMeta meta = it.getItemMeta();
        meta.displayName(name.decoration(TextDecoration.ITALIC, false));
        List<Component> l = new ArrayList<>();
        for (Component c : lore) l.add(c.decoration(TextDecoration.ITALIC, false));
        meta.lore(l);
        meta.setEnchantmentGlintOverride(glint);
        meta.addItemFlags(ItemFlag.values());
        it.setItemMeta(meta);
        return it;
    }

    private static final int[] BORDER = {0, 1, 2, 3, 5, 6, 7, 8, 9, 10, 11, 12, 13, 14, 15, 16, 17, 18, 26, 27, 35, 36, 37, 39, 41, 43, 44, 46, 52};

    private void render(Menu m) {
        Player p = m.player;
        Data x = d(p);
        int level = levelOf(x.stars);
        Inventory inv = m.inv;
        for (int s : BORDER) inv.setItem(s, star(s));
        inv.setItem(4, header(x, -1));
        for (int i = 0; i < PER_PAGE; i++) {
            int lvl = m.page * PER_PAGE + i + 1;
            if (lvl > LEVELS) { inv.setItem(19 + i, null); inv.setItem(28 + i, null); continue; }
            Reward r = REWARDS.get(lvl - 1);
            boolean got = x.claimed.contains(lvl), ready = !got && level >= lvl;
            Material pane = got ? Material.LIME_STAINED_GLASS_PANE : ready ? Material.YELLOW_STAINED_GLASS_PANE
                    : lvl == level + 1 ? Material.LIGHT_BLUE_STAINED_GLASS_PANE : Material.GRAY_STAINED_GLASS_PANE;
            List<Component> lore = new ArrayList<>();
            lore.add(Component.text(r.name(), TextColor.color(r.color())));
            lore.add(Component.text("NEYRO$ +$" + (750 + lvl * 50) + " за получение уровня", NamedTextColor.GREEN));
            if (got) lore.add(Component.text("✔ Получено", NamedTextColor.GREEN));
            else if (ready) lore.add(Component.text("▶ Нажми, чтобы забрать!", TextColor.color(0xFFE08A)));
            else lore.add(Component.text("Нужно " + (lvl * COST) + " ★ (у тебя " + x.stars + ")", NamedTextColor.GRAY));
            inv.setItem(19 + i, icon(pane, Component.text((got ? "✔ " : ready ? "★ " : "") + "Уровень " + lvl,
                    got ? NamedTextColor.GREEN : ready ? TextColor.color(0xFFE08A) : NamedTextColor.GRAY).decorate(TextDecoration.BOLD), lore, ready));
            inv.setItem(28 + i, icon(r.icon(), Component.text(r.name(), TextColor.color(r.color())).decorate(TextDecoration.BOLD), lore, ready));
        }
        inv.setItem(38, icon(Material.CLOCK, Component.text("Как получать ★", TextColor.color(0xFFE08A)).decorate(TextDecoration.BOLD), List.of(
                Component.text("• Вход на сервер раз в день: +100 ★", NamedTextColor.GRAY),
                Component.text("  серия дней: ещё до +200 ★", NamedTextColor.DARK_GRAY),
                Component.text("• Каждые 10 минут в игре: +10 ★", NamedTextColor.GRAY),
                Component.text("• Победа в LaserTag: +80 ★, MVP: +30 ★", NamedTextColor.GRAY),
                Component.text("• Световые мечи: +10 ★ за победу в дуэли,", NamedTextColor.GRAY),
                Component.text("  +100 ★ за титул мастера клинка", NamedTextColor.GRAY)), false));
        inv.setItem(40, icon(Material.FIREWORK_STAR, Component.text("Серия входов: " + x.streak + " дн.", TextColor.color(0x7FE8FF)).decorate(TextDecoration.BOLD), List.of(
                Component.text("Заходи каждый день — бонус растёт!", NamedTextColor.GRAY),
                Component.text("Сегодня за онлайн: " + x.onlineStarsToday + "/120 ★", NamedTextColor.DARK_GRAY)), false));
        inv.setItem(42, cosmeticsIcon(p, x));
        inv.setItem(45, icon(m.page > 0 ? Material.SPECTRAL_ARROW : Material.GRAY_DYE, Component.text("◀ Назад", NamedTextColor.WHITE), List.of(), false));
        inv.setItem(53, icon((m.page + 1) * PER_PAGE < LEVELS ? Material.SPECTRAL_ARROW : Material.GRAY_DYE, Component.text("Вперёд ▶", NamedTextColor.WHITE), List.of(), false));
        inv.setItem(47, trailIcon(x));
        inv.setItem(51, titleIcon(x));
        inv.setItem(48, icon(Material.PAPER, Component.text("Страница " + (m.page + 1) + "/" + ((LEVELS - 1) / PER_PAGE + 1), NamedTextColor.GRAY), List.of(), false));
        inv.setItem(49, icon(Material.BARRIER, Component.text("Закрыть", NamedTextColor.RED), List.of(), false));
        inv.setItem(50, icon(Material.NETHER_STAR, Component.text("Уровень " + level + "/" + LEVELS, TextColor.color(PASS)).decorate(TextDecoration.BOLD),
                List.of(Component.text(x.stars + " ★ всего", NamedTextColor.GRAY)), level >= LEVELS));
    }

    private ItemStack header(Data x, int shine) {
        int level = levelOf(x.stars);
        int into = level >= LEVELS ? COST : x.stars - level * COST;
        return icon(Material.NETHER_STAR, Component.text()
                .append(Component.text("✦ ", TextColor.color(0x7F6BFF))).append(brand(shine).decorate(TextDecoration.BOLD))
                .append(Component.text(" ✦", TextColor.color(0x7F6BFF))).build(), List.of(
                Component.text(SEASON, TextColor.color(0x9FB8E8)),
                Component.text(""),
                Component.text("Уровень " + level + "/" + LEVELS + "   ·   " + x.stars + " ★", NamedTextColor.WHITE),
                bar(into, COST, shine),
                Component.text(level >= LEVELS ? "Сезон пройден! Ты — легенда." : "До следующего уровня: " + (COST - into) + " ★", NamedTextColor.GRAY)), true);
    }

    /** Progress bar with a sparkle running along it. */
    private static Component bar(int have, int need, int shine) {
        TextComponent.Builder b = Component.text();
        int cells = 20, filled = Math.min(cells, have * cells / Math.max(1, need));
        for (int i = 0; i < cells; i++) {
            boolean on = i < filled;
            int c = on ? SpaceDecor.mix(STAR, PASS, i / (double) cells) : 0x2A2A3A;
            if (on && shine >= 0 && Math.floorMod(shine * 2, cells + 8) == i) c = 0xFFFFFF;
            b.append(Component.text("▌", TextColor.color(c)));
        }
        return b.build();
    }

    private ItemStack star(int slot) {
        int h = rnd.nextInt(100);
        Material m = h < 52 ? Material.BLACK_STAINED_GLASS_PANE : h < 74 ? Material.PURPLE_STAINED_GLASS_PANE
                : h < 90 ? Material.BLUE_STAINED_GLASS_PANE : h < 97 ? Material.LIGHT_BLUE_STAINED_GLASS_PANE : Material.WHITE_STAINED_GLASS_PANE;
        return icon(m, Component.text(h >= 97 ? "✦" : " ", NamedTextColor.WHITE), List.of(), false);
    }

    private ItemStack cosmeticsIcon(Player p, Data x) {
        List<Component> lore = new ArrayList<>();
        lore.add(Component.text("Открытые цвета клинка:", NamedTextColor.GRAY));
        for (String c : new String[]{"orange", "cyan", "white"})
            lore.add(Component.text((x.unlocks.contains("saber:" + c) ? "✔ " : "🔒 ") + saberName(c), x.unlocks.contains("saber:" + c) ? NamedTextColor.GREEN : NamedTextColor.DARK_GRAY));
        return icon(Material.NETHERITE_SWORD, Component.text("Световые мечи", TextColor.color(0x7FE8FF)).decorate(TextDecoration.BOLD), lore, false);
    }

    private static String saberName(String id) {
        return switch (id) { case "orange" -> "Оранжевый"; case "cyan" -> "Бирюзовый"; case "white" -> "Белый"; default -> id; };
    }

    private ItemStack trailIcon(Data x) {
        String cur = x.trail.isEmpty() ? "нет" : TRAIL_NAMES.getOrDefault(x.trail, x.trail);
        return icon(Material.GLOWSTONE_DUST, Component.text("✦ След: " + cur, TextColor.color(0xFFF4C8)).decorate(TextDecoration.BOLD),
                List.of(Component.text("Нажми — следующий открытый след", NamedTextColor.GRAY),
                        Component.text("Следы видны в хабах", NamedTextColor.DARK_GRAY)), !x.trail.isEmpty());
    }

    private ItemStack titleIcon(Data x) {
        String cur = x.title.isEmpty() ? "нет" : x.title;
        return icon(Material.NAME_TAG, Component.text("✦ Титул: " + cur, TextColor.color(0xC08CFF)).decorate(TextDecoration.BOLD),
                List.of(Component.text("Нажми — следующий открытый титул", NamedTextColor.GRAY),
                        Component.text("Титул виден в чате после ника", NamedTextColor.DARK_GRAY)), !x.title.isEmpty());
    }

    @EventHandler
    public void onClick(InventoryClickEvent e) {
        if (!(e.getInventory().getHolder() instanceof Menu m)) return;
        e.setCancelled(true);
        if (e.getClickedInventory() != m.inv) return;
        Player p = m.player;
        int s = e.getRawSlot();
        Data x = d(p);
        if (s == 45 && m.page > 0) { m.page--; click(p); render(m); }
        else if (s == 53 && (m.page + 1) * PER_PAGE < LEVELS) { m.page++; click(p); render(m); }
        else if (s == 49) p.closeInventory();
        else if (s == 47) { cycle(p, x, true); render(m); }
        else if (s == 51) { cycle(p, x, false); render(m); }
        else if ((s >= 19 && s <= 25) || (s >= 28 && s <= 34)) {
            int lvl = m.page * PER_PAGE + (s >= 28 ? s - 28 : s - 19) + 1;
            claim(p, x, lvl);
            render(m);
        }
    }

    @EventHandler public void onDrag(InventoryDragEvent e) { if (e.getInventory().getHolder() instanceof Menu) e.setCancelled(true); }
    @EventHandler public void onClose(InventoryCloseEvent e) { if (e.getInventory().getHolder() instanceof Menu) open.remove(e.getPlayer().getUniqueId()); }

    private void click(Player p) { p.playSound(Sound.sound(Key.key("minecraft", "ui.button.click"), Sound.Source.MASTER, 0.4f, 1.6f)); }

    private void claim(Player p, Data x, int lvl) {
        if (lvl < 1 || lvl > LEVELS || x.claimed.contains(lvl)) return;
        if (levelOf(x.stars) < lvl) {
            p.playSound(Sound.sound(Key.key("minecraft", "block.note_block.bass"), Sound.Source.MASTER, 0.6f, 0.6f));
            return;
        }
        Reward r = REWARDS.get(lvl - 1);
        if (r.kind() == Kind.ITEM && !plugin.canReceiveItems(p)) {
            p.sendMessage(Component.text("Вещи из StarPass можно забрать в хабе или в выживании", NamedTextColor.RED));
            return;
        }
        switch (r.kind()) {
            case ITEM -> {
                Material mat = Material.matchMaterial(r.value());
                if (mat != null) for (ItemStack left : p.getInventory().addItem(new ItemStack(mat, r.amount())).values())
                    p.getWorld().dropItemNaturally(p.getLocation(), left);
            }
            case TITLE -> { x.unlocks.add("title:" + r.value()); x.title = r.value(); applyTitle(p); }
            case TRAIL -> { x.unlocks.add("trail:" + r.value()); x.trail = r.value(); }
            case SABER -> x.unlocks.add("saber:" + r.value());
        }
        x.claimed.add(lvl);
        if (plugin.money() != null) plugin.money().rewardPass(p, lvl);
        save(p.getUniqueId());
        p.playSound(Sound.sound(Key.key("minecraft", "entity.player.levelup"), Sound.Source.MASTER, 0.7f, 1.4f));
        p.sendMessage(Component.text().append(brand(-1)).append(Component.text(" ➜ получено: ", NamedTextColor.GRAY))
                .append(Component.text(r.name(), TextColor.color(r.color()))).build());
    }

    private void cycle(Player p, Data x, boolean trail) {
        List<String> list = new ArrayList<>();
        list.add("");
        for (Reward r : REWARDS) {
            if (trail && r.kind() == Kind.TRAIL && x.unlocks.contains("trail:" + r.value())) list.add(r.value());
            if (!trail && r.kind() == Kind.TITLE && x.unlocks.contains("title:" + r.value())) list.add(r.value());
        }
        String cur = trail ? x.trail : x.title;
        String next = list.get((Math.max(0, list.indexOf(cur)) + 1) % list.size());
        if (trail) x.trail = next; else { x.title = next; applyTitle(p); }
        click(p);
    }

    /** The chosen title is stored on the player; NeyroRanks shows it in chat after the name. */
    private void applyTitle(Player p) {
        Data x = d(p);
        Reward r = null;
        for (Reward rr : REWARDS) if (rr.kind() == Kind.TITLE && rr.value().equals(x.title)) r = rr;
        if (r == null || x.title.isEmpty()) { p.getPersistentDataContainer().remove(titleKey); return; }
        Component c = Component.text("「" + r.value() + "」", TextColor.color(r.color()));
        p.getPersistentDataContainer().set(titleKey, PersistentDataType.STRING, GsonComponentSerializer.gson().serialize(c));
    }

    // ------------------------------------------------------------ animation, trails, daily check

    private void tick() {
        tick++;
        // animated menus: twinkling stars, a shine running over the name and along the bar
        for (Menu m : open.values()) {
            int shine = (int) (tick % 16) - 3;
            m.inv.setItem(4, header(d(m.player), shine));
            for (int k = 0; k < 4; k++) { int s = BORDER[rnd.nextInt(BORDER.length)]; m.inv.setItem(s, star(s)); }
        }
        if (terminalLabel != null && terminalLabel.isValid() && tick % 2 == 0) terminalLabel.text(terminalText((int) (tick / 2 % 14) - 3));
        if (tick % 20 == 0) for (Player p : Bukkit.getOnlinePlayers()) if (plugin.authed(p) && plugin.inHub(p)) daily(p);
        for (Player p : Bukkit.getOnlinePlayers()) {
            Data x = data.get(p.getUniqueId());
            if (x == null || x.trail.isEmpty() || !plugin.inHub(p) || p.getGameMode() == org.bukkit.GameMode.SPECTATOR || plugin.inCutscene(p)) continue;
            trail(p, x.trail);
        }
    }

    private void trail(Player p, String id) {
        Location l = p.getLocation().add(0, 0.15, 0);
        World w = p.getWorld();
        double a = tick * 0.6;
        switch (id) {
            case "stardust" -> w.spawnParticle(Particle.END_ROD, l, 1, 0.15, 0.05, 0.15, 0.003);
            case "nebula" -> {
                w.spawnParticle(Particle.DUST, l, 2, 0.25, 0.1, 0.25, 0, new Particle.DustOptions(Color.fromRGB(0xB04BFF), 0.9f));
                w.spawnParticle(Particle.WITCH, l, 1, 0.2, 0.05, 0.2, 0);
            }
            case "comet" -> {
                w.spawnParticle(Particle.FLAME, l, 1, 0.08, 0.02, 0.08, 0.004);
                w.spawnParticle(Particle.DUST, l, 1, 0.1, 0.05, 0.1, 0, new Particle.DustOptions(Color.fromRGB(0xFFB347), 1.0f));
            }
            case "aurora" -> {
                int c = SpaceDecor.mix(0x5AFFB0, 0x4FC3FF, (Math.sin(a) + 1) / 2);
                w.spawnParticle(Particle.DUST, l.clone().add(Math.cos(a) * 0.4, 0.3 + Math.sin(a * 0.5) * 0.15, Math.sin(a) * 0.4), 1, 0, 0, 0, 0,
                        new Particle.DustOptions(Color.fromRGB(c), 1.0f));
            }
            case "galaxy" -> {
                for (int k = 0; k < 2; k++) {
                    double b = a + k * Math.PI;
                    w.spawnParticle(Particle.DUST, l.clone().add(Math.cos(b) * 0.55, 0.05, Math.sin(b) * 0.55), 1, 0, 0, 0, 0,
                            new Particle.DustOptions(Color.fromRGB(k == 0 ? 0x7F6BFF : 0xFF7FD8), 0.9f));
                }
            }
            case "supernova" -> {
                w.spawnParticle(Particle.END_ROD, l, 1, 0.1, 0.05, 0.1, 0.01);
                if (tick % 20 == 0) w.spawnParticle(Particle.FIREWORK, l.clone().add(0, 0.5, 0), 14, 0.1, 0.1, 0.1, 0.08);
            }
            default -> { }
        }
    }

    // ------------------------------------------------------------ the StarPass terminal in the hub

    private Component terminalText(int shine) {
        return Component.text()
                .append(Component.text("✦ ", TextColor.color(0x7F6BFF))).append(brand(shine).decorate(TextDecoration.BOLD))
                .append(Component.text(" ✦", TextColor.color(0x7F6BFF))).append(Component.newline())
                .append(Component.text(SEASON, TextColor.color(0x9FB8E8))).append(Component.newline())
                .append(Component.text("[ПКМ — открыть пропуск]", NamedTextColor.YELLOW)).build();
    }

    private void spawnTerminal(Location at) {
        World w = at.getWorld();
        terminal.add(w.spawn(at.clone().add(0, 0.02, 0), org.bukkit.entity.BlockDisplay.class, d -> {
            plugin.prepare(d);
            d.setBlock(Material.CRYING_OBSIDIAN.createBlockData());
            d.setTransformation(new Transformation(new Vector3f(-0.45f, 0, -0.45f), new AxisAngle4f(), new Vector3f(0.9f, 1.1f, 0.9f), new AxisAngle4f()));
            d.setBrightness(new Display.Brightness(15, 15));
        }));
        terminal.add(w.spawn(at.clone().add(0, 1.15, 0), org.bukkit.entity.ItemDisplay.class, d -> {
            plugin.prepare(d);
            d.setItemStack(new ItemStack(Material.NETHER_STAR));
            d.setBillboard(Display.Billboard.VERTICAL);
            d.setBrightness(new Display.Brightness(15, 15));
            d.setTransformation(new Transformation(new Vector3f(0, 0.35f, 0), new AxisAngle4f(), new Vector3f(0.7f, 0.7f, 0.7f), new AxisAngle4f()));
        }));
        terminalLabel = w.spawn(at.clone().add(0, 2.15, 0), TextDisplay.class, t -> {
            plugin.prepare(t);
            t.setBillboard(Display.Billboard.CENTER);
            t.setBackgroundColor(Color.fromARGB(150, 14, 8, 36));
            t.setShadowed(true);
            t.setTransformation(new Transformation(new Vector3f(), new AxisAngle4f(), new Vector3f(1f, 1f, 1f), new AxisAngle4f()));
            t.text(terminalText(-1));
        });
        terminal.add(terminalLabel);
        terminalHit = w.spawn(at.clone(), Interaction.class, e -> {
            e.addScoreboardTag(NeyroHub.TAG);
            e.setPersistent(false);
            e.setInteractionWidth(1.1f);
            e.setInteractionHeight(2.2f);
            e.setResponsive(true);
        });
        terminal.add(terminalHit);
    }
}
