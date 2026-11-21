package net.neyrocraft.hub;

import io.papermc.paper.math.Position;
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
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.Particle;
import org.bukkit.World;
import org.bukkit.block.data.BlockData;
import org.bukkit.entity.BlockDisplay;
import org.bukkit.entity.Display;
import org.bukkit.entity.Entity;
import org.bukkit.entity.Player;
import org.bukkit.scheduler.BukkitTask;
import org.bukkit.util.Transformation;
import org.joml.Quaternionf;
import org.joml.Vector3f;

import java.time.Duration;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.UUID;

/**
 * "Memory loading" arrival on the main deck: the player stands in a white void with
 * floating fragments while a DNA helix spins; then a wave rolls outward and renders the
 * deck — first as white ghost blocks, then for real. Only this player sees the fakes.
 */
final class AnimusLoader {

    private static final double OX = 0.5, OZ = 0.5;
    private static final int TOTAL_TICKS = 130;
    private static final int WAVE_START = 34;
    private static final double WAVE_SPEED = 0.42;      // blocks per tick
    private static final double GHOST_LAG = 3.5;        // real blocks follow the ghost wave
    private static final double SHELL_R = 20;

    private record Cell(int x, int y, int z, BlockData real, double dist) {}

    /** A column of dark code falling through the white void. */
    private static final class Rain {
        org.bukkit.entity.TextDisplay display;
        char[] chars = new char[14];
        double head;
        double speed;
    }

    /** Camera key frame; yaw is not wrapped so the camera can spin. */
    private record Frame(double x, double y, double z, double yaw, double pitch, int tick) {}

    private static final String GLYPHS = "01アイウエオカキクケコサシスセソ<>/#NEYRO";
    private static final String NOISE = "█▓▒░#@$%&01<>/\\";

    private final NeyroHub plugin;
    private final World hub;
    private final int floorY;
    private final Random rnd = new Random();
    private final BlockData white = Material.WHITE_CONCRETE.createBlockData();
    private final BlockData ghost = Material.WHITE_STAINED_GLASS.createBlockData();
    private final BlockData air = Material.AIR.createBlockData();

    private List<Cell> cells = new ArrayList<>();       // every real block of the deck, nearest first
    private List<Cell> shell = new ArrayList<>();       // the white void sphere
    private final Map<UUID, Session> sessions = new HashMap<>();

    private final class Session {
        final Player player;
        BukkitTask task;
        int t = 0;
        int ghostIdx = 0, realIdx = 0;
        boolean shellGone = false;
        final List<Entity> hidden = new ArrayList<>();
        final List<Player> hiddenPlayers = new ArrayList<>();
        final List<BlockDisplay> fragments = new ArrayList<>();
        final List<Rain> rain = new ArrayList<>();
        org.bukkit.entity.TextDisplay holo;
        org.bukkit.entity.ItemDisplay camera;
        GameMode mode;

        Session(Player player) {
            this.player = player;
        }
    }

    AnimusLoader(NeyroHub plugin, World hub, int floorY) {
        this.plugin = plugin;
        this.hub = hub;
        this.floorY = floorY;
        scan();
    }

    /** Captures the deck's blocks; call again after a rebuild. */
    void scan() {
        List<Cell> list = new ArrayList<>();
        Map<Long, BlockData> real = new HashMap<>();
        for (int x = -27; x <= 27; x++)
            for (int z = -27; z <= 27; z++)
                for (int y = floorY - 14; y <= floorY + 24; y++) {
                    BlockData d = hub.getBlockAt(x, y, z).getBlockData();
                    if (d.getMaterial().isAir()) continue;
                    list.add(new Cell(x, y, z, d, dist(x, y, z)));
                    real.put(key(x, y, z), d);
                }
        list.sort(Comparator.comparingDouble(Cell::dist));
        List<Cell> sh = new ArrayList<>();
        int r = (int) SHELL_R + 1;
        for (int x = -r; x <= r; x++)
            for (int z = -r; z <= r; z++)
                for (int y = floorY + 1; y <= floorY + r; y++) {
                    double d = Math.sqrt((x + 0.5 - OX) * (x + 0.5 - OX) + (y - floorY) * (y - floorY) + (z + 0.5 - OZ) * (z + 0.5 - OZ));
                    if (d < SHELL_R - 0.5 || d >= SHELL_R + 0.5) continue;
                    BlockData under = real.getOrDefault(key(x, y, z), air);
                    sh.add(new Cell(x, y, z, under, d));
                }
        // white floor disk out to the shell, where the deck has no floor block
        for (int x = -r; x <= r; x++)
            for (int z = -r; z <= r; z++) {
                double d = Math.hypot(x + 0.5 - OX, z + 0.5 - OZ);
                if (d <= SHELL_R && !real.containsKey(key(x, floorY, z))) sh.add(new Cell(x, floorY, z, air, d));
            }
        cells = list;
        shell = sh;
    }

