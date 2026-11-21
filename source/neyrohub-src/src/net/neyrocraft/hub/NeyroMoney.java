package net.neyrocraft.hub;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import org.bukkit.Bukkit;
import org.bukkit.GameMode;
import org.bukkit.Material;
import org.bukkit.Tag;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.Monster;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.block.BlockBreakEvent;
import org.bukkit.event.block.BlockPlaceEvent;
import org.bukkit.event.entity.EntityDeathEvent;
import org.bukkit.event.player.PlayerQuitEvent;

import java.io.File;
import java.io.IOException;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/** Neyro-City dollars. Work orders and Star Pass are the only sources of new money. */
final class NeyroMoney implements Listener {
    private static final ZoneId MOSCOW = ZoneId.of("Europe/Moscow");
    private static final int JOB_PAY = 900, DAILY_LIMIT = 5;
    private final NeyroHub plugin;
    private final File dir;
    private final Map<UUID, Account> accounts = new HashMap<>();
    private final Set<String> placed = new HashSet<>();

    private static final class Account {
        long balance;
        String job = "", day = "";
        int progress, completed;
        Set<Integer> passPaid = new HashSet<>();
        boolean dirty;
    }

    NeyroMoney(NeyroHub plugin) {
        this.plugin = plugin;
        dir = new File(plugin.getDataFolder(), "money");
        dir.mkdirs();
    }

    void start() {
        Bukkit.getScheduler().runTaskTimer(plugin, () -> accounts.forEach((id, a) -> { if (a.dirty) save(id, a); }), 1200L, 1200L);
    }

    void shutdown() { accounts.forEach(this::save); }

    private Account account(UUID id) {
        return accounts.computeIfAbsent(id, key -> {
            Account a = new Account();
            File file = new File(dir, key + ".yml");
            if (!file.exists()) return a;
            YamlConfiguration y = YamlConfiguration.loadConfiguration(file);
            a.balance = Math.max(0, y.getLong("balance"));
            a.job = y.getString("job", "");
            a.day = y.getString("day", "");
            a.progress = y.getInt("progress");
            a.completed = y.getInt("completed");
            a.passPaid.addAll(y.getIntegerList("pass-paid"));
            return a;
        });
    }

    private void save(UUID id, Account a) {
        YamlConfiguration y = new YamlConfiguration();
        y.set("balance", a.balance);
        y.set("job", a.job);
        y.set("day", a.day);
        y.set("progress", a.progress);
        y.set("completed", a.completed);
        y.set("pass-paid", a.passPaid.stream().sorted().toList());
        try { y.save(new File(dir, id + ".yml")); a.dirty = false; }
        catch (IOException ex) { plugin.getLogger().severe("NeyroMoney could not save " + id + ": " + ex); }
    }

    long balance(Player p) { return account(p.getUniqueId()).balance; }

    boolean withdraw(Player p, long amount) {
        if (amount <= 0) return false;
        Account a = account(p.getUniqueId());
        if (a.balance < amount) return false;
        a.balance -= amount;
        a.dirty = true;
        save(p.getUniqueId(), a);
        if (a.dirty) { a.balance += amount; return false; }
        return !a.dirty;
    }

    void deposit(Player p, long amount, String reason) {
        if (amount <= 0) return;
        Account a = account(p.getUniqueId());
        a.balance = Math.addExact(a.balance, amount);
        save(p.getUniqueId(), a);
        p.sendMessage(Component.text("NEYRO$ +$" + amount + " · " + reason + " · баланс $" + a.balance, NamedTextColor.GREEN));
    }

    void rewardPass(Player p, int level) {
        Account a = account(p.getUniqueId());
        if (a.passPaid.contains(level)) return;
        int amount = 750 + level * 50;
        a.balance = Math.addExact(a.balance, amount);
        a.passPaid.add(level);
        save(p.getUniqueId(), a);
        p.sendMessage(Component.text("Star Pass: +$" + amount + " NEYRO$ · баланс $" + a.balance, NamedTextColor.GREEN));
    }

