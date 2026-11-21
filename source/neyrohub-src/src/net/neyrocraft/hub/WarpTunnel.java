package net.neyrocraft.hub;

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
import org.bukkit.attribute.Attribute;
import org.bukkit.attribute.AttributeInstance;
import org.bukkit.entity.BlockDisplay;
import org.bukkit.entity.Display;
import org.bukkit.entity.Entity;
import org.bukkit.entity.Player;
import org.bukkit.entity.TextDisplay;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.event.player.PlayerToggleSneakEvent;
import org.bukkit.potion.PotionEffect;
import org.bukkit.potion.PotionEffectType;
import org.bukkit.scheduler.BukkitTask;
import org.bukkit.util.Transformation;
import org.joml.Quaternionf;
import org.joml.Vector3f;

import java.time.Duration;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.UUID;
import java.util.function.Consumer;

/**
 * Long warp jump (≈10 s) to station Orion and to the planets:
 *  1. charge — energy spirals gather around the player, they lift off, the HUD counts the charge;
 *  2. flash  — a white-out, and the player's eyes move into a hyperspace tunnel (far out in the empty hub space);
 *  3. warp   — streaks of stars, a twisting vortex of two colours, rings of light flying through the camera,
 *              flashes of energy, the FOV widening as the speed grows;
 *  4. exit   — an exit gate grows out of the depth and floods the view with light;
 *  5. arrive — the destination dissolves in from white, rings of crystals burst out, a title.
 * Shift skips the tunnel.
 */
final class WarpTunnel implements Listener {

    record Style(int c1, int c2, int c3, String course, Title arrival) { }

    // the tunnel: deep in the empty hub space, away from everything
    private static final double TX = -1500.5, TY = 250, TZ = 0.5;

    private static final int CHARGE = 36;            // gathering energy on the spot
    private static final int JUMP = CHARGE + 3;      // eyes move into the tunnel
    private static final int FADE = 12;              // white of the jump fades into the tunnel
    private static final int GATE = JUMP + 104;      // exit gate appears
    private static final int WHITE = GATE + 18;      // light starts to fill the view
    private static final int FULL = GATE + 32;       // fully white
    private static final int ARRIVE = FULL + 6;      // at the destination
    private static final int END = ARRIVE + 26;

    private static NeyroHub plugin;
    private static World hub;
    private static final Map<UUID, Session> sessions = new HashMap<>();
    private static final Random rnd = new Random();

    private static final class Session {
        final Player p;
        final Location target;
        final Style style;
        final Consumer<Player> after;
        final List<Entity> temp = new ArrayList<>();
        final List<double[]> rings = new ArrayList<>();
        BukkitTask task;
        int t;
        float walk;
        double jumpBase;
        GameMode mode;
        boolean inTunnel;
        BlockDisplay cube;
        TextDisplay panel, gate;

        Session(Player p, Location target, Style style, Consumer<Player> after) {
            this.p = p; this.target = target; this.style = style; this.after = after;
        }
    }

    private WarpTunnel() { }

    static void init(NeyroHub pl, World hubWorld) {
        plugin = pl;
        hub = hubWorld;
        hub.setChunkForceLoaded((int) Math.floor(TX) >> 4, (int) Math.floor(TZ) >> 4, true);
        pl.getServer().getPluginManager().registerEvents(new WarpTunnel(), pl);
    }

    static boolean busy(Player p) {
        return sessions.containsKey(p.getUniqueId());
    }

    static void run(Player p, Location target, Style style, Consumer<Player> after) {
        if (plugin == null || busy(p) || BeamTransfer.busy(p)) return;
        Session s = new Session(p, target, style, after);
        sessions.put(p.getUniqueId(), s);
        s.walk = p.getWalkSpeed();
        AttributeInstance jump = p.getAttribute(Attribute.JUMP_STRENGTH);
        s.jumpBase = jump == null ? 0.42 : jump.getBaseValue();
        s.mode = p.getGameMode() == GameMode.CREATIVE ? GameMode.CREATIVE : GameMode.ADVENTURE;
        p.setWalkSpeed(0f);
        if (jump != null) jump.setBaseValue(0);
        // the destination's chunks load while the jump plays
        target.getWorld().getChunkAtAsync(target);
        sound(p, "block.beacon.activate", 0.9f, 0.8f);
        sound(p, "block.respawn_anchor.charge", 0.7f, 0.6f);
        s.task = Bukkit.getScheduler().runTaskTimer(plugin, () -> tick(s), 1L, 1L);
    }

