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
import org.bukkit.Difficulty;
import org.bukkit.GameMode;
import org.bukkit.Input;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.Particle;
import org.bukkit.World;
import org.bukkit.WorldCreator;
import org.bukkit.block.Block;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.Display;
import org.bukkit.entity.Entity;
import org.bukkit.entity.ItemDisplay;
import org.bukkit.entity.Player;
import org.bukkit.entity.TextDisplay;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.block.Action;
import org.bukkit.event.entity.EntityDamageEvent;
import org.bukkit.event.entity.EntityDismountEvent;
import org.bukkit.event.entity.FoodLevelChangeEvent;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.player.PlayerChangedWorldEvent;
import org.bukkit.event.player.PlayerDropItemEvent;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerMoveEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.event.player.PlayerSwapHandItemsEvent;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.potion.PotionEffect;
import org.bukkit.potion.PotionEffectType;
import org.bukkit.util.Transformation;
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
import java.util.UUID;

/**
 * Speeder races on planet Terrax: time attack on a 1.6 km circuit, two laps.
 * Hover-bikes are ridden item displays driven by the plugin every tick:
 *   W — throttle, S — brake / reverse, mouse — steering, A/D — side thrusters,
 *   Space or Ctrl — boost (energy recharges), F — back onto the track.
 * Timer with checkpoint splits against the personal best, lap times, records board, StarPass stars.
 */
final class PlanetRace implements Listener {
    static final String WORLD = "neyro_terrax";
    static final String GAME = "race";
    private static final int ORANGE = 0xFFA040, SAND = 0xFF6A3D, CYAN = 0x6FE3FF, GOLD = 0xFFD54A;
    private static final int GEN_VERSION = 1;

    // driving model (blocks per tick)
    private static final double MAX = 1.3, BOOST_MAX = 2.0, OFFROAD_MAX = 0.6, REVERSE_MAX = 0.35;
    private static final double ACCEL = 0.05, BOOST_ACCEL = 0.09, BRAKE = 0.085, DRAG = 0.975;
    private static final double STRAFE = 0.28, HOVER = 0.85, STEP_UP = 1.1, GRAVITY = 0.055;
    private static final double KMH = 20 * 3.6 * 2.2;  // shown speed (speeder scale)

    private final NeyroHub plugin;
    private final RaceTrack track = new RaceTrack();
    private final int laps;
    private final float modelScale, modelUp, modelForward;
    private World world;
    private final Map<UUID, Racer> racers = new HashMap<>();
    private final Map<UUID, List<TextDisplay>> moons = new HashMap<>();
    private final List<Entity> fixtures = new ArrayList<>();
    private TextDisplay board;
    private final File recordsFile;
    private final YamlConfiguration records;
    private final NamespacedKey itemKey;
    private long tick;

    private enum State { GRID, RACING, DONE }

    private final class Racer {
        final Player p;
        ItemDisplay bike;
        TextDisplay lights;
        int lit = -1, lightsGone;
        State state = State.GRID;
        int countdown = 70;
        double x, y, z, v, vy, energy = 100;
        float yaw, roll, pitch;
        boolean boosting, airborne;
        int idx, lap, next, offTicks, wrongTicks, doneTicks, lastWarn, sounds, slot;
        double prog;
        long startNs, lapStartNs;
        long[] splits;
        long bestLapRun = Long.MAX_VALUE;
        final BossBar bar = BossBar.bossBar(Component.empty(), 0f, BossBar.Color.YELLOW, BossBar.Overlay.NOTCHED_12);
        boolean allowDismount;

        Racer(Player p) { this.p = p; }
    }

    PlanetRace(NeyroHub plugin) {
        this.plugin = plugin;
        this.laps = Math.max(1, plugin.getConfig().getInt("race.laps", 2));
        this.modelScale = (float) plugin.getConfig().getDouble("race.model-scale", 2.0);
        this.modelUp = (float) plugin.getConfig().getDouble("race.model-up", -0.05);
        this.modelForward = (float) plugin.getConfig().getDouble("race.model-forward", 0.56);
        this.itemKey = new NamespacedKey(plugin, "race_item");
        File dir = new File(plugin.getDataFolder(), "race");
        dir.mkdirs();
        this.recordsFile = new File(dir, "records.yml");
        this.records = YamlConfiguration.loadConfiguration(recordsFile);
    }

    // ------------------------------------------------------------ world

    void start() {
        world = new WorldCreator(WORLD).generator(new TerraxGenerator(track)).environment(World.Environment.NORMAL)
                .generateStructures(false).createWorld();
        if (world == null) { plugin.getLogger().warning("Planet Terrax could not be created"); return; }
        world.setDifficulty(Difficulty.PEACEFUL);
        world.setSpawnLocation(0, RaceTrack.ROAD_Y + 1, 24);
        world.setTime(10800);
        world.setStorm(false);
        world.setThundering(false);
        world.setClearWeatherDuration(Integer.MAX_VALUE);
        world.setKeepSpawnInMemory(false);
        for (String rule : new String[]{"advance_time", "advance_weather", "spawn_mobs"}) {
            try {
                Bukkit.dispatchCommand(Bukkit.getConsoleSender(), "execute in minecraft:" + WORLD + " run gamerule " + rule + " false");
            } catch (Exception e) {
                plugin.getLogger().warning("Terrax gamerule " + rule + ": " + e.getMessage());
            }
        }
        plugin.getLogger().info("Planet Terrax ready: circuit " + track.n + " m, " + laps + " laps");
        pregenerate();
        // keep the chunks with signs loaded (their displays are not saved)
        for (int c = 0; c < RaceTrack.CHECKPOINTS; c++) {
            int s = track.cps[c] % track.n;
            world.setChunkForceLoaded((int) Math.floor(track.x[s]) >> 4, (int) Math.floor(track.z[s]) >> 4, true);
        }
        for (int cx = -2; cx <= 1; cx++) for (int cz = -2; cz <= 2; cz++) world.setChunkForceLoaded(cx, cz, true);
        File startMarker = new File(plugin.getDataFolder(), "terrax-start-v" + START_VERSION);
        if (!startMarker.exists()) {
            buildStart();
            try { Files.writeString(startMarker.toPath(), "built"); } catch (IOException ignored) { }
        }
        spawnFixtures();
        Bukkit.getScheduler().runTaskTimer(plugin, this::tickAll, 1L, 1L);
    }

    private static final int START_VERSION = 2;

    /** Grid slots: sample behind the line and lateral offset (+ = right of the driving direction). */
    private int gridSample(int slot) {
        return Math.floorMod(-14 - (slot / 3) * 9, track.n);
    }

    private static double gridLateral(int slot) {
        return ((slot % 3) - 1) * 3.6;
    }

    private void put(int x, int y, int z, Material m) {
        world.getBlockAt(x, y, z).setType(m, false);
    }

