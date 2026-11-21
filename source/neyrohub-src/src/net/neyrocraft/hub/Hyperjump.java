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
import org.bukkit.Particle;
import org.bukkit.World;
import org.bukkit.entity.Display;
import org.bukkit.entity.Entity;
import org.bukkit.entity.ItemDisplay;
import org.bukkit.entity.Player;
import org.bukkit.entity.TextDisplay;
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

/**
 * Arrival on the main deck as the exit from a hyperjump.
 * Phases: accelerate through a tunnel of stars and rings → slow down → an exit gate of
 * light grows and gently fills the view → the deck dissolves in from white while the camera
 * glides to the spawn point → a short rest, then control returns.
 */
final class Hyperjump {

    // the tunnel is far away in the empty part of the hub world
    // the tunnel sits high above the ship: the world below is already loaded, so the camera of the
    // descent that follows can attach right away
    private static final double TX = 0.5, TY = 250, TZ = 0.5;

    private static final int FADE_IN = 12;        // white of the visor fades into the tunnel
    private static final int CRUISE_END = 88;     // full speed until here
    private static final int GATE_START = 88;     // exit gate appears, the tunnel slows down
    private static final int WHITE_START = 110;   // light starts to fill the view
    private static final int WHITE_FULL = 126;    // view fully white
    private static final int WHITE_HOLD = 138;    // bright light holds until here
    private static final int ARRIVE = 142;        // deck camera takes over
    private static final int DECK_FADE = 46;      // white dissolves on the deck
    private static final int GLIDE = 62;          // camera arc down to the spawn point
    private static final int TITLE_AT = ARRIVE + 30;
    private static final int TOTAL = ARRIVE + GLIDE + 14;   // rest before control returns

    private final NeyroHub plugin;
    private final World hub;
    private final int floorY;
    private final Random rnd = new Random();
    private final Map<UUID, Session> sessions = new HashMap<>();

    private final class Session {
        final Player player;
        BukkitTask task;
        int t;
        ItemDisplay tunnelCam, deckCam;
        TextDisplay flash, gate;
        final List<TextDisplay> rings = new ArrayList<>();
        final List<double[]> prings = new ArrayList<>();      // particle rings: {z, radius, rgb}
        org.bukkit.entity.BlockDisplay cube;
        final List<Entity> extra = new ArrayList<>();
        GameMode mode;

        Session(Player player) {
            this.player = player;
        }
    }

    Hyperjump(NeyroHub plugin, World hub, int floorY) {
        this.plugin = plugin;
        this.hub = hub;
        this.floorY = floorY;
        hub.setChunkForceLoaded((int) Math.floor(TX) >> 4, (int) Math.floor(TZ) >> 4, true);
    }

    /** Where the player stands so that their eyes are exactly on the flight axis. */
    private Location eyePoint(float yaw, float pitch) {
        return new Location(hub, TX, TY - 1.62, TZ, yaw, pitch);
    }

    void scan() {
        // nothing to precompute
    }

    boolean isLoading(Player p) {
        return sessions.containsKey(p.getUniqueId());
    }

    void start(Player p) {
        stop(p, false);
        Session s = new Session(p);
        sessions.put(p.getUniqueId(), s);
        s.mode = p.getGameMode() == GameMode.CREATIVE ? GameMode.CREATIVE : GameMode.ADVENTURE;
        // the player's own eyes are the camera, placed exactly on the flight axis
        p.setGameMode(GameMode.SPECTATOR);
        p.teleport(eyePoint(180f, 0f));
        p.setFlySpeed(0f);
        s.flash = whitePanel(s, new Location(hub, TX, TY, TZ - 0.35), 255);
        s.cube = flashCube(plugin, p, new Location(hub, TX, TY, TZ), org.bukkit.Material.WHITE_CONCRETE);
        s.extra.add(s.cube);
        // the speed effect widens the field of view: a feeling of acceleration
        p.addPotionEffect(new PotionEffect(PotionEffectType.SPEED, CRUISE_END + 10, 6, false, false, false));
        p.showTitle(Title.title(
                Component.text("◈ ГИПЕРПРЫЖОК ◈", TextColor.color(NeyroHub.ORANGE)).decorate(TextDecoration.BOLD),
                Component.text("курс: главная палуба NEYRO-1", NamedTextColor.GRAY),
                Title.Times.times(Duration.ofMillis(300), Duration.ofMillis(2000), Duration.ofMillis(700))));
        sound(p, "item.trident.riptide_3", 1f, 0.7f);
        sound(p, "block.beacon.activate", 0.7f, 0.5f);
        s.task = Bukkit.getScheduler().runTaskTimer(plugin, () -> tick(s), 1L, 1L);
    }

