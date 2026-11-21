package net.neyrocraft.hub;

import io.papermc.paper.datacomponent.item.ResolvableProfile;
import io.papermc.paper.entity.LookAnchor;
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
import org.bukkit.GameMode;
import org.bukkit.HeightMap;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.Particle;
import org.bukkit.Tag;
import org.bukkit.World;
import org.bukkit.block.Block;
import org.bukkit.block.data.BlockData;
import org.bukkit.command.CommandSender;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.BlockDisplay;
import org.bukkit.entity.Display;
import org.bukkit.entity.Enemy;
import org.bukkit.entity.Entity;
import org.bukkit.entity.FallingBlock;
import org.bukkit.entity.ItemDisplay;
import org.bukkit.entity.LivingEntity;
import org.bukkit.entity.Mannequin;
import org.bukkit.entity.Player;
import org.bukkit.entity.Projectile;
import org.bukkit.entity.TextDisplay;
import org.bukkit.event.Event;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.block.Action;
import org.bukkit.event.block.BlockBreakEvent;
import org.bukkit.event.block.BlockBurnEvent;
import org.bukkit.event.block.BlockExplodeEvent;
import org.bukkit.event.block.BlockFromToEvent;
import org.bukkit.event.block.BlockIgniteEvent;
import org.bukkit.event.block.BlockPistonExtendEvent;
import org.bukkit.event.block.BlockPistonRetractEvent;
import org.bukkit.event.block.BlockPlaceEvent;
import org.bukkit.event.block.BlockSpreadEvent;
import org.bukkit.event.entity.CreatureSpawnEvent;
import org.bukkit.event.entity.EntityChangeBlockEvent;
import org.bukkit.event.entity.EntityDamageByEntityEvent;
import org.bukkit.event.entity.EntityDamageEvent;
import org.bukkit.event.entity.EntityDismountEvent;
import org.bukkit.event.entity.EntityExplodeEvent;
import org.bukkit.event.entity.EntityPlaceEvent;
import org.bukkit.event.entity.EntityTargetLivingEntityEvent;
import org.bukkit.event.hanging.HangingBreakByEntityEvent;
import org.bukkit.event.hanging.HangingBreakEvent;
import org.bukkit.event.hanging.HangingPlaceEvent;
import org.bukkit.event.player.PlayerBucketEmptyEvent;
import org.bukkit.event.player.PlayerBucketFillEvent;
import org.bukkit.event.player.PlayerInteractEntityEvent;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.event.player.PlayerRespawnEvent;
import org.bukkit.event.player.PlayerToggleSneakEvent;
import org.bukkit.inventory.EquipmentSlot;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.Merchant;
import org.bukkit.inventory.meta.LeatherArmorMeta;
import org.bukkit.profile.PlayerTextures;
import org.bukkit.scheduler.BukkitRunnable;
import org.bukkit.scheduler.BukkitTask;
import org.bukkit.util.BoundingBox;
import org.bukkit.util.Transformation;
import org.joml.AxisAngle4f;
import org.joml.Quaternionf;
import org.joml.Vector3f;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.OutputStreamWriter;
import java.io.Writer;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.function.DoubleFunction;
import java.util.zip.GZIPOutputStream;

/**
 * Neyro-City: a cyberpunk city around the survival spawn. It is a safe zone (no PvP, no mobs, no
 * griefing), the place where players spawn and respawn in survival, with shops, traders and neon
 * signs. The starship NEYRO-1 (the main hub) hangs in the sky above it; a beam of light connects the
 * plaza with the ship: step into it to fly up, and coming down from the ship you slide down the beam.
 */
final class NeyroCity implements Listener {

    static final String TAG = "neyrocity";
    static final int VERSION = 2;              // raise to rebuild the city on the next start (in place)
                                               // 2: big gates, avenues outside, the land around smoothed
    static final int SKY_ABOVE = 85;           // deck floor of the sky ship above the plaza (below the clouds)
    static final int SHIP_X = 108, SHIP_Z = 110, SHIP_DOWN = 24, SHIP_UP = 32;
    static final int DROP_TICKS = 120;         // the slide down the beam: 6 seconds

    private static final int ORANGE = NeyroHub.ORANGE;
    private static final int CYAN = 0x40E8FF, MAGENTA = 0xFF4FD8;

    private final NeyroHub plugin;
    private final CityPlan plan = new CityPlan();
    private final NeyroHousing housing;
    private final Random rnd = new Random();
    private World world;
    private int cx, cz, y0, shipY;
    private boolean located, building, ready;
    private BukkitTask buildTask, tickTask;
    private long tick;

    private final Set<Long> tickets = new HashSet<>();
    private final Map<UUID, CityShops.Type> traders = new HashMap<>();
    private final Map<UUID, CityFolk.Npc> folkNpcs = new HashMap<>();
    private final Map<UUID, String> talkers = new HashMap<>();        // guide, guards: a line when clicked
    private final Map<Mannequin, Float> lookers = new HashMap<>();    // NPCs that turn to nearby players
    private final List<TextDisplay> ads = new ArrayList<>();
    private final List<ItemDisplay> showcase = new ArrayList<>();
    private final List<Car> cars = new ArrayList<>();
    private final Set<UUID> inZone = new HashSet<>(), onPad = new HashSet<>();
    private final Map<UUID, Long> padCooldown = new HashMap<>();
    private final Map<UUID, BukkitTask> warmups = new HashMap<>();

    NeyroCity(NeyroHub plugin) {
        this.plugin = plugin;
        this.folk = new CityFolk(plugin);
        this.housing = new NeyroHousing(plugin, this);
    }

    private final CityFolk folk;

    // ================================================================== lifecycle

    void start() {
        if (!plugin.getConfig().getBoolean("city.enabled", true)) {
            plugin.getLogger().info("Neyro-City is disabled in config.yml (city.enabled)");
            return;
        }
        world = Bukkit.getWorld(plugin.survivalWorldName());
        if (world == null) {
            plugin.getLogger().warning("Neyro-City: survival world '" + plugin.survivalWorldName() + "' not found");
            return;
        }
        YamlConfiguration st = YamlConfiguration.loadConfiguration(stateFile());
        Location ws = world.getSpawnLocation();
        cx = st.getInt("center-x", plugin.getConfig().getInt("city.center-x", ws.getBlockX()));
        cz = st.getInt("center-z", plugin.getConfig().getInt("city.center-z", ws.getBlockZ()));
        tickTask = Bukkit.getScheduler().runTaskTimer(plugin, this::tick, 40L, 1L);
        if (st.contains("plaza-y")) {               // built before: keep its place and height
            y0 = st.getInt("plaza-y");
            shipY = st.getInt("ship-y");
            located = true;
        }
        if (located && st.getInt("version", 0) >= VERSION) {
            loadChunks(cityChunks(), this::becomeReady);
        } else {
            if (located) plugin.getLogger().info("Neyro-City: new version " + VERSION + ", rebuilding in place");
            build(false);
        }
    }

    void shutdown() {
        if (buildTask != null) buildTask.cancel();
        if (tickTask != null) tickTask.cancel();
        for (Map.Entry<UUID, long[]> e : music.entrySet()) {
            Player p = Bukkit.getPlayer(e.getKey());
            if (p != null) stopMusic(p, e.getValue());
        }
        music.clear();
        warmups.values().forEach(BukkitTask::cancel);
        warmups.clear();
        removeDecor();
        if (world != null) world.removePluginChunkTickets(plugin);
        tickets.clear();
    }

    boolean ready() { return ready; }

    NeyroHousing housing() { return housing; }

    private File stateFile() {
        return new File(plugin.getDataFolder(), "city.yml");
    }

    private void saveState() {
        YamlConfiguration st = new YamlConfiguration();
        st.set("version", VERSION);
        st.set("center-x", cx);
        st.set("center-z", cz);
        st.set("plaza-y", y0);
        st.set("ship-y", shipY);
        try {
            plugin.getDataFolder().mkdirs();
            st.save(stateFile());
        } catch (IOException e) {
            plugin.getLogger().warning("Neyro-City: could not save city.yml: " + e);
        }
    }

    // ================================================================== places

    /** Where players appear in survival: the plaza, south of the beam, facing it. */
    Location spawn() {
        return ready ? new Location(world, cx + 0.5, y0 + 1, cz + 9.5, 180f, 0f) : null;
    }

    private Location pad() {
        return new Location(world, cx + 0.5, y0 + 1, cz + 0.5);
    }

    private Location at(double dx, double h, double dz, float yaw) {
        return new Location(world, cx + dx, y0 + h, cz + dz, yaw, 0f);
    }

    /** Inside the city wall (any height): the safe zone. */
    boolean safe(Location l) {
        if (world == null || !located || l.getWorld() == null || !l.getWorld().equals(world)) return false;
        return CityPlan.insideWall(l.getBlockX() - cx, l.getBlockZ() - cz);
    }

    /** Blocks nobody may change: the city with its avenues outside the gates, and the ship in the sky. */
    boolean guarded(Location l) {
        if (world == null || !located || l.getWorld() == null || !l.getWorld().equals(world)) return false;
        int dx = l.getBlockX() - cx, dz = l.getBlockZ() - cz;
        if (CityPlan.inFootprint(dx, dz)) return true;
        return inShipBox(l);
    }

    private static final int SPAWN_PROTECTION = 500;

    private double spawnDistance(Location l) {
        Location spawn = world.getSpawnLocation();
        return Math.hypot((double) l.getBlockX() - spawn.getBlockX(), (double) l.getBlockZ() - spawn.getBlockZ());
    }

    boolean spawnProtected(Location l) {
        if (world == null || l.getWorld() == null || !l.getWorld().equals(world)) return false;
        return spawnDistance(l) <= SPAWN_PROTECTION;
    }

    private boolean inShipBox(Location l) {
        int dx = l.getBlockX() - cx, dz = l.getBlockZ() - cz, y = l.getBlockY();
        return Math.abs(dx) <= SHIP_X && Math.abs(dz) <= SHIP_Z && y >= shipY - SHIP_DOWN && y <= shipY + SHIP_UP;
    }

    /** A survival destination that lies in the city: arriving there means sliding down the beam. */
    boolean isCityTarget(Location l) {
        return ready && l != null && safe(l);
    }

    // ================================================================== building

    private List<long[]> cityChunks() {
        return chunkRange(CityPlan.EXT + 2);
    }

    private List<long[]> chunkRange(int reach) {
        List<long[]> out = new ArrayList<>();
        for (int x = (cx - reach) >> 4; x <= (cx + reach) >> 4; x++)
            for (int z = (cz - reach) >> 4; z <= (cz + reach) >> 4; z++) out.add(new long[]{x, z});
        return out;
    }

    private static long key(long x, long z) {
        return (x << 32) ^ (z & 0xFFFFFFFFL);
    }

    /** Loads the chunks asynchronously, keeps them loaded with plugin tickets, then runs {@code then}. */
    private void loadChunks(List<long[]> chunks, Runnable then) {
        CompletableFuture<?>[] fs = new CompletableFuture[chunks.size()];
        for (int i = 0; i < chunks.size(); i++) fs[i] = world.getChunkAtAsync((int) chunks.get(i)[0], (int) chunks.get(i)[1], true);
        CompletableFuture.allOf(fs).whenComplete((v, ex) -> {
            if (!plugin.isEnabled()) return;
            Bukkit.getScheduler().runTask(plugin, () -> {
                if (ex != null) plugin.getLogger().warning("Neyro-City: chunk loading failed: " + ex);
                for (long[] c : chunks)
                    if (tickets.add(key(c[0], c[1]))) world.addPluginChunkTicket((int) c[0], (int) c[1], plugin);
                then.run();
            });
        });
    }

