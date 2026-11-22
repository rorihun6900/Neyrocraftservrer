package net.neyrocraft.hub;

import io.papermc.paper.dialog.Dialog;
import io.papermc.paper.event.player.AsyncChatEvent;
import io.papermc.paper.event.player.PrePlayerAttackEntityEvent;
import io.papermc.paper.registry.data.dialog.ActionButton;
import io.papermc.paper.registry.data.dialog.DialogBase;
import io.papermc.paper.registry.data.dialog.action.DialogAction;
import io.papermc.paper.registry.data.dialog.body.DialogBody;
import io.papermc.paper.registry.data.dialog.input.DialogInput;
import io.papermc.paper.registry.data.dialog.type.DialogType;
import net.kyori.adventure.bossbar.BossBar;
import net.kyori.adventure.key.Key;
import net.kyori.adventure.sound.Sound;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.TextComponent;
import net.kyori.adventure.text.event.ClickCallback;
import net.kyori.adventure.text.format.NamedTextColor;
import net.kyori.adventure.text.format.TextColor;
import net.kyori.adventure.text.format.TextDecoration;
import net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer;
import net.kyori.adventure.title.Title;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.core.LogEvent;
import org.apache.logging.log4j.core.LoggerContext;
import org.apache.logging.log4j.core.filter.AbstractFilter;
import org.bukkit.Bukkit;
import org.bukkit.Color;
import org.bukkit.GameMode;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.Particle;
import org.bukkit.World;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.ArmorStand;
import org.bukkit.entity.BlockDisplay;
import org.bukkit.entity.Display;
import org.bukkit.entity.Entity;
import org.bukkit.entity.Interaction;
import org.bukkit.entity.Player;
import org.bukkit.entity.TextDisplay;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.block.BlockBreakEvent;
import org.bukkit.event.block.BlockPlaceEvent;
import org.bukkit.event.entity.EntityDamageByEntityEvent;
import org.bukkit.event.entity.EntityDamageEvent;
import org.bukkit.event.entity.EntityDismountEvent;
import org.bukkit.event.entity.EntityPickupItemEvent;
import org.bukkit.event.entity.FoodLevelChangeEvent;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.inventory.InventoryOpenEvent;
import org.bukkit.event.player.AsyncPlayerPreLoginEvent;
import org.bukkit.event.player.PlayerCommandPreprocessEvent;
import org.bukkit.event.player.PlayerDropItemEvent;
import org.bukkit.event.player.PlayerInteractEntityEvent;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.event.player.PlayerItemConsumeEvent;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerMoveEvent;
import org.bukkit.event.player.PlayerSwapHandItemsEvent;
import org.bukkit.potion.PotionEffect;
import org.bukkit.potion.PotionEffectType;
import org.bukkit.scheduler.BukkitTask;
import org.bukkit.util.Transformation;
import org.joml.AxisAngle4f;
import org.joml.Vector3f;

import java.io.File;
import java.time.Duration;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Random;
import java.util.Set;
import java.util.UUID;

/**
 * NeyroAuth — login as "entering the simulation".
 *
 * A new connection is seated at a futuristic computer in a dark chamber surrounded by
 * falling code. First the player proves they are human by typing a sync code on a
 * holographic keypad (captcha), then enters their access key in a native dialog window.
 */
final class Simulation implements Listener {

    static final double LX = 0.5, LY = 200, LZ = 4000.5;    // the chamber, inside the hub world
    static final int TIMEOUT_SECONDS = 600;
    static final int MAX_PASSWORD_FAILS = 5;
    static final int MAX_CAPTCHA_FAILS = 3;
    static final int MAX_ACCOUNTS_PER_IP = 3;
    static final int IP_FAIL_LIMIT = 10;                    // wrong keys per IP ...
    static final long IP_FAIL_WINDOW_MS = 10 * 60_000L;     // ... within 10 minutes
    static final long IP_LOCK_MS = 10 * 60_000L;            // locks the IP for 10 minutes

    private static final String GLYPHS = "01アイウエオカキクケコサシスセソタチツテトナニヌネノハヒフヘホ<>/#NEYRO";
    private static final String NOISE = "█▓▒░#@$%&01<>/\\";
    private static final String P = "NEYRO//SIM";
    private static final int ORANGE = NeyroHub.ORANGE;

    private final NeyroHub plugin;
    private final World world;
    private final AccountStore store;
    private final Random rnd = new Random();

    private final List<Column> columns = new ArrayList<>();
    private final Map<UUID, Session> sessions = new HashMap<>();
    private final Set<UUID> authed = new HashSet<>();
    private final File restartSessionFile;
    private final org.bukkit.configuration.file.YamlConfiguration restartSessions;
    private final Map<String, Deque<Long>> ipFails = new HashMap<>();
    private final Map<String, Long> ipLocks = new HashMap<>();
    private final PasswordLogFilter logFilter = new PasswordLogFilter();
    private final EmailChallenge emailChallenge;
    private BukkitTask ticker;
    private long tick;

    enum Phase { BOOT, CAPTCHA, PASSWORD, CHECKING, DONE }

    /** One column of falling code. */
    private static final class Column {
        TextDisplay display;
        char[] chars;
        double head;
        double speed;
        int trail;
    }

    /** One player sitting at the terminal. */
    private final class Session {
        final Player player;
        final String ip;
        final boolean registered;
        Phase phase = Phase.BOOT;
        final List<Entity> entities = new ArrayList<>();
        final Map<UUID, Character> keyByEntity = new HashMap<>();
        final Map<Character, TextDisplay> keyLabel = new HashMap<>();
        final Map<Character, Integer> keyFlash = new HashMap<>();
        ArmorStand seat;
        BlockDisplay lid;
        BlockDisplay lidEdge;
        final List<BlockDisplay> podLights = new ArrayList<>();
        final List<BlockDisplay> statusLights = new ArrayList<>();
        final List<BlockDisplay> lidRibs = new ArrayList<>();
        BlockDisplay visor;
        TextDisplay hud;
        final Map<UUID, Vector3f> hingeMin = new HashMap<>();
        final Map<UUID, Vector3f> hingeSize = new HashMap<>();
        Entity camera;
        GameMode prevMode = GameMode.ADVENTURE;
        TextDisplay screen;
        TextDisplay panelLeft;
        TextDisplay panelRight;
        BossBar bar;
        final List<String> lines = new ArrayList<>();
        final List<Integer> colors = new ArrayList<>();
        int revealed = 0;
        String code = "";
        final StringBuilder typed = new StringBuilder();
        int captchaFails = 0;
        int passwordFails = 0;
        int secondsLeft = TIMEOUT_SECONDS;
        int checkingTicks = 0;
        String pendingRegistrationHash;
        long lastPress = 0;
        String status = "";
        int statusColor = 0xB0B0B0;

        Session(Player player, String ip, boolean registered) {
            this.player = player;
            this.ip = ip;
            this.registered = registered;
        }

        void log(String text, int color) {
            lines.add(text);
            colors.add(color);
            while (lines.size() > 7) {       // keep the log short, the footer needs space
                int removed = lines.remove(0).length() + 1;
                colors.remove(0);
                revealed = Math.max(0, revealed - removed);
            }
        }

        int totalChars() {
            int n = 0;
            for (String s : lines) n += s.length() + 1;
            return n;
        }

        boolean logDone() {
            return revealed >= totalChars();
        }
    }

    Simulation(NeyroHub plugin, World world, AccountStore store) {
        this.plugin = plugin;
        this.world = world;
        this.store = store;
        this.emailChallenge = new EmailChallenge(plugin);
        this.restartSessionFile = new File(plugin.getDataFolder(), "restart-sessions.yml");
        this.restartSessions = org.bukkit.configuration.file.YamlConfiguration.loadConfiguration(restartSessionFile);
        buildBay();
        spawnBayPlanets();
        LoggerContext ctx = (LoggerContext) LogManager.getContext(false);
        ctx.getConfiguration().getRootLogger().addFilter(logFilter);
        ctx.updateLoggers();
        ticker = Bukkit.getScheduler().runTaskTimer(plugin, this::tick, 1L, 1L);
        // players already online when the plugin (re)loads count as authenticated
        for (Player p : Bukkit.getOnlinePlayers()) authed.add(p.getUniqueId());
    }

    void shutdown() {
        if (ticker != null) ticker.cancel();
        for (Session s : new ArrayList<>(sessions.values())) {
            cleanup(s);
            s.player.kick(Component.text(P + " · перезагрузка симуляции, переподключись", TextColor.color(ORANGE)));
        }
        for (Column c : columns) if (c.display != null) c.display.remove();
        columns.clear();
        for (TextDisplay d : planets) if (d.isValid()) d.remove();
        planets.clear();
        try {
            LoggerContext ctx = (LoggerContext) LogManager.getContext(false);
            ctx.getConfiguration().getRootLogger().removeFilter(logFilter);
            ctx.updateLoggers();
        } catch (RuntimeException ignored) {
        }
    }

    boolean isAuthed(Player p) {
        return authed.contains(p.getUniqueId());
    }

    void saveRestartSessions() {
        restartSessions.set("players", null);
        long expiry = System.currentTimeMillis() + 300_000L;
        for (Player p : Bukkit.getOnlinePlayers()) {
            if (!isAuthed(p) || p.getAddress() == null) continue;
            AccountStore.Account account = store.get(p.getName());
            if (account == null) continue;
            String path = "players." + p.getUniqueId();
            restartSessions.set(path + ".name", p.getName());
            restartSessions.set(path + ".ip", p.getAddress().getAddress().getHostAddress());
            restartSessions.set(path + ".hash", account.hash());
            restartSessions.set(path + ".expires", expiry);
        }
        saveRestartFile();
    }

    private void saveRestartFile() {
        try { restartSessions.save(restartSessionFile); }
        catch (java.io.IOException ex) { plugin.getLogger().warning("Restart sessions could not be saved: " + ex.getMessage()); }
    }

    private boolean restoreRestartSession(Player p) {
        String path = "players." + p.getUniqueId();
        if (!restartSessions.isConfigurationSection(path)) return false;
        AccountStore.Account account = store.get(p.getName());
        String ip = p.getAddress() == null ? "" : p.getAddress().getAddress().getHostAddress();
        boolean ok = account != null && account.realName().equals(p.getName())
                && p.getName().equals(restartSessions.getString(path + ".name"))
                && !ip.isEmpty() && ip.equals(restartSessions.getString(path + ".ip"))
                && account.hash().equals(restartSessions.getString(path + ".hash"))
                && System.currentTimeMillis() <= restartSessions.getLong(path + ".expires");
        restartSessions.set(path, null);
        saveRestartFile();
        if (ok) {
            authed.add(p.getUniqueId());
            p.sendMessage(NeyroChat.systemMessage(Component.text("Сессия восстановлена после перезапуска. Добро пожаловать!", NamedTextColor.GREEN)));
        }
        return ok;
    }

    boolean inChamber(Player p) {
        return sessions.containsKey(p.getUniqueId());
    }

    int importAuthMe() {
        return store.importAuthMe(new File(plugin.getDataFolder().getParentFile(), "AuthMe/authme.db"));
    }

    // ------------------------------------------------------------------ scene

    // where the player stands, facing the capsule (north)
    static final double VX = LX, VZ = LZ + 2.3;

    private void set(int x, int y, int z, Material m) {
        world.getBlockAt(x, y, z).setType(m, false);
    }

