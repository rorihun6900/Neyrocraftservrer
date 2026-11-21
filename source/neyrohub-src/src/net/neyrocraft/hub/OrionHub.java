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
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.Particle;
import org.bukkit.World;
import org.bukkit.block.data.BlockData;
import org.bukkit.block.data.Orientable;
import org.bukkit.entity.BlockDisplay;
import org.bukkit.entity.Display;
import org.bukkit.entity.EnderCrystal;
import org.bukkit.entity.Entity;
import org.bukkit.entity.Player;
import org.bukkit.entity.TextDisplay;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerMoveEvent;
import org.bukkit.potion.PotionEffect;
import org.bukkit.potion.PotionEffectType;
import org.bukkit.util.Transformation;
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
import java.util.Random;
import java.util.UUID;

/**
 * Station "Orion" — the second hub. A dark crystal observatory floating in a nebula:
 * deepslate and amethyst, prismarine light lines, purple spires, a crystal core with an energy beam,
 * the same four portals (in different arches), and Ami.
 */
final class OrionHub implements Listener {
    static final int OX = -3000, OZ = 0;
    private static final int R = 21;                 // platform radius
    private static final int BUILD = 1;

    private record Gate(String id, String title, String sub, int color, int dx, int dz, Axis axis, Material frame, Material corner) { }

    private final List<Gate> gates = List.of(
            new Gate("sabers", "СВЕТОВЫЕ МЕЧИ", "Дуэли на энергоклинках", 0x7FE8FF, 0, -15, Axis.X, Material.WAXED_OXIDIZED_COPPER, Material.AMETHYST_BLOCK),
            new Gate("race", "ГОНКИ НА СПИДЕРАХ", "Планета Терракс · трасса 1,6 км", 0xFFA040, 15, 0, Axis.Z, Material.SMOOTH_RED_SANDSTONE, Material.OCHRE_FROGLIGHT),
            new Gate("cinema", "КОСМОКИНО", "Огромный экран под звёздами", 0xE07BFF, 0, 15, Axis.X, Material.PURPUR_BLOCK, Material.PEARLESCENT_FROGLIGHT),
            new Gate("starfight", "КОСМИЧЕСКИЕ БОИ", "Истребители в поясе астероидов", 0x7FE8FF, -15, 0, Axis.Z, Material.POLISHED_DEEPSLATE, Material.SEA_LANTERN));

    private final NeyroHub plugin;
    private final World w;
    private final int fy;                            // floor level
    private final List<Entity> entities = new ArrayList<>();
    private final List<BlockDisplay> ringA = new ArrayList<>(), ringB = new ArrayList<>();
    private final Map<UUID, Long> cooldown = new HashMap<>();
    private final Random rnd = new Random();
    private BlockDisplay crystalCore;
    private Ami ami;
    private long tick;

    OrionHub(NeyroHub plugin, World hubWorld, int floorY) {
        this.plugin = plugin;
        this.w = hubWorld;
        this.fy = floorY;
    }

    void start() {
        for (int cx = (OX - 48) >> 4; cx <= (OX + 48) >> 4; cx++)
            for (int cz = (OZ - 48) >> 4; cz <= (OZ + 48) >> 4; cz++) w.setChunkForceLoaded(cx, cz, true);
        File marker = new File(plugin.getDataFolder(), "orion-built-v" + BUILD);
        if (!marker.exists()) {
            build();
            try { Files.writeString(marker.toPath(), "built"); } catch (IOException ignored) { }
        }
        File raceGate = new File(plugin.getDataFolder(), "orion-race-gate-v1");
        if (!raceGate.exists()) {
            for (Gate g : gates) if (g.id().equals("race")) buildGate(g);
            try { Files.writeString(raceGate.toPath(), "built"); } catch (IOException ignored) { }
        }
        File fightGate = new File(plugin.getDataFolder(), "orion-fight-gate-v1");
        if (!fightGate.exists()) {
            for (Gate g : gates) if (g.id().equals("starfight")) buildGate(g);
            try { Files.writeString(fightGate.toPath(), "built"); } catch (IOException ignored) { }
        }
        spawnDisplays();
        Bukkit.getScheduler().runTaskTimer(plugin, this::animate, 20L, 2L);
    }