    /**
     * The start: a proper gantry over the line (two towers, a chequered beam), the starting grid painted
     * on the road (five numbered boxes, noses pointing east) and chevrons showing the direction.
     */
    private void buildStart() {
        int fy = RaceTrack.ROAD_Y;
        // remove the old arch beam and pillars made by the generator
        for (int x = -3; x <= 3; x++)
            for (int z = -12; z <= 12; z++)
                for (int y = fy + 1; y <= fy + 16; y++) {
                    Material m = world.getBlockAt(x, y, z).getType();
                    if (m == Material.ORANGE_STAINED_GLASS || m == Material.OCHRE_FROGLIGHT || m == Material.POLISHED_BLACKSTONE_BRICKS || m == Material.END_ROD)
                        put(x, y, z, Material.AIR);
                }
        // towers
        for (int side : new int[]{-1, 1}) {
            for (int x = -1; x <= 1; x++)
                for (int dz = 9; dz <= 11; dz++) {
                    int z = side * dz;
                    for (int y = fy - 2; y <= fy + 14; y++) {
                        boolean corner = Math.abs(x) == 1 && (dz == 9 || dz == 11);
                        Material m = y <= fy ? Material.POLISHED_BLACKSTONE
                                : (y - fy) % 4 == 0 ? Material.OCHRE_FROGLIGHT
                                : corner ? Material.POLISHED_BLACKSTONE_BRICKS : Material.BLACK_CONCRETE;
                        put(x, y, z, m);
                    }
                    put(x, fy + 15, z, Math.abs(x) == 1 && (dz == 9 || dz == 11) ? Material.END_ROD : Material.POLISHED_BLACKSTONE_SLAB);
                }
        }
        // chequered beam across the track
        for (int x = -1; x <= 1; x++)
            for (int z = -8; z <= 8; z++) {
                put(x, fy + 12, z, x == 0 && z % 2 == 0 ? Material.SEA_LANTERN : Material.BLACK_CONCRETE);
                put(x, fy + 13, z, Math.floorMod(x + z, 2) == 0 ? Material.WHITE_CONCRETE : Material.BLACK_CONCRETE);
                put(x, fy + 14, z, Math.floorMod(x + z, 2) == 0 ? Material.BLACK_CONCRETE : Material.WHITE_CONCRETE);
                if (x == 0) put(x, fy + 15, z, Material.OCHRE_FROGLIGHT);
            }
        // the orange walkway from the pit moves beside the tower
        for (int x = -1; x <= 5; x++)
            for (int z = 9; z <= 29; z++) {
                if (z <= 11 && Math.abs(x) <= 1) continue;
                boolean walk = x >= 3 && x <= 5;
                Material floor = walk ? Material.ORANGE_CONCRETE
                        : ((x >> 1) + (z >> 1)) % 2 == 0 ? Material.SMOOTH_QUARTZ : Material.POLISHED_DIORITE;
                if (world.getBlockAt(x, fy, z).getType() != Material.SEA_LANTERN) put(x, fy, z, floor);
            }
        // starting grid: white bracket boxes, the front line is where the nose of the speeder goes
        for (int slot = 0; slot < 5; slot++) {
            int s = gridSample(slot);
            int sx = (int) Math.floor(track.x[s]);
            int cz = (int) Math.floor(track.z[s] + gridLateral(slot));
            for (int z = cz - 1; z <= cz + 1; z++) put(sx + 2, fy, z, Material.WHITE_CONCRETE);
            for (int x = sx - 2; x <= sx + 1; x++) {
                put(x, fy, cz - 1, Material.WHITE_CONCRETE);
                put(x, fy, cz + 1, Material.WHITE_CONCRETE);
            }
            put(sx, fy, cz, Material.YELLOW_CONCRETE);
        }
        // chevrons after the line: this way!
        for (int base : new int[]{8, 15, 22})
            for (int z = -2; z <= 2; z++)
                for (int t = 0; t < 2; t++) put(base + 2 - Math.abs(z) + t, fy, z, Material.WHITE_CONCRETE);
        plugin.getLogger().info("Terrax: start gantry and grid built");
    }

    /** Generates the whole circuit once (in the background), so nobody drives into ungenerated land. */
    private void pregenerate() {
        File marker = new File(plugin.getDataFolder(), "terrax-gen-v" + GEN_VERSION);
        if (marker.exists()) return;
        List<long[]> chunks = new ArrayList<>();
        for (int cx = (track.gx0 >> 4) - 2; cx <= ((track.gx0 + track.gw) >> 4) + 2; cx++)
            for (int cz = (track.gz0 >> 4) - 2; cz <= ((track.gz0 + track.gh) >> 4) + 2; cz++) chunks.add(new long[]{cx, cz});
        plugin.getLogger().info("Terrax: generating " + chunks.size() + " chunks of the circuit...");
        int[] pos = {0}, done = {0};
        Runnable[] pump = new Runnable[1];
        pump[0] = () -> {
            if (pos[0] >= chunks.size()) return;
            long[] c = chunks.get(pos[0]++);
            world.getChunkAtAsync((int) c[0], (int) c[1]).thenAccept(ch -> {
                done[0]++;
                if (done[0] % 200 == 0) plugin.getLogger().info("Terrax: " + done[0] + "/" + chunks.size());
                if (done[0] == chunks.size()) {
                    plugin.getLogger().info("Terrax: circuit generated");
                    try { Files.writeString(marker.toPath(), "generated"); } catch (IOException ignored) { }
                }
                pump[0].run();
            });
        };
        for (int i = 0; i < 6; i++) pump[0].run();
    }

    boolean in(World w) {
        return w != null && world != null && w.equals(world);
    }

    boolean in(Player p) {
        return in(p.getWorld());
    }

    boolean riding(Player p) {
        return racers.containsKey(p.getUniqueId());
    }

    int online() {
        return world == null ? 0 : world.getPlayers().size();
    }

    Location plaza() {
        return new Location(world, 0.5, RaceTrack.ROAD_Y + 1, 26.5, 180f, 5f);
    }

    void shutdown() {
        for (Racer r : new ArrayList<>(racers.values())) stopRacer(r);
        for (List<TextDisplay> l : moons.values()) for (TextDisplay d : l) if (d.isValid()) d.remove();
        moons.clear();
        for (Entity e : fixtures) if (e.isValid()) e.remove();
        fixtures.clear();
    }

    // ------------------------------------------------------------ going there

    static final WarpTunnel.Style STYLE = new WarpTunnel.Style(ORANGE, SAND, 0xFFE6B0, "планета Терракс",
            BeamTransfer.title(Component.text("ПЛАНЕТА ТЕРРАКС", TextColor.color(ORANGE)).decorate(TextDecoration.BOLD),
                    Component.text("гонки на спидерах · трасса 1,6 км", NamedTextColor.GRAY)));

    void send(Player p) {
        if (world == null) { p.sendMessage(Component.text("Планета Терракс сейчас недоступна", NamedTextColor.RED)); return; }
        WarpTunnel.run(p, plaza(), STYLE, this::welcome);
    }

    private void welcome(Player p) {
        if (!in(p)) return;
        p.sendMessage(Component.text()
                .append(Component.text("━━━━━━━━  ", TextColor.color(0x553322)))
                .append(Component.text("ГОНКИ НА СПИДЕРАХ", TextColor.color(ORANGE)).decorate(TextDecoration.BOLD))
                .append(Component.text("  ━━━━━━━━", TextColor.color(0x553322))).build());
        line(p, "W / S", "газ и тормоз");
        line(p, "Мышь", "руль — спидер поворачивает туда, куда ты смотришь");
        line(p, "A / D", "боковые двигатели");
        line(p, "Пробел или Ctrl", "ускоритель (энергия восстанавливается)");
        line(p, "F", "вернуться на трассу к последней контрольной точке");
        p.sendMessage(Component.text("  " + laps + " круга · " + RaceTrack.CHECKPOINTS + " контрольных арок · песок тормозит", NamedTextColor.GRAY));
        p.sendMessage(Component.text("  ПКМ спидером в руке — на старт!", TextColor.color(GOLD)));
    }

    private static void line(Player p, String key, String what) {
        p.sendMessage(Component.text()
                .append(Component.text("  " + key, TextColor.color(CYAN)).decorate(TextDecoration.BOLD))
                .append(Component.text(" — " + what, NamedTextColor.GRAY)).build());
    }