    /** The VR bay of the starship NEYRO-1: a white cabin with big windows onto space. */
    private void buildBay() {
        int bx = (int) Math.floor(LX), by = (int) LY - 1, bz = (int) Math.floor(LZ);
        for (int cx = (bx - 20) >> 4; cx <= (bx + 20) >> 4; cx++)
            for (int cz = (bz - 14) >> 4; cz <= (bz + 14) >> 4; cz++) world.setChunkForceLoaded(cx, cz, true);
        // clear the old chamber / previous build
        for (int x = -18; x <= 18; x++) for (int z = -13; z <= 13; z++) for (int y = -4; y <= 9; y++)
            if (world.getBlockAt(bx + x, by + y, bz + z).getType() != Material.AIR) set(bx + x, by + y, bz + z, Material.AIR);

        // cabin cross-section: straight walls, then two chamfers up to a narrow ceiling
        int[] half = {4, 4, 4, 4, 3, 2};          // interior half-width for y = 0..5 (index = y)
        for (int z = -7; z <= 6; z++) {
            boolean front = z == -7, back = z == 6;
            for (int x = -6; x <= 6; x++) {
                int ax = Math.abs(x);
                // floor: light walkway, glowing guide lines, dark grating
                if (ax <= 5) {
                    Material f = ax >= 5 ? Material.GRAY_CONCRETE
                            : ax <= 1 ? (x == 0 && Math.floorMod(z, 2) == 0 ? Material.SMOOTH_QUARTZ : Material.LIGHT_GRAY_CONCRETE)
                            : ax == 2 ? (Math.floorMod(z, 3) == 0 ? Material.OCHRE_FROGLIGHT : Material.GRAY_CONCRETE)
                            : Material.POLISHED_DEEPSLATE;
                    set(bx + x, by, bz + z, f);
                }
                for (int y = 1; y <= 6; y++) {
                    int h = y <= 5 ? half[y] : -1;
                    boolean inside = ax <= h && !front && !back;
                    boolean shell = !inside && (ax == h + 1 || (y == 6 && ax <= 2) || (ax == h + 2 && y >= 4 && y <= 5 && ax <= 5));
                    if (front || back) shell = ax <= (y <= 5 ? half[y] + 1 : 2);
                    if (!shell) continue;
                    Material m = Material.WHITE_CONCRETE;
                    if (y == 6) m = x == 0 && Math.floorMod(z, 2) == 0 ? Material.OCHRE_FROGLIGHT
                            : ax == 2 ? Material.LIGHT_GRAY_CONCRETE : Material.WHITE_CONCRETE;
                    else if (ax == 5 && (y == 2 || y == 3) && z >= -5 && z <= 4)          // side windows
                        m = Math.floorMod(z, 3) == 0 ? Material.LIGHT_GRAY_CONCRETE : Material.GLASS;
                    else if (y == 4 && ax >= 4) m = Math.floorMod(z, 2) == 0 ? Material.OCHRE_FROGLIGHT : Material.WHITE_CONCRETE;
                    else if (y == 5 && ax >= 3) m = Material.LIGHT_GRAY_CONCRETE;
                    if (front) m = (y >= 1 && y <= 5 && ax <= half[y] && !(ax == 0 && y == 5)) ? Material.GLASS : Material.LIGHT_GRAY_CONCRETE;
                    if (back) {
                        m = Material.WHITE_CONCRETE;
                        if (x == 0 && y <= 3) m = Material.LIGHT_GRAY_CONCRETE;          // door
                        else if (ax == 1 && y <= 4) m = Material.ORANGE_CONCRETE;
                        else if (x == 0 && y == 4) m = Material.OCHRE_FROGLIGHT;
                    }
                    set(bx + x, by + y, bz + z, m);
                }
            }
        }
        // pilot console under the cockpit glass, side consoles along the walls
        for (int x = -3; x <= 3; x++) set(bx + x, by + 1, bz - 6, Math.abs(x) == 2 ? Material.OCHRE_FROGLIGHT : Material.BLACK_CONCRETE);
        for (int side : new int[]{-1, 1}) for (int z : new int[]{-4, 2}) {
            set(bx + side * 4, by + 1, bz + z, Material.BLACK_CONCRETE);
            set(bx + side * 4, by + 1, bz + z + 1, Material.BLACK_CONCRETE);
        }
        // the ship's nose ahead of the cockpit
        for (int dz = 1; dz <= 9; dz++) {
            int hw = Math.max(0, 5 - (int) Math.round(dz * 0.6));
            for (int x = -hw; x <= hw; x++) {
                set(bx + x, by, bz - 7 - dz, x == 0 ? Material.ORANGE_CONCRETE : Material.LIGHT_GRAY_CONCRETE);
                if (dz <= 5 && Math.abs(x) < hw) set(bx + x, by + 1, bz - 7 - dz, Math.abs(x) == hw - 1 ? Material.WHITE_CONCRETE : Material.AIR);
            }
            set(bx, by - 1, bz - 7 - dz, Material.GRAY_CONCRETE);
        }
        // wings with engine pods and navigation lights
        for (int side : new int[]{-1, 1}) {
            for (int dx = 6; dx <= 18; dx++) {
                double halfLen = 4.0 - (dx - 6) * 0.22;
                for (int z = (int) -Math.floor(halfLen); z <= (int) Math.floor(halfLen); z++) {
                    Material m = z == (int) -Math.floor(halfLen) ? Material.ORANGE_CONCRETE : Material.LIGHT_GRAY_CONCRETE;
                    set(bx + side * dx, by + 1, bz + z + 1, m);
                }
            }
            set(bx + side * 18, by + 1, bz + 1, side < 0 ? Material.PEARLESCENT_FROGLIGHT : Material.VERDANT_FROGLIGHT);
            for (int x = 13; x <= 15; x++) for (int y = 0; y <= 2; y++) for (int z = -3; z <= 4; z++) {
                boolean edge = (x == 13 || x == 15) && (y == 0 || y == 2);
                if (edge) continue;
                set(bx + side * x, by + y, bz + z, z == 4 ? Material.SHROOMLIGHT : z == -3 ? Material.WHITE_CONCRETE : Material.GRAY_CONCRETE);
            }
        }
        // main engines at the back
        for (int x = -3; x <= 3; x++) for (int y = 1; y <= 4; y++) for (int z = 7; z <= 9; z++) {
            boolean glow = z == 9 && Math.abs(x) <= 2 && y >= 2 && y <= 3;
            set(bx + x, by + y, bz + z, glow ? Material.SHROOMLIGHT : Material.GRAY_CONCRETE);
        }
        buildEscort(bx - 24, by + 7, bz - 20);
    }

    /** A small escort fighter flying alongside, seen through the left and front windows. */
    private void buildEscort(int ex, int ey, int ez) {
        for (int z = 0; z <= 8; z++) for (int x = -1; x <= 1; x++) for (int y = 0; y <= 1; y++)
            set(ex + x, ey + y, ez + z, z <= 1 && y == 1 ? Material.LIGHT_BLUE_STAINED_GLASS : Material.LIGHT_GRAY_CONCRETE);
        for (int x = -5; x <= 5; x++) for (int z = 4; z <= 6; z++)
            if (Math.abs(x) > 1) set(ex + x, ey, ez + z, z == 4 ? Material.ORANGE_CONCRETE : Material.WHITE_CONCRETE);
        set(ex - 5, ey, ez + 5, Material.PEARLESCENT_FROGLIGHT);
        set(ex + 5, ey, ez + 5, Material.VERDANT_FROGLIGHT);
        for (int x = -1; x <= 1; x++) set(ex + x, ey, ez + 9, Material.SHROOMLIGHT);
        set(ex, ey + 2, ez + 7, Material.LIGHT_GRAY_CONCRETE);
    }

    private final List<TextDisplay> planets = new ArrayList<>();

    private void spawnBayPlanets() {
        planets.addAll(SpaceDecor.planet(plugin, world, new Location(world, LX + 12, LY + 14, LZ - 75), SpaceDecor.Kind.TERRA, 40f, 3));
        planets.addAll(SpaceDecor.planet(plugin, world, new Location(world, LX + 80, LY + 6, LZ - 15), SpaceDecor.Kind.GAS, 34f, 11));
        planets.addAll(SpaceDecor.planet(plugin, world, new Location(world, LX - 70, LY + 18, LZ - 30), SpaceDecor.Kind.MOON, 12f, 5));
        planets.addAll(SpaceDecor.planet(plugin, world, new Location(world, LX - 35, LY + 32, LZ - 90), SpaceDecor.Kind.MARS, 10f, 9));
    }

    private void spawnRain() {
        addRing(22, 6.5, 1.5f, -0.5);
        addRing(30, 10.5, 2.2f, -3.0);
    }

    private void addRing(int count, double radius, float scale, double yOffset) {
        for (int i = 0; i < count; i++) {
            double a = i * Math.PI * 2 / count + rnd.nextDouble() * 0.1;
            Location l = new Location(world, LX + Math.cos(a) * radius, LY + yOffset, LZ + Math.sin(a) * radius);
            Column c = new Column();
            c.chars = new char[16];
            for (int k = 0; k < c.chars.length; k++) c.chars[k] = glyph();
            c.head = -rnd.nextInt(16);
            c.speed = 0.25 + rnd.nextDouble() * 0.35;
            c.trail = 5 + rnd.nextInt(5);
            c.display = world.spawn(l, TextDisplay.class, d -> {
                plugin.prepare(d);
                d.setBillboard(Display.Billboard.VERTICAL);
                d.setBackgroundColor(Color.fromARGB(0, 0, 0, 0));
                d.setShadowed(false);
                d.setBrightness(new Display.Brightness(15, 15));
                d.setLineWidth(20);
                d.setTransformation(scale(scale));
            });
            columns.add(c);
        }
    }

    private char glyph() {
        return GLYPHS.charAt(rnd.nextInt(GLYPHS.length()));
    }

    private void renderColumn(Column c) {
        TextComponent.Builder b = Component.text();
        for (int row = 0; row < c.chars.length; row++) {
            if (row > 0) b.append(Component.newline());
            int dist = (int) Math.floor(c.head) - row;
            int color;
            if (dist == 0) color = 0xFFF0C8;
            else if (dist > 0 && dist <= c.trail) color = mix(0xFF9A1A, 0x2A1400, dist / (double) c.trail);
            else color = 0x140A00;
            if (dist >= 0 && dist <= 1 && rnd.nextInt(3) == 0) c.chars[row] = glyph();
            b.append(Component.text(String.valueOf(c.chars[row]), TextColor.color(color)));
        }
        c.display.text(b.build());
    }

    private static Transformation scale(float s) {
        return new Transformation(new Vector3f(), new AxisAngle4f(), new Vector3f(s, s, s), new AxisAngle4f());
    }

    /** A block display box of the given size whose centre sits at (x, y, z). */
    private BlockDisplay box(Session s, Material m, double x, double y, double z, float sx, float sy, float sz, boolean glow) {
        BlockDisplay d = world.spawn(new Location(world, x, y, z), BlockDisplay.class, e -> {
            plugin.prepare(e);
            e.setVisibleByDefault(false);
            e.setBlock(m.createBlockData());
            e.setTransformation(new Transformation(new Vector3f(-sx / 2, -sy / 2, -sz / 2), new AxisAngle4f(),
                    new Vector3f(sx, sy, sz), new AxisAngle4f()));
            if (glow) e.setBrightness(new Display.Brightness(15, 15));
        });
        s.player.showEntity(plugin, d);
        s.entities.add(d);
        return d;
    }

