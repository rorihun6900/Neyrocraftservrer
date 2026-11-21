package net.neyrocraft.hub;

import io.papermc.paper.entity.TeleportFlag;
import net.kyori.adventure.bossbar.BossBar;
import net.kyori.adventure.key.Key;
import net.kyori.adventure.sound.Sound;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.TextComponent;
import net.kyori.adventure.text.format.NamedTextColor;
import net.kyori.adventure.text.format.TextColor;
import net.kyori.adventure.text.format.TextDecoration;
import net.kyori.adventure.title.Title;
import org.bukkit.Bukkit;
import org.bukkit.Color;
import org.bukkit.FluidCollisionMode;
import org.bukkit.GameMode;
import org.bukkit.Input;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.Particle;
import org.bukkit.World;
import org.bukkit.block.Block;
import org.bukkit.entity.BlockDisplay;
import org.bukkit.entity.Display;
import org.bukkit.entity.Entity;
import org.bukkit.entity.ItemDisplay;
import org.bukkit.entity.Player;
import org.bukkit.entity.TextDisplay;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.block.Action;
import org.bukkit.event.entity.EntityDismountEvent;
import org.bukkit.event.player.PlayerAnimationEvent;
import org.bukkit.event.player.PlayerAnimationType;
import org.bukkit.event.player.PlayerDropItemEvent;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.event.player.PlayerSwapHandItemsEvent;
import org.bukkit.inventory.EquipmentSlot;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.persistence.PersistentDataType;
import org.bukkit.scoreboard.Criteria;
import org.bukkit.scoreboard.DisplaySlot;
import org.bukkit.scoreboard.Objective;
import org.bukkit.scoreboard.Scoreboard;
import org.bukkit.util.RayTraceResult;
import org.bukkit.util.Transformation;
import org.bukkit.util.Vector;
import org.joml.Quaternionf;
import org.joml.Vector3f;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.time.Duration;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.UUID;

/**
 * Space battles: dogfights of "NX-2 Стриж" fighters in a huge open space full of asteroids, around
 * the launch station "Аврора". Free-for-all to 20 kills; pirate fighters (AI) fill up the sky when few
 * pilots are online. The ship flies where you look; W/S throttle, A/D side thrusters, Space/Ctrl boost,
 * LMB/RMB twin lasers. Shields regenerate, the hull does not; overheat the cannons and they jam.
 */
final class StarFight implements Listener {
    static final String GAME = "starfight";
    static final int SX = 12000, SY = 190, SZ = 0;
    private static final int R = 230, BUILD = 1;
    private static final int WIN_KILLS = 20;
    private static final long MATCH_MS = 6 * 60 * 1000;
    private static final double MAX = 1.5, BOOST = 2.4, MIN = 0.25, ACC = 0.06;
    private static final double HIT_R = 2.6, BOLT_SPEED = 5.0;
    private static final String[] COLOR = {"red", "blue", "green", "gold"};
    private static final int[] RGB = {0xFF4A3A, 0x46A0FF, 0x5AE68A, 0xFFC040};

    private final NeyroHub plugin;
    private final World w;
    private final Random rnd = new Random();
    private final NamespacedKey itemKey;
    private final Map<UUID, Ship> pilots = new HashMap<>();
    private final List<Ship> bots = new ArrayList<>();
    private final List<Bolt> bolts = new ArrayList<>();
    private final List<Entity> decor = new ArrayList<>();
    private final Map<UUID, List<TextDisplay>> sky = new HashMap<>();
    private Scoreboard board;
    private Objective side;
    private long tick, matchStart = System.currentTimeMillis();
    private boolean forced;
    private int botSeq;

    private final class Ship {
        Player p;                      // null for a pirate
        String name;
        int color;
        ItemDisplay body;
        TextDisplay tag;
        Vector pos = new Vector(), dir = new Vector(0, 0, 1);
        double speed = 0.8, hull = 100, shield = 60, heat, energy = 100, strafe;
        float roll;
        boolean alive, boosting;
        long lastHit, lastShot, jamUntil;
        int kills, deaths, respawn, cannon, outside, engine, launchIn = -1;
        Ship target;
        int retarget, breakOff;
        boolean allowDismount;
        final BossBar bar = BossBar.bossBar(Component.empty(), 1f, BossBar.Color.BLUE, BossBar.Overlay.NOTCHED_10);

        boolean bot() { return p == null; }
    }

    private static final class Bolt {
        Vector pos, vel;
        Ship owner;
        int life, color;
        BlockDisplay display;
    }

    StarFight(NeyroHub plugin, World hub) {
        this.plugin = plugin;
        this.w = hub;
        this.itemKey = new NamespacedKey(plugin, "starfight_item");
    }

    // ------------------------------------------------------------ lifecycle

    void start() {
        for (int cx = (SX - 40) >> 4; cx <= (SX + 40) >> 4; cx++)
            for (int cz = (SZ - 40) >> 4; cz <= (SZ + 40) >> 4; cz++) w.setChunkForceLoaded(cx, cz, true);
        File marker = new File(plugin.getDataFolder(), "starfight-built-v" + BUILD);
        if (!marker.exists()) {
            buildStation();
            buildAsteroids(() -> { try { Files.writeString(marker.toPath(), "built"); } catch (IOException ignored) { } });
        }
        board = Bukkit.getScoreboardManager().getNewScoreboard();
        side = board.registerNewObjective("starfight", Criteria.DUMMY,
                Component.text("⚔ КОСМИЧЕСКИЕ БОИ", TextColor.color(0x7FE8FF)).decorate(TextDecoration.BOLD));
        side.setDisplaySlot(DisplaySlot.SIDEBAR);
        spawnDecor();
        Bukkit.getScheduler().runTaskTimer(plugin, this::tickAll, 20L, 1L);
    }

    void shutdown() {
        for (Ship s : new ArrayList<>(pilots.values())) leave(s.p, false);
        for (Ship b : bots) removeBody(b);
        bots.clear();
        for (Bolt b : bolts) if (b.display != null && b.display.isValid()) b.display.remove();
        bolts.clear();
        for (Entity e : decor) if (e.isValid()) e.remove();
        decor.clear();
        for (List<TextDisplay> l : sky.values()) for (TextDisplay d : l) if (d.isValid()) d.remove();
        sky.clear();
        forceArena(false);
    }

    boolean inGame(Player p) { return pilots.containsKey(p.getUniqueId()); }

    boolean contains(Location l) {
        if (l == null || !w.equals(l.getWorld())) return false;
        double dx = l.getX() - SX, dz = l.getZ() - SZ;
        return dx * dx + dz * dz < (R + 80) * (R + 80);
    }

    int online() { return pilots.size(); }

    Location deck() { return new Location(w, SX + 0.5, SY + 1, SZ - 6.5, 0f, 0f); }

    private void forceArena(boolean on) {
        if (forced == on) return;
        forced = on;
        for (int cx = (SX - R) >> 4; cx <= (SX + R) >> 4; cx++)
            for (int cz = (SZ - R) >> 4; cz <= (SZ + R) >> 4; cz++) {
                if (Math.abs(cx - (SX >> 4)) <= 3 && Math.abs(cz - (SZ >> 4)) <= 3) continue;   // the station stays loaded
                w.setChunkForceLoaded(cx, cz, on);
            }
    }