    /** A full-view white panel right in front of a camera; alpha 0..255. */
    private TextDisplay whitePanel(Session s, Location at, int alpha) {
        TextDisplay d = whitePanel(plugin, s.player, at, alpha);
        s.extra.add(d);
        return d;
    }

    /**
     * A glowing cube turned inside out (negative scale) around the camera: its inner faces
     * are rendered, so it covers the whole screen whatever the field of view.
     */
    static org.bukkit.entity.BlockDisplay flashCube(NeyroHub plugin, Player viewer, Location centre, org.bukkit.Material m) {
        org.bukkit.entity.BlockDisplay d = centre.getWorld().spawn(centre, org.bukkit.entity.BlockDisplay.class, e -> {
            plugin.prepare(e);
            e.setVisibleByDefault(false);
            e.setBlock(m.createBlockData());
            e.setBrightness(new Display.Brightness(15, 15));
            e.setTeleportDuration(1);
            e.setTransformation(new Transformation(new Vector3f(1.5f, 1.5f, 1.5f), new Quaternionf(),
                    new Vector3f(-3f, -3f, -3f), new Quaternionf()));
        });
        viewer.showEntity(plugin, d);
        return d;
    }

    static final float PANEL_SCALE = 0.42f;
    static final double PANEL_HALF = 22 * 9 * 0.025 * PANEL_SCALE / 2;   // half the panel height in blocks

    /**
     * A white rectangle (~2×2 blocks) centred on {@code eyeAhead}, a point just in front of the camera.
     * Text displays grow upwards from their anchor, so the anchor sits half a panel lower.
     */
    static TextDisplay whitePanel(NeyroHub plugin, Player viewer, Location eyeAhead, int alpha) {
        TextDisplay d = eyeAhead.getWorld().spawn(eyeAhead.clone().add(0, -PANEL_HALF, 0), TextDisplay.class, e -> {
            plugin.prepare(e);
            e.setVisibleByDefault(false);
            e.setBillboard(Display.Billboard.CENTER);
            e.setBackgroundColor(Color.fromARGB(alpha, 255, 255, 255));
            e.setLineWidth(1000);
            e.setBrightness(new Display.Brightness(15, 15));
            e.setTeleportDuration(1);
            e.setTransformation(new Transformation(new Vector3f(), new Quaternionf(),
                    new Vector3f(PANEL_SCALE, PANEL_SCALE, PANEL_SCALE), new Quaternionf()));
            StringBuilder sb = new StringBuilder();
            for (int i = 0; i < 22; i++) sb.append(i == 0 ? "" : "\n").append(" ".repeat(48));
            e.text(Component.text(sb.toString()));
        });
        viewer.showEntity(plugin, d);
        return d;
    }

    private static double smooth(double k) {
        k = Math.max(0, Math.min(1, k));
        return k * k * (3 - 2 * k);
    }

    /** Tunnel speed: accelerate, cruise, then ease down before the gate. */
    private static double speed(int t) {
        if (t <= CRUISE_END) return 1.0 + Math.min(1.0, t / 40.0) * 2.0;
        return 3.0 - 2.6 * smooth((t - CRUISE_END) / (double) (WHITE_FULL - CRUISE_END));
    }