    /** Back to station Orion (the beam), things are given back as the player leaves the planet. */
    void leave(Player p) {
        Racer r = racers.get(p.getUniqueId());
        if (r != null) stopRacer(r);
        Location orion = plugin.orionSpawn();
        if (orion == null) { plugin.sendToHub(p, false); return; }
        BeamTransfer.run(plugin, p, orion, Color.fromRGB(ORANGE), Color.fromRGB(0xC0, 0x8C, 0xFF),
                BeamTransfer.title(Component.text("СТАНЦИЯ «ОРИОН»", TextColor.color(0xC08CFF)).decorate(TextDecoration.BOLD),
                        Component.text("возвращение с планеты Терракс", NamedTextColor.GRAY)));
    }

    // ------------------------------------------------------------ items

    private ItemStack item(Material m, String id, Component name, String... lore) {
        ItemStack it = new ItemStack(m);
        ItemMeta meta = it.getItemMeta();
        meta.displayName(name.decoration(TextDecoration.ITALIC, false));
        List<Component> l = new ArrayList<>();
        for (String s : lore) l.add(Component.text(s, NamedTextColor.GRAY).decoration(TextDecoration.ITALIC, false));
        meta.lore(l);
        meta.getPersistentDataContainer().set(itemKey, org.bukkit.persistence.PersistentDataType.STRING, id);
        if (id.equals("start") || id.equals("bike")) meta.setItemModel(new NamespacedKey("neyrocraft", "speeder"));
        it.setItemMeta(meta);
        return it;
    }

    private String itemId(ItemStack it) {
        if (it == null || !it.hasItemMeta()) return null;
        return it.getItemMeta().getPersistentDataContainer().get(itemKey, org.bukkit.persistence.PersistentDataType.STRING);
    }

    private void plazaItems(Player p) {
        p.getInventory().clear();
        p.getInventory().setItem(0, item(Material.PAPER, "start", Component.text("▶ Новый заезд", TextColor.color(GOLD)).decorate(TextDecoration.BOLD),
                "ПКМ — сесть в спидер и встать на старт"));
        p.getInventory().setItem(4, item(Material.GOLD_INGOT, "records", Component.text("🏆 Рекорды трассы", TextColor.color(GOLD)),
                "ПКМ — лучшие времена"));
        p.getInventory().setItem(8, item(Material.AMETHYST_SHARD, "leave", Component.text("✖ На станцию «Орион»", TextColor.color(0xC08CFF)),
                "ПКМ — вернуться на станцию"));
        p.getInventory().setHeldItemSlot(0);
    }

    private void raceItems(Player p) {
        p.getInventory().clear();
        p.getInventory().setItem(0, item(Material.PAPER, "bike", Component.text("Спидер", TextColor.color(ORANGE)).decorate(TextDecoration.BOLD),
                "W/S — газ/тормоз, мышь — руль", "Пробел/Ctrl — ускоритель, F — на трассу"));
        p.getInventory().setItem(7, item(Material.RECOVERY_COMPASS, "reset", Component.text("⟲ На трассу", TextColor.color(CYAN)),
                "ПКМ или F — к последней контрольной точке"));
        p.getInventory().setItem(8, item(Material.BARRIER, "quit", Component.text("✖ Выйти из заезда", NamedTextColor.RED),
                "ПКМ — сойти с дистанции"));
        p.getInventory().setHeldItemSlot(0);
    }

    // ------------------------------------------------------------ starting a run

    void startRun(Player p) {
        if (world == null || !in(p) || racers.containsKey(p.getUniqueId())) return;
        // free grid slot: three lanes, two rows behind the line
        int slot = 0;
        outer:
        for (; slot < 5; slot++) {
            for (Racer o : racers.values()) if (o.state == State.GRID && o.slot == slot) continue outer;
            break;
        }
        int s = gridSample(slot);
        double lat = gridLateral(slot);
        Racer r = new Racer(p);
        r.slot = slot;
        r.x = track.x[s] + -track.dirZ[s] * lat;
        r.z = track.z[s] + track.dirX[s] * lat;
        r.y = ground(r.x, r.z, track.y[s] + 3) + HOVER;
        r.yaw = track.yaw(s);
        r.idx = s;
        r.prog = -Math.floorMod(-s, track.n);          // just behind the line: negative progress
        if (r.prog < -track.n / 2.0) r.prog += track.n;
        r.splits = new long[laps * RaceTrack.CHECKPOINTS];
        racers.put(p.getUniqueId(), r);
        spawnBike(r);
        raceItems(p);
        p.showBossBar(r.bar);
        r.bar.name(Component.text("Приготовься...", TextColor.color(GOLD)));
        r.lights = world.spawn(new Location(world, -2.2, RaceTrack.ROAD_Y + 10.2, 0.5), TextDisplay.class, t -> {
            plugin.prepare(t);
            t.setVisibleByDefault(false);
            t.setViewRange(6f);
            t.setBillboard(Display.Billboard.VERTICAL);
            t.setBackgroundColor(Color.fromARGB(200, 10, 10, 10));
            t.setBrightness(new Display.Brightness(15, 15));
            t.setTransformation(scale(3.4f));
            t.text(lightsText(0, false));
        });
        p.showEntity(plugin, r.lights);
        sound(p, "block.beacon.activate", 0.7f, 1.6f);
    }

    private void spawnBike(Racer r) {
        Location at = new Location(world, r.x, r.y, r.z, r.yaw, 0);
        ItemStack model = new ItemStack(Material.PAPER);
        ItemMeta meta = model.getItemMeta();
        meta.setItemModel(new NamespacedKey("neyrocraft", "speeder"));
        model.setItemMeta(meta);
        r.bike = world.spawn(at, ItemDisplay.class, d -> {
            plugin.prepare(d);
            d.setViewRange(6f);
            d.setItemStack(model);
            d.setItemDisplayTransform(ItemDisplay.ItemDisplayTransform.FIXED);
            d.setTeleportDuration(2);
            d.setInterpolationDuration(2);
            d.setTransformation(bikeTransform(0, 0));
        });
        r.bike.addPassenger(r.p);
    }

    private Transformation bikeTransform(float roll, float pitch) {
        Quaternionf q = new Quaternionf().rotateZ((float) Math.toRadians(roll)).rotateX((float) Math.toRadians(pitch));
        return new Transformation(new Vector3f(0, modelUp, modelForward), q, new Vector3f(modelScale, modelScale, modelScale), new Quaternionf());
    }

    private void stopRacer(Racer r) {
        racers.remove(r.p.getUniqueId());
        r.allowDismount = true;
        if (r.bike != null && r.bike.isValid()) {
            r.bike.eject();
            r.bike.remove();
        }
        if (r.p.isOnline()) {
            r.p.hideBossBar(r.bar);
            if (r.lights != null && r.lights.isValid()) r.lights.remove();
            r.p.removePotionEffect(PotionEffectType.SPEED);
        }
    }

    /** Off the bike, back to the pit. */
    private void toPlaza(Racer r) {
        stopRacer(r);
        Player p = r.p;
        if (!p.isOnline() || !in(p)) return;
        p.teleport(plaza());
        plazaItems(p);
        p.sendActionBar(Component.empty());
    }

    // ------------------------------------------------------------ the tick