    private static long key(int x, int y, int z) {
        return ((long) (x & 0x3FFFFFF) << 38) | ((long) (z & 0x3FFFFFF) << 12) | (y & 0xFFF);
    }

    private double dist(int x, int y, int z) {
        double dx = x + 0.5 - OX, dz = z + 0.5 - OZ, dy = (y - floorY) * 0.8;
        return Math.sqrt(dx * dx + dy * dy + dz * dz);
    }

    boolean isLoading(Player p) {
        return sessions.containsKey(p.getUniqueId());
    }

    // ------------------------------------------------------------------ start

    void start(Player p) {
        stop(p, false);
        Session s = new Session(p);
        sessions.put(p.getUniqueId(), s);
        s.mode = p.getGameMode() == GameMode.CREATIVE ? GameMode.CREATIVE : GameMode.ADVENTURE;
        if (p.getGameMode() != GameMode.CREATIVE) p.setGameMode(GameMode.ADVENTURE);

        // 1. the white void: hide the deck, paint the floor and a sphere white
        Map<Position, BlockData> fake = new HashMap<>();
        for (Cell c : cells) fake.put(Position.block(c.x, c.y, c.z), c.y == floorY && c.dist <= SHELL_R ? white : air);
        for (Cell c : shell) fake.put(Position.block(c.x, c.y, c.z), white);
        p.sendMultiBlockChange(fake);

        for (Entity e : hub.getEntities()) {
            if (e.getScoreboardTags().contains(NeyroHub.TAG)) {
                p.hideEntity(plugin, e);
                s.hidden.add(e);
            } else if (e instanceof Player other && !other.equals(p)) {
                p.hidePlayer(plugin, other);
                s.hiddenPlayers.add(other);
            }
        }
        spawnFragments(s);
        spawnRain(s);
        s.holo = hub.spawn(new Location(hub, OX, floorY + 8.5, OZ - 12), org.bukkit.entity.TextDisplay.class, d -> {
            plugin.prepare(d);
            d.setVisibleByDefault(false);
            d.setBillboard(Display.Billboard.CENTER);
            d.setBackgroundColor(Color.fromARGB(0, 0, 0, 0));
            d.setShadowed(false);
            d.setBrightness(new Display.Brightness(15, 15));
            d.setTransformation(new Transformation(new Vector3f(), new Quaternionf(), new Vector3f(3.4f, 3.4f, 3.4f), new Quaternionf()));
        });
        p.showEntity(plugin, s.holo);

        // the view leaves the body: a camera flies over the deck while it loads
        s.camera = hub.spawn(new Location(hub, OX, floorY + 1.62, OZ, 180f, 0f), org.bukkit.entity.ItemDisplay.class, d -> {
            plugin.prepare(d);
            d.setTeleportDuration(2);
        });
        p.setGameMode(GameMode.SPECTATOR);
        sound(p, "entity.warden.sonic_boom", 0.35f, 1.6f);

        p.showTitle(Title.title(
                Component.text("СИНХРОНИЗАЦИЯ ПАМЯТИ", NamedTextColor.WHITE).decorate(TextDecoration.BOLD),
                Component.text("NEYRO-SYNC · загрузка палубы NEYRO-1", NamedTextColor.GRAY),
                Title.Times.times(Duration.ofMillis(300), Duration.ofMillis(2600), Duration.ofMillis(500))));
        sound(p, "block.beacon.activate", 0.8f, 1.6f);
        sound(p, "block.amethyst_block.resonate", 0.9f, 0.7f);

        s.task = Bukkit.getScheduler().runTaskTimer(plugin, () -> tick(s), 1L, 1L);
    }