    void shutdown() {
        if (ami != null) ami.remove();
        for (Entity e : entities) if (e.isValid()) e.remove();
        entities.clear();
    }

    Ami ami() { return ami; }

    Location spawn() {
        return new Location(w, OX + 0.5, fy + 1, OZ + 6.5, 180f, -8f);
    }

    boolean contains(Location l) {
        if (l == null || !w.equals(l.getWorld())) return false;
        double dx = l.getX() - (OX + 0.5), dz = l.getZ() - (OZ + 0.5);
        return dx * dx + dz * dz < (R + 30) * (R + 30) && l.getY() > fy - 60 && l.getY() < fy + 60;
    }

    // ------------------------------------------------------------ going there

    void send(Player p) {
        if (BeamTransfer.busy(p) || WarpTunnel.busy(p)) return;
        WarpTunnel.run(p, spawn(), new WarpTunnel.Style(0xC08CFF, 0x7FFFE8, 0xE8D8FF, "станция «Орион»",
                BeamTransfer.title(Component.text("СТАНЦИЯ «ОРИОН»", TextColor.color(0xC08CFF)).decorate(TextDecoration.BOLD),
                        Component.text("второй хаб NeyroCraft · обсерватория в туманности", NamedTextColor.GRAY))), null);
    }
    // ------------------------------------------------------------ building

    private void set(int x, int y, int z, Material m) { w.getBlockAt(OX + x, y, OZ + z).setType(m, false); }

    private static int hash(int x, int z) {
        int h = x * 73856093 ^ z * 83492791;
        h ^= (h >>> 13); h *= 0x5bd1e995;
        return Math.floorMod(h ^ (h >>> 15), 1000);
    }

    private void build() {
        plugin.getLogger().info("Building station Orion...");
        for (int x = -R - 4; x <= R + 4; x++)
            for (int z = -R - 4; z <= R + 4; z++)
                for (int y = fy - 22; y <= fy + 24; y++)
                    if (!w.getBlockAt(OX + x, y, OZ + z).getType().isAir()) set(x, y, z, Material.AIR);

        for (int x = -R - 1; x <= R + 1; x++) {
            for (int z = -R - 1; z <= R + 1; z++) {
                double r = Math.sqrt(x * x + z * z);
                if (r > R + 0.5) continue;
                // floor: dark tiles, amethyst rings, prismarine light lines on the diagonals
                Material f;
                if (r < 2.5) f = Material.CRYING_OBSIDIAN;
                else if (r < 3.5) f = Material.AMETHYST_BLOCK;
                else if (Math.abs(r - 9) < 0.5 || Math.abs(r - 15.5) < 0.5) f = Material.AMETHYST_BLOCK;
                else if (Math.abs(Math.abs(x) - Math.abs(z)) == 0) f = Material.SEA_LANTERN;
                else if (r > R - 1.2) f = Material.POLISHED_DEEPSLATE;
                else {
                    int h = hash(x, z);
                    f = h < 90 ? Material.PRISMARINE_BRICKS : h < 550 ? Material.DEEPSLATE_TILES : h < 850 ? Material.POLISHED_DEEPSLATE : Material.CRACKED_DEEPSLATE_TILES;
                }
                set(x, fy, z, f);
                // parapet: low wall of deepslate with purple glass
                if (r > R - 0.6) {
                    set(x, fy + 1, z, Material.POLISHED_DEEPSLATE_WALL);
                    set(x, fy + 2, z, Material.PURPLE_STAINED_GLASS_PANE);
                }
                // floating rock underneath, like a crystal geode
                int depth = (int) Math.round(Math.pow(Math.max(0, 1 - r / (R + 0.5)), 0.75) * 16);
                for (int d = 1; d <= depth; d++) {
                    int h = hash(x * 3 + d, z * 5 - d);
                    Material m = d == 1 ? Material.POLISHED_DEEPSLATE : h < 120 ? Material.CALCITE : h < 220 ? Material.AMETHYST_BLOCK
                            : h < 260 ? Material.BUDDING_AMETHYST : h < 700 ? Material.DEEPSLATE : Material.TUFF;
                    set(x, fy - d, z, m);
                }
                if (depth > 2 && hash(x, z) % 9 == 0) set(x, fy - depth - 1, z, Material.AMETHYST_CLUSTER);
            }
        }
        // eight purple spires around the edge
        for (int i = 0; i < 8; i++) {
            double a = Math.PI / 8 + i * Math.PI / 4;
            int sx = (int) Math.round(Math.cos(a) * (R - 2)), sz = (int) Math.round(Math.sin(a) * (R - 2));
            int h = 7 + (i % 2) * 4;
            for (int y = 1; y <= h; y++) set(sx, fy + y, sz, y == h ? Material.AMETHYST_BLOCK : (y % 3 == 0 ? Material.PURPUR_PILLAR : Material.POLISHED_DEEPSLATE));
            set(sx, fy + h + 1, sz, Material.END_ROD);
        }
        // core dais
        for (int x = -2; x <= 2; x++) for (int z = -2; z <= 2; z++) if (x * x + z * z <= 5) set(x, fy + 1, z, Material.PURPUR_SLAB);
        set(0, fy + 1, 0, Material.BEACON);
        for (Gate g : gates) buildGate(g);
        plugin.getLogger().info("Station Orion built");
    }

