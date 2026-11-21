package net.neyrocraft.hub;

import net.kyori.adventure.key.Key;
import net.kyori.adventure.sound.Sound;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.TextComponent;
import net.kyori.adventure.text.format.NamedTextColor;
import net.kyori.adventure.text.format.TextColor;
import net.kyori.adventure.text.format.TextDecoration;
import net.kyori.adventure.title.Title;
import org.bukkit.Axis;
import org.bukkit.Bukkit;
import org.bukkit.Color;
import org.bukkit.Difficulty;
import org.bukkit.GameMode;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.Particle;
import org.bukkit.World;
import org.bukkit.WorldCreator;
import org.bukkit.block.Block;
import org.bukkit.block.data.BlockData;
import org.bukkit.block.data.Orientable;
import org.bukkit.command.Command;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.BlockDisplay;
import org.bukkit.entity.Display;
import org.bukkit.entity.Entity;
import org.bukkit.entity.Player;
import org.bukkit.entity.TextDisplay;
import org.bukkit.event.Event;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.block.BlockBreakEvent;
import org.bukkit.event.block.BlockPlaceEvent;
import org.bukkit.event.entity.EntityDamageEvent;
import org.bukkit.event.entity.EntityPickupItemEvent;
import org.bukkit.event.entity.FoodLevelChangeEvent;
import org.bukkit.event.player.PlayerDropItemEvent;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerMoveEvent;
import org.bukkit.event.player.PlayerPortalEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.generator.ChunkGenerator;
import org.bukkit.persistence.PersistentDataType;
import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.potion.PotionEffect;
import org.bukkit.potion.PotionEffectType;
import org.bukkit.util.Transformation;
import org.bukkit.util.Vector;
import org.joml.AxisAngle4f;
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
 * NeyroHub: a floating "neural core" hub in a void world. Players land in its
 * centre after logging in and pick a mode through large portals.
 */
public final class NeyroHub extends JavaPlugin implements Listener {

    static final String HUB_WORLD = "neyro_hub";
    static final String TAG = "neyrohub";
    static final int BUILD_VERSION = 3;           // 2: the hub became the main deck of the starship
                                                  // 3: the whole starship NEYRO-1 around the deck (Blender model)
    static final int WALL_H = 6;                  // hull wall height around the deck
    static final double DOME_FLAT = 0.62;         // dome height = radius * this

    static final int FLOOR_Y = 100;           // top layer of the platform
    static final int RADIUS = 24;             // platform radius
    static final int PORTAL_DIST = 18;        // distance from centre to every portal
    static final int CORE_Y = 114;            // height of the floating core

    static final int ORANGE = 0xFF8C00;
    static final int CRAFT_BLUE = 0x4FC3FF;       // Craft in cosmic blue
    private static final int ANARCHY_RED = 0xFF3B50;
    private static final int SURVIVAL_GREEN = 0x88F7AC;
    private static final int MINIGAME_VIOLET = 0xD18BFF;
    private static final int STAR_WARS_GOLD = 0xFFD371;

    private World hub;
    private String survivalWorldName;
    private String serverAddress;
    private NamespacedKey survivalKey;
    private boolean authMePresent;
    private Simulation simulation;
    void saveRestartSessions() { if (simulation != null) simulation.saveRestartSessions(); }
    private Hyperjump animus;      // arrival on the deck (hyperjump exit)
    private LaserTag laser;
    private Object authMeApi;

    private final List<BlockDisplay> orbitA = new ArrayList<>();
    private final List<BlockDisplay> orbitB = new ArrayList<>();
    private BlockDisplay coreInner;
    private BlockDisplay coreShell;
    private TextDisplay infoBoard;
    private org.bukkit.entity.Interaction infoBoardButton;
    private final Map<String, TextDisplay> openLabels = new HashMap<>();
    private final Map<UUID, Long> portalCooldown = new HashMap<>();
    private long tick = 0;
    private final java.util.Random streakRnd = new java.util.Random();

    /** One portal on the platform. */
    record Portal(String id, String title, String subtitle, int textColor, boolean open,
                  int cx, int cz, Axis axis, int innerWidth, int innerHeight,
                  Material frame, Material corner) {}

    private final List<Portal> portals = List.of(
            new Portal("survival", "ВЫЖИВАНИЕ", "Классическое выживание", ORANGE, true,
                    0, -PORTAL_DIST, Axis.X, 7, 10, Material.ORANGE_CONCRETE, Material.OCHRE_FROGLIGHT),
            new Portal("skyblock", "СКАЙБЛОК", "Свой остров в небе", 0x55FFFF, true,
                    PORTAL_DIST, 0, Axis.Z, 5, 8, Material.CYAN_CONCRETE, Material.SEA_LANTERN),
            new Portal("lasertag", "LASERTAG", "PvP на бластерах", 0xFF55FF, true,
                    0, PORTAL_DIST, Axis.X, 5, 8, Material.PURPLE_CONCRETE, Material.PEARLESCENT_FROGLIGHT),
            new Portal("game3", "АНАРХИЯ", "Алые врата · храм Тёмного Лорда", ANARCHY_RED, true,
                    -PORTAL_DIST, 0, Axis.Z, 5, 8, Material.RED_NETHER_BRICKS, Material.SHROOMLIGHT)
    );

    // ------------------------------------------------------------------ setup

    @Override
    public void onEnable() {
        saveDefaultConfig();
        survivalWorldName = getConfig().getString("survival-world", "world");
        serverAddress = getConfig().getString("server-address", "");
        survivalKey = new NamespacedKey(this, "survival_location");

        hub = new WorldCreator(HUB_WORLD).generator(new VoidGenerator()).createWorld();
        if (hub == null) {
            getLogger().severe("Could not create hub world");
            return;
        }
        setupWorldRules(hub);
        darkAnarchy = new DarkAnarchy(this);
        if (darkAnarchy.world() != null) anarchyDrop = new AnarchyDrop(this, darkAnarchy.world());
        WarpTunnel.init(this, hub);
        for (int cx = -2; cx <= 1; cx++) for (int cz = -2; cz <= 1; cz++) hub.setChunkForceLoaded(cx, cz, true);

        File marker = new File(getDataFolder(), "built-v" + BUILD_VERSION);
        if (!marker.exists()) {
            buildHub();
            try {
                getDataFolder().mkdirs();
                Files.writeString(marker.toPath(), "built");
            } catch (IOException e) {
                getLogger().warning("Could not write build marker: " + e);
            }
        }
        buildAnarchyDock();
        spawnDisplays();
        animus = new Hyperjump(this, hub, FLOOR_Y);
        laser = new LaserTag(this, hub);
        try {
            retro = new RetroWorld(this);
            retro.start();
            getServer().getPluginManager().registerEvents(retro, this);
        } catch (RuntimeException ex) {
            getLogger().severe("Minecraft 1.0 world failed to start: " + ex);
            ex.printStackTrace();
            retro = null;
        }
        voice = new VoicePack(this);
        voice.start();
        music = new HubMusic(this, voice);
        music.start();
        guard = new NeyroGuard(this);
        getServer().getPluginManager().registerEvents(guard, this);
        org.bukkit.command.PluginCommand ng = getCommand("neyroguard");
        if (ng != null) { ng.setExecutor(guard); ng.setTabCompleter(guard); }
        guard.start();
        try {
            spawnExtras();
        } catch (RuntimeException ex) {
            getLogger().severe("Ami / station Orion failed to start: " + ex);
            ex.printStackTrace();
        }
        starPass = new StarPass(this);
        getServer().getPluginManager().registerEvents(starPass, this);
        starPass.start(hub, new Location(hub, 6.5, FLOOR_Y + 1, -4.5));
        try {
            race = new PlanetRace(this);
            getServer().getPluginManager().registerEvents(race, this);
            race.start();
        } catch (RuntimeException ex) {
            getLogger().severe("Planet Terrax failed to start: " + ex);
            ex.printStackTrace();
            race = null;
        }
        getServer().getPluginManager().registerEvents(new HubShield(this), this);
        ServerReboot reboot = new ServerReboot(this);
        getServer().getPluginManager().registerEvents(reboot, this);
        org.bukkit.command.PluginCommand rb = getCommand("reboot");
        if (rb != null) rb.setExecutor(reboot);
        gear = new NeyroGear(this);
        getServer().getPluginManager().registerEvents(gear, this);
        org.bukkit.command.PluginCommand gearCommand = getCommand("neyrogear");
        if (gearCommand != null) gearCommand.setExecutor(gear);
        gear.start();
        hud = new HudBoard(this);
        classes = new NeyroClasses(this);
        getServer().getPluginManager().registerEvents(classes, this);
        evilPass = new EvilPass(this);
        Bukkit.getPluginManager().registerEvents(evilPass, this);
        getServer().getPluginManager().registerEvents(hud, this);
        hud.start();
        dialogue = new Dialogue(this);
        getServer().getPluginManager().registerEvents(new NeyroChat(this), this);
        getServer().getPluginManager().registerEvents(dialogue, this);
        money = new NeyroMoney(this);
        casino = new NeyroCasino(this);
        getServer().getPluginManager().registerEvents(money, this);
        money.start();
        homes = new NeyroHomes(this);
        beamDrop = new BeamDrop(this);
        getServer().getPluginManager().registerEvents(beamDrop, this);
        karma = new NeyroKarma(this);
        getServer().getPluginManager().registerEvents(karma, this);
        karma.start();
        try {
            city = new NeyroCity(this);
            getServer().getPluginManager().registerEvents(city, this);
            city.start();
        } catch (RuntimeException ex) {
            getLogger().severe("Neyro-City failed to start: " + ex);
            ex.printStackTrace();
            city = null;
        }

        getServer().getPluginManager().registerEvents(this, this);
        getServer().getPluginManager().registerEvents(laser, this);
        if (getConfig().getBoolean("auth.enabled", true)) {
            if (Bukkit.getPluginManager().getPlugin("AuthMe") != null) {
                getLogger().warning("AuthMe is installed: NeyroAuth is disabled to avoid two login systems");
                hookAuthMeLogin();
            } else {
                AccountStore store = new AccountStore(getDataFolder(), getLogger());
                simulation = new Simulation(this, hub, store);
                int imported = simulation.importAuthMe();
                if (imported > 0) getLogger().info("Imported " + imported + " account(s) from AuthMe");
                getServer().getPluginManager().registerEvents(simulation, this);
                getLogger().info("NeyroAuth enabled, " + store.size() + " account(s)");
            }
        } else {
            hookAuthMeLogin();
        }
        Bukkit.getScheduler().runTaskTimer(this, this::animate, 20L, 2L);
        Bukkit.getScheduler().runTaskTimer(this, this::updateTexts, 20L, 20L);
        getLogger().info("NeyroHub ready in world '" + HUB_WORLD + "'");
    }

    @Override
    public void onDisable() {
        for (Player p : Bukkit.getOnlinePlayers()) cancelArrival(p, true);
        if (animus != null) animus.stopAll();
        if (laser != null) laser.shutdown();
        if (simulation != null) simulation.shutdown();
        if (music != null) music.shutdown();
        if (guard != null) guard.shutdown();
        if (dialogue != null) dialogue.shutdown();
        if (mainAmi != null) mainAmi.remove();
        if (starPass != null) starPass.shutdown();
        if (sabers != null) sabers.shutdown();
        if (cinema != null) cinema.shutdown();
        if (starfight != null) starfight.shutdown();
        if (race != null) race.shutdown();
        if (hud != null) hud.shutdown();
        if (city != null) city.shutdown();
        if (beamDrop != null) beamDrop.shutdown();
        if (karma != null) karma.shutdown();
        if (money != null) money.shutdown();
        WarpTunnel.stopAll();
        if (orion != null) orion.shutdown();
        if (voice != null) voice.stop();
        if (gear != null) gear.shutdown();
        if (anarchyDrop != null) anarchyDrop.clear();
        if (darkAnarchy != null && darkAnarchy.world() != null) Bukkit.unloadWorld(darkAnarchy.world(), true);
        removeDisplays();
    }

    private NeyroGuard guard;
    private DarkAnarchy darkAnarchy;
    private AnarchyDrop anarchyDrop;
    private EvilPass evilPass;
    private NeyroClasses classes;

    EvilPass evilPass() { return evilPass; }
    NeyroClasses classes() { return classes; }
    NeyroGear gear() { return gear; }
    private RetroWorld retro;
    private VoicePack voice;
    private NeyroGear gear;
    private NeyroMoney money;
    private NeyroMail mail;
    private NeyroCasino casino;
    private NeyroHomes homes;

    NeyroMoney money() { return money; }
    NeyroCity city() { return city; }
    NeyroHousing housing() { return city == null ? null : city.housing(); }

    VoicePack voice() {
        return voice;
    }

    private HudBoard hud;

    HudBoard hud() {
        return hud;
    }

    private NeyroCity city;
    private BeamDrop beamDrop;
    private NeyroKarma karma;

    BeamDrop beamDrop() {
        return beamDrop;
    }

    NeyroKarma karma() {
        return karma;
    }

    private Dialogue dialogue;

    /** Mass Effect style conversations with NPCs (Ami, the people of Neyro-City). */
    Dialogue dialogue() {
        return dialogue;
    }

    String survivalWorldName() {
        return survivalWorldName;
    }

    boolean survivalWorld(World w) {
        return isSurvivalWorld(w);
    }

    /** From the beam on the city plaza up to the main deck; coming back, the player lands at {@code backTo}. */
    void liftToShip(Player p, Location backTo) {
        if (BeamTransfer.busy(p) || hub == null) return;
        rememberSurvival(p, backTo);
        BeamTransfer.run(this, p, spawn(), Color.fromRGB(0x40, 0xE8, 0xFF), Color.fromRGB(0xFF, 0x4F, 0xD8),
                BeamTransfer.title(serverName(), Component.text("лифт · главная палуба NEYRO-1", NamedTextColor.GRAY)));
    }