    // ------------------------------------------------------------ building

    private void set(int x, int y, int z, Material m) { w.getBlockAt(x, y, z).setType(m, false); }

    private void buildStation() {
        // a round launch deck with a light ring, a hangar arch facing the launch lane (+Z) and pylons
        for (int x = -18; x <= 18; x++)
            for (int z = -18; z <= 18; z++) {
                double r = Math.hypot(x, z);
                if (r > 17.5) continue;
                set(SX + x, SY, SZ + z, Math.abs(r - 12) < 0.6 ? Material.SEA_LANTERN : (x + z) % 2 == 0 ? Material.POLISHED_DEEPSLATE : Material.DEEPSLATE_TILES);
                int depth = (int) Math.round(Math.pow(Math.max(0, 1 - r / 17.5), 0.7) * 9);
                for (int d = 1; d <= depth; d++) set(SX + x, SY - d, SZ + z, d == depth ? Material.SEA_LANTERN : Material.POLISHED_DEEPSLATE);
                if (r > 16.6) { set(SX + x, SY + 1, SZ + z, Material.POLISHED_DEEPSLATE_WALL); if ((x + z) % 4 == 0) set(SX + x, SY + 2, SZ + z, Material.PEARLESCENT_FROGLIGHT); }
            }
        // the launch lane: a gap in the wall and a glowing runway
        for (int x = -3; x <= 3; x++) for (int z = 9; z <= 18; z++) { set(SX + x, SY + 1, SZ + z, Material.AIR); set(SX + x, SY + 2, SZ + z, Material.AIR); if (Math.abs(x) == 3) set(SX + x, SY, SZ + z, Material.SEA_LANTERN); }
        for (int y = 1; y <= 9; y++) for (int x : new int[]{-5, 5}) set(SX + x, SY + y, SZ + 12, y % 3 == 0 ? Material.SEA_LANTERN : Material.POLISHED_BLACKSTONE_BRICKS);
        for (int x = -5; x <= 5; x++) set(SX + x, SY + 10, SZ + 12, x % 2 == 0 ? Material.SEA_LANTERN : Material.POLISHED_BLACKSTONE_BRICKS);
        for (int i = 0; i < 6; i++) {
            double a = Math.PI / 6 + i * Math.PI / 3;
            int px = (int) Math.round(Math.cos(a) * 15), pz = (int) Math.round(Math.sin(a) * 15);
            if (pz > 8 && Math.abs(px) < 7) continue;
            for (int y = 1; y <= 12; y++) set(SX + px, SY + y, SZ + pz, y == 12 ? Material.END_ROD : y % 4 == 0 ? Material.SEA_LANTERN : Material.POLISHED_DEEPSLATE);
        }
    }

    /** Seventy asteroids of stone, deepslate and crystal, built a few per tick. */
    private void buildAsteroids(Runnable done) {
        Random r = new Random(42);
        List<double[]> list = new ArrayList<>();
        while (list.size() < 72) {
            double a = r.nextDouble() * Math.PI * 2, d = 50 + r.nextDouble() * (R - 60);
            double x = SX + Math.cos(a) * d, z = SZ + Math.sin(a) * d, y = 70 + r.nextDouble() * 220;
            double rad = list.size() < 4 ? 16 + r.nextDouble() * 8 : 3 + Math.pow(r.nextDouble(), 1.6) * 10;
            list.add(new double[]{x, y, z, rad, r.nextInt(100)});
        }
        plugin.getLogger().info("StarFight: building " + list.size() + " asteroids...");
        int[] i = {0};
        Bukkit.getScheduler().runTaskTimer(plugin, task -> {
            for (int k = 0; k < 2 && i[0] < list.size(); k++) asteroid(list.get(i[0]++));
            if (i[0] >= list.size()) { task.cancel(); plugin.getLogger().info("StarFight: asteroids built"); done.run(); }
        }, 5L, 1L);
    }

    private static int hash(int x, int y, int z) {
        int h = x * 73856093 ^ y * 19349663 ^ z * 83492791;
        h ^= (h >>> 13); h *= 0x5bd1e995;
        return Math.floorMod(h ^ (h >>> 15), 1000);
    }

    private void asteroid(double[] a) {
        int cx = (int) a[0], cy = (int) a[1], cz = (int) a[2], seed = (int) a[4];
        double rad = a[3];
        boolean crystal = seed < 8, dark = seed > 60;
        int ri = (int) Math.ceil(rad * 1.3);
        for (int x = -ri; x <= ri; x++)
            for (int y = -ri; y <= ri; y++)
                for (int z = -ri; z <= ri; z++) {
                    double d = Math.sqrt(x * x + y * y * 1.25 + z * z);
                    double bump = 1 + 0.22 * Math.sin(x * 0.7 + seed) * Math.cos(z * 0.6 - seed) + 0.12 * Math.sin(y * 0.9 + seed * 2);
                    if (d > rad * bump) continue;
                    int h = hash(cx + x, cy + y, cz + z);
                    Material m;
                    if (crystal) m = h < 150 ? Material.AMETHYST_BLOCK : h < 180 ? Material.BUDDING_AMETHYST : h < 600 ? Material.CALCITE : Material.SMOOTH_BASALT;
                    else if (dark) m = h < 15 ? Material.DEEPSLATE_DIAMOND_ORE : h < 50 ? Material.DEEPSLATE_IRON_ORE : h < 400 ? Material.COBBLED_DEEPSLATE : h < 800 ? Material.DEEPSLATE : Material.BLACKSTONE;
                    else m = h < 30 ? Material.IRON_ORE : h < 45 ? Material.GOLD_ORE : h < 400 ? Material.STONE : h < 700 ? Material.ANDESITE : h < 900 ? Material.TUFF : Material.COBBLESTONE;
                    set(cx + x, cy + y, cz + z, m);
                }
    }

    private void spawnDecor() {
        decor.add(w.spawn(new Location(w, SX + 0.5, SY + 7, SZ - 14.5, 0f, 0f), TextDisplay.class, t -> {
            plugin.prepare(t);
            t.setBillboard(Display.Billboard.CENTER);
            t.setBackgroundColor(Color.fromARGB(140, 6, 10, 30));
            t.setShadowed(true);
            t.setTransformation(scale(2.6f));
            t.text(Component.text()
                    .append(Component.text("⚔ КОСМИЧЕСКИЕ БОИ ⚔", TextColor.color(0x7FE8FF)).decorate(TextDecoration.BOLD)).append(Component.newline())
                    .append(Component.text("станция «Аврора» · истребители NX-2 «Стриж»", NamedTextColor.GRAY)).append(Component.newline())
                    .append(Component.text("ПКМ истребителем в руке — на взлёт", TextColor.color(0xFFD54A))).build());
        }));
        // four parked fighters on the deck
        for (int i = 0; i < 4; i++) {
            final int c = i;
            double a = Math.PI * 0.75 + i * Math.PI * 0.5 / 3;
            Location at = new Location(w, SX + 0.5 + Math.cos(a) * 9, SY + 1.9, SZ + 0.5 + Math.sin(a) * 9 - 2, (float) Math.toDegrees(a) + 90, 0f);
            decor.add(w.spawn(at, ItemDisplay.class, d -> {
                plugin.prepare(d);
                d.setItemStack(model(c));
                d.setItemDisplayTransform(ItemDisplay.ItemDisplayTransform.FIXED);
                d.setTransformation(scale(3.2f));
            }));
        }
    }

