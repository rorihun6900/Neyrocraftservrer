package net.neyrocraft.hub;

import net.kyori.adventure.key.Key;
import net.kyori.adventure.sound.Sound;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.TextComponent;
import net.kyori.adventure.text.format.NamedTextColor;
import net.kyori.adventure.text.format.TextColor;
import net.kyori.adventure.title.Title;
import org.bukkit.Bukkit;
import org.bukkit.Color;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.Particle;
import org.bukkit.World;
import org.bukkit.block.Block;
import org.bukkit.entity.BlockDisplay;
import org.bukkit.entity.Display;
import org.bukkit.entity.ItemDisplay;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.entity.EntityDamageEvent;
import org.bukkit.event.entity.EntityDismountEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.event.player.PlayerToggleSneakEvent;
import org.bukkit.scheduler.BukkitTask;
import org.bukkit.util.Transformation;
import org.joml.AxisAngle4f;
import org.joml.Vector3f;

import java.time.Duration;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.function.Consumer;

/**
 * Arrival by a beam of light: the player appears high above the destination in a white flash,
 * slides down a column of light while circling it, and lands softly in a ring of light.
 * Used when leaving the ship for survival (the city, any saved place) and for the skyblock island.
 */
final class BeamDrop implements Listener {

    /** Colours and texts of one kind of arrival. */
    record Style(int a, int b, Component title, Component subtitle, Component landTitle, Component landSubtitle) {}

    private static final class Session {
        ItemDisplay seat;
        BlockDisplay flash;
        final List<BlockDisplay> beam = new ArrayList<>();
        BukkitTask task;
        Location target;
        double startY, dy = -0.6;
        boolean measured;
        int t, ticks;
        Style style;
        Consumer<Player> onLand;
    }

    private final NeyroHub plugin;
    private final Map<UUID, Session> sessions = new HashMap<>();

    BeamDrop(NeyroHub plugin) {
        this.plugin = plugin;
    }

    boolean busy(Player p) {
        return sessions.containsKey(p.getUniqueId());
    }

    /**
     * The highest feet height above {@code target} (up to {@code max} blocks) with free air all the way
     * down: under a roof or in a cave the slide is shorter.
     */
    static double clearTop(Location target, int max) {
        World w = target.getWorld();
        int x = target.getBlockX(), z = target.getBlockZ(), y0 = target.getBlockY();
        int limit = Math.min(w.getMaxHeight() - 3, y0 + max);
        for (int y = y0 + 2; y <= limit; y++) {
            Block b = w.getBlockAt(x, y, z);
            if (!b.isPassable() || b.isLiquid()) return y - 2.2;
        }
        return limit;
    }

    /** Slides the player down to {@code target}, starting at feet height {@code startY}. */
    void run(Player p, Location target, double startY, int ticks, Style style, boolean showBeam, Consumer<Player> onLand) {
        stop(p, false);
        World w = target.getWorld();
        if (startY - target.getY() < 4) {               // no room above: just materialise
            p.setFallDistance(0);
            Location to = target.clone();
            to.setYaw(p.getLocation().getYaw());
            p.teleport(to);
            BlockDisplay f = Hyperjump.flashCube(plugin, p, p.getEyeLocation(), Material.WHITE_STAINED_GLASS);
            Bukkit.getScheduler().runTaskLater(plugin, () -> { if (f.isValid()) f.remove(); }, 8L);
            landingEffects(p, style, to);
            if (onLand != null) onLand.accept(p);
            return;
        }
        Session s = new Session();
        s.target = target.clone();
        s.startY = startY;
        s.ticks = ticks;
        s.style = style;
        s.onLand = onLand;
        Location start = new Location(w, target.getX(), startY, target.getZ() + 1.6, p.getLocation().getYaw(), 35f);
        p.setFallDistance(0);
        p.teleport(start);
        s.seat = w.spawn(new Location(w, start.getX(), startY - s.dy, start.getZ()), ItemDisplay.class, e -> {
            plugin.prepare(e);
            e.setTeleportDuration(2);
        });
        s.seat.addPassenger(p);
        s.flash = Hyperjump.flashCube(plugin, p, p.getEyeLocation(), Material.WHITE_CONCRETE);
        if (showBeam) {
            float h = (float) (startY + 3 - target.getY());
            Location base = new Location(w, target.getX(), target.getY(), target.getZ());
            s.beam.add(beam(base, Material.LIGHT_BLUE_STAINED_GLASS, 1.4f, h));
            s.beam.add(beam(base, Material.WHITE_STAINED_GLASS, 0.45f, h));
        }
        sessions.put(p.getUniqueId(), s);
        p.showTitle(Title.title(style.title(), style.subtitle(),
                Title.Times.times(Duration.ofMillis(300), Duration.ofMillis(Math.max(1500, ticks * 40L)), Duration.ofMillis(700))));
        sound(p, "block.beacon.power_select", 0.9f, 0.7f);
        sound(p, "entity.firework_rocket.blast", 0.6f, 0.6f);
        sound(p, "block.portal.travel", 0.15f, 1.7f);
        s.task = Bukkit.getScheduler().runTaskTimer(plugin, () -> step(p, s), 1L, 1L);
    }