    /** A text display facing the seated player. */
    private TextDisplay text(Session s, double x, double y, double z, float scale, int lineWidth, int bgArgb) {
        float yaw = (float) Math.toDegrees(Math.atan2(-(VX - x), VZ - z));
        TextDisplay d = world.spawn(new Location(world, x, y, z, yaw, 0), TextDisplay.class, e -> {
            plugin.prepare(e);
            e.setVisibleByDefault(false);
            e.setBillboard(Display.Billboard.FIXED);
            e.setBackgroundColor(Color.fromARGB(bgArgb));
            e.setLineWidth(lineWidth);
            e.setBrightness(new Display.Brightness(15, 15));
            e.setTransformation(scale(scale));
        });
        s.player.showEntity(plugin, d);
        s.entities.add(d);
        return d;
    }

    // the VR capsule lies along Z in front of the player; origin = centre of its footprint on the floor
    static final double CX = LX, CZ = LZ - 1.0;
    static final float CAP_W = 1.3f, CAP_H = 0.6f, CAP_L = 2.5f, LID_H = 0.42f, LID_L = 2.1f;
    static final double HEAD_Z = CZ + CAP_L / 2 - 0.38;      // where the head rests
    static final double ARCH_Z = CZ + CAP_L / 2 - 0.2;       // arch holding the VR visor

    private void buildTerminal(Session s) {
        double e = LY + 1.62;    // standing eye height

        // pedestal with a status ring of light
        box(s, Material.GRAY_CONCRETE, CX, LY + 0.09, CZ, 1.0f, 0.18f, 2.2f, false);
        s.statusLights.add(box(s, Material.OCHRE_FROGLIGHT, CX, LY + 0.19, CZ, 1.04f, 0.03f, 2.24f, true));
        // body with rounded (stepped) ends and side trims
        box(s, Material.WHITE_CONCRETE, CX, LY + 0.2 + (CAP_H - 0.2) / 2, CZ, CAP_W, CAP_H - 0.2f, CAP_L, false);
        for (int end : new int[]{-1, 1}) {
            box(s, Material.WHITE_CONCRETE, CX, LY + 0.4, CZ + end * (CAP_L / 2 + 0.06), CAP_W - 0.2f, 0.32f, 0.12f, false);
            box(s, Material.WHITE_CONCRETE, CX, LY + 0.4, CZ + end * (CAP_L / 2 + 0.14), CAP_W - 0.5f, 0.22f, 0.06f, false);
        }
        for (int side : new int[]{-1, 1})
            box(s, Material.LIGHT_GRAY_CONCRETE, CX + side * (CAP_W / 2 + 0.005), LY + 0.42, CZ, 0.02f, 0.06f, CAP_L - 0.1f, false);
        s.statusLights.add(box(s, Material.OCHRE_FROGLIGHT, CX, LY + CAP_H - 0.03, CZ, CAP_W + 0.02f, 0.03f, CAP_L + 0.02f, true));
        // padded interior: mattress, side cushions, pillow, inner light strips
        box(s, Material.BLACK_CONCRETE, CX, LY + CAP_H + 0.01, CZ, CAP_W - 0.26f, 0.05f, CAP_L - 0.24f, false);
        for (int side : new int[]{-1, 1})
            box(s, Material.GRAY_WOOL, CX + side * 0.44, LY + CAP_H + 0.06, CZ - 0.1, 0.12f, 0.1f, CAP_L - 0.6f, false);
        box(s, Material.LIGHT_GRAY_WOOL, CX, LY + CAP_H + 0.08, HEAD_Z, 0.62f, 0.11f, 0.34f, false);
        s.podLights.add(box(s, Material.GRAY_CONCRETE, CX - CAP_W / 2 + 0.1, LY + CAP_H + 0.04, CZ, 0.03f, 0.03f, CAP_L - 0.3f, false));
        s.podLights.add(box(s, Material.GRAY_CONCRETE, CX + CAP_W / 2 - 0.1, LY + CAP_H + 0.04, CZ, 0.03f, 0.03f, CAP_L - 0.3f, false));

        // control panel on the right side with three buttons
        box(s, Material.BLACK_CONCRETE, CX + CAP_W / 2 + 0.02, LY + 0.4, CZ + 0.35, 0.04f, 0.24f, 0.62f, false);
        Material[] buttons = {Material.VERDANT_FROGLIGHT, Material.OCHRE_FROGLIGHT, Material.PEARLESCENT_FROGLIGHT};
        for (int i = 0; i < 3; i++)
            box(s, buttons[i], CX + CAP_W / 2 + 0.05, LY + 0.44, CZ + 0.15 + i * 0.2, 0.03f, 0.07f, 0.07f, true);
        // hoses from the head end into the floor
        for (int side : new int[]{-1, 1}) {
            box(s, Material.BLACK_CONCRETE, CX + side * 0.38, LY + 0.3, CZ + CAP_L / 2 + 0.22, 0.08f, 0.5f, 0.08f, false);
            box(s, Material.BLACK_CONCRETE, CX + side * 0.38, LY + 0.04, CZ + CAP_L / 2 + 0.6, 0.08f, 0.08f, 0.8f, false);
        }

        // arch over the head end holding the VR visor
        for (int side : new int[]{-1, 1})
            box(s, Material.WHITE_CONCRETE, CX + side * (CAP_W / 2 - 0.05), LY + CAP_H + 0.33, ARCH_Z, 0.1f, 0.66f, 0.12f, false);
        box(s, Material.WHITE_CONCRETE, CX, LY + CAP_H + 0.68, ARCH_Z, CAP_W + 0.02f, 0.08f, 0.14f, false);
        s.statusLights.add(box(s, Material.OCHRE_FROGLIGHT, CX, LY + CAP_H + 0.62, ARCH_Z + 0.08, CAP_W - 0.2f, 0.03f, 0.02f, true));
        s.visor = world.spawn(new Location(world, CX, LY + CAP_H, HEAD_Z), BlockDisplay.class, d -> {
            plugin.prepare(d);
            d.setVisibleByDefault(false);
            d.setBlock(Material.BLACK_STAINED_GLASS.createBlockData());
            d.setTransformation(visorTransform(0.58f));
        });
        s.player.showEntity(plugin, s.visor);
        s.entities.add(s.visor);
        TextDisplay label = text(s, CX + 0.9, LY + 0.75, ARCH_Z + 0.2, 0.32f, 200, 0x00000000);
        label.setBillboard(Display.Billboard.CENTER);
        label.text(Component.text("◈ VR-КАПСУЛА 07 ◈", TextColor.color(ORANGE)).decorate(TextDecoration.BOLD));

        // hinge and the glass lid (up to the arch) with orange ribs; all of it swings about the hinge
        box(s, Material.GRAY_CONCRETE, CX - CAP_W / 2, LY + CAP_H, CZ - CAP_L / 2 + LID_L / 2, 0.08f, 0.08f, LID_L, false);
        float lidZ = -CAP_L / 2;
        s.lid = hinged(s, Material.LIGHT_BLUE_STAINED_GLASS, new Vector3f(-CAP_W / 2, CAP_H, lidZ), new Vector3f(CAP_W, LID_H, LID_L));
        s.lidEdge = hinged(s, Material.ORANGE_CONCRETE,
                new Vector3f(CAP_W / 2 - 0.07f, CAP_H + LID_H - 0.07f, lidZ), new Vector3f(0.07f, 0.07f, LID_L));
        for (float rz : new float[]{0.35f, 1.05f, 1.75f})
            s.lidRibs.add(hinged(s, Material.WHITE_CONCRETE,
                    new Vector3f(-CAP_W / 2, CAP_H + LID_H - 0.04f, lidZ + rz), new Vector3f(CAP_W, 0.05f, 0.05f)));
        // light pylons at the far end
        for (int side : new int[]{-1, 1}) {
            box(s, Material.WHITE_CONCRETE, CX + side * 0.85, LY + 0.65, CZ - CAP_L / 2 + 0.1, 0.12f, 1.3f, 0.12f, false);
            box(s, Material.OCHRE_FROGLIGHT, CX + side * 0.85, LY + 1.33, CZ - CAP_L / 2 + 0.1, 0.16f, 0.06f, 0.16f, true);
        }

        // holo-screen floating above the far end of the capsule
        s.screen = text(s, LX, LY + 1.75, LZ - 2.7, 0.46f, 420, 0xC804080E);
        s.screen.setAlignment(TextDisplay.TextAlignment.LEFT);

        // ship status panels on the left
        s.panelLeft = text(s, LX - 1.75, e + 0.05, LZ + 0.6, 0.32f, 200, 0x9A0A0602);
        s.panelLeft.setAlignment(TextDisplay.TextAlignment.LEFT);
        s.panelRight = text(s, LX - 1.75, e - 0.6, LZ + 0.6, 0.32f, 200, 0x9A0A0602);
        s.panelRight.setAlignment(TextDisplay.TextAlignment.LEFT);

        // keypad on the right, at chest height
        buildKeypad(s, LX + 0.95, e - 0.22, LZ + 1.3);
    }

    /** A block display that rotates about the capsule's hinge (the -X top edge, along Z). */
    private BlockDisplay hinged(Session s, Material m, Vector3f min, Vector3f size) {
        BlockDisplay d = world.spawn(new Location(world, CX, LY, CZ), BlockDisplay.class, e -> {
            plugin.prepare(e);
            e.setVisibleByDefault(false);
            e.setBlock(m.createBlockData());
            e.setTransformation(hingeTransform(min, size, 0));
            e.setBrightness(new Display.Brightness(15, 15));
        });
        s.player.showEntity(plugin, d);
        s.entities.add(d);
        s.hingeMin.put(d.getUniqueId(), min);
        s.hingeSize.put(d.getUniqueId(), size);
        return d;
    }

    private static Transformation hingeTransform(Vector3f min, Vector3f size, float degrees) {
        Vector3f pivot = new Vector3f(-CAP_W / 2, CAP_H, 0);
        org.joml.Quaternionf q = new org.joml.Quaternionf().rotateZ((float) Math.toRadians(degrees));
        Vector3f t = q.transform(new Vector3f(min).sub(pivot)).add(pivot);
        return new Transformation(t, q, size, new org.joml.Quaternionf());
    }

    /** The visor box hangs {@code height} above the capsule floor level, centred over the head. */
    private static Transformation visorTransform(float height) {
        return new Transformation(new Vector3f(-0.36f, height, -0.26f), new org.joml.Quaternionf(),
                new Vector3f(0.72f, 0.05f, 0.52f), new org.joml.Quaternionf());
    }

    private void swingLid(Session s, float degrees, int ticks) {
        List<BlockDisplay> parts = new ArrayList<>(s.lidRibs);
        parts.add(s.lid);
        parts.add(s.lidEdge);
        for (BlockDisplay d : parts) {
            if (d == null || !d.isValid()) continue;
            d.setInterpolationDelay(0);
            d.setInterpolationDuration(ticks);
            d.setTransformation(hingeTransform(s.hingeMin.get(d.getUniqueId()), s.hingeSize.get(d.getUniqueId()), degrees));
        }
    }

    private static final char[] KEYS = {'1', '2', '3', '4', '5', '6', '7', '8', '9', 'C', '0', '⌨'};