    private static Transformation scale(float s) {
        return new Transformation(new Vector3f(), new Quaternionf(), new Vector3f(s, s, s), new Quaternionf());
    }

    private static ItemStack model(int color) {
        ItemStack it = new ItemStack(Material.PAPER);
        ItemMeta m = it.getItemMeta();
        m.setItemModel(new NamespacedKey("neyrocraft", "fighter_" + COLOR[color]));
        it.setItemMeta(m);
        return it;
    }

    // ------------------------------------------------------------ joining

    static final WarpTunnel.Style STYLE = new WarpTunnel.Style(0x7FE8FF, 0xFF4A3A, 0xE0F6FF, "станция «Аврора»",
            BeamTransfer.title(Component.text("КОСМИЧЕСКИЕ БОИ", TextColor.color(0x7FE8FF)).decorate(TextDecoration.BOLD),
                    Component.text("станция «Аврора» · бой до " + WIN_KILLS + " побед", NamedTextColor.GRAY)));

    void send(Player p) {
        WarpTunnel.run(p, deck(), STYLE, this::join);
    }

    void join(Player p) {
        if (!p.isOnline() || inGame(p)) return;
        plugin.vault().lock(p, GAME);
        p.setGameMode(GameMode.ADVENTURE);
        Ship s = new Ship();
        s.p = p;
        s.name = p.getName();
        s.color = pilots.size() % 4;
        pilots.put(p.getUniqueId(), s);
        p.setScoreboard(board);
        side.getScore(s.name).setScore(0);
        deckItems(p);
        p.showBossBar(s.bar);
        s.launchIn = 60;
        forceArena(true);
        msg(p, Component.text("Корабль летит туда, куда ты смотришь. W/S — газ, A/D — боковые двигатели, Пробел/Ctrl — форсаж.", NamedTextColor.GRAY));
        msg(p, Component.text("ЛКМ/ПКМ — лазеры (следи за перегревом). Щит восстанавливается, корпус — нет. Бой до " + WIN_KILLS + " сбитых.", NamedTextColor.GRAY));
        broadcast(Component.text().append(Component.text(p.getName(), TextColor.color(RGB[s.color]))).append(Component.text(" прибыл на станцию «Аврора»", NamedTextColor.GRAY)).build());
        fillBots();
    }

    void leave(Player p, boolean announce) {
        Ship s = pilots.remove(p.getUniqueId());
        if (s == null) return;
        s.allowDismount = true;
        removeBody(s);
        board.resetScores(s.name);
        if (p.isOnline()) {
            p.hideBossBar(s.bar);
            p.setScoreboard(Bukkit.getScoreboardManager().getMainScoreboard());
            plugin.vault().unlock(p, GAME);
        }
        List<TextDisplay> l = sky.remove(p.getUniqueId());
        if (l != null) for (TextDisplay d : l) if (d.isValid()) d.remove();
        if (announce) broadcast(Component.text(p.getName() + " покинул бой", NamedTextColor.GRAY));
        if (pilots.isEmpty()) {
            for (Ship b : bots) { removeBody(b); board.resetScores(b.name); }
            bots.clear();
            forceArena(false);
        } else fillBots();
    }

    private ItemStack item(Material m, String id, Component name, String... lore) {
        ItemStack it = new ItemStack(m);
        ItemMeta meta = it.getItemMeta();
        meta.displayName(name.decoration(TextDecoration.ITALIC, false));
        List<Component> l = new ArrayList<>();
        for (String s : lore) l.add(Component.text(s, NamedTextColor.GRAY).decoration(TextDecoration.ITALIC, false));
        meta.lore(l);
        meta.getPersistentDataContainer().set(itemKey, PersistentDataType.STRING, id);
        if (id.equals("ship")) meta.setItemModel(new NamespacedKey("neyrocraft", "fighter_blue"));
        it.setItemMeta(meta);
        return it;
    }

    private void deckItems(Player p) {
        p.getInventory().clear();
        p.getInventory().setItem(0, item(Material.PAPER, "ship", Component.text("Истребитель NX-2 «Стриж»", TextColor.color(0x7FE8FF)).decorate(TextDecoration.BOLD),
                "ПКМ — взлёт", "в полёте ЛКМ/ПКМ — лазеры"));
        p.getInventory().setItem(8, item(Material.AMETHYST_SHARD, "leave", Component.text("✖ На станцию «Орион»", TextColor.color(0xC08CFF)), "ПКМ — покинуть бой"));
        p.getInventory().setHeldItemSlot(0);
    }

    private String itemId(ItemStack it) {
        if (it == null || !it.hasItemMeta()) return null;
        return it.getItemMeta().getPersistentDataContainer().get(itemKey, PersistentDataType.STRING);
    }

    // ------------------------------------------------------------ ships

    private void launch(Ship s, Location at, Vector dir) {
        s.pos = at.toVector();
        s.dir = dir.clone().normalize();
        s.speed = 1.0;
        s.hull = 100; s.shield = 60; s.heat = 0; s.energy = 100;
        s.alive = true;
        s.allowDismount = false;
        spawnBody(s);
        if (s.p != null) {
            s.body.addPassenger(s.p);
            sound(s.p, "starfight.boost", 1f, 1f, true);
            s.p.showTitle(Title.title(Component.text("ВЗЛЁТ!", TextColor.color(0x7FE8FF)).decorate(TextDecoration.BOLD),
                    Component.text("найди и сбей врага", NamedTextColor.GRAY), Title.Times.times(Duration.ZERO, Duration.ofMillis(1200), Duration.ofMillis(300))));
        }
    }

    private void spawnBody(Ship s) {
        removeBody(s);
        Location at = loc(s);
        s.body = w.spawn(at, ItemDisplay.class, d -> {
            plugin.prepare(d);
            d.setViewRange(8f);
            d.setItemStack(model(s.color));
            d.setItemDisplayTransform(ItemDisplay.ItemDisplayTransform.FIXED);
            d.setTeleportDuration(2);
            d.setInterpolationDuration(2);
            d.setTransformation(bodyTransform(0));
        });
        if (s.bot()) {
            s.tag = w.spawn(at, TextDisplay.class, t -> {
                plugin.prepare(t);
                t.setBillboard(Display.Billboard.CENTER);
                t.setBackgroundColor(Color.fromARGB(90, 0, 0, 0));
                t.setTeleportDuration(2);
                t.setTransformation(new Transformation(new Vector3f(0, 2.2f, 0), new Quaternionf(), new Vector3f(1.4f, 1.4f, 1.4f), new Quaternionf()));
                t.text(Component.text("☠ " + s.name, TextColor.color(RGB[s.color])));
            });
        }
    }