    // ------------------------------------------------------------ the scene

    private static void tick(Session s) {
        Player p = s.p;
        if (!p.isOnline()) { abort(s); return; }
        int t = s.t++;
        if (t < CHARGE) charge(s, t);
        else if (t == CHARGE) flash(s);
        else if (t < JUMP) { if (s.cube != null && s.cube.isValid()) s.cube.teleport(p.getEyeLocation()); }
        else if (t == JUMP) enterTunnel(s);
        else if (t < ARRIVE) tunnel(s, t);
        else if (t == ARRIVE) arrive(s);
        else if (t < END) landing(s, t);
        else finish(s);
    }

    private static void charge(Session s, int t) {
        Player p = s.p;
        World w = p.getWorld();
        Location feet = p.getLocation();
        double k = t / (double) CHARGE;
        Particle.DustOptions a = dust(s.style.c1(), 1.4f), b = dust(s.style.c2(), 1.2f), c = dust(s.style.c3(), 1.0f);
        // three spirals that tighten and climb
        for (int arm = 0; arm < 3; arm++) {
            double ang = t * 0.5 + arm * Math.PI * 2 / 3, rad = 2.0 - k * 1.3;
            for (int j = 0; j < 2; j++) {
                double aa = ang + j * 0.25;
                Location l = feet.clone().add(Math.cos(aa) * rad, 0.1 + k * 2.8 + j * 0.2, Math.sin(aa) * rad);
                w.spawnParticle(Particle.DUST, l, 1, 0, 0, 0, 0, arm == 0 ? a : arm == 1 ? b : c);
            }
        }
        // a ring on the floor that charges up
        int pts = 24;
        for (int i = 0; i < pts; i++) {
            if (i > pts * k * 1.2) break;
            double ang = i * Math.PI * 2 / pts;
            w.spawnParticle(Particle.DUST, feet.clone().add(Math.cos(ang) * 1.6, 0.1, Math.sin(ang) * 1.6), 1, 0, 0, 0, 0, i % 2 == 0 ? a : b);
        }
        // the column of light comes down
        if (t > 12) for (int i = 0; i < 5; i++)
            w.spawnParticle(Particle.END_ROD, feet.clone().add((rnd.nextDouble() - 0.5) * 0.6, 2 + rnd.nextDouble() * 12, (rnd.nextDouble() - 0.5) * 0.6), 0, 0, -0.7, 0, 1);
        if (t > 20 && t % 3 == 0) w.spawnParticle(Particle.ELECTRIC_SPARK, feet.clone().add(0, 1, 0), 8, 0.5, 0.8, 0.5, 0.15);
        if (t % 4 == 0) sound(p, "block.note_block.chime", 0.55f, (float) (0.5 + k * 1.5));
        if (t == 14) p.addPotionEffect(new PotionEffect(PotionEffectType.LEVITATION, 24, 0, false, false, false));
        if (t == 24) sound(p, "block.beacon.power_select", 0.9f, 1.4f);
        if (t == 30) sound(p, "item.trident.riptide_3", 0.8f, 0.8f);
        int pct = (int) Math.round(k * 100);
        HudBoard.notice(p, hud("ВАРП-ДВИГАТЕЛЬ · ЗАРЯД  ", s.style, pct, false));
    }

    private static void flash(Session s) {
        Player p = s.p;
        sound(p, "entity.firework_rocket.blast", 0.9f, 0.6f);
        sound(p, "entity.generic.explode", 0.25f, 1.8f);
        sound(p, "block.portal.travel", 0.15f, 1.7f);
        s.cube = Hyperjump.flashCube(plugin, p, p.getEyeLocation(), Material.WHITE_CONCRETE);
        s.temp.add(s.cube);
        p.getWorld().spawnParticle(Particle.FIREWORK, p.getEyeLocation(), 40, 0.4, 0.4, 0.4, 0.2);
        p.getWorld().spawnParticle(Particle.END_ROD, p.getLocation().add(0, 1, 0), 60, 0.3, 0.8, 0.3, 0.25);
    }