    private void spawnFragments(Session s) {
        Material[] mats = {Material.WHITE_STAINED_GLASS, Material.LIGHT_GRAY_STAINED_GLASS, Material.WHITE_CONCRETE,
                Material.QUARTZ_BLOCK, Material.ORANGE_STAINED_GLASS};
        for (int i = 0; i < 42; i++) {
            double a = rnd.nextDouble() * Math.PI * 2, rr = 3 + rnd.nextDouble() * 13;
            Location l = new Location(hub, OX + Math.cos(a) * rr, floorY + 1.5 + rnd.nextDouble() * 10, OZ + Math.sin(a) * rr);
            Material m = mats[i % 9 == 0 ? 4 : rnd.nextInt(4)];
            float size = 0.15f + rnd.nextFloat() * 0.75f;
            BlockDisplay d = hub.spawn(l, BlockDisplay.class, e -> {
                plugin.prepare(e);
                e.setVisibleByDefault(false);
                e.setBlock(m.createBlockData());
                e.setBrightness(new Display.Brightness(15, 15));
                e.setTeleportDuration(3);
                e.setTransformation(cube(size, rnd.nextFloat() * 6f, rnd.nextFloat() * 6f));
            });
            s.player.showEntity(plugin, d);
            s.fragments.add(d);
        }
    }

    private void spawnRain(Session s) {
        for (int i = 0; i < 26; i++) {
            double a = i * Math.PI * 2 / 26 + rnd.nextDouble() * 0.15, rr = 7 + rnd.nextDouble() * 9;
            Location l = new Location(hub, OX + Math.cos(a) * rr, floorY + 1.5 + rnd.nextDouble() * 4, OZ + Math.sin(a) * rr);
            Rain r = new Rain();
            for (int k = 0; k < r.chars.length; k++) r.chars[k] = GLYPHS.charAt(rnd.nextInt(GLYPHS.length()));
            r.head = -rnd.nextInt(14);
            r.speed = 0.3 + rnd.nextDouble() * 0.4;
            float sc = 1.2f + rnd.nextFloat() * 0.8f;
            r.display = hub.spawn(l, org.bukkit.entity.TextDisplay.class, d -> {
                plugin.prepare(d);
                d.setVisibleByDefault(false);
                d.setBillboard(Display.Billboard.VERTICAL);
                d.setBackgroundColor(Color.fromARGB(0, 0, 0, 0));
                d.setShadowed(false);
                d.setLineWidth(20);
                d.setBrightness(new Display.Brightness(15, 15));
                d.setTransformation(new Transformation(new Vector3f(), new Quaternionf(), new Vector3f(sc, sc, sc), new Quaternionf()));
            });
            s.player.showEntity(plugin, r.display);
            s.rain.add(r);
        }
    }

    private void renderRain(Rain r) {
        TextComponent.Builder b = Component.text();
        for (int row = 0; row < r.chars.length; row++) {
            if (row > 0) b.append(Component.newline());
            int dist = (int) Math.floor(r.head) - row;
            int color;
            if (dist == 0) color = 0x101010;
            else if (dist > 0 && dist <= 7) color = SpaceDecor.mix(0x3A3A3A, 0xD8D8D8, dist / 7.0);
            else color = 0xEDEDED;
            if (dist >= 0 && dist <= 1 && rnd.nextInt(3) == 0) r.chars[row] = GLYPHS.charAt(rnd.nextInt(GLYPHS.length()));
            if (dist == 1 && rnd.nextInt(9) == 0) color = NeyroHub.ORANGE;
            b.append(Component.text(String.valueOf(r.chars[row]), TextColor.color(color)));
        }
        r.display.text(b.build());
    }

    private Component holoText() {
        String word = "NEYRO//CRAFT";
        TextComponent.Builder b = Component.text();
        for (int i = 0; i < word.length(); i++) {
            char ch = word.charAt(i);
            boolean glitch = rnd.nextInt(12) == 0;
            if (glitch) ch = NOISE.charAt(rnd.nextInt(NOISE.length()));
            int color = glitch ? 0xFF8C00 : i < 5 ? 0x2A2A2A : 0x6A6A6A;
            b.append(Component.text(String.valueOf(ch), TextColor.color(color)).decorate(TextDecoration.BOLD));
        }
        return b.build();
    }