    private Transformation bodyTransform(float roll) {
        Quaternionf q = new Quaternionf().rotateZ((float) Math.toRadians(roll));
        return new Transformation(new Vector3f(0, -0.45f, 0.1f), q, new Vector3f(4f, 4f, 4f), new Quaternionf());
    }

    private void removeBody(Ship s) {
        if (s.body != null && s.body.isValid()) { s.allowDismount = true; s.body.eject(); s.body.remove(); }
        if (s.tag != null && s.tag.isValid()) s.tag.remove();
        s.body = null; s.tag = null;
    }

    private Location loc(Ship s) {
        float yaw = (float) Math.toDegrees(Math.atan2(-s.dir.getX(), s.dir.getZ()));
        float pitch = (float) Math.toDegrees(-Math.asin(Math.max(-1, Math.min(1, s.dir.getY()))));
        return new Location(w, s.pos.getX(), s.pos.getY(), s.pos.getZ(), yaw, pitch);
    }

    private void fillBots() {
        int want = pilots.isEmpty() ? 0 : Math.max(0, 6 - pilots.size());
        while (bots.size() < want) {
            Ship b = new Ship();
            b.name = "Пират-" + (++botSeq);
            b.color = 2 + botSeq % 2;
            bots.add(b);
            side.getScore(b.name).setScore(0);
            b.respawn = 40 + rnd.nextInt(60);
        }
        while (bots.size() > want) { Ship b = bots.remove(bots.size() - 1); removeBody(b); board.resetScores(b.name); }
    }

    private List<Ship> all() {
        List<Ship> l = new ArrayList<>(pilots.values());
        l.addAll(bots);
        return l;
    }

    // ------------------------------------------------------------ the tick

    private void tickAll() {
        tick++;
        if (pilots.isEmpty()) return;
        for (Ship s : all()) {
            try {
                if (s.alive) fly(s); else if (s.bot()) { if (--s.respawn <= 0) respawnBot(s); }
                else if (s.launchIn >= 0) deckCountdown(s);
            } catch (RuntimeException ex) {
                plugin.getLogger().warning("StarFight: " + s.name + ": " + ex);
            }
        }
        tickBolts();
        for (Ship s : pilots.values()) { hud(s); skyFor(s.p); }
        if (tick % 20 == 0) checkMatch();
    }

    private void deckCountdown(Ship s) {
        Player p = s.p;
        if (p.getLocation().getY() < SY - 25) { p.teleport(deck()); p.setFallDistance(0); }
        if (s.launchIn % 20 == 0 && s.launchIn > 0)
            HudBoard.notice(p, Component.text("Взлёт через " + s.launchIn / 20 + "…  (ПКМ истребителем — сразу)", TextColor.color(0x7FE8FF)));
        if (--s.launchIn <= 0) { s.launchIn = -1; launchFromDeck(s); }
    }

    private void launchFromDeck(Ship s) {
        if (s.alive || !s.p.isOnline()) return;
        Location at = new Location(w, SX + 0.5 + (rnd.nextDouble() - 0.5) * 4, SY + 4, SZ + 16);
        launch(s, at, new Vector(0, 0.15, 1));
        deckItemsFlying(s.p);
    }

    private void deckItemsFlying(Player p) {
        p.getInventory().setItem(0, item(Material.PAPER, "ship", Component.text("Пульт управления", TextColor.color(0x7FE8FF)).decorate(TextDecoration.BOLD),
                "ЛКМ/ПКМ — лазеры", "Пробел/Ctrl — форсаж"));
        p.getInventory().setHeldItemSlot(0);
    }

    private void respawnBot(Ship b) {
        double a = rnd.nextDouble() * Math.PI * 2;
        Player near = null;
        for (Ship s : pilots.values()) if (s.p.isOnline()) { near = s.p; break; }
        Vector base = near != null ? near.getLocation().toVector() : new Vector(SX, SY, SZ);
        Vector at = base.clone().add(new Vector(Math.cos(a) * 90, (rnd.nextDouble() - 0.3) * 40, Math.sin(a) * 90));
        clampToArena(at);
        launch(b, at.toLocation(w), base.clone().subtract(at));
    }

    private void clampToArena(Vector v) {
        double dx = v.getX() - SX, dz = v.getZ() - SZ, d = Math.hypot(dx, dz);
        if (d > R - 20) { v.setX(SX + dx / d * (R - 20)); v.setZ(SZ + dz / d * (R - 20)); }
        v.setY(Math.max(40, Math.min(300, v.getY())));
    }

    private static Vector turn(Vector dir, Vector want, double maxRad) {
        Vector d = dir.clone().normalize(), t = want.clone().normalize();
        double dot = Math.max(-1, Math.min(1, d.dot(t)));
        double ang = Math.acos(dot);
        if (ang <= maxRad || ang < 1e-6) return t;
        Vector perp = t.clone().subtract(d.clone().multiply(dot));
        if (perp.lengthSquared() < 1e-9) perp = new Vector(0, 1, 0).crossProduct(d);
        perp.normalize();
        return d.multiply(Math.cos(maxRad)).add(perp.multiply(Math.sin(maxRad))).normalize();
    }