    private static Location eye(float yaw, float pitch) {
        return new Location(hub, TX, TY - 1.62, TZ, yaw, pitch);
    }

    private static void enterTunnel(Session s) {
        Player p = s.p;
        p.removePotionEffect(PotionEffectType.LEVITATION);
        if (s.cube != null && s.cube.isValid()) s.cube.remove();
        p.setGameMode(GameMode.SPECTATOR);
        p.teleport(eye(180f, 0f));
        p.setFlySpeed(0f);
        s.inTunnel = true;
        s.cube = Hyperjump.flashCube(plugin, p, new Location(hub, TX, TY, TZ), Material.WHITE_CONCRETE);
        s.temp.add(s.cube);
        s.panel = Hyperjump.whitePanel(plugin, p, new Location(hub, TX, TY, TZ - 0.35), 255);
        s.temp.add(s.panel);
        p.addPotionEffect(new PotionEffect(PotionEffectType.SPEED, GATE - JUMP + 20, 7, false, false, false));
        p.showTitle(Title.title(
                Component.text("◈ ВАРП-ПРЫЖОК ◈", TextColor.color(s.style.c1())).decorate(TextDecoration.BOLD),
                Component.text("курс: " + s.style.course(), NamedTextColor.GRAY),
                Title.Times.times(Duration.ofMillis(300), Duration.ofMillis(2200), Duration.ofMillis(800))));
        sound(p, "item.trident.riptide_3", 1f, 0.6f);
        sound(p, "block.beacon.activate", 0.8f, 0.5f);
    }

    private static double smooth(double k) {
        k = Math.max(0, Math.min(1, k));
        return k * k * (3 - 2 * k);
    }

    private static double speed(int dt) {
        int cruise = GATE - JUMP;
        if (dt <= cruise) return 1.0 + smooth(dt / 50.0) * 2.4;
        return 3.4 - 3.0 * smooth((dt - cruise) / (double) (FULL - GATE));
    }

