package net.neyrocraft.hub;

import net.kyori.adventure.key.Key;
import net.kyori.adventure.sound.Sound;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.title.Title;
import org.bukkit.Bukkit;
import org.bukkit.Color;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.Particle;
import org.bukkit.World;
import org.bukkit.attribute.Attribute;
import org.bukkit.attribute.AttributeInstance;
import org.bukkit.entity.BlockDisplay;
import org.bukkit.entity.Player;
import org.bukkit.entity.TextDisplay;
import org.bukkit.potion.PotionEffect;
import org.bukkit.potion.PotionEffectType;
import org.bukkit.scheduler.BukkitRunnable;

import java.time.Duration;
import java.util.HashSet;
import java.util.Set;
import java.util.UUID;

/**
 * A teleport "beam": light gathers in a spiral around the player, a column of light comes down,
 * they lift off, the screen flashes white, and they land softly at the destination in a burst of crystals.
 */
final class BeamTransfer {
    private static final Set<UUID> running = new HashSet<>();

    private BeamTransfer() { }

    static boolean busy(Player p) {
        return running.contains(p.getUniqueId());
    }

    static void run(NeyroHub plugin, Player p, Location target, Color a, Color b, Title title) {
        if (!running.add(p.getUniqueId())) return;
        float walk = p.getWalkSpeed();
        AttributeInstance jump = p.getAttribute(Attribute.JUMP_STRENGTH);
        double jumpBase = jump == null ? 0.42 : jump.getBaseValue();
        p.setWalkSpeed(0f);
        if (jump != null) jump.setBaseValue(0);
        Particle.DustOptions dustA = new Particle.DustOptions(a, 1.3f), dustB = new Particle.DustOptions(b, 1.1f);
        sound(p, "block.beacon.activate", 0.8f, 1.4f);
        sound(p, "block.amethyst_block.resonate", 1f, 0.8f);

        new BukkitRunnable() {
            int t = 0;
            BlockDisplay flash;
            TextDisplay panel;

            @Override
            public void run() {
                if (!p.isOnline()) { finish(); return; }
                World w = p.getWorld();
                Location feet = p.getLocation();
                if (t < 34) {
                    // gathering spiral, tighter and higher every tick
                    double k = t / 34.0, rad = 1.4 - k * 0.8;
                    for (int s = 0; s < 3; s++) {
                        double ang = t * 0.55 + s * Math.PI * 2 / 3;
                        Location l = feet.clone().add(Math.cos(ang) * rad, 0.1 + k * 2.6 + s * 0.15, Math.sin(ang) * rad);
                        w.spawnParticle(Particle.DUST, l, 1, 0, 0, 0, 0, s == 1 ? dustB : dustA);
                    }
                    // beam of light coming down onto the player
                    if (t > 8) for (int i = 0; i < 4; i++)
                        w.spawnParticle(Particle.END_ROD, feet.clone().add((Math.random() - 0.5) * 0.5, 2 + Math.random() * 10, (Math.random() - 0.5) * 0.5), 0, 0, -0.6, 0, 1);
                    if (t % 4 == 0) sound(p, "block.note_block.chime", 0.6f, (float) (0.6 + k * 1.4));
                    if (t == 10) p.addPotionEffect(new PotionEffect(PotionEffectType.LEVITATION, 26, 0, false, false, false));
                    if (t == 30) sound(p, "block.beacon.power_select", 1f, 1.8f);
                } else if (t == 34) {
                    // white-out
                    sound(p, "entity.firework_rocket.blast", 0.8f, 0.7f);
                    sound(p, "block.portal.travel", 0.18f, 1.6f);
                    flash = Hyperjump.flashCube(plugin, p, p.getEyeLocation(), Material.WHITE_CONCRETE);
                    panel = Hyperjump.whitePanel(plugin, p, p.getEyeLocation().add(p.getEyeLocation().getDirection().multiply(0.6)), 255);
                } else if (t < 37) {
                    // hold the white-out for a moment before the jump
                    if (flash != null) flash.teleport(p.getEyeLocation());
                } else if (t == 37) {
                    p.removePotionEffect(PotionEffectType.LEVITATION);
                    Location to = target.clone().add(0, 2.2, 0);
                    p.teleport(to);
                    if (flash != null) flash.remove();
                    if (panel != null) panel.remove();
                    flash = Hyperjump.flashCube(plugin, p, p.getEyeLocation(), Material.WHITE_STAINED_GLASS);
                    p.addPotionEffect(new PotionEffect(PotionEffectType.SLOW_FALLING, 60, 0, false, false, false));
                    p.showTitle(title);
                } else if (t > 37 && t < 50) {
                    if (t == 42 && flash != null) { flash.remove(); flash = Hyperjump.flashCube(plugin, p, p.getEyeLocation(), Material.LIGHT_BLUE_STAINED_GLASS); }
                    if (flash != null) flash.teleport(p.getEyeLocation());
                    // arrival burst: crystal rings expanding around the player
                    double r = (t - 37) * 0.35;
                    for (int i = 0; i < 16; i++) {
                        double ang = i * Math.PI / 8;
                        w.spawnParticle(Particle.DUST, feet.clone().add(Math.cos(ang) * r, 0.2, Math.sin(ang) * r), 1, 0, 0, 0, 0, i % 2 == 0 ? dustA : dustB);
                    }
                    if (t == 39) { sound(p, "block.amethyst_cluster.break", 1f, 1.2f); sound(p, "block.beacon.power_select", 0.8f, 1.3f);
                        w.spawnParticle(Particle.REVERSE_PORTAL, feet.clone().add(0, 1, 0), 120, 0.6, 1, 0.6, 0.08); }
                } else {
                    finish();
                    return;
                }
                t++;
            }

            void finish() {
                cancel();
                if (flash != null && flash.isValid()) flash.remove();
                if (panel != null && panel.isValid()) panel.remove();
                running.remove(p.getUniqueId());
                if (p.isOnline()) {
                    p.setWalkSpeed(walk <= 0 ? 0.2f : walk);
                    if (jump != null) jump.setBaseValue(jumpBase <= 0 ? 0.42 : jumpBase);
                }
            }
        }.runTaskTimer(plugin, 1L, 1L);
    }

    static Title title(Component main, Component sub) {
        return Title.title(main, sub, Title.Times.times(Duration.ofMillis(200), Duration.ofMillis(2200), Duration.ofMillis(700)));
    }

    private static void sound(Player p, String key, float vol, float pitch) {
        p.playSound(Sound.sound(Key.key("minecraft", key), Sound.Source.MASTER, vol, pitch));
    }
}