    private void moveCamera(Session s) {
        double f = floorY;
        Frame[] keys = {
                new Frame(OX, f + 1.62, OZ, 180, 0, 0),
                new Frame(OX, f + 1.62, OZ, 180, -4, 18),        // look at the helix
                new Frame(OX, f + 5.5, OZ + 6.5, 180, 30, 44),   // pull back and up
                new Frame(OX, f + 17, OZ + 0.1, 180, 89, 66),    // bird's-eye view of the deck
                new Frame(OX, f + 16, OZ + 0.1, 270, 89, 96),    // slowly turning while the wave spreads
                new Frame(OX, f + 1.62, OZ, 540, 0, 126)         // spiral dive back into the eyes
        };
        int t = Math.min(s.t, keys[keys.length - 1].tick());
        Frame a = keys[0], b = keys[1];
        for (int i = 1; i < keys.length; i++) if (t <= keys[i].tick()) { a = keys[i - 1]; b = keys[i]; break; }
        double k = (t - a.tick()) / (double) Math.max(1, b.tick() - a.tick());
        k = k * k * (3 - 2 * k);
        double x = a.x() + (b.x() - a.x()) * k, y = a.y() + (b.y() - a.y()) * k, z = a.z() + (b.z() - a.z()) * k;
        double yaw = a.yaw() + (b.yaw() - a.yaw()) * k, pitch = a.pitch() + (b.pitch() - a.pitch()) * k;
        if (b.tick() == 126) {      // spiral out and back in on the way down
            double rad = Math.sin(k * Math.PI) * 4.5, ang = Math.toRadians(yaw + 90);
            x += Math.cos(ang) * rad;
            z += Math.sin(ang) * rad;
        }
        s.camera.teleport(new Location(hub, x, y, z, (float) (yaw % 360), (float) Math.max(-89.9, Math.min(89.9, pitch))));
    }

    private static Transformation cube(float size, float ry, float rx) {
        Quaternionf q = new Quaternionf().rotateY(ry).rotateX(rx);
        Vector3f off = q.transform(new Vector3f(-size / 2, -size / 2, -size / 2));
        return new Transformation(off, q, new Vector3f(size, size, size), new Quaternionf());
    }

    // ------------------------------------------------------------------- tick