    private void buildKeypad(Session s, double kx, double ky, double kz) {
        double vx = kx - VX, vz = kz - VZ;                    // from player to keypad
        double len = Math.sqrt(vx * vx + vz * vz);
        vx /= len;
        vz /= len;
        double rx = -vz, rz = vx;                             // viewer's right
        final double step = 0.17;                             // distance between keys
        // a faint backplate behind the keys
        TextDisplay plate = text(s, kx + vx * 0.04, ky - 0.4, kz + vz * 0.04, 0.52f, 60, 0x5A140A00);
        plate.text(Component.text("\n\n\n\n\n", NamedTextColor.BLACK));
        TextDisplay caption = text(s, kx, ky + 0.33, kz, 0.2f, 120, 0x00000000);
        caption.text(Component.text("ГОЛОКЛАВИАТУРА", TextColor.color(ORANGE)).decorate(TextDecoration.BOLD));
        for (int i = 0; i < KEYS.length; i++) {
            int col = i % 3, row = i / 3;
            double ox = (col - 1) * step, oy = (1.5 - row) * step;
            double x = kx + rx * ox, y = ky + oy, z = kz + rz * ox;
            char key = KEYS[i];
            TextDisplay label = text(s, x, y - 0.065, z, 0.28f, 40, 0xC8140C04);
            label.text(keyText(key, false));
            s.keyLabel.put(key, label);
            Interaction hit = world.spawn(new Location(world, x, y - 0.075, z), Interaction.class, e -> {
                e.addScoreboardTag(NeyroHub.TAG);
                e.setPersistent(false);
                e.setVisibleByDefault(false);
                e.setInteractionWidth(0.16f);
                e.setInteractionHeight(0.15f);
                e.setResponsive(true);
            });
            s.player.showEntity(plugin, hit);
            s.entities.add(hit);
            s.keyByEntity.put(hit.getUniqueId(), key);
        }
    }

    private Component keyText(char key, boolean lit) {
        int color = key == 'C' ? 0xFF5555 : key == '⌨' ? 0x55FFFF : ORANGE;
        return Component.text(" " + key + " ", lit ? TextColor.color(0x101010) : TextColor.color(color))
                .decorate(TextDecoration.BOLD);
    }

    // ------------------------------------------------------------- sessions

    void enter(Player p) {
        if (sessions.containsKey(p.getUniqueId()) || isAuthed(p)) return;
        String ip = p.getAddress() != null ? p.getAddress().getAddress().getHostAddress() : "?";
        boolean registered = store.exists(p.getName());
        p.teleport(standPoint());
        if (p.getGameMode() != GameMode.CREATIVE) p.setGameMode(GameMode.ADVENTURE);
        p.addPotionEffect(new PotionEffect(PotionEffectType.DARKNESS, 50, 0, false, false, false));

        Session s = new Session(p, ip, registered);
        sessions.put(p.getUniqueId(), s);
        buildTerminal(s);
        s.bar = BossBar.bossBar(barTitle(s), 1f, BossBar.Color.YELLOW, BossBar.Overlay.NOTCHED_20);
        p.showBossBar(s.bar);

        s.log("NEYRO-1  ·  бортовой компьютер  ·  VR-капсула №7", ORANGE);
        s.log("> системы корабля ................ OK", 0xB0B0B0);
        s.log("> курс: сектор NeyroCraft ........ OK", 0xB0B0B0);
        s.log("> пассажир: " + p.getName() + (registered ? "  · профиль найден" : "  · новый профиль"), 0xFFFFFF);
        s.log("> нейроинтерфейс капсулы ......... ожидание", 0xFFD27F);

        decryptTitle(p, "БОРТ «NEYRO-1»", ORANGE,
                Component.text("Подключись к VR-капсуле, чтобы войти в NeyroCraft", NamedTextColor.GRAY), 22, 3000);
        sound(p, "block.beacon.activate", 0.8f, 0.5f);
        sound(p, "block.conduit.activate", 0.6f, 0.8f);
        // the captcha starts once the boot log has been typed out
        Bukkit.getScheduler().runTaskLater(plugin, () -> startCaptcha(s), 60L);
    }

    private void startCaptcha(Session s) {
        if (!sessions.containsValue(s) || s.phase == Phase.DONE) return;
        s.phase = Phase.CAPTCHA;
        StringBuilder c = new StringBuilder();
        for (int i = 0; i < 4; i++) c.append((char) ('0' + rnd.nextInt(10)));
        s.code = c.toString();
        s.typed.setLength(0);
        s.status = "набери синхро-код на голоклавиатуре справа →";
        s.statusColor = 0xFFFF55;
        sound(s.player, "block.note_block.chime", 0.8f, 1.2f);
        HudBoard.notice(s.player, Component.text("Повернись вправо и нажимай клавиши голоклавиатуры (ПКМ)", NamedTextColor.YELLOW));
    }

    private void pressKey(Session s, char key) {
        Player p = s.player;
        long now = System.currentTimeMillis();
        if (now - s.lastPress < 120) return;           // one click can arrive as several packets
        s.lastPress = now;
        s.keyFlash.put(key, 4);
        sound(p, "block.note_block.bit", 0.7f, key >= '0' && key <= '9' ? 0.8f + (key - '0') * 0.1f : 0.6f);
        if (key == '⌨') {
            if (s.phase == Phase.PASSWORD) msg(p, s.registered ? "Вход: /login <пароль>" : "Регистрация: /register <пароль> <пароль>", NamedTextColor.YELLOW);
            return;
        }
        if (s.phase != Phase.CAPTCHA) return;
        if (key == 'C') {
            s.typed.setLength(0);
            return;
        }
        s.typed.append(key);
        if (s.typed.length() < s.code.length()) return;
        if (s.typed.toString().equals(s.code)) {
            s.phase = Phase.PASSWORD;
            s.log("> проверка нейроинтерфейса ....... OK ✔", 0x55FF55);
            s.status = s.registered ? "введи /login пароль" : "введи /register пароль пароль";
            s.statusColor = 0xFFFF55;
            sound(p, "block.amethyst_block.chime", 1f, 1.5f);
            sound(p, "block.beacon.power_select", 0.6f, 1.6f);
            msg(p, s.registered ? "Войди: /login <пароль>" : "Зарегистрируйся: /register <пароль> <пароль>", NamedTextColor.YELLOW);
        } else {
            s.captchaFails++;
            s.log("> синхро-код не совпал (" + s.captchaFails + "/" + MAX_CAPTCHA_FAILS + ")", 0xFF5555);
            sound(p, "block.note_block.basedrum", 1f, 0.5f);
            if (s.captchaFails >= MAX_CAPTCHA_FAILS) {
                kick(s, "Проверка нейроинтерфейса не пройдена");
                return;
            }
            startCaptcha(s);
        }
    }

    // ----------------------------------------------------------------- dialog

    private void openDialog(Session s, String error) {
        if (s.phase != Phase.PASSWORD || !s.player.isOnline()) return;
        Player p = s.player;
        List<DialogBody> body = new ArrayList<>();
        body.add(DialogBody.plainMessage(Component.text()
                .append(Component.text("Субъект: ", NamedTextColor.GRAY))
                .append(Component.text(p.getName(), TextColor.color(ORANGE)).decorate(TextDecoration.BOLD))
                .append(Component.newline())
                .append(Component.text("Нейроинтерфейс: ", NamedTextColor.GRAY))
                .append(Component.text("подтверждён ✔", NamedTextColor.GREEN))
                .build(), 300));
        if (s.registered) {
            body.add(DialogBody.plainMessage(Component.text(
                    "Введи ключ доступа, чтобы синхронизировать сознание с NeyroCraft.", NamedTextColor.WHITE), 300));
        } else {
            body.add(DialogBody.plainMessage(Component.text()
                    .append(Component.text("Новый субъект. Придумай ключ доступа — пароль для входа.", NamedTextColor.WHITE))
                    .append(Component.newline())
                    .append(Component.text("От 5 до 32 символов, не совпадает с ником. Никому его не сообщай.", NamedTextColor.GRAY))
                    .build(), 300));
        }
        if (error != null) body.add(DialogBody.plainMessage(Component.text("✖ " + error, NamedTextColor.RED), 300));

        List<DialogInput> inputs = new ArrayList<>();
        inputs.add(DialogInput.text("key", Component.text("Ключ доступа", TextColor.color(ORANGE)))
                .width(260).maxLength(32).build());
        if (!s.registered) {
            inputs.add(DialogInput.text("key2", Component.text("Повтори ключ", TextColor.color(ORANGE)))
                    .width(260).maxLength(32).build());
        }

        ClickCallback.Options once = ClickCallback.Options.builder().uses(1).lifetime(Duration.ofMinutes(5)).build();
        ActionButton connect = ActionButton.builder(Component.text("▶ ПОДКЛЮЧИТЬСЯ", NamedTextColor.GREEN).decorate(TextDecoration.BOLD))
                .tooltip(Component.text("Синхронизировать сознание с симуляцией"))
                .width(140)
                .action(DialogAction.customClick((view, audience) -> {
                    String key = view.getText("key");
                    String key2 = s.registered ? null : view.getText("key2");
                    Bukkit.getScheduler().runTask(plugin, () -> submit(s, key, key2));
                }, once))
                .build();
        ActionButton leave = ActionButton.builder(Component.text("✖ ОТКЛЮЧИТЬСЯ", NamedTextColor.RED))
                .tooltip(Component.text("Покинуть сервер"))
                .width(140)
                .action(DialogAction.customClick((view, audience) ->
                        Bukkit.getScheduler().runTask(plugin, () -> kick(s, "Соединение с симуляцией разорвано")), once))
                .build();

        Dialog dialog = Dialog.create(b -> b.empty()
                .base(DialogBase.builder(Component.text("◈ VR-КАПСУЛА №7 · ВХОД В NEYROCRAFT ◈", TextColor.color(ORANGE))
                                .decorate(TextDecoration.BOLD))
                        .externalTitle(Component.text("Вход в NeyroCraft"))
                        .canCloseWithEscape(false)
                        .pause(false)
                        .afterAction(DialogBase.DialogAfterAction.CLOSE)
                        .body(body)
                        .inputs(inputs)
                        .build())
                .type(DialogType.multiAction(List.of(connect, leave)).columns(2).build()));
        p.showDialog(dialog);
    }

    /** Called with what the player typed (dialog, chat or command). */
    private void submit(Session s, String key, String key2) {
        if (s.phase != Phase.PASSWORD || !s.player.isOnline()) return;
        Player p = s.player;
        if (key == null || key.isEmpty()) {
            msg(p, "Введи пароль командой.", NamedTextColor.RED);
            return;
        }
        if (!s.registered) {
            String err = null;
            if (key.length() < 5 || key.length() > 32) err = "Ключ должен быть от 5 до 32 символов";
            else if (key.equalsIgnoreCase(p.getName())) err = "Ключ не может совпадать с ником";
            else if (key2 != null && !key.equals(key2)) err = "Ключи не совпадают";
            else if (store.countByIp(s.ip) >= MAX_ACCOUNTS_PER_IP) err = "С этого адреса создано слишком много профилей";
            if (err != null) {
                sound(p, "block.note_block.basedrum", 1f, 0.5f);
                msg(p, err.replace("Ключ", "Пароль").replace("ключ", "пароль"), NamedTextColor.RED);
                return;
            }
        }
        s.phase = Phase.CHECKING;
        s.checkingTicks = 0;
        s.status = "ключ: " + "•".repeat(Math.min(key.length(), 16)) + "   проверка нейроподписи";
        s.statusColor = 0xB0B0B0;
        sound(p, "block.note_block.hat", 0.8f, 1.2f);
        String name = p.getName();
        if (s.registered) {
            String stored = store.get(name).hash();
            Bukkit.getScheduler().runTaskAsynchronously(plugin, () -> {
                boolean ok = AccountStore.verify(key, stored);
                String upgraded = ok && AccountStore.isLegacy(stored) ? AccountStore.hash(key) : null;
                Bukkit.getScheduler().runTaskLater(plugin, () -> {
                    if (upgraded != null) store.setHash(name, upgraded);
                    if (ok) granted(s);
                    else denied(s);
                }, 14L);
            });
        } else {
            Bukkit.getScheduler().runTaskAsynchronously(plugin, () -> {
                String hash = AccountStore.hash(key);
                Bukkit.getScheduler().runTaskLater(plugin, () -> {
                    if (store.exists(name)) {      // registered meanwhile from another connection
                        kick(s, "Этот ник уже зарегистрирован");
                        return;
                    }
                    if (!alive(s)) return;
                    s.pendingRegistrationHash = hash;
                    s.phase = Phase.PASSWORD;
                    s.status = "укажи почту для подтверждения";
                    msg(p, "Пароль сохранён временно. Укажи почту: /email <адрес>", NamedTextColor.YELLOW);
                }, 14L);
            });
        }
    }