    private void buildGate(Gate g) {
        int half = 2, height = 6;
        BlockData portal = Bukkit.createBlockData(Material.NETHER_PORTAL, bd -> ((Orientable) bd).setAxis(g.axis()));
        for (int wv = -half - 1; wv <= half + 1; wv++) {
            for (int h = 0; h <= height + 1; h++) {
                int x = g.axis() == Axis.X ? g.dx() + wv : g.dx();
                int z = g.axis() == Axis.X ? g.dz() : g.dz() + wv;
                boolean edgeW = Math.abs(wv) == half + 1, edgeH = h == 0 || h == height + 1;
                // pointed arch: corners cut off at the top
                boolean cut = h == height + 1 && edgeW;
                if (cut) set(x, fy + h, z, Material.AIR);
                else if (edgeW && h == height) set(x, fy + h, z, g.corner());
                else if (edgeW || edgeH) set(x, fy + h, z, g.frame());
                else w.getBlockAt(OX + x, fy + h, OZ + z).setBlockData(portal, false);
            }
        }
        int tx = g.axis() == Axis.X ? g.dx() : g.dx(), tz = g.axis() == Axis.X ? g.dz() : g.dz();
        set(tx, fy + height + 2, tz, g.corner());
        set(tx, fy + height + 3, tz, Material.END_ROD);
    }

    // ------------------------------------------------------------ displays