    private void fly(Ship s) {
        Vector want;
        boolean fw = false, bw = false, left = false, right = false, boostKey = false;
        if (s.p != null) {
            if (!s.p.isOnline() || s.body == null || !s.body.isValid()) { leave(s.p, false); return; }
            if (!s.body.getPassengers().contains(s.p)) s.body.addPassenger(s.p);
            want = s.p.getLocation().getDirection();
            Input in = s.p.getCurrentInput();
            fw = in.isForward(); bw = in.isBackward(); left = in.isLeft(); right = in.isRight(); boostKey = in.isJump() || in.isSprint();
        } else {
            want = botSteer(s);
            fw = true;
            boostKey = s.breakOff > 0 && rnd.nextInt(3) == 0;
        }
        // keep inside the arena
        double dx = s.pos.getX() - SX, dz = s.pos.getZ() - SZ, dist = Math.hypot(dx, dz);
        boolean out = dist > R || s.pos.getY() < 30 || s.pos.getY() > 312;
        if (out) {
            s.outside++;
            Vector home = new Vector(SX, SY, SZ).subtract(s.pos);
            if (s.bot()) want = home;
            if (s.p != null && s.outside % 40 == 1)
                s.p.showTitle(Title.title(Component.empty(), Component.text("⚠ Ты покидаешь зону боя — разворачивайся!", NamedTextColor.RED),
                        Title.Times.times(Duration.ZERO, Duration.ofMillis(1500), Duration.ofMillis(200))));
            if (s.outside > 120 && s.outside % 20 == 0) damage(s, null, 10, false);
        } else s.outside = 0;

        // boost and speed
        boolean boost = boostKey && s.energy > 3;
        if (boost && !s.boosting) sound3d(s, "starfight.boost", 1f, 1f);
        s.boosting = boost;
        if (boost) s.energy -= 1.1; else s.energy = Math.min(100, s.energy + 0.35);
        double max = boost ? BOOST : MAX;
        if (fw) s.speed = Math.min(max, s.speed + (boost ? ACC * 2 : ACC));
        else if (bw) s.speed = Math.max(MIN, s.speed - ACC * 1.4);
        if (s.speed > max) s.speed = Math.max(max, s.speed - 0.04);

        // steering: slower turns at high speed
        double rate = Math.toRadians((s.bot() ? 3.4 : 4.8) - (s.speed > MAX ? 1.4 : 0));
        Vector before = s.dir.clone();
        s.dir = turn(s.dir, want, rate);
        Vector rightV = s.dir.clone().crossProduct(new Vector(0, 1, 0));
        if (rightV.lengthSquared() < 1e-6) rightV = new Vector(1, 0, 0);
        rightV.normalize();
        double strafeWant = s.p != null ? (right ? 0.3 : 0) - (left ? 0.3 : 0) : 0;
        s.strafe += (strafeWant - s.strafe) * 0.3;

        // move with collision against asteroids and the station
        Vector move = s.dir.clone().multiply(s.speed).add(rightV.clone().multiply(s.strafe));
        double len = move.length();
        int steps = Math.max(1, (int) Math.ceil(len / 0.6));
        for (int i = 0; i < steps; i++) {
            Vector next = s.pos.clone().add(move.clone().multiply(1.0 / steps));
            if (solid(next)) { crash(s); break; }
            s.pos = next;
        }
        if (!s.alive) return;

        // banking: roll into the turn
        double yawNow = Math.atan2(-s.dir.getX(), s.dir.getZ()), yawBefore = Math.atan2(-before.getX(), before.getZ());
        double dyaw = Math.toDegrees(yawNow - yawBefore);
        if (dyaw > 180) dyaw -= 360; if (dyaw < -180) dyaw += 360;
        float rollWant = (float) Math.max(-50, Math.min(50, dyaw * 9 + s.strafe * 60));
        s.roll += (rollWant - s.roll) * 0.25f;
        Location at = loc(s);
        s.body.teleport(at, TeleportFlag.EntityState.RETAIN_PASSENGERS);
        s.body.setInterpolationDelay(0);
        s.body.setTransformation(bodyTransform(s.roll));
        if (s.tag != null && s.tag.isValid()) s.tag.teleport(at);

        // engines: glow trail and the engine grain sound
        Vector back = s.pos.clone().subtract(s.dir.clone().multiply(1.6));
        w.spawnParticle(Particle.DUST, back.getX(), back.getY() - 0.4, back.getZ(), 2, 0.1, 0.1, 0.1, 0,
                new Particle.DustOptions(Color.fromRGB(boost ? 0xFFB060 : 0x8AD8FF), boost ? 1.8f : 1.2f));
        if (boost) w.spawnParticle(Particle.END_ROD, back.getX(), back.getY() - 0.4, back.getZ(), 1, 0.05, 0.05, 0.05, 0.01);
        if ((tick + s.name.hashCode()) % 5 == 0) {
            float pitch = (float) Math.max(0.6, Math.min(1.6, 0.65 + s.speed * 0.35));
            w.playSound(Sound.sound(Key.key("neyrocraft", "starfight.engine"), Sound.Source.NEUTRAL, (s.bot() ? 0.5f : 0.35f) * pitch, pitch), s.body);
        }

        // shields, heat
        long now = System.currentTimeMillis();
        if (now - s.lastHit > 3000) s.shield = Math.min(60, s.shield + 0.6);
        s.heat = Math.max(0, s.heat - 1.6);
        if (s.bot()) botFire(s);
    }

    private boolean solid(Vector v) {
        Block b = w.getBlockAt(v.getBlockX(), v.getBlockY(), v.getBlockZ());
        if (!b.isPassable()) return true;
        Block below = w.getBlockAt(v.getBlockX(), v.getBlockY() - 1, v.getBlockZ());
        return !below.isPassable() && v.getY() - v.getBlockY() < 0.4;
    }

    private void crash(Ship s) {
        w.spawnParticle(Particle.ELECTRIC_SPARK, s.pos.getX(), s.pos.getY(), s.pos.getZ(), 40, 0.8, 0.5, 0.8, 0.4);
        w.spawnParticle(Particle.SMOKE, s.pos.getX(), s.pos.getY(), s.pos.getZ(), 20, 0.6, 0.4, 0.6, 0.05);
        sound3d(s, "starfight.hit", 1f, 0.6f);
        s.dir = s.dir.clone().multiply(-1).add(new Vector(0, 0.6, 0)).normalize();
        s.pos.add(s.dir.clone().multiply(1.5));
        double hit = 18 + s.speed * 18;
        s.speed = 0.4;
        damage(s, null, hit, true);
        if (s.p != null && s.alive) HudBoard.notice(s.p, Component.text("💥 Столкновение!", NamedTextColor.RED));
    }

    // ------------------------------------------------------------ pirates

    private Vector botSteer(Ship b) {
        if (--b.retarget <= 0 || b.target == null || !b.target.alive) {
            b.retarget = 40;
            Ship best = null; double bd = Double.MAX_VALUE;
            for (Ship o : all()) {
                if (o == b || !o.alive) continue;
                double d = o.pos.distanceSquared(b.pos) * (o.bot() ? 2.5 : 1);     // prefer real pilots
                if (d < bd) { bd = d; best = o; }
            }
            b.target = best;
        }
        Vector want;
        if (b.target == null) want = new Vector(SX, SY, SZ).subtract(b.pos);
        else {
            double d = b.target.pos.distance(b.pos);
            Vector lead = b.target.pos.clone().add(b.target.dir.clone().multiply(b.target.speed * d / BOLT_SPEED));
            want = lead.subtract(b.pos);
            if (d < 14 && b.breakOff <= 0) b.breakOff = 30 + rnd.nextInt(20);
            if (b.breakOff > 0) {
                b.breakOff--;
                want = b.pos.clone().subtract(b.target.pos).add(new Vector(rnd.nextDouble() - 0.5, 0.6, rnd.nextDouble() - 0.5).multiply(20));
            }
        }
        // stay near the real pilots so the pirates never drift into unloaded space
        Ship nearest = null; double nd = Double.MAX_VALUE;
        for (Ship s : pilots.values()) { double d = s.p.getLocation().toVector().distanceSquared(b.pos); if (d < nd) { nd = d; nearest = s; } }
        if (nearest != null && nd > 130 * 130) want = nearest.p.getLocation().toVector().subtract(b.pos);
        // dodge asteroids
        RayTraceResult ahead = w.rayTraceBlocks(b.pos.toLocation(w), b.dir, 16, FluidCollisionMode.NEVER, true);
        if (ahead != null) want = b.dir.clone().add(new Vector(0, 1.2, 0)).add(b.dir.clone().crossProduct(new Vector(0, 1, 0)).multiply(0.8));
        return want;
    }