    void build(boolean rebuild) {
        if (building || world == null) return;
        building = true;
        ready = false;
        removeDecor();
        List<long[]> chunks = new ArrayList<>(cityChunks());
        Set<Long> city = new HashSet<>();
        for (long[] c : chunks) city.add(key(c[0], c[1]));
        Set<Long> extra = new HashSet<>();
        for (long[] c : chunkRange(Math.max(SHIP_Z, CityPlan.HALF + CityPlan.SKIRT) + 2))
            if (!city.contains(key(c[0], c[1])) && extra.add(key(c[0], c[1]))) chunks.add(c);
        plugin.getLogger().info("Neyro-City: loading " + chunks.size() + " chunks around " + cx + ", " + cz + "...");
        loadChunks(chunks, () -> {
            if (!located) {
                y0 = pickPlazaY();
                shipY = Math.min(world.getMaxHeight() - SHIP_UP - 2, y0 + SKY_ABOVE);
                located = true;
            } else if (rebuild) {
                ShipModel.clear(world, cx - SHIP_X, shipY - SHIP_DOWN, cz - SHIP_Z, cx + SHIP_X, Math.min(world.getMaxHeight() - 1, shipY + SHIP_UP), cz + SHIP_Z);
            }
            plugin.getLogger().info("Neyro-City: building the city (plaza at y=" + y0 + ") and the ship in the sky (deck at y=" + shipY + ")...");
            ShipModel sky = ShipModel.load(plugin, ShipModel.SKY_RESOURCE);
            List<int[]> cols = new ArrayList<>(), skirt = new ArrayList<>();
            int reach = CityPlan.HALF + CityPlan.SKIRT;
            for (int dx = -reach; dx <= reach; dx++)
                for (int dz = -reach; dz <= reach; dz++) {
                    if (CityPlan.inFootprint(dx, dz)) cols.add(new int[]{dx, dz});
                    else skirt.add(new int[]{dx, dz});
                }
            cols.addAll(skirt);                       // first the city, then the land around it
            int cityCols = cols.size() - skirt.size();
            long started = System.currentTimeMillis();
            buildTask = new BukkitRunnable() {
                int i = 0, run = 0, lastPct = -100;

                @Override
                public void run() {
                    long deadline = System.nanoTime() + 25_000_000L;      // 25 ms per tick, the server keeps running
                    while (i < cols.size() && System.nanoTime() < deadline) {
                        int[] c = cols.get(i);
                        if (i < cityCols) buildColumn(c[0], c[1]);
                        else smoothColumn(c[0], c[1]);
                        i++;
                    }
                    int pct = i * 100 / cols.size();
                    if (pct / 25 != lastPct / 25) { lastPct = pct; plugin.getLogger().info("Neyro-City: " + pct + "%"); }
                    if (i < cols.size()) return;
                    if (sky != null && run < sky.runCount()) {
                        run = sky.placeSome(world, cx, shipY, cz, run, deadline);
                        if (run < sky.runCount()) return;
                    }
                    cancel();
                    buildTask = null;
                    building = false;
                    saveState();
                    // only the city itself stays loaded (for its traders and signs)
                    for (long k : extra) {
                        if (tickets.remove(k)) world.removePluginChunkTicket((int) (k >> 32), (int) k, plugin);
                    }
                    plugin.getLogger().info("Neyro-City built in " + (System.currentTimeMillis() - started) / 1000 + " s");
                    becomeReady();
                }
            }.runTaskTimer(plugin, 1L, 1L);
        });
    }

    /** The plaza height: the median ground height of the area, at least sea level. */
    private int pickPlazaY() {
        List<Integer> hs = new ArrayList<>();
        for (int dx = -56; dx <= 56; dx += 4)
            for (int dz = -56; dz <= 56; dz += 4)
                hs.add(world.getHighestBlockYAt(cx + dx, cz + dz, HeightMap.MOTION_BLOCKING_NO_LEAVES));
        hs.sort(Integer::compare);
        int median = hs.get(hs.size() / 2);
        return Math.max(world.getSeaLevel(), Math.min(110, median));
    }

    private static final BlockData AIR = Material.AIR.createBlockData();
    private static final BlockData STONE = Material.STONE.createBlockData();

    private void buildColumn(int dx, int dz) {
        int x = cx + dx, z = cz + dz;
        int top = Math.max(y0, belowShip(x, z, world.getHighestBlockYAt(x, z, HeightMap.WORLD_SURFACE)));
        int clearTop = Math.min(world.getMaxHeight() - 1, Math.max(y0 + CityPlan.TOP, top));
        for (int y = y0 - 3; y <= clearTop; y++) {
            BlockData want = plan.block(dx, y - y0, dz);
            if (want == null) want = AIR;
            Block b = world.getBlockAt(x, y, z);
            if (!b.getBlockData().equals(want)) b.setBlockData(want, false);
        }
        // a solid foundation under the street: fill air, water, lava, leaves and plants down to the ground
        for (int y = y0 - 4; y >= Math.max(world.getMinHeight(), y0 - 48); y--) {
            Block b = world.getBlockAt(x, y, z);
            Material m = b.getType();
            if (m.isAir() || !m.isSolid() || Tag.LEAVES.isTagged(m)) b.setBlockData(STONE, false);
            else break;
        }
    }

    /**
     * The land around the wall: a flat ring at street height, then a smooth slope back to the natural
     * ground. Hills and mountains are cut down, hollows filled; water is never drained (a sandy shore
     * is built over it instead), trees stay where the ground keeps its height.
     */
    private void smoothColumn(int dx, int dz) {
        int x = cx + dx, z = cz + dz;
        int d = Math.max(Math.abs(dx), Math.abs(dz)) - CityPlan.HALF;
        if (d < 1 || d > CityPlan.SKIRT) return;
        double t = Math.max(0, Math.min(1, (d - CityPlan.APRON) / (double) (CityPlan.SKIRT - CityPlan.APRON)));
        t = t * t * (3 - 2 * t);
        int natural = groundY(x, z);
        int target = (int) Math.round(y0 + (natural - y0) * t);
        if (target == natural) return;
        Material top = world.getBlockAt(x, natural, z).getType();
        boolean water = top == Material.WATER || top == Material.LAVA || top == Material.ICE || top == Material.KELP || top == Material.SEAGRASS;
        boolean sandy = water || top == Material.SAND || top == Material.RED_SAND || top == Material.GRAVEL;
        BlockData surface = (sandy ? Material.SAND : Material.GRASS_BLOCK).createBlockData();
        BlockData under = (sandy ? Material.SANDSTONE : Material.DIRT).createBlockData();
        if (target < natural) {
            if (water) return;                                           // never drain the sea
            int roof = belowShip(x, z, Math.max(natural, world.getHighestBlockYAt(x, z, HeightMap.WORLD_SURFACE)));
            for (int y = target + 1; y <= roof; y++) {
                Block b = world.getBlockAt(x, y, z);
                if (!b.getType().isAir()) b.setBlockData(AIR, false);
            }
        } else {
            for (int y = natural + 1; y < target; y++) world.getBlockAt(x, y, z).setBlockData(target - y <= 3 ? under : STONE, false);
        }
        world.getBlockAt(x, target, z).setBlockData(surface, false);
        for (int y = target - 1; y >= target - 2; y--) {
            Block b = world.getBlockAt(x, y, z);
            Material m = b.getType();
            if (m == Material.STONE || m == Material.DEEPSLATE || m == Material.GRANITE || m == Material.ANDESITE
                    || m == Material.DIORITE || m == Material.TUFF || m.isAir() || !m.isSolid()) b.setBlockData(under, false);
        }
    }

    /** Under the ship in the sky nothing may reach into it: heights are capped below the ship's box. */
    private int belowShip(int x, int z, int y) {
        if (Math.abs(x - cx) <= SHIP_X && Math.abs(z - cz) <= SHIP_Z) return Math.min(y, shipY - SHIP_DOWN - 1);
        return y;
    }

    /** The real ground of a column: below trees (logs, leaves, mushrooms), plants and snow; water counts as ground. */
    private int groundY(int x, int z) {
        int y = belowShip(x, z, world.getHighestBlockYAt(x, z, HeightMap.WORLD_SURFACE));
        for (; y > world.getMinHeight(); y--) {
            Material m = world.getBlockAt(x, y, z).getType();
            if (m == Material.WATER || m == Material.LAVA) return y;
            if (m.isAir() || !m.isSolid() || Tag.LOGS.isTagged(m) || Tag.LEAVES.isTagged(m)
                    || m == Material.MUSHROOM_STEM || m == Material.BROWN_MUSHROOM_BLOCK || m == Material.RED_MUSHROOM_BLOCK
                    || m == Material.BAMBOO || m == Material.SNOW || m == Material.POWDER_SNOW || m == Material.BEE_NEST
                    || m == Material.PUMPKIN || m == Material.MELON || m == Material.CACTUS) continue;
            return y;
        }
        return y;
    }

    private void becomeReady() {
        ready = true;
        world.setSpawnLocation(spawn());
        housing.prepare(world, cx, cz, y0, plan);
        housing.ensureInteriors();
        NeyroInteriors.ensure(plugin, world, cx, cz, y0, plan);
        try {
            spawnDecor();
        } catch (RuntimeException e) {
            plugin.getLogger().severe("Neyro-City: decorations failed: " + e);
            e.printStackTrace();
        }
        int trades = 0;
        for (CityShops.Type t : CityShops.TYPES) {
            try {
                trades += t.recipes().size();
                if (t.recipes(1.5).size() != t.recipes().size() || t.recipes(2.0).size() != t.recipes().size()
                        || t.recipes(0.9).size() != t.recipes().size()) throw new IllegalStateException("karma prices lost trades");
            } catch (RuntimeException e) {
                plugin.getLogger().severe("Neyro-City: shop " + t.id() + " has broken trades: " + e);
            }
        }
        plugin.getLogger().info("Neyro-City ready: spawn " + (cx) + ", " + (y0 + 1) + ", " + (cz + 9)
                + ", " + traders.size() + " traders with " + trades + " trades, ship deck at y=" + shipY);
    }

    // ================================================================== decorations

    private void removeDecor() {
        traders.clear();
        housing.clearAgents();
        folkNpcs.clear();
        guards.clear();
        talkers.clear();
        lookers.clear();
        ads.clear();
        showcase.clear();
        cars.clear();
        if (world == null || !located) return;
        BoundingBox box = new BoundingBox(cx - CityPlan.EXT - 4, world.getMinHeight(), cz - CityPlan.EXT - 4,
                cx + CityPlan.EXT + 5, world.getMaxHeight(), cz + CityPlan.EXT + 5);
        for (Entity e : world.getNearbyEntities(box, e -> e.getScoreboardTags().contains(TAG))) e.remove();
    }

    private <T extends Entity> T tag(T e) {
        e.addScoreboardTag(TAG);
        e.setPersistent(false);
        return e;
    }

    private TextDisplay text(Location l, Component c, float scale, Display.Billboard bb, int bgArgb) {
        return world.spawn(l, TextDisplay.class, t -> {
            tag(t);
            t.text(c);
            t.setBillboard(bb);
            t.setBackgroundColor(Color.fromARGB(bgArgb));
            t.setShadowed(true);
            t.setLineWidth(300);
            t.setViewRange(4f);
            t.setBrightness(new Display.Brightness(15, 15));
            t.setTransformation(new Transformation(new Vector3f(), new AxisAngle4f(), new Vector3f(scale, scale, scale), new AxisAngle4f()));
        });
    }