    private void spawnDisplays() {
        shutdown();
        Location core = new Location(w, OX + 0.5, fy + 12, OZ + 0.5);
        EnderCrystal crystal = w.spawn(core.clone().add(0, 1.2, 0), EnderCrystal.class, c -> {
            c.addScoreboardTag(NeyroHub.TAG);
            c.setPersistent(false);
            c.setShowingBottom(false);
            c.setInvulnerable(true);
            c.setBeamTarget(new Location(w, OX, fy + 1, OZ));
        });
        entities.add(crystal);
        crystalCore = w.spawn(core, BlockDisplay.class, d -> {
            plugin.prepare(d);
            d.setBlock(Material.AMETHYST_BLOCK.createBlockData());
            d.setTransformation(cube(3.2f));
            d.setBrightness(new Display.Brightness(15, 15));
            d.setInterpolationDuration(2);
        });
        entities.add(crystalCore);
        for (int i = 0; i < 10; i++) {
            Material m = i % 2 == 0 ? Material.PURPLE_STAINED_GLASS : Material.SEA_LANTERN;
            BlockDisplay d = w.spawn(core, BlockDisplay.class, b -> {
                plugin.prepare(b);
                b.setBlock(m.createBlockData());
                b.setTransformation(cube(0.6f));
                b.setTeleportDuration(2);
                b.setBrightness(new Display.Brightness(15, 15));
            });
            ringA.add(d); entities.add(d);
        }
        for (int i = 0; i < 14; i++) {
            BlockDisplay d = w.spawn(core, BlockDisplay.class, b -> {
                plugin.prepare(b);
                b.setBlock(Material.AMETHYST_CLUSTER.createBlockData());
                b.setTransformation(cube(0.5f));
                b.setTeleportDuration(2);
                b.setBrightness(new Display.Brightness(15, 15));
            });
            ringB.add(d); entities.add(d);
        }
        entities.add(w.spawn(core.clone().add(0, 4.4, 0), TextDisplay.class, t -> {
            plugin.prepare(t);
            t.setBillboard(Display.Billboard.CENTER);
            t.setBackgroundColor(Color.fromARGB(0, 0, 0, 0));
            t.setShadowed(true);
            t.setTransformation(scale(3.6f));
            t.text(Component.text()
                    .append(Component.text("ОРИОН", TextColor.color(0xC08CFF)).decorate(TextDecoration.BOLD)).append(Component.newline())
                    .append(Component.text("кристальное ядро станции", TextColor.color(0x7FFFE8))).build());
        }));
        for (Gate g : gates) {
            entities.add(w.spawn(new Location(w, OX + g.dx() + 0.5, fy + 10.6, OZ + g.dz() + 0.5), TextDisplay.class, t -> {
                plugin.prepare(t);
                t.setBillboard(Display.Billboard.CENTER);
                t.setBackgroundColor(Color.fromARGB(130, 20, 6, 40));
                t.setShadowed(true);
                t.setTransformation(scale(2.4f));
                t.text(Component.text()
                        .append(Component.text("✦ " + g.title() + " ✦", TextColor.color(g.color())).decorate(TextDecoration.BOLD))
                        .append(Component.newline()).append(Component.text(g.sub(), NamedTextColor.GRAY))
                        .append(Component.newline()).append(g.id().equals("sabers") || g.id().equals("race") || g.id().equals("cinema") || g.id().equals("starfight")
                                ? Component.text("▶ Войди в арку", NamedTextColor.YELLOW)
                                : Component.text("✦ СКОРО ✦", TextColor.color(0xC08CFF)).decorate(TextDecoration.BOLD)).build());
            }));
        }
        // nebula planets in other colours than the main ship
        SpaceDecor.planet(plugin, w, new Location(w, OX + 70, fy + 25, OZ - 60), SpaceDecor.Kind.MARS, 36f, 31);
        SpaceDecor.planet(plugin, w, new Location(w, OX - 65, fy + 40, OZ + 55), SpaceDecor.Kind.GAS, 30f, 44);
        SpaceDecor.planet(plugin, w, new Location(w, OX - 40, fy + 12, OZ - 70), SpaceDecor.Kind.MOON, 9f, 5);

        ami = new Ami(plugin, new Location(w, OX + 5.5, fy + 1, OZ + 2.5), Ami.Station.ORION, plugin::beamToMain);
        ami.spawn();
    }

    private static Transformation cube(float s) {
        return new Transformation(new Vector3f(-s / 2, -s / 2, -s / 2), new AxisAngle4f(), new Vector3f(s, s, s), new AxisAngle4f());
    }

    private static Transformation scale(float s) {
        return new Transformation(new Vector3f(), new AxisAngle4f(), new Vector3f(s, s, s), new AxisAngle4f());
    }