    private BlockDisplay beam(Location base, Material m, float width, float height) {
        return base.getWorld().spawn(base, BlockDisplay.class, d -> {
            plugin.prepare(d);
            d.setBlock(m.createBlockData());
            d.setViewRange(5f);
            d.setBrightness(new Display.Brightness(15, 15));
            d.setTransformation(new Transformation(new Vector3f(-width / 2, 0, -width / 2), new AxisAngle4f(),
                    new Vector3f(width, height, width), new AxisAngle4f()));
        });
    }

    private void step(Player p, Session s) {
        if (!p.isOnline() || s.seat == null || !s.seat.isValid()) { stop(p, false); return; }
        s.t++;
        if (!s.seat.getPassengers().contains(p)) s.seat.addPassenger(p);
        if (!s.measured && s.t == 3 && s.seat.getPassengers().contains(p)) {
            s.dy = p.getLocation().getY() - s.seat.getLocation().getY();
            s.measured = true;
        }
        double k = Math.min(1.0, s.t / (double) s.ticks);
        double e = k * k * k * (k * (k * 6 - 15) + 10);                     // smootherstep
        double feet = s.startY - (s.startY - s.target.getY()) * e;
        double rad = 1.6 * (1 - e), ang = Math.PI / 2 + s.t * 0.09;
        World w = s.target.getWorld();
        double x = s.target.getX() + Math.cos(ang) * rad, z = s.target.getZ() + Math.sin(ang) * rad;
        s.seat.teleport(new Location(w, x, feet - s.dy, z), io.papermc.paper.entity.TeleportFlag.EntityState.RETAIN_PASSENGERS);

        if (s.flash != null && s.flash.isValid()) {                          // white → glass → blue → gone
            s.flash.teleport(p.getEyeLocation());
            if (s.t == 6) s.flash.setBlock(Material.WHITE_STAINED_GLASS.createBlockData());
            if (s.t == 12) s.flash.setBlock(Material.LIGHT_BLUE_STAINED_GLASS.createBlockData());
            if (s.t >= 18) { s.flash.remove(); s.flash = null; }
        }
        Location feetL = new Location(w, x, feet, z);
        Particle.DustOptions a = dust(s.style.a(), 1.3f), b = dust(s.style.b(), 1.1f);
        for (int i = 0; i < 4; i++) {
            double an = s.t * 0.4 + i * Math.PI / 2;
            w.spawnParticle(Particle.DUST, feetL.clone().add(Math.cos(an) * 0.9, 0.2 + (i % 2) * 1.2, Math.sin(an) * 0.9), 1, 0, 0, 0, 0, i % 2 == 0 ? a : b);
        }
        w.spawnParticle(Particle.END_ROD, feetL.clone().add(0, -1.5, 0), 2, 0.3, 0.6, 0.3, 0.02);
        if (s.t % 12 == 0) sound(p, "block.amethyst_block.chime", 0.7f, (float) (0.6 + k * 1.2));
        if (k < 1) {
            int pct = (int) Math.round(e * 100);
            TextComponent.Builder bar = Component.text().append(Component.text("Спуск по лучу  ", NamedTextColor.GRAY));
            for (int i = 0; i < 10; i++) bar.append(Component.text("▰", i < pct / 10 ? TextColor.color(s.style.a()) : TextColor.color(0x3C3C3C)));
            bar.append(Component.text("  " + pct + "%", NamedTextColor.WHITE))
                    .append(Component.text("   [Shift — пропустить]", NamedTextColor.DARK_GRAY));
            p.sendActionBar(bar.build());
            return;
        }
        stop(p, true);
    }