    private BlockDisplay box(Location l, Material m, float sx, float sy, float sz, float tx, float ty, float tz, boolean glow) {
        return world.spawn(l, BlockDisplay.class, d -> {
            tag(d);
            d.setBlock(m.createBlockData());
            d.setShadowRadius(0);
            d.setShadowStrength(0);
            d.setViewRange(6f);
            if (glow) d.setBrightness(new Display.Brightness(15, 15));
            d.setTransformation(new Transformation(new Vector3f(tx, ty, tz), new AxisAngle4f(), new Vector3f(sx, sy, sz), new AxisAngle4f()));
        });
    }

    private static Component line(String s, int rgb, boolean bold) {
        Component c = Component.text(s, TextColor.color(rgb));
        return bold ? c.decorate(TextDecoration.BOLD) : c;
    }

    private void spawnDecor() {
        removeDecor();
        // ---- the beam of light from the plaza up to the belly of the ship
        float beamH = (float) ((shipY - 18) - (y0 + 1));
        Location base = pad();
        box(base, Material.LIGHT_BLUE_STAINED_GLASS, 1.6f, beamH, 1.6f, -0.8f, 0, -0.8f, true);
        box(base, Material.WHITE_STAINED_GLASS, 0.5f, beamH, 0.5f, -0.25f, 0, -0.25f, true);

        // ---- city name over the plaza
        text(at(0.5, 23, 0.5, 0), Component.text()
                .append(line("NEYRO", ORANGE, true)).append(line("-CITY", CYAN, true)).build(), 7f, Display.Billboard.CENTER, 0);
        text(at(0.5, 20.6, 0.5, 0), line("БЕЗОПАСНАЯ ЗОНА · ТОРГОВЛЯ · СПАВН", 0xFFE6C8, false), 2.2f, Display.Billboard.CENTER, 0x66000000);
        text(at(0.5, 3.4, 0.5, 0), Component.text()
                .append(line("▲ ЛИФТ НА КОРАБЛЬ NEYRO-1 ▲", CYAN, true)).append(Component.newline())
                .append(line("встань в луч", 0xFFFFFF, false)).build(), 1.1f, Display.Billboard.CENTER, 0x88001018);

        // ---- shops: sign, trader, goods on the counter
        for (CityPlan.Shop s : plan.shops()) spawnShop(s);
        for (NeyroHousing.House house : housing.houses()) {
            Location door = housing.outside(house);
            Mannequin agent = mannequin(door.clone().add(1.5, 0, 0), "Агент NEYRO-CORP",
                    "ПКМ — купить резиденцию №" + house.number() + " · $" + house.price(), "alex", true,
                    Color.fromRGB(0x23, 0x65, 0xA4), Material.EMERALD, null, true);
            housing.registerAgent(agent, house);
            text(door.clone().add(0, 3.5, 0), Component.text("РЕЗИДЕНЦИЯ №" + house.number() + "  ·  $" + house.price(),
                    TextColor.color(0xFFB000)), 1.25f, Display.Billboard.CENTER, 0x99050010);
        }
        for (CityFolk.Npc n : CityFolk.ALL) {
            CityFolk.Race r = n.race();
            Mannequin m = mannequin(at(n.x(), 1, n.z(), 0), n.name(), r.name() + " · " + n.job() + " · ПКМ — говорить",
                    r.plainSkin(), r.slim(), Color.fromRGB((r.outfit() >> 16) & 255, (r.outfit() >> 8) & 255, r.outfit() & 255),
                    null, null, true);
            if (m != null) folkNpcs.put(m.getUniqueId(), n);
        }

        // Public radio booth operated by the crew of NEYRO-1.
        Location radio = at(-18.5, 1, -18.5, 135f);
        // The original flat display ended exactly on the street surface. Its rotated
        // top face overlapped the road and produced a large black flickering patch.
        box(radio, Material.CYAN_STAINED_GLASS, 5, 0.12f, 4, -2.5f, 3.15f, -2, true);
        box(radio, Material.PURPLE_CONCRETE, 0.3f, 3.4f, 0.3f, -2.4f, 0, -1.9f, true);
        box(radio, Material.PURPLE_CONCRETE, 0.3f, 3.4f, 0.3f, 2.1f, 0, 1.6f, true);
        text(at(-18.5, 4.45, -18.5, 135f), Component.text()
                .append(line("◈ NEYRO-RADIO ◈", CYAN, true)).append(Component.newline())
                .append(line("Голос NEYRO-CORP · новости и музыка", 0xFFFFFF, false)).build(),
                1.35f, Display.Billboard.CENTER, 0xBB071126);
        mannequin(at(-18.5, 1, -18.5, 135f), "Ведущая Рэй", "ПКМ — эфир Нейро-Радио", "makena", true,
                Color.fromRGB(0x20, 0x70, 0xA0), null, "radio", true);

        // Neon casino counter on the opposite side of the main avenue.
        Location casino = at(18.5, 1, -18.5, 225f);
        // Keep the street as the floor; do not place a second coplanar display here.
        box(casino, Material.MAGENTA_STAINED_GLASS, 7, 0.15f, 5, -3.5f, 3.1f, -2.5f, true);
        box(casino, Material.PURPLE_CONCRETE, 0.4f, 3.5f, 0.4f, -3.2f, 0, -2.2f, true);
        box(casino, Material.PURPLE_CONCRETE, 0.4f, 3.5f, 0.4f, 2.8f, 0, 1.8f, true);
        text(at(18.5, 4.5, -18.5, 225f), Component.text()
                .append(line("◈ NEYRO-CASINO ◈", MAGENTA, true)).append(Component.newline())
                .append(line("NEYRO$ · /casino rules", 0xFFFFFF, false)).build(),
                1.4f, Display.Billboard.CENTER, 0xBB16051E);
        mannequin(casino, "Крупье Вега", "ПКМ — казино NEYRO-CORP", "alex", true,
                Color.fromRGB(0x8C, 0x25, 0xA8), Material.EMERALD, "casino", true);

        // ---- the guide next to the spawn point
        mannequin(at(4.5, 1, 9.5, 90f), "Гид Нова", "ПКМ — как тут всё устроено", "alex", true,
                Color.fromRGB(0xFF, 0x8C, 0x00), null, "guide", true);
        mannequin(at(8.5, 1, 9.5, 90f), "Представитель NEYRO-CORP", "ПКМ — донатные ранги", "alex", true,
                Color.fromRGB(0xAA, 0x28, 0xD0), Material.NETHER_STAR, "donate", true);
        text(at(8.5, 4.3, 9.5, 0), line("✦ ДОНАТЫ NEYRO-CORP ✦", MAGENTA, true), 1.25f, Display.Billboard.CENTER, 0xAA12051D);

        // ---- clone guards: gates, wall corners, the beam, patrols on the avenues
        spawnGuards();

        int[][] gates = {{0, -1}, {1, 0}, {0, 1}, {-1, 0}};
        for (int[] g : gates) {
            float yaw = (float) Math.toDegrees(Math.atan2(-g[0], g[1]));
            // gate signs: outside and inside
            double ox = g[0] * 64.5 + 0.5 + g[0] * 0.65, oz = g[1] * 64.5 + 0.5 + g[1] * 0.65;
            if (g[0] == 0) ox = 0.5;
            if (g[1] == 0) oz = 0.5;
            text(at(ox, 10.6, oz, yaw), Component.text()
                    .append(line("NEYRO", ORANGE, true)).append(line("-CITY", CYAN, true)).append(Component.newline())
                    .append(line("безопасная зона · PvP выключен", 0x7CFF4F, false)).build(), 2.4f, Display.Billboard.FIXED, 0x99050010);
            double ix = g[0] == 0 ? 0.5 : g[0] * 62.5 + 0.5 - g[0] * 0.65, iz = g[1] == 0 ? 0.5 : g[1] * 62.5 + 0.5 - g[1] * 0.65;
            text(at(ix, 10.6, iz, yaw + 180f), Component.text()
                    .append(line("ВЫХОД ИЗ ГОРОДА", 0xFF5555, true)).append(Component.newline())
                    .append(line("за стеной опасно · /city — назад", 0xFFE6C8, false)).build(), 1.6f, Display.Billboard.FIXED, 0x99050010);
            Component warning = Component.text().append(line("⚠  ОПАСНАЯ ЗОНА  ⚠", 0xFF3030, true)).build();
            text(at(ix, 7.0, iz, yaw + 180f), warning, 1.7f, Display.Billboard.FIXED, 0xCC250000);
            text(at(ox, 7.0, oz, yaw), warning, 1.7f, Display.Billboard.FIXED, 0xCC250000);
        }

        // ---- citizens on the plaza
        String[] skins = {"sunny", "noor", "zuri", "makena", "ari", "steve"};
        int[][] spots = {{10, -10}, {-11, 9}, {9, 12}, {-12, -10}, {15, 1}, {-15, -1}};
        int[] colors = {0xFF4FD8, 0x40E8FF, 0x7CFF4F, 0xFFD84A, 0xB060FF, 0xFF8C00};
        String[] names = {"Мира", "Джет", "Ника", "Рокс", "Лиам", "Сайфер"};
        for (int i = 0; i < spots.length; i++) {
            double px = spots[i][0] + 0.5, pz = spots[i][1] + 0.5;
            float yaw = (float) Math.toDegrees(Math.atan2(px, -pz));          // looking at the beam
            int c = colors[i];
            mannequin(at(px, 1, pz, yaw), names[i], "житель", skins[i], i % 2 == 0,
                    Color.fromRGB((c >> 16) & 255, (c >> 8) & 255, c & 255), null, "citizen" + i, false);
        }

        // ---- street names where the avenues leave the plaza
        String[][] streets = {{"0", "-1", "Проспект Нейронов"}, {"1", "0", "Неоновая улица"},
                {"0", "1", "Улица Киберпанка"}, {"-1", "0", "Кремниевый проспект"}};
        for (String[] s : streets) {
            int fx = Integer.parseInt(s[0]), fz = Integer.parseInt(s[1]);
            double px = fx * 20 + 0.5 + (fz != 0 ? 5.6 * -fz : 0), pz = fz * 20 + 0.5 + (fx != 0 ? 5.6 * fx : 0);
            float yaw = (float) Math.toDegrees(Math.atan2(fx, -fz));            // facing the plaza
            text(at(px, 3.0, pz, yaw), Component.text()
                    .append(line(s[2], CYAN, true)).append(Component.newline())
                    .append(line("→ ворота · " + (fz < 0 ? "север" : fz > 0 ? "юг" : fx > 0 ? "восток" : "запад"), 0xB0B0B0, false)).build(),
                    1.0f, Display.Billboard.FIXED, 0xAA050010);
        }

        // ---- adverts on the skyscrapers, NEYRO-CORP logo on its tower
        String[][] adText = {
                {"NEYRO-CORP", "Корпорация экипажа NEYRO-1"},
                {"ИМПЛАНТЫ ПАМЯТИ", "два по цене одного"},
                {"СКАЙБЛОК", "свой остров в небе · портал на NEYRO-1"},
                {"LASERTAG", "проверь рефлексы · портал на NEYRO-1"},
                {"НЕОН-РАМЕН", "горячий рамен 24/7 у площади"},
                {"ЛОМБАРД 24/7", "продай добычу за изумруды"},
                {"КОСМОКИНО", "/kino — премьера каждый вечер"},
                {"ГОНКИ НА ТЕРРАКСЕ", "/race — спидеры ждут"},
                {"ЧЁРНЫЙ РЫНОК", "не ищи нас — мы сами найдём"},
                {"ネオン・シティ", "добро пожаловать в будущее"},
                {"STARPASS", "/pass — награды сезона"},
                {"НЕ ЧИТЕРЬ", "Саманта видит всё"},
        };
        int[] adColors = {MAGENTA, CYAN, 0xFFD84A, 0x7CFF4F, ORANGE, 0xB060FF};
        int n = 0;
        for (CityPlan.Tower t : plan.towers()) {
            if (t.h1() < 16) continue;
            // the face looking towards the nearer avenue
            int tcx = t.cx(), tcz = t.cz();
            boolean xFace = Math.abs(tcx) < Math.abs(tcz);
            double px, pz;
            float yaw;
            if (xFace) {
                int sgn = tcx > 0 ? -1 : 1;
                px = (sgn < 0 ? t.x0() - 0.08 : t.x1() + 1.08);
                pz = (t.z0() + t.z1() + 1) / 2.0;
                yaw = (float) Math.toDegrees(Math.atan2(-sgn, 0));
            } else {
                int sgn = tcz > 0 ? -1 : 1;
                pz = (sgn < 0 ? t.z0() - 0.08 : t.z1() + 1.08);
                px = (t.x0() + t.x1() + 1) / 2.0;
                yaw = (float) Math.toDegrees(Math.atan2(0, sgn));
            }
            double h = 6 + (t.h1() - 6) * 0.55;
            if (t.hq()) {
                text(at(px, t.h1() - 9, pz, yaw), Component.text()
                        .append(line("NEYRO", ORANGE, true)).append(line("-CORP", CYAN, true)).append(Component.newline())
                        .append(line("КОРАБЛЬ NEYRO-1 · ГОРОД · РАДИО", 0xFFFFFF, false)).build(), 7f, Display.Billboard.FIXED, 0);
                continue;
            }
            String[] a = adText[n % adText.length];
            int col = adColors[n % adColors.length];
            n++;
            ads.add(text(at(px, h, pz, yaw), Component.text()
                    .append(line(a[0], col, true)).append(Component.newline())
                    .append(line(a[1], 0xFFFFFF, false)).build(), 2.6f, Display.Billboard.FIXED, 0xB0050010));
        }

        // ---- flying cars
        cars.add(new Car(superellipse(32, 2, 14, 1), 900, 0.0, Material.BLACK_CONCRETE, Material.MAGENTA_CONCRETE));
        cars.add(new Car(superellipse(32, 2, 17, -1), 950, 0.5, Material.WHITE_CONCRETE, Material.CYAN_CONCRETE));
        cars.add(new Car(stadium(true, 42, 5, 9), 800, 0.0, Material.YELLOW_CONCRETE, Material.ORANGE_CONCRETE));
        cars.add(new Car(stadium(false, 42, 5, 11), 820, 0.3, Material.GRAY_CONCRETE, Material.LIME_CONCRETE));
        cars.add(new Car(superellipse(50, 2, 60, 1), 900, 0.2, Material.BLACK_CONCRETE, Material.PURPLE_CONCRETE));
        cars.add(new Car(superellipse(58, 2, 50, -1), 1000, 0.7, Material.RED_CONCRETE, Material.CYAN_CONCRETE));
    }