    private void rememberSurvival(Player p, Location l) {
        if (l == null || l.getWorld() == null) return;
        String s = l.getWorld().getName() + ";" + l.getX() + ";" + l.getY() + ";" + l.getZ() + ";" + l.getYaw() + ";" + l.getPitch();
        p.getPersistentDataContainer().set(survivalKey, PersistentDataType.STRING, s);
    }

    boolean laserInGame(Player p) { return laser != null && laser.inGame(p); }

    boolean sabersInGame(Player p) { return sabers != null && sabers.inGame(p); }

    boolean inRetro(Player p) { return retro != null && retro.in(p.getWorld()); }

    boolean inSkyblock(Player p) { return isSkyblockWorld(p.getWorld()); }

    boolean inSurvival(Player p) { return isSurvivalWorld(p.getWorld()); }

    boolean inOrion(Player p) { return orion != null && orion.contains(p.getLocation()); }

    private PlanetRace race;

    PlanetRace race() {
        return race;
    }

    Location orionSpawn() {
        return orion == null ? null : orion.spawn();
    }

    private StarPass starPass;

    StarPass starPass() {
        return starPass;
    }

    /** Logged in (passed the VR capsule). */
    boolean authed(Player p) {
        return simulation == null || simulation.isAuthed(p);
    }

    /** StarPass items go into the real inventory: only in the hubs or survival, never inside a mini-game. */
    boolean canReceiveItems(Player p) {
        if (!authed(p)) return false;
        if (laser != null && laser.inGame(p)) return false;
        if (race != null && race.in(p)) return false;
        if (starfight != null && starfight.inGame(p)) return false;
        if (sabers != null && sabers.inGame(p)) return false;
        if (retro != null && retro.in(p.getWorld())) return false;
        return inHub(p) || isSurvivalWorld(p.getWorld());
    }

    private Vault vault;

    Vault vault() {
        if (vault == null) vault = new Vault(this);
        return vault;
    }
    private HubMusic music;

    private void setupWorldRules(World w) {
        w.setDifficulty(Difficulty.PEACEFUL);
        w.setSpawnLocation(0, FLOOR_Y + 1, 0);
        w.setTime(18000);
        w.setStorm(false);
        w.setThundering(false);
        w.setClearWeatherDuration(Integer.MAX_VALUE);
        for (String rule : new String[]{"advance_time", "advance_weather", "spawn_mobs"}) {
            try {
                Bukkit.dispatchCommand(Bukkit.getConsoleSender(),
                        "execute in minecraft:" + HUB_WORLD + " run gamerule " + rule + " false");
            } catch (Exception e) {
                getLogger().warning("Could not set gamerule " + rule + ": " + e.getMessage());
            }
        }
    }

    Location spawn() {
        // centre of the platform, facing the survival portal (north)
        return new Location(hub, 0.5, FLOOR_Y + 1, 0.5, 180f, 0f);
    }

    // ------------------------------------------------------------------ build

    private void set(int x, int y, int z, Material m) {
        hub.getBlockAt(x, y, z).setType(m, false);
    }

    private static int hash(int x, int y, int z) {
        int h = x * 73856093 ^ y * 19349663 ^ z * 83492791;
        h ^= (h >>> 13);
        h *= 0x5bd1e995;
        return Math.floorMod(h ^ (h >>> 15), 1000);
    }

    void buildHub() {
        getLogger().info("Building the hub...");
        long started = System.currentTimeMillis();
        ShipModel ship = ShipModel.load(this);
        // clear the area first (rebuild support): the old round deck and the whole starship
        int x0 = -RADIUS - 2, x1 = RADIUS + 2, z0 = -RADIUS - 2, z1 = RADIUS + 2;
        int y0 = FLOOR_Y - 24, y1 = FLOOR_Y + 26;
        if (ship != null) {
            x0 = Math.min(x0, ship.minX - 2);
            x1 = Math.max(x1, ship.maxX + 2);
            z0 = Math.min(z0, ship.minZ - 2);
            z1 = Math.max(z1, ship.maxZ + 2);
            y0 = Math.min(y0, FLOOR_Y + ship.minDy - 2);
            y1 = Math.max(y1, FLOOR_Y + ship.maxDy + 2);
        }
        int cleared = ShipModel.clear(hub, x0, y0, z0, x1, y1, z1);

        // the starship NEYRO-1 around the deck: hull, wings, engines, bridge tower (source/ship-src)
        if (ship != null) ship.place(hub, FLOOR_Y);

        for (int x = -RADIUS - 1; x <= RADIUS + 1; x++) {
            for (int z = -RADIUS - 1; z <= RADIUS + 1; z++) {
                double r = Math.sqrt(x * x + z * z);
                if (r > RADIUS + 0.5) continue;
                set(x, FLOOR_Y, z, floorMaterial(x, z, r));
                // hull wall of the deck with a band of windows
                if (r > RADIUS - 0.6) {
                    int ang = (int) Math.round(Math.toDegrees(Math.atan2(z, x)));
                    boolean rib = Math.floorMod(ang, 15) <= 1;
                    for (int y = 1; y <= WALL_H; y++) {
                        Material m = Material.WHITE_CONCRETE;
                        if (y >= 2 && y <= 4) m = rib ? Material.LIGHT_GRAY_CONCRETE : Material.GLASS;
                        if (y == WALL_H && Math.floorMod(ang, 30) <= 1) m = Material.OCHRE_FROGLIGHT;
                        set(x, FLOOR_Y + y, z, m);
                    }
                }
                // without the model: the old cone-shaped hull underneath the deck
                if (ship != null) continue;
                int depth = (int) Math.round(Math.pow(Math.max(0, 1 - r / (RADIUS + 0.5)), 0.6) * 12);
                for (int d = 1; d <= depth; d++) {
                    int roll = hash(x, -d, z);
                    Material m = d == 1 || r > RADIUS - 1.5 ? Material.GRAY_CONCRETE
                            : roll < 450 ? Material.LIGHT_GRAY_CONCRETE
                            : roll < 800 ? Material.GRAY_CONCRETE
                            : Material.POLISHED_DEEPSLATE;
                    if (d == depth && roll % 7 == 0) m = Material.SHROOMLIGHT;     // belly lights
                    set(x, FLOOR_Y - d, z, m);
                }
            }
        }

        // glass dome with a light-grey frame (meridians every 30°, rings every 4 blocks)
        double domeBase = FLOOR_Y + WALL_H;
        for (int x = -RADIUS - 1; x <= RADIUS + 1; x++) {
            for (int z = -RADIUS - 1; z <= RADIUS + 1; z++) {
                for (int y = (int) domeBase + 1; y <= domeBase + RADIUS * DOME_FLAT + 1; y++) {
                    double dy = (y - domeBase) / DOME_FLAT;
                    double d = Math.sqrt(x * x + z * z + dy * dy);
                    if (d < RADIUS - 0.55 || d >= RADIUS + 0.45) continue;
                    int ang = (int) Math.round(Math.toDegrees(Math.atan2(z, x)));
                    boolean frame = Math.floorMod(ang, 30) <= 1 || (y - FLOOR_Y) % 4 == 0;
                    set(x, y, z, frame ? Material.LIGHT_GRAY_CONCRETE : Material.GLASS);
                }
            }
        }

        // central dais with a glowing heart under the spawn point
        set(0, FLOOR_Y, 0, Material.OCHRE_FROGLIGHT);

        // synapse nodes on the diagonals
        for (int[] d : new int[][]{{1, 1}, {1, -1}, {-1, 1}, {-1, -1}}) {
            int nx = d[0] * 9, nz = d[1] * 9;
            for (int x = -1; x <= 1; x++) for (int z = -1; z <= 1; z++)
                set(nx + x, FLOOR_Y, nz + z, Material.ORANGE_CONCRETE);
            set(nx, FLOOR_Y, nz, Material.SHROOMLIGHT);
            set(nx, FLOOR_Y + 1, nz, Material.END_ROD);
        }

        for (Portal p : portals) buildPortal(p);
        getLogger().info("Hub built: " + (ship != null ? "starship NEYRO-1, " + ship.blocks + " hull blocks" : "deck only")
                + ", " + cleared + " old blocks cleared, " + (System.currentTimeMillis() - started) + " ms");
    }

    private Material floorMaterial(int x, int z, double r) {
        if (r < 1.5) return Material.BLACK_CONCRETE;
        if (r < 3.5) return Material.BLACK_CONCRETE;
        if (r < 4.5) return Material.ORANGE_CONCRETE;
        if (r >= 11.5 && r < 12.5) return Material.ORANGE_CONCRETE;
        if (r >= 12.5 && r < 13.2) return Material.GRAY_CONCRETE;
        if (r > RADIUS - 1.5) return Material.POLISHED_BLACKSTONE_BRICKS;
        // neural veins: cardinal lines (to the portals) and diagonals (to the nodes)
        boolean cardinal = (Math.abs(x) <= 0 || Math.abs(z) <= 0);
        boolean diagonal = Math.abs(Math.abs(x) - Math.abs(z)) == 0 && r < 12;
        if (cardinal || diagonal) return Material.SHROOMLIGHT;
        // little branches off the veins
        if ((Math.abs(x) == 1 || Math.abs(z) == 1) && hash(x, 7, z) % 7 == 0) return Material.ORANGE_TERRACOTTA;
        int h = hash(x, FLOOR_Y, z);
        if (h < 60) return Material.GILDED_BLACKSTONE;
        if (h < 500) return Material.POLISHED_BLACKSTONE;
        if (h < 800) return Material.BLACKSTONE;
        return Material.POLISHED_DEEPSLATE;
    }

    private void buildPortal(Portal p) {
        int half = p.innerWidth() / 2;
        BlockData portalBlock = Bukkit.createBlockData(Material.NETHER_PORTAL, bd -> ((Orientable) bd).setAxis(p.axis()));
        for (int w = -half - 1; w <= half + 1; w++) {
            for (int h = 0; h <= p.innerHeight() + 1; h++) {
                int x = p.axis() == Axis.X ? p.cx() + w : p.cx();
                int z = p.axis() == Axis.X ? p.cz() : p.cz() + w;
                int y = FLOOR_Y + h;
                boolean edgeW = Math.abs(w) == half + 1;
                boolean edgeH = h == 0 || h == p.innerHeight() + 1;
                Block b = hub.getBlockAt(x, y, z);
                if (edgeW && edgeH) b.setType(p.corner(), false);
                else if (edgeW || edgeH) b.setType(p.frame(), false);
                else if (p.id().equals("game3")) b.setType(Material.AIR, false);
                else b.setBlockData(portalBlock, false);
            }
        }
        // pillars with lights next to the portal
        for (int side : new int[]{-1, 1}) {
            int off = (half + 3) * side;
            int x = p.axis() == Axis.X ? p.cx() + off : p.cx();
            int z = p.axis() == Axis.X ? p.cz() : p.cz() + off;
            for (int y = FLOOR_Y + 1; y <= FLOOR_Y + p.innerHeight() - 1; y++)
                set(x, y, z, Material.POLISHED_BLACKSTONE_BRICKS);
            set(x, FLOOR_Y + p.innerHeight(), z, p.corner());
            set(x, FLOOR_Y + p.innerHeight() + 1, z, Material.END_ROD);
        }
    }

    private void buildAnarchyDock() {
        Portal gate = portals.stream().filter(portal -> portal.id().equals("game3")).findFirst().orElseThrow();
        buildPortal(gate);
        // Crimson runway and inset lights lead from the deck to the temple gate.
        for (int x = gate.cx(); x <= -8; x++) for (int z = -2; z <= 2; z++) {
            Material panel = Math.abs(z) == 2 ? (x % 3 == 0 ? Material.SHROOMLIGHT : Material.RED_NETHER_BRICKS)
                    : z == 0 && x % 2 == 0 ? Material.RED_TERRACOTTA : Material.POLISHED_BLACKSTONE_BRICKS;
            set(x, FLOOR_Y, z, panel);
        }
        for (int z : new int[]{-4, 4}) {
            for (int dy = 1; dy <= 6; dy++)
                set(gate.cx() + 2, FLOOR_Y + dy, z, dy == 4 ? Material.SHROOMLIGHT : Material.CHISELED_POLISHED_BLACKSTONE);
            set(gate.cx() + 2, FLOOR_Y + 7, z, Material.RED_NETHER_BRICKS);
        }
    }

    // --------------------------------------------------------------- displays

    private void removeDisplays() {
        if (hub == null) return;
        for (Entity e : hub.getEntities()) if (e.getScoreboardTags().contains(TAG)) e.remove();
        orbitA.clear();
        orbitB.clear();
    }