    private void tickAll() {
        if (world == null) return;
        tick++;
        for (Racer r : new ArrayList<>(racers.values())) {
            if (!r.p.isOnline() || !in(r.p) || r.bike == null || !r.bike.isValid()) { stopRacer(r); continue; }
            try {
                drive(r);
            } catch (RuntimeException ex) {
                plugin.getLogger().warning("Race tick failed for " + r.p.getName() + ": " + ex);
                stopRacer(r);
            }
        }
        for (Player p : world.getPlayers()) skyMoons(p);
        if (tick % 40 == 0) for (UUID id : new ArrayList<>(moons.keySet())) {
            Player p = Bukkit.getPlayer(id);
            if (p == null || !in(p)) dropMoons(id);
        }
    }

    private void drive(Racer r) {
        Player p = r.p;
        if (!r.bike.getPassengers().contains(p)) r.bike.addPassenger(p);
        Input in = p.getCurrentInput();
        boolean fw = in.isForward(), bw = in.isBackward(), boostKey = in.isJump() || in.isSprint();

        // ---- countdown on the grid
        if (r.state == State.GRID) {
            r.countdown--;
            int c = r.countdown;
            int litNow = Math.max(0, Math.min(5, (70 - c) / 13));
            if (c <= 0) litNow = 6;
            if (litNow != r.lit && r.lights != null && r.lights.isValid()) {
                r.lit = litNow;
                r.lights.text(lightsText(Math.min(5, litNow), litNow == 6));
            }
            if (c == 60 || c == 40 || c == 20) {
                int n = c / 20;
                TextColor col = TextColor.color(n == 3 ? 0xFF4040 : n == 2 ? 0xFFA040 : 0xFFE040);
                p.showTitle(Title.title(Component.text(String.valueOf(n), col).decorate(TextDecoration.BOLD),
                        Component.text("● ".repeat(4 - n).trim(), col), Title.Times.times(Duration.ZERO, Duration.ofMillis(900), Duration.ofMillis(100))));
                sound(p, "block.note_block.pling", 1f, 0.8f);
            }
            if (c <= 0) {
                r.state = State.RACING;
                r.startNs = r.lapStartNs = System.nanoTime();
                p.showTitle(Title.title(Component.text("СТАРТ!", TextColor.color(0x55FF55)).decorate(TextDecoration.BOLD),
                        Component.text("круг 1/" + laps, NamedTextColor.GRAY), Title.Times.times(Duration.ZERO, Duration.ofMillis(900), Duration.ofMillis(300))));
                sound(p, "block.note_block.pling", 1f, 1.6f);
                sound(p, "entity.firework_rocket.launch", 0.8f, 0.8f);
            }
            fw = bw = boostKey = false;
            r.v = 0;
        }

        if (r.state != State.GRID && r.lights != null && r.lights.isValid() && ++r.lightsGone > 40) r.lights.remove();

        // ---- steering: the bike turns towards where the rider looks
        float look = p.getLocation().getYaw();
        float diff = wrap(look - r.yaw);
        double maxTurn = 7.5 - Math.min(3.5, Math.abs(r.v) * 2.0);
        float turn = (float) Math.max(-maxTurn, Math.min(maxTurn, diff * 0.32));
        if (r.state == State.GRID) turn = 0;
        r.yaw = wrap(r.yaw + turn);

        // ---- speed
        int gi = track.nearest((int) Math.floor(r.x), (int) Math.floor(r.z));
        double dCentre = gi < 0 ? 99 : track.distance((int) Math.floor(r.x), (int) Math.floor(r.z));
        boolean onRoad = dCentre <= RaceTrack.HW + 2.5;
        boolean wantBoost = boostKey && fw && r.energy > 2 && r.state == State.RACING;
        if (wantBoost && !r.boosting) {
            sound(p, "entity.firework_rocket.launch", 0.6f, 0.6f);
            world.playSound(Sound.sound(Key.key("neyrocraft", "speeder.boost"), Sound.Source.NEUTRAL, 1f, 1f), r.bike);
        }
        r.boosting = wantBoost;
        if (r.boosting) {
            r.energy -= 1.3;
            if (tick % 10 == 0) p.addPotionEffect(new PotionEffect(PotionEffectType.SPEED, 16, 3, false, false, false));
        } else {
            r.energy = Math.min(100, r.energy + 0.4);
        }
        double max = r.boosting ? BOOST_MAX : MAX;
        if (!onRoad) max = Math.min(max, OFFROAD_MAX);
        if (r.state == State.DONE) { fw = false; bw = false; r.v *= 0.94; }
        if (fw) r.v += r.boosting ? BOOST_ACCEL : ACCEL;
        else if (bw) r.v -= r.v > 0 ? BRAKE : ACCEL * 0.6;
        else r.v *= DRAG;
        if (r.v > max) r.v = Math.max(max, r.v - 0.05);
        if (r.v < -REVERSE_MAX) r.v = -REVERSE_MAX;
        if (Math.abs(r.v) < 0.003 && !fw && !bw) r.v = 0;

        // ---- moving, in small steps with wall collisions
        double rad = Math.toRadians(r.yaw);
        double fx = -Math.sin(rad), fz = Math.cos(rad);
        double strafe = r.state == State.RACING ? (in.isLeft() ? STRAFE : 0) - (in.isRight() ? STRAFE : 0) : 0;
        // left of the heading is (fz, -fx)
        double dx = fx * r.v + fz * strafe, dz = fz * r.v - fx * strafe;
        double len = Math.hypot(dx, dz);
        int steps = Math.max(1, (int) Math.ceil(len / 0.45));
        double feet = r.y - HOVER;
        double g = ground(r.x, r.z, feet);
        boolean crashed = false;
        for (int i = 0; i < steps; i++) {
            double nx = r.x + dx / steps, nz = r.z + dz / steps;
            double ng = ground(nx, nz, feet);
            if (ng <= feet + STEP_UP) { r.x = nx; r.z = nz; g = ng; continue; }
            // blocked: slide along the wall if possible
            double gx = ground(nx, r.z, feet), gz = ground(r.x, nz, feet);
            if (gx <= feet + STEP_UP && Math.abs(dx) > 1e-4) { r.x = nx; g = gx; r.v *= 0.9; continue; }
            if (gz <= feet + STEP_UP && Math.abs(dz) > 1e-4) { r.z = nz; g = gz; r.v *= 0.9; continue; }
            crashed = true;
            break;
        }
        if (crashed) {
            if (Math.abs(r.v) > 0.5 && r.sounds <= 0) {
                world.playSound(Sound.sound(Key.key("minecraft", "entity.iron_golem.hurt"), Sound.Source.NEUTRAL, 0.8f, 0.6f), r.bike);
                world.playSound(Sound.sound(Key.key("minecraft", "block.anvil.land"), Sound.Source.NEUTRAL, 0.35f, 1.4f), r.bike);
                world.spawnParticle(Particle.ELECTRIC_SPARK, r.x + fx, r.y, r.z + fz, 25, 0.4, 0.3, 0.4, 0.3);
                world.spawnParticle(Particle.SMOKE, r.x + fx, r.y, r.z + fz, 12, 0.3, 0.2, 0.3, 0.02);
                r.sounds = 8;
            }
            r.v = -r.v * 0.3;
        }
        if (r.sounds > 0) r.sounds--;

        // ---- hovering, jumps and landings
        double target = g + HOVER;
        if (g < -500) target = r.y - 3;                // nothing below: fall
        double oldY = r.y;
        if (r.airborne || r.y - target > 0.45) {
            r.airborne = true;
            r.vy -= GRAVITY;
            r.y += r.vy;
            if (r.y <= target) {
                if (r.vy < -0.45) {
                    world.playSound(Sound.sound(Key.key("minecraft", "entity.generic.big_fall"), Sound.Source.NEUTRAL, 0.7f, 0.8f), r.bike);
                    world.spawnParticle(Particle.DUST, r.x, g + 0.1, r.z, 30, 1.0, 0.1, 1.0, 0, new Particle.DustOptions(Color.fromRGB(SAND), 1.6f));
                }
                r.y = target; r.vy = 0; r.airborne = false;
            }
        } else {
            double ny = r.y + (target - r.y) * 0.45;
            r.vy = ny - oldY;
            r.y = ny;
        }

        // ---- look of the bike: bank into turns, nose follows the slope
        float rollTarget = (float) Math.max(-28, Math.min(28, -turn * 3.2 - strafe * 30));
        r.roll += (rollTarget - r.roll) * 0.3f;
        float pitchTarget = (float) Math.max(-22, Math.min(22, -Math.toDegrees(Math.atan2(r.vy, Math.max(0.2, Math.abs(r.v))))));
        r.pitch += (pitchTarget - r.pitch) * 0.3f;
        double bob = r.airborne ? 0 : Math.sin(tick * 0.25) * 0.03;
        r.bike.teleport(new Location(world, r.x, r.y + bob, r.z, r.yaw, 0), TeleportFlag.EntityState.RETAIN_PASSENGERS);
        r.bike.setInterpolationDelay(0);
        r.bike.setTransformation(bikeTransform(r.roll, r.pitch));

        effects(r, fx, fz, onRoad, g);
        progress(r, onRoad);
        hud(r);
        if (r.y < 40) reset(r, "Спидер сорвался в пропасть");
    }