    boolean command(Player p, String name, String[] args) {
        if (!plugin.authed(p)) return true;
        Account a = account(p.getUniqueId());
        if (name.equals("money")) {
            p.sendMessage(Component.text("NEYRO$ · Ваш баланс: $" + a.balance, NamedTextColor.GREEN));
            p.sendMessage(Component.text("Зарабатывай через /job и Star Pass (/pass)", NamedTextColor.GRAY));
            return true;
        }
        resetDay(a);
        if (args.length > 0) {
            String choice = args[0].toLowerCase(java.util.Locale.ROOT);
            if (choice.equals("miner") || choice.equals("woodcutter") || choice.equals("hunter")) {
                if (!a.job.equals(choice)) { a.job = choice; a.progress = 0; a.dirty = true; save(p.getUniqueId(), a); }
            } else {
                p.sendMessage(Component.text("Профессии: /job miner | woodcutter | hunter", NamedTextColor.YELLOW));
                return true;
            }
        }
        int goal = goal(a.job);
        String title = switch (a.job) { case "miner" -> "Шахтёр"; case "woodcutter" -> "Лесоруб"; case "hunter" -> "Охотник"; default -> "не выбрана"; };
        p.sendMessage(Component.text("Профессия: " + title + " · задание " + a.progress + "/" + goal + " · $" + JOB_PAY + " за заказ", NamedTextColor.AQUA));
        p.sendMessage(Component.text("Сегодня выполнено: " + a.completed + "/" + DAILY_LIMIT + " · выбрать: /job miner | woodcutter | hunter", NamedTextColor.GRAY));
        return true;
    }

    private static int goal(String job) { return switch (job) { case "miner" -> 24; case "woodcutter" -> 48; case "hunter" -> 16; default -> 0; }; }

    private void resetDay(Account a) {
        String today = LocalDate.now(MOSCOW).toString();
        if (!today.equals(a.day)) { a.day = today; a.completed = 0; a.progress = 0; a.dirty = true; }
    }

    private boolean eligible(Player p) {
        return p != null && plugin.authed(p) && p.getGameMode() == GameMode.SURVIVAL
                && (plugin.city() == null || !plugin.city().safe(p.getLocation()));
    }

    private void progress(Player p, String job) {
        if (!eligible(p)) return;
        Account a = account(p.getUniqueId());
        resetDay(a);
        if (!job.equals(a.job) || a.completed >= DAILY_LIMIT) return;
        if (++a.progress >= goal(job)) {
            a.progress = 0;
            a.completed++;
            a.balance += JOB_PAY;
            save(p.getUniqueId(), a);
            p.sendMessage(Component.text("Заказ профессии выполнен! +$" + JOB_PAY + " NEYRO$ · баланс $" + a.balance, NamedTextColor.GREEN));
        } else a.dirty = true;
    }

    private static String blockKey(org.bukkit.block.Block b) {
        return b.getWorld().getUID() + ":" + b.getX() + ":" + b.getY() + ":" + b.getZ();
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onPlace(BlockPlaceEvent e) {
        if (placed.size() > 100_000) placed.clear();
        placed.add(blockKey(e.getBlockPlaced()));
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onBreak(BlockBreakEvent e) {
        if (placed.remove(blockKey(e.getBlock()))) return;
        Material m = e.getBlock().getType();
        if (m.name().endsWith("_ORE") || m == Material.ANCIENT_DEBRIS) progress(e.getPlayer(), "miner");
        else if (Tag.LOGS.isTagged(m)) progress(e.getPlayer(), "woodcutter");
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onKill(EntityDeathEvent e) {
        if (e.getEntity() instanceof Monster) progress(e.getEntity().getKiller(), "hunter");
    }

    @EventHandler public void onQuit(PlayerQuitEvent e) {
        Account a = accounts.remove(e.getPlayer().getUniqueId());
        if (a != null && a.dirty) save(e.getPlayer().getUniqueId(), a);
    }
}