    private void denied(Session s) {
        if (!s.player.isOnline()) return;
        Player p = s.player;
        s.passwordFails++;
        registerIpFail(s.ip);
        s.phase = Phase.PASSWORD;
        s.log("> ключ отклонён · попытка " + s.passwordFails + "/" + MAX_PASSWORD_FAILS, 0xFF5555);
        s.status = "неверный ключ доступа";
        s.statusColor = 0xFF5555;
        decryptTitle(p, "✖ ДОСТУП ЗАПРЕЩЁН ✖", 0xFF3B3B, Component.text("Неверный ключ доступа", NamedTextColor.GRAY), 10, 1500);
        sound(p, "block.respawn_anchor.deplete", 1f, 0.7f);
        sound(p, "block.note_block.basedrum", 1f, 0.5f);
        world.spawnParticle(Particle.DUST, new Location(world, LX, LY + 1.9, LZ - 1.8), 50, 1.4, 0.8, 0.1, 0,
                new Particle.DustOptions(Color.fromRGB(255, 40, 40), 1.4f));
        if (s.passwordFails >= MAX_PASSWORD_FAILS) {
            kick(s, "Слишком много неверных ключей");
            return;
        }
        msg(p, "Неверный пароль. Осталось попыток: " + (MAX_PASSWORD_FAILS - s.passwordFails), NamedTextColor.RED);
    }

    private void granted(Session s) {
        Player p = s.player;
        if (!p.isOnline()) return;
        s.phase = Phase.DONE;
        authed.add(p.getUniqueId());
        store.touch(p.getName(), s.ip);
        if (store.email(p.getName()).isEmpty()) msg(p, "Для восстановления пароля привяжи почту командой /email <адрес>.", NamedTextColor.YELLOW);
        s.log("> ключ принят ✔", 0x55FF55);
        s.log("> VR-капсула: открытие ...", 0x55FF55);
        s.status = "ложись в капсулу";
        s.statusColor = 0x55FF55;
        p.closeDialog();
        p.hideBossBar(s.bar);
        s.prevMode = p.getGameMode() == GameMode.CREATIVE ? GameMode.CREATIVE : GameMode.ADVENTURE;
        decryptTitle(p, "✔ ДОСТУП РАЗРЕШЁН", 0x55FF55,
                Component.text("VR-капсула открывается...", NamedTextColor.GRAY), 12, 1400);
        sound(p, "block.beacon.power_select", 1f, 1.2f);
        plugin.getLogger().info(p.getName() + " authenticated from " + s.ip);

        // 1. the lid swings open with a hiss of steam, the inside lights up
        swingLid(s, 108f, 26);
        sound(p, "block.piston.extend", 1f, 0.6f);
        sound(p, "block.fire.extinguish", 0.8f, 0.7f);
        for (BlockDisplay l : s.podLights) {
            l.setBlock(Material.OCHRE_FROGLIGHT.createBlockData());
            l.setBrightness(new Display.Brightness(15, 15));
        }
        for (BlockDisplay l : s.statusLights) l.setBlock(Material.VERDANT_FROGLIGHT.createBlockData());   // status: ready
        for (int i = 0; i < 26; i += 2) {
            Bukkit.getScheduler().runTaskLater(plugin, () -> {
                if (!alive(s)) return;
                world.spawnParticle(Particle.CLOUD, new Location(world, CX, LY + CAP_H + 0.2, CZ), 6,
                        CAP_W * 0.45, 0.1, CAP_L * 0.45, 0.02);
            }, i);
        }
        // 2. lie down: the view glides into the capsule and turns to look up at the lid
        later(s, 30, () -> lieDown(s));
    }

    private boolean alive(Session s) {
        return s.player.isOnline() && sessions.get(s.player.getUniqueId()) == s;
    }

    private void later(Session s, long ticks, Runnable r) {
        Bukkit.getScheduler().runTaskLater(plugin, () -> { if (alive(s)) r.run(); }, ticks);
    }

    /** Camera key frame: position, yaw, pitch, and the tick it is reached. */
    private record Frame(double x, double y, double z, float yaw, float pitch, int tick) {}

    private void lieDown(Session s) {
        Player p = s.player;
        double eyeLying = LY + CAP_H + 0.28;
        List<Frame> keys = List.of(
                new Frame(VX, LY + 1.62, VZ, 180f, 10f, 0),                          // standing
                new Frame(CX + 0.95, LY + 1.62, HEAD_Z + 0.15, 100f, 32f, 24),       // walk to the side of the capsule
                new Frame(CX + 0.2, LY + 1.22, HEAD_Z - 0.25, 180f, 18f, 40),        // sit on the edge, look along it
                new Frame(CX, eyeLying, HEAD_Z, 180f, -89f, 62));                    // lie back onto the pillow
        org.bukkit.entity.ItemDisplay cam = world.spawn(new Location(world, VX, LY + 1.62, VZ, 180f, 10f),
                org.bukkit.entity.ItemDisplay.class, d -> {
                    plugin.prepare(d);
                    d.setTeleportDuration(2);
                });
        s.camera = cam;
        s.entities.add(cam);
        p.setGameMode(GameMode.SPECTATOR);
        HudBoard.notice(p, Component.text("Ложимся в VR-капсулу...", NamedTextColor.GRAY));
        // the keypad is no longer needed and would be in the way of the walk
        for (Entity en : s.entities) if (en instanceof Interaction && en.isValid()) en.remove();
        for (TextDisplay d : s.keyLabel.values()) if (d.isValid()) d.remove();

        int last = keys.get(keys.size() - 1).tick();
        for (int t = 1; t <= last; t++) {
            final int step = t;
            later(s, t, () -> {
                if (step == 2) p.setSpectatorTarget(cam);
                Frame a = keys.get(0), b = keys.get(1);
                for (int i = 1; i < keys.size(); i++) {
                    if (step <= keys.get(i).tick()) { a = keys.get(i - 1); b = keys.get(i); break; }
                }
                double k = smooth((step - a.tick()) / (double) (b.tick() - a.tick()));
                double x = a.x() + (b.x() - a.x()) * k;
                double y = a.y() + (b.y() - a.y()) * k;
                double z = a.z() + (b.z() - a.z()) * k;
                if (b.tick() == 24) y += Math.abs(Math.sin(k * Math.PI * 3)) * 0.05;   // walking bob
                float yaw = (float) lerpAngle(a.yaw(), b.yaw(), k);
                float pitch = (float) (a.pitch() + (b.pitch() - a.pitch()) * (b.tick() == last ? ease(k) : k));
                cam.teleport(new Location(world, x, y, z, yaw, pitch));
                if (b.tick() == 24 && step % 8 == 4) sound(p, "block.metal.step", 0.6f, 1.0f);
                if (step == 40) sound(p, "block.wool.place", 0.9f, 0.8f);
                if (step == last) sound(p, "block.wool.step", 0.9f, 0.7f);
            });
        }

        // the VR visor lowers in front of the eyes, its HUD boots up
        later(s, last + 2, () -> {
            s.visor.setInterpolationDelay(0);
            s.visor.setInterpolationDuration(16);
            s.visor.setTransformation(visorTransform(0.28f + 0.16f));
            sound(p, "block.iron_trapdoor.close", 0.9f, 0.7f);
            sound(p, "block.beacon.activate", 0.5f, 1.8f);
        });
        later(s, last + 16, () -> {
            s.hud = world.spawn(new Location(world, CX, eyeLying + 0.13, HEAD_Z), TextDisplay.class, d -> {
                plugin.prepare(d);
                d.setVisibleByDefault(false);
                d.setBillboard(Display.Billboard.CENTER);
                d.setBackgroundColor(Color.fromARGB(0, 0, 0, 0));
                d.setAlignment(TextDisplay.TextAlignment.LEFT);
                d.setBrightness(new Display.Brightness(15, 15));
                d.setTransformation(scale(0.075f));
            });
            p.showEntity(plugin, s.hud);
            s.entities.add(s.hud);
        });
        // the lid closes over the capsule
        later(s, last + 20, () -> {
            swingLid(s, 0f, 24);
            sound(p, "block.piston.contract", 1f, 0.6f);
            sound(p, "block.fire.extinguish", 0.6f, 0.9f);
        });
        // HUD boot text and neural sync
        String[] boot = {"◈ NEYRO-SYNC VISOR · v3", "> визор подключён .......... OK", "> капсула загерметизирована . OK",
                "> пункт назначения: палуба NEYRO-1"};
        int syncStart = last + 22, syncLen = 56;
        for (int i = 0; i <= syncLen; i++) {
            final int step = i;
            later(s, syncStart + i, () -> {
                int pct = Math.min(100, step * 100 / syncLen);
                if (s.hud != null && s.hud.isValid()) {
                    TextComponent.Builder b = Component.text();
                    int linesShown = Math.min(boot.length, 1 + step / 6);
                    for (int l = 0; l < linesShown; l++) {
                        if (l > 0) b.append(Component.newline());
                        b.append(Component.text(boot[l], l == 0 ? TextColor.color(ORANGE) : TextColor.color(0xB0B0B0)));
                    }
                    b.append(Component.newline()).append(Component.newline())
                            .append(Component.text("НЕЙРОСИНХРОНИЗАЦИЯ ", TextColor.color(ORANGE)).decorate(TextDecoration.BOLD));
                    for (int k = 0; k < 12; k++)
                        b.append(Component.text("▰", k < pct * 12 / 100 ? TextColor.color(ORANGE) : TextColor.color(0x3C3C3C)));
                    b.append(Component.text(" " + pct + "%", NamedTextColor.WHITE));
                    if (rnd.nextInt(7) == 0) b.append(Component.text("  " + NOISE.charAt(rnd.nextInt(NOISE.length())), NamedTextColor.WHITE));
                    s.hud.text(b.build());
                }
                if (step % 10 == 0) sound(p, "entity.warden.heartbeat", 0.6f, 1.0f + step / 70f);
                if (step % 4 == 0) world.spawnParticle(Particle.END_ROD,
                        new Location(world, CX, LY + CAP_H + 0.35, CZ - 0.2), 2, 0.4, 0.05, 0.8, 0.0);
            });
        }
        // white-out: the visor floods with light — it continues as the white void on the deck
        later(s, syncStart + syncLen + 2, () -> {
            if (s.hud != null && s.hud.isValid()) s.hud.remove();
            s.visor.setBlock(Material.WHITE_CONCRETE.createBlockData());
            s.visor.setBrightness(new Display.Brightness(15, 15));
            s.visor.setInterpolationDelay(0);
            s.visor.setInterpolationDuration(4);
            s.visor.setTransformation(new Transformation(new Vector3f(-0.6f, 0.36f, -0.6f), new org.joml.Quaternionf(),
                    new Vector3f(1.2f, 0.04f, 1.2f), new org.joml.Quaternionf()));
            sound(p, "block.respawn_anchor.charge", 1f, 1.4f);
            sound(p, "block.beacon.power_select", 0.8f, 1.8f);
        });
        later(s, syncStart + syncLen + 10, () -> {
            p.setSpectatorTarget(null);
            GameMode mode = s.prevMode;
            cleanup(s);
            // stay a spectator for the hyperjump that follows (creative players keep creative)
            if (mode == GameMode.CREATIVE) p.setGameMode(mode);
            for (Player other : Bukkit.getOnlinePlayers()) if (!other.equals(p)) other.showPlayer(plugin, p);
            plugin.sendToHub(p, true);
        });
    }

