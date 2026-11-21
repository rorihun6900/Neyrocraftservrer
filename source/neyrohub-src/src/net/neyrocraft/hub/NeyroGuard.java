package net.neyrocraft.hub;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.TextComponent;
import net.kyori.adventure.text.event.ClickEvent;
import net.kyori.adventure.text.event.HoverEvent;
import net.kyori.adventure.text.format.NamedTextColor;
import net.kyori.adventure.text.format.TextColor;
import org.bukkit.Bukkit;
import org.bukkit.GameMode;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.command.TabCompleter;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.block.BlockBreakEvent;
import org.bukkit.event.player.PlayerAnimationEvent;
import org.bukkit.event.player.PlayerAnimationType;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.permissions.PermissionAttachment;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.EnumSet;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * NeyroGuard — the server's own layer on top of Grim:
 *  - switches Grim off ("grim.disabled") while a player is inside one of our cutscenes
 *    (VR bay, hyperjump, arrival flight), so scripted movement never counts as cheating;
 *  - checks Grim does not have: auto-clicker (CPS and machine-like rhythm) and X-ray mining patterns;
 *  - sends every suspicion to admins in chat, /ng check shows a player's record.
 */
final class NeyroGuard implements Listener, CommandExecutor, TabCompleter {
    private static final String GRIM_OFF = "grim.disabled";
    private static final long GRACE_MS = 2500;               // keep Grim off a moment after a cutscene ends
    private static final int CPS_LIMIT = 20;                 // humans don't sustain more
    private static final long ALERT_COOLDOWN_MS = 8000;
    private static final long XRAY_WINDOW_MS = 15 * 60_000L;
    private static final Set<Material> RARE = EnumSet.of(Material.DIAMOND_ORE, Material.DEEPSLATE_DIAMOND_ORE,
            Material.ANCIENT_DEBRIS, Material.EMERALD_ORE, Material.DEEPSLATE_EMERALD_ORE);
    private static final Set<Material> ROCK = EnumSet.of(Material.STONE, Material.DEEPSLATE, Material.TUFF,
            Material.GRANITE, Material.DIORITE, Material.ANDESITE, Material.NETHERRACK, Material.BASALT,
            Material.BLACKSTONE, Material.COBBLED_DEEPSLATE, Material.CALCITE);

    private static final TextColor ORANGE = TextColor.color(0xFF8C00);

    private static final class Track {
        final Deque<Long> clicks = new ArrayDeque<>();
        int maxCps;
        long lastCpsAt;
        final Map<String, Integer> violations = new HashMap<>();
        final Map<String, Long> lastAlert = new HashMap<>();
        final Deque<long[]> veins = new ArrayDeque<>();      // {time, x, y, z}
        final Deque<Long> rock = new ArrayDeque<>();
        int rareTotal;
        boolean grimOff;
        long cutsceneEnded;
        PermissionAttachment attachment;
    }

    private final NeyroHub plugin;
    private final Map<UUID, Track> tracks = new HashMap<>();
    private final Set<UUID> alertsOff = new HashSet<>();
    private boolean grimPresent;
    private SpyMode spy;

    NeyroGuard(NeyroHub plugin) {
        this.plugin = plugin;
    }

    void start() {
        grimPresent = Bukkit.getPluginManager().getPlugin("GrimAC") != null;
        spy = new SpyMode(plugin, this);
        Bukkit.getPluginManager().registerEvents(spy, plugin);
        Bukkit.getScheduler().runTaskTimer(plugin, this::syncGrim, 20L, 2L);
        plugin.getLogger().info("NeyroGuard active" + (grimPresent ? " (with Grim)" : " (Grim not installed)"));
    }


    void shutdown() {
        if (spy != null) spy.shutdown();
        for (Player p : Bukkit.getOnlinePlayers()) {
            Track t = tracks.get(p.getUniqueId());
            if (t != null && t.attachment != null) {
                try { p.removeAttachment(t.attachment); } catch (IllegalArgumentException ignored) { }
            }
        }
        tracks.clear();
    }

    private Track track(Player p) {
        return tracks.computeIfAbsent(p.getUniqueId(), k -> new Track());
    }

    // ------------------------------------------------------------ Grim bridge

    private void syncGrim() {
        if (!grimPresent) return;
        long now = System.currentTimeMillis();
        for (Player p : Bukkit.getOnlinePlayers()) {
            Track t = track(p);
            boolean scene = plugin.inCutscene(p) || (spy != null && spy.isSpying(p));
            if (scene) t.cutsceneEnded = now;
            boolean off = uncheckedWorld(p) || scene || now - t.cutsceneEnded < GRACE_MS;
            if (t.attachment == null) {
                t.attachment = p.addAttachment(plugin);
                t.attachment.setPermission("grim.alerts", false);
                t.attachment.setPermission("grim.alerts.enable-on-join", false);
            }
            if (off == t.grimOff) continue;
            t.attachment.setPermission(GRIM_OFF, off);
            p.recalculatePermissions();
            t.grimOff = off;
        }
    }