    private void tick(Session s) {
        Player p = s.player;
        if (!p.isOnline() || !p.getWorld().equals(hub)) {
            stop(p, false);
            return;
        }
        s.t++;
        int t = s.t;

        // camera flight
        if (s.camera != null && s.camera.isValid()) {
            if (t == 2 || (t % 10 == 0 && !s.camera.equals(p.getSpectatorTarget()))) p.setSpectatorTarget(s.camera);
            moveCamera(s);
        }
        // falling code and the glitching hologram in the void
        if (t % 2 == 0) {
            for (Rain r : s.rain) {
                if (!r.display.isValid()) continue;
                r.head += r.speed * 2;
                if (r.head - 7 > r.chars.length) { r.head = -rnd.nextInt(8); r.speed = 0.3 + rnd.nextDouble() * 0.4; }
                renderRain(r);
            }
        }
        if (t % 3 == 0 && s.holo != null && s.holo.isValid()) s.holo.text(holoText());
        if (t == 16) sound(p, "block.beacon.ambient", 1f, 0.6f);
        if (t == 40) sound(p, "item.elytra.flying", 0.35f, 1.4f);
        if (t == 98) sound(p, "item.elytra.flying", 0.35f, 1.8f);

        // DNA helix of light in front of the player while the void is white
        if (t < WAVE_START + 50) {
            for (int k = 0; k < 2; k++) {
                double h = ((t * 0.12) + k * 3.0) % 7.0;
                double ang = t * 0.25 + h * 1.4;
                for (int strand = 0; strand < 2; strand++) {
                    double a = ang + strand * Math.PI;
                    Location l = new Location(hub, OX + Math.cos(a) * 0.9, floorY + 1 + h, OZ - 4.5 + Math.sin(a) * 0.9);
                    p.spawnParticle(Particle.DUST, l, 1, 0, 0, 0, 0,
                            new Particle.DustOptions(strand == 0 ? Color.fromRGB(255, 255, 255) : Color.fromRGB(255, 150, 30), 1.1f));
                }
            }
        }

        // drifting fragments
        if (t % 3 == 0) {
            for (BlockDisplay f : s.fragments) {
                if (!f.isValid()) continue;
                Location l = f.getLocation();
                l.add(Math.sin((t + f.getEntityId()) * 0.05) * 0.05, 0.03, Math.cos((t + f.getEntityId()) * 0.05) * 0.05);
                f.teleport(l);
            }
        }
        if (t % 20 == 0) {
            for (BlockDisplay f : s.fragments) {
                if (!f.isValid()) continue;
                float size = f.getTransformation().getScale().x;
                f.setInterpolationDelay(0);
                f.setInterpolationDuration(20);
                f.setTransformation(cube(size, rnd.nextFloat() * 6f, rnd.nextFloat() * 6f));
            }
        }

        // progress
        int pct = Math.min(100, t * 100 / (TOTAL_TICKS - 8));
        TextComponent.Builder bar = Component.text().append(Component.text("Загрузка памяти  ", NamedTextColor.GRAY));
        for (int i = 0; i < 20; i++)
            bar.append(Component.text("▮", i < pct / 5 ? NamedTextColor.WHITE : NamedTextColor.DARK_GRAY));
        bar.append(Component.text("  " + pct + "%", NamedTextColor.WHITE))
                .append(Component.text("   [Shift — пропустить]", NamedTextColor.DARK_GRAY));
        p.sendActionBar(bar.build());

        // the rendering wave
        if (t >= WAVE_START) {
            double r = (t - WAVE_START) * WAVE_SPEED;
            Map<Position, BlockData> changes = new HashMap<>();
            while (s.ghostIdx < cells.size() && cells.get(s.ghostIdx).dist <= r) {
                Cell c = cells.get(s.ghostIdx++);
                changes.put(Position.block(c.x, c.y, c.z), c.y == floorY ? white : ghost);
            }
            while (s.realIdx < cells.size() && cells.get(s.realIdx).dist <= r - GHOST_LAG) {
                Cell c = cells.get(s.realIdx++);
                changes.put(Position.block(c.x, c.y, c.z), c.real);
            }
            if (!s.shellGone && r >= SHELL_R - 1) {
                s.shellGone = true;
                for (Cell c : shell) changes.put(Position.block(c.x, c.y, c.z), c.real);
                sound(p, "block.glass.break", 1f, 0.6f);
                sound(p, "block.respawn_anchor.set_spawn", 0.8f, 1.4f);
                sound(p, "entity.generic.explode", 0.25f, 1.8f);
                p.spawnParticle(Particle.END_ROD, new Location(hub, OX, floorY + 6, OZ), 160, 12, 6, 12, 0.15);
                if (s.holo != null && s.holo.isValid()) {
                    p.spawnParticle(Particle.END_ROD, s.holo.getLocation(), 60, 3, 1, 0.3, 0.1);
                    s.holo.remove();
                }
                for (Rain rr : s.rain) if (rr.display.isValid()) rr.display.remove();
                s.rain.clear();
            }
            if (!changes.isEmpty()) p.sendMultiBlockChange(changes);

            // reveal displays and fragments the wave has passed
            for (Entity e : new ArrayList<>(s.hidden)) {
                if (!e.isValid()) { s.hidden.remove(e); continue; }
                Location l = e.getLocation();
                double d = Math.sqrt((l.getX() - OX) * (l.getX() - OX) + (l.getZ() - OZ) * (l.getZ() - OZ));
                if (d <= r) {
                    p.showEntity(plugin, e);
                    s.hidden.remove(e);
                }
            }
            for (Rain rr : new ArrayList<>(s.rain)) {
                Location l = rr.display.getLocation();
                if (Math.hypot(l.getX() - OX, l.getZ() - OZ) <= r) {
                    p.spawnParticle(Particle.END_ROD, l.add(0, 1.5, 0), 8, 0.1, 1.0, 0.1, 0.03);
                    rr.display.remove();
                    s.rain.remove(rr);
                }
            }
            for (BlockDisplay f : new ArrayList<>(s.fragments)) {
                Location l = f.getLocation();
                if (Math.hypot(l.getX() - OX, l.getZ() - OZ) <= r) {
                    p.spawnParticle(Particle.END_ROD, l, 5, 0.1, 0.1, 0.1, 0.05);
                    f.remove();
                    s.fragments.remove(f);
                }
            }
            // glowing ring on the floor at the wave front
            if (r < 28 && t % 2 == 0) {
                int pts = (int) Math.max(12, r * 7);
                for (int i = 0; i < pts; i++) {
                    double a = i * Math.PI * 2 / pts;
                    Location l = new Location(hub, OX + Math.cos(a) * r, floorY + 1.1, OZ + Math.sin(a) * r);
                    p.spawnParticle(Particle.DUST, l, 1, 0, 0, 0, 0,
                            new Particle.DustOptions(i % 3 == 0 ? Color.fromRGB(255, 150, 30) : Color.WHITE, 1.3f));
                }
            }
            if (t % 6 == 0 && r < 30) sound(p, "block.amethyst_block.chime", 0.8f, (float) Math.min(2.0, 0.6 + r / 20));
        }

        if (t >= TOTAL_TICKS) finish(s);
    }