    private void effects(Racer r, double fx, double fz, boolean onRoad, double g) {
        double bx = r.x - fx * 1.5, bz = r.z - fz * 1.5, by = r.y - 0.25;
        if (Math.abs(r.v) > 0.05 || r.state == State.GRID) {
            world.spawnParticle(Particle.DUST, bx, by, bz, 2, 0.08, 0.05, 0.08, 0, new Particle.DustOptions(Color.fromRGB(0xFF9A30), 0.9f));
            if (r.boosting) {
                world.spawnParticle(Particle.FLAME, bx, by, bz, 3, 0.1, 0.06, 0.1, 0.01);
                world.spawnParticle(Particle.SOUL_FIRE_FLAME, bx, by, bz, 1, 0.08, 0.05, 0.08, 0.01);
                if (tick % 2 == 0) world.spawnParticle(Particle.END_ROD, bx, by, bz, 1, 0.05, 0.05, 0.05, 0.02);
            }
        }
        if (!onRoad && Math.abs(r.v) > 0.2 && !r.airborne)
            world.spawnParticle(Particle.DUST, r.x, g + 0.2, r.z, 4, 0.8, 0.1, 0.8, 0, new Particle.DustOptions(Color.fromRGB(SAND), 1.8f));
        // engine: a one-second loop, pitch follows the speed
        // overlapping grains every 5 ticks: continuous at any pitch, loudness kept even
        if ((tick + r.slot) % 5 == 0) {
            float pitch = (float) Math.max(0.65, Math.min(1.7, 0.7 + Math.abs(r.v) * 0.45 + (r.boosting ? 0.15 : 0)));
            world.playSound(Sound.sound(Key.key("neyrocraft", "speeder.engine"), Sound.Source.NEUTRAL, 0.32f * pitch, pitch), r.bike);
        }
        if (tick % 30 == 0 && Math.abs(r.v) > 0.6)
            world.playSound(Sound.sound(Key.key("minecraft", "entity.elytra.flying"), Sound.Source.NEUTRAL, 0.12f, (float) (0.8 + r.v * 0.3)), r.bike);
    }

    // ------------------------------------------------------------ track progress

    private void progress(Racer r, boolean onRoad) {
        Player p = r.p;
        int ni = track.nearestAround(r.x, r.z, r.idx, 25, 70);
        double d2 = (track.x[ni] - r.x) * (track.x[ni] - r.x) + (track.z[ni] - r.z) * (track.z[ni] - r.z);
        if (d2 > 30 * 30) ni = track.nearestGlobal(r.x, r.z);
        int delta = ni - r.idx;
        if (delta > track.n / 2) delta -= track.n;
        if (delta < -track.n / 2) delta += track.n;
        r.prog += delta;
        r.idx = ni;
        if (r.state != State.RACING) return;

        // checkpoints in order
        double target = (double) r.lap * track.n + track.cps[r.next];
        if (r.prog >= target && Math.abs(delta) < 80) checkpoint(r);
        else if (r.prog > target + 50 && tick - r.lastWarn > 60) {
            r.lastWarn = (int) tick;
            p.showTitle(Title.title(Component.empty(), Component.text("Пропущена контрольная арка " + (r.next + 1) + " — вернись (F)", NamedTextColor.RED),
                    Title.Times.times(Duration.ZERO, Duration.ofMillis(1500), Duration.ofMillis(300))));
            sound(p, "block.note_block.bass", 1f, 0.6f);
        }

        // wrong way
        double rad = Math.toRadians(r.yaw);
        double dot = -Math.sin(rad) * track.dirX[ni] + Math.cos(rad) * track.dirZ[ni];
        if (dot < -0.4 && r.v > 0.3) r.wrongTicks++; else r.wrongTicks = 0;
        if (r.wrongTicks == 30) {
            p.showTitle(Title.title(Component.text("⟲", NamedTextColor.RED).decorate(TextDecoration.BOLD),
                    Component.text("НЕВЕРНОЕ НАПРАВЛЕНИЕ", NamedTextColor.RED).decorate(TextDecoration.BOLD),
                    Title.Times.times(Duration.ZERO, Duration.ofMillis(1500), Duration.ofMillis(300))));
            sound(p, "block.note_block.bass", 1f, 0.5f);
        }
        // too long off the track: back onto it
        if (!onRoad) r.offTicks++; else r.offTicks = 0;
        if (r.offTicks == 80) p.sendActionBar(Component.text("Вне трассы! Вернись или нажми F", NamedTextColor.RED));
        if (r.offTicks > 160) reset(r, "Слишком долго вне трассы");
    }

    private void checkpoint(Racer r) {
        Player p = r.p;
        long now = System.nanoTime();
        long ms = (now - r.startNs) / 1_000_000;
        int k = r.lap * RaceTrack.CHECKPOINTS + r.next;
        r.splits[k] = ms;
        long[] pb = splitsOf(p.getUniqueId());
        Component delta = Component.empty();
        if (pb != null && k < pb.length && pb[k] > 0) {
            long dlt = ms - pb[k];
            delta = Component.text((dlt <= 0 ? " −" : " +") + fmtShort(Math.abs(dlt)), dlt <= 0 ? TextColor.color(0x55FF55) : TextColor.color(0xFF5555)).decorate(TextDecoration.BOLD);
        }
        boolean lapDone = r.next == RaceTrack.CHECKPOINTS - 1;
        if (lapDone) {
            long lapMs = (now - r.lapStartNs) / 1_000_000;
            r.lapStartNs = now;
            r.bestLapRun = Math.min(r.bestLapRun, lapMs);
            r.lap++;
            r.next = 0;
            if (r.lap >= laps) { finish(r, ms); return; }
            p.showTitle(Title.title(Component.text("КРУГ " + (r.lap + 1) + "/" + laps, TextColor.color(ORANGE)).decorate(TextDecoration.BOLD),
                    Component.text().append(Component.text("круг: " + fmt(lapMs), NamedTextColor.WHITE)).append(delta).build(),
                    Title.Times.times(Duration.ZERO, Duration.ofMillis(1800), Duration.ofMillis(400))));
            sound(p, "entity.player.levelup", 0.6f, 1.4f);
            sound(p, "block.beacon.power_select", 0.6f, 1.8f);
        } else {
            r.next++;
            p.showTitle(Title.title(Component.empty(),
                    Component.text().append(Component.text("◆ " + (r.next) + "/" + RaceTrack.CHECKPOINTS + "  ", TextColor.color(CYAN)))
                            .append(Component.text(fmt(ms), NamedTextColor.WHITE)).append(delta).build(),
                    Title.Times.times(Duration.ZERO, Duration.ofMillis(1000), Duration.ofMillis(250))));
            sound(p, "block.note_block.chime", 0.9f, 1.5f);
            sound(p, "block.amethyst_block.chime", 0.8f, 1.2f);
        }
        r.offTicks = 0;
        int s = track.cps[Math.floorMod(r.lap * RaceTrack.CHECKPOINTS + r.next - 1, RaceTrack.CHECKPOINTS)] % track.n;
        world.spawnParticle(Particle.END_ROD, track.x[s], track.y[s] + 6, track.z[s], 30, 4, 2, 0.4, 0.05);
    }