    private static double lerpAngle(double a, double b, double t) {
        double d = ((b - a + 540) % 360) - 180;
        return a + d * t;
    }

    private void lieDownOld(Session s) {
        Player p = s.player;
        Location start = standPoint().add(0, 1.62, 0);
        org.bukkit.entity.ItemDisplay cam = world.spawn(start, org.bukkit.entity.ItemDisplay.class, d -> {
            plugin.prepare(d);
            d.setTeleportDuration(2);
        });
        s.camera = cam;
        s.entities.add(cam);
        p.setGameMode(GameMode.SPECTATOR);
        HudBoard.notice(p, Component.text("Ложимся в VR-капсулу...", NamedTextColor.GRAY));
        sound(p, "item.armor.equip_elytra", 0.8f, 0.8f);
        final int duration = 46;
        final double[] p0 = {VX, LY + 1.62, VZ};
        final double[] p1 = {CX, LY + 2.15, CZ + CAP_L / 2 + 0.25};
        final double[] p2 = {CX, LY + CAP_H + 0.32, CZ + CAP_L / 2 - 0.38};
        for (int t = 1; t <= duration; t++) {
            final int step = t;
            later(s, t, () -> {
                if (step == 2) p.setSpectatorTarget(cam);
                double e = ease(step / (double) duration);
                double a = (1 - e) * (1 - e), b = 2 * (1 - e) * e, c = e * e;
                double x = a * p0[0] + b * p1[0] + c * p2[0];
                double y = a * p0[1] + b * p1[1] + c * p2[1];
                double z = a * p0[2] + b * p1[2] + c * p2[2];
                float pitch = (float) (12 + (-89 - 12) * smooth(step / (double) duration));
                cam.teleport(new Location(world, x, y, z, 180f, pitch));
            });
        }
        // 3. the lid closes over the player, synchronisation runs
        later(s, duration + 4, () -> {
            swingLid(s, 0f, 24);
            sound(p, "block.piston.contract", 1f, 0.6f);
            sound(p, "block.fire.extinguish", 0.6f, 0.9f);
        });
        for (int i = 0; i <= 50; i++) {
            final int step = i;
            later(s, duration + 10 + i, () -> {
                int pct = step * 2;
                TextComponent.Builder bar = Component.text().append(Component.text("Нейросинхронизация  ", NamedTextColor.GRAY));
                for (int k = 0; k < 10; k++)
                    bar.append(Component.text("▰", k < pct / 10 ? TextColor.color(ORANGE) : TextColor.color(0x3C3C3C)));
                bar.append(Component.text("  " + pct + "%", NamedTextColor.WHITE));
                HudBoard.notice(p, bar.build());
                if (step % 10 == 0) sound(p, "entity.warden.heartbeat", 0.6f, 1.0f + step / 60f);
                if (step % 3 == 0) world.spawnParticle(Particle.END_ROD,
                        new Location(world, CX, LY + CAP_H + 0.35, CZ), 2, 0.4, 0.05, 0.9, 0.0);
            });
        }
        later(s, duration + 44, () -> {
            decryptTitle(p, "◈ ЗАГРУЗКА В NEYROCRAFT ◈", ORANGE,
                    Component.text("Сознание переносится на главную палубу", NamedTextColor.GRAY), 14, 1200);
            sound(p, "block.portal.travel", 0.25f, 1.4f);
        });
        later(s, duration + 58, () -> p.addPotionEffect(new PotionEffect(PotionEffectType.BLINDNESS, 30, 0, false, false, false)));
        // 4. wake up on the main deck
        later(s, duration + 66, () -> {
            p.setSpectatorTarget(null);
            GameMode mode = s.prevMode;
            cleanup(s);
            p.setGameMode(mode);
            for (Player other : Bukkit.getOnlinePlayers()) if (!other.equals(p)) other.showPlayer(plugin, p);
            plugin.sendToHub(p, true);
        });
    }

    private static double ease(double t) {
        return t < 0.5 ? 4 * t * t * t : 1 - Math.pow(-2 * t + 2, 3) / 2;
    }

    private static double smooth(double t) {
        t = Math.max(0, Math.min(1, t));
        return t * t * (3 - 2 * t);
    }

    Location standPoint() {
        return new Location(world, VX, LY, VZ, 180f, 8f);
    }

    private void kick(Session s, String reason) {
        cleanup(s);
        s.player.kick(Component.text()
                .append(Component.text(P, TextColor.color(ORANGE)).decorate(TextDecoration.BOLD))
                .append(Component.newline()).append(Component.newline())
                .append(Component.text(reason, NamedTextColor.RED))
                .build());
    }

    void quit(Player p) {
        emailChallenge.clear(p);
        authed.remove(p.getUniqueId());
        Session s = sessions.get(p.getUniqueId());
        if (s != null) cleanup(s);
    }

    private void cleanup(Session s) {
        sessions.remove(s.player.getUniqueId());
        if (s.bar != null) s.player.hideBossBar(s.bar);
        if (s.seat != null && s.seat.isValid()) s.seat.eject();
        if (s.camera != null && s.player.getGameMode() == GameMode.SPECTATOR
                && s.camera.equals(s.player.getSpectatorTarget())) s.player.setSpectatorTarget(null);
        for (Entity e : s.entities) if (e.isValid()) e.remove();
        s.entities.clear();
    }

    private void registerIpFail(String ip) {
        long now = System.currentTimeMillis();
        Deque<Long> q = ipFails.computeIfAbsent(ip, k -> new ArrayDeque<>());
        q.addLast(now);
        while (!q.isEmpty() && now - q.peekFirst() > IP_FAIL_WINDOW_MS) q.removeFirst();
        if (q.size() >= IP_FAIL_LIMIT) {
            ipLocks.put(ip, now + IP_LOCK_MS);
            q.clear();
        }
    }

    // ------------------------------------------------------------------ tick

    private void tick() {
        tick++;
        if (sessions.isEmpty()) return;

        if (tick % 2 == 0) {
            for (Column c : columns) {
                if (c.display == null || !c.display.isValid()) continue;
                c.head += c.speed * 2;
                if (c.head - c.trail > c.chars.length) {
                    c.head = -rnd.nextInt(10);
                    c.speed = 0.25 + rnd.nextDouble() * 0.35;
                }
                renderColumn(c);
            }
        }

        // the ship is flying: stars rush past the windows
        if (tick % 2 == 0) SpaceDecor.streaks(world, new Location(world, LX, LY + 2, LZ), 45, 28, 8, 5, rnd);

        for (Session s : new ArrayList<>(sessions.values())) {
            Player p = s.player;
            if (!p.isOnline()) { cleanup(s); continue; }
            // keep the passenger on their spot in front of the capsule (until they lie down)
            if (s.phase != Phase.DONE && (!p.getWorld().equals(world) || p.getLocation().distanceSquared(standPoint()) > 0.09)) {
                Location back = standPoint();
                back.setYaw(p.getLocation().getYaw());
                back.setPitch(p.getLocation().getPitch());
                p.teleport(back);
            }
            if (tick % 80 == 0) sound(p, "block.beacon.ambient", 0.35f, 0.5f);   // engine hum
            int total = s.totalChars();
            if (s.revealed < total) {
                s.revealed = Math.min(total, s.revealed + 3);
                if (tick % 2 == 0) sound(p, "block.note_block.hat", 0.2f, 1.8f);
            }
            if (s.phase == Phase.CHECKING) s.checkingTicks++;
            if (tick % 2 == 0 && s.screen != null && s.screen.isValid()) s.screen.text(renderScreen(s));
            if (tick % 5 == 0) {
                if (s.panelLeft != null && s.panelLeft.isValid()) s.panelLeft.text(renderLeftPanel(s));
                if (s.panelRight != null && s.panelRight.isValid()) s.panelRight.text(renderRightPanel(s));
            }
            for (Map.Entry<Character, TextDisplay> e : s.keyLabel.entrySet()) {
                int f = s.keyFlash.getOrDefault(e.getKey(), 0);
                if (f > 0) s.keyFlash.put(e.getKey(), f - 1);
                if (f == 4 || f == 1) {
                    boolean lit = f == 4;
                    e.getValue().text(keyText(e.getKey(), lit));
                    e.getValue().setBackgroundColor(lit ? Color.fromARGB(240, 255, 140, 0) : Color.fromARGB(200, 20, 12, 4));
                }
            }

            if (tick % 20 == 0 && s.phase != Phase.DONE) {
                s.secondsLeft--;
                if (s.secondsLeft <= 0) {
                    kick(s, "Сессия симуляции истекла. Переподключись и войди снова.");
                    continue;
                }
                s.bar.name(barTitle(s));
                s.bar.progress(Math.max(0f, s.secondsLeft / (float) TIMEOUT_SECONDS));
                if (s.secondsLeft <= 20) s.bar.color(BossBar.Color.RED);
            }
            if (tick % 30 == 0 && s.phase != Phase.DONE) sound(p, "entity.warden.heartbeat", 0.45f, 1.0f);
            if (tick % 6 == 0 && s.phase != Phase.DONE) {
                world.spawnParticle(Particle.DUST, new Location(world, CX, LY + 0.9, CZ), 2, 0.6, 0.2, 1.1, 0,
                        new Particle.DustOptions(Color.fromRGB(255, 140, 0), 0.6f));
            }
        }
    }

    private Component renderScreen(Session s) {
        TextComponent.Builder b = Component.text();
        int left = s.revealed;
        for (int i = 0; i < s.lines.size() && left > 0; i++) {
            String line = s.lines.get(i);
            String shown = line.substring(0, Math.min(line.length(), left));
            left -= line.length() + 1;
            if (i > 0) b.append(Component.newline());
            b.append(Component.text(shown, TextColor.color(s.colors.get(i))));
        }
        boolean cursorOn = (tick / 6) % 2 == 0;
        if (!s.logDone()) {
            b.append(Component.text(cursorOn ? " █" : "  ", TextColor.color(ORANGE)));
            return b.build();
        }
        b.append(Component.newline()).append(Component.text("──────────────────────────────────", TextColor.color(0x5A3200)));
        switch (s.phase) {
            case CAPTCHA -> {
                b.append(Component.newline())
                        .append(Component.text("ПРОВЕРКА НЕЙРОИНТЕРФЕЙСА", TextColor.color(ORANGE)).decorate(TextDecoration.BOLD));
                b.append(Component.newline()).append(Component.text("синхро-код:  ", NamedTextColor.GRAY));
                for (int i = 0; i < s.code.length(); i++) {
                    char ch = s.code.charAt(i);
                    if (rnd.nextInt(14) == 0) ch = NOISE.charAt(rnd.nextInt(NOISE.length()));   // glitch
                    b.append(Component.text(ch + "  ", NamedTextColor.WHITE).decorate(TextDecoration.BOLD));
                }
                b.append(Component.newline()).append(Component.text("ввод:        ", NamedTextColor.GRAY));
                for (int i = 0; i < s.code.length(); i++) {
                    if (i < s.typed.length()) b.append(Component.text("■  ", TextColor.color(ORANGE)));
                    else if (i == s.typed.length() && cursorOn) b.append(Component.text("▌  ", NamedTextColor.WHITE));
                    else b.append(Component.text("_  ", NamedTextColor.DARK_GRAY));
                }
            }
            case CHECKING -> {
                int filled = Math.min(10, s.checkingTicks / 2);
                b.append(Component.newline()).append(Component.text("ВЕРИФИКАЦИЯ  ", TextColor.color(ORANGE)).decorate(TextDecoration.BOLD));
                for (int i = 0; i < 10; i++)
                    b.append(Component.text("▰", i < filled ? TextColor.color(ORANGE) : TextColor.color(0x3C3C3C)));
            }
            case DONE -> b.append(Component.newline())
                    .append(Component.text("ДОСТУП РАЗРЕШЁН", NamedTextColor.GREEN).decorate(TextDecoration.BOLD));
            default -> {
            }
        }
        if (!s.status.isEmpty()) {
            b.append(Component.newline()).append(Component.text("» " + s.status, TextColor.color(s.statusColor)));
        }
        if (s.phase == Phase.PASSWORD) {
            b.append(Component.newline()).append(Component.text("  окно закрылось? нажми ⌨ на голоклавиатуре", NamedTextColor.DARK_GRAY));
        }
        b.append(Component.text(cursorOn ? " █" : "  ", TextColor.color(ORANGE)));
        return b.build();
    }