    private void finish(Session s) {
        Player p = s.player;
        stop(p, true);
        p.showTitle(Title.title(
                NeyroHub.serverName(),
                Component.text()
                        .append(Component.text("Синхронизация завершена · добро пожаловать, ", NamedTextColor.GRAY))
                        .append(Component.text(p.getName(), TextColor.color(NeyroHub.ORANGE)).decorate(TextDecoration.BOLD))
                        .build(),
                Title.Times.times(Duration.ofMillis(200), Duration.ofMillis(2500), Duration.ofMillis(800))));
        sound(p, "entity.player.levelup", 0.6f, 1.3f);
        sound(p, "block.beacon.power_select", 0.7f, 1.5f);
        Component note = Component.text()
                .append(Component.text("✦ ", TextColor.color(NeyroHub.ORANGE)))
                .append(Component.text(p.getName(), TextColor.color(NeyroHub.ORANGE)))
                .append(Component.text(" синхронизировался с палубой", NamedTextColor.GRAY))
                .build();
        for (Player other : hub.getPlayers()) if (!other.equals(p)) other.sendActionBar(note);
    }

    /** Skip (Shift): render everything at once. */
    void skip(Player p) {
        Session s = sessions.get(p.getUniqueId());
        if (s != null) finish(s);
    }

    /** Ends a session; with {@code restore} the real deck is sent and everything is shown again. */
    void stop(Player p, boolean restore) {
        Session s = sessions.remove(p.getUniqueId());
        if (s == null) return;
        if (s.task != null) s.task.cancel();
        for (BlockDisplay f : s.fragments) if (f.isValid()) f.remove();
        s.fragments.clear();
        for (Rain r : s.rain) if (r.display.isValid()) r.display.remove();
        s.rain.clear();
        if (s.holo != null && s.holo.isValid()) s.holo.remove();
        if (s.camera != null) {
            if (p.isOnline() && s.camera.equals(p.getSpectatorTarget())) p.setSpectatorTarget(null);
            if (s.camera.isValid()) s.camera.remove();
        }
        if (!restore || !p.isOnline()) return;
        p.teleport(new Location(hub, OX, floorY + 1, OZ, 180f, 0f));
        Map<Position, BlockData> all = new HashMap<>();
        for (Cell c : shell) all.put(Position.block(c.x, c.y, c.z), c.real);
        for (Cell c : cells) all.put(Position.block(c.x, c.y, c.z), c.real);
        p.sendMultiBlockChange(all);
        for (Entity e : s.hidden) if (e.isValid()) p.showEntity(plugin, e);
        for (Player o : s.hiddenPlayers) if (o.isOnline()) p.showPlayer(plugin, o);
        p.sendActionBar(Component.empty());
        if (p.getGameMode() != GameMode.CREATIVE) p.setGameMode(s.mode);
    }

    void stopAll() {
        for (Session s : new ArrayList<>(sessions.values())) stop(s.player, true);
    }

    private static void sound(Player p, String key, float volume, float pitch) {
        p.playSound(Sound.sound(Key.key("minecraft", key), Sound.Source.MASTER, volume, pitch));
    }
}