    private void spawnShop(CityPlan.Shop s) {
        CityShops.Type t = s.type();
        float yaw = s.yaw();
        // sign over the entrance, just in front of the neon band (outer face of the front wall)
        double[] signAt = s.at(0, -0.55);
        text(at(signAt[0] + 0.5, 5.15, signAt[1] + 0.5, yaw), Component.text()
                .append(line(t.title(), t.color(), true)).append(Component.newline())
                .append(line(t.subtitle(), 0xE0E0E0, false)).build(), 1.5f, Display.Billboard.FIXED, 0x99050010);
        // the trader behind the counter
        double[] npcAt = s.at(0, 4);
        Mannequin m = mannequin(at(npcAt[0] + 0.5, 1, npcAt[1] + 0.5, yaw), t.npc(), "торговец · ПКМ — торговля", t.skin(), t.slim(),
                t.outfit(), null, null, true);
        if (m != null) traders.put(m.getUniqueId(), t);
        // the goods turning slowly over the counter
        for (int i = 0; i < t.showcase().length; i++) {
            double[] it = s.at((i - 1) * 2, 2);
            Material mat = t.showcase()[i];
            showcase.add(world.spawn(at(it[0] + 0.5, 2.55, it[1] + 0.5, yaw), ItemDisplay.class, d -> {
                tag(d);
                d.setItemStack(new ItemStack(mat));
                d.setViewRange(1.5f);
                d.setBrightness(new Display.Brightness(15, 15));
                d.setTransformation(new Transformation(new Vector3f(), new AxisAngle4f(), new Vector3f(0.6f, 0.6f, 0.6f), new AxisAngle4f()));
            }));
        }
    }

    private Mannequin mannequin(Location l, String name, String description, String skin, boolean slim, Color outfit,
                                Material hand, String talk, boolean looks) {
        try {
            Mannequin m = world.spawn(l, Mannequin.class, e -> {
                tag(e);
                e.setInvulnerable(true);
                e.setSilent(true);
                e.setGravity(false);
                e.setCollidable(false);
                e.setImmovable(true);
                e.setRemoveWhenFarAway(false);
                e.customName(Component.text(name, NamedTextColor.WHITE).decorate(TextDecoration.BOLD));
                e.setCustomNameVisible(true);
                e.setDescription(Component.text(description, NamedTextColor.GRAY));
                try {
                    e.setProfile(ResolvableProfile.resolvableProfile()
                            .skinPatch(sp -> sp.body(Key.key("minecraft", "entity/player/" + (slim ? "slim/" : "wide/") + skin))
                                    .model(slim ? PlayerTextures.SkinModel.SLIM : PlayerTextures.SkinModel.CLASSIC))
                            .build());
                } catch (RuntimeException ex) {
                    plugin.getLogger().fine("Mannequin skin not set: " + ex);
                }
                var eq = e.getEquipment();
                eq.setChestplate(leather(Material.LEATHER_CHESTPLATE, outfit));
                eq.setLeggings(leather(Material.LEATHER_LEGGINGS, Color.fromRGB(0x14, 0x14, 0x1C)));
                eq.setBoots(leather(Material.LEATHER_BOOTS, Color.fromRGB(0x10, 0x10, 0x14)));
                if (hand == Material.NETHERITE_SWORD) {
                    eq.setHelmet(new ItemStack(Material.NETHERITE_HELMET));
                    eq.setChestplate(new ItemStack(Material.NETHERITE_CHESTPLATE));
                }
                if (hand != null) eq.setItemInMainHand(new ItemStack(hand));
            });
            if (talk != null) talkers.put(m.getUniqueId(), talk);
            if (looks) lookers.put(m, l.getYaw());
            return m;
        } catch (RuntimeException e) {
            plugin.getLogger().warning("Neyro-City: could not spawn NPC " + name + ": " + e);
            return null;
        }
    }

    private static ItemStack leather(Material m, Color c) {
        ItemStack s = new ItemStack(m);
        s.editMeta(LeatherArmorMeta.class, meta -> meta.setColor(c));
        return s;
    }

    // ================================================================== flying cars

    private final class Car {
        final List<BlockDisplay> parts = new ArrayList<>();
        final DoubleFunction<double[]> path;
        final double period, phase;

        Car(DoubleFunction<double[]> path, double period, double phase, Material body, Material glow) {
            this.path = path;
            this.period = period;
            this.phase = phase;
            Location l = where(0);
            parts.add(box(l, body, 1.6f, 0.55f, 3.2f, -0.8f, 0, -1.6f, false));
            parts.add(box(l, Material.LIGHT_BLUE_STAINED_GLASS, 1.2f, 0.45f, 1.4f, -0.6f, 0.55f, -0.6f, false));
            parts.add(box(l, Material.SEA_LANTERN, 1.4f, 0.2f, 0.1f, -0.7f, 0.2f, 1.6f, true));
            parts.add(box(l, Material.RED_CONCRETE, 1.4f, 0.2f, 0.1f, -0.7f, 0.2f, -1.7f, true));
            parts.add(box(l, glow, 1.3f, 0.06f, 2.8f, -0.65f, -0.08f, -1.4f, true));
            parts.add(box(l, body, 0.22f, 0.25f, 2.4f, -1.0f, 0.08f, -1.2f, false));
            parts.add(box(l, body, 0.22f, 0.25f, 2.4f, 0.78f, 0.08f, -1.2f, false));
            for (BlockDisplay d : parts) d.setTeleportDuration(2);
        }

        Location where(long t) {
            double s = ((t / period) + phase) % 1.0;
            double[] a = path.apply(s), b = path.apply((s + 0.002) % 1.0);
            float yaw = (float) Math.toDegrees(Math.atan2(-(b[0] - a[0]), b[2] - a[2]));
            return at(a[0] + 0.5, a[1], a[2] + 0.5, yaw);
        }

        void move(long t) {
            Location l = where(t);
            for (BlockDisplay d : parts) if (d.isValid()) d.teleport(l);
        }
    }

    /** A loop around the centre: n = 2 is a circle, bigger n a rounded square (along the cross streets). */
    private static DoubleFunction<double[]> superellipse(double r, double n, double h, int dir) {
        return s -> {
            double a = 2 * Math.PI * s * dir, c = Math.cos(a), sn = Math.sin(a);
            return new double[]{r * Math.copySign(Math.pow(Math.abs(c), 2 / n), c), h, r * Math.copySign(Math.pow(Math.abs(sn), 2 / n), sn)};
        };
    }

    /** Along an avenue and back on the other lane, turning round outside the gates. */
    private static DoubleFunction<double[]> stadium(boolean alongX, double half, double lane, double h) {
        double straight = 2 * half, turn = Math.PI * lane, total = 2 * straight + 2 * turn;
        return s -> {
            double d = s * total, along, across;
            if (d < straight) { along = -half + d; across = lane; }
            else if (d < straight + turn) { double a = (d - straight) / lane; along = half + Math.sin(a) * lane; across = Math.cos(a) * lane; }
            else if (d < 2 * straight + turn) { along = half - (d - straight - turn); across = -lane; }
            else { double a = (d - 2 * straight - turn) / lane; along = -half - Math.sin(a) * lane; across = -Math.cos(a) * lane; }
            return alongX ? new double[]{along, h, across} : new double[]{across, h, along};
        };
    }

    // ================================================================== every tick

    private void tick() {
        tick++;
        if (!ready || world == null) return;
        List<Player> players = world.getPlayers();
        if (players.isEmpty()) return;
        if (tick % 10 == 0) musicTick();
        Location c = pad();
        boolean near = false;
        for (Player p : players) if (p.getLocation().distanceSquared(c) < 200 * 200) { near = true; break; }
        if (!near) return;

        if (tick % 2 == 0) for (Car car : cars) car.move(tick);
        if (tick % 2 == 0) guardTick(tick);

        if (tick % 2 == 0) for (Player p : players) checkPad(p, c);

        if (tick % 4 == 0) {
            // light rising in the beam, a soft ring around the pad
            Particle.DustOptions cyan = new Particle.DustOptions(Color.fromRGB(0x40, 0xE8, 0xFF), 1.2f);
            world.spawnParticle(Particle.END_ROD, c.clone().add(0, 0.3 + rnd.nextDouble() * 3, 0), 2, 0.35, 0.4, 0.35, 0.04);
            double a = tick * 0.15;
            for (int i = 0; i < 3; i++) {
                double ang = a + i * Math.PI * 2 / 3;
                world.spawnParticle(Particle.DUST, c.clone().add(Math.cos(ang) * 2.2, 0.15, Math.sin(ang) * 2.2), 1, 0, 0, 0, 0, cyan);
            }
        }

        if (tick % 10 == 0) {
            for (Player p : players) zoneMessage(p);
            for (Map.Entry<Mannequin, Float> e : lookers.entrySet()) look(e.getKey(), e.getValue());
        }

        if (tick % 20 == 0 && !ads.isEmpty()) {
            // a flickering neon sign now and then
            TextDisplay ad = ads.get(rnd.nextInt(ads.size()));
            if (ad.isValid()) {
                ad.setTextOpacity((byte) 70);
                Bukkit.getScheduler().runTaskLater(plugin, () -> { if (ad.isValid()) ad.setTextOpacity((byte) -1); }, 2L + rnd.nextInt(3));
            }
        }

        if (tick % 40 == 0) {
            float angle = (float) ((tick / 40) % 4 * Math.PI / 2 + Math.PI / 2);
            for (ItemDisplay d : showcase) {
                if (!d.isValid()) continue;
                d.setInterpolationDelay(0);
                d.setInterpolationDuration(40);
                d.setTransformation(new Transformation(new Vector3f(), new AxisAngle4f(angle, 0, 1, 0), new Vector3f(0.6f, 0.6f, 0.6f), new AxisAngle4f()));
            }
            sweepMobs();
        }
    }