    private Component renderLeftPanel(Session s) {
        TextComponent.Builder b = Component.text()
                .append(Component.text("◈ NEYRO-1 · СИСТЕМЫ", TextColor.color(ORANGE)).decorate(TextDecoration.BOLD));
        String[] names = {"реактор", "щиты   ", "двигат.", "связь  "};
        for (String n : names) {
            int v = 40 + rnd.nextInt(60);
            b.append(Component.newline()).append(Component.text(n + " ", NamedTextColor.GRAY)).append(meter(v))
                    .append(Component.text(" " + v + "%", NamedTextColor.WHITE));
        }
        b.append(Component.newline()).append(Component.text("пинг " + s.player.getPing() + " мс", NamedTextColor.DARK_GRAY));
        return b.build();
    }

    private Component renderRightPanel(Session s) {
        TextComponent.Builder b = Component.text()
                .append(Component.text("◈ СИГНАЛ МОЗГА", TextColor.color(ORANGE)).decorate(TextDecoration.BOLD))
                .append(Component.newline());
        String bars = "▁▂▃▄▅▆▇█";
        for (int i = 0; i < 18; i++) {
            double wave = Math.sin((tick * 0.3) + i * 0.7) * 0.5 + 0.5;
            int idx = Math.max(0, Math.min(7, (int) (wave * 6) + rnd.nextInt(2)));
            b.append(Component.text(String.valueOf(bars.charAt(idx)), TextColor.color(mix(0x5A3200, ORANGE, wave))));
        }
        b.append(Component.newline()).append(Component.text("субъект ", NamedTextColor.GRAY))
                .append(Component.text(s.player.getName(), NamedTextColor.WHITE));
        b.append(Component.newline()).append(Component.text("фаза    ", NamedTextColor.GRAY))
                .append(Component.text(phaseName(s.phase), TextColor.color(ORANGE)));
        return b.build();
    }

    private static String phaseName(Phase ph) {
        return switch (ph) {
            case BOOT -> "загрузка";
            case CAPTCHA -> "проверка";
            case PASSWORD -> "ключ";
            case CHECKING -> "верификация";
            case DONE -> "синхронизирован";
        };
    }

    private Component meter(int v) {
        TextComponent.Builder b = Component.text();
        int f = v / 10;
        for (int i = 0; i < 10; i++) b.append(Component.text("▮", i < f ? TextColor.color(ORANGE) : TextColor.color(0x2A2A2A)));
        return b.build();
    }

    private Component barTitle(Session s) {
        String what = switch (s.phase) {
            case BOOT -> "загрузка терминала";
            case CAPTCHA -> "проверка нейроинтерфейса";
            case PASSWORD, CHECKING -> s.registered ? "ожидание ключа доступа" : "создание ключа доступа";
            case DONE -> "готово";
        };
        return Component.text()
                .append(Component.text("◈ СИНХРОНИЗАЦИЯ ◈  ", TextColor.color(ORANGE)).decorate(TextDecoration.BOLD))
                .append(Component.text(what + " · " + s.secondsLeft + " сек", NamedTextColor.GRAY))
                .build();
    }

    // --------------------------------------------------------------- commands

    /** /login, /register, /changepassword, /neyroauth. Returns true when handled. */
    boolean command(CommandSender sender, String cmd, String[] args) {
        switch (cmd) {
            case "email" -> {
                if (!(sender instanceof Player p)) return true;
                Session s = sessions.get(p.getUniqueId());
                boolean registration = s != null && s.pendingRegistrationHash != null && s.phase == Phase.PASSWORD;
                if (!registration && !isAuthed(p)) { msg(p, "Сначала войди или зарегистрируйся.", NamedTextColor.RED); return true; }
                if (!registration && !store.email(p.getName()).isEmpty()) { msg(p, "Почта уже подтверждена. Для изменения обратись к администрации.", NamedTextColor.RED); return true; }
                if (args.length != 1) { msg(p, "Использование: /email <адрес>", NamedTextColor.YELLOW); return true; }
                emailChallenge.send(p,args[0],registration ? "registration" : "bind");
                return true;
            }
            case "emailcode" -> {
                if (!(sender instanceof Player p) || args.length != 1) return true;
                Session s=sessions.get(p.getUniqueId());
                boolean registration=s != null && s.pendingRegistrationHash != null && s.phase == Phase.PASSWORD;
                if (!registration && !isAuthed(p)) return true;
                String email=emailChallenge.verify(p,args[0],registration ? "registration" : "bind");
                if(email==null) return true;
                if(registration) {
                    if(store.exists(p.getName())) {kick(s,"Этот ник уже зарегистрирован");return true;}
                    try { store.putVerified(p.getName(),s.pendingRegistrationHash,s.ip,email); }
                    catch (java.io.IOException ex) { plugin.getLogger().severe("Could not save registration"); msg(p,"Не удалось сохранить аккаунт. Повтори подтверждение позже.",NamedTextColor.RED); return true; }
                    s.pendingRegistrationHash=null;
                    msg(p,"Почта подтверждена, регистрация завершена!",NamedTextColor.GREEN);
                    granted(s);
                } else {store.setEmail(p.getName(),email); msg(p,"Почта подтверждена и привязана.",NamedTextColor.GREEN);}
                return true;
            }
            case "recover" -> {
                if (!(sender instanceof Player p)) return true;
                Session s=sessions.get(p.getUniqueId());
                if(s==null || s.phase!=Phase.PASSWORD || !s.registered) {msg(p,"Восстановление доступно после капчи для зарегистрированного игрока.",NamedTextColor.RED);return true;}
                String email=store.email(p.getName());
                if(email.isEmpty()) {msg(p,"Почта не привязана. Обратись к администрации.",NamedTextColor.RED);return true;}
                emailChallenge.send(p,email,"recover");
                msg(p,"После письма введи /resetpass <код> <новый пароль> <повтор>.",NamedTextColor.YELLOW);
                return true;
            }
            case "resetpass" -> {
                if (!(sender instanceof Player p)) return true;
                if (args.length!=3) {msg(p,"Формат: /resetpass <код> <новый пароль> <повтор>",NamedTextColor.YELLOW);return true;}
                Session s=sessions.get(p.getUniqueId());
                if(s==null || s.phase!=Phase.PASSWORD || !s.registered) return true;
                if(!args[1].equals(args[2]) || args[1].length()<5 || args[1].length()>32 || args[1].equalsIgnoreCase(p.getName())) {msg(p,"Пароли должны совпадать и содержать 5–32 символа.",NamedTextColor.RED);return true;}
                String email=emailChallenge.verify(p,args[0],"recover");
                if(email==null || !email.equals(store.email(p.getName()))) return true;
                s.phase=Phase.CHECKING;
                Bukkit.getScheduler().runTaskAsynchronously(plugin, () -> {String h=AccountStore.hash(args[1]); Bukkit.getScheduler().runTask(plugin, () -> {if(!alive(s)) return; store.setHash(p.getName(),h);msg(p,"Пароль изменён.",NamedTextColor.GREEN);granted(s);});});
                return true;
            }
            case "login" -> {
                if (!(sender instanceof Player p)) return true;
                Session s = sessions.get(p.getUniqueId());
                if (isAuthed(p)) { msg(p, "Ты уже синхронизирован.", NamedTextColor.GRAY); return true; }
                if (s == null) return true;
                if (!s.registered) { msg(p, "Профиль не найден. Создай ключ в окне терминала.", NamedTextColor.RED); return true; }
                if (s.phase != Phase.PASSWORD) { msg(p, "Сначала пройди проверку нейроинтерфейса на голоклавиатуре.", NamedTextColor.YELLOW); return true; }
                if (args.length < 1 || args.length > 2) { msg(p, "Формат: /login <ключ> [повтор ключа]", NamedTextColor.RED); return true; }
                if (args.length == 2 && !args[0].equals(args[1])) { msg(p, "Пароли не совпадают.", NamedTextColor.RED); return true; }
                p.closeDialog();
                submit(s, args[0], null);
                return true;
            }
            case "register" -> {
                if (!(sender instanceof Player p)) return true;
                Session s = sessions.get(p.getUniqueId());
                if (isAuthed(p) || s == null) return true;
                if (s.registered) { msg(p, "Профиль уже существует. Используй /login <ключ>.", NamedTextColor.RED); return true; }
                if (s.phase != Phase.PASSWORD) { msg(p, "Сначала пройди проверку нейроинтерфейса на голоклавиатуре.", NamedTextColor.YELLOW); return true; }
                if (args.length < 2) { msg(p, "Формат: /register <ключ> <ключ>", NamedTextColor.RED); return true; }
                p.closeDialog();
                submit(s, args[0], args[1]);
                return true;
            }
            case "changepassword" -> {
                if (!(sender instanceof Player p) || !isAuthed(p)) return true;
                if (args.length < 2) { msg(p, "Формат: /changepassword <старый ключ> <новый ключ>", NamedTextColor.RED); return true; }
                String oldKey = args[0], newKey = args[1];
                if (newKey.length() < 5 || newKey.length() > 32 || newKey.equalsIgnoreCase(p.getName())) {
                    msg(p, "Новый ключ: от 5 до 32 символов и не совпадает с ником.", NamedTextColor.RED);
                    return true;
                }
                AccountStore.Account acc = store.get(p.getName());
                if (acc == null) return true;
                Bukkit.getScheduler().runTaskAsynchronously(plugin, () -> {
                    boolean ok = AccountStore.verify(oldKey, acc.hash());
                    String h = ok ? AccountStore.hash(newKey) : null;
                    Bukkit.getScheduler().runTask(plugin, () -> {
                        if (!ok) { msg(p, "Старый ключ неверный.", NamedTextColor.RED); return; }
                        store.setHash(p.getName(), h);
                        msg(p, "Ключ доступа изменён ✔", NamedTextColor.GREEN);
                    });
                });
                return true;
            }
            case "neyroauth" -> {
                if (!sender.hasPermission("neyrohub.admin")) return true;
                if (args.length >= 2 && args[0].equalsIgnoreCase("unregister")) {
                    boolean ok = store.remove(args[1]);
                    sender.sendMessage(ok ? "Профиль " + args[1] + " удалён" : "Профиль не найден");
                    Player t = Bukkit.getPlayerExact(args[1]);
                    if (ok && t != null) t.kick(Component.text(P + " · профиль сброшен администратором"));
                    return true;
                }
                if (args.length >= 3 && args[0].equalsIgnoreCase("setpassword")) {
                    String name = args[1], pw = args[2];
                    if (!store.exists(name)) { sender.sendMessage("Профиль не найден"); return true; }
                    Bukkit.getScheduler().runTaskAsynchronously(plugin, () -> {
                        String h = AccountStore.hash(pw);
                        Bukkit.getScheduler().runTask(plugin, () -> {
                            store.setHash(name, h);
                            sender.sendMessage("Ключ для " + name + " изменён");
                        });
                    });
                    return true;
                }
                if (args.length >= 1 && args[0].equalsIgnoreCase("info")) {
                    sender.sendMessage("NeyroAuth: профилей " + store.size() + ", в терминале " + sessions.size());
                    return true;
                }
                sender.sendMessage("/neyroauth info | unregister <ник> | setpassword <ник> <ключ>");
                return true;
            }
            default -> {
                return false;
            }
        }
    }