    private void spawnDisplays() {
        removeDisplays();
        spawnDeckDecor();
        Location coreLoc = new Location(hub, 0.5, CORE_Y, 0.5);

        coreInner = hub.spawn(coreLoc, BlockDisplay.class, d -> {
            prepare(d);
            d.setBlock(Material.SHROOMLIGHT.createBlockData());
            d.setTransformation(cube(2.4f, 0));
            d.setBrightness(new Display.Brightness(15, 15));
        });
        coreShell = hub.spawn(coreLoc, BlockDisplay.class, d -> {
            prepare(d);
            d.setBlock(Material.ORANGE_STAINED_GLASS.createBlockData());
            d.setTransformation(cube(4.2f, 0));
            d.setBrightness(new Display.Brightness(15, 15));
        });
        for (int i = 0; i < 12; i++) {
            Material m = i % 2 == 0 ? Material.OCHRE_FROGLIGHT : Material.POLISHED_BLACKSTONE;
            orbitA.add(hub.spawn(coreLoc, BlockDisplay.class, d -> {
                prepare(d);
                d.setBlock(m.createBlockData());
                d.setTransformation(cube(0.7f, 0));
                d.setTeleportDuration(2);
            }));
        }
        for (int i = 0; i < 8; i++) {
            orbitB.add(hub.spawn(coreLoc, BlockDisplay.class, d -> {
                prepare(d);
                d.setBlock(Material.ORANGE_STAINED_GLASS.createBlockData());
                d.setTransformation(cube(0.5f, 0));
                d.setTeleportDuration(2);
                d.setBrightness(new Display.Brightness(15, 15));
            }));
        }

        // title floating over the core
        hub.spawn(new Location(hub, 0.5, CORE_Y + 4.2, 0.5), TextDisplay.class, t -> {
            prepare(t);
            t.text(serverName());
            t.setBillboard(Display.Billboard.CENTER);
            t.setBackgroundColor(Color.fromARGB(0, 0, 0, 0));
            t.setShadowed(true);
            t.setTransformation(scale(4.0f));
        });

        // painted planets around the ship, seen through the dome and the windows
        SpaceDecor.planet(this, hub, new Location(hub, 75, FLOOR_Y + 28, -55), SpaceDecor.Kind.GAS, 40f, 21);
        SpaceDecor.planet(this, hub, new Location(hub, -70, FLOOR_Y + 18, -45), SpaceDecor.Kind.TERRA, 34f, 7);
        SpaceDecor.planet(this, hub, new Location(hub, 25, FLOOR_Y + 42, 60), SpaceDecor.Kind.MOON, 10f, 13);
        SpaceDecor.planet(this, hub, new Location(hub, -50, FLOOR_Y + 34, 62), SpaceDecor.Kind.ICE, 14f, 17);

        // information board in front of the spawn point
        infoBoard = hub.spawn(new Location(hub, 0.5, FLOOR_Y + 2.2, -6.5), TextDisplay.class, t -> {
            prepare(t);
            t.setBillboard(Display.Billboard.CENTER);
            t.setBackgroundColor(Color.fromARGB(170, 10, 10, 10));
            t.setLineWidth(260);
            t.setTransformation(scale(1.15f));
        });
        infoBoardButton = hub.spawn(new Location(hub, 0.5, FLOOR_Y + 1, -6.5), org.bukkit.entity.Interaction.class, button -> {
            button.addScoreboardTag(TAG);
            button.setPersistent(false);
            button.setInteractionWidth(2.5f);
            button.setInteractionHeight(3.5f);
            button.setResponsive(true);
        });

        for (Portal p : portals) {
            int top = FLOOR_Y + p.innerHeight() + 2;
            TextDisplay label = hub.spawn(new Location(hub, p.cx() + 0.5, top + 0.4, p.cz() + 0.5), TextDisplay.class, t -> {
                prepare(t);
                t.setBillboard(Display.Billboard.CENTER);
                t.setBackgroundColor(Color.fromARGB(120, 0, 0, 0));
                t.setShadowed(true);
                t.setTransformation(scale(p.open() ? 2.6f : 2.0f));
                t.text(portalLabel(p, 0));
            });
            if (p.open()) openLabels.put(p.id(), label);
            if (p.id().equals("game3")) {
                hub.spawn(new Location(hub, p.cx() + 0.46, FLOOR_Y + 1, p.cz() - 2), BlockDisplay.class, d -> {
                    prepare(d);
                    d.setBlock(Material.RED_STAINED_GLASS.createBlockData());
                    d.setBrightness(new Display.Brightness(14, 15));
                    d.setTransformation(new Transformation(new Vector3f(), new AxisAngle4f(),
                            new Vector3f(0.08f, p.innerHeight(), p.innerWidth()), new AxisAngle4f()));
                });
            }
        }
        updateTexts();
    }

    private void spawnDeckDecor() {
        for (int side : new int[]{-1, 1}) {
            double x = 0.5 + side * 5;
            double z = 5.5;
            deckDisplay(x, FLOOR_Y + 1, z, Material.POLISHED_BLACKSTONE, 2.4f, .24f, 2.4f);
            deckDisplay(x, FLOOR_Y + 1.24, z, Material.COPPER_GRATE, 1.6f, .22f, 1.6f);
            deckDisplay(x, FLOOR_Y + 1.46, z, Material.CYAN_STAINED_GLASS, 1.05f, .48f, 1.05f);
            deckDisplay(x, FLOOR_Y + 1.56, z, Material.SEA_LANTERN, .58f, .29f, .58f);
            deckDisplay(x, FLOOR_Y + 1.94, z, Material.POLISHED_BLACKSTONE, 1.25f, .13f, 1.25f);
            for (int edge : new int[]{-1, 1}) {
                deckDisplay(x + edge * .93, FLOOR_Y + 1.24, z, Material.OCHRE_FROGLIGHT, .14f, .06f, 1.85f);
            }
        }
    }

    private void deckDisplay(double x, double y, double z, Material material, float width, float height, float depth) {
        hub.spawn(new Location(hub, x - width / 2, y, z - depth / 2), BlockDisplay.class, display -> {
            prepare(display);
            display.setBlock(material.createBlockData());
            display.setTransformation(new Transformation(new Vector3f(), new AxisAngle4f(),
                    new Vector3f(width, height, depth), new AxisAngle4f()));
        });
    }

    void prepare(Display d) {
        d.addScoreboardTag(TAG);
        d.setPersistent(false);
        d.setViewRange(4.0f);
    }

    private static Transformation cube(float size, float angle) {
        return new Transformation(new Vector3f(-size / 2, -size / 2, -size / 2),
                new AxisAngle4f(angle, 0, 1, 0), new Vector3f(size, size, size), new AxisAngle4f());
    }

    private static Transformation scale(float s) {
        return new Transformation(new Vector3f(), new AxisAngle4f(), new Vector3f(s, s, s), new AxisAngle4f());
    }

    // -------------------------------------------------------------- animation

    private void animate() {
        tick++;
        if (hub == null || hub.getPlayers().isEmpty()) return;
        if (mainAmi != null) mainAmi.animate();
        double t = tick * 0.05;

        // spinning core: inner and shell rotate in opposite directions on tilted axes
        if (coreInner != null && coreInner.isValid()) spin(coreInner, 2.4f, (float) (t * 1.3), new Vector3f(0.3f, 1f, 0.2f));
        if (coreShell != null && coreShell.isValid()) spin(coreShell, 4.2f, (float) (-t * 0.7), new Vector3f(-0.2f, 1f, 0.35f));

        // two orbit rings: one tilted, one flat and faster
        Location c = new Location(hub, 0.5, CORE_Y, 0.5);
        for (int i = 0; i < orbitA.size(); i++) {
            double a = t * 0.6 + i * (Math.PI * 2 / orbitA.size());
            double x = Math.cos(a) * 6.5, z = Math.sin(a) * 6.5;
            double y = Math.sin(a) * 2.2;   // tilt
            BlockDisplay d = orbitA.get(i);
            if (d.isValid()) d.teleport(c.clone().add(x, y, z));
        }
        for (int i = 0; i < orbitB.size(); i++) {
            double a = -t * 1.1 + i * (Math.PI * 2 / orbitB.size());
            BlockDisplay d = orbitB.get(i);
            if (d.isValid()) d.teleport(c.clone().add(Math.cos(a) * 4.0, Math.cos(a * 2) * 0.6, Math.sin(a) * 4.0));
        }

        // neural impulses running from the core down the veins to the portals
        Particle.DustOptions orange = new Particle.DustOptions(Color.fromRGB(255, 150, 20), 1.4f);
        Particle.DustOptions light = new Particle.DustOptions(Color.fromRGB(255, 230, 160), 1.0f);
        for (int k = 0; k < 3; k++) {
            double prog = ((tick + k * 20) % 60) / 60.0;      // 0..1
            double dist = 2 + prog * (PORTAL_DIST - 3);
            for (int[] dir : new int[][]{{0, -1}, {1, 0}, {0, 1}, {-1, 0}}) {
                Location l = new Location(hub, 0.5 + dir[0] * dist, FLOOR_Y + 1.15, 0.5 + dir[1] * dist);
                hub.spawnParticle(Particle.DUST, l, 2, 0.05, 0.02, 0.05, 0, orange);
                hub.spawnParticle(Particle.DUST, l, 1, 0, 0, 0, 0, light);
            }
        }
        // beam from core down to the spawn point
        if (tick % 2 == 0) {
            double y = FLOOR_Y + 1.5 + ((tick * 0.35) % (CORE_Y - FLOOR_Y - 2));
            hub.spawnParticle(Particle.END_ROD, new Location(hub, 0.5, y, 0.5), 1, 0.05, 0, 0.05, 0);
        }
        // the ship is flying: stars rush past outside the dome
        SpaceDecor.streaks(hub, new Location(hub, 0.5, FLOOR_Y + 10, 0.5), 70, 30, RADIUS + 3, 6, streakRnd);

        // sparks around the core
        if (tick % 3 == 0) hub.spawnParticle(Particle.WAX_ON, c, 4, 2.5, 2.5, 2.5, 0);
        hub.spawnParticle(Particle.DUST,
                new Location(hub, -PORTAL_DIST + 0.5, FLOOR_Y + 4.5, 0.5),
                12, 0.08, 1.6, 0.95, 0, new Particle.DustOptions(Color.fromRGB(ANARCHY_RED), 1.3f));

        // pulse the labels of the open portals
        if (tick % 4 == 0) {
            for (Portal p : portals) {
                TextDisplay label = openLabels.get(p.id());
                if (label != null && label.isValid()) label.text(portalLabel(p, tick));
            }
        }
    }

    private static void spin(BlockDisplay d, float size, float angle, Vector3f axis) {
        Quaternionf q = new Quaternionf().rotateAxis(angle, axis.normalize(new Vector3f()));
        Vector3f half = new Vector3f(-size / 2, -size / 2, -size / 2);
        Vector3f offset = q.transform(new Vector3f(half));
        d.setInterpolationDelay(0);
        d.setInterpolationDuration(2);
        d.setTransformation(new Transformation(offset, q, new Vector3f(size, size, size), new Quaternionf()));
    }

    // ------------------------------------------------------------------ texts

    static Component serverName() {
        return Component.text()
                .append(Component.text("Neyro", TextColor.color(ORANGE)).decorate(TextDecoration.BOLD))
                .append(Component.text("Craft", TextColor.color(CRAFT_BLUE)).decorate(TextDecoration.BOLD))
                .build();
    }

    private Component portalLabel(Portal p, long t) {
        TextComponent.Builder b = Component.text();
        if (p.open()) {
            double pulse = (Math.sin(t * 0.15) + 1) / 2;
            int col = mix(p.textColor(), 0xFFFFFF, pulse * 0.6);
            String icon = p.id().equals("skyblock") ? "☁" : p.id().equals("lasertag") ? "⚡" : p.id().equals("game3") ? "☠" : "⚔";
            int online = p.id().equals("skyblock") ? skyblockOnline()
                    : p.id().equals("lasertag") ? (laser == null ? 0 : laser.size())
                    : p.id().equals("game3") ? (darkAnarchy == null || darkAnarchy.world() == null ? 0 : darkAnarchy.world().getPlayers().size()) : survivalOnline();
            b.append(Component.text(icon + " " + p.title() + " " + icon, TextColor.color(col)).decorate(TextDecoration.BOLD))
                    .append(Component.newline())
                    .append(Component.text(p.subtitle(), NamedTextColor.GRAY))
                    .append(Component.newline())
                    .append(Component.text("Игроков: " + online, NamedTextColor.WHITE))
                    .append(Component.newline())
                    .append(Component.text("▶ Войди в портал", NamedTextColor.YELLOW));
        } else {
            b.append(Component.text(p.title(), TextColor.color(p.textColor())).decorate(TextDecoration.BOLD))
                    .append(Component.newline())
                    .append(Component.text("✖ СКОРО ✖", NamedTextColor.DARK_GRAY).decorate(TextDecoration.BOLD));
        }
        return b.build();
    }

    private int survivalOnline() {
        World w = Bukkit.getWorld(survivalWorldName);
        int n = 0;
        if (w != null) n += w.getPlayers().size();
        World nether = Bukkit.getWorld(survivalWorldName + "_nether");
        if (nether != null) n += nether.getPlayers().size();
        World end = Bukkit.getWorld(survivalWorldName + "_the_end");
        if (end != null) n += end.getPlayers().size();
        return n;
    }

    private static boolean isSkyblockWorld(World w) {
        return w.getName().startsWith("bskyblock");
    }

    private int skyblockOnline() {
        int n = 0;
        for (World w : Bukkit.getWorlds()) if (isSkyblockWorld(w)) n += w.getPlayers().size();
        return n;
    }