    private static void tunnel(Session s, int t) {
        Player p = s.p;
        int dt = t - JUMP;
        double v = speed(dt);
        Location here = p.getLocation();
        if (!here.getWorld().equals(hub) || here.distanceSquared(eye(0, 0)) > 0.04) p.teleport(eye(here.getYaw(), here.getPitch()));

        // the white of the jump dissolves into the tunnel
        if (dt <= FADE && s.panel != null && s.panel.isValid())
            s.panel.setBackgroundColor(Color.fromARGB((int) (255 * (1 - smooth(dt / (double) FADE))), 255, 255, 255));
        if (dt == 4 && s.cube != null && s.cube.isValid()) s.cube.setBlock(Material.WHITE_STAINED_GLASS.createBlockData());
        if (dt == 8 && s.cube != null && s.cube.isValid()) { s.cube.remove(); s.cube = null; }

        Style st = s.style;
        // stars stretched into streaks
        int count = (int) (12 + v * 20);
        for (int i = 0; i < count; i++) {
            double a = rnd.nextDouble() * Math.PI * 2, r = 1.6 + rnd.nextDouble() * 9;
            Location l = new Location(hub, TX + Math.cos(a) * r, TY + Math.sin(a) * r, TZ - 26 - rnd.nextDouble() * 14);
            Particle part = rnd.nextInt(5) == 0 ? Particle.FIREWORK : rnd.nextInt(7) == 0 ? Particle.ELECTRIC_SPARK : Particle.END_ROD;
            p.spawnParticle(part, l, 0, 0, 0, 1, 0.5 + v * 1.3 + rnd.nextDouble() * 0.5, null, true);
        }
        // the vortex: two coloured helices twisting along the walls of the tunnel
        if (dt < GATE - JUMP + 10) {
            Particle.DustOptions h1 = dust(st.c1(), 1.6f), h2 = dust(st.c2(), 1.6f);
            for (int i = 0; i < 26; i++) {
                double z = TZ - 3 - i * 1.4;
                double phase = dt * 0.22 * v + i * 0.32;
                double r = 3.6 + Math.sin(i * 0.4 + dt * 0.1) * 0.5;
                p.spawnParticle(Particle.DUST, new Location(hub, TX + Math.cos(phase) * r, TY + Math.sin(phase) * r, z), 1, 0, 0, 0, 0, h1, true);
                p.spawnParticle(Particle.DUST, new Location(hub, TX + Math.cos(phase + Math.PI) * r, TY + Math.sin(phase + Math.PI) * r, z), 1, 0, 0, 0, 0, h2, true);
            }
        }
        // rings of light flying through the camera
        if (dt % 4 == 0 && dt > 6 && dt < GATE - JUMP - 4) {
            int[] pal = {st.c1(), st.c2(), st.c3(), 0xFFFFFF};
            s.rings.add(new double[]{TZ - 40, 2.6 + rnd.nextDouble() * 2.4, pal[rnd.nextInt(pal.length)]});
        }
        for (double[] ring : new ArrayList<>(s.rings)) {
            ring[0] += 1.4 * v;
            if (ring[0] > TZ + 0.5) {
                if (ring[0] - 1.4 * v <= TZ + 0.5) sound(p, "block.note_block.chime", 0.22f, 1.5f + rnd.nextFloat() * 0.5f);
                s.rings.remove(ring);
                continue;
            }
            int rgb = (int) ring[2];
            Particle.DustOptions main = dust(rgb, 2.0f), glow = dust(SpaceDecor.mix(rgb, 0xFFFFFF, 0.6), 1.0f);
            double r = ring[1];
            int pts = (int) (30 + r * 6);
            for (int i = 0; i < pts; i++) {
                double a = dt * 0.09 + i * Math.PI * 2 / pts;
                p.spawnParticle(Particle.DUST, new Location(hub, TX + Math.cos(a) * r, TY + Math.sin(a) * r, ring[0]), 1, 0, 0, 0, 0, i % 3 == 0 ? glow : main, true);
            }
        }
        // flashes of energy along the walls
        if (dt > 20 && dt < GATE - JUMP && rnd.nextInt(14) == 0) {
            double a = rnd.nextDouble() * Math.PI * 2;
            Location l = new Location(hub, TX + Math.cos(a) * 4, TY + Math.sin(a) * 4, TZ - 8 - rnd.nextDouble() * 14);
            p.spawnParticle(Particle.ELECTRIC_SPARK, l, 30, 0.6, 0.6, 1.5, 0.4, null, true);
            p.spawnParticle(Particle.FIREWORK, l, 12, 0.3, 0.3, 0.8, 0.1, null, true);
            sound(p, "entity.lightning_bolt.thunder", 0.08f, 1.8f);
        }

        // the exit gate grows out of the depth
        if (t == GATE) {
            s.gate = hub.spawn(new Location(hub, TX, TY, TZ - 40), TextDisplay.class, d -> {
                plugin.prepare(d);
                d.setVisibleByDefault(false);
                d.setBillboard(Display.Billboard.CENTER);
                d.setBackgroundColor(Color.fromARGB(0, 0, 0, 0));
                d.setShadowed(false);
                d.setBrightness(new Display.Brightness(15, 15));
                d.setTeleportDuration(2);
                d.setInterpolationDuration(2);
                d.text(Component.text("●", TextColor.color(st.c3())));
            });
            p.showEntity(plugin, s.gate);
            s.temp.add(s.gate);
            sound(p, "block.beacon.power_select", 0.8f, 0.7f);
            sound(p, "block.portal.trigger", 0.25f, 1.6f);
        }
        if (s.gate != null && s.gate.isValid() && t > GATE) {
            double k = smooth((t - GATE) / (double) (FULL - GATE));
            float sc = (float) (2 + 48 * k * k);
            double z = TZ - 40 + 37 * k;
            s.gate.setInterpolationDelay(0);
            s.gate.setTransformation(new Transformation(new Vector3f(), new Quaternionf(), new Vector3f(sc, sc, sc), new Quaternionf()));
            s.gate.teleport(new Location(hub, TX, TY - sc * 0.11, z));
            s.gate.text(Component.text("●", TextColor.color(SpaceDecor.mix(st.c3(), 0xFFFFFF, k))));
            if (t % 3 == 0) p.spawnParticle(Particle.END_ROD, new Location(hub, TX, TY, z), 8, sc * 0.08, sc * 0.08, 0.2, 0.02, null, true);
        }
        // light fills the view
        if (t >= WHITE && s.panel != null && s.panel.isValid())
            s.panel.setBackgroundColor(Color.fromARGB((int) (255 * smooth((t - WHITE) / (double) (FULL - WHITE))), 255, 255, 255));
        if (t == FULL - 3) { s.cube = Hyperjump.flashCube(plugin, p, new Location(hub, TX, TY, TZ), Material.WHITE_STAINED_GLASS); s.temp.add(s.cube); }
        if (t == FULL && s.cube != null && s.cube.isValid()) s.cube.setBlock(Material.WHITE_CONCRETE.createBlockData());
        if (t == WHITE) { sound(p, "block.amethyst_block.resonate", 1f, 0.6f); sound(p, "block.respawn_anchor.charge", 0.6f, 1.3f); }
        if (t == FULL) { sound(p, "block.beacon.power_select", 1f, 1.8f); sound(p, "entity.firework_rocket.twinkle", 0.8f, 1.2f); }

        // sound of the flight
        if (dt % 10 == 0) sound(p, "entity.elytra.flying", (float) (0.08 + 0.07 * v), (float) (0.6 + v * 0.3));
        if (dt == 24) sound(p, "block.beacon.ambient", 1f, 1.6f);
        if (dt == 50) sound(p, "item.trident.riptide_2", 0.7f, 1.2f);
        if (dt == 80) sound(p, "block.conduit.activate", 0.6f, 1.4f);

        // HUD
        int pct = Math.min(100, dt * 100 / (FULL - JUMP));
        HudBoard.notice(p, hud(t < GATE ? "ВАРП  " : "ВЫХОД ИЗ ВАРПА  ", st, pct, true));
    }