    private void tick(Session s) {
        Player p = s.player;
        if (!p.isOnline()) {
            stop(p, false);
            return;
        }
        s.t++;
        int t = s.t;

        if (t < WHITE_HOLD) tunnel(s, t);
        if (t >= WHITE_HOLD) {
            // out of hyperspace high above the ship: hand over to the spiral descent onto the deck
            GameMode mode = s.mode;
            sessions.remove(p.getUniqueId());
            if (s.task != null) s.task.cancel();
            p.setFlySpeed(0.1f);
            for (TextDisplay r : s.rings) if (r.isValid()) r.remove();
            for (Entity e : s.extra) if (e.isValid()) e.remove();
            if (s.tunnelCam != null && s.tunnelCam.isValid()) s.tunnelCam.remove();
            p.removePotionEffect(PotionEffectType.SPEED);
            HudBoard.notice(p, Component.empty());
            plugin.arrivalAfterJump(p, mode);
            return;
        }
        if (t == TITLE_AT) {
            p.showTitle(Title.title(
                    NeyroHub.serverName(),
                    Component.text()
                            .append(Component.text("Добро пожаловать на борт, ", NamedTextColor.GRAY))
                            .append(Component.text(p.getName(), TextColor.color(NeyroHub.ORANGE)).decorate(TextDecoration.BOLD))
                            .build(),
                    Title.Times.times(Duration.ofMillis(800), Duration.ofMillis(3000), Duration.ofMillis(1200))));
            sound(p, "block.amethyst_block.resonate", 0.7f, 1.2f);
        }
        if (t >= TOTAL) finish(s);
    }