    private void updateTexts() {
        if (infoBoard == null || !infoBoard.isValid()) return;
        double tps = Math.min(20.0, Bukkit.getTPS()[0]);
        NamedTextColor tpsCol = tps >= 19 ? NamedTextColor.GREEN : tps >= 16 ? NamedTextColor.YELLOW : NamedTextColor.RED;
        TextComponent.Builder b = Component.text()
                .append(serverName())
                .append(Component.newline())
                .append(Component.newline())
                .append(Component.text("Главная палуба · корабль NEYRO-1", NamedTextColor.GRAY))
                .append(Component.newline())
                .append(Component.text("━━━━━━━━━━━━━━━━━━", TextColor.color(0x5A3200)))
                .append(Component.newline())
                .append(Component.text("Онлайн: ", NamedTextColor.GRAY))
                .append(Component.text(Bukkit.getOnlinePlayers().size() + "/" + Bukkit.getMaxPlayers(), NamedTextColor.WHITE))
                .append(Component.text("   TPS: ", NamedTextColor.GRAY))
                .append(Component.text(String.format("%.1f", tps), tpsCol))
                .append(Component.newline())
                .append(Component.text("Версия: ", NamedTextColor.GRAY))
                .append(Component.text("Java 1.21.11–26.3", TextColor.color(CRAFT_BLUE)));
        if (!serverAddress.isEmpty()) {
            b.append(Component.newline())
                    .append(Component.text("Адрес: ", NamedTextColor.GRAY))
                    .append(Component.text(serverAddress, TextColor.color(ORANGE)));
        }
        b.append(Component.newline()).append(Component.newline())
                .append(Component.text("Правила", TextColor.color(ORANGE)).decorate(TextDecoration.BOLD))
                .append(Component.newline())
                .append(Component.text("• Без читов и гриферства", NamedTextColor.GRAY))
                .append(Component.newline())
                .append(Component.text("• Уважай других игроков", NamedTextColor.GRAY))
                .append(Component.newline())
                .append(Component.text("• Не сообщай никому свой пароль", NamedTextColor.GRAY))
                .append(Component.newline()).append(Component.newline())
                .append(Component.text("/hub", NamedTextColor.YELLOW))
                .append(Component.text(" — вернуться сюда", NamedTextColor.GRAY))
                .append(Component.newline())
                .append(Component.text("❖ ВЫЖИВАНИЕ · портал впереди", TextColor.color(SURVIVAL_GREEN)))
                .append(Component.newline())
                .append(Component.text("Бирюзовый портал справа → Скайблок", NamedTextColor.WHITE))
                .append(Component.newline())
                .append(Component.text("✦ МИНИ-ИГРЫ · LaserTag, гонки", TextColor.color(MINIGAME_VIOLET)))
                .append(Component.newline())
                .append(Component.text("⚔ STAR WARS · /sabers и /space", TextColor.color(STAR_WARS_GOLD)))
                .append(Component.newline())
                .append(Component.text("На скайблоке: ", NamedTextColor.GRAY))
                .append(Component.text("/is", NamedTextColor.YELLOW))
                .append(Component.text(" — меню острова", NamedTextColor.GRAY));
        infoBoard.text(b.append(Component.newline())
                .append(Component.text("☠ АНАРХИЯ · алые врата слева · /hub3", TextColor.color(ANARCHY_RED)))
                .append(Component.newline())
                .append(Component.text("✦ ОРИОН И МИНИ-ИГРЫ · ПКМ по стенду или /hubinfo", TextColor.color(CRAFT_BLUE))).build());
    }

    private void openHubInfo(Player player) {
        if (!authed(player)) return;
        var first = Component.text("✦ NEYROCRAFT\n").color(NamedTextColor.DARK_BLUE).decorate(TextDecoration.BOLD)
                .append(Component.text("\nГлавная палуба · /hub\n\nВыживание — оранжевый портал. Строить и ломать можно за пределами 500 блоков по горизонтали от спавна.\n\nСкайблок — бирюзовый портал.\nАнархия — алые врата.\n\nОрион и арены → стр. 2", NamedTextColor.BLACK)
                        .decoration(TextDecoration.BOLD, false));
        var second = Component.text("✦ СТАНЦИЯ «ОРИОН»\n", NamedTextColor.DARK_BLUE).decorate(TextDecoration.BOLD)
                .append(Component.text("Лети через /hub2 или /orion. На главной палубе также можно попросить Ами.\n\n", NamedTextColor.BLACK)
                        .decoration(TextDecoration.BOLD, false))
                .append(Component.text("Лазертаг — фиолетовый портал в /hub.\n", NamedTextColor.BLACK).decoration(TextDecoration.BOLD, false))
                .append(bookLink("/sabers", "Бои на мечах"))
                .append(bookLink("/race", "Гонки на Терраксе"))
                .append(bookLink("/space", "Космические бои"))
                .append(bookLink("/kino", "Космокино"));
        player.openBook(net.kyori.adventure.inventory.Book.book(Component.text("Путеводитель NeyroCraft"),
                Component.text("NEYRO-1"), java.util.List.of(first, second)));
    }

    private static Component bookLink(String command, String name) {
        return Component.text(name + " · " + command + "\n", NamedTextColor.DARK_BLUE)
                .decoration(TextDecoration.BOLD, false)
                .clickEvent(net.kyori.adventure.text.event.ClickEvent.runCommand(command));
    }

    private static int mix(int a, int b, double t) {
        int r = (int) Math.round(((a >> 16) & 0xFF) * (1 - t) + ((b >> 16) & 0xFF) * t);
        int g = (int) Math.round(((a >> 8) & 0xFF) * (1 - t) + ((b >> 8) & 0xFF) * t);
        int bl = (int) Math.round((a & 0xFF) * (1 - t) + (b & 0xFF) * t);
        return (r << 16) | (g << 8) | bl;
    }

    // ------------------------------------------------------------ teleporting

    boolean inHub(Player p) {
        return hub != null && p.getWorld().equals(hub);
    }

    /** Inside one of our scripted scenes, where movement is driven by the plugin, not the player. */
    boolean inCutscene(Player p) {
        if (arrivals.containsKey(p.getUniqueId())) return true;
        if (WarpTunnel.busy(p) || BeamTransfer.busy(p)) return true;
        if (beamDrop != null && beamDrop.busy(p)) return true;
        if (race != null && race.riding(p)) return true;
        if (animus != null && animus.isLoading(p)) return true;
        if (simulation != null && (simulation.inChamber(p) || !simulation.isAuthed(p))) return true;
        if (p.getGameMode() == GameMode.SPECTATOR) return true;
        return p.isInsideVehicle() && inHub(p);
    }

    /** Standing on the main deck (not in the VR bay, the jump tunnel, the arena or mid-arrival). */
    boolean onDeck(Player p) {
        if (!inHub(p) || arrivals.containsKey(p.getUniqueId())) return false;
        if (animus != null && animus.isLoading(p)) return false;
        if (laser != null && laser.inGame(p)) return false;
        if (simulation != null && !simulation.isAuthed(p)) return false;
        Location l = p.getLocation();
        if (orion != null && orion.contains(l)) return true;      // the second hub has the same calm music
        double r = RADIUS + 8;
        return l.getX() * l.getX() + l.getZ() * l.getZ() <= r * r
                && l.getY() > FLOOR_Y - 8 && l.getY() < FLOOR_Y + RADIUS * DOME_FLAT + 10;
    }

    private boolean isSurvivalWorld(World w) {
        String n = w.getName();
        return n.equals(survivalWorldName) || n.equals(survivalWorldName + "_nether") || n.equals(survivalWorldName + "_the_end");
    }

    private void saveSurvivalLocation(Player p) {
        Location l = p.getLocation();
        if (!isSurvivalWorld(l.getWorld())) return;
        if (beamDrop != null && beamDrop.busy(p)) return;     // mid-slide down a beam: not a real place
        rememberSurvival(p, l);
    }

    private Location loadSurvivalLocation(Player p) {
        String s = p.getPersistentDataContainer().get(survivalKey, PersistentDataType.STRING);
        if (s != null) {
            String[] a = s.split(";");
            World w = Bukkit.getWorld(a[0]);
            if (w != null && a.length == 6) {
                return new Location(w, Double.parseDouble(a[1]), Double.parseDouble(a[2]), Double.parseDouble(a[3]),
                        Float.parseFloat(a[4]), Float.parseFloat(a[5]));
            }
        }
        // first time in survival: Neyro-City
        if (city != null && city.ready()) return city.spawn();
        World w = Bukkit.getWorld(survivalWorldName);
        if (w == null) w = Bukkit.getWorlds().get(0);
        Location sp = w.getSpawnLocation();
        return w.getHighestBlockAt(sp).getLocation().add(0.5, 1, 0.5);
    }

    void sendToHub(Player p, boolean cinematic) {
        if (hub == null) return;
        if (laser != null && laser.inGame(p)) laser.leave(p, true);
        saveSurvivalLocation(p);
        p.setFallDistance(0);
        p.setFoodLevel(20);
        p.setHealth(p.getAttribute(org.bukkit.attribute.Attribute.MAX_HEALTH).getValue());
        p.setFireTicks(0);
        if (cinematic && animus != null) {
            if (music != null) music.awaitGreeting(p);   // no music until the AI has greeted the player
            if (p.getGameMode() == GameMode.SURVIVAL) p.setGameMode(GameMode.ADVENTURE);
            animus.start(p);          // goes straight into the hyperjump tunnel
            return;
        }
        p.teleportAsync(spawn()).thenRun(() -> Bukkit.getScheduler().runTask(this, () -> {
            if (!p.isOnline()) return;
            if (p.getGameMode() == GameMode.SURVIVAL || p.getGameMode() == GameMode.SPECTATOR) p.setGameMode(GameMode.ADVENTURE);
        }));
    }

    // ------------------------------------------------------- arrival cinematic

    static final int ARRIVAL_TICKS = 150;         // 7.5 seconds
    static final double ARRIVAL_START_Y = 152;             // high above the ship, out of hyperspace
    static final double ARRIVAL_START_R = 15;
    private final Map<UUID, org.bukkit.scheduler.BukkitTask> arrivals = new HashMap<>();
    private final Map<UUID, GameMode> arrivalModes = new HashMap<>();
    private final Map<UUID, Entity> cameras = new HashMap<>();

    /** Smootherstep: starts gently, speeds up in the middle, settles gently. */
    private static double ease(double s) {
        s = Math.max(0, Math.min(1, s));
        return s * s * s * (s * (s * 6 - 15) + 10);
    }

    /** Key points of the flight: out of the core, a close orbit around it, a swoop down to the spawn. */
    private static final double[][] FLIGHT = {
            {16.5, 150, 6.5},            // high above the ship
            {10.0, 140, -8.0},           // spiralling down...
            {-4.0, 128, -7.0},
            {-3.0, 120, 3.0},            // ...through the glass dome
            {1.2, CORE_Y, 1.2},          // right through the reactor core
            {3.6, CORE_Y - 3.5, -2.5},   // a turn under the core
            {0.5, FLOOR_Y + 8, -7.0},
            {-5.5, FLOOR_Y + 6, -1.0},
            {-4.0, FLOOR_Y + 4.6, 6.5},  // sweeping round behind the spawn
            {0.5, FLOOR_Y + 3.4, 6.0},
            {0.5, FLOOR_Y + 1 + 1.62, 0.5}  // the player's eyes on the spawn point
    };

    private static double smooth01(double v) {
        v = Math.max(0, Math.min(1, v));
        return v * v * (3 - 2 * v);
    }

    /** Catmull-Rom spline through {@link #FLIGHT}: smooth speed and direction between all key points. */
    private static double[] flightPos(double u) {
        int n = FLIGHT.length;
        double f = Math.max(0, Math.min(1, u)) * (n - 1);
        int i = Math.min(n - 2, (int) Math.floor(f));
        double t = f - i, t2 = t * t, t3 = t2 * t;
        double[] p0 = FLIGHT[Math.max(0, i - 1)], p1 = FLIGHT[i], p2 = FLIGHT[i + 1], p3 = FLIGHT[Math.min(n - 1, i + 2)];
        double[] out = new double[3];
        for (int k = 0; k < 3; k++) {
            out[k] = 0.5 * ((2 * p1[k]) + (-p0[k] + p2[k]) * t
                    + (2 * p0[k] - 5 * p1[k] + 4 * p2[k] - p3[k]) * t2
                    + (-p0[k] + 3 * p1[k] - 3 * p2[k] + p3[k]) * t3);
        }
        return out;
    }

    /**
     * Camera at progress s (0..1): out of the core, around it and down onto the spawn point,
     * ending exactly in the eyes facing the survival portal.
     */
    private Location arrivalPoint(double s) {
        double e = ease(s);
        double[] pos = flightPos(e);
        double eyeY = FLOOR_Y + 1 + 1.62;
        // look at the core from above, down at the deck while diving through it, then ahead at the portal
        double w1 = smooth01((e - 0.22) / 0.18), w2 = smooth01((e - 0.62) / 0.32);
        double ax = 0.5, ay = CORE_Y, az = 0.5;
        double bx = 0.5, by = FLOOR_Y + 1, bz = 0.5;
        double cx = 0.5, cy = eyeY, cz = -25;
        double tx = ax + (bx - ax) * w1, ty = ay + (by - ay) * w1, tz = az + (bz - az) * w1;
        tx += (cx - tx) * w2;
        ty += (cy - ty) * w2;
        tz += (cz - tz) * w2;
        double dx = tx - pos[0], dy = ty - pos[1], dz = tz - pos[2];
        double flat = Math.sqrt(dx * dx + dz * dz);
        float yaw = flat < 0.05 ? 180f : (float) Math.toDegrees(Math.atan2(-dx, dz));
        float pitch = (float) -Math.toDegrees(Math.atan2(dy, Math.max(flat, 0.05)));
        return new Location(hub, pos[0], pos[1], pos[2], yaw, Math.max(-89, Math.min(89, pitch)));
    }

    private static double lerpAngle(double a, double b, double t) {
        double d = ((b - a + 540) % 360) - 180;
        return a + d * t;
    }

    /** After the hyperjump: start the spiral descent from high above, fading in from white. */
    void arrivalAfterJump(Player p, GameMode mode) {
        rideArrival(p, mode == GameMode.CREATIVE ? GameMode.CREATIVE : GameMode.ADVENTURE);
    }

    // ------------------------------------------------------ ride-along arrival

    /** The eye path: out of the core, one and a half slow turns around it, then a spiral down to the spawn. */
    private static final double[][] RIDE = {
            {0.5, CORE_Y, 0.5},
            {2.6, CORE_Y - 0.4, 4.4},
            {7.0, CORE_Y - 1.0, 1.2},
            {5.2, CORE_Y - 1.6, -5.0},
            {-0.8, CORE_Y - 2.2, -7.0},
            {-6.4, CORE_Y - 3.0, -3.2},
            {-6.2, CORE_Y - 3.8, 3.4},
            {-0.6, CORE_Y - 4.6, 7.0},
            {5.4, CORE_Y - 5.6, 3.6},
            {5.2, CORE_Y - 7.0, -2.4},
            {-0.6, FLOOR_Y + 5.6, -3.8},
            {-3.8, FLOOR_Y + 4.6, 0.6},
            {-2.6, FLOOR_Y + 3.8, 4.8},
            {0.5, FLOOR_Y + 3.1, 4.6},
            {0.5, FLOOR_Y + 1 + 1.62, 0.5}
    };
    static final int RIDE_TICKS = 170;            // 8.5 seconds
    private final java.util.Set<UUID> ejecting = new java.util.HashSet<>();