    private static void msg(Player p, String text, NamedTextColor color) {
        p.sendMessage(Component.text()
                .append(Component.text("[", NamedTextColor.DARK_GRAY))
                .append(Component.text("NEYRO", TextColor.color(ORANGE)))
                .append(Component.text("//", NamedTextColor.DARK_GRAY))
                .append(Component.text("SIM", NamedTextColor.GRAY))
                .append(Component.text("] ", NamedTextColor.DARK_GRAY))
                .append(Component.text(text, color))
                .build());
    }

    // ---------------------------------------------------------------- guards

    private boolean locked(Player p) {
        return !authed.contains(p.getUniqueId());
    }

    @EventHandler(priority = EventPriority.HIGHEST)
    public void onPreLogin(AsyncPlayerPreLoginEvent e) {
        String ip = e.getAddress().getHostAddress();
        Long until = ipLocks.get(ip);
        if (until != null && until > System.currentTimeMillis()) {
            long min = Math.max(1, (until - System.currentTimeMillis()) / 60_000);
            e.disallow(AsyncPlayerPreLoginEvent.Result.KICK_OTHER, Component.text(P + "\n\nСлишком много неверных ключей.\nПопробуй через " + min + " мин.", NamedTextColor.RED));
            return;
        }
        Player online = Bukkit.getPlayerExact(e.getName());
        if (online == null) {
            for (Player p : Bukkit.getOnlinePlayers()) if (p.getName().equalsIgnoreCase(e.getName())) online = p;
        }
        if (online != null) {
            e.disallow(AsyncPlayerPreLoginEvent.Result.KICK_OTHER, Component.text(P + "\n\nСубъект с этим ником уже в симуляции.", NamedTextColor.RED));
            return;
        }
        AccountStore.Account acc = store.get(e.getName());
        if (acc != null && !acc.realName().equals(e.getName())) {
            e.disallow(AsyncPlayerPreLoginEvent.Result.KICK_OTHER, Component.text(P + "\n\nЭтот профиль зарегистрирован как " + acc.realName()
                    + ".\nЗайди с ником " + acc.realName() + ".", NamedTextColor.RED));
        }
    }

    @EventHandler(priority = EventPriority.LOWEST)
    public void onJoinHide(PlayerJoinEvent e) {
        Player p = e.getPlayer();
        authed.remove(p.getUniqueId());
        if (restoreRestartSession(p)) return;
        for (Player other : Bukkit.getOnlinePlayers()) {
            if (other.equals(p)) continue;
            other.hidePlayer(plugin, p);                       // nobody sees a subject before login
            if (locked(other)) p.hidePlayer(plugin, other);
        }
    }

    @EventHandler(priority = EventPriority.LOWEST)
    public void onChat(AsyncChatEvent e) {
        Player p = e.getPlayer();
        if (!locked(p)) return;
        e.setCancelled(true);
        String text = PlainTextComponentSerializer.plainText().serialize(e.message()).trim();
        Bukkit.getScheduler().runTask(plugin, () -> {
            Session s = sessions.get(p.getUniqueId());
            if (s == null) return;
            if (s.phase == Phase.CAPTCHA) {
                msg(p, "Набери синхро-код на голоклавиатуре справа от монитора.", NamedTextColor.YELLOW);
            } else {
                msg(p, "Чат недоступен до синхронизации.", NamedTextColor.RED);
            }
        });
    }

    private static final Set<String> ALLOWED = Set.of("/login", "/l", "/log", "/register", "/reg", "/regiser", "/email", "/emailcode", "/recover", "/resetpass");

    @EventHandler(priority = EventPriority.LOWEST)
    public void onCommand(PlayerCommandPreprocessEvent e) {
        if (!locked(e.getPlayer())) return;
        String first = e.getMessage().split(" ")[0].toLowerCase(Locale.ROOT);
        if (!ALLOWED.contains(first)) {
            e.setCancelled(true);
            msg(e.getPlayer(), "Сначала пройди идентификацию.", NamedTextColor.RED);
        }
    }

    @EventHandler(priority = EventPriority.LOWEST)
    public void onMove(PlayerMoveEvent e) {
        if (!locked(e.getPlayer())) return;
        Location from = e.getFrom(), to = e.getTo();
        if (from.getX() != to.getX() || from.getY() != to.getY() || from.getZ() != to.getZ()) {
            Location back = from.clone();
            back.setYaw(to.getYaw());
            back.setPitch(to.getPitch());
            e.setTo(back);
        }
    }

    @EventHandler(priority = EventPriority.LOWEST)
    public void onDismount(EntityDismountEvent e) {
        if (!(e.getEntity() instanceof Player p)) return;
        Session s = sessions.get(p.getUniqueId());
        if (s != null && s.phase != Phase.DONE && e.getDismounted().equals(s.seat)) e.setCancelled(true);
    }

    @EventHandler(priority = EventPriority.LOWEST)
    public void onInteractEntity(PlayerInteractEntityEvent e) {
        Player p = e.getPlayer();
        Session s = sessions.get(p.getUniqueId());
        if (s != null && e.getHand() == org.bukkit.inventory.EquipmentSlot.HAND) {
            Character key = s.keyByEntity.get(e.getRightClicked().getUniqueId());
            if (key != null) pressKey(s, key);
        }
        if (locked(p)) e.setCancelled(true);
    }

    @EventHandler(priority = EventPriority.LOWEST)
    public void onAttackEntity(PrePlayerAttackEntityEvent e) {
        Player p = e.getPlayer();
        Session s = sessions.get(p.getUniqueId());
        if (s != null) {
            Character key = s.keyByEntity.get(e.getAttacked().getUniqueId());
            if (key != null) pressKey(s, key);
        }
        if (locked(p)) e.setCancelled(true);
    }

    @EventHandler(priority = EventPriority.LOWEST)
    public void onInteract(PlayerInteractEvent e) {
        if (locked(e.getPlayer())) e.setCancelled(true);
    }

    @EventHandler(priority = EventPriority.LOWEST)
    public void onDrop(PlayerDropItemEvent e) {
        if (locked(e.getPlayer())) e.setCancelled(true);
    }

    @EventHandler(priority = EventPriority.LOWEST)
    public void onPickup(EntityPickupItemEvent e) {
        if (e.getEntity() instanceof Player p && locked(p)) e.setCancelled(true);
    }

    @EventHandler(priority = EventPriority.LOWEST)
    public void onInventoryClick(InventoryClickEvent e) {
        if (e.getWhoClicked() instanceof Player p && locked(p)) e.setCancelled(true);
    }

    @EventHandler(priority = EventPriority.LOWEST)
    public void onInventoryOpen(InventoryOpenEvent e) {
        if (e.getPlayer() instanceof Player p && locked(p)) e.setCancelled(true);
    }

    @EventHandler(priority = EventPriority.LOWEST)
    public void onSwap(PlayerSwapHandItemsEvent e) {
        if (locked(e.getPlayer())) e.setCancelled(true);
    }

    @EventHandler(priority = EventPriority.LOWEST)
    public void onConsume(PlayerItemConsumeEvent e) {
        if (locked(e.getPlayer())) e.setCancelled(true);
    }

    @EventHandler(priority = EventPriority.LOWEST)
    public void onBreak(BlockBreakEvent e) {
        if (locked(e.getPlayer())) e.setCancelled(true);
    }

    @EventHandler(priority = EventPriority.LOWEST)
    public void onPlace(BlockPlaceEvent e) {
        if (locked(e.getPlayer())) e.setCancelled(true);
    }

    @EventHandler(priority = EventPriority.LOWEST)
    public void onDamage(EntityDamageEvent e) {
        if (e.getEntity() instanceof Player p && locked(p)) e.setCancelled(true);
    }

    @EventHandler(priority = EventPriority.LOWEST)
    public void onDamageOther(EntityDamageByEntityEvent e) {
        if (e.getDamager() instanceof Player p && locked(p)) e.setCancelled(true);
    }

    @EventHandler(priority = EventPriority.LOWEST)
    public void onHunger(FoodLevelChangeEvent e) {
        if (e.getEntity() instanceof Player p && locked(p)) e.setCancelled(true);
    }

    // --------------------------------------------------------------- effects

    /** A title that starts as noise and "decrypts" into the real text. */
    private void decryptTitle(Player p, String text, int color, Component subtitle, int steps, long stayMs) {
        for (int i = 0; i <= steps; i++) {
            final int step = i;
            Bukkit.getScheduler().runTaskLater(plugin, () -> {
                if (!p.isOnline()) return;
                double solved = step / (double) steps;
                StringBuilder sb = new StringBuilder();
                for (int k = 0; k < text.length(); k++) {
                    char ch = text.charAt(k);
                    boolean keep = ch == ' ' || (k + 1) / (double) text.length() <= solved;
                    sb.append(keep ? ch : NOISE.charAt(rnd.nextInt(NOISE.length())));
                }
                long stay = step == steps ? stayMs : 200;
                p.showTitle(Title.title(
                        Component.text(sb.toString(), TextColor.color(color)).decorate(TextDecoration.BOLD),
                        step == steps ? subtitle : Component.empty(),
                        Title.Times.times(Duration.ZERO, Duration.ofMillis(stay), Duration.ofMillis(step == steps ? 600 : 0))));
            }, i);
        }
    }

    private static void sound(Player p, String key, float volume, float pitch) {
        p.playSound(Sound.sound(Key.key("minecraft", key), Sound.Source.MASTER, volume, pitch));
    }

    private static int mix(int a, int b, double t) {
        int r = (int) Math.round(((a >> 16) & 0xFF) * (1 - t) + ((b >> 16) & 0xFF) * t);
        int g = (int) Math.round(((a >> 8) & 0xFF) * (1 - t) + ((b >> 8) & 0xFF) * t);
        int bl = (int) Math.round((a & 0xFF) * (1 - t) + (b & 0xFF) * t);
        return (r << 16) | (g << 8) | bl;
    }

    /** Keeps passwords out of the server log ("issued server command: /login ..."). */
    private static final class PasswordLogFilter extends AbstractFilter {
        private static final String[] HIDDEN = {"/login ", "/l ", "/log ", "/register ", "/reg ", "/regiser ", "/changepassword ",
                "/neyroauth setpassword ", "/email ", "/emailcode ", "/recover ", "/resetpass ",
                "/neyrohub:email ", "/neyrohub:emailcode ", "/neyrohub:recover ", "/neyrohub:resetpass ",
                "/neyrohub:login ", "/neyrohub:register ", "/neyrohub:changepassword "};

        @Override
        public Result filter(LogEvent event) {
            if (event == null || event.getMessage() == null) return Result.NEUTRAL;
            String m = event.getMessage().getFormattedMessage();
            if (m == null || !m.contains("issued server command")) return Result.NEUTRAL;
            String lower = m.toLowerCase(Locale.ROOT);
            for (String h : HIDDEN) if (lower.contains(h)) return Result.DENY;
            return Result.NEUTRAL;
        }
    }
}