    private void tunnel(Session s, int t) {
        Player p = s.player;
        double speed = speed(t);
        // stay on the axis (looking around is fine)
        Location here = p.getLocation();
        if (!here.getWorld().equals(hub) || here.distanceSquared(eyePoint(0, 0)) > 0.04)
            p.teleport(eyePoint(here.getYaw(), here.getPitch()));

        // white of the visor fades into the tunnel
        if (t <= FADE_IN && s.flash != null && s.flash.isValid())
            s.flash.setBackgroundColor(Color.fromARGB((int) (255 * (1 - smooth(t / (double) FADE_IN))), 255, 255, 255));


        // streams of stars rushing towards the camera
        int count = (int) (10 + speed * 18);
        for (int i = 0; i < count; i++) {
            double a = rnd.nextDouble() * Math.PI * 2, r = 1.4 + rnd.nextDouble() * 8.0;
            Location l = new Location(hub, TX + Math.cos(a) * r, TY + Math.sin(a) * r, TZ - 26 - rnd.nextDouble() * 12);
            Particle part = rnd.nextInt(5) == 0 ? Particle.FIREWORK : rnd.nextInt(6) == 0 ? Particle.ELECTRIC_SPARK : Particle.END_ROD;
            p.spawnParticle(part, l, 0, 0, 0, 1, 0.4 + speed * 1.2 + rnd.nextDouble() * 0.5, null, true);
        }

        // full-screen white at the start dissolves: solid → translucent → gone
        if (t == 5 && s.cube != null && s.cube.isValid()) s.cube.setBlock(org.bukkit.Material.WHITE_STAINED_GLASS.createBlockData());
        if (t == 9 && s.cube != null && s.cube.isValid()) s.cube.remove();

        // rings of light centred exactly on the flight axis, the camera flies through each one
        if (t % 4 == 0 && t > 6 && t < GATE_START - 4) {
            int[] palette = {0xFF8C00, 0x55E0FF, 0xFFFFFF, 0xB070FF, 0xFFB347};
            s.prings.add(new double[]{TZ - 38, 2.6 + rnd.nextDouble() * 2.2, palette[rnd.nextInt(palette.length)]});
        }
        for (double[] ring : new ArrayList<>(s.prings)) {
            ring[0] += 1.3 * speed;
            if (ring[0] > TZ + 0.5) {
                if (ring[0] - 1.3 * speed <= TZ + 0.5) sound(p, "block.note_block.chime", 0.25f, 1.6f + rnd.nextFloat() * 0.4f);
                s.prings.remove(ring);
                continue;
            }
            int rgb = (int) ring[2];
            Particle.DustOptions main = new Particle.DustOptions(Color.fromRGB(rgb), 1.9f);
            Particle.DustOptions glow = new Particle.DustOptions(Color.fromRGB(SpaceDecor.mix(rgb, 0xFFFFFF, 0.6)), 1.0f);
            double r = ring[1];
            int pts = (int) (28 + r * 6);
            double spin = t * 0.08;
            for (int i = 0; i < pts; i++) {
                double a = spin + i * Math.PI * 2 / pts;
                Location l = new Location(hub, TX + Math.cos(a) * r, TY + Math.sin(a) * r, ring[0]);
                p.spawnParticle(Particle.DUST, l, 1, 0, 0, 0, 0, i % 3 == 0 ? glow : main, true);
            }
        }

        // the exit gate: a disc of light that grows and comes closer
        if (t == GATE_START) {
            s.gate = hub.spawn(new Location(hub, TX, TY, TZ - 40), TextDisplay.class, d -> {
                plugin.prepare(d);
                d.setVisibleByDefault(false);
                d.setBillboard(Display.Billboard.CENTER);
                d.setBackgroundColor(Color.fromARGB(0, 0, 0, 0));
                d.setShadowed(false);
                d.setBrightness(new Display.Brightness(15, 15));
                d.setTeleportDuration(2);
                d.setInterpolationDuration(2);
                d.text(Component.text("●", TextColor.color(0xFFF4E0)));
            });
            p.showEntity(plugin, s.gate);
            s.extra.add(s.gate);
            sound(p, "block.beacon.power_select", 0.8f, 0.7f);
            sound(p, "block.portal.trigger", 0.25f, 1.6f);
        }
        if (s.gate != null && s.gate.isValid() && t > GATE_START) {
            double k = smooth((t - GATE_START) / (double) (WHITE_FULL - GATE_START));
            float sc = (float) (2 + 46 * k * k);
            double z = TZ - 40 + 37 * k;
            s.gate.setInterpolationDelay(0);
            s.gate.setTransformation(new Transformation(new Vector3f(), new Quaternionf(), new Vector3f(sc, sc, sc), new Quaternionf()));
            s.gate.teleport(new Location(hub, TX, TY - sc * 0.11, z));
            s.gate.text(Component.text("●", TextColor.color(SpaceDecor.mix(0xFFB060, 0xFFFFFF, k))));
            if (t % 3 == 0) p.spawnParticle(Particle.END_ROD, new Location(hub, TX, TY, z), 6, sc * 0.08, sc * 0.08, 0.2, 0.02, null, true);
        }

        // light gently fills the view
        if (t >= WHITE_START && s.flash != null && s.flash.isValid()) {
            double k = smooth((t - WHITE_START) / (double) (WHITE_FULL - WHITE_START));
            s.flash.setBackgroundColor(Color.fromARGB((int) (255 * k), 255, 255, 255));
        }
        // full-screen flash: translucent white, then solid white around the camera
        if (t == WHITE_FULL - 4) {
            s.cube = flashCube(plugin, p, new Location(hub, TX, TY, TZ), org.bukkit.Material.WHITE_STAINED_GLASS);
            s.extra.add(s.cube);
        }
        if (t == WHITE_FULL && s.cube != null && s.cube.isValid()) s.cube.setBlock(org.bukkit.Material.WHITE_CONCRETE.createBlockData());
        if (t == WHITE_START) {
            sound(p, "block.amethyst_block.resonate", 1f, 0.6f);
            sound(p, "block.respawn_anchor.charge", 0.6f, 1.2f);
        }
        // bright light: the view stays white and flares with sparks
        if (t >= WHITE_FULL - 4 && t <= WHITE_HOLD) {
            p.spawnParticle(Particle.END_ROD, new Location(hub, TX, TY, TZ - 2.5), 10, 1.4, 1.0, 0.4, 0.06, null, true);
            if (t % 3 == 0) p.spawnParticle(Particle.FIREWORK, new Location(hub, TX, TY, TZ - 3), 6, 1.6, 1.2, 0.4, 0.05, null, true);
        }
        if (t == WHITE_FULL) {
            sound(p, "block.beacon.power_select", 1f, 1.8f);
            sound(p, "block.amethyst_block.resonate", 1f, 1.6f);
        }

        // HUD
        int pct = Math.min(100, t * 100 / WHITE_FULL);
        String phase = t < GATE_START ? "ГИПЕРПРЫЖОК  " : "ВЫХОД ИЗ ПРЫЖКА  ";
        TextComponent.Builder bar = Component.text()
                .append(Component.text(phase, TextColor.color(NeyroHub.ORANGE)).decorate(TextDecoration.BOLD))
                .append(Component.text("курс: палуба NEYRO-1  ", NamedTextColor.GRAY));
        for (int i = 0; i < 14; i++)
            bar.append(Component.text("▰", i < pct * 14 / 100 ? TextColor.color(NeyroHub.ORANGE) : TextColor.color(0x3C3C3C)));
        bar.append(Component.text("  " + pct + "%", NamedTextColor.WHITE))
                .append(Component.text("   [Shift — пропустить]", NamedTextColor.DARK_GRAY));
        HudBoard.notice(p, bar.build());

        if (t % 12 == 0) sound(p, "entity.elytra.flying", (float) (0.08 + 0.08 * speed), (float) (0.6 + speed * 0.3));
        if (t == 30) sound(p, "block.beacon.ambient", 1f, 1.5f);
        if (t == GATE_START + 4) sound(p, "block.beacon.deactivate", 0.6f, 1.4f);
        if (t == 60) sound(p, "item.trident.riptide_2", 0.7f, 1.1f);
    }