    /** Ends the slide on the target; with {@code effects} also the landing title, sound and light ring. */
    void stop(Player p, boolean effects) {
        Session s = sessions.remove(p.getUniqueId());
        if (s == null) return;
        if (s.task != null) s.task.cancel();
        if (s.flash != null && s.flash.isValid()) s.flash.remove();
        if (s.seat != null && s.seat.isValid()) { s.seat.eject(); s.seat.remove(); }
        Bukkit.getScheduler().runTaskLater(plugin, () -> s.beam.forEach(d -> { if (d.isValid()) d.remove(); }), 25L);
        if (!p.isOnline()) return;
        Location look = p.getLocation();
        Location to = s.target.clone();
        to.setYaw(look.getYaw());
        to.setPitch(Math.min(20f, look.getPitch()));
        p.teleport(to);
        p.setFallDistance(0);
        p.sendActionBar(Component.empty());
        if (effects) landingEffects(p, s.style, to);
        if (s.onLand != null) s.onLand.accept(p);
    }

    private void landingEffects(Player p, Style style, Location l) {
        p.showTitle(Title.title(style.landTitle(), style.landSubtitle(),
                Title.Times.times(Duration.ofMillis(200), Duration.ofMillis(2500), Duration.ofMillis(800))));
        sound(p, "block.respawn_anchor.set_spawn", 0.9f, 1.3f);
        sound(p, "block.amethyst_cluster.break", 1f, 1.1f);
        World w = l.getWorld();
        w.spawnParticle(Particle.END_ROD, l.clone().add(0, 0.3, 0), 50, 0.4, 0.2, 0.4, 0.15);
        Particle.DustOptions wave = dust(style.a(), 1.5f);
        for (int step = 0; step < 10; step++) {
            final double r = 1 + step * 0.8;
            Bukkit.getScheduler().runTaskLater(plugin, () -> {
                int points = (int) (r * 10);
                for (int i = 0; i < points; i++) {
                    double a = i * Math.PI * 2 / points;
                    w.spawnParticle(Particle.DUST, l.clone().add(Math.cos(a) * r, 0.1, Math.sin(a) * r), 1, 0, 0, 0, 0, wave);
                }
            }, step);
        }
    }

    void shutdown() {
        for (UUID id : new ArrayList<>(sessions.keySet())) {
            Player p = Bukkit.getPlayer(id);
            if (p != null) stop(p, false);
            else sessions.remove(id);
        }
    }

    // ---- while sliding: nobody gets off the beam, nothing hurts, Shift lands at once

    @EventHandler(priority = EventPriority.LOWEST)
    public void onDismount(EntityDismountEvent e) {
        if (!(e.getEntity() instanceof Player p)) return;
        Session s = sessions.get(p.getUniqueId());
        if (s != null && e.getDismounted().equals(s.seat)) e.setCancelled(true);
    }

    @EventHandler
    public void onSneak(PlayerToggleSneakEvent e) {
        if (e.isSneaking() && busy(e.getPlayer())) stop(e.getPlayer(), true);
    }

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onDamage(EntityDamageEvent e) {
        if (e.getEntity() instanceof Player p && busy(p)) e.setCancelled(true);
    }

    @EventHandler
    public void onQuit(PlayerQuitEvent e) {
        if (busy(e.getPlayer())) stop(e.getPlayer(), false);
    }

    private static Particle.DustOptions dust(int rgb, float size) {
        return new Particle.DustOptions(Color.fromRGB((rgb >> 16) & 255, (rgb >> 8) & 255, rgb & 255), size);
    }

    private static void sound(Player p, String key, float vol, float pitch) {
        p.playSound(Sound.sound(Key.key("minecraft", key), Sound.Source.MASTER, vol, pitch));
    }
}