    private static TextComponent hud(String phase, Style st, int pct, boolean skip) {
        TextComponent.Builder bar = Component.text()
                .append(Component.text(phase, TextColor.color(st.c1())).decorate(TextDecoration.BOLD))
                .append(Component.text(st.course() + "  ", NamedTextColor.GRAY));
        for (int i = 0; i < 16; i++)
            bar.append(Component.text("▰", i < pct * 16 / 100 ? TextColor.color(i % 2 == 0 ? st.c1() : st.c2()) : TextColor.color(0x3C3C3C)));
        bar.append(Component.text("  " + pct + "%", NamedTextColor.WHITE));
        if (skip) bar.append(Component.text("   [Shift — пропустить]", NamedTextColor.DARK_GRAY));
        return bar.build();
    }

    private static void arrive(Session s) {
        Player p = s.p;
        clearTemp(s);
        s.rings.clear();
        p.removePotionEffect(PotionEffectType.SPEED);
        p.setFlySpeed(0.1f);
        Location to = s.target.clone().add(0, 1.6, 0);
        p.teleport(to);
        p.setGameMode(s.mode);
        s.inTunnel = false;
        p.setFallDistance(0);
        p.addPotionEffect(new PotionEffect(PotionEffectType.SLOW_FALLING, 50, 0, false, false, false));
        s.cube = Hyperjump.flashCube(plugin, p, p.getEyeLocation(), Material.WHITE_STAINED_GLASS);
        s.temp.add(s.cube);
        p.showTitle(s.style.arrival());
        HudBoard.notice(p, Component.empty());
        sound(p, "block.beacon.power_select", 0.8f, 1.3f);
        sound(p, "block.amethyst_cluster.break", 1f, 1.1f);
    }