    private void switchToDeck(Session s) {
        Player p = s.player;
        p.setSpectatorTarget(null);
        p.teleport(new Location(hub, 0.5, floorY + 1, 0.5, 180f, 0f));
        Location start = deckCamAt(0);
        s.deckCam = hub.spawn(start, ItemDisplay.class, d -> {
            plugin.prepare(d);
            d.setTeleportDuration(2);
        });
        if (s.flash != null && s.flash.isValid()) s.flash.remove();
        if (s.gate != null && s.gate.isValid()) s.gate.remove();
        s.flash = whitePanel(s, start.clone().add(start.getDirection().multiply(0.3)), 255);
        p.removePotionEffect(PotionEffectType.SPEED);
        HudBoard.notice(p, Component.empty());
    }

    private void deck(Session s, int t) {
        Player p = s.player;
        if (s.deckCam == null || !s.deckCam.isValid()) return;
        int dt = t - ARRIVE;
        if (dt == 0 || (t % 10 == 0 && !s.deckCam.equals(p.getSpectatorTarget()))) p.setSpectatorTarget(s.deckCam);
        if (dt == 0) {
            p.addPotionEffect(new PotionEffect(PotionEffectType.SLOWNESS, 20, 1, false, false, false));
            sound(p, "block.beacon.power_select", 0.7f, 1.6f);
        }
        // camera glide
        double k = Math.min(1.0, dt / (double) GLIDE);
        Location cam = deckCamAt(k);
        s.deckCam.teleport(cam);
        // white dissolves slowly
        if (s.flash != null && s.flash.isValid()) {
            s.flash.teleport(cam.clone().add(cam.getDirection().multiply(0.3)).add(0, -0.34, 0));
            double f = Math.min(1.0, dt / (double) DECK_FADE);
            int alpha = (int) (255 * Math.pow(1 - f, 2.2));
            s.flash.setBackgroundColor(Color.fromARGB(alpha, 255, 255, 255));
            if (alpha <= 0) s.flash.remove();
        }
        double floor = floorY + 1;
        // a pillar of light at the spawn point, fading as the camera arrives
        if (dt < GLIDE - 6) {
            double strength = 1 - dt / (double) (GLIDE - 6);
            int n = (int) (6 + 10 * strength);
            for (int i = 0; i < n; i++) {
                double h = rnd.nextDouble() * 12;
                double a = rnd.nextDouble() * Math.PI * 2, r = rnd.nextDouble() * 0.45 * (1 + h / 12);
                p.spawnParticle(Particle.END_ROD, new Location(hub, 0.5 + Math.cos(a) * r, floor + h, 0.5 + Math.sin(a) * r),
                        0, 0, 1, 0, 0.12 + rnd.nextDouble() * 0.1);
            }
            if (dt % 2 == 0) p.spawnParticle(Particle.DUST, new Location(hub, 0.5, floor + 1.5, 0.5), 6, 0.35, 1.2, 0.35, 0,
                    new Particle.DustOptions(Color.fromRGB(255, 170, 60), 1.4f));
        }
        // three waves of light rolling across the floor
        for (int start : new int[]{4, 14, 24}) {
            int w = dt - start;
            if (w < 0 || w > 22 || w % 2 != 0) continue;
            double r = 0.8 + w * 0.9;
            int pts = (int) (16 + r * 8);
            for (int i = 0; i < pts; i++) {
                double a = i * Math.PI * 2 / pts;
                p.spawnParticle(Particle.DUST, new Location(hub, 0.5 + Math.cos(a) * r, floor + 0.1, 0.5 + Math.sin(a) * r), 1, 0, 0, 0, 0,
                        new Particle.DustOptions(i % 3 == 0 ? Color.fromRGB(255, 150, 30) : Color.WHITE, 1.5f));
            }
            if (w == 0) sound(p, "block.amethyst_block.chime", 0.7f, 1.2f + start / 40f);
        }
        // sparkles drifting down around the arrival point
        if (dt % 2 == 0) {
            p.spawnParticle(Particle.END_ROD, new Location(hub, 0.5, floor + 5, 0.5), 3, 4, 2.5, 4, 0.004);
            if (dt % 6 == 0) p.spawnParticle(Particle.FIREWORK, new Location(hub, 0.5, floor + 3, 0.5), 2, 3.5, 1.5, 3.5, 0.01);
        }
        if (dt == 2) sound(p, "block.beacon.activate", 0.8f, 1.3f);
    }