    /** Back to the last checkpoint passed (or the grid), standing still and facing the right way. */
    private void reset(Racer r, String why) {
        if (r.state == State.GRID) return;
        int s;
        if (r.next == 0 && r.lap == 0) { s = Math.floorMod(-14, track.n); r.prog = -14; }
        else {
            int c = r.next == 0 ? RaceTrack.CHECKPOINTS - 1 : r.next - 1;
            int lapBase = r.next == 0 ? r.lap - 1 : r.lap;
            s = track.cps[c] % track.n;
            r.prog = (double) lapBase * track.n + track.cps[c];
        }
        r.idx = s;
        r.x = track.x[s]; r.z = track.z[s];
        r.y = ground(r.x, r.z, track.y[s] + 3) + HOVER;
        r.yaw = track.yaw(s);
        r.v = 0; r.vy = 0; r.airborne = false; r.offTicks = 0; r.wrongTicks = 0;
        r.bike.teleport(new Location(world, r.x, r.y, r.z, r.yaw, 0), TeleportFlag.EntityState.RETAIN_PASSENGERS);
        Location eye = new Location(world, r.x, r.y + 1, r.z, r.yaw, 5);
        r.p.setRotation(r.yaw, 5);
        world.spawnParticle(Particle.REVERSE_PORTAL, eye, 60, 0.6, 0.6, 0.6, 0.05);
        sound(r.p, "entity.enderman.teleport", 0.6f, 1.3f);
        if (why != null) r.p.sendActionBar(Component.text("⟲ " + why + " — снова на трассе", TextColor.color(CYAN)));
    }

    private void finish(Racer r, long total) {
        Player p = r.p;
        r.state = State.DONE;
        r.doneTicks = 0;
        UUID id = p.getUniqueId();
        long prevBest = records.getLong(id + ".best", 0);
        long serverBest = serverRecord();
        boolean pb = prevBest == 0 || total < prevBest;
        boolean sr = serverBest == 0 || total < serverBest;
        if (pb) {
            records.set(id + ".name", p.getName());
            records.set(id + ".best", total);
            List<Long> sp = new ArrayList<>();
            for (long v : r.splits) sp.add(v);
            records.set(id + ".splits", sp);
        }
        long prevLap = records.getLong(id + ".lap", 0);
        if (prevLap == 0 || r.bestLapRun < prevLap) records.set(id + ".lap", r.bestLapRun);
        records.set(id + ".name", p.getName());
        records.set(id + ".runs", records.getInt(id + ".runs", 0) + 1);
        try { records.save(recordsFile); } catch (IOException e) { plugin.getLogger().warning("Race records: " + e); }
        updateBoard();

        Component sub = Component.text()
                .append(Component.text(fmt(total), NamedTextColor.WHITE).decorate(TextDecoration.BOLD))
                .append(Component.text(sr ? "  РЕКОРД ТРАССЫ!" : pb ? "  личный рекорд!" : "", TextColor.color(GOLD)).decorate(TextDecoration.BOLD))
                .build();
        p.showTitle(Title.title(Component.text("🏁 ФИНИШ!", TextColor.color(ORANGE)).decorate(TextDecoration.BOLD), sub,
                Title.Times.times(Duration.ZERO, Duration.ofMillis(3500), Duration.ofMillis(800))));
        sound(p, "ui.toast.challenge_complete", 0.8f, 1.0f);
        sound(p, "entity.firework_rocket.twinkle", 1f, 1.0f);
        world.spawnParticle(Particle.FIREWORK, r.x, r.y + 2, r.z, 80, 2, 2, 2, 0.15);
        world.spawnParticle(Particle.TOTEM_OF_UNDYING, r.x, r.y + 1.5, r.z, 60, 1.5, 1, 1.5, 0.3);

        p.sendMessage(Component.text()
                .append(Component.text("🏁 ", TextColor.color(ORANGE)))
                .append(Component.text("Финиш: ", NamedTextColor.GRAY))
                .append(Component.text(fmt(total), NamedTextColor.WHITE).decorate(TextDecoration.BOLD))
                .append(Component.text("  · лучший круг " + fmt(r.bestLapRun), NamedTextColor.GRAY))
                .append(Component.text(pb && prevBest > 0 ? "  · было " + fmt(prevBest) : "", NamedTextColor.DARK_GRAY)).build());
        if (sr) {
            Component all = Component.text()
                    .append(Component.text("🏆 ", TextColor.color(GOLD)))
                    .append(Component.text(p.getName(), TextColor.color(GOLD)).decorate(TextDecoration.BOLD))
                    .append(Component.text(" поставил рекорд трассы Терракс: ", NamedTextColor.GRAY))
                    .append(Component.text(fmt(total), NamedTextColor.WHITE).decorate(TextDecoration.BOLD)).build();
            for (Player o : Bukkit.getOnlinePlayers()) o.sendMessage(all);
        }
        StarPass sp = plugin.starPass();
        if (sp != null) {
            sp.award(p, 30, "финиш гонки");
            if (pb && prevBest > 0) Bukkit.getScheduler().runTaskLater(plugin, () -> sp.award(p, 25, "личный рекорд"), 40L);
            if (sr) Bukkit.getScheduler().runTaskLater(plugin, () -> sp.award(p, 100, "рекорд трассы"), 80L);
        }
    }

    // ------------------------------------------------------------ HUD

    private void hud(Racer r) {
        Player p = r.p;
        if (r.state == State.DONE) {
            r.doneTicks++;
            if (r.doneTicks > 70) toPlaza(r);
            return;
        }
        long ms = r.state == State.RACING ? (System.nanoTime() - r.startNs) / 1_000_000 : 0;
        if (tick % 2 == 0) {
            r.bar.name(Component.text()
                    .append(Component.text("КРУГ " + Math.min(laps, r.lap + 1) + "/" + laps, TextColor.color(ORANGE)).decorate(TextDecoration.BOLD))
                    .append(Component.text("   ⏱ ", NamedTextColor.GRAY))
                    .append(Component.text(fmt(ms), NamedTextColor.WHITE).decorate(TextDecoration.BOLD))
                    .append(Component.text("   ◆ " + r.next + "/" + RaceTrack.CHECKPOINTS, TextColor.color(CYAN)))
                    .append(Component.text(bestText(p), NamedTextColor.GRAY)).build());
            double prog = Math.max(0, Math.min(1, r.prog / ((double) laps * track.n)));
            r.bar.progress((float) prog);
        }
        int kmh = (int) Math.round(Math.abs(r.v) * KMH);
        TextComponent.Builder ab = Component.text()
                .append(Component.text("⚡ ", TextColor.color(r.boosting ? 0xFF7A20 : GOLD)))
                .append(Component.text(String.format("%3d", kmh), NamedTextColor.WHITE).decorate(TextDecoration.BOLD))
                .append(Component.text(" км/ч   ", NamedTextColor.GRAY))
                .append(Component.text("УСКОРИТЕЛЬ ", TextColor.color(r.boosting ? 0xFF7A20 : CYAN)));
        int cells = 10, full = (int) Math.round(r.energy / 100 * cells);
        for (int i = 0; i < cells; i++) ab.append(Component.text("▮", i < full ? TextColor.color(r.boosting ? 0xFF7A20 : CYAN) : TextColor.color(0x333333)));
        if (r.offTicks < 80) p.sendActionBar(ab.build());
    }