    private void look(Mannequin m, float baseYaw) {
        if (!m.isValid()) return;
        Player best = null;
        double bd = 49;
        for (Player p : m.getWorld().getPlayers()) {
            double d = p.getLocation().distanceSquared(m.getLocation());
            if (d < bd) { bd = d; best = p; }
        }
        if (best != null) {
            Location e = best.getEyeLocation();
            m.lookAt(e.getX(), e.getY(), e.getZ(), LookAnchor.EYES);
        } else if (Math.abs(m.getLocation().getYaw() - baseYaw) > 1) {
            m.setRotation(baseYaw, 0);
        }
    }

    private void zoneMessage(Player p) {
        boolean in = safe(p.getLocation());
        UUID id = p.getUniqueId();
        if (in && inZone.add(id)) {
            if (!plugin.beamDrop().busy(p)) HudBoard.notice(p, Component.text("✔ Безопасная зона NEYRO-CITY: PvP выключен, мобов нет", TextColor.color(0x7CFF4F)));
        } else if (!in && inZone.remove(id)) {
            HudBoard.notice(p, Component.text("⚠ Опасная зона · строй за отметкой 500 блоков от спавна", TextColor.color(0xFF5555)));
            p.showTitle(net.kyori.adventure.title.Title.title(Component.text("⚠ ОПАСНАЯ ЗОНА ⚠", NamedTextColor.RED), Component.text("Ты покинул NEYRO-CITY", NamedTextColor.YELLOW)));
            if (plugin.voice() != null && plugin.voice().hasVoice(p))
                p.playSound(Sound.sound(Key.key("neyrocraft", "sam_city_warning"), Sound.Source.VOICE, 1f, 1f), Sound.Emitter.self());
        }
    }

    private void sweepMobs() {
        BoundingBox city = new BoundingBox(cx - CityPlan.HALF, world.getMinHeight(), cz - CityPlan.HALF,
                cx + CityPlan.HALF + 1, world.getMaxHeight(), cz + CityPlan.HALF + 1);
        BoundingBox ship = new BoundingBox(cx - SHIP_X, shipY - SHIP_DOWN, cz - SHIP_Z, cx + SHIP_X + 1, shipY + SHIP_UP, cz + SHIP_Z + 1);
        for (BoundingBox b : new BoundingBox[]{city, ship}) {
            for (Entity e : world.getNearbyEntities(b, e -> e instanceof Enemy && e.customName() == null)) {
                world.spawnParticle(Particle.POOF, e.getLocation().add(0, 0.8, 0), 12, 0.3, 0.5, 0.3, 0.02);
                e.remove();
            }
        }
    }

    // ================================================================== the beam: up to the ship

    private void checkPad(Player p, Location c) {
        UUID id = p.getUniqueId();
        Location l = p.getLocation();
        double dx = l.getX() - c.getX(), dz = l.getZ() - c.getZ(), dy = l.getY() - c.getY();
        boolean on = dx * dx + dz * dz <= 1.6 * 1.6 && dy > -0.2 && dy < 2.5;
        if (!on) {
            onPad.remove(id);
            if (dx * dx + dz * dz < 25 && dy > -0.5 && dy < 3 && tick % 20 == 0 && !plugin.beamDrop().busy(p))
                HudBoard.notice(p, Component.text("▲ Встань в луч — лифт на корабль NEYRO-1", TextColor.color(CYAN)));
            return;
        }
        if (!onPad.add(id)) return;                         // only when stepping into the beam
        if (plugin.beamDrop().busy(p) || BeamTransfer.busy(p) || !plugin.authed(p)) return;
        Long until = padCooldown.get(id);
        if (until != null && until > System.currentTimeMillis()) return;
        padCooldown.put(id, System.currentTimeMillis() + 5000);
        plugin.liftToShip(p, spawn());
    }

    // ================================================================== the beam: down into the city

    static BeamDrop.Style cityStyle() {
        return new BeamDrop.Style(CYAN, MAGENTA,
                Component.text().append(line("NEYRO", ORANGE, true)).append(line("-CITY", CYAN, true)).build(),
                Component.text("спуск по лучу с корабля NEYRO-1", NamedTextColor.GRAY),
                Component.text().append(line("NEYRO", ORANGE, true)).append(line("-CITY", CYAN, true)).build(),
                Component.text("безопасная зона · магазины вокруг площади", NamedTextColor.GRAY));
    }

    /**
     * From the ship into the city: the player appears under the belly of the ship in a white flash
     * and slides down the beam of light onto the plaza, circling it slowly.
     */
    void dropIntoCity(Player p) {
        if (!ready) return;
        UUID id = p.getUniqueId();
        onPad.add(id);
        plugin.beamDrop().run(p, pad(), shipY - 20.5, DROP_TICKS, cityStyle(), false, pl -> {
            onPad.add(pl.getUniqueId());
            padCooldown.put(pl.getUniqueId(), System.currentTimeMillis() + 3000);
            if (pl.getGameMode() == GameMode.ADVENTURE) pl.setGameMode(GameMode.SURVIVAL);
        });
    }

    @EventHandler
    public void onWorldChange(org.bukkit.event.player.PlayerChangedWorldEvent e) {
        long[] s = music.remove(e.getPlayer().getUniqueId());
        if (s != null) stopMusic(e.getPlayer(), s);
    }

    @EventHandler
    public void onQuit(PlayerQuitEvent e) {
        UUID id = e.getPlayer().getUniqueId();
        BukkitTask w = warmups.remove(id);
        if (w != null) w.cancel();
        inZone.remove(id);
        onPad.remove(id);
        padCooldown.remove(id);
        music.remove(id);
        nextSurvivalMusic.remove(id);
        survivalPlaying.remove(id);
        warned.remove(id);
    }

    // ================================================================== clone guards

    /** A guard of the city: white armour, a blaster, never hurt, shoots monsters, watches villains. */
    /**
     * Every guard exists twice in the same spot: the clone trooper skin from the NeyroCraft pack
     * ({@code m}, shown to players who have the pack) and a white-armour version ({@code alt}, for
     * everyone else, who would otherwise see a missing texture). Both move and aim together.
     */
    private final class Guard {
        final Mannequin m, alt;
        final double[][] path;          // patrol points (plaza-relative x, z) or null for a post
        int next = 1, cooldown;
        Guard(Mannequin m, Mannequin alt, double[][] path) { this.m = m; this.alt = alt; this.path = path; }

        void teleport(Location l) {
            m.teleport(l);
            if (alt != null && alt.isValid()) alt.teleport(l);
        }

        void lookAt(double x, double y, double z) {
            m.lookAt(x, y, z, LookAnchor.EYES);
            if (alt != null && alt.isValid()) alt.lookAt(x, y, z, LookAnchor.EYES);
        }
    }

    /** Pack players see the clone skins, the others the armoured guards. */
    private void guardVisibility() {
        for (Player p : world.getPlayers()) {
            boolean pack = plugin.voice() != null && plugin.voice().hasVoice(p);
            for (Guard g : guards) {
                if (g.alt == null) continue;
                if (pack) { p.showEntity(plugin, g.m); p.hideEntity(plugin, g.alt); }
                else { p.showEntity(plugin, g.alt); p.hideEntity(plugin, g.m); }
            }
        }
    }

    private final List<Guard> guards = new ArrayList<>();
    private final Map<UUID, Long> warned = new HashMap<>();
    private int guardNo;

    private static ItemStack blaster() {
        ItemStack it = new ItemStack(Material.STICK);
        it.editMeta(m -> {
            m.setItemModel(new org.bukkit.NamespacedKey("neyrocraft", "blaster_blue"));
            m.displayName(Component.text("Бластер стража", TextColor.color(CYAN)));
        });
        return it;
    }

    private void spawnGuard(double px, double pz, double h, float yaw, double[][] path) {
        String name = "Клон-страж КС-" + (1100 + 7 * (++guardNo) + guardNo * guardNo % 13);
        boolean sergeant = guardNo % 5 == 0;
        Location l = at(px, h, pz, yaw);
        try {
            Mannequin m = guardBody(l, name, sergeant, true);
            Mannequin alt = guardBody(l, name, sergeant, false);
            talkers.put(m.getUniqueId(), "guard");
            talkers.put(alt.getUniqueId(), "guard");
            guards.add(new Guard(m, alt, path));
        } catch (RuntimeException e) {
            plugin.getLogger().warning("Neyro-City: could not spawn guard: " + e);
        }
    }

    /** {@code clone}: the trooper skin from the pack, no armour; otherwise white leather armour on a plain skin. */
    private Mannequin guardBody(Location l, String name, boolean sergeant, boolean clone) {
        return world.spawn(l, Mannequin.class, e -> {
            tag(e);
            e.setVisibleByDefault(false);
            e.setInvulnerable(true);
            e.setSilent(true);
            e.setGravity(false);
            e.setCollidable(false);
            e.setImmovable(true);
            e.setRemoveWhenFarAway(false);
            e.customName(Component.text(name, sergeant ? TextColor.color(0xFF9A30) : NamedTextColor.WHITE).decorate(TextDecoration.BOLD));
            e.setCustomNameVisible(true);
            e.setDescription(Component.text(sergeant ? "сержант стражи" : "страж Neyro-City", NamedTextColor.GRAY));
            try {
                Key skin = clone ? Key.key("neyrocraft", "entity/clone_trooper") : Key.key("minecraft", "entity/player/wide/steve");
                e.setProfile(ResolvableProfile.resolvableProfile()
                        .skinPatch(sp -> sp.body(skin).model(PlayerTextures.SkinModel.CLASSIC))
                        .build());
            } catch (RuntimeException ignored) { }
            var eq = e.getEquipment();
            if (!clone) {
                Color white = Color.fromRGB(0xF2, 0xF2, 0xF0);
                eq.setHelmet(leather(Material.LEATHER_HELMET, white));
                eq.setChestplate(leather(Material.LEATHER_CHESTPLATE, sergeant ? Color.fromRGB(0xE0, 0x80, 0x20) : white));
                eq.setLeggings(leather(Material.LEATHER_LEGGINGS, white));
                eq.setBoots(leather(Material.LEATHER_BOOTS, Color.fromRGB(0x30, 0x30, 0x34)));
            }
            eq.setItemInMainHand(blaster());
        });
    }