    /**
     * Camera on the deck: starts high and to the side, circles a quarter turn around the
     * spawn point while descending, and ends exactly in the player's eyes facing north.
     */
    private Location deckCamAt(double k) {
        double e = 1 - Math.pow(1 - k, 3.2);
        double eyeY = floorY + 1 + 1.62;                     // floor surface is floorY + 1
        double radius = 12 * (1 - e);
        double ang = Math.toRadians(100 * (1 - e));          // 100° → 0° measured from south
        double x = 0.5 + Math.sin(ang) * radius;
        double z = 0.5 + Math.cos(ang) * radius;
        double y = eyeY + 5.5 * (1 - e) * (1 - e) + 0.6 * Math.sin(e * Math.PI) * (1 - e);
        // look at the pillar of light while far away, then turn to face north
        double dx = 0.5 - x, dz = 0.5 - z, dy = (eyeY + 0.8) - y;
        double flat = Math.sqrt(dx * dx + dz * dz);
        double lookYaw = flat < 0.05 ? 180 : Math.toDegrees(Math.atan2(-dx, dz));
        double lookPitch = -Math.toDegrees(Math.atan2(dy, Math.max(flat, 0.05)));
        double blend = Math.max(0, (e - 0.7) / 0.3);
        blend = blend * blend * (3 - 2 * blend);
        double dyaw = ((180 - lookYaw + 540) % 360) - 180;
        float yaw = (float) (lookYaw + dyaw * blend);
        float pitch = (float) (lookPitch * (1 - blend));
        return new Location(hub, x, y, z, yaw, Math.max(-89, Math.min(89, pitch)));
    }