    private static Component lightsText(int lit, boolean go) {
        TextComponent.Builder b = Component.text();
        for (int i = 0; i < 5; i++) {
            TextColor c = go ? TextColor.color(0x40FF60) : i < lit ? TextColor.color(0xFF2A2A) : TextColor.color(0x3A1010);
            b.append(Component.text(i == 0 ? "●" : " ●", c));
        }
        return b.build();
    }

    private String bestText(Player p) {
        long b = records.getLong(p.getUniqueId() + ".best", 0);
        return b > 0 ? "   ЛР " + fmt(b) : "";
    }

    static String fmt(long ms) {
        long m = ms / 60000, s = (ms / 1000) % 60, x = ms % 1000;
        return String.format("%d:%02d.%03d", m, s, x);
    }

    private static String fmtShort(long ms) {
        return String.format("%d.%02d", ms / 1000, (ms % 1000) / 10);
    }

    // ------------------------------------------------------------ physics helpers

    /** Top of the first solid block at or below a point (slabs count as half), or -1000 if there is nothing. */
    private double ground(double x, double z, double feet) {
        int bx = (int) Math.floor(x), bz = (int) Math.floor(z);
        int from = (int) Math.floor(feet + 1.6), to = Math.max(world.getMinHeight(), from - 40);
        for (int by = from; by >= to; by--) {
            Block b = world.getBlockAt(bx, by, bz);
            if (b.isPassable()) continue;
            org.bukkit.util.BoundingBox box = b.getBoundingBox();
            double top = box.getVolume() > 0 ? box.getMaxY() : by + 1;
            return top;
        }
        return -1000;
    }

    private static float wrap(float a) {
        a %= 360f;
        if (a > 180) a -= 360;
        if (a < -180) a += 360;
        return a;
    }

    // ------------------------------------------------------------ records

    long best(UUID id) { return records.getLong(id + ".best", 0); }

    long bestLap(UUID id) { return records.getLong(id + ".lap", 0); }

    int runs(UUID id) { return records.getInt(id + ".runs", 0); }

    private long[] splitsOf(UUID id) {
        List<Long> l = records.getLongList(id + ".splits");
        if (l.isEmpty()) return null;
        long[] a = new long[l.size()];
        for (int i = 0; i < a.length; i++) a[i] = l.get(i);
        return a;
    }

    private long serverRecord() {
        long best = 0;
        for (String k : records.getKeys(false)) {
            long b = records.getLong(k + ".best", 0);
            if (b > 0 && (best == 0 || b < best)) best = b;
        }
        return best;
    }

    private List<String[]> top(int n) {
        List<String[]> rows = new ArrayList<>();
        for (String k : records.getKeys(false)) {
            ConfigurationSection s = records.getConfigurationSection(k);
            if (s == null || s.getLong("best", 0) <= 0) continue;
            rows.add(new String[]{s.getString("name", "?"), String.valueOf(s.getLong("best")), String.valueOf(s.getLong("lap", 0))});
        }
        rows.sort((a, b) -> Long.compare(Long.parseLong(a[1]), Long.parseLong(b[1])));
        return rows.subList(0, Math.min(n, rows.size()));
    }

    private Component boardText() {
        TextComponent.Builder b = Component.text()
                .append(Component.text("🏆 РЕКОРДЫ ТРАССЫ ТЕРРАКС 🏆", TextColor.color(GOLD)).decorate(TextDecoration.BOLD))
                .append(Component.newline())
                .append(Component.text(laps + " круга · " + track.n + " м", NamedTextColor.GRAY));
        List<String[]> rows = top(10);
        if (rows.isEmpty()) b.append(Component.newline()).append(Component.text("пока никого — стань первым!", NamedTextColor.GRAY));
        for (int i = 0; i < rows.size(); i++) {
            String[] r = rows.get(i);
            TextColor c = TextColor.color(i == 0 ? GOLD : i == 1 ? 0xD8D8D8 : i == 2 ? 0xD08A50 : 0xA0A0A0);
            b.append(Component.newline())
                    .append(Component.text((i + 1) + ". ", c).decorate(TextDecoration.BOLD))
                    .append(Component.text(r[0] + "  ", NamedTextColor.WHITE))
                    .append(Component.text(fmt(Long.parseLong(r[1])), c));
        }
        return b.build();
    }

    private void updateBoard() {
        if (board != null && board.isValid()) board.text(boardText());
    }

    private void showRecords(Player p) {
        p.sendMessage(boardText());
        long b = records.getLong(p.getUniqueId() + ".best", 0), l = records.getLong(p.getUniqueId() + ".lap", 0);
        if (b > 0) p.sendMessage(Component.text("Твой рекорд: " + fmt(b) + " · лучший круг " + fmt(l), TextColor.color(CYAN)));
    }

    // ------------------------------------------------------------ fixtures and sky

    private void spawnFixtures() {
        for (Entity e : fixtures) if (e.isValid()) e.remove();
        fixtures.clear();
        board = world.spawn(new Location(world, 0.5, RaceTrack.ROAD_Y + 4.2, 35.2), TextDisplay.class, t -> {
            plugin.prepare(t);
            t.setBillboard(Display.Billboard.VERTICAL);
            t.setBackgroundColor(Color.fromARGB(170, 30, 12, 6));
            t.setShadowed(true);
            t.setTransformation(scale(1.3f));
            t.text(boardText());
        });
        fixtures.add(board);
        fixtures.add(label(new Location(world, 0.5, RaceTrack.ROAD_Y + 4.5, 12.5), Component.text()
                .append(Component.text("ГОНКИ НА СПИДЕРАХ", TextColor.color(ORANGE)).decorate(TextDecoration.BOLD)).append(Component.newline())
                .append(Component.text("ПКМ спидером в руке — на старт", NamedTextColor.GRAY)).build(), 1.6f));
        for (int c = 0; c < RaceTrack.CHECKPOINTS; c++) {
            int s = track.cps[c] % track.n;
            boolean fin = c == RaceTrack.CHECKPOINTS - 1;
            Component t = fin ? Component.text("СТАРТ ◆ ФИНИШ", TextColor.color(ORANGE)).decorate(TextDecoration.BOLD)
                    : Component.text("◆ " + (c + 1) + " ◆", TextColor.color(CYAN)).decorate(TextDecoration.BOLD);
            fixtures.add(label(new Location(world, track.x[s], Math.floor(track.y[s]) + (fin ? 17.2 : 11.5), track.z[s]), t, fin ? 3.2f : 2.6f));
        }
        // starting grid: slot numbers and the direction of the start
        int fy = RaceTrack.ROAD_Y;
        for (int slot = 0; slot < 5; slot++) {
            int s = gridSample(slot);
            double gz = track.z[s] + gridLateral(slot);
            fixtures.add(label(new Location(world, Math.floor(track.x[s]) + 0.5, fy + 1.15, Math.floor(gz) + 0.5),
                    Component.text(String.valueOf(slot + 1), TextColor.color(GOLD)).decorate(TextDecoration.BOLD), 1.4f));
        }
        fixtures.add(label(new Location(world, -18.5, fy + 5.5, 0.5), Component.text()
                .append(Component.text("СТАРТОВАЯ РЕШЁТКА", TextColor.color(GOLD)).decorate(TextDecoration.BOLD)).append(Component.newline())
                .append(Component.text("старт на восток  ▶ ▶ ▶", NamedTextColor.WHITE)).build(), 1.5f));
    }

