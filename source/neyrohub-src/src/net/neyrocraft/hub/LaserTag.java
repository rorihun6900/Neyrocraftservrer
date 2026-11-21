package net.neyrocraft.hub;

import net.kyori.adventure.bossbar.BossBar;
import net.kyori.adventure.key.Key;
import net.kyori.adventure.sound.Sound;
import net.kyori.adventure.sound.SoundStop;
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
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.Particle;
import org.bukkit.World;
import org.bukkit.block.BlockFace;
import org.bukkit.block.data.BlockData;
import org.bukkit.block.data.type.Stairs;
import org.bukkit.entity.BlockDisplay;
import org.bukkit.entity.Display;
import org.bukkit.entity.Entity;
import org.bukkit.entity.Player;
import org.bukkit.entity.TextDisplay;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.block.Action;
import org.bukkit.event.player.PlayerAnimationEvent;
import org.bukkit.event.player.PlayerAnimationType;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.inventory.EquipmentSlot;
import org.bukkit.scheduler.BukkitTask;
import org.bukkit.scoreboard.Criteria;
import org.bukkit.scoreboard.DisplaySlot;
import org.bukkit.scoreboard.Objective;
import org.bukkit.scoreboard.Scoreboard;
import org.bukkit.scoreboard.Team;
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
 * LaserTag: team PvP with blasters on a space-station arena.
 * Red vs blue, three hits knock a player out, first team to {@link #WIN_SCORE} or the
 * higher score after {@link #MATCH_SECONDS} wins. Shots are glowing bolts that fly at
 * a visible speed; each player carries a 3D blaster model built from block displays.
 */
final class LaserTag implements Listener {

    static final int AX = 3000, AY = 100, AZ = 0;          // arena centre, floor level
    static final int HALF_X = 30, HALF_Z = 38;
    static final int BUILD_VERSION = 1;

    static final int WIN_SCORE = 25;
    static final int MATCH_SECONDS = 180;
    static final int COUNTDOWN = 10;
    static final int HIT_DAMAGE = 34;                      // 3 hits
    static final int RESPAWN_TICKS = 60;
    static final int SPAWN_PROTECT_TICKS = 40;
    static final double BOLT_SPEED = 2.4;
    static final int BOLT_LIFE = 32;
    static final long SHOT_COOLDOWN_MS = 260;
    static final int HEAT_PER_SHOT = 13;

    static final int RED = 0, BLUE = 1;
    private static final int[] TEAM_RGB = {0xFF3030, 0x3CA0FF};
    private static final String[] TEAM_NAME = {"Красные", "Синие"};
    private static final NamedTextColor[] TEAM_COLOR = {NamedTextColor.RED, NamedTextColor.BLUE};
    private static final Material[] TEAM_CONCRETE = {Material.RED_CONCRETE, Material.LIGHT_BLUE_CONCRETE};
    private static final Material[] TEAM_GLASS = {Material.RED_STAINED_GLASS, Material.LIGHT_BLUE_STAINED_GLASS};

    private static final String[][] MUSIC = {{"music_disc.pigstep", "149"}, {"music_disc.creator", "176"}};

    enum State { WAITING, COUNTDOWN, RUNNING, ENDED }

    private final NeyroHub plugin;
    private final World world;
    private final Random rnd = new Random();
    private final Map<UUID, LP> players = new HashMap<>();
    private final List<Bolt> bolts = new ArrayList<>();
    private final List<Drone> drones = new ArrayList<>();
    private final List<Entity> decor = new ArrayList<>();
    private final List<BlockDisplay> showcase = new ArrayList<>();
    private final int[] score = new int[2];
    private State state = State.WAITING;
    private int stateSeconds = 0;
    private int timeLeft = MATCH_SECONDS;
    private long tick = 0;
    private final Scoreboard board;
    private final Objective sidebar;
    private final Team[] teams = new Team[2];
    private final BossBar bar = BossBar.bossBar(Component.text("LASERTAG"), 1f, BossBar.Color.PURPLE, BossBar.Overlay.PROGRESS);
    private final BukkitTask ticker;

    /** One player in the arena. */
    private final class LP {
        final Player player;
        int team;
        int hp = 100;
        int kills, deaths, training;
        long lastShot;
        double heat;
        long overheatedUntil;
        int respawnIn = -1;
        int protectedFor = 0;
        double attachDy = 1.8;
        final List<BlockDisplay> gun = new ArrayList<>();
        final List<Vector3f[]> gunParts = new ArrayList<>();   // {min, size}
        BukkitTask music;
        int musicIdx;
        String musicKey;

        LP(Player player) {
            this.player = player;
        }

        boolean alive() {
            return respawnIn < 0;
        }
    }

    /** A blaster bolt in flight. */
    private static final class Bolt {
        BlockDisplay display;
        Vector pos, dir;
        UUID owner;
        int team, age;
    }

    /** A training drone that floats around the arena. */
    private static final class Drone {
        BlockDisplay display;
        double cx, cy, cz, radius, speed, phase;
        int respawnIn = -1;
    }

    LaserTag(NeyroHub plugin, World world) {
        this.plugin = plugin;
        this.world = world;
        for (int cx = (AX - HALF_X - 8) >> 4; cx <= (AX + HALF_X + 8) >> 4; cx++)
            for (int cz = (AZ - HALF_Z - 8) >> 4; cz <= (AZ + HALF_Z + 8) >> 4; cz++) world.setChunkForceLoaded(cx, cz, true);
        File marker = new File(plugin.getDataFolder(), "lasertag-built-v" + BUILD_VERSION);
        if (!marker.exists()) {
            buildArena();
            try {
                Files.writeString(marker.toPath(), "built");
            } catch (IOException e) {
                plugin.getLogger().warning("Could not write LaserTag marker: " + e);
            }
        }
        spawnDecor();
        board = Bukkit.getScoreboardManager().getNewScoreboard();
        sidebar = board.registerNewObjective("lasertag", Criteria.DUMMY,
                Component.text("✦ LASER TAG ✦", TextColor.color(0xFF55FF)).decorate(TextDecoration.BOLD));
        sidebar.setDisplaySlot(DisplaySlot.SIDEBAR);
        for (int t = 0; t < 2; t++) {
            teams[t] = board.registerNewTeam(t == RED ? "red" : "blue");
            teams[t].color(TEAM_COLOR[t]);
            teams[t].prefix(Component.text(t == RED ? "[К] " : "[С] ", TEAM_COLOR[t]));
            teams[t].setAllowFriendlyFire(false);
        }
        updateSidebar();
        ticker = Bukkit.getScheduler().runTaskTimer(plugin, this::tick, 1L, 1L);
    }

    void shutdown() {
        ticker.cancel();
        for (LP lp : new ArrayList<>(players.values())) leave(lp.player, false);
        for (Bolt b : bolts) if (b.display.isValid()) b.display.remove();
        bolts.clear();
        for (Drone d : drones) if (d.display.isValid()) d.display.remove();
        drones.clear();
        for (Entity e : decor) if (e.isValid()) e.remove();
        decor.clear();
    }

    boolean inGame(Player p) {
        return players.containsKey(p.getUniqueId());
    }

    int size() {
        return players.size();
    }

    // =================================================================== arena

    private void set(int x, int y, int z, Material m) {
        world.getBlockAt(AX + x, AY + y, AZ + z).setType(m, false);
    }

    private void set(int x, int y, int z, BlockData d) {
        world.getBlockAt(AX + x, AY + y, AZ + z).setBlockData(d, false);
    }

    private static int hash(int x, int z) {
        int h = x * 73856093 ^ z * 19349663;
        h ^= h >>> 13;
        h *= 0x5bd1e995;
        return Math.floorMod(h ^ (h >>> 15), 100);
    }

    private void buildArena() {
        plugin.getLogger().info("Building the LaserTag arena...");
        for (int x = -HALF_X - 2; x <= HALF_X + 2; x++)
            for (int z = -HALF_Z - 2; z <= HALF_Z + 2; z++)
                for (int y = -6; y <= 12; y++) set(x, y, z, Material.AIR);

        for (int x = -HALF_X; x <= HALF_X; x++) {
            for (int z = -HALF_Z; z <= HALF_Z; z++) {
                boolean border = Math.abs(x) == HALF_X || Math.abs(z) == HALF_Z;
                Material f;
                if (border) f = Material.GRAY_CONCRETE;
                else if (z == 0) f = Material.SMOOTH_QUARTZ;
                else if (Math.abs(z) == 20) f = z < 0 ? Material.RED_CONCRETE : Material.LIGHT_BLUE_CONCRETE;
                else if (x == 0 && Math.floorMod(z, 3) == 0) f = Material.OCHRE_FROGLIGHT;
                else f = (Math.floorDiv(x, 4) + Math.floorDiv(z, 4)) % 2 == 0 ? Material.POLISHED_DEEPSLATE : Material.DEEPSLATE_TILES;
                set(x, 0, z, f);
                // station hull underneath
                for (int d = 1; d <= 3; d++) {
                    Material m = d == 3 && hash(x, z) < 6 ? Material.SEA_LANTERN
                            : d == 1 ? Material.GRAY_CONCRETE : Material.LIGHT_GRAY_CONCRETE;
                    if (d == 3 && (Math.abs(x) > HALF_X - 3 || Math.abs(z) > HALF_Z - 3)) continue;
                    set(x, -d, z, m);
                }
                // perimeter wall with a band of windows
                if (border) {
                    boolean rib = Math.floorMod(x + z, 4) == 0;
                    for (int y = 1; y <= 6; y++) {
                        Material m = Material.WHITE_CONCRETE;
                        if (y >= 2 && y <= 4) m = rib ? Material.LIGHT_GRAY_CONCRETE : Material.GLASS;
                        if (y == 6 && rib) m = z < 0 ? Material.SHROOMLIGHT : z > 0 ? Material.SEA_LANTERN : Material.OCHRE_FROGLIGHT;
                        set(x, y, z, m);
                    }
                }
            }
        }

        for (int team = 0; team < 2; team++) buildBase(team);
        buildCentre();

        // mirrored cover: pillars, crates, low walls
        int[][] pillars = {{12, 8}, {22, 14}, {6, 24}, {-12, 8}, {-22, 14}, {-6, 24}};
        int[][] crates = {{17, 3}, {9, 15}, {24, 26}, {-17, 3}, {-9, 15}, {-24, 26}, {0, 16}};
        for (int s : new int[]{-1, 1}) {
            for (int[] pp : pillars) pillar(pp[0], pp[1] * s);
            for (int[] c : crates) crate(c[0], c[1] * s);
            lowWall(-15, -11, 12 * s);
            lowWall(11, 15, 12 * s);
            lowWall(-14, -10, 28 * s);
            lowWall(10, 14, 28 * s);
        }
        catwalks();
        plugin.getLogger().info("LaserTag arena built");
    }

    private void buildBase(int team) {
        int s = team == RED ? -1 : 1;
        Material conc = TEAM_CONCRETE[team], glass = TEAM_GLASS[team];
        Material light = team == RED ? Material.SHROOMLIGHT : Material.SEA_LANTERN;
        for (int x = -9; x <= 9; x++) {
            for (int dz = 31; dz <= 37; dz++) {
                int z = dz * s;
                set(x, 0, z, Math.abs(x) % 4 == 0 && dz == 35 ? light : conc);
                set(x, 5, z, (x + dz) % 3 == 0 ? light : Material.WHITE_CONCRETE);            // roof
                if (Math.abs(x) == 9) for (int y = 1; y <= 4; y++) set(x, y, z, y == 2 || y == 3 ? glass : conc);
            }
            // front wall with two doorways
            int z = 30 * s;
            for (int y = 1; y <= 5; y++) {
                boolean door = (Math.abs(x) >= 3 && Math.abs(x) <= 5) && y <= 3;
                if (door) continue;
                Material m = y == 5 ? Material.WHITE_CONCRETE : (y == 2 || y == 3) && Math.abs(x) <= 1 ? glass : conc;
                set(x, y, z, m);
            }
        }
        // team banner of light above the doors
        for (int x = -2; x <= 2; x++) set(x, 4, 30 * s, light);
    }

    private void buildCentre() {
        for (int x = -5; x <= 5; x++)
            for (int z = -5; z <= 5; z++) {
                boolean edge = Math.abs(x) == 5 || Math.abs(z) == 5;
                set(x, 1, z, Material.LIGHT_GRAY_CONCRETE);
                set(x, 2, z, edge ? Material.ORANGE_CONCRETE : (x + z) % 2 == 0 ? Material.SMOOTH_QUARTZ : Material.WHITE_CONCRETE);
            }
        // ramps on four sides
        for (int w = -1; w <= 1; w++) {
            for (BlockFace face : new BlockFace[]{BlockFace.NORTH, BlockFace.SOUTH, BlockFace.EAST, BlockFace.WEST}) {
                int dx = face.getModX(), dz = face.getModZ();       // direction from centre outwards
                BlockFace up = face.getOppositeFace();               // stairs face the centre
                BlockData stair = Bukkit.createBlockData(Material.POLISHED_ANDESITE_STAIRS, bd -> ((Stairs) bd).setFacing(up));
                int x6 = dx != 0 ? dx * 6 : w, z6 = dz != 0 ? dz * 6 : w;
                int x7 = dx != 0 ? dx * 7 : w, z7 = dz != 0 ? dz * 7 : w;
                set(x6, 1, z6, Material.LIGHT_GRAY_CONCRETE);
                set(x6, 2, z6, stair);
                set(x7, 1, z7, stair);
            }
        }
        // the pylon in the middle
        set(0, 3, 0, Material.LIGHT_GRAY_CONCRETE);
        set(0, 4, 0, Material.OCHRE_FROGLIGHT);
        set(0, 5, 0, Material.LIGHT_GRAY_CONCRETE);
    }

    private void pillar(int x, int z) {
        for (int ox = 0; ox <= 1; ox++) for (int oz = 0; oz <= 1; oz++) for (int y = 1; y <= 5; y++)
            set(x + ox, y, z + oz, y == 3 ? Material.OCHRE_FROGLIGHT : Material.LIGHT_GRAY_CONCRETE);
    }

    private void crate(int x, int z) {
        for (int ox = 0; ox <= 1; ox++) for (int oz = 0; oz <= 1; oz++) {
            set(x + ox, 1, z + oz, Material.IRON_BLOCK);
            set(x + ox, 2, z + oz, Material.WHITE_CONCRETE);
        }
    }

    private void lowWall(int x1, int x2, int z) {
        for (int x = x1; x <= x2; x++) {
            set(x, 1, z, Material.WHITE_CONCRETE);
            set(x, 2, z, x == x1 || x == x2 ? Material.ORANGE_CONCRETE : Material.LIGHT_GRAY_CONCRETE);
        }
    }

    private void catwalks() {
        for (int s : new int[]{-1, 1}) {
            for (int z = -19; z <= 19; z++) {
                for (int x = 26; x <= 28; x++) set(x * s, 3, z, x == 26 ? Material.ORANGE_CONCRETE : Material.LIGHT_GRAY_CONCRETE);
                if (z % 5 == 0) for (int y = 1; y <= 2; y++) set(27 * s, y, z, Material.GRAY_CONCRETE);
            }
            for (int end : new int[]{-1, 1}) {
                BlockFace toCatwalk = end < 0 ? BlockFace.SOUTH : BlockFace.NORTH;
                BlockData stair = Bukkit.createBlockData(Material.POLISHED_ANDESITE_STAIRS, bd -> ((Stairs) bd).setFacing(toCatwalk));
                for (int x = 26; x <= 28; x++) {
                    set(x * s, 3, 20 * end, stair);
                    set(x * s, 2, 20 * end, Material.LIGHT_GRAY_CONCRETE);
                    set(x * s, 2, 21 * end, stair);
                    set(x * s, 1, 21 * end, Material.LIGHT_GRAY_CONCRETE);
                    set(x * s, 1, 22 * end, stair);
                }
            }
        }
    }

    private void spawnDecor() {
        // planets around the station
        decor.addAll(SpaceDecor.planet(plugin, world, new Location(world, AX + 70, AY + 30, AZ - 50), SpaceDecor.Kind.MARS, 30f, 31));
        decor.addAll(SpaceDecor.planet(plugin, world, new Location(world, AX - 75, AY + 22, AZ + 30), SpaceDecor.Kind.GAS, 38f, 41));
        decor.addAll(SpaceDecor.planet(plugin, world, new Location(world, AX + 20, AY + 45, AZ + 70), SpaceDecor.Kind.ICE, 12f, 51));
        // title over the centre
        TextDisplay title = world.spawn(new Location(world, AX + 0.5, AY + 9, AZ + 0.5), TextDisplay.class, d -> {
            plugin.prepare(d);
            d.setBillboard(Display.Billboard.CENTER);
            d.setBackgroundColor(Color.fromARGB(0, 0, 0, 0));
            d.setShadowed(true);
            d.setTransformation(new Transformation(new Vector3f(), new Quaternionf(), new Vector3f(4f, 4f, 4f), new Quaternionf()));
            d.text(Component.text()
                    .append(Component.text("LASER", TextColor.color(TEAM_RGB[RED])).decorate(TextDecoration.BOLD))
                    .append(Component.text("TAG", TextColor.color(TEAM_RGB[BLUE])).decorate(TextDecoration.BOLD))
                    .build());
        });
        decor.add(title);
        // a big rotating blaster on the pylon: the showcase model
        showcase.addAll(spawnGunModel(new Location(world, AX + 0.5, AY + 6.6, AZ + 0.5), 0xFF55FF, true));
        decor.addAll(showcase);
    }

    // ================================================================ blaster model

    /** Parts of the blaster in its own space (forward = +Z, up = +Y, the player's right = -X). */
    private static Object[][] gunParts(int rgb) {
        Material cell = rgb == TEAM_RGB[RED] ? Material.RED_STAINED_GLASS
                : rgb == TEAM_RGB[BLUE] ? Material.LIGHT_BLUE_STAINED_GLASS : Material.MAGENTA_STAINED_GLASS;
        Material tip = rgb == TEAM_RGB[RED] ? Material.RED_CONCRETE
                : rgb == TEAM_RGB[BLUE] ? Material.LIGHT_BLUE_CONCRETE : Material.MAGENTA_CONCRETE;
        return new Object[][]{
                {Material.GRAY_CONCRETE, v(-0.05f, -0.06f, -0.12f), v(0.10f, 0.12f, 0.34f), false},
                {Material.LIGHT_GRAY_CONCRETE, v(-0.035f, -0.03f, 0.20f), v(0.07f, 0.07f, 0.10f), false},
                {Material.BLACK_CONCRETE, v(-0.025f, -0.02f, 0.22f), v(0.05f, 0.05f, 0.26f), false},
                {tip, v(-0.03f, -0.025f, 0.47f), v(0.06f, 0.06f, 0.035f), true},
                {Material.BLACK_CONCRETE, v(-0.02f, 0.06f, -0.03f), v(0.04f, 0.04f, 0.19f), false},
                {Material.LIGHT_BLUE_STAINED_GLASS, v(-0.017f, 0.063f, 0.16f), v(0.034f, 0.034f, 0.012f), true},
                {Material.BLACK_CONCRETE, v(-0.03f, -0.21f, -0.07f), v(0.06f, 0.16f, 0.07f), false},
                {cell, v(-0.075f, -0.04f, -0.06f), v(0.025f, 0.07f, 0.15f), true},
                {Material.GRAY_CONCRETE, v(-0.04f, -0.05f, -0.30f), v(0.08f, 0.09f, 0.18f), false},
                {Material.ORANGE_CONCRETE, v(-0.051f, 0.0f, -0.10f), v(0.102f, 0.02f, 0.25f), false},
        };
    }

    private static Vector3f v(float x, float y, float z) {
        return new Vector3f(x, y, z);
    }

    private static Quaternionf aim(float yawDeg, float pitchDeg) {
        return new Quaternionf().rotateY((float) Math.toRadians(-yawDeg)).rotateX((float) Math.toRadians(pitchDeg));
    }

    /** A free-standing blaster (showcase); with {@code big} it is scaled up 6×. */
    private List<BlockDisplay> spawnGunModel(Location at, int rgb, boolean big) {
        List<BlockDisplay> out = new ArrayList<>();
        float k = big ? 6f : 1f;
        for (Object[] part : gunParts(rgb)) {
            Vector3f min = new Vector3f((Vector3f) part[1]).mul(k), size = new Vector3f((Vector3f) part[2]).mul(k);
            BlockDisplay d = world.spawn(at, BlockDisplay.class, e -> {
                plugin.prepare(e);
                e.setBlock(((Material) part[0]).createBlockData());
                e.setTransformation(new Transformation(min, new Quaternionf(), size, new Quaternionf()));
                if ((Boolean) part[3]) e.setBrightness(new Display.Brightness(15, 15));
            });
            out.add(d);
        }
        return out;
    }

    private final org.bukkit.NamespacedKey gunKey = new org.bukkit.NamespacedKey("neyrohub", "blaster");

    /** The blaster as a real item with its own 3D model in the team colour (no bare arm in first person). */
    private org.bukkit.inventory.ItemStack gunItem(int team) {
        org.bukkit.inventory.ItemStack it = new org.bukkit.inventory.ItemStack(Material.STICK);
        org.bukkit.inventory.meta.ItemMeta m = it.getItemMeta();
        m.setItemModel(new org.bukkit.NamespacedKey("neyrocraft", team == RED ? "blaster_red" : "blaster_blue"));
        m.displayName(Component.text("Бластер «Импульс»", TEAM_COLOR[team]).decorate(TextDecoration.BOLD).decoration(TextDecoration.ITALIC, false));
        m.lore(List.of(
                Component.text("ЛКМ или ПКМ — выстрел", NamedTextColor.GRAY).decoration(TextDecoration.ITALIC, false),
                Component.text("3 попадания — выбывание", NamedTextColor.GRAY).decoration(TextDecoration.ITALIC, false),
                Component.text("Следи за перегревом!", NamedTextColor.DARK_GRAY).decoration(TextDecoration.ITALIC, false)));
        m.setMaxStackSize(1);
        m.addItemFlags(org.bukkit.inventory.ItemFlag.HIDE_ATTRIBUTES);
        m.getPersistentDataContainer().set(gunKey, org.bukkit.persistence.PersistentDataType.BYTE, (byte) 1);
        it.setItemMeta(m);
        return it;
    }

    private boolean isGun(org.bukkit.inventory.ItemStack it) {
        return it != null && it.hasItemMeta() && it.getItemMeta().getPersistentDataContainer().has(gunKey);
    }

    private void giveGun(LP lp) {
        removeGun(lp);
        Player p = lp.player;
        p.getInventory().setItem(0, gunItem(lp.team));
        p.getInventory().setHeldItemSlot(0);
    }

    private void removeGun(LP lp) {
        for (BlockDisplay d : lp.gun) if (d.isValid()) d.remove();
        lp.gun.clear();
        lp.gunParts.clear();
        Player p = lp.player;
        if (p.isOnline()) for (int i = 0; i < p.getInventory().getSize(); i++) if (isGun(p.getInventory().getItem(i))) p.getInventory().setItem(i, null);
    }

    /** Keeps the blaster in the player's hands: low on the right, pointing where they look. */
    private void updateGun(LP lp) {
        Player p = lp.player;
        if (lp.gun.isEmpty()) return;
        Location l = p.getLocation();
        Quaternionf q = aim(l.getYaw(), l.getPitch());
        Vector3f eye = new Vector3f(0, (float) (p.getEyeHeight() - lp.attachDy), 0);
        Vector3f hold = new Vector3f(-0.24f, -0.27f, 0.32f);        // right, down, forward in view space
        // recoil kick right after a shot
        long since = System.currentTimeMillis() - lp.lastShot;
        if (since < 120) hold.z -= 0.06f * (1 - since / 120f);
        for (int i = 0; i < lp.gun.size(); i++) {
            BlockDisplay d = lp.gun.get(i);
            if (!d.isValid()) continue;
            Vector3f[] part = lp.gunParts.get(i);
            Vector3f t = q.transform(new Vector3f(hold).add(part[0])).add(eye);
            d.setInterpolationDelay(0);
            d.setInterpolationDuration(1);
            d.setTransformation(new Transformation(t, q, part[1], new Quaternionf()));
        }
    }

    // ===================================================================== join

    void join(Player p) {
        if (inGame(p)) return;
        plugin.vault().lock(p, "lasertag");          // own things stay outside: only the arena's blaster here
        LP lp = new LP(p);
        lp.team = teamSize(RED) <= teamSize(BLUE) ? RED : BLUE;
        players.put(p.getUniqueId(), lp);
        teams[lp.team].addEntry(p.getName());
        p.setScoreboard(board);
        p.setGameMode(GameMode.ADVENTURE);
        p.setGlowing(true);
        p.setFoodLevel(20);
        p.showBossBar(bar);
        Location to = state == State.RUNNING ? teamSpawn(lp.team) : lobbySpot();
        p.teleport(to);
        giveGun(lp);
        lp.hp = 100;
        syncHealth(lp);
        startMusic(lp);
        p.showTitle(Title.title(
                Component.text()
                        .append(Component.text("LASER", TextColor.color(TEAM_RGB[RED])).decorate(TextDecoration.BOLD))
                        .append(Component.text("TAG", TextColor.color(TEAM_RGB[BLUE])).decorate(TextDecoration.BOLD))
                        .build(),
                Component.text("Ты в команде: ", NamedTextColor.GRAY)
                        .append(Component.text(TEAM_NAME[lp.team], TEAM_COLOR[lp.team]).decorate(TextDecoration.BOLD)),
                Title.Times.times(Duration.ofMillis(300), Duration.ofMillis(2500), Duration.ofMillis(600))));
        msg(p, Component.text("ЛКМ или ПКМ — выстрел. 3 попадания — выбывание. Следи за перегревом бластера!", NamedTextColor.GRAY));
        msg(p, Component.text("/hub — вернуться на палубу", NamedTextColor.DARK_GRAY));
        broadcast(Component.text()
                .append(Component.text(p.getName(), TEAM_COLOR[lp.team]))
                .append(Component.text(" вошёл на арену", NamedTextColor.GRAY)).build());
        sound(p, "block.beacon.power_select", 0.8f, 1.4f);
        if (state == State.WAITING && players.size() >= 2) startCountdown();
        updateSidebar();
    }

    void leave(Player p, boolean announce) {
        LP lp = players.remove(p.getUniqueId());
        if (lp == null) return;
        removeGun(lp);
        stopMusic(lp);
        teams[lp.team].removeEntry(p.getName());
        if (p.isOnline()) {
            plugin.vault().unlock(p, "lasertag");
            p.setScoreboard(Bukkit.getScoreboardManager().getMainScoreboard());
            p.setGlowing(false);
            p.hideBossBar(bar);
            p.setHealth(p.getAttribute(org.bukkit.attribute.Attribute.MAX_HEALTH).getValue());
            if (p.getGameMode() == GameMode.SPECTATOR) p.setGameMode(GameMode.ADVENTURE);
        }
        if (announce) broadcast(Component.text()
                .append(Component.text(p.getName(), TEAM_COLOR[lp.team]))
                .append(Component.text(" покинул арену", NamedTextColor.GRAY)).build());
        if (state == State.RUNNING && (teamSize(RED) == 0 || teamSize(BLUE) == 0)) endMatch();
        if (state == State.COUNTDOWN && players.size() < 2) {
            state = State.WAITING;
            broadcast(Component.text("Недостаточно игроков — отсчёт остановлен", NamedTextColor.YELLOW));
        }
        updateSidebar();
    }

    private int teamSize(int team) {
        int n = 0;
        for (LP lp : players.values()) if (lp.team == team) n++;
        return n;
    }

    private Location lobbySpot() {
        double a = rnd.nextDouble() * Math.PI * 2;
        return new Location(world, AX + 0.5 + Math.cos(a) * 2.5, AY + 3, AZ + 0.5 + Math.sin(a) * 2.5, rnd.nextFloat() * 360, 0);
    }

    private Location teamSpawn(int team) {
        int s = team == RED ? -1 : 1;
        int[] xs = {-5, 0, 5};
        return new Location(world, AX + xs[rnd.nextInt(3)] + 0.5, AY + 1, AZ + 34 * s + 0.5, team == RED ? 0f : 180f, 0f);
    }

    // =============================================================== shooting

    @EventHandler(priority = EventPriority.HIGH)
    public void onSwing(PlayerAnimationEvent e) {
        if (e.getAnimationType() == PlayerAnimationType.ARM_SWING) tryShoot(e.getPlayer());
    }

    @EventHandler(priority = EventPriority.HIGH)
    public void onUse(PlayerInteractEvent e) {
        if (e.getHand() != EquipmentSlot.HAND) return;
        if (e.getAction() == Action.RIGHT_CLICK_AIR || e.getAction() == Action.RIGHT_CLICK_BLOCK) {
            if (inGame(e.getPlayer())) {
                e.setCancelled(true);
                tryShoot(e.getPlayer());
            }
        }
    }

    // the blaster stays in the hand: no dropping, swapping, moving or switching slots
    @EventHandler public void onDrop(org.bukkit.event.player.PlayerDropItemEvent e) { if (inGame(e.getPlayer())) e.setCancelled(true); }
    @EventHandler public void onSwap(org.bukkit.event.player.PlayerSwapHandItemsEvent e) { if (inGame(e.getPlayer())) e.setCancelled(true); }
    @EventHandler public void onSlot(org.bukkit.event.player.PlayerItemHeldEvent e) { if (inGame(e.getPlayer()) && e.getNewSlot() != 0) e.setCancelled(true); }
    @EventHandler public void onInvClick(org.bukkit.event.inventory.InventoryClickEvent e) { if (e.getWhoClicked() instanceof Player p && inGame(p)) e.setCancelled(true); }
    @EventHandler(priority = EventPriority.HIGH)
    public void onMelee(org.bukkit.event.entity.EntityDamageByEntityEvent e) { if (e.getDamager() instanceof Player p && inGame(p)) e.setCancelled(true); }

    private void tryShoot(Player p) {
        LP lp = players.get(p.getUniqueId());
        if (lp == null || !lp.alive() || state == State.ENDED) return;
        long now = System.currentTimeMillis();
        if (now - lp.lastShot < SHOT_COOLDOWN_MS) return;
        if (now < lp.overheatedUntil) {
            if (now - lp.lastShot > 400) {
                sound(p, "block.fire.extinguish", 0.5f, 1.8f);
                lp.lastShot = now - SHOT_COOLDOWN_MS + 400;
            }
            return;
        }
        lp.lastShot = now;
        lp.heat += HEAT_PER_SHOT;
        if (lp.heat >= 100) {
            lp.heat = 100;
            lp.overheatedUntil = now + 2200;
            sound(p, "block.lava.extinguish", 0.8f, 1.2f);
            world.playSound(Sound.sound(Key.key("neyrocraft", "laser.overheat"), Sound.Source.PLAYER, 1f, 1f), p);
            p.setCooldown(Material.STICK, 44);                              // the hotbar icon greys out while it cools
            p.showTitle(Title.title(Component.empty(), Component.text("⚠ ПЕРЕГРЕВ ⚠", NamedTextColor.RED).decorate(TextDecoration.BOLD),
                    Title.Times.times(Duration.ZERO, Duration.ofMillis(900), Duration.ofMillis(300))));
        }
        Location eye = p.getEyeLocation();
        Quaternionf q = aim(eye.getYaw(), eye.getPitch());
        Vector3f m = q.transform(new Vector3f(-0.24f, -0.22f, 0.85f));
        Vector start = eye.toVector().add(new Vector(m.x, m.y, m.z));
        // aim at what the crosshair points at
        Vector look = eye.getDirection();
        RayTraceResult aimHit = world.rayTraceBlocks(eye, look, 60, FluidCollisionMode.NEVER, true);
        Vector target = aimHit != null ? aimHit.getHitPosition() : eye.toVector().add(look.clone().multiply(60));
        Vector dir = target.clone().subtract(start);
        if (dir.lengthSquared() < 1) dir = look.clone();
        dir.normalize();
        spawnBolt(lp, start, dir);
        // blaster sound, heard by everyone nearby
        Location at = eye.clone();
        world.playSound(Sound.sound(Key.key("neyrocraft", "laser.shot"), Sound.Source.PLAYER, 1f, 0.95f + rnd.nextFloat() * 0.1f), at.getX(), at.getY(), at.getZ());
        world.playSound(at, "item.crossbow.shoot", 0.25f, 1.9f);       // quiet vanilla layer for players without the pack
        p.spawnParticle(Particle.DUST, new Location(world, start.getX(), start.getY(), start.getZ()), 3, 0.03, 0.03, 0.03, 0,
                new Particle.DustOptions(Color.fromRGB(TEAM_RGB[lp.team]), 1.0f));
    }

    private void spawnBolt(LP lp, Vector start, Vector dir) {
        Bolt b = new Bolt();
        b.pos = start.clone();
        b.dir = dir.clone();
        b.owner = lp.player.getUniqueId();
        b.team = lp.team;
        float yaw = (float) Math.toDegrees(Math.atan2(-dir.getX(), dir.getZ()));
        float pitch = (float) Math.toDegrees(-Math.asin(Math.max(-1, Math.min(1, dir.getY()))));
        Quaternionf q = aim(yaw, pitch);
        Vector3f size = new Vector3f(0.07f, 0.07f, 1.5f);
        Vector3f off = q.transform(new Vector3f(-0.035f, -0.035f, -0.75f));
        Color glow = Color.fromRGB(TEAM_RGB[lp.team]);
        b.display = world.spawn(new Location(world, start.getX(), start.getY(), start.getZ()), BlockDisplay.class, e -> {
            plugin.prepare(e);
            e.setBlock(Material.WHITE_CONCRETE.createBlockData());
            e.setBrightness(new Display.Brightness(15, 15));
            e.setGlowing(true);
            e.setGlowColorOverride(glow);
            e.setTeleportDuration(1);
            e.setTransformation(new Transformation(off, q, size, new Quaternionf()));
        });
        bolts.add(b);
    }

    private void tickBolts() {
        for (Bolt b : new ArrayList<>(bolts)) {
            b.age++;
            if (b.age > BOLT_LIFE || !b.display.isValid()) {
                removeBolt(b);
                continue;
            }
            Location from = new Location(world, b.pos.getX(), b.pos.getY(), b.pos.getZ());
            Player shooter = Bukkit.getPlayer(b.owner);
            RayTraceResult hit = world.rayTrace(from, b.dir, BOLT_SPEED, FluidCollisionMode.NEVER, true, 0.25, ent -> {
                if (!(ent instanceof Player pl) || pl.getUniqueId().equals(b.owner)) return false;
                LP v = players.get(pl.getUniqueId());
                return v != null && v.alive() && v.team != b.team;
            });
            // training drones are displays without a hitbox: check them by distance to the segment
            Drone droneHit = null;
            if (state != State.RUNNING) {
                for (Drone d : drones) {
                    if (d.respawnIn >= 0 || !d.display.isValid()) continue;
                    Vector c = d.display.getLocation().toVector().add(new Vector(0, 0.4, 0));
                    if (distToSegment(c, b.pos, b.dir, BOLT_SPEED) < 0.7) { droneHit = d; break; }
                }
            }
            if (droneHit != null) {
                hitDrone(droneHit, shooter);
                removeBolt(b);
                continue;
            }
            if (hit != null) {
                Vector at = hit.getHitPosition();
                Location loc = new Location(world, at.getX(), at.getY(), at.getZ());
                if (hit.getHitEntity() instanceof Player victim) {
                    hitPlayer(victim, shooter, b.team, loc);
                } else {
                    world.spawnParticle(Particle.ELECTRIC_SPARK, loc, 8, 0.1, 0.1, 0.1, 0.15);
                    world.spawnParticle(Particle.DUST, loc, 6, 0.1, 0.1, 0.1, 0, new Particle.DustOptions(Color.fromRGB(TEAM_RGB[b.team]), 1.2f));
                    world.playSound(loc, "block.amethyst_block.hit", 0.6f, 1.7f);
                }
                removeBolt(b);
                continue;
            }
            b.pos.add(b.dir.clone().multiply(BOLT_SPEED));
            b.display.teleport(new Location(world, b.pos.getX(), b.pos.getY(), b.pos.getZ()));
        }
    }

    private static double distToSegment(Vector c, Vector a, Vector dir, double len) {
        Vector ac = c.clone().subtract(a);
        double t = Math.max(0, Math.min(len, ac.dot(dir)));
        return a.clone().add(dir.clone().multiply(t)).distance(c);
    }

    private void removeBolt(Bolt b) {
        if (b.display.isValid()) b.display.remove();
        bolts.remove(b);
    }

    private void hitPlayer(Player victim, Player shooter, int shooterTeam, Location at) {
        LP v = players.get(victim.getUniqueId());
        if (v == null || !v.alive()) return;
        world.spawnParticle(Particle.DUST, at, 14, 0.2, 0.3, 0.2, 0, new Particle.DustOptions(Color.fromRGB(TEAM_RGB[shooterTeam]), 1.4f));
        world.spawnParticle(Particle.ELECTRIC_SPARK, at, 10, 0.2, 0.3, 0.2, 0.2);
        if (v.protectedFor > 0) {
            world.playSound(at, "block.amethyst_block.hit", 0.7f, 0.6f);
            return;
        }
        if (state != State.RUNNING) {
            // outside a match: harmless sparks only
            world.playSound(at, "block.amethyst_block.hit", 0.7f, 1.2f);
            return;
        }
        v.hp -= HIT_DAMAGE;
        victim.playSound(Sound.sound(Key.key("minecraft", "entity.player.hurt"), Sound.Source.PLAYER, 1f, 1.2f));
        world.playSound(Sound.sound(Key.key("neyrocraft", "laser.hit"), Sound.Source.PLAYER, 1f, 1f), victim);
        victim.playSound(Sound.sound(Key.key("minecraft", "block.respawn_anchor.deplete"), Sound.Source.PLAYER, 0.5f, 2f));
        if (shooter != null) shooter.playSound(Sound.sound(Key.key("minecraft", "entity.experience_orb.pickup"), Sound.Source.PLAYER, 0.8f, 1.6f));
        if (shooter != null) {
            Vector kb = victim.getLocation().toVector().subtract(shooter.getLocation().toVector()).setY(0);
            if (kb.lengthSquared() > 0.01) victim.setVelocity(kb.normalize().multiply(0.35).setY(0.15));
        }
        if (v.hp <= 0) eliminate(v, shooter);
        else syncHealth(v);
    }

    private void eliminate(LP v, Player shooter) {
        Player victim = v.player;
        v.deaths++;
        v.hp = 0;
        LP s = shooter != null ? players.get(shooter.getUniqueId()) : null;
        if (s != null) {
            s.kills++;
            score[s.team]++;
            shooter.playSound(Sound.sound(Key.key("minecraft", "entity.player.levelup"), Sound.Source.PLAYER, 0.6f, 1.8f));
        }
        Location at = victim.getLocation().add(0, 1, 0);
        world.spawnParticle(Particle.DUST, at, 50, 0.4, 0.8, 0.4, 0, new Particle.DustOptions(Color.fromRGB(TEAM_RGB[v.team]), 1.6f));
        world.spawnParticle(Particle.END_ROD, at, 25, 0.3, 0.6, 0.3, 0.12);
        world.playSound(at, "entity.generic.explode", 0.4f, 1.8f);
        removeGun(v);
        v.respawnIn = RESPAWN_TICKS;
        victim.setGameMode(GameMode.SPECTATOR);
        victim.showTitle(Title.title(Component.text("ВЫБЫЛ", NamedTextColor.RED).decorate(TextDecoration.BOLD),
                Component.text(shooter != null ? "тебя подстрелил " + shooter.getName() : "возрождение через 3 сек", NamedTextColor.GRAY),
                Title.Times.times(Duration.ofMillis(100), Duration.ofMillis(2200), Duration.ofMillis(500))));
        if (shooter != null && s != null) {
            broadcast(Component.text()
                    .append(Component.text(shooter.getName(), TEAM_COLOR[s.team]))
                    .append(Component.text("  ⌁⌁⌁▶  ", NamedTextColor.GRAY))
                    .append(Component.text(victim.getName(), TEAM_COLOR[v.team])).build());
        }
        updateSidebar();
        if (s != null && score[s.team] >= WIN_SCORE) endMatch();
    }

    private void respawn(LP lp) {
        Player p = lp.player;
        lp.respawnIn = -1;
        lp.hp = 100;
        lp.heat = 0;
        lp.protectedFor = SPAWN_PROTECT_TICKS;
        p.setGameMode(GameMode.ADVENTURE);
        p.teleport(state == State.RUNNING ? teamSpawn(lp.team) : lobbySpot());
        giveGun(lp);
        syncHealth(lp);
        sound(p, "block.beacon.power_select", 0.6f, 1.6f);
        world.spawnParticle(Particle.END_ROD, p.getLocation().add(0, 1, 0), 30, 0.3, 0.8, 0.3, 0.05);
    }

    private void syncHealth(LP lp) {
        Player p = lp.player;
        double max = p.getAttribute(org.bukkit.attribute.Attribute.MAX_HEALTH).getValue();
        p.setHealth(Math.max(1, Math.min(max, max * lp.hp / 100.0)));
    }

    // =================================================================== drones

    private void ensureDrones() {
        boolean want = state != State.RUNNING && !players.isEmpty();
        if (!want) {
            for (Drone d : drones) if (d.display.isValid()) d.display.remove();
            drones.clear();
            return;
        }
        while (drones.size() < 6) {
            Drone d = new Drone();
            int i = drones.size();
            d.cx = AX + 0.5 + (i % 2 == 0 ? -1 : 1) * (8 + rnd.nextInt(10));
            d.cz = AZ + 0.5 + (i < 3 ? -1 : 1) * (6 + rnd.nextInt(14));
            d.cy = AY + 3.5 + rnd.nextDouble() * 3;
            d.radius = 2 + rnd.nextDouble() * 3;
            d.speed = 0.03 + rnd.nextDouble() * 0.03;
            d.phase = rnd.nextDouble() * Math.PI * 2;
            d.display = spawnDroneDisplay(new Location(world, d.cx, d.cy, d.cz));
            drones.add(d);
        }
    }

    private BlockDisplay spawnDroneDisplay(Location at) {
        return world.spawn(at, BlockDisplay.class, e -> {
            plugin.prepare(e);
            e.setBlock(Material.TARGET.createBlockData());
            e.setBrightness(new Display.Brightness(15, 15));
            e.setGlowing(true);
            e.setGlowColorOverride(Color.fromRGB(0xFF8C00));
            e.setTeleportDuration(2);
            e.setTransformation(new Transformation(new Vector3f(-0.4f, 0, -0.4f), new Quaternionf(), new Vector3f(0.8f, 0.8f, 0.8f), new Quaternionf()));
        });
    }

    private void tickDrones() {
        for (Drone d : drones) {
            if (d.respawnIn >= 0) {
                if (--d.respawnIn < 0) d.display = spawnDroneDisplay(new Location(world, d.cx, d.cy, d.cz));
                continue;
            }
            if (!d.display.isValid()) continue;
            d.phase += d.speed;
            double x = d.cx + Math.cos(d.phase) * d.radius, z = d.cz + Math.sin(d.phase * 1.3) * d.radius;
            double y = d.cy + Math.sin(d.phase * 2) * 0.6;
            if (tick % 2 == 0) d.display.teleport(new Location(world, x, y, z));
            if (tick % 6 == 0) world.spawnParticle(Particle.DUST, new Location(world, x, y + 0.4, z), 1, 0.2, 0.1, 0.2, 0,
                    new Particle.DustOptions(Color.fromRGB(255, 140, 0), 0.8f));
        }
    }

    private void hitDrone(Drone d, Player shooter) {
        Location at = d.display.getLocation().add(0, 0.4, 0);
        world.spawnParticle(Particle.EXPLOSION, at, 1);
        world.spawnParticle(Particle.DUST, at, 30, 0.3, 0.3, 0.3, 0, new Particle.DustOptions(Color.fromRGB(255, 140, 0), 1.5f));
        world.playSound(at, "entity.generic.explode", 0.4f, 1.9f);
        d.display.remove();
        d.respawnIn = 60;
        LP s = shooter != null ? players.get(shooter.getUniqueId()) : null;
        if (s != null) {
            s.training++;
            shooter.playSound(Sound.sound(Key.key("minecraft", "entity.experience_orb.pickup"), Sound.Source.PLAYER, 0.8f, 1.4f));
            HudBoard.notice(shooter, Component.text("Дрон сбит! Тренировка: " + s.training, NamedTextColor.GOLD));
        }
    }

    // =================================================================== match

    private void startCountdown() {
        state = State.COUNTDOWN;
        stateSeconds = COUNTDOWN;
        broadcast(Component.text("Матч начнётся через " + COUNTDOWN + " секунд!", NamedTextColor.YELLOW));
    }

    private void startMatch() {
        state = State.RUNNING;
        timeLeft = MATCH_SECONDS;
        score[RED] = score[BLUE] = 0;
        for (Bolt b : new ArrayList<>(bolts)) removeBolt(b);
        // rebalance teams
        List<LP> all = new ArrayList<>(players.values());
        java.util.Collections.shuffle(all, rnd);
        for (int i = 0; i < all.size(); i++) {
            LP lp = all.get(i);
            teams[lp.team].removeEntry(lp.player.getName());
            lp.team = i % 2 == 0 ? RED : BLUE;
            teams[lp.team].addEntry(lp.player.getName());
            lp.kills = lp.deaths = 0;
            lp.respawnIn = -1;
            respawn(lp);
            lp.player.showTitle(Title.title(Component.text("БОЙ!", NamedTextColor.GOLD).decorate(TextDecoration.BOLD),
                    Component.text("Ты в команде: ", NamedTextColor.GRAY).append(Component.text(TEAM_NAME[lp.team], TEAM_COLOR[lp.team])),
                    Title.Times.times(Duration.ofMillis(100), Duration.ofMillis(1500), Duration.ofMillis(400))));
            sound(lp.player, "entity.ender_dragon.growl", 0.4f, 1.6f);
        }
        ensureDrones();
        updateSidebar();
    }

    private void endMatch() {
        if (state != State.RUNNING) return;
        state = State.ENDED;
        stateSeconds = 8;
        int winner = score[RED] > score[BLUE] ? RED : score[BLUE] > score[RED] ? BLUE : -1;
        Component title = winner < 0 ? Component.text("НИЧЬЯ", NamedTextColor.YELLOW).decorate(TextDecoration.BOLD)
                : Component.text("ПОБЕДА: " + TEAM_NAME[winner].toUpperCase(), TEAM_COLOR[winner]).decorate(TextDecoration.BOLD);
        LP best = null;
        for (LP lp : players.values()) if (best == null || lp.kills > best.kills) best = lp;
        Component sub = Component.text(score[RED] + " : " + score[BLUE] + (best != null ? "   ·   MVP: " + best.player.getName() + " (" + best.kills + ")" : ""),
                NamedTextColor.GRAY);
        for (LP lp : players.values()) {
            lp.player.showTitle(Title.title(title, sub, Title.Times.times(Duration.ofMillis(200), Duration.ofMillis(5000), Duration.ofMillis(800))));
            sound(lp.player, winner == lp.team ? "ui.toast.challenge_complete" : "entity.wither.death", winner == lp.team ? 0.8f : 0.3f, 1.2f);
            // StarPass: stars for the winners, a little for everyone who played, a bonus for the MVP
            if (plugin.starPass() != null) {
                if (plugin.hud() != null) { plugin.hud().add(lp.player, "lasertag.games", 1); plugin.hud().add(lp.player, "lasertag.kills", lp.kills); if (winner == lp.team) plugin.hud().add(lp.player, "lasertag.wins", 1); }
                plugin.starPass().award(lp.player, winner == lp.team ? 80 : 20, winner == lp.team ? "победа в LaserTag" : "матч LaserTag");
                if (lp == best && best.kills > 0) plugin.starPass().award(lp.player, 30, "MVP матча");
            }
        }
        if (winner >= 0) {
            int s = winner == RED ? -1 : 1;
            for (int i = 0; i < 6; i++) {
                final int k = i;
                Bukkit.getScheduler().runTaskLater(plugin, () -> {
                    Location l = new Location(world, AX + 0.5 + (k - 3) * 3, AY + 6, AZ + 0.5 + 30 * s);
                    world.spawnParticle(Particle.FIREWORK, l, 60, 0.5, 0.5, 0.5, 0.25);
                    world.spawnParticle(Particle.DUST, l, 40, 1, 1, 1, 0, new Particle.DustOptions(Color.fromRGB(TEAM_RGB[winner]), 2f));
                    world.playSound(l, "entity.firework_rocket.twinkle", 1f, 1f);
                }, i * 8L);
            }
        }
        broadcast(Component.text("Матч окончен! ", NamedTextColor.GOLD).append(sub));
    }

    private void resetToLobby() {
        state = State.WAITING;
        score[RED] = score[BLUE] = 0;
        for (LP lp : players.values()) {
            lp.respawnIn = -1;
            respawn(lp);
            lp.protectedFor = 0;
        }
        updateSidebar();
        if (players.size() >= 2) startCountdown();
    }

    // ==================================================================== tick

    private void tick() {
        tick++;
        if (tick % 2 == 0) for (BlockDisplay d : showcase) spinShowcase(d);
        if (players.isEmpty()) {
            if (!drones.isEmpty()) ensureDrones();
            return;
        }
        for (LP lp : new ArrayList<>(players.values())) {
            Player p = lp.player;
            if (!p.isOnline()) { leave(p, false); continue; }
            if (lp.alive()) {
                updateGun(lp);
                if (!lp.gun.isEmpty() && !p.getPassengers().containsAll(lp.gun)) giveGun(lp);
                if (p.getLocation().getY() < AY - 15) {
                    p.teleport(state == State.RUNNING ? teamSpawn(lp.team) : lobbySpot());
                }
            } else if (--lp.respawnIn <= 0) {
                respawn(lp);
            }
            if (lp.protectedFor > 0) lp.protectedFor--;
            lp.heat = Math.max(0, lp.heat - 1.1);
            if (tick % 2 == 0) hud(lp);
        }
        tickBolts();
        tickDrones();
        if (tick % 20 == 0) second();
    }

    private void spinShowcase(BlockDisplay d) {
        Transformation tr = d.getTransformation();
        float ang = (float) (tick * 0.04);
        Quaternionf q = new Quaternionf().rotateY(ang);
        // rotate each part about the showcase origin: recover its local min from the stored list order
        int idx = showcase.indexOf(d);
        Object[] part = gunParts(0xFF55FF)[idx];
        Vector3f min = new Vector3f((Vector3f) part[1]).mul(6f);
        d.setInterpolationDelay(0);
        d.setInterpolationDuration(2);
        d.setTransformation(new Transformation(q.transform(new Vector3f(min)), q, tr.getScale(), new Quaternionf()));
    }

    private void second() {
        ensureDrones();
        switch (state) {
            case WAITING -> {
                bar.name(Component.text()
                        .append(Component.text("LASERTAG  ", TextColor.color(0xFF55FF)).decorate(TextDecoration.BOLD))
                        .append(Component.text(players.size() < 2 ? "ожидание игроков (" + players.size() + "/2) · стреляй по дронам"
                                : "скоро старт", NamedTextColor.GRAY)).build());
                bar.progress(1f);
            }
            case COUNTDOWN -> {
                stateSeconds--;
                bar.name(Component.text("Старт через " + stateSeconds + " сек", NamedTextColor.YELLOW));
                bar.progress(Math.max(0f, stateSeconds / (float) COUNTDOWN));
                if (stateSeconds <= 3 && stateSeconds > 0) {
                    for (LP lp : players.values()) {
                        lp.player.showTitle(Title.title(Component.text(String.valueOf(stateSeconds), NamedTextColor.GOLD).decorate(TextDecoration.BOLD),
                                Component.empty(), Title.Times.times(Duration.ZERO, Duration.ofMillis(800), Duration.ofMillis(200))));
                        sound(lp.player, "block.note_block.pling", 1f, 1f + (3 - stateSeconds) * 0.2f);
                    }
                }
                if (stateSeconds <= 0) startMatch();
            }
            case RUNNING -> {
                timeLeft--;
                bar.name(Component.text()
                        .append(Component.text(TEAM_NAME[RED] + " " + score[RED], TEAM_COLOR[RED]).decorate(TextDecoration.BOLD))
                        .append(Component.text("   ⏱ " + timeLeft / 60 + ":" + String.format("%02d", timeLeft % 60) + "   ", NamedTextColor.WHITE))
                        .append(Component.text(score[BLUE] + " " + TEAM_NAME[BLUE], TEAM_COLOR[BLUE]).decorate(TextDecoration.BOLD)).build());
                bar.progress(Math.max(0f, timeLeft / (float) MATCH_SECONDS));
                if (timeLeft == 30) for (LP lp : players.values()) sound(lp.player, "block.note_block.bell", 1f, 1.2f);
                if (timeLeft <= 0) endMatch();
            }
            case ENDED -> {
                stateSeconds--;
                bar.name(Component.text("Новый раунд через " + stateSeconds + " сек", NamedTextColor.GRAY));
                if (stateSeconds <= 0) resetToLobby();
            }
        }
        updateSidebar();
    }

    private void hud(LP lp) {
        Player p = lp.player;
        if (!lp.alive()) {
            HudBoard.notice(p, Component.text("Возрождение через " + (lp.respawnIn / 20 + 1) + "...", NamedTextColor.GRAY));
            return;
        }
        long now = System.currentTimeMillis();
        boolean hot = now < lp.overheatedUntil;
        TextComponent.Builder b = Component.text();
        b.append(Component.text("❤ ", NamedTextColor.RED));
        int hearts = (int) Math.ceil(lp.hp / 34.0);
        for (int i = 0; i < 3; i++) b.append(Component.text("■", i < hearts ? NamedTextColor.RED : NamedTextColor.DARK_GRAY));
        b.append(Component.text("   🔥 ", hot ? NamedTextColor.RED : NamedTextColor.GOLD));
        int heat = (int) Math.round(lp.heat / 10);
        for (int i = 0; i < 10; i++) {
            NamedTextColor c = i >= heat ? NamedTextColor.DARK_GRAY : i < 5 ? NamedTextColor.YELLOW : i < 8 ? NamedTextColor.GOLD : NamedTextColor.RED;
            b.append(Component.text("▮", hot ? NamedTextColor.RED : c));
        }
        if (hot) b.append(Component.text(" ПЕРЕГРЕВ", NamedTextColor.RED).decorate(TextDecoration.BOLD));
        b.append(Component.text("   ⚔ " + lp.kills + " / ☠ " + lp.deaths, NamedTextColor.WHITE));
        if (lp.protectedFor > 0) b.append(Component.text("   🛡", NamedTextColor.AQUA));
        HudBoard.notice(p, b.build());
    }

    private void updateSidebar() {
        for (String entry : new ArrayList<>(board.getEntries())) {
            if (board.getEntryTeam(entry) == null) board.resetScores(entry);
        }
        sidebar.getScore("§c● Красные").setScore(score[RED]);
        sidebar.getScore("§9● Синие").setScore(score[BLUE]);
        sidebar.getScore("§7Цель: §f" + WIN_SCORE).setScore(-1);
        sidebar.getScore("§7Игроков: §f" + players.size()).setScore(-2);
    }

    // ==================================================================== music

    private void startMusic(LP lp) {
        stopMusic(lp);
        playTrack(lp);
    }

    private void playTrack(LP lp) {
        if (!lp.player.isOnline() || !players.containsKey(lp.player.getUniqueId())) return;
        String[] track = MUSIC[lp.musicIdx % MUSIC.length];
        lp.musicIdx++;
        lp.musicKey = track[0];
        lp.player.playSound(Sound.sound(Key.key("minecraft", track[0]), Sound.Source.RECORD, 0.55f, 1f), Sound.Emitter.self());
        lp.music = Bukkit.getScheduler().runTaskLater(plugin, () -> playTrack(lp), Long.parseLong(track[1]) * 20L + 20L);
    }

    private void stopMusic(LP lp) {
        if (lp.music != null) lp.music.cancel();
        lp.music = null;
        if (lp.player.isOnline()) for (String[] t : MUSIC) lp.player.stopSound(SoundStop.named(Key.key("minecraft", t[0])));
    }

    // ==================================================================== utils

    private void broadcast(Component c) {
        for (LP lp : players.values()) msg(lp.player, c);
    }

    private static void msg(Player p, Component c) {
        p.sendMessage(Component.text()
                .append(Component.text("[", NamedTextColor.DARK_GRAY))
                .append(Component.text("LASER", TextColor.color(TEAM_RGB[RED])))
                .append(Component.text("TAG", TextColor.color(TEAM_RGB[BLUE])))
                .append(Component.text("] ", NamedTextColor.DARK_GRAY))
                .append(c).build());
    }

    private static void sound(Player p, String key, float volume, float pitch) {
        p.playSound(Sound.sound(Key.key("minecraft", key), Sound.Source.MASTER, volume, pitch));
    }
}