    private static double[] ridePos(double u) {
        int n = RIDE.length;
        double f = Math.max(0, Math.min(1, u)) * (n - 1);
        int i = Math.min(n - 2, (int) Math.floor(f));
        double t = f - i, t2 = t * t, t3 = t2 * t;
        double[] p0 = RIDE[Math.max(0, i - 1)], p1 = RIDE[i], p2 = RIDE[i + 1], p3 = RIDE[Math.min(n - 1, i + 2)];
        double[] out = new double[3];
        for (int k = 0; k < 3; k++) {
            out[k] = 0.5 * ((2 * p1[k]) + (-p0[k] + p2[k]) * t
                    + (2 * p0[k] - 5 * p1[k] + 4 * p2[k] - p3[k]) * t2
                    + (-p0[k] + 3 * p1[k] - 3 * p2[k] + p3[k]) * t3);
        }
        return out;
    }

    /**
     * The player rides an invisible platform along the path. The client smooths the platform's
     * movement, so the flight is fluid; no camera has to attach and the game mode never changes,
     * so the landing has no jump. The player may look around freely.
     */
    private void rideArrival(Player p, GameMode mode) {
        UUID id = p.getUniqueId();
        cancelArrival(p, false);
        arrivalModes.put(id, mode);
        p.setGameMode(mode);
        final double[] dy = {-0.6};                      // feet relative to the platform while riding (measured)
        double[] e0 = ridePos(0);
        Location seat = new Location(hub, e0[0], e0[1] - 1.62 - dy[0], e0[2]);
        org.bukkit.entity.ItemDisplay platform = hub.spawn(seat, org.bukkit.entity.ItemDisplay.class, d -> {
            prepare(d);
            d.setTeleportDuration(2);
        });
        cameras.put(id, platform);
        Location start = seat.clone().add(0, dy[0], 0);
        start.setYaw(160f);
        start.setPitch(20f);
        p.teleport(start);
        platform.addPassenger(p);
        Location eye0 = new Location(hub, e0[0], e0[1], e0[2]);
        arrivalPanels.put(id, Hyperjump.whitePanel(this, p, eye0.clone().add(p.getEyeLocation().getDirection().multiply(0.35)), 255));
        arrivalCubes.put(id, Hyperjump.flashCube(this, p, eye0, Material.WHITE_CONCRETE));
        p.showTitle(Title.title(
                Component.text("◈ МАТЕРИАЛИЗАЦИЯ ◈", TextColor.color(ORANGE)).decorate(TextDecoration.BOLD),
                Component.text("Главная палуба корабля NEYRO-1", NamedTextColor.GRAY),
                Title.Times.times(Duration.ofMillis(500), Duration.ofMillis(2500), Duration.ofMillis(700))));
        p.playSound(Sound.sound(Key.key("minecraft", "block.beacon.power_select"), Sound.Source.MASTER, 0.8f, 0.7f));

        Particle.DustOptions trail = new Particle.DustOptions(Color.fromRGB(255, 140, 0), 1.5f);
        org.bukkit.scheduler.BukkitTask task = Bukkit.getScheduler().runTaskTimer(this, new Runnable() {
            int t = 0, landed = 0;
            boolean measured = false;
            @Override public void run() {
                if (!p.isOnline() || !platform.isValid()) { cancelArrival(p, true); return; }
                t++;
                if (!platform.getPassengers().contains(p)) platform.addPassenger(p);
                if (!measured && t == 3 && platform.getPassengers().contains(p)) {
                    dy[0] = p.getLocation().getY() - platform.getLocation().getY();
                    measured = true;
                }
                double s = Math.min(1.0, t / (double) RIDE_TICKS);
                double[] e = ridePos(ease(s));
                Location eye = new Location(hub, e[0], e[1], e[2]);
                platform.teleport(new Location(hub, e[0], e[1] - 1.62 - dy[0], e[2]),
                        io.papermc.paper.entity.TeleportFlag.EntityState.RETAIN_PASSENGERS);

                // light of the jump: white → orange glow of the core → the deck
                org.bukkit.entity.BlockDisplay cube = arrivalCubes.get(id);
                if (cube != null && cube.isValid()) {
                    cube.teleport(eye);
                    if (t == 8) cube.setBlock(Material.ORANGE_STAINED_GLASS.createBlockData());
                    if (t >= 22) { cube.remove(); arrivalCubes.remove(id); }
                }
                org.bukkit.entity.TextDisplay panel = arrivalPanels.get(id);
                if (panel != null && panel.isValid()) {
                    double f = Math.min(1.0, t / 12.0);
                    int alpha = (int) (255 * Math.pow(1 - f, 2));
                    panel.teleport(eye.clone().add(p.getEyeLocation().getDirection().multiply(0.35)).add(0, -Hyperjump.PANEL_HALF, 0));
                    panel.setBackgroundColor(Color.fromARGB(alpha, 255, 255, 255));
                    if (alpha <= 0) { panel.remove(); arrivalPanels.remove(id); }
                }
                if (t == 22) {
                    hub.spawnParticle(Particle.END_ROD, new Location(hub, 0.5, CORE_Y, 0.5), 60, 1.5, 1.5, 1.5, 0.12);
                    p.playSound(Sound.sound(Key.key("minecraft", "block.amethyst_block.resonate"), Sound.Source.MASTER, 1f, 0.9f));
                }
                // glowing trail and soft chimes
                if (s < 1) {
                    double[] b = ridePos(ease(Math.max(0, s - 0.02)));
                    hub.spawnParticle(Particle.DUST, new Location(hub, b[0], b[1] - 0.3, b[2]), 3, 0.15, 0.15, 0.15, 0, trail);
                    if (t % 18 == 0) p.playSound(Sound.sound(Key.key("minecraft", "block.amethyst_block.chime"),
                            Sound.Source.MASTER, 0.6f, (float) (0.7 + s)));
                    int pct = (int) Math.round(ease(s) * 100);
                    TextComponent.Builder bar = Component.text().append(Component.text("Синхронизация с палубой  ", NamedTextColor.GRAY));
                    for (int i = 0; i < 10; i++)
                        bar.append(Component.text("▰", i < pct / 10 ? TextColor.color(ORANGE) : TextColor.color(0x3C3C3C)));
                    bar.append(Component.text("  " + pct + "%", NamedTextColor.WHITE))
                            .append(Component.text("   [Shift — пропустить]", NamedTextColor.DARK_GRAY));
                    p.sendActionBar(bar.build());
                    return;
                }
                // touchdown: effects, a short rest, then step off the platform
                if (landed == 0) { landed = t; landingEffects(p); }
                if (t - landed >= 12) {
                    Location look = p.getLocation();
                    finishArrival(p, false);
                    Location sp = spawn();
                    sp.setYaw(look.getYaw());
                    sp.setPitch(look.getPitch());
                    p.teleport(sp);
                    if (music != null) music.hold(p, 30L + 320L);   // silence until the AI has spoken
                    Bukkit.getScheduler().runTaskLater(NeyroHub.this, () -> aiVoice(p), 30L);
                }
            }
        }, 1L, 1L);
        arrivals.put(id, task);
    }

    @EventHandler(priority = EventPriority.LOWEST)
    public void onRideDismount(org.bukkit.event.entity.EntityDismountEvent e) {
        if (!(e.getEntity() instanceof Player p)) return;
        Entity cam = cameras.get(p.getUniqueId());
        if (cam != null && cam.equals(e.getDismounted()) && arrivals.containsKey(p.getUniqueId())) e.setCancelled(true);
    }

    // ------------------------------------------------------------- ship's AI

    /** Samantha, the server core, greets the player: her voice (resource pack) and a typed hologram at the core. */
    void aiVoice(Player p) {
        if (!p.isOnline() || !inHub(p)) return;
        String[] lines = {
                "Добро пожаловать на сервер NeyroCraft, " + p.getName() + ".",
                "Я — Ядро Сервера, Саманта, ваш проводник на сервере.",
                "Не читерите: мои помощники наблюдают за вами."};
        org.bukkit.entity.TextDisplay holo = hub.spawn(new Location(hub, 0.5, CORE_Y - 4.2, 0.5), org.bukkit.entity.TextDisplay.class, d -> {
            prepare(d);
            d.setVisibleByDefault(false);
            d.setBillboard(Display.Billboard.CENTER);
            d.setBackgroundColor(Color.fromARGB(150, 10, 6, 2));
            d.setLineWidth(420);
            d.setShadowed(true);
            d.setTransformation(new Transformation(new Vector3f(), new AxisAngle4f(), new Vector3f(1.6f, 1.6f, 1.6f), new AxisAngle4f()));
        });
        p.showEntity(this, holo);
        String full = String.join("\n", lines);
        int total = full.length();
        hub.spawnParticle(Particle.END_ROD, new Location(hub, 0.5, CORE_Y, 0.5), 40, 1.2, 1.2, 1.2, 0.05);
        p.playSound(Sound.sound(Key.key("minecraft", "block.beacon.activate"), Sound.Source.MASTER, 0.6f, 1.6f));
        // Samantha's real voice (resource pack); the typing follows the length of what she says
        final long voiceTicks = voice != null ? voice.playWelcome(p) : -1;
        final boolean spoken = voiceTicks > 0;
        final long step = spoken ? Math.max(2L, Math.round((double) voiceTicks / total)) : 2L;
        // calm music begins once she has finished
        if (music != null) music.greeted(p, Math.max(spoken ? voiceTicks : 0L, total * step) + 60L);
        for (int i = 0; i <= total; i++) {
            final int n = i;
            Bukkit.getScheduler().runTaskLater(this, () -> {
                if (!p.isOnline() || !holo.isValid()) return;
                String shown = full.substring(0, n);
                TextComponent.Builder b = Component.text()
                        .append(Component.text("◈ САМАНТА · ЯДРО СЕРВЕРА ◈", TextColor.color(ORANGE)).decorate(TextDecoration.BOLD))
                        .append(Component.newline());
                String[] parts = shown.split("\n", -1);
                for (int k = 0; k < parts.length; k++) {
                    if (k > 0) b.append(Component.newline());
                    b.append(Component.text(parts[k], k == 2 ? TextColor.color(0xFF9A6A) : TextColor.color(0xFFE6C8)));
                }
                if (n < total) b.append(Component.text("▌", NamedTextColor.WHITE));
                holo.text(b.build());
                char c = n > 0 ? full.charAt(n - 1) : ' ';
                if (!spoken && n % 2 == 0 && Character.isLetter(c))
                    p.playSound(Sound.sound(Key.key("minecraft", "entity.allay.ambient_without_item"), Sound.Source.VOICE,
                            0.35f, 1.25f + (float) Math.sin(n * 0.7) * 0.12f));
            }, i * step);
        }
        Bukkit.getScheduler().runTaskLater(this, () -> {
            if (!p.isOnline()) return;
            for (String l : lines) p.sendMessage(Component.text()
                    .append(Component.text("◈ Саманта: ", TextColor.color(ORANGE)))
                    .append(Component.text(l, TextColor.color(0xFFE6C8))).build());
        }, 4L);
        Bukkit.getScheduler().runTaskLater(this, () -> { if (holo.isValid()) holo.remove(); }, total * step + 100L);
    }

    private final Map<UUID, org.bukkit.entity.TextDisplay> arrivalPanels = new HashMap<>();
    private final Map<UUID, org.bukkit.entity.BlockDisplay> arrivalCubes = new HashMap<>();

