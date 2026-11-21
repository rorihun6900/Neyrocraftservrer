package net.neyrocraft.rtp;

import org.bukkit.Bukkit;
import org.bukkit.HeightMap;
import org.bukkit.Location;
import org.bukkit.World;
import org.bukkit.Particle;
import org.bukkit.Sound;
import org.bukkit.Color;
import org.bukkit.command.Command;
import org.bukkit.command.CommandSender;
import org.bukkit.command.TabExecutor;
import org.bukkit.entity.Player;
import org.bukkit.plugin.java.JavaPlugin;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.Set;
import java.util.UUID;

/** Random X/Z teleport. Ground is used only to choose Y; terrain is not screened. */
public final class NeyroRTP extends JavaPlugin implements TabExecutor {
    private static final int RANGE = 10_000;
    private final Random random = new Random();
    private final Map<UUID, Long> cooldown = new HashMap<>();
    private final Set<UUID> searching = new HashSet<>();

    @Override public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        if (args.length > 1) { sender.sendMessage("/rtp [all|ник]"); return true; }
        if (args.length == 1) {
            if (!sender.hasPermission("neyrortp.admin")) { sender.sendMessage("/rtp all и /rtp <ник> доступны операторам"); return true; }
            if (args[0].equalsIgnoreCase("all")) {
                int count = 0;
                for (Player p : Bukkit.getOnlinePlayers()) if (teleport(p, true)) count++;
                sender.sendMessage("NEYRO-RTP: отправляю " + count + " игроков на случайные координаты");
                return true;
            }
            Player target = Bukkit.getPlayerExact(args[0]);
            if (target == null) { sender.sendMessage("Игрок не найден: " + args[0]); return true; }
            if (teleport(target, true)) sender.sendMessage("NEYRO-RTP: отправляю " + target.getName());
            return true;
        }
        if (!(sender instanceof Player p)) { sender.sendMessage("Из консоли: /rtp all или /rtp <ник>"); return true; }
        teleport(p, false);
        return true;
    }

    private boolean teleport(Player p, boolean admin) {
        World world = rtpWorld(p);
        if (world == null) {
            p.sendMessage("§cNEYRO-RTP доступен только в мире выживания или анархии.");
            return false;
        }
        long now = System.currentTimeMillis();
        long left = cooldown.getOrDefault(p.getUniqueId(), 0L) - now;
        if (!admin && left > 0) { p.sendMessage("RTP снова доступен через " + (left / 1000 + 1) + " сек."); return false; }
        if (!searching.add(p.getUniqueId())) { p.sendMessage("RTP уже выполняется..."); return false; }
        int x, z;
        Location spawn = world.getSpawnLocation();
        do {
            x = random.nextInt(RANGE * 2 + 1) - RANGE;
            z = random.nextInt(RANGE * 2 + 1) - RANGE;
        } while ((long)(x - spawn.getBlockX()) * (x - spawn.getBlockX()) + (long)(z - spawn.getBlockZ()) * (z - spawn.getBlockZ()) <= 1_000_000L);
        final int targetX = x, targetZ = z;
        p.sendMessage("NEYRO-RTP: случайные X=" + x + ", Z=" + z + " · загружаю местность...");
        p.playSound(p.getLocation(), Sound.BLOCK_BEACON_ACTIVATE, 1f, 1.2f);
        for (int frame = 0; frame < 40; frame += 2) {
            final int step = frame;
            Bukkit.getScheduler().runTaskLater(this, () -> {
                if (!p.isOnline() || !searching.contains(p.getUniqueId())) return;
                Location base = p.getLocation();
                Particle.DustOptions cyan = new Particle.DustOptions(Color.fromRGB(0x40, 0xE8, 0xFF), 1.3f);
                Particle.DustOptions purple = new Particle.DustOptions(Color.fromRGB(0xD0, 0x44, 0xFF), 1.1f);
                double radius = 1.45 - step * 0.012;
                for (int j = 0; j < 16; j++) {
                    double a = j * Math.PI / 8 + step * .15;
                    p.getWorld().spawnParticle(Particle.DUST, base.clone().add(Math.cos(a) * radius, 0.12 + step * .045, Math.sin(a) * radius), 1, 0, 0, 0, 0, j % 2 == 0 ? cyan : purple);
                }
                if (step % 10 == 0) p.playSound(base, Sound.BLOCK_BEACON_AMBIENT, 0.5f, 1f + step / 80f);
                for (int i = 0; i < 3; i++) {
                    double a = step * 0.24 + i * Math.PI * 2 / 3;
                    p.getWorld().spawnParticle(Particle.PORTAL, base.clone().add(Math.cos(a) * 1.1, (step % 20) * 0.12, Math.sin(a) * 1.1), 2, 0.05, 0.05, 0.05, 0.05);
                }
            }, frame);
        }
        world.getChunkAtAsync(x >> 4, z >> 4, true).thenAccept(chunk ->
                Bukkit.getScheduler().runTask(this, () -> {
                    if (!p.isOnline()) { searching.remove(p.getUniqueId()); return; }
                    int y = world.getHighestBlockYAt(targetX, targetZ, HeightMap.WORLD_SURFACE);
                    Location target = new Location(world, targetX + 0.5, y, targetZ + 0.5, p.getLocation().getYaw(), 0);
                    Bukkit.getScheduler().runTaskLater(this, () -> p.teleportAsync(target).thenAccept(ok -> Bukkit.getScheduler().runTask(this, () -> {
                        searching.remove(p.getUniqueId());
                        if (ok) {
                            p.getWorld().spawnParticle(Particle.PORTAL, p.getLocation().add(0, 1, 0), 80, 0.8, 1, 0.8, 0.2);
                            p.getWorld().spawnParticle(Particle.END_ROD, p.getLocation().add(0, 1, 0), 35, 0.6, 0.8, 0.6, 0.04);
                            p.playSound(p.getLocation(), Sound.ENTITY_ENDERMAN_TELEPORT, 1f, 1f);
                            if (!admin) cooldown.put(p.getUniqueId(), System.currentTimeMillis() + 300_000L);
                            p.sendMessage("NEYRO-RTP: X=" + targetX + ", Y=" + y + ", Z=" + targetZ);
                        } else p.sendMessage("RTP не удался, попробуй снова");
                    })), 40L);
                })).exceptionally(ex -> {
                    Bukkit.getScheduler().runTask(this, () -> { searching.remove(p.getUniqueId()); if (p.isOnline()) p.sendMessage("Не удалось загрузить координаты RTP"); });
                    getLogger().warning("RTP chunk load: " + ex);
                    return null;
                });
        return true;
    }

    /** RTP never changes a player's game mode or sends them to the main survival world. */
    private World rtpWorld(Player p) {
        if (p == null || p.getWorld() == null) return null;
        String name = p.getWorld().getName().toLowerCase(java.util.Locale.ROOT);
        if (name.equals("world") || name.equals("world_nether") || name.equals("world_the_end")
                || name.equals("neyro_anarchy") || name.contains("skyblock")) return p.getWorld();
        return null;
    }

    @Override public List<String> onTabComplete(CommandSender sender, Command command, String alias, String[] args) {
        if (args.length != 1 || !sender.hasPermission("neyrortp.admin")) return List.of();
        List<String> out = new ArrayList<>();
        if ("all".startsWith(args[0].toLowerCase())) out.add("all");
        for (Player p : Bukkit.getOnlinePlayers()) if (p.getName().toLowerCase().startsWith(args[0].toLowerCase())) out.add(p.getName());
        return out;
    }
}