    private void spawnGuards() {
        guards.clear();
        guardNo = 0;
        int[][] gates = {{0, -1}, {1, 0}, {0, 1}, {-1, 0}};
        for (int[] g : gates) {
            float out = (float) Math.toDegrees(Math.atan2(-g[0], g[1]));
            for (int side = -1; side <= 1; side += 2) {                       // two at every gate, on the curbs in the gate mouth, facing out
                double px = g[0] != 0 ? g[0] * 62 + 0.5 : side * 3 + 0.5;
                double pz = g[1] != 0 ? g[1] * 62 + 0.5 : side * 3 + 0.5;
                spawnGuard(px, pz, 1, out, null);
            }
        }
        for (int sx = -1; sx <= 1; sx += 2)                                    // sentries on the wall corners
            for (int sz = -1; sz <= 1; sz += 2)
                spawnGuard(sx * 63 + 0.5, sz * 63 + 0.5, 10, (float) Math.toDegrees(Math.atan2(-sx, sz)), null);
        spawnGuard(-3.5, 12.5, 1, 180f, null);                                // two at the beam
        spawnGuard(4.5, -12.5, 1, 0f, null);
        // patrols along the avenues, plaza edge to gate and back
        spawnGuard(3.5, -20.5, 1, 180f, new double[][]{{3.5, -20.5}, {3.5, -57.5}});
        spawnGuard(-2.5, 20.5, 1, 0f, new double[][]{{-2.5, 20.5}, {-2.5, 57.5}});
        spawnGuard(20.5, 3.5, 1, -90f, new double[][]{{20.5, 3.5}, {57.5, 3.5}});
        spawnGuard(-20.5, -2.5, 1, 90f, new double[][]{{-20.5, -2.5}, {-57.5, -2.5}});
        guardVisibility();
    }

    /** Patrols walk; every guard shoots monsters in range; villains get stared at and warned. */
    private void guardTick(long t) {
        for (Guard g : guards) {
            if (!g.m.isValid()) continue;
            if (g.cooldown > 0) g.cooldown -= 2;
            if (g.path != null && g.cooldown <= 10) walk(g);
            if (t % 6 == 0 && g.cooldown <= 0) shoot(g);
        }
        if (t % 40 == 0) guardVisibility();
        if (t % 20 == 0) {
            for (Player p : world.getPlayers()) {
                if (plugin.karma() == null || !plugin.karma().dark(p) || !safe(p.getLocation())) continue;
                Guard near = null;
                double best = 14 * 14;
                for (Guard g : guards) {
                    if (!g.m.isValid()) continue;
                    double d = g.m.getLocation().distanceSquared(p.getLocation());
                    if (d < best) { best = d; near = g; }
                }
                if (near == null) continue;
                Location e = p.getEyeLocation();
                near.lookAt(e.getX(), e.getY(), e.getZ());
                near.cooldown = Math.max(near.cooldown, 30);              // stops to watch the villain
                Long last = warned.get(p.getUniqueId());
                if (last == null || System.currentTimeMillis() - last > 45_000) {
                    warned.put(p.getUniqueId(), System.currentTimeMillis());
                    String[] lines = {"Стоять. Мы знаем, что ты сделал.", "Одно неверное движение — и ты пожалеешь.",
                            "Цены для таких, как ты, выше. Порядок есть порядок.", "Мы следим за тобой, преступник."};
                    p.sendMessage(Component.text()
                            .append(line("◈ " + PlainName.of(near.m) + ": ", 0xFF7070, false))
                            .append(Component.text(lines[rnd.nextInt(lines.length)], TextColor.color(0xFFE6C8))).build());
                    sound(p, "entity.iron_golem.hurt", 0.4f, 0.6f);
                }
            }
        }
    }

    private void walk(Guard g) {
        double[] goal = g.path[g.next];
        Location l = g.m.getLocation();
        double gx = cx + goal[0], gz = cz + goal[1];
        double dx = gx - l.getX(), dz = gz - l.getZ(), dist = Math.sqrt(dx * dx + dz * dz);
        if (dist < 0.3) {
            g.next = (g.next + 1) % g.path.length;
            g.cooldown = 40;                                                  // a short stop at each end
            return;
        }
        double step = Math.min(dist, 0.22);
        float yaw = (float) Math.toDegrees(Math.atan2(-dx, dz));
        g.teleport(new Location(world, l.getX() + dx / dist * step, y0 + 1, l.getZ() + dz / dist * step, yaw, 0f));
    }

    private void shoot(Guard g) {
        Location eye = g.m.getEyeLocation();
        LivingEntity target = null;
        double best = 26 * 26;
        for (Entity e : world.getNearbyEntities(eye, 26, 14, 26, e -> e instanceof Enemy && e instanceof LivingEntity le && !le.isDead())) {
            double d = e.getLocation().distanceSquared(eye);
            if (d < best && g.m.hasLineOfSight(e)) { best = d; target = (LivingEntity) e; }
        }
        if (target == null) return;
        Location aim = target.getLocation().add(0, target.getHeight() * 0.6, 0);
        g.lookAt(aim.getX(), aim.getY(), aim.getZ());
        org.bukkit.util.Vector dir = aim.toVector().subtract(eye.toVector());
        double len = dir.length();
        dir.normalize();
        Location muzzle = eye.clone().add(dir.clone().multiply(0.8)).add(0, -0.3, 0);
        Particle.DustOptions bolt = new Particle.DustOptions(Color.fromRGB(0x40, 0xC8, 0xFF), 1.1f);
        Particle.DustOptions core = new Particle.DustOptions(Color.fromRGB(0xE8, 0xFF, 0xFF), 0.6f);
        for (double s = 0.8; s < len; s += 0.35) {
            Location p = eye.clone().add(dir.clone().multiply(s)).add(0, -0.3 * (1 - s / len), 0);
            world.spawnParticle(Particle.DUST, p, 1, 0, 0, 0, 0, bolt);
            if (((int) (s / 0.35)) % 2 == 0) world.spawnParticle(Particle.DUST, p, 1, 0, 0, 0, 0, core);
        }
        world.spawnParticle(Particle.END_ROD, muzzle, 3, 0.05, 0.05, 0.05, 0.02);
        world.spawnParticle(Particle.ELECTRIC_SPARK, aim, 10, 0.2, 0.3, 0.2, 0.1);
        world.playSound(Sound.sound(Key.key("neyrocraft", "laser.shot"), Sound.Source.HOSTILE, 1.4f, 0.9f), eye.getX(), eye.getY(), eye.getZ());
        world.playSound(Sound.sound(Key.key("minecraft", "entity.blaze.shoot"), Sound.Source.HOSTILE, 0.25f, 2.0f), eye.getX(), eye.getY(), eye.getZ());
        target.damage(9.0, g.m);
        target.setVelocity(target.getVelocity().add(dir.clone().multiply(0.35).setY(0.15)));
        g.cooldown = 20;
    }

    // ================================================================== music

    private static final Key MUSIC = Key.key("neyrocraft", "city_music");
    private static final Key CYBER1 = Key.key("neyrocraft", "cyber_1"), CYBER2 = Key.key("neyrocraft", "cyber_2");
    private static final Key[] CITY_TRACKS = { MUSIC, CYBER1, CYBER2, Key.key("neyrocraft", "cyber_3"), Key.key("neyrocraft", "cyber_4"), Key.key("neyrocraft", "cyber_5") };
    private static final long MUSIC_TICKS = 1536;                  // 76.8 s
    private static final Key MUSIC_FALLBACK = Key.key("minecraft", "music_disc.creator");
    private static final Key RADIO_NEWS = Key.key("neyrocraft", "radio_news");
    private static final long FALLBACK_TICKS = 176 * 20;
    private final Map<UUID, long[]> music = new HashMap<>();       // {playing: 1 custom / 2 fallback, next start tick}
    private final Map<UUID, Integer> lastCityTrack = new HashMap<>();
    private final Map<UUID, Long> nextNews = new HashMap<>();
    private final Set<UUID> radioMuted = new HashSet<>();
    private final Map<UUID, Long> nextSurvivalMusic = new HashMap<>();
    private final Map<UUID, Key> survivalPlaying = new HashMap<>();

    /** Synthwave in the streets (with the NeyroCraft pack), a calm disc otherwise; stops outside the walls. */
    private void musicTick() {
        for (Player p : world.getPlayers()) {
            Location l = p.getLocation();
            boolean in = safe(l) && l.getY() < y0 + 70;
            long[] s = music.get(p.getUniqueId());
            if (!in) {
                if (s != null) { stopMusic(p, s); music.remove(p.getUniqueId()); }
                nextNews.remove(p.getUniqueId());
                if (plugin.inSurvival(p) && plugin.authed(p) && plugin.voice() != null && plugin.voice().hasVoice(p)
                        && tick >= nextSurvivalMusic.getOrDefault(p.getUniqueId(), 0L)) {
                    Key track = Key.key("neyrocraft", rnd.nextBoolean() ? "survival_1" : "survival_2");
                    p.playSound(Sound.sound(track, Sound.Source.MUSIC, 0.35f, 1f), Sound.Emitter.self());
                    survivalPlaying.put(p.getUniqueId(), track);
                    nextSurvivalMusic.put(p.getUniqueId(), tick + 20L * (120 + rnd.nextInt(90)));
                }
                continue;
            }
            Key oldTrack = survivalPlaying.remove(p.getUniqueId());
            if (oldTrack != null) p.stopSound(net.kyori.adventure.sound.SoundStop.namedOnSource(oldTrack, Sound.Source.MUSIC));
            if (radioMuted.contains(p.getUniqueId())) {
                if (s != null) { stopMusic(p, s); music.remove(p.getUniqueId()); }
                continue;
            }
            boolean pack = plugin.voice() != null && plugin.voice().hasVoice(p);
            int kind = pack ? 1 : 2;
            if (s == null || (pack ? s[0] == 2 : s[0] != 2) || tick >= s[1]) {
                if (s != null) stopMusic(p, s);
                if (pack) {
                    int last = lastCityTrack.getOrDefault(p.getUniqueId(), -1);
                    int choice;
                    if (s == null || last < 0 || s[0] == 2) choice = 0; // The original city theme welcomes every arrival.
                    else {
                        choice = rnd.nextInt(CITY_TRACKS.length - 1);
                        if (last >= 0 && choice >= last) choice++;
                    }
                    lastCityTrack.put(p.getUniqueId(), choice);
                    kind = choice + 3;
                }
                p.stopSound(net.kyori.adventure.sound.SoundStop.source(Sound.Source.MUSIC));
                Key chosen = kind >= 3 && kind < 3 + CITY_TRACKS.length ? CITY_TRACKS[kind - 3] : pack ? MUSIC : MUSIC_FALLBACK;
                p.playSound(Sound.sound(chosen, Sound.Source.RECORD, pack ? 0.22f : 0.15f, 1f), Sound.Emitter.self());
                music.put(p.getUniqueId(), new long[]{kind, tick + (kind >= 3 ? (kind == 3 ? MUSIC_TICKS : 1200) + 20L * (15 + rnd.nextInt(16)) : pack ? MUSIC_TICKS : FALLBACK_TICKS)});
            } else if (tick % 200 == 0) {
                p.stopSound(net.kyori.adventure.sound.SoundStop.source(Sound.Source.MUSIC));
            }
            if (tick >= nextNews.getOrDefault(p.getUniqueId(), 0L)) {
                news(p);
                nextNews.put(p.getUniqueId(), tick + 20L * 300);
            }
        }
        music.keySet().removeIf(id -> {
            Player p = Bukkit.getPlayer(id);
            if (p != null && p.getWorld().equals(world)) return false;
            if (p != null) stopMusic(p, music.get(id));
            return true;
        });
    }

    private static void stopMusic(Player p, long[] s) {
        if (s == null) return;
        Key key = s[0] >= 3 && s[0] < 3 + CITY_TRACKS.length ? CITY_TRACKS[(int)s[0] - 3] : s[0] == 1 ? MUSIC : MUSIC_FALLBACK;
        p.stopSound(net.kyori.adventure.sound.SoundStop.namedOnSource(key, Sound.Source.RECORD));
    }