    private void playArrival(Player p, boolean fromJump) {
        UUID id = p.getUniqueId();
        cancelArrival(p, false);
        arrivalModes.put(id, p.getGameMode() == GameMode.CREATIVE ? GameMode.CREATIVE : GameMode.ADVENTURE);
        p.setGameMode(GameMode.SPECTATOR);
        if (fromJump) {
            Location c0 = arrivalPoint(0);
            arrivalPanels.put(id, Hyperjump.whitePanel(this, p, c0.clone().add(c0.getDirection().multiply(0.35)), 255));
            arrivalCubes.put(id, Hyperjump.flashCube(this, p, c0, Material.WHITE_CONCRETE));
        } else {
            p.addPotionEffect(new PotionEffect(PotionEffectType.BLINDNESS, 14, 0, false, false, false));
        }
        p.showTitle(Title.title(
                Component.text("◈ МАТЕРИАЛИЗАЦИЯ ◈", TextColor.color(ORANGE)).decorate(TextDecoration.BOLD),
                Component.text("Главная палуба корабля NEYRO-1", NamedTextColor.GRAY),
                Title.Times.times(Duration.ofMillis(400), Duration.ofMillis(2200), Duration.ofMillis(400))));
        p.playSound(Sound.sound(Key.key("minecraft", "block.beacon.power_select"), Sound.Source.MASTER, 0.8f, 0.6f));
        p.playSound(Sound.sound(Key.key("minecraft", "block.portal.travel"), Sound.Source.MASTER, 0.15f, 1.8f));

        // The camera rides an invisible display entity. The client interpolates display
        // movement between updates, so the flight stays smooth even with network jitter.
        org.bukkit.entity.ItemDisplay camera = hub.spawn(arrivalPoint(0), org.bukkit.entity.ItemDisplay.class, d -> {
            prepare(d);
            d.setTeleportDuration(3);
        });
        cameras.put(id, camera);

        Particle.DustOptions trail = new Particle.DustOptions(Color.fromRGB(255, 140, 0), 1.7f);
        Particle.DustOptions spark = new Particle.DustOptions(Color.fromRGB(255, 225, 150), 1.0f);
        final boolean[] passedCore = {false};
        org.bukkit.scheduler.BukkitTask task = Bukkit.getScheduler().runTaskTimer(this, new Runnable() {
            int t = 0;
            int landed = 0;
            @Override public void run() {
                if (!p.isOnline() || !inHub(p) || !camera.isValid()) { cancelArrival(p, true); return; }
                t++;
                // attach the view once the client knows about the camera entity
                // attach the view only once the client surely knows the camera entity, and attach
                // a second time while the screen is still white in case the first one was dropped
                if (t == 2) p.setSpectatorTarget(camera);
                if (t == 6 || t == 10 || t == 14) { p.setSpectatorTarget(null); p.setSpectatorTarget(camera); }
                if (t > 14 && t % 20 == 0 && !camera.equals(p.getSpectatorTarget())) p.setSpectatorTarget(camera);
                double s = t / (double) ARRIVAL_TICKS;
                if (s >= 1) {
                    // touchdown: effects now, then rest a moment so the camera settles before control returns
                    camera.teleport(arrivalPoint(1));
                    if (landed == 0) { landed = t; landingEffects(p); }
                    if (t - landed >= 14) finishArrival(p, false);
                    return;
                }
                Location l = arrivalPoint(s);
                camera.teleport(l);
                if (t < 10) p.teleport(l.clone().subtract(0, 1.62, 0));   // until attached: own eyes on the path
                // the light of the hyperjump exit dissolves: full-screen white → translucent → soft fade
                org.bukkit.entity.BlockDisplay cube = arrivalCubes.get(id);
                if (cube != null && cube.isValid() && t <= 19) {
                    cube.teleport(l);
                    // the white of the jump: solid, then translucent, then gone (re-attaching happens under it)
                    if (t == 14) cube.setBlock(Material.WHITE_STAINED_GLASS.createBlockData());
                    if (t >= 19) { cube.remove(); arrivalCubes.remove(id); }
                } else {
                    // orange glow while the camera dives through the reactor core
                    double cd = l.toVector().distance(new org.bukkit.util.Vector(0.5, CORE_Y, 0.5));
                    if (cd < 2.3 && !arrivalCubes.containsKey(id)) {
                        arrivalCubes.put(id, Hyperjump.flashCube(NeyroHub.this, p, l, Material.ORANGE_STAINED_GLASS));
                    }
                }
                org.bukkit.entity.BlockDisplay glow = arrivalCubes.get(id);
                if (glow != null && glow.isValid() && t > 19) {
                    glow.teleport(l);
                    double cd = l.toVector().distance(new org.bukkit.util.Vector(0.5, CORE_Y, 0.5));
                    if (cd > 2.6) { glow.remove(); arrivalCubes.remove(id); }
                }
                org.bukkit.entity.TextDisplay panel = arrivalPanels.get(id);
                if (panel != null && panel.isValid()) {
                    double f = Math.min(1.0, Math.max(0, t - 12) / 16.0);
                    int alpha = (int) (255 * Math.pow(1 - f, 2));
                    panel.teleport(l.clone().add(l.getDirection().multiply(0.35)).add(0, -Hyperjump.PANEL_HALF, 0));
                    panel.setBackgroundColor(Color.fromARGB(alpha, 255, 255, 255));
                    if (alpha <= 0) { panel.remove(); arrivalPanels.remove(id); }
                }

                // glowing trail behind the camera, seen by everyone in the hub
                Location behind = arrivalPoint(Math.max(0, s - 0.03));
                hub.spawnParticle(Particle.DUST, behind, 6, 0.25, 0.25, 0.25, 0, trail);
                hub.spawnParticle(Particle.DUST, behind, 2, 0.4, 0.4, 0.4, 0, spark);
                if (t % 2 == 0) hub.spawnParticle(Particle.END_ROD, behind, 1, 0.1, 0.1, 0.1, 0.01);

                // progress bar
                int pct = (int) Math.round(ease(s) * 100);
                int filled = pct / 10;
                TextComponent.Builder bar = Component.text()
                        .append(Component.text("Загрузка  ", NamedTextColor.GRAY));
                for (int i = 0; i < 10; i++)
                    bar.append(Component.text("▰", i < filled ? TextColor.color(ORANGE) : TextColor.color(0x3C3C3C)));
                bar.append(Component.text("  " + pct + "%", NamedTextColor.WHITE))
                        .append(Component.text("   [Shift — пропустить]", NamedTextColor.DARK_GRAY));
                p.sendActionBar(bar.build());

                // flash while diving through the core
                if (!passedCore[0] && l.getY() < CORE_Y + 1) {
                    passedCore[0] = true;
                    Location c = new Location(hub, 0.5, CORE_Y, 0.5);
                    hub.spawnParticle(Particle.END_ROD, c, 60, 1.5, 1.5, 1.5, 0.15);
                    hub.spawnParticle(Particle.DUST, c, 80, 2.5, 2.5, 2.5, 0, trail);
                    p.playSound(Sound.sound(Key.key("minecraft", "block.amethyst_block.resonate"), Sound.Source.MASTER, 1f, 0.8f));
                    p.playSound(Sound.sound(Key.key("minecraft", "block.beacon.ambient"), Sound.Source.MASTER, 1f, 1.6f));
                }
                if (t % 10 == 0) {
                    p.playSound(Sound.sound(Key.key("minecraft", "block.amethyst_block.chime"), Sound.Source.MASTER,
                            0.7f, (float) (0.6 + s * 1.2)));
                }
            }
        }, 1L, 1L);
        arrivals.put(id, task);
    }

    private void finishArrival(Player p) {
        boolean wasArriving = arrivals.containsKey(p.getUniqueId());
        finishArrival(p, true);
        // skipped with Shift: the AI still greets, and the music still waits for it
        if (wasArriving) Bukkit.getScheduler().runTaskLater(this, () -> aiVoice(p), 30L);
    }

    private void finishArrival(Player p, boolean effects) {
        UUID id = p.getUniqueId();
        org.bukkit.scheduler.BukkitTask task = arrivals.remove(id);
        if (task != null) task.cancel();
        GameMode mode = arrivalModes.getOrDefault(id, GameMode.ADVENTURE);
        arrivalModes.remove(id);
        Entity camera = cameras.remove(id);
        org.bukkit.entity.TextDisplay whitePanel = arrivalPanels.remove(id);
        if (whitePanel != null && whitePanel.isValid()) whitePanel.remove();
        org.bukkit.entity.BlockDisplay flashCube = arrivalCubes.remove(id);
        if (flashCube != null && flashCube.isValid()) flashCube.remove();
        if (p.isOnline() && p.getGameMode() == GameMode.SPECTATOR) p.setSpectatorTarget(null);
        if (camera != null) camera.remove();
        if (!p.isOnline()) return;
        p.teleport(spawn());
        p.setGameMode(mode);
        p.setFallDistance(0);
        p.sendActionBar(Component.empty());
        if (effects) landingEffects(p);
    }

    /** Title, sounds, burst and the shockwave of a landing on the deck. */
    private void landingEffects(Player p) {
        p.showTitle(Title.title(
                serverName(),
                Component.text()
                        .append(Component.text("Добро пожаловать, ", NamedTextColor.GRAY))
                        .append(Component.text(p.getName(), TextColor.color(ORANGE)).decorate(TextDecoration.BOLD))
                        .append(Component.text("!", NamedTextColor.GRAY))
                        .build(),
                Title.Times.times(Duration.ofMillis(300), Duration.ofMillis(2500), Duration.ofMillis(900))));
        p.playSound(Sound.sound(Key.key("minecraft", "block.respawn_anchor.set_spawn"), Sound.Source.MASTER, 0.9f, 1.2f));
        p.playSound(Sound.sound(Key.key("minecraft", "entity.player.levelup"), Sound.Source.MASTER, 0.6f, 1.4f));
        p.playSound(Sound.sound(Key.key("minecraft", "item.trident.thunder"), Sound.Source.MASTER, 0.25f, 1.6f));

        // landing burst and an expanding shockwave across the floor
        Location centre = spawn();
        hub.spawnParticle(Particle.TOTEM_OF_UNDYING, centre.clone().add(0, 1, 0), 60, 0.4, 0.8, 0.4, 0.4);
        hub.spawnParticle(Particle.END_ROD, centre.clone().add(0, 0.2, 0), 40, 0.3, 0.1, 0.3, 0.2);
        Particle.DustOptions wave = new Particle.DustOptions(Color.fromRGB(255, 150, 20), 1.6f);
        Particle.DustOptions waveLight = new Particle.DustOptions(Color.fromRGB(255, 235, 180), 1.0f);
        for (int step = 0; step < 12; step++) {
            final double radius = 1 + step * 0.9;
            Bukkit.getScheduler().runTaskLater(this, () -> {
                int points = (int) (radius * 10);
                for (int i = 0; i < points; i++) {
                    double a = i * Math.PI * 2 / points;
                    Location l = new Location(hub, 0.5 + Math.cos(a) * radius, FLOOR_Y + 1.1, 0.5 + Math.sin(a) * radius);
                    hub.spawnParticle(Particle.DUST, l, 1, 0, 0, 0, 0, wave);
                    if (i % 3 == 0) hub.spawnParticle(Particle.DUST, l.add(0, 0.25, 0), 1, 0, 0, 0, 0, waveLight);
                }
            }, step);
        }

        // let everyone else in the hub know
        Component note = Component.text()
                .append(Component.text("✦ ", TextColor.color(ORANGE)))
                .append(Component.text(p.getName(), TextColor.color(ORANGE)))
                .append(Component.text(" прибыл в хаб", NamedTextColor.GRAY))
                .build();
        for (Player other : hub.getPlayers()) if (!other.equals(p)) other.sendActionBar(note);
    }

    /** Stops a running arrival; when {@code restore} is set the player is put back on the spawn point. */
    private void cancelArrival(Player p, boolean restore) {
        UUID id = p.getUniqueId();
        org.bukkit.scheduler.BukkitTask task = arrivals.remove(id);
        if (task != null) task.cancel();
        GameMode mode = arrivalModes.remove(id);
        Entity camera = cameras.remove(id);
        org.bukkit.entity.TextDisplay whitePanel = arrivalPanels.remove(id);
        if (whitePanel != null && whitePanel.isValid()) whitePanel.remove();
        org.bukkit.entity.BlockDisplay flashCube = arrivalCubes.remove(id);
        if (flashCube != null && flashCube.isValid()) flashCube.remove();
        if (task != null && p.getGameMode() == GameMode.SPECTATOR) p.setSpectatorTarget(null);
        if (camera != null) camera.remove();
        if (restore && task != null) {
            p.setGameMode(mode != null ? mode : GameMode.ADVENTURE);
            p.teleport(spawn());
        }
    }

    // ----------------------------------------------------------------- Ami & station Orion

    private Ami mainAmi;
    private OrionHub orion;

    private void spawnExtras() {
        if (mainAmi != null) mainAmi.remove();
        mainAmi = new Ami(this, new Location(hub, -5.5, FLOOR_Y + 1, -4.5), Ami.Station.MAIN, p -> { if (orion != null) orion.send(p); });
        mainAmi.spawn();
        if (orion == null) {
            orion = new OrionHub(this, hub, FLOOR_Y);
            getServer().getPluginManager().registerEvents(orion, this);
            orion.start();
        }
        if (starfight == null) {
            starfight = new StarFight(this, hub);
            getServer().getPluginManager().registerEvents(starfight, this);
            starfight.start();
        }
        if (cinema == null) {
            cinema = new Cinema(this, hub, FLOOR_Y);
            getServer().getPluginManager().registerEvents(cinema, this);
            cinema.start();
        }
        if (sabers == null) {
            sabers = new SaberArena(this, hub, FLOOR_Y);
            getServer().getPluginManager().registerEvents(sabers, this);
            sabers.start();
        }
    }

    private SaberArena sabers;
    private Cinema cinema;
    private StarFight starfight;

    boolean starfightInGame(Player p) { return starfight != null && starfight.inGame(p); }

    void enterStarfight(Player p) {
        if (starfight == null || starfight.inGame(p)) return;
        if (laser != null && laser.inGame(p)) laser.leave(p, true);
        if (sabers != null && sabers.inGame(p)) sabers.leave(p);
        if (race != null && race.in(p)) race.quit(p);
        starfight.send(p);
    }

    boolean inCinema(Player p) { return cinema != null && cinema.contains(p.getLocation()); }

    void enterCinema(Player p) {
        if (cinema == null) return;
        if (laser != null && laser.inGame(p)) laser.leave(p, true);
        if (sabers != null && sabers.inGame(p)) sabers.leave(p);
        if (race != null && race.in(p)) race.quit(p);
        cinema.send(p);
    }

    void enterSabers(Player p) {
        if (sabers == null || sabers.inGame(p)) return;
        if (laser != null && laser.inGame(p)) laser.leave(p, true);
        p.addPotionEffect(new PotionEffect(PotionEffectType.BLINDNESS, 25, 0, false, false, false));
        p.playSound(Sound.sound(Key.key("minecraft", "block.beacon.power_select"), Sound.Source.MASTER, 0.8f, 1.6f));
        Bukkit.getScheduler().runTaskLater(this, () -> { if (p.isOnline()) sabers.join(p); }, 8L);
    }

    /** Two-line space-styled server card for the multiplayer list. */
    @EventHandler
    public void onPing(org.bukkit.event.server.ServerListPingEvent e) {
        Component first = Component.text()
                .append(Component.text("✦ ", TextColor.color(0x8B5CFF)))
                .append(serverName())
                .append(listDivider())
                .append(Component.text("Выживание", TextColor.color(SURVIVAL_GREEN)))
                .append(listDivider())
                .append(Component.text("Анархия", TextColor.color(ANARCHY_RED)).decorate(TextDecoration.BOLD))
                .build();
        Component second = Component.text()
                .append(Component.text("Мини-игры", TextColor.color(MINIGAME_VIOLET)))
                .append(listDivider())
                .append(Component.text("Star Wars", TextColor.color(STAR_WARS_GOLD)))
                .append(listDivider())
                .append(Component.text("1.21.11–26.3", TextColor.color(CRAFT_BLUE)))
                .build();
        e.motd(first.append(Component.newline()).append(second));
    }