    private void botFire(Ship b) {
        if (b.target == null || !b.target.alive) return;
        Vector to = b.target.pos.clone().subtract(b.pos);
        double d = to.length();
        if (d > 95 || d < 6) return;
        double ang = Math.toDegrees(Math.acos(Math.max(-1, Math.min(1, to.normalize().dot(b.dir)))));
        if (ang < 7 && tick % 7 == 0 && rnd.nextInt(3) > 0) fire(b, b.pos.clone().add(b.dir.clone().multiply(80)).add(new Vector(rnd.nextGaussian(), rnd.nextGaussian(), rnd.nextGaussian()).multiply(1.8)));
    }

    // ------------------------------------------------------------ lasers

    private void fire(Ship s, Vector aim) {
        long now = System.currentTimeMillis();
        if (!s.alive || now - s.lastShot < (s.bot() ? 330 : 220) || now < s.jamUntil) return;
        s.lastShot = now;
        s.heat += 11;
        if (s.heat >= 100) {
            s.heat = 100;
            s.jamUntil = now + 2000;
            if (s.p != null) { s.p.showTitle(Title.title(Component.empty(), Component.text("⚠ ПЕРЕГРЕВ ПУШЕК ⚠", NamedTextColor.RED).decorate(TextDecoration.BOLD),
                    Title.Times.times(Duration.ZERO, Duration.ofMillis(900), Duration.ofMillis(200)))); sound(s.p, "starfight.alarm", 0.8f, 1.3f, true); }
        }
        Vector right = s.dir.clone().crossProduct(new Vector(0, 1, 0));
        if (right.lengthSquared() < 1e-6) right = new Vector(1, 0, 0);
        right.normalize();
        s.cannon = 1 - s.cannon;
        Vector muzzle = s.pos.clone().add(right.multiply(s.cannon == 0 ? 2.6 : -2.6)).add(s.dir.clone().multiply(2.2)).add(new Vector(0, -0.4, 0));
        Vector v = aim.clone().subtract(muzzle).normalize().multiply(BOLT_SPEED);
        Bolt b = new Bolt();
        b.pos = muzzle; b.vel = v; b.owner = s; b.life = 32; b.color = RGB[s.color];
        float yaw = (float) Math.toDegrees(Math.atan2(-v.getX(), v.getZ()));
        float pitch = (float) Math.toDegrees(-Math.asin(Math.max(-1, Math.min(1, v.clone().normalize().getY()))));
        Material glow = s.color == 0 ? Material.RED_CONCRETE : s.color == 1 ? Material.LIGHT_BLUE_CONCRETE : s.color == 2 ? Material.LIME_CONCRETE : Material.YELLOW_CONCRETE;
        b.display = w.spawn(new Location(w, muzzle.getX(), muzzle.getY(), muzzle.getZ(), yaw, pitch), BlockDisplay.class, d -> {
            plugin.prepare(d);
            d.setBlock(glow.createBlockData());
            d.setBrightness(new Display.Brightness(15, 15));
            d.setTeleportDuration(1);
            d.setGlowing(true);
            d.setGlowColorOverride(Color.fromRGB(b.color));
            d.setTransformation(new Transformation(new Vector3f(-0.06f, -0.06f, -1.6f), new Quaternionf(), new Vector3f(0.12f, 0.12f, 3.2f), new Quaternionf()));
        });
        bolts.add(b);
        w.playSound(Sound.sound(Key.key("neyrocraft", "starfight.laser"), Sound.Source.PLAYER, s.bot() ? 1.2f : 1f, 0.95f + rnd.nextFloat() * 0.1f), muzzle.getX(), muzzle.getY(), muzzle.getZ());
    }

    private void tickBolts() {
        for (Bolt b : new ArrayList<>(bolts)) {
            Vector from = b.pos.clone(), to = b.pos.clone().add(b.vel);
            boolean done = --b.life <= 0;
            // ships
            Ship hit = null; double best = Double.MAX_VALUE;
            for (Ship s : all()) {
                if (!s.alive || s == b.owner) continue;
                double d = segDist(s.pos, from, to);
                if (d < HIT_R) { double t = s.pos.distanceSquared(from); if (t < best) { best = t; hit = s; } }
            }
            if (hit != null) {
                damage(hit, b.owner, 14, false);
                done = true;
            } else {
                RayTraceResult r = w.rayTraceBlocks(from.toLocation(w), b.vel.clone().normalize(), b.vel.length(), FluidCollisionMode.NEVER, true);
                if (r != null) {
                    Vector at = r.getHitPosition();
                    w.spawnParticle(Particle.DUST, at.getX(), at.getY(), at.getZ(), 10, 0.2, 0.2, 0.2, 0, new Particle.DustOptions(Color.fromRGB(b.color), 1.4f));
                    w.spawnParticle(Particle.SMOKE, at.getX(), at.getY(), at.getZ(), 4, 0.1, 0.1, 0.1, 0.02);
                    done = true;
                }
            }
            if (done) { if (b.display != null && b.display.isValid()) b.display.remove(); bolts.remove(b); continue; }
            b.pos = to;
            if (b.display != null && b.display.isValid()) {
                Location l = b.display.getLocation();
                b.display.teleport(new Location(w, to.getX(), to.getY(), to.getZ(), l.getYaw(), l.getPitch()));
            }
        }
    }

    private static double segDist(Vector p, Vector a, Vector b) {
        Vector ab = b.clone().subtract(a);
        double t = Math.max(0, Math.min(1, p.clone().subtract(a).dot(ab) / Math.max(1e-9, ab.lengthSquared())));
        return a.clone().add(ab.multiply(t)).distance(p);
    }

    // ------------------------------------------------------------ damage

    private void damage(Ship s, Ship by, double amount, boolean crash) {
        if (!s.alive) return;
        s.lastHit = System.currentTimeMillis();
        boolean shieldHit = s.shield > 0 && !crash;
        if (shieldHit) {
            double take = Math.min(s.shield, amount);
            s.shield -= take; amount -= take;
            w.spawnParticle(Particle.DUST, s.pos.getX(), s.pos.getY(), s.pos.getZ(), 18, 1.4, 0.9, 1.4, 0, new Particle.DustOptions(Color.fromRGB(0x7FE8FF), 1.2f));
        }
        if (amount > 0) {
            s.hull -= amount;
            w.spawnParticle(Particle.ELECTRIC_SPARK, s.pos.getX(), s.pos.getY(), s.pos.getZ(), 14, 0.6, 0.4, 0.6, 0.3);
            w.spawnParticle(Particle.SMOKE, s.pos.getX(), s.pos.getY(), s.pos.getZ(), 6, 0.4, 0.3, 0.4, 0.03);
        }
        sound3d(s, "starfight.hit", 0.9f, shieldHit ? 1.4f : 0.9f);
        if (by != null && by.p != null) by.p.playSound(Sound.sound(Key.key("minecraft", "entity.experience_orb.pickup"), Sound.Source.PLAYER, 0.5f, 1.6f));
        if (s.hull <= 0) explode(s, by);
    }