    private void spawnRing(Session s) {
        int[] palette = {0xFF8C00, 0x55E0FF, 0xFFFFFF, 0xB070FF, 0xFFB347};
        int color = palette[rnd.nextInt(palette.length)];
        float size = 11f + rnd.nextFloat() * 10f;
        double ox = (rnd.nextDouble() - 0.5) * 1.2, oy = (rnd.nextDouble() - 0.5) * 1.2;
        TextDisplay ring = hub.spawn(new Location(hub, TX + ox, TY - size * 0.16 + oy, TZ - 38), TextDisplay.class, d -> {
            plugin.prepare(d);
            d.setVisibleByDefault(false);
            d.setBillboard(Display.Billboard.CENTER);
            d.setBackgroundColor(Color.fromARGB(0, 0, 0, 0));
            d.setShadowed(false);
            d.setBrightness(new Display.Brightness(15, 15));
            d.setTeleportDuration(1);
            d.setTransformation(new Transformation(new Vector3f(), new Quaternionf(), new Vector3f(size, size, size), new Quaternionf()));
            d.text(Component.text("◯", TextColor.color(color)));
        });
        s.player.showEntity(plugin, ring);
        s.rings.add(ring);
    }

    private void finish(Session s) {
        Player p = s.player;
        boolean skipped = s.t < TITLE_AT;
        stop(p, true);
        if (skipped) {
            p.showTitle(Title.title(
                    NeyroHub.serverName(),
                    Component.text("Добро пожаловать на борт, " + p.getName(), NamedTextColor.GRAY),
                    Title.Times.times(Duration.ofMillis(300), Duration.ofMillis(2200), Duration.ofMillis(900))));
        }
        // materialization: particles gather into the player as control returns
        Location body = new Location(hub, 0.5, floorY + 2, 0.5);
        p.getWorld().spawnParticle(Particle.PORTAL, body, 120, 0.9, 1.1, 0.9, 0.9);
        p.getWorld().spawnParticle(Particle.END_ROD, body, 30, 0.4, 0.8, 0.4, 0.03);
        p.getWorld().spawnParticle(Particle.DUST, body, 40, 0.5, 0.9, 0.5, 0,
                new Particle.DustOptions(Color.fromRGB(255, 160, 40), 1.2f));
        sound(p, "block.respawn_anchor.set_spawn", 0.6f, 1.5f);
        sound(p, "block.note_block.chime", 0.5f, 1.2f);
        Component note = Component.text()
                .append(Component.text("✦ ", TextColor.color(NeyroHub.ORANGE)))
                .append(Component.text(p.getName(), TextColor.color(NeyroHub.ORANGE)))
                .append(Component.text(" прибыл на палубу", NamedTextColor.GRAY))
                .build();
        for (Player other : hub.getPlayers()) if (!other.equals(p)) HudBoard.notice(other, note);
    }

    void skip(Player p) {
        Session s = sessions.get(p.getUniqueId());
        if (s != null) finish(s);
    }

    void stop(Player p, boolean restore) {
        Session s = sessions.remove(p.getUniqueId());
        if (s == null) return;
        if (s.task != null) s.task.cancel();
        if (p.isOnline() && p.getGameMode() == GameMode.SPECTATOR) p.setSpectatorTarget(null);
        for (TextDisplay r : s.rings) if (r.isValid()) r.remove();
        for (Entity e : s.extra) if (e.isValid()) e.remove();
        if (s.tunnelCam != null && s.tunnelCam.isValid()) s.tunnelCam.remove();
        if (s.deckCam != null && s.deckCam.isValid()) s.deckCam.remove();
        if (!p.isOnline()) return;
        p.setFlySpeed(0.1f);
        p.removePotionEffect(PotionEffectType.SPEED);
        p.removePotionEffect(PotionEffectType.SLOWNESS);
        if (!restore) return;
        p.teleport(new Location(hub, 0.5, floorY + 1, 0.5, 180f, 0f));
        p.setGameMode(s.mode);
        HudBoard.notice(p, Component.empty());
    }

    void stopAll() {
        for (Session s : new ArrayList<>(sessions.values())) stop(s.player, true);
    }

    private static void sound(Player p, String key, float volume, float pitch) {
        p.playSound(Sound.sound(Key.key("minecraft", key), Sound.Source.MASTER, volume, pitch));
    }
}