    private static Component listDivider() {
        return Component.text(" · ", TextColor.color(0x66758A));
    }

    void enterRace(Player p) {
        if (race == null) { p.sendMessage(Component.text("Гонки сейчас недоступны", NamedTextColor.RED)); return; }
        if (race.in(p)) return;
        if (laser != null && laser.inGame(p)) laser.leave(p, true);
        if (sabers != null && sabers.inGame(p)) sabers.leave(p);
        race.send(p);
    }

    /** From station Orion back to the main deck, with the light-beam animation. */
    void beamToMain(Player p) {
        if (BeamTransfer.busy(p)) return;
        BeamTransfer.run(this, p, spawn(), Color.fromRGB(0xFF, 0x8C, 0x00), Color.fromRGB(0xFF, 0xE6, 0xC8),
                BeamTransfer.title(serverName(), Component.text("главная палуба корабля", NamedTextColor.GRAY)));
    }

    /** Portals of either hub lead here. */
    void enterPortal(Player p, String id) {
        switch (id) {
            case "skyblock" -> sendToSkyblock(p);
            case "lasertag" -> sendToLaserTag(p);
            case "game3" -> { if (darkAnarchy != null) darkAnarchy.visit(p); }
            case "sabers" -> enterSabers(p);
            case "race" -> enterRace(p);
            case "cinema" -> enterCinema(p);
            case "starfight" -> enterStarfight(p);
            default -> sendToSurvival(p);
        }
    }

    private Ami amiOf(Entity e) {
        if (mainAmi != null && mainAmi.isHitbox(e)) return mainAmi;
        if (orion != null && orion.ami() != null && orion.ami().isHitbox(e)) return orion.ami();
        return null;
    }

    @EventHandler(priority = EventPriority.HIGH)
    public void onTalkToAmi(org.bukkit.event.player.PlayerInteractEntityEvent e) {
        if (e.getHand() != org.bukkit.inventory.EquipmentSlot.HAND) return;
        if (e.getRightClicked().equals(infoBoardButton)) {
            e.setCancelled(true);
            openHubInfo(e.getPlayer());
            return;
        }
        if (dialogue != null && dialogue.talking(e.getPlayer())) return;     // the wheel handles it (Dialogue)
        if (starPass != null && starPass.isTerminal(e.getRightClicked())) {
            e.setCancelled(true);
            starPass.openMenu(e.getPlayer(), 0);
            return;
        }
        Ami ami = amiOf(e.getRightClicked());
        if (ami == null) return;
        e.setCancelled(true);
        ami.interact(e.getPlayer());
    }

    @EventHandler
    public void lockArrivalMovement(PlayerMoveEvent e) {
        Player p = e.getPlayer();
        if (!arrivals.containsKey(p.getUniqueId()) || e.getTo() == null) return;
        // Spectator clients can drift down when Shift stops spectating. Keep the camera
        // sequence on its rail; rotation remains free so the player can look around.
        if (e.getTo().getY() < e.getFrom().getY() - 0.35 || e.getTo().getY() < FLOOR_Y - 8) {
            e.setTo(e.getFrom());
            p.setFallDistance(0);
        }
    }

    @EventHandler
    public void onSneak(org.bukkit.event.player.PlayerToggleSneakEvent e) {
        if (e.isSneaking() && arrivals.containsKey(e.getPlayer().getUniqueId())) finishArrival(e.getPlayer());
        if (e.isSneaking() && animus != null && animus.isLoading(e.getPlayer())) animus.skip(e.getPlayer());
    }

    /** Shift while riding the camera makes the client stop spectating: treat it as "skip". */
    @EventHandler
    public void onStopSpectating(com.destroystokyo.paper.event.player.PlayerStopSpectatingEntityEvent e) {
        Player p = e.getPlayer();
        if (animus != null && animus.isLoading(p)) {            // Shift during the deck loading = skip
            Bukkit.getScheduler().runTask(this, () -> { if (animus.isLoading(p)) animus.skip(p); });
            return;
        }
        if (arrivals.containsKey(p.getUniqueId()) && e.getSpectatorTarget().equals(cameras.get(p.getUniqueId()))) {
            Bukkit.getScheduler().runTask(this, () -> {
                if (arrivals.containsKey(p.getUniqueId())) finishArrival(p);
            });
        }
    }

    private void sendToSurvival(Player p) {
        Location target = loadSurvivalLocation(p);
        // going down into the city: slide down the beam of light from the ship
        if (city != null && city.isCityTarget(target) && inHub(p)) {
            city.dropIntoCity(p);
            return;
        }
        // anywhere else in survival: a beam of light comes down on that place and brings the player there
        if (beamDrop != null && inHub(p) && target.getWorld() != null) {
            BeamDrop.Style style = new BeamDrop.Style(ORANGE, 0x40E8FF,
                    Component.text("ВЫЖИВАНИЕ", TextColor.color(ORANGE)).decorate(TextDecoration.BOLD),
                    Component.text("спуск по лучу с корабля NEYRO-1", NamedTextColor.GRAY),
                    Component.text("ВЫЖИВАНИЕ", TextColor.color(ORANGE)).decorate(TextDecoration.BOLD),
                    Component.text("Удачи, " + p.getName() + "!", NamedTextColor.GRAY));
            target.getWorld().getChunkAtAsync(target).thenAccept(c -> Bukkit.getScheduler().runTask(this, () -> {
                if (!p.isOnline() || !inHub(p) || beamDrop.busy(p)) return;
                double top = BeamDrop.clearTop(target, 45);
                int ticks = (int) Math.max(50, Math.min(110, 40 + (top - target.getY()) * 1.6));
                beamDrop.run(p, target, top, ticks, style, true,
                        pl -> { if (pl.getGameMode() == GameMode.ADVENTURE) pl.setGameMode(GameMode.SURVIVAL); });
            }));
            return;
        }
        p.addPotionEffect(new PotionEffect(PotionEffectType.BLINDNESS, 25, 0, false, false, false));
        p.playSound(Sound.sound(Key.key("minecraft", "entity.enderman.teleport"), Sound.Source.MASTER, 0.8f, 0.8f));
        p.showTitle(Title.title(
                Component.text("ВЫЖИВАНИЕ", TextColor.color(ORANGE)).decorate(TextDecoration.BOLD),
                Component.text("Удачи, " + p.getName() + "!", NamedTextColor.GRAY),
                Title.Times.times(Duration.ofMillis(200), Duration.ofMillis(1200), Duration.ofMillis(500))));
        Bukkit.getScheduler().runTaskLater(this, () -> p.teleportAsync(target).thenRun(() ->
                Bukkit.getScheduler().runTask(this, () -> {
                    if (p.getGameMode() == GameMode.ADVENTURE) p.setGameMode(GameMode.SURVIVAL);
                })), 8L);
    }

    private void sendToLaserTag(Player p) {
        if (laser == null) return;
        p.addPotionEffect(new PotionEffect(PotionEffectType.BLINDNESS, 20, 0, false, false, false));
        p.playSound(Sound.sound(Key.key("minecraft", "entity.enderman.teleport"), Sound.Source.MASTER, 0.8f, 1.4f));
        p.playSound(Sound.sound(Key.key("minecraft", "block.beacon.power_select"), Sound.Source.MASTER, 0.7f, 1.8f));
        Bukkit.getScheduler().runTaskLater(this, () -> { if (p.isOnline()) laser.join(p); }, 8L);
    }