    private void explode(Ship s, Ship by) {
        s.alive = false;
        s.deaths++;
        Vector p = s.pos;
        w.spawnParticle(Particle.EXPLOSION_EMITTER, p.getX(), p.getY(), p.getZ(), 1);
        w.spawnParticle(Particle.FLAME, p.getX(), p.getY(), p.getZ(), 60, 1.2, 1.2, 1.2, 0.15);
        w.spawnParticle(Particle.LAVA, p.getX(), p.getY(), p.getZ(), 20, 1, 1, 1, 0.1);
        w.spawnParticle(Particle.END_ROD, p.getX(), p.getY(), p.getZ(), 40, 0.5, 0.5, 0.5, 0.4);
        w.playSound(Sound.sound(Key.key("neyrocraft", "starfight.explode"), Sound.Source.PLAYER, 3f, 1f), p.getX(), p.getY(), p.getZ());
        w.playSound(Sound.sound(Key.key("minecraft", "entity.generic.explode"), Sound.Source.PLAYER, 1.5f, 0.8f), p.getX(), p.getY(), p.getZ());
        debris(s);
        removeBody(s);
        if (by != null && by != s) {
            by.kills++;
            side.getScore(by.name).setScore(by.kills);
            if (by.p != null) {
                if (plugin.starPass() != null) plugin.starPass().award(by.p, 10, "сбит истребитель");
                if (plugin.hud() != null) plugin.hud().add(by.p, "starfight.kills", 1);
                by.p.showTitle(Title.title(Component.empty(), Component.text("✦ Сбит: " + s.name, TextColor.color(0xFFD54A)).decorate(TextDecoration.BOLD),
                        Title.Times.times(Duration.ZERO, Duration.ofMillis(900), Duration.ofMillis(200))));
            }
            broadcast(Component.text().append(Component.text(by.name, TextColor.color(RGB[by.color]))).append(Component.text(" сбил ", NamedTextColor.GRAY))
                    .append(Component.text(s.name, TextColor.color(RGB[s.color]))).append(Component.text("  (" + by.kills + "/" + WIN_KILLS + ")", NamedTextColor.DARK_GRAY)).build());
        } else broadcast(Component.text(s.name + " разбился", NamedTextColor.GRAY));
        if (s.p != null) {
            Player pl = s.p;
            pl.teleport(deck());
            pl.showTitle(Title.title(Component.text("ВЫ СБИТЫ", NamedTextColor.RED).decorate(TextDecoration.BOLD),
                    Component.text(by != null && by != s ? "вас сбил " + by.name : "корабль разбился", NamedTextColor.GRAY), Title.Times.times(Duration.ZERO, Duration.ofMillis(2500), Duration.ofMillis(500))));
            deckItems(pl);
            s.launchIn = 80;
        } else s.respawn = 100;
        if (by != null && by.kills >= WIN_KILLS) endMatch(by);
    }

    /** Pieces of hull flying apart and fading. */
    private void debris(Ship s) {
        List<BlockDisplay> parts = new ArrayList<>();
        List<Vector> vel = new ArrayList<>();
        Material[] mats = {Material.LIGHT_GRAY_CONCRETE, Material.GRAY_CONCRETE, Material.BLACK_CONCRETE, Material.LIGHT_BLUE_STAINED_GLASS};
        for (int i = 0; i < 8; i++) {
            Material m = mats[i % mats.length];
            float sz = 0.3f + rnd.nextFloat() * 0.5f;
            parts.add(w.spawn(s.pos.toLocation(w), BlockDisplay.class, d -> {
                plugin.prepare(d);
                d.setBlock(m.createBlockData());
                d.setTeleportDuration(2);
                d.setTransformation(new Transformation(new Vector3f(-sz / 2), new Quaternionf().rotateXYZ(rnd.nextFloat() * 3, rnd.nextFloat() * 3, 0), new Vector3f(sz), new Quaternionf()));
            }));
            vel.add(new Vector(rnd.nextGaussian(), rnd.nextGaussian(), rnd.nextGaussian()).normalize().multiply(0.4 + rnd.nextDouble() * 0.5));
        }
        int[] t = {0};
        Bukkit.getScheduler().runTaskTimer(plugin, task -> {
            t[0]++;
            for (int i = 0; i < parts.size(); i++) {
                BlockDisplay d = parts.get(i);
                if (!d.isValid()) continue;
                d.teleport(d.getLocation().add(vel.get(i)));
                if (t[0] % 3 == 0) w.spawnParticle(Particle.SMOKE, d.getLocation(), 1, 0, 0, 0, 0.01);
            }
            if (t[0] > 40) { for (BlockDisplay d : parts) if (d.isValid()) d.remove(); task.cancel(); }
        }, 1L, 1L);
    }

    // ------------------------------------------------------------ match

    private void checkMatch() {
        if (System.currentTimeMillis() - matchStart > MATCH_MS) {
            Ship best = null;
            for (Ship s : all()) if (best == null || s.kills > best.kills) best = s;
            endMatch(best);
        }
    }

    private void endMatch(Ship winner) {
        Component title = winner == null ? Component.text("НИЧЬЯ", NamedTextColor.YELLOW).decorate(TextDecoration.BOLD)
                : Component.text(winner.name, TextColor.color(RGB[winner.color])).decorate(TextDecoration.BOLD);
        for (Ship s : pilots.values()) {
            Player p = s.p;
            p.showTitle(Title.title(title, Component.text(winner == null ? "бой окончен" : "— ас космоса! Новый бой…", NamedTextColor.GRAY),
                    Title.Times.times(Duration.ofMillis(300), Duration.ofMillis(3500), Duration.ofMillis(700))));
            p.playSound(Sound.sound(Key.key("minecraft", s == winner ? "ui.toast.challenge_complete" : "entity.firework_rocket.twinkle"), Sound.Source.MASTER, 0.8f, 1f));
            if (plugin.starPass() != null) plugin.starPass().award(p, s == winner ? 80 : 20, s == winner ? "победа в космическом бою" : "космический бой");
            if (s == winner && plugin.hud() != null) plugin.hud().add(p, "starfight.wins", 1);
        }
        for (Ship s : all()) { s.kills = 0; side.getScore(s.name).setScore(0); }
        matchStart = System.currentTimeMillis();
    }

    // ------------------------------------------------------------ HUD and sky

    private static String bar(double v, double max, int cells) {
        int full = (int) Math.round(Math.max(0, v) / max * cells);
        return "▮".repeat(Math.max(0, Math.min(cells, full))) + "▯".repeat(Math.max(0, cells - full));
    }