    private void animate() {
        tick++;
        if (ami != null) ami.animate();
        boolean anyone = false;
        for (Player p : w.getPlayers()) if (contains(p.getLocation())) { anyone = true; break; }
        if (!anyone) return;
        double t = tick * 0.05;
        Location core = new Location(w, OX + 0.5, fy + 12, OZ + 0.5);
        if (crystalCore != null && crystalCore.isValid()) {
            Quaternionf q = new Quaternionf().rotateY((float) (t * 0.9)).rotateX((float) (Math.PI / 4)).rotateZ((float) (Math.PI / 4));
            float s = 3.2f + (float) Math.sin(t * 2) * 0.15f;
            Vector3f off = new Vector3f(-s / 2, -s / 2, -s / 2).rotate(q);
            crystalCore.setInterpolationDelay(0);
            crystalCore.setTransformation(new Transformation(off, q, new Vector3f(s, s, s), new Quaternionf()));
        }
        for (int i = 0; i < ringA.size(); i++) {
            double a = t * 0.8 + i * (Math.PI * 2 / ringA.size());
            ringA.get(i).teleport(core.clone().add(Math.cos(a) * 5.2, Math.sin(a * 2) * 0.6, Math.sin(a) * 5.2));
        }
        for (int i = 0; i < ringB.size(); i++) {
            double a = -t * 1.3 + i * (Math.PI * 2 / ringB.size());
            ringB.get(i).teleport(core.clone().add(Math.cos(a) * 7.5, Math.cos(a) * Math.sin(t * 0.3) * 3, Math.sin(a) * 7.5));
        }
        // nebula dust drifting around the station
        for (int i = 0; i < 4; i++) {
            Location l = new Location(w, OX + (rnd.nextDouble() - 0.5) * 90, fy + (rnd.nextDouble() - 0.3) * 40, OZ + (rnd.nextDouble() - 0.5) * 90);
            if (Math.hypot(l.getX() - OX, l.getZ() - OZ) < R + 4) continue;
            w.spawnParticle(rnd.nextBoolean() ? Particle.WITCH : Particle.END_ROD, l, 1, 0, 0, 0, 0.01);
        }
        if (tick % 4 == 0) w.spawnParticle(Particle.REVERSE_PORTAL, core, 6, 1.2, 1.2, 1.2, 0.02);
    }

    /** The arches of Orion are still empty: a soft push back and a note that a new world is coming. */
    private void emptyGate(Player p, Gate g) {
        org.bukkit.util.Vector back = new org.bukkit.util.Vector(OX + 0.5, fy + 1, OZ + 0.5).subtract(p.getLocation().toVector()).setY(0).normalize();
        p.setVelocity(back.multiply(1.0).setY(0.4));
        p.playSound(Sound.sound(Key.key("minecraft", "block.amethyst_block.resonate"), Sound.Source.MASTER, 1f, 0.6f));
        p.playSound(Sound.sound(Key.key("minecraft", "block.portal.ambient"), Sound.Source.MASTER, 0.4f, 1.8f));
        p.showTitle(Title.title(
                Component.text(g.title(), TextColor.color(g.color())).decorate(TextDecoration.BOLD),
                Component.text("Этот мир ещё строится — портал пока пуст", NamedTextColor.GRAY),
                Title.Times.times(Duration.ofMillis(150), Duration.ofMillis(1600), Duration.ofMillis(400))));
        w.spawnParticle(Particle.REVERSE_PORTAL, p.getLocation().add(0, 1, 0), 40, 0.4, 0.8, 0.4, 0.04);
    }

    // ------------------------------------------------------------ walking around

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onMove(PlayerMoveEvent e) {
        Location to = e.getTo();
        if (!contains(to)) return;
        Player p = e.getPlayer();
        if (to.getY() < fy - 30) { p.teleport(spawn()); p.setFallDistance(0); return; }
        if (e.getFrom().getBlockX() == to.getBlockX() && e.getFrom().getBlockY() == to.getBlockY() && e.getFrom().getBlockZ() == to.getBlockZ()) return;
        Material feet = to.getBlock().getType(), head = to.clone().add(0, 1, 0).getBlock().getType();
        if (feet != Material.NETHER_PORTAL && head != Material.NETHER_PORTAL) return;
        long now = System.currentTimeMillis();
        Long until = cooldown.get(p.getUniqueId());
        if (until != null && until > now) return;
        cooldown.put(p.getUniqueId(), now + 2000);
        Gate best = null; double bd = 1e9;
        for (Gate g : gates) {
            double dx = to.getX() - (OX + g.dx() + 0.5), dz = to.getZ() - (OZ + g.dz() + 0.5), d = dx * dx + dz * dz;
            if (d < bd) { bd = d; best = g; }
        }
        if (best == null || bd >= 40) return;
        if (best.id().equals("sabers") || best.id().equals("race") || best.id().equals("cinema") || best.id().equals("starfight")) plugin.enterPortal(p, best.id());
        else emptyGate(p, best);
    }
}