    // ------------------------------------------------------------ auto-clicker

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onSwing(PlayerAnimationEvent e) {
        if (e.getAnimationType() != PlayerAnimationType.ARM_SWING) return;
        Player p = e.getPlayer();
        if (uncheckedWorld(p)) return;
        if (p.getGameMode() == GameMode.CREATIVE || p.getGameMode() == GameMode.SPECTATOR) return;
        // holding the button on a block swings every tick while digging — that's not clicking
        if (p.getTargetBlockExact(5) != null) return;
        Track t = track(p);
        long now = System.currentTimeMillis();
        t.clicks.addLast(now);
        while (!t.clicks.isEmpty() && now - t.clicks.peekFirst() > 1000) t.clicks.removeFirst();
        int cps = t.clicks.size();
        if (now - t.lastCpsAt > 10_000) t.maxCps = 0;
        if (cps > t.maxCps) { t.maxCps = cps; t.lastCpsAt = now; }
        if (cps > CPS_LIMIT) flag(p, t, "AutoClicker", cps + " CPS (норма до " + CPS_LIMIT + ")");
        else if (cps >= 12 && robotic(t)) flag(p, t, "AutoClicker", "идеально ровные клики, " + cps + " CPS");
    }

    /** Machine-like rhythm: the gaps between the last clicks are almost identical. */
    private boolean robotic(Track t) {
        if (t.clicks.size() < 12) return false;
        Long[] c = t.clicks.toArray(new Long[0]);
        double sum = 0, sq = 0; int n = 0;
        for (int i = 1; i < c.length; i++) { double d = c[i] - c[i - 1]; sum += d; sq += d * d; n++; }
        double mean = sum / n, sd = Math.sqrt(Math.max(0, sq / n - mean * mean));
        return sd < 4.0;
    }

    // ------------------------------------------------------------ X-ray pattern

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onBreak(BlockBreakEvent e) {
        Player p = e.getPlayer();
        if (uncheckedWorld(p) || plugin.inHub(p) || p.getGameMode() == GameMode.CREATIVE) return;
        Material m = e.getBlock().getType();
        Track t = track(p);
        long now = System.currentTimeMillis();
        while (!t.rock.isEmpty() && now - t.rock.peekFirst() > XRAY_WINDOW_MS) t.rock.removeFirst();
        while (!t.veins.isEmpty() && now - t.veins.peekFirst()[0] > XRAY_WINDOW_MS) t.veins.removeFirst();
        if (ROCK.contains(m)) { t.rock.addLast(now); return; }
        if (!RARE.contains(m)) return;
        t.rareTotal++;
        Location l = e.getBlock().getLocation();
        for (long[] v : t.veins) {
            long dx = v[1] - l.getBlockX(), dy = v[2] - l.getBlockY(), dz = v[3] - l.getBlockZ();
            if (dx * dx + dy * dy + dz * dz <= 25) return;     // same vein
        }
        t.veins.addLast(new long[]{now, l.getBlockX(), l.getBlockY(), l.getBlockZ()});
        int veins = t.veins.size(), rock = t.rock.size();
        if (veins >= 4 && rock < veins * 35)
            flag(p, t, "X-Ray?", veins + " жил редких руд за 15 мин, камня вскопано всего " + rock);
    }

    // ------------------------------------------------------------ alerts

    private void flag(Player p, Track t, String check, String details) {
        if (uncheckedWorld(p)) return;
        int vl = t.violations.merge(check, 1, Integer::sum);
        long now = System.currentTimeMillis();
        Long last = t.lastAlert.get(check);
        if (last != null && now - last < ALERT_COOLDOWN_MS) return;
        t.lastAlert.put(check, now);
    }

    private static boolean uncheckedWorld(Player player) {
        String world = player.getWorld().getName();
        return world.equals(DarkAnarchy.WORLD_NAME) || world.equals("neyro_dead_realm");
    }

    @EventHandler
    public void onQuit(PlayerQuitEvent e) {
        Track t = tracks.remove(e.getPlayer().getUniqueId());
        if (t != null && t.attachment != null) {
            try { e.getPlayer().removeAttachment(t.attachment); } catch (IllegalArgumentException ignored) { }
        }
    }