    private void sendToSkyblock(Player p) {
        org.bukkit.plugin.Plugin bento = Bukkit.getPluginManager().getPlugin("BentoBox");
        if (bento == null || !bento.isEnabled()) {
            p.sendMessage(Component.text("Скайблок сейчас недоступен", NamedTextColor.RED));
            return;
        }
        // the island is known: find a safe spot there ourselves and come down on it by a beam
        Location home = SkyblockLink.home(bento, p, getLogger());
        if (home != null && beamDrop != null) {
            home.getWorld().getChunkAtAsync(home).thenAccept(c -> Bukkit.getScheduler().runTask(this, () -> {
                if (!p.isOnline()) return;
                Location spot = SkyblockLink.safe(home);
                if (spot == null) {
                    getLogger().warning("Skyblock: no safe spot on " + p.getName() + "'s island near " + home.getBlockX() + " "
                            + home.getBlockY() + " " + home.getBlockZ() + ", handing over to /island go");
                    p.performCommand("island go");
                    return;
                }
                getLogger().info("Skyblock: " + p.getName() + " -> island " + spot.getBlockX() + " " + spot.getBlockY() + " " + spot.getBlockZ());
                BeamDrop.Style style = new BeamDrop.Style(0x55FFFF, 0x7CFF4F,
                        Component.text("СКАЙБЛОК", TextColor.color(0x55FFFF)).decorate(TextDecoration.BOLD),
                        Component.text("летим на твой остров...", NamedTextColor.GRAY),
                        Component.text("СКАЙБЛОК", TextColor.color(0x55FFFF)).decorate(TextDecoration.BOLD),
                        Component.text("твой остров · /is — меню острова", NamedTextColor.GRAY));
                double top = BeamDrop.clearTop(spot, 40);
                beamDrop.run(p, spot, top, (int) Math.max(50, Math.min(100, 40 + (top - spot.getY()) * 1.5)), style, true,
                        pl -> { if (pl.getGameMode() == GameMode.ADVENTURE) pl.setGameMode(GameMode.SURVIVAL); });
            }));
            return;
        }
        getLogger().info("Skyblock: " + p.getName() + " has no island yet, running /island (island creation)");
        p.addPotionEffect(new PotionEffect(PotionEffectType.BLINDNESS, 25, 0, false, false, false));
        p.playSound(Sound.sound(Key.key("minecraft", "entity.player.levelup"), Sound.Source.MASTER, 0.7f, 1.6f));
        p.showTitle(Title.title(
                Component.text("СКАЙБЛОК", TextColor.color(0x55FFFF)).decorate(TextDecoration.BOLD),
                Component.text("Летим на твой остров...", NamedTextColor.GRAY),
                Title.Times.times(Duration.ofMillis(200), Duration.ofMillis(1500), Duration.ofMillis(500))));
        // BentoBox: /island teleports home, or creates a new island for newcomers
        Bukkit.getScheduler().runTaskLater(this, () -> {
            if (p.isOnline()) p.performCommand("island");
        }, 8L);
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onWorldChange(org.bukkit.event.player.PlayerChangedWorldEvent e) {
        Player p = e.getPlayer();
        // Run one tick later: InvSwitcher (BentoBox) saves and restores the game mode per world
        // in this same event. Changing it earlier made it store ADVENTURE for the skyblock world.
        Bukkit.getScheduler().runTask(this, () -> {
            if (!p.isOnline()) return;
            if (inHub(p) || darkAnarchy != null && darkAnarchy.inLobby(p.getWorld())) {
                if (p.getGameMode() == GameMode.SURVIVAL) p.setGameMode(GameMode.ADVENTURE);
            } else if (p.getGameMode() == GameMode.ADVENTURE || p.getGameMode() == GameMode.SPECTATOR && !p.isOp()) {
                p.setGameMode(GameMode.SURVIVAL);
            }
        });
    }

    private void bounceBack(Player p, Portal portal) {
        Vector toCentre = new Vector(0.5, FLOOR_Y + 1, 0.5).subtract(p.getLocation().toVector()).setY(0).normalize();
        p.setVelocity(toCentre.multiply(1.1).setY(0.45));
        p.playSound(Sound.sound(Key.key("minecraft", "block.note_block.bass"), Sound.Source.MASTER, 1f, 0.6f));
        p.showTitle(Title.title(
                Component.text(portal.title(), TextColor.color(portal.textColor())).decorate(TextDecoration.BOLD),
                Component.text("Этот режим скоро откроется!", NamedTextColor.GRAY),
                Title.Times.times(Duration.ofMillis(150), Duration.ofMillis(1500), Duration.ofMillis(400))));
    }

    private Portal portalAt(Location l) {
        Portal best = null;
        double bestD = Double.MAX_VALUE;
        for (Portal p : portals) {
            double dx = l.getX() - (p.cx() + 0.5), dz = l.getZ() - (p.cz() + 0.5);
            double d = dx * dx + dz * dz;
            if (d < bestD) { best = p; bestD = d; }
        }
        return bestD <= 64 ? best : null;
    }

    // ----------------------------------------------------------------- events

    private void hookAuthMeLogin() {
        if (Bukkit.getPluginManager().getPlugin("AuthMe") == null) return;
        boolean ok = onAuthEvent("fr.xephi.authme.events.LoginEvent", p ->
                Bukkit.getScheduler().runTaskLater(this, () -> sendToHub(p, true), 2L));
        if (!ok) {
            getLogger().warning("AuthMe LoginEvent not found, sending players to the hub on join");
            return;
        }
        authMePresent = true;
        try {
            authMeApi = Class.forName("fr.xephi.authme.api.v3.AuthMeApi").getMethod("getInstance").invoke(null);
        } catch (ReflectiveOperationException e) {
            getLogger().warning("AuthMe API not available: " + e);
        }
    }

    /** Listens to an AuthMe event by class name (no compile-time dependency) and passes on its player. */
    @SuppressWarnings("unchecked")
    private boolean onAuthEvent(String className, java.util.function.Consumer<Player> action) {
        try {
            Class<? extends Event> type = (Class<? extends Event>) Class.forName(className);
            Bukkit.getPluginManager().registerEvent(type, this, EventPriority.MONITOR, (listener, event) -> {
                if (!type.isInstance(event)) return;
                try {
                    Object player = event.getClass().getMethod("getPlayer").invoke(event);
                    if (player instanceof Player p) action.accept(p);
                } catch (ReflectiveOperationException e) {
                    getLogger().warning("Could not read " + type.getSimpleName() + ": " + e);
                }
            }, this);
            return true;
        } catch (ClassNotFoundException e) {
            return false;
        }
    }

    private boolean authCall(String method, Object arg, Class<?> argType) {
        if (authMeApi == null) return false;
        try {
            return (Boolean) authMeApi.getClass().getMethod(method, argType).invoke(authMeApi, arg);
        } catch (ReflectiveOperationException e) {
            return false;
        }
    }

    @EventHandler
    public void onJoin(PlayerJoinEvent e) {
        Player p = e.getPlayer();
        // left in spectator by an interrupted arrival (crash, restart): put them back on the floor
        if (inHub(p) && p.getGameMode() == GameMode.SPECTATOR && !p.isOp()) {
            p.setGameMode(GameMode.ADVENTURE);
            p.teleport(spawn());
        }
        // left a mini-game by disconnecting or a crash: give their own things back
        vault().recover(p, "lasertag", "sabers", "race", "starfight");
        if (voice != null) Bukkit.getScheduler().runTaskLater(this, () -> { if (p.isOnline()) voice.offer(p); }, 20L);
        if (simulation != null) {
            Bukkit.getScheduler().runTaskLater(this, () -> {
                if (!p.isOnline() || simulation.isAuthed(p)) return;
                saveSurvivalLocation(p);
                simulation.enter(p);
            }, 2L);
            return;
        }
        if (!authMePresent) Bukkit.getScheduler().runTaskLater(this, () -> sendToHub(p, true), 3L);
    }

    @EventHandler
    public void onQuit(PlayerQuitEvent e) {
        if (simulation != null) simulation.quit(e.getPlayer());
        if (animus != null) animus.stop(e.getPlayer(), false);
        if (laser != null) laser.leave(e.getPlayer(), true);
        cancelArrival(e.getPlayer(), true);
        if (voice != null) voice.forget(e.getPlayer());
        if (music != null) music.forget(e.getPlayer());
        saveSurvivalLocation(e.getPlayer());
        portalCooldown.remove(e.getPlayer().getUniqueId());
    }

    @EventHandler(ignoreCancelled = true)
    public void onMove(PlayerMoveEvent e) {
        Player p = e.getPlayer();
        if (animus != null && animus.isLoading(p)) {
            // frozen while the deck loads: looking around is fine, walking is not
            Location from = e.getFrom(), to = e.getTo();
            if (from.getX() != to.getX() || from.getY() != to.getY() || from.getZ() != to.getZ()) {
                Location back = from.clone();
                back.setYaw(to.getYaw());
                back.setPitch(to.getPitch());
                e.setTo(back);
            }
            return;
        }
        if (!inHub(p) || arrivals.containsKey(p.getUniqueId())) return;
        if (simulation != null && (simulation.inChamber(p) || !simulation.isAuthed(p))) return;
        Location to = e.getTo();
        if (orion != null && orion.contains(to)) return;            // station Orion handles its own portals and falls
        if (sabers != null && sabers.contains(to)) return;          // so does the energy-sword arena
        if (cinema != null && cinema.contains(to)) return;          // and the cinema
        if (starfight != null && starfight.contains(to)) return;    // and the space battle arena
        if (to.getY() < FLOOR_Y - 30) {
            p.teleport(spawn());
            p.setFallDistance(0);
            return;
        }
        if (e.getFrom().getBlockX() == to.getBlockX() && e.getFrom().getBlockY() == to.getBlockY()
                && e.getFrom().getBlockZ() == to.getBlockZ()) return;
        Material feet = to.getBlock().getType();
        Material head = to.clone().add(0, 1, 0).getBlock().getType();
        boolean anarchyGate = to.getBlockX() == -PORTAL_DIST && Math.abs(to.getBlockZ()) <= 2
                && to.getY() >= FLOOR_Y + 1 && to.getY() < FLOOR_Y + 9;
        if (!anarchyGate && feet != Material.NETHER_PORTAL && head != Material.NETHER_PORTAL) return;
        long now = System.currentTimeMillis();
        Long until = portalCooldown.get(p.getUniqueId());
        if (until != null && until > now) return;
        portalCooldown.put(p.getUniqueId(), now + 2000);
        Portal portal = portalAt(to);
        if (portal == null) return;
        if (!portal.open()) bounceBack(p, portal);
        else if (portal.id().equals("skyblock")) sendToSkyblock(p);
        else if (portal.id().equals("lasertag")) sendToLaserTag(p);
        else if (portal.id().equals("game3")) { if (darkAnarchy != null) darkAnarchy.visit(p); }
        else sendToSurvival(p);
    }

    @EventHandler
    public void onPortal(PlayerPortalEvent e) {
        if (e.getFrom().getWorld() != null && e.getFrom().getWorld().equals(hub)) e.setCancelled(true);
    }

    private boolean builder(Player p) {
        return p.isOp() && p.getGameMode() == GameMode.CREATIVE;
    }

    boolean isBuilder(Player p) {
        return builder(p);
    }

    boolean isHubWorld(World w) {
        return w != null && (w.equals(hub) || w.getName().equals("neyro_dead_realm"));
    }

    @EventHandler(ignoreCancelled = true)
    public void onBreak(BlockBreakEvent e) {
        if (inHub(e.getPlayer()) && !builder(e.getPlayer())) e.setCancelled(true);
    }

    @EventHandler(ignoreCancelled = true)
    public void onPlace(BlockPlaceEvent e) {
        if (inHub(e.getPlayer()) && !builder(e.getPlayer())) e.setCancelled(true);
    }

    @EventHandler(ignoreCancelled = true)
    public void onDamage(EntityDamageEvent e) {
        if (e.getEntity() instanceof Player p && inHub(p)) e.setCancelled(true);
    }

    @EventHandler(ignoreCancelled = true)
    public void onHunger(FoodLevelChangeEvent e) {
        if (e.getEntity() instanceof Player p && inHub(p)) e.setCancelled(true);
    }

    @EventHandler(ignoreCancelled = true)
    public void onDrop(PlayerDropItemEvent e) {
        if (inHub(e.getPlayer()) && !builder(e.getPlayer())) e.setCancelled(true);
    }

    @EventHandler(ignoreCancelled = true)
    public void onPickup(EntityPickupItemEvent e) {
        if (e.getEntity() instanceof Player p && inHub(p) && !builder(p)) e.setCancelled(true);
    }

    @EventHandler(priority = EventPriority.LOW)
    public void onInteract(PlayerInteractEvent e) {
        if (inHub(e.getPlayer()) && !builder(e.getPlayer()) && e.getItem() != null) e.setCancelled(true);
    }

    // --------------------------------------------------------------- commands

    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        if (simulation != null && simulation.command(sender, command.getName().toLowerCase(java.util.Locale.ROOT), args)) return true;
        String cmd = command.getName().toLowerCase(java.util.Locale.ROOT);
        if (cmd.equals("hubinfo")) {
            if (sender instanceof Player p) openHubInfo(p);
            else sender.sendMessage("/hubinfo доступна в игре.");
            return true;
        }
        if (cmd.equals("anarchydrop")) {
            if (sender instanceof Player p && !authed(p)) return true;
            boolean launched = anarchyDrop != null && anarchyDrop.launch();
            sender.sendMessage(Component.text(launched ? "Истребитель отправлен в анархию."
                    : "Дроп уже идёт или точка сброса недоступна.", NamedTextColor.RED));
            return true;
        }
        if (cmd.equals("classes")) return classes.command(sender, args);
        if (cmd.equals("evilpass")) {
            if (sender instanceof Player p) evilPass.open(p);
            else sender.sendMessage("Команда /evilpass доступна в игре.");
            return true;
        }
        if (cmd.equals("dark")) {
            if (!(sender instanceof Player p)) { sender.sendMessage("Only players can use /dark"); return true; }
            if (darkAnarchy == null || args.length == 0 || !darkAnarchy.command(p, args[0]))
                p.sendMessage(Component.text("/dark accept или /dark cancel", NamedTextColor.GRAY));
            return true;
        }
        if (cmd.equals("hub3")) {
            if (!(sender instanceof Player p)) { sender.sendMessage("Only players can use /hub3"); return true; }
            if (darkAnarchy != null) darkAnarchy.visit(p);
            return true;
        }
        if (cmd.equals("neyromail")) {
            if (mail == null) mail = new NeyroMail(this);
            return mail.command(sender, args);
        }
        if (cmd.equals("home") || cmd.equals("sethome") || cmd.equals("delhome") || cmd.equals("homes")) {
            if (!(sender instanceof Player p)) { sender.sendMessage("Only players can use home commands"); return true; }
            return homes.command(p, cmd, args);
        }
        if (cmd.equals("money") || cmd.equals("job")) {
            if (!(sender instanceof Player p)) { sender.sendMessage("Only players can use this command"); return true; }
            return money.command(p, cmd, args);
        }
        if (cmd.equals("casino")) {
            if (!(sender instanceof Player p)) { sender.sendMessage("Only players can use /casino"); return true; }
            return casino.command(p, args);
        }
        if (cmd.equals("house")) {
            if (!(sender instanceof Player p)) { sender.sendMessage("Only players can use /house"); return true; }
            if (housing() == null || city == null || !city.ready()) { sender.sendMessage("Neyro-City is not ready"); return true; }
            return housing().command(p, args);
        }
        if (command.getName().equalsIgnoreCase("hub")) {
            if (!(sender instanceof Player p)) {
                sender.sendMessage("Only players can use /hub");
                return true;
            }
            if (sabers != null && sabers.inGame(p)) {
                sabers.leave(p);
                sendToHub(p, false);
            } else if (race != null && race.in(p)) {
                race.quit(p);
                sendToHub(p, false);
            } else if (starfight != null && starfight.inGame(p)) {
                starfight.leave(p, true);
                sendToHub(p, false);
            } else if (laser != null && laser.inGame(p)) {
                sendToHub(p, false);          // leave the arena, straight back to the deck
            } else if (inHub(p)) {
                p.teleport(spawn());
            } else {
                sendToHub(p, true);
            }
            p.sendMessage(Component.text()
                    .append(Component.text("✦ ", TextColor.color(ORANGE)))
                    .append(Component.text("Добро пожаловать в хаб ", NamedTextColor.GRAY))
                    .append(serverName())
                    .build());
            return true;
        }
        if (command.getName().equalsIgnoreCase("pass")) {
            if (!(sender instanceof Player p)) { sender.sendMessage("Only players can use /pass"); return true; }
            if (!authed(p)) return true;
            if (starPass != null) starPass.openMenu(p, 0);
            return true;
        }
        if (command.getName().equalsIgnoreCase("sabers")) {
            if (!(sender instanceof Player p)) { sender.sendMessage("Only players can use /sabers"); return true; }
            if (simulation != null && !simulation.isAuthed(p)) return true;
            enterSabers(p);
            return true;
        }
        if (command.getName().equalsIgnoreCase("space")) {
            if (!(sender instanceof Player p)) { sender.sendMessage("Only players can use /space"); return true; }
            if (simulation != null && !simulation.isAuthed(p)) return true;
            enterStarfight(p);
            return true;
        }
        if (command.getName().equalsIgnoreCase("kino")) {
            if (!(sender instanceof Player p)) { sender.sendMessage("Only players can use /kino"); return true; }
            if (simulation != null && !simulation.isAuthed(p)) return true;
            enterCinema(p);
            return true;
        }
        if (command.getName().equalsIgnoreCase("race")) {
            if (!(sender instanceof Player p)) { sender.sendMessage("Only players can use /race"); return true; }
            if (simulation != null && !simulation.isAuthed(p)) return true;
            enterRace(p);
            return true;
        }
        if (command.getName().equalsIgnoreCase("orion")) {
            if (!(sender instanceof Player p)) { sender.sendMessage("Only players can use /orion"); return true; }
            if (simulation != null && !simulation.isAuthed(p)) return true;
            if (laser != null && laser.inGame(p)) laser.leave(p, true);
            if (orion != null) orion.send(p);
            return true;
        }
        if (command.getName().equalsIgnoreCase("char")) {
            if (!(sender instanceof Player p)) { sender.sendMessage("Only players can use /char"); return true; }
            if (!authed(p) || karma == null) return true;
            karma.open(p);
            return true;
        }
        if (command.getName().equalsIgnoreCase("city") || command.getName().equalsIgnoreCase("neyrocity")
                || command.getName().equalsIgnoreCase("radio")) {
            if (city == null) { sender.sendMessage(Component.text("Neyro-City is off", NamedTextColor.RED)); return true; }
            return city.command(sender, command.getName().toLowerCase(java.util.Locale.ROOT), args);
        }
        if (command.getName().equalsIgnoreCase("neyrohub")) {
            if (args.length == 1 && args[0].equalsIgnoreCase("rebuild")) {
                buildHub();
                buildAnarchyDock();
                spawnDisplays();
                if (orion != null) { orion.shutdown(); orion = null; }
                spawnExtras();
                if (animus != null) animus.scan();
                sender.sendMessage("Hub rebuilt");
                return true;
            }
            sender.sendMessage("/neyrohub rebuild");
            return true;
        }
        return false;
    }

    // ------------------------------------------------------------ void world

    /** Generates nothing: the hub floats in an empty void. */
    public static final class VoidGenerator extends ChunkGenerator {
        @Override public boolean shouldGenerateNoise() { return false; }
        @Override public boolean shouldGenerateSurface() { return false; }
        @Override public boolean shouldGenerateCaves() { return false; }
        @Override public boolean shouldGenerateDecorations() { return false; }
        @Override public boolean shouldGenerateMobs() { return false; }
        @Override public boolean shouldGenerateStructures() { return false; }
        @Override public Location getFixedSpawnLocation(World world, java.util.Random random) {
            return new Location(world, 0.5, FLOOR_Y + 1, 0.5);
        }
    }
}