    private void news(Player p) {
        java.time.LocalDate date = java.time.LocalDate.now(java.time.ZoneId.of("Europe/Moscow"));
        long day = date.toEpochDay();
        String[] headlines = {
                "Патрули NEYRO-CORP охраняют все ворота. За стенами обнаружены новые скопления монстров.",
                "Торговцы площади принимают изумруды. Ломбард выкупает добычу путешественников.",
                "Корабль NEYRO-1 держит связь с городом. Порталы на палубе открыты для пилотов.",
                "Экспедиция к станции Орион продолжается. Разведчики советуют проверить снаряжение перед вылетом."};
        p.sendMessage(Component.text("◈ НЕЙРО-РАДИО · Новости дня ◈", TextColor.color(CYAN)).decorate(TextDecoration.BOLD));
        p.sendMessage(Component.text(date + " · пилотов в сети: " + Bukkit.getOnlinePlayers().size()
                + " · погода: " + (world.hasStorm() ? "дождь за стенами" : "ясно"), NamedTextColor.GRAY));
        p.sendMessage(Component.text(headlines[Math.floorMod(day, headlines.length)], NamedTextColor.WHITE));
        p.sendMessage(Component.text("Эфир NEYRO-CORP, экипаж корабля NEYRO-1 · /radio — включить или выключить", NamedTextColor.GRAY));
        if (plugin.voice() != null && plugin.voice().hasVoice(p))
            p.playSound(Sound.sound(RADIO_NEWS, Sound.Source.RECORD, 0.9f, 1f), Sound.Emitter.self());
    }

    // ================================================================== spawning in the city

    @EventHandler(priority = EventPriority.HIGH)
    public void onRespawn(PlayerRespawnEvent e) {
        if (!ready || e.isBedSpawn() || e.isAnchorSpawn()) return;
        Location l = e.getRespawnLocation();
        if (l.getWorld() != null && l.getWorld().equals(world)) e.setRespawnLocation(spawn());
    }

    // ================================================================== traders and NPCs

    @EventHandler(priority = EventPriority.HIGH)
    public void onNpcClick(PlayerInteractEntityEvent e) {
        if (e.getHand() != EquipmentSlot.HAND) return;
        UUID id = e.getRightClicked().getUniqueId();
        Player p = e.getPlayer();
        if (housing.agentClick(p, e.getRightClicked())) { e.setCancelled(true); return; }
        CityFolk.Npc resident = folkNpcs.get(id);
        if (resident != null) {
            e.setCancelled(true);
            if (plugin.dialogue() != null) plugin.dialogue().start(p, resident.speaker(), e.getRightClicked().getLocation().add(0, 1.5, 0),
                    s -> folk.talk(resident, s));
            return;
        }
        CityShops.Type shop = traders.get(id);
        if (shop != null) {
            e.setCancelled(true);
            if (p.isSneaking()) {
                talkSimple(p, e.getRightClicked(), shop.npc(), "торговец NEYRO-CORP", shop.color(),
                        "Товары города поступают с корабля NEYRO-1. За изумруды найдём почти всё.");
                return;
            }
            double mult = plugin.karma() == null ? 1.0 : plugin.karma().priceMultiplier(p);
            String priceNote = mult > 1 ? " · цены ×" + (mult == 2.0 ? "2" : "1.5") : mult < 1 ? " · скидка героя" : "";
            Merchant m = Bukkit.createMerchant(Component.text()
                    .append(line(shop.title(), shop.color(), true))
                    .append(Component.text(" · " + shop.npc() + priceNote, mult > 1 ? TextColor.color(0xB02020) : NamedTextColor.DARK_GRAY)).build());
            m.setRecipes(shop.recipes(mult));
            p.openMerchant(m, true);
            String say = mult > 1 ? "Я тебя знаю. Для таких, как ты, цены другие. Плати — или проваливай."
                    : mult < 1 ? "Герой! Для тебя — скидка. " + shop.greeting() : shop.greeting();
            p.sendMessage(Component.text()
                    .append(line("◈ " + shop.npc() + ": ", shop.color(), false))
                    .append(Component.text(say, TextColor.color(mult > 1 ? 0xFF9090 : 0xFFE6C8))).build());
            sound(p, "entity.wandering_trader.yes", 0.6f, 1.1f);
            return;
        }
        String talk = talkers.get(id);
        if (talk == null) {
            if (p.isSneaking() && world.equals(e.getRightClicked().getWorld())
                    && e.getRightClicked() instanceof org.bukkit.entity.AbstractVillager) {
                e.setCancelled(true);
                talkSimple(p, e.getRightClicked(), PlainName.of(e.getRightClicked()), "житель выживания", CYAN,
                        "Я живу за стенами Нейро-Сити. Слышал новости NEYRO-CORP по радио? Их корабль виден даже отсюда.");
            }
            return;
        }
        e.setCancelled(true);
        String name = PlainName.of(e.getRightClicked());
        if (talk.equals("radio")) {
            talkRadio(p, e.getRightClicked());
        } else if (talk.equals("casino")) {
            p.performCommand("casino rules");
        } else if (talk.equals("donate")) {
            p.sendMessage(Component.text("✦ ДОНАТЫ NEYRO-CORP: VIP → PREMIUM → ELITE → TITAN", TextColor.color(0xFF55DD)));
            p.sendMessage(Component.text("VIP: /fly · PREMIUM: /heal · ELITE: /kill · TITAN: /ban", NamedTextColor.AQUA));
            p.sendMessage(Component.text("Для получения ранга обратись к администрации. Автоматическая оплата пока не подключена.", NamedTextColor.YELLOW));
        } else if (talk.equals("guide")) {
            talkSimple(p, e.getRightClicked(), name, "гид NEYRO-CORP", ORANGE,
                    "NEYRO-CORP — это мы, экипаж корабля NEYRO-1. Мы построили город, охраняем его и ведём Нейро-Радио.");
        } else if (talk.equals("guard")) {
            talkSimple(p, e.getRightClicked(), name, "охрана NEYRO-CORP", CYAN,
                    "Мы охраняем ворота и жителей. Если идёшь за стену, проверь оружие и припасы.");
        } else {
            talkSimple(p, e.getRightClicked(), name, "житель Нейро-Сити", CYAN,
                    "В городе всем заправляет NEYRO-CORP — экипаж корабля над нами. У радио каждый день новости.");
        }
    }

    private void talkSimple(Player p, Entity npc, String name, String role, int color, String greeting) {
        if (plugin.dialogue() == null) return;
        plugin.dialogue().start(p, new Dialogue.Speaker(name.toUpperCase(), role, color, 0xD70C2260),
                npc.getLocation().add(0, 1.5, 0), s -> simpleMenu(s, greeting));
    }

    private void simpleMenu(Dialogue.Session s, String greeting) {
        s.say(greeting, () -> s.choose(
                new Dialogue.Choice("[Свет] Чем помочь городу?", Dialogue.Kind.LIGHT, 2,
                        x -> x.sayAndEnd("Помогай новичкам и защищай жителей от монстров за стеной. Город это запомнит.")),
                Dialogue.Choice.of("Что нового?", Dialogue.Kind.ASK, x -> { news(x.player); x.sayAndEnd("Это свежий выпуск Нейро-Радио. Нашу станцию найдёшь у площади."); }),
                new Dialogue.Choice("[Тьма] Кто здесь главный?", Dialogue.Kind.DARK, -2,
                        x -> x.sayAndEnd(x.tier() < 0 ? "NEYRO-CORP знает о твоих делах. Стража тоже." : "NEYRO-CORP. Не испытывай терпение стражи.")),
                Dialogue.Choice.of("До встречи", Dialogue.Kind.LEAVE, x -> x.end(true))));
    }

    private void talkRadio(Player p, Entity npc) {
        if (plugin.dialogue() == null) return;
        plugin.dialogue().start(p, new Dialogue.Speaker("РЭЙ", "ведущая Нейро-Радио", CYAN, 0xD70C2260),
                npc.getLocation().add(0, 1.5, 0), s -> s.say("Это Нейро-Радио, голос NEYRO-CORP! Новости дня, музыка города и связь с NEYRO-1.",
                        () -> s.choose(
                                Dialogue.Choice.of("Слушать новости", Dialogue.Kind.ASK, x -> { news(x.player); x.end(true); }),
                                Dialogue.Choice.of("Включить или выключить эфир", Dialogue.Kind.ACTION,
                                        x -> { toggleRadio(x.player); x.end(true); }),
                                Dialogue.Choice.of("До встречи", Dialogue.Kind.LEAVE, x -> x.end(true)))));
    }

    private void toggleRadio(Player p) {
        if (!radioMuted.add(p.getUniqueId())) {
            radioMuted.remove(p.getUniqueId());
            nextNews.remove(p.getUniqueId());
            p.sendMessage(Component.text("Нейро-Радио включено", TextColor.color(CYAN)));
        } else {
            long[] s = music.remove(p.getUniqueId());
            stopMusic(p, s);
            p.stopSound(net.kyori.adventure.sound.SoundStop.namedOnSource(RADIO_NEWS, Sound.Source.RECORD));
            p.sendMessage(Component.text("Нейро-Радио выключено", NamedTextColor.GRAY));
        }
    }

    /** The custom name as plain text (citizens). */
    private static final class PlainName {
        static String of(Entity e) {
            Component c = e.customName();
            return c == null ? "Житель" : net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer.plainText().serialize(c);
        }
    }

    private void guide(Player p) {
        String[] lines = {
                "Это NEYRO-CITY — безопасная зона выживания.",
                "Здесь нельзя драться и ломать блоки, мобы сюда не заходят.",
                "Магазины — вокруг площади: ПКМ по торговцу. Платим изумрудами.",
                "Нет изумрудов? Продай добычу в «ЛОМБАРД 24/7».",
                "Луч в центре площади — лифт на корабль NEYRO-1 (хаб).",
                "/city — вернуться в город из любой точки выживания.",
                "Строить свой дом можно за стенами города."};
        p.sendMessage(Component.text()
                .append(line("◈ Гид Нова", ORANGE, true)).build());
        for (String l : lines) p.sendMessage(Component.text("  • " + l, TextColor.color(0xFFE6C8)));
    }

    // ================================================================== the safe zone

    private boolean free(Player p) {
        return p != null && plugin.isBuilder(p);
    }

    private void deny(Player p, Event e) {
        if (e instanceof org.bukkit.event.Cancellable c) c.setCancelled(true);
        if (p != null && tick % 2 == 0) {
            int remaining = Math.max(0, (int) Math.floor(SPAWN_PROTECTION - spawnDistance(p.getLocation())) + 1);
            String hint = remaining > 0 ? " · отойди ещё примерно на " + remaining + " бл. от спавна" : "";
            HudBoard.notice(p, Component.text("✖ Строй и ломай дальше 500 блоков от спавна" + hint, TextColor.color(0xFF5555)));
        }
    }

    @EventHandler(priority = EventPriority.LOW, ignoreCancelled = true)
    public void onBreak(BlockBreakEvent e) { if (spawnProtected(e.getBlock().getLocation()) && !free(e.getPlayer())
            && !housing.canEdit(e.getPlayer(), e.getBlock().getLocation())) deny(e.getPlayer(), e); }

    @EventHandler(priority = EventPriority.LOW, ignoreCancelled = true)
    public void onPlace(BlockPlaceEvent e) { if (spawnProtected(e.getBlock().getLocation()) && !free(e.getPlayer())
            && !housing.canEdit(e.getPlayer(), e.getBlock().getLocation())) deny(e.getPlayer(), e); }

    @EventHandler(priority = EventPriority.LOW, ignoreCancelled = true)
    public void onBucket(PlayerBucketEmptyEvent e) { if (spawnProtected(e.getBlock().getLocation()) && !free(e.getPlayer())) deny(e.getPlayer(), e); }