    // ------------------------------------------------------------ /ng

    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        if (args.length == 0) {
            sender.sendMessage(header("NeyroGuard — античит NeyroCraft"));
            line(sender, "/ng check <ник>", "досье игрока: нарушения, CPS, руды, клиент");
            line(sender, "/ng alerts", "вкл/выкл оповещения NeyroGuard в чате");
            line(sender, "/grim alerts", "вкл/выкл оповещения Grim (движение, бой, пакеты)");
            line(sender, "/grim profile <ник>", "подробный профиль игрока от Grim");
            line(sender, "/ng spy <ник>", "незаметно наблюдать: в панели — забанить, телепорт, досье, закончить");
            line(sender, "/ng stop", "закончить наблюдение (то же, что /grim stopspectating)");
            return true;
        }
        switch (args[0].toLowerCase(java.util.Locale.ROOT)) {
            case "alerts" -> {
                if (!(sender instanceof Player p)) { sender.sendMessage("Только для игроков"); return true; }
                if (!alertsOff.add(p.getUniqueId())) alertsOff.remove(p.getUniqueId());
                boolean on = !alertsOff.contains(p.getUniqueId());
                p.sendMessage(Component.text("NeyroGuard: оповещения ", NamedTextColor.GRAY)
                        .append(Component.text(on ? "включены" : "выключены", on ? NamedTextColor.GREEN : NamedTextColor.RED)));
            }
            case "spy", "spectate", "watch" -> {
                if (!(sender instanceof Player p)) { sender.sendMessage("Только для игроков"); return true; }
                if (args.length < 2) { sender.sendMessage("Использование: /ng spy <ник>"); return true; }
                spy.start(p, args[1]);
            }
            case "stop" -> {
                if (sender instanceof Player p && spy.isSpying(p)) spy.stop(p, true);
                else sender.sendMessage(Component.text("Ты сейчас ни за кем не наблюдаешь", NamedTextColor.GRAY));
            }
            case "check" -> {
                if (args.length < 2) { sender.sendMessage("Использование: /ng check <ник>"); return true; }
                Player target = Bukkit.getPlayerExact(args[1]);
                if (target == null) { sender.sendMessage(Component.text("Игрок не в сети", NamedTextColor.RED)); return true; }
                report(sender, target);
            }
            default -> sender.sendMessage("Неизвестная команда. /ng — список");
        }
        return true;
    }

    void report(CommandSender to, Player p) {
        Track t = track(p);
        to.sendMessage(header("Досье: " + p.getName()));
        String brand = p.getClientBrandName();
        line(to, "Клиент", brand == null ? "неизвестно" : brand);
        line(to, "Пинг", p.getPing() + " мс");
        line(to, "Макс. CPS (10 с)", String.valueOf(t.maxCps));
        line(to, "Редкие руды", t.rareTotal + " блоков, жил за 15 мин: " + t.veins.size() + ", камня: " + t.rock.size());
        line(to, "Grim сейчас", !grimPresent ? "не установлен" : t.grimOff ? "на паузе (катсцена)" : "проверяет");
        if (t.violations.isEmpty()) line(to, "Нарушения NeyroGuard", "нет");
        else t.violations.forEach((k, v) -> line(to, "Нарушение " + k, "×" + v));
        if (grimPresent) to.sendMessage(Component.text("  Подробно от Grim: ", NamedTextColor.GRAY)
                .append(Component.text("/grim profile " + p.getName(), ORANGE)
                        .clickEvent(ClickEvent.runCommand("/grim profile " + p.getName()))));
    }

    private static Component header(String title) {
        return Component.text("— ", NamedTextColor.DARK_GRAY).append(Component.text(title, ORANGE))
                .append(Component.text(" —", NamedTextColor.DARK_GRAY));
    }

    private static void line(CommandSender to, String k, String v) {
        TextComponent c = Component.text("  " + k + ": ", NamedTextColor.GRAY).append(Component.text(v, NamedTextColor.WHITE));
        to.sendMessage(c);
    }

    @Override
    public List<String> onTabComplete(CommandSender sender, Command command, String alias, String[] args) {
        List<String> out = new ArrayList<>();
        if (args.length == 1) for (String s : new String[]{"check", "spy", "stop", "alerts"}) if (s.startsWith(args[0].toLowerCase())) out.add(s);
        if (args.length == 2 && (args[0].equalsIgnoreCase("check") || args[0].equalsIgnoreCase("spy")))
            for (Player p : Bukkit.getOnlinePlayers()) if (p.getName().toLowerCase().startsWith(args[1].toLowerCase())) out.add(p.getName());
        return out;
    }
}