    private TextDisplay label(Location l, Component text, float sc) {
        return world.spawn(l, TextDisplay.class, t -> {
            plugin.prepare(t);
            t.setViewRange(6f);
            t.setBillboard(Display.Billboard.CENTER);
            t.setBackgroundColor(Color.fromARGB(110, 20, 8, 4));
            t.setShadowed(true);
            t.setBrightness(new Display.Brightness(15, 15));
            t.setTransformation(scale(sc));
            t.text(text);
        });
    }

    private static Transformation scale(float s) {
        return new Transformation(new Vector3f(), new Quaternionf(), new Vector3f(s, s, s), new Quaternionf());
    }

    // two moons that hang in the sky of Terrax: personal displays that travel with each player
    private static final double[][] MOON = {{58, 34, 46, 48, 0xFFE2C0}, {58, 34, 46, 74, -1}, {-62, 22, 30, 20, 0x9FD8FF}};

    private void skyMoons(Player p) {
        List<TextDisplay> l = moons.get(p.getUniqueId());
        Location eye = p.getEyeLocation();
        if (l == null) {
            l = new ArrayList<>();
            for (double[] m : MOON) {
                float sc = (float) m[3];
                boolean ring = m[4] < 0;
                TextDisplay d = world.spawn(eye.clone().add(m[0], m[1], m[2]), TextDisplay.class, t -> {
                    plugin.prepare(t);
                    t.setVisibleByDefault(false);
                    t.setViewRange(10f);
                    t.setBillboard(Display.Billboard.CENTER);
                    t.setBackgroundColor(Color.fromARGB(0, 0, 0, 0));
                    t.setShadowed(false);
                    t.setBrightness(new Display.Brightness(15, 15));
                    t.setTeleportDuration(1);
                    t.setTransformation(scale(sc));
                    t.text(Component.text(ring ? "◯" : "●", TextColor.color(ring ? 0xFFF1DC : (int) m[4])));
                });
                p.showEntity(plugin, d);
                l.add(d);
            }
            moons.put(p.getUniqueId(), l);
        }
        for (int i = 0; i < l.size() && i < MOON.length; i++) {
            TextDisplay d = l.get(i);
            if (!d.isValid()) { dropMoons(p.getUniqueId()); return; }
            double[] m = MOON[i];
            d.teleport(eye.clone().add(m[0], m[1] - m[3] * 0.11, m[2]));
        }
    }

    private void dropMoons(UUID id) {
        List<TextDisplay> l = moons.remove(id);
        if (l != null) for (TextDisplay d : l) if (d.isValid()) d.remove();
    }

    // ------------------------------------------------------------ events

    @EventHandler
    public void onWorld(PlayerChangedWorldEvent e) {
        Player p = e.getPlayer();
        if (in(e.getFrom())) {
            Racer r = racers.get(p.getUniqueId());
            if (r != null) stopRacer(r);
            dropMoons(p.getUniqueId());
            plugin.vault().unlock(p, GAME);
        }
        if (in(p)) {
            // the warp is still landing: lock the inventory once it is over
            Bukkit.getScheduler().runTaskLater(plugin, () -> {
                if (!p.isOnline() || !in(p)) return;
                plugin.vault().lock(p, GAME);
                if (p.getGameMode() != GameMode.CREATIVE && p.getGameMode() != GameMode.SPECTATOR) p.setGameMode(GameMode.ADVENTURE);
                if (!racers.containsKey(p.getUniqueId())) plazaItems(p);
            }, 30L);
        }
    }

    @EventHandler
    public void onJoin(PlayerJoinEvent e) {
        Player p = e.getPlayer();
        if (in(p)) Bukkit.getScheduler().runTaskLater(plugin, () -> {
            if (!p.isOnline() || !in(p)) return;
            Location o = plugin.orionSpawn();
            p.teleport(o != null ? o : plugin.spawn());
        }, 1L);
    }

    void quit(Player p) {
        Racer r = racers.get(p.getUniqueId());
        if (r != null) stopRacer(r);
        dropMoons(p.getUniqueId());
    }

    @EventHandler
    public void onQuit(PlayerQuitEvent e) {
        quit(e.getPlayer());
    }

    @EventHandler(priority = EventPriority.HIGHEST)
    public void onDismount(EntityDismountEvent e) {
        if (!(e.getEntity() instanceof Player p)) return;
        Racer r = racers.get(p.getUniqueId());
        if (r != null && !r.allowDismount && e.getDismounted().equals(r.bike)) e.setCancelled(true);
    }

    @EventHandler(priority = EventPriority.HIGH)
    public void onUse(PlayerInteractEvent e) {
        Player p = e.getPlayer();
        if (!in(p)) return;
        String id = itemId(e.getItem());
        if (id == null) return;
        e.setCancelled(true);
        if (e.getAction() == Action.PHYSICAL) return;
        if (e.getHand() != org.bukkit.inventory.EquipmentSlot.HAND) return;
        use(p, id);
    }

    private void use(Player p, String id) {
        Racer r = racers.get(p.getUniqueId());
        switch (id) {
            case "start" -> startRun(p);
            case "records" -> showRecords(p);
            case "leave" -> leave(p);
            case "reset" -> { if (r != null && r.state == State.RACING) reset(r, null); }
            case "quit" -> { if (r != null) { toPlaza(r); p.sendMessage(Component.text("Ты сошёл с дистанции", NamedTextColor.GRAY)); } }
            default -> { }
        }
    }

    @EventHandler
    public void onSwap(PlayerSwapHandItemsEvent e) {
        Player p = e.getPlayer();
        if (!in(p)) return;
        e.setCancelled(true);
        Racer r = racers.get(p.getUniqueId());
        if (r != null && r.state == State.RACING) reset(r, null);
    }

    @EventHandler
    public void onDrop(PlayerDropItemEvent e) {
        if (in(e.getPlayer()) && e.getPlayer().getGameMode() != GameMode.CREATIVE) e.setCancelled(true);
    }

    @EventHandler
    public void onClick(InventoryClickEvent e) {
        if (e.getWhoClicked() instanceof Player p && in(p) && p.getGameMode() != GameMode.CREATIVE) e.setCancelled(true);
    }

    @EventHandler
    public void onDamage(EntityDamageEvent e) {
        if (e.getEntity() instanceof Player p && in(p)) e.setCancelled(true);
    }

    @EventHandler
    public void onHunger(FoodLevelChangeEvent e) {
        if (e.getEntity() instanceof Player p && in(p)) e.setCancelled(true);
    }

    @EventHandler(ignoreCancelled = true)
    public void onMove(PlayerMoveEvent e) {
        Player p = e.getPlayer();
        if (!in(p) || riding(p)) return;
        if (e.getTo().getY() < 45) { p.teleport(plaza()); p.setFallDistance(0); }
    }

    private static void sound(Player p, String key, float vol, float pitch) {
        p.playSound(Sound.sound(Key.key("minecraft", key), Sound.Source.MASTER, vol, pitch));
    }
}