    @EventHandler(priority = EventPriority.LOW, ignoreCancelled = true)
    public void onBucketFill(PlayerBucketFillEvent e) { if (spawnProtected(e.getBlock().getLocation()) && !free(e.getPlayer())) deny(e.getPlayer(), e); }

    @EventHandler(priority = EventPriority.LOW)
    public void onInteract(PlayerInteractEvent e) {
        Block b = e.getClickedBlock();
        if (b == null || free(e.getPlayer()) || !guarded(b.getLocation())) return;
        if (housing.ownsInterior(e.getPlayer(), b.getLocation())) return;
        if (e.getAction() == Action.PHYSICAL) { e.setCancelled(true); return; }
        if (e.getAction() == Action.RIGHT_CLICK_BLOCK && b.getType().isInteractable()) e.setUseInteractedBlock(Event.Result.DENY);
    }

    @EventHandler(priority = EventPriority.LOW, ignoreCancelled = true)
    public void onEntityPlace(EntityPlaceEvent e) {
        if (guarded(e.getEntity().getLocation()) && !free(e.getPlayer())) deny(e.getPlayer(), e);
    }

    @EventHandler(priority = EventPriority.LOW, ignoreCancelled = true)
    public void onHangingPlace(HangingPlaceEvent e) {
        if (guarded(e.getEntity().getLocation()) && !free(e.getPlayer())) deny(e.getPlayer(), e);
    }

    @EventHandler(priority = EventPriority.LOW, ignoreCancelled = true)
    public void onHangingBreak(HangingBreakEvent e) {
        if (!guarded(e.getEntity().getLocation())) return;
        if (e instanceof HangingBreakByEntityEvent be && be.getRemover() instanceof Player p && free(p)) return;
        e.setCancelled(true);
    }

    @EventHandler(ignoreCancelled = true)
    public void onExplode(EntityExplodeEvent e) { e.blockList().removeIf(b -> guarded(b.getLocation())); }

    @EventHandler(ignoreCancelled = true)
    public void onBlockExplode(BlockExplodeEvent e) { e.blockList().removeIf(b -> guarded(b.getLocation())); }

    @EventHandler(ignoreCancelled = true)
    public void onIgnite(BlockIgniteEvent e) { if (guarded(e.getBlock().getLocation()) && !free(e.getPlayer())) e.setCancelled(true); }

    @EventHandler(ignoreCancelled = true)
    public void onBurn(BlockBurnEvent e) { if (guarded(e.getBlock().getLocation())) e.setCancelled(true); }

    @EventHandler(ignoreCancelled = true)
    public void onSpread(BlockSpreadEvent e) { if (guarded(e.getBlock().getLocation())) e.setCancelled(true); }

    @EventHandler(ignoreCancelled = true)
    public void onFlow(BlockFromToEvent e) { if (guarded(e.getToBlock().getLocation())) e.setCancelled(true); }

    @EventHandler(ignoreCancelled = true)
    public void onMobChange(EntityChangeBlockEvent e) {
        if (e.getEntity() instanceof FallingBlock) return;
        if (e.getEntity() instanceof Player p && free(p)) return;
        if (guarded(e.getBlock().getLocation())) e.setCancelled(true);
    }

    @EventHandler(ignoreCancelled = true)
    public void onPistonOut(BlockPistonExtendEvent e) {
        if (guarded(e.getBlock().getLocation())) { e.setCancelled(true); return; }
        for (Block b : e.getBlocks())
            if (guarded(b.getLocation()) || guarded(b.getRelative(e.getDirection()).getLocation())) { e.setCancelled(true); return; }
    }

    @EventHandler(ignoreCancelled = true)
    public void onPistonIn(BlockPistonRetractEvent e) {
        if (guarded(e.getBlock().getLocation())) { e.setCancelled(true); return; }
        for (Block b : e.getBlocks()) if (guarded(b.getLocation())) { e.setCancelled(true); return; }
    }

    /** Nobody gets hurt in the city: no PvP, no mobs, no falls. */
    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onDamage(EntityDamageEvent e) {
        if (e.getEntity().getScoreboardTags().contains(TAG)) { e.setCancelled(true); return; }
        if (!(e.getEntity() instanceof Player p)) return;
        if (e.getCause() == EntityDamageEvent.DamageCause.VOID || e.getCause() == EntityDamageEvent.DamageCause.KILL) return;
        if (safe(p.getLocation()) || plugin.beamDrop().busy(p)) e.setCancelled(true);
    }

    /** And nobody shoots out of the city at players outside. */
    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onHit(EntityDamageByEntityEvent e) {
        if (!(e.getEntity() instanceof Player)) return;
        Player attacker = e.getDamager() instanceof Player p ? p
                : e.getDamager() instanceof Projectile pr && pr.getShooter() instanceof Player p2 ? p2 : null;
        if (attacker != null && safe(attacker.getLocation())) e.setCancelled(true);
    }

    @EventHandler(ignoreCancelled = true)
    public void onSpawn(CreatureSpawnEvent e) {
        if (!(e.getEntity() instanceof Enemy)) return;
        CreatureSpawnEvent.SpawnReason r = e.getSpawnReason();
        if (r == CreatureSpawnEvent.SpawnReason.CUSTOM || r == CreatureSpawnEvent.SpawnReason.COMMAND) return;
        Location l = e.getLocation();
        if (safe(l) || (located && l.getWorld() != null && l.getWorld().equals(world) && inShipBox(l))) e.setCancelled(true);
    }

    @EventHandler(ignoreCancelled = true)
    public void onTarget(EntityTargetLivingEntityEvent e) {
        if (e.getTarget() instanceof Player p && safe(p.getLocation())) e.setCancelled(true);
    }

    // ================================================================== commands

    boolean command(CommandSender sender, String name, String[] args) {
        if (name.equals("radio")) {
            if (!(sender instanceof Player p)) { sender.sendMessage("Only players can use /radio"); return true; }
            if (!ready || !safe(p.getLocation())) { p.sendMessage("Нейро-Радио ловит только в Нейро-Сити."); return true; }
            if (args.length > 0 && args[0].equalsIgnoreCase("news")) news(p);
            else toggleRadio(p);
            return true;
        }
        if (name.equals("city")) {
            if (!(sender instanceof Player p)) { sender.sendMessage("Only players can use /city"); return true; }
            goToCity(p);
            return true;
        }
        if (!sender.hasPermission("neyrohub.admin")) return true;
        String sub = args.length > 0 ? args[0].toLowerCase(java.util.Locale.ROOT) : "info";
        switch (sub) {
            case "rebuild" -> {
                if (world == null) { sender.sendMessage("Neyro-City is disabled"); return true; }
                sender.sendMessage("Rebuilding Neyro-City (watch the console)...");
                build(true);
            }
            case "dump" -> sender.sendMessage(dump());
            default -> sender.sendMessage("Neyro-City: " + (ready ? "ready" : building ? "building" : "off")
                    + ", centre " + cx + " " + cz + ", plaza y=" + y0 + ", ship deck y=" + shipY
                    + ", traders " + traders.size() + ". /neyrocity rebuild | dump");
        }
        return true;
    }

    private void goToCity(Player p) {
        if (!ready) { p.sendMessage(Component.text("Город ещё строится, попробуй через минуту", NamedTextColor.RED)); return; }
        if (!plugin.authed(p) || plugin.beamDrop().busy(p)) return;
        if (plugin.inHub(p)) {
            if (plugin.laserInGame(p) || plugin.sabersInGame(p) || plugin.starfightInGame(p) || plugin.inCutscene(p)) {
                p.sendMessage(Component.text("Сначала выйди из игры: /hub", NamedTextColor.RED));
                return;
            }
            dropIntoCity(p);
            return;
        }
        if (!plugin.survivalWorld(p.getWorld())) {
            p.sendMessage(Component.text("Отсюда в город не попасть. Сначала /hub, потом портал «Выживание».", NamedTextColor.RED));
            return;
        }
        if (safe(p.getLocation()) || !p.getWorld().equals(world)) {
            teleportToCity(p);
            return;
        }
        BukkitTask old = warmups.remove(p.getUniqueId());
        if (old != null) old.cancel();
        Location from = p.getLocation();
        p.sendMessage(Component.text("Телепорт в NEYRO-CITY через 3 секунды. Не двигайся.", TextColor.color(CYAN)));
        warmups.put(p.getUniqueId(), new BukkitRunnable() {
            int left = 3;

            @Override
            public void run() {
                if (!p.isOnline() || !p.getWorld().equals(from.getWorld()) || p.getLocation().distanceSquared(from) > 1.0) {
                    if (p.isOnline()) HudBoard.notice(p, Component.text("✖ Телепорт отменён: ты сдвинулся", NamedTextColor.RED));
                    warmups.remove(p.getUniqueId());
                    cancel();
                    return;
                }
                if (left == 0) {
                    warmups.remove(p.getUniqueId());
                    cancel();
                    teleportToCity(p);
                    return;
                }
                HudBoard.notice(p, Component.text("Телепорт в город: " + left + "...", TextColor.color(CYAN)));
                sound(p, "block.note_block.chime", 0.6f, 0.8f + (3 - left) * 0.3f);
                left--;
            }
        }.runTaskTimer(plugin, 0L, 20L));
    }

    private void teleportToCity(Player p) {
        p.setFallDistance(0);
        p.teleport(spawn());
        sound(p, "entity.enderman.teleport", 0.7f, 1.3f);
        world.spawnParticle(Particle.REVERSE_PORTAL, spawn().add(0, 1, 0), 60, 0.4, 0.8, 0.4, 0.05);
    }

    /** Writes every block of the city (and the decorations) to city-dump.txt.gz for checking it outside the game. */
    private String dump() {
        if (!located) return "Not built";
        File out = new File(plugin.getDataFolder(), "city-dump.txt.gz");
        int count = 0;
        try (Writer w = new OutputStreamWriter(new GZIPOutputStream(new FileOutputStream(out)), StandardCharsets.UTF_8)) {
            w.write("plaza " + y0 + " ship " + shipY + "\n");
            int reach = CityPlan.HALF + CityPlan.SKIRT + 4;          // the city and the land around it
            for (int dx = -reach; dx <= reach; dx++)
                for (int dz = -reach; dz <= reach; dz++) {
                    for (int h = -3; h <= CityPlan.TOP; h++) {
                        Material m = world.getBlockAt(cx + dx, y0 + h, cz + dz).getType();
                        if (m.isAir()) continue;
                        w.write(dx + " " + h + " " + dz + " " + m.getKey().getKey() + "\n");
                        count++;
                    }
                }
            BoundingBox box = new BoundingBox(cx - 80, world.getMinHeight(), cz - 80, cx + 81, world.getMaxHeight(), cz + 81);
            for (Entity e : world.getNearbyEntities(box, e -> e.getScoreboardTags().contains(TAG))) {
                Location l = e.getLocation();
                String label = e instanceof TextDisplay t
                        ? net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer.plainText().serialize(t.text()).replace('\n', '|')
                        : e.customName() != null ? PlainName.of(e) : "";
                w.write(String.format(java.util.Locale.ROOT, "E %s %.2f %.2f %.2f %.1f %s%n", e.getType().getKey().getKey(),
                        l.getX() - cx, l.getY() - y0, l.getZ() - cz, l.getYaw(), label));
            }
        } catch (IOException e) {
            return "Dump failed: " + e;
        }
        return "Dumped " + count + " blocks to " + out.getPath();
    }

    private static void sound(Player p, String key, float vol, float pitch) {
        p.playSound(Sound.sound(Key.key("minecraft", key), Sound.Source.MASTER, vol, pitch));
    }
}