    private void hud(Ship s) {
        Player p = s.p;
        if (!s.alive) return;
        if (tick % 2 != 0) return;
        int kmh = (int) Math.round(s.speed * 20 * 3.6 * 6);
        TextComponent.Builder ab = Component.text()
                .append(Component.text("⚡ " + kmh + " км/ч  ", TextColor.color(s.boosting ? 0xFFB060 : 0x7FE8FF)).decorate(TextDecoration.BOLD))
                .append(Component.text("ЩИТ ", NamedTextColor.GRAY)).append(Component.text(bar(s.shield, 60, 6), TextColor.color(0x7FE8FF)))
                .append(Component.text("  КОРПУС ", NamedTextColor.GRAY)).append(Component.text(bar(s.hull, 100, 8), TextColor.color(s.hull < 30 ? 0xFF4040 : 0x9CFF6B)))
                .append(Component.text("  ПУШКИ ", NamedTextColor.GRAY)).append(Component.text(bar(100 - s.heat, 100, 5), TextColor.color(s.heat > 75 ? 0xFF7A30 : 0xFFD54A)))
                .append(Component.text("  ФОРСАЖ ", NamedTextColor.GRAY)).append(Component.text(bar(s.energy, 100, 5), TextColor.color(0xFFB060)));
        HudBoard.notice(p, ab.build());
        long left = Math.max(0, MATCH_MS - (System.currentTimeMillis() - matchStart)) / 1000;
        s.bar.name(Component.text()
                .append(Component.text("Сбито: " + s.kills + "/" + WIN_KILLS, TextColor.color(0xFFD54A)).decorate(TextDecoration.BOLD))
                .append(Component.text("   ⏱ " + left / 60 + ":" + String.format("%02d", left % 60), NamedTextColor.WHITE))
                .append(Component.text(s.target != null ? "" : "", NamedTextColor.GRAY)).build());
        s.bar.progress((float) Math.max(0, Math.min(1, s.hull / 100)));
        s.bar.color(s.hull < 30 ? BossBar.Color.RED : s.hull < 60 ? BossBar.Color.YELLOW : BossBar.Color.BLUE);
        if (s.hull < 30 && tick % 40 == 0) sound(p, "starfight.alarm", 0.6f, 1f, true);
    }

    // far planets and a sun: personal displays travelling with each pilot, so they look infinitely far away
    private static final double[][] SKY = {{-70, 30, -60, 70, 0xD9A066}, {-70, 30, -60, 105, -1}, {80, -10, 40, 34, 0xC0503A}, {30, 60, 70, 16, 0x9FD8FF}, {60, 15, -75, 22, 0xFFF0C0}};

    private void skyFor(Player p) {
        List<TextDisplay> l = sky.get(p.getUniqueId());
        Location eye = p.getEyeLocation();
        if (l == null) {
            l = new ArrayList<>();
            for (double[] m : SKY) {
                float sc = (float) m[3];
                boolean ring = m[4] < 0;
                TextDisplay d = w.spawn(eye.clone().add(m[0], m[1], m[2]), TextDisplay.class, t -> {
                    plugin.prepare(t);
                    t.setVisibleByDefault(false);
                    t.setViewRange(10f);
                    t.setBillboard(Display.Billboard.CENTER);
                    t.setBackgroundColor(Color.fromARGB(0, 0, 0, 0));
                    t.setShadowed(false);
                    t.setBrightness(new Display.Brightness(15, 15));
                    t.setTeleportDuration(1);
                    t.setTransformation(scale(sc));
                    t.text(Component.text(ring ? "◯" : "●", TextColor.color(ring ? 0xF0D8B0 : (int) m[4])));
                });
                p.showEntity(plugin, d);
                l.add(d);
            }
            sky.put(p.getUniqueId(), l);
        }
        for (int i = 0; i < l.size() && i < SKY.length; i++) {
            TextDisplay d = l.get(i);
            if (!d.isValid()) { sky.remove(p.getUniqueId()); return; }
            double[] m = SKY[i];
            d.teleport(eye.clone().add(m[0], m[1] - m[3] * 0.11, m[2]));
        }
    }

    // ------------------------------------------------------------ helpers

    private void sound(Player p, String key, float vol, float pitch, boolean custom) {
        p.playSound(Sound.sound(Key.key(custom ? "neyrocraft" : "minecraft", key), Sound.Source.PLAYER, vol, pitch));
    }

    private void sound3d(Ship s, String key, float vol, float pitch) {
        w.playSound(Sound.sound(Key.key("neyrocraft", key), Sound.Source.PLAYER, vol, pitch), s.pos.getX(), s.pos.getY(), s.pos.getZ());
    }

    private void msg(Player p, Component c) {
        p.sendMessage(Component.text().append(Component.text("[Бои] ", TextColor.color(0x7FE8FF)).decorate(TextDecoration.BOLD)).append(c).build());
    }

    private void broadcast(Component c) {
        for (Ship s : pilots.values()) msg(s.p, c);
    }

    // ------------------------------------------------------------ events

    @EventHandler(priority = EventPriority.HIGH)
    public void onSwing(PlayerAnimationEvent e) {
        if (e.getAnimationType() != PlayerAnimationType.ARM_SWING) return;
        Ship s = pilots.get(e.getPlayer().getUniqueId());
        if (s != null && s.alive) fire(s, e.getPlayer().getEyeLocation().toVector().add(e.getPlayer().getLocation().getDirection().multiply(80)));
    }

    @EventHandler(priority = EventPriority.HIGH)
    public void onUse(PlayerInteractEvent e) {
        Player p = e.getPlayer();
        Ship s = pilots.get(p.getUniqueId());
        if (s == null || e.getHand() != EquipmentSlot.HAND) return;
        if (e.getAction() == Action.PHYSICAL) return;
        e.setCancelled(true);
        String id = itemId(e.getItem());
        if ("leave".equals(id)) { leaveToOrion(p); return; }
        if (e.getAction() == Action.RIGHT_CLICK_AIR || e.getAction() == Action.RIGHT_CLICK_BLOCK) {
            if (s.alive) fire(s, p.getEyeLocation().toVector().add(p.getLocation().getDirection().multiply(80)));
            else if ("ship".equals(id)) { s.launchIn = -1; launchFromDeck(s); }
        }
    }

    private void leaveToOrion(Player p) {
        leave(p, true);
        Location o = plugin.orionSpawn();
        BeamTransfer.run(plugin, p, o != null ? o : plugin.spawn(), Color.fromRGB(0x7F, 0xE8, 0xFF), Color.fromRGB(0xC0, 0x8C, 0xFF),
                BeamTransfer.title(Component.text("СТАНЦИЯ «ОРИОН»", TextColor.color(0xC08CFF)).decorate(TextDecoration.BOLD),
                        Component.text("после космического боя", NamedTextColor.GRAY)));
    }

    @EventHandler(priority = EventPriority.HIGHEST)
    public void onDismount(EntityDismountEvent e) {
        if (!(e.getEntity() instanceof Player p)) return;
        Ship s = pilots.get(p.getUniqueId());
        if (s != null && s.alive && !s.allowDismount && e.getDismounted().equals(s.body)) e.setCancelled(true);
    }

    @EventHandler public void onDrop(PlayerDropItemEvent e) { if (inGame(e.getPlayer())) e.setCancelled(true); }
    @EventHandler public void onSwap(PlayerSwapHandItemsEvent e) { if (inGame(e.getPlayer())) e.setCancelled(true); }

    @EventHandler
    public void onQuit(PlayerQuitEvent e) {
        leave(e.getPlayer(), true);
    }
}