    private static void landing(Session s, int t) {
        Player p = s.p;
        int dt = t - ARRIVE;
        if (s.cube != null && s.cube.isValid()) {
            s.cube.teleport(p.getEyeLocation());
            if (dt == 5) s.cube.setBlock(Material.LIGHT_BLUE_STAINED_GLASS.createBlockData());
            if (dt == 10) { s.cube.remove(); s.cube = null; }
        }
        World w = p.getWorld();
        Location feet = p.getLocation();
        Particle.DustOptions a = dust(s.style.c1(), 1.4f), b = dust(s.style.c2(), 1.2f);
        double r = dt * 0.4;
        for (int i = 0; i < 20; i++) {
            double ang = i * Math.PI / 10;
            w.spawnParticle(Particle.DUST, feet.clone().add(Math.cos(ang) * r, 0.2, Math.sin(ang) * r), 1, 0, 0, 0, 0, i % 2 == 0 ? a : b);
        }
        if (dt == 3) {
            w.spawnParticle(Particle.REVERSE_PORTAL, feet.clone().add(0, 1, 0), 140, 0.6, 1, 0.6, 0.1);
            w.spawnParticle(Particle.END_ROD, feet.clone().add(0, 1, 0), 40, 0.4, 0.8, 0.4, 0.06);
            sound(p, "block.respawn_anchor.set_spawn", 0.6f, 1.5f);
        }
    }

    private static void finish(Session s) {
        end(s);
        Player p = s.p;
        if (p.isOnline() && s.after != null) s.after.accept(p);
    }

    /** Shift in the tunnel: straight to the exit. */
    private static void skip(Session s) {
        if (!s.inTunnel || s.t >= WHITE - 2) return;
        s.t = WHITE - 2;
        if (s.gate == null) tunnel(s, GATE);
        for (double[] r : s.rings) r[0] = TZ + 10;
    }

    private static void clearTemp(Session s) {
        for (Entity e : s.temp) if (e.isValid()) e.remove();
        s.temp.clear();
        s.cube = null; s.panel = null; s.gate = null;
    }

    private static void end(Session s) {
        sessions.remove(s.p.getUniqueId());
        if (s.task != null) s.task.cancel();
        clearTemp(s);
        Player p = s.p;
        if (!p.isOnline()) return;
        p.setWalkSpeed(s.walk <= 0 ? 0.2f : s.walk);
        AttributeInstance jump = p.getAttribute(Attribute.JUMP_STRENGTH);
        if (jump != null) jump.setBaseValue(s.jumpBase <= 0 ? 0.42 : s.jumpBase);
    }

    /** Interrupted (quit, shutdown): drop the player at the destination, never leave them in the tunnel. */
    private static void abort(Session s) {
        end(s);
        Player p = s.p;
        p.removePotionEffect(PotionEffectType.LEVITATION);
        p.removePotionEffect(PotionEffectType.SPEED);
        p.setFlySpeed(0.1f);
        if (p.getGameMode() == GameMode.SPECTATOR) p.setGameMode(s.mode);
        if (s.t >= JUMP) p.teleport(s.target);
        p.setWalkSpeed(s.walk <= 0 ? 0.2f : s.walk);
        AttributeInstance jump = p.getAttribute(Attribute.JUMP_STRENGTH);
        if (jump != null) jump.setBaseValue(s.jumpBase <= 0 ? 0.42 : s.jumpBase);
    }

    static void stopAll() {
        for (Session s : new ArrayList<>(sessions.values())) abort(s);
    }

    @EventHandler
    public void onSneak(PlayerToggleSneakEvent e) {
        if (!e.isSneaking()) return;
        Session s = sessions.get(e.getPlayer().getUniqueId());
        if (s != null) skip(s);
    }

    @EventHandler
    public void onQuit(PlayerQuitEvent e) {
        Session s = sessions.get(e.getPlayer().getUniqueId());
        if (s != null) abort(s);
    }

    private static Particle.DustOptions dust(int rgb, float size) {
        return new Particle.DustOptions(Color.fromRGB(rgb), size);
    }

    private static void sound(Player p, String key, float vol, float pitch) {
        p.playSound(Sound.sound(Key.key("minecraft", key), Sound.Source.MASTER, vol, pitch));
    }
}
