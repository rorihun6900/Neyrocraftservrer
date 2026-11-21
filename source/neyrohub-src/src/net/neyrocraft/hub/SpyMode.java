package net.neyrocraft.hub;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import net.kyori.adventure.text.format.TextColor;
import net.kyori.adventure.text.format.TextDecoration;
import org.bukkit.Bukkit;
import org.bukkit.GameMode;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.OfflinePlayer;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.block.Action;
import org.bukkit.event.block.BlockBreakEvent;
import org.bukkit.event.block.BlockPlaceEvent;
import org.bukkit.event.entity.EntityDamageEvent;
import org.bukkit.event.entity.EntityPickupItemEvent;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.player.PlayerCommandPreprocessEvent;
import org.bukkit.event.player.PlayerDropItemEvent;
import org.bukkit.event.player.PlayerInteractEntityEvent;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.event.player.PlayerSwapHandItemsEvent;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.persistence.PersistentDataType;
import org.bukkit.potion.PotionEffect;
import org.bukkit.potion.PotionEffectType;

import java.io.File;
import java.io.IOException;
import java.time.Duration;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Admin watch mode: invisible, flying, invulnerable, with a hotbar of actions —
 * ban the watched player, teleport to them, open their record, stop watching.
 * The admin's own inventory/position/gamemode are saved to disk and restored afterwards
 * (also after a crash: restored on the next join).
 */
final class SpyMode implements Listener {
    private static final TextColor ORANGE = TextColor.color(0xFF8C00);
    private static final long CONFIRM_MS = 4000;

    private final NeyroHub plugin;
    private final NeyroGuard guard;
    private final NamespacedKey actionKey;
    private final File dir;
    private final Map<UUID, String> watching = new HashMap<>();     // spy -> target name
    private final Map<UUID, Long> banArmed = new HashMap<>();

    SpyMode(NeyroHub plugin, NeyroGuard guard) {
        this.plugin = plugin;
        this.guard = guard;
        this.actionKey = new NamespacedKey(plugin, "spy_action");
        this.dir = new File(plugin.getDataFolder(), "spy");
    }

    boolean isSpying(Player p) {
        return watching.containsKey(p.getUniqueId());
    }

    void shutdown() {
        for (Player p : Bukkit.getOnlinePlayers()) if (isSpying(p)) stop(p, false);
    }

    // ------------------------------------------------------------ start / stop

    void start(Player spy, String targetName) {
        Player target = Bukkit.getPlayerExact(targetName);
        if (target == null) { spy.sendMessage(Component.text("Игрок не в сети", NamedTextColor.RED)); return; }
        if (target.equals(spy)) { spy.sendMessage(Component.text("Нельзя наблюдать за собой", NamedTextColor.RED)); return; }
        if (!isSpying(spy)) {
            if (!save(spy)) { spy.sendMessage(Component.text("Не удалось сохранить инвентарь — наблюдение отменено", NamedTextColor.RED)); return; }
            spy.getInventory().clear();
            spy.getInventory().setArmorContents(new ItemStack[4]);
            spy.getInventory().setItemInOffHand(null);
        }
        watching.put(spy.getUniqueId(), target.getName());
        spy.setGameMode(GameMode.ADVENTURE);
        spy.setAllowFlight(true);
        spy.setFlying(true);
        spy.setInvulnerable(true);
        spy.setCollidable(false);
        spy.setCanPickupItems(false);
        spy.addPotionEffect(new PotionEffect(PotionEffectType.INVISIBILITY, PotionEffect.INFINITE_DURATION, 0, false, false, false));
        spy.addPotionEffect(new PotionEffect(PotionEffectType.NIGHT_VISION, PotionEffect.INFINITE_DURATION, 0, false, false, false));
        for (Player other : Bukkit.getOnlinePlayers()) if (!other.equals(spy)) other.hidePlayer(plugin, spy);
        hotbar(spy, target.getName());
        teleportBehind(spy, target);
        spy.sendMessage(Component.text()
                .append(Component.text("👁 Наблюдение за ", NamedTextColor.GRAY))
                .append(Component.text(target.getName(), ORANGE))
                .append(Component.text(". Ты невидим. Действия — в панели быстрого доступа (ПКМ).", NamedTextColor.GRAY))
                .build());
    }

    void stop(Player spy, boolean message) {
        if (watching.remove(spy.getUniqueId()) == null) return;
        banArmed.remove(spy.getUniqueId());
        spy.removePotionEffect(PotionEffectType.INVISIBILITY);
        spy.removePotionEffect(PotionEffectType.NIGHT_VISION);
        spy.setInvulnerable(false);
        spy.setCollidable(true);
        spy.setCanPickupItems(true);
        for (Player other : Bukkit.getOnlinePlayers()) other.showPlayer(plugin, spy);
        restore(spy);
        if (message) spy.sendMessage(Component.text("✔ Наблюдение закончено, ты вернулся на место.", NamedTextColor.GREEN));
    }

    private void teleportBehind(Player spy, Player target) {
        Location l = target.getLocation();
        Location at = l.clone().subtract(l.getDirection().setY(0).normalize().multiply(3)).add(0, 1.5, 0);
        at.setDirection(l.toVector().add(new org.bukkit.util.Vector(0, 1.2, 0)).subtract(at.toVector()));
        spy.teleport(at);
        spy.setFlying(true);
    }

    // ------------------------------------------------------------ hotbar

    private void hotbar(Player spy, String target) {
        var inv = spy.getInventory();
        inv.setItem(0, item(Material.BARRIER, "ban", Component.text("⛔ Забанить " + target, NamedTextColor.RED),
                Component.text("ПКМ, затем ещё раз для подтверждения", NamedTextColor.GRAY)));
        inv.setItem(2, item(Material.COMPASS, "tp", Component.text("➤ Телепорт к " + target, NamedTextColor.YELLOW),
                Component.text("Встать за спиной игрока", NamedTextColor.GRAY)));
        inv.setItem(4, item(Material.BOOK, "info", Component.text("📋 Досье " + target, ORANGE),
                Component.text("Нарушения, CPS, руды, клиент", NamedTextColor.GRAY)));
        inv.setItem(8, item(Material.LIME_DYE, "stop", Component.text("✔ Закончить наблюдение", NamedTextColor.GREEN),
                Component.text("Вернуться туда, где был", NamedTextColor.GRAY)));
        inv.setHeldItemSlot(4);
    }

    private ItemStack item(Material m, String action, Component name, Component lore) {
        ItemStack it = new ItemStack(m);
        ItemMeta meta = it.getItemMeta();
        meta.displayName(name.decoration(TextDecoration.ITALIC, false));
        meta.lore(List.of(lore.decoration(TextDecoration.ITALIC, false)));
        meta.getPersistentDataContainer().set(actionKey, PersistentDataType.STRING, action);
        it.setItemMeta(meta);
        return it;
    }

    private String actionOf(ItemStack it) {
        if (it == null || !it.hasItemMeta()) return null;
        return it.getItemMeta().getPersistentDataContainer().get(actionKey, PersistentDataType.STRING);
    }

    @EventHandler(priority = EventPriority.LOWEST)
    public void onUse(PlayerInteractEvent e) {
        Player p = e.getPlayer();
        if (!isSpying(p)) return;
        e.setCancelled(true);
        if (e.getAction() != Action.RIGHT_CLICK_AIR && e.getAction() != Action.RIGHT_CLICK_BLOCK) return;
        if (e.getHand() != org.bukkit.inventory.EquipmentSlot.HAND) return;
        act(p, actionOf(e.getItem()));
    }

    @EventHandler(priority = EventPriority.LOWEST)
    public void onUseOnEntity(PlayerInteractEntityEvent e) {
        if (!isSpying(e.getPlayer())) return;
        e.setCancelled(true);
        if (e.getHand() == org.bukkit.inventory.EquipmentSlot.HAND)
            act(e.getPlayer(), actionOf(e.getPlayer().getInventory().getItemInMainHand()));
    }

    private void act(Player spy, String action) {
        if (action == null) return;
        String target = watching.get(spy.getUniqueId());
        switch (action) {
            case "stop" -> stop(spy, true);
            case "info" -> {
                Player t = Bukkit.getPlayerExact(target);
                if (t != null) guard.report(spy, t);
                else spy.sendMessage(Component.text(target + " вышел с сервера", NamedTextColor.GRAY));
            }
            case "tp" -> {
                Player t = Bukkit.getPlayerExact(target);
                if (t != null) teleportBehind(spy, t);
                else spy.sendMessage(Component.text(target + " вышел с сервера", NamedTextColor.GRAY));
            }
            case "ban" -> {
                long now = System.currentTimeMillis();
                Long armed = banArmed.get(spy.getUniqueId());
                if (armed == null || now - armed > CONFIRM_MS) {
                    banArmed.put(spy.getUniqueId(), now);
                    spy.sendMessage(Component.text("⛔ Нажми ПКМ ещё раз в течение 4 секунд, чтобы забанить " + target, NamedTextColor.RED));
                    return;
                }
                banArmed.remove(spy.getUniqueId());
                ban(spy, target);
            }
            default -> { }
        }
    }

    private void ban(Player spy, String targetName) {
        String reason = "Читы (NeyroGuard). Решение администратора " + spy.getName();
        Player online = Bukkit.getPlayerExact(targetName);
        if (online != null) {
            online.ban(reason, (Duration) null, spy.getName(), true);
        } else {
            @SuppressWarnings("deprecation")
            OfflinePlayer off = Bukkit.getOfflinePlayer(targetName);
            off.ban(reason, (Duration) null, spy.getName());
        }
        plugin.getLogger().warning("[NeyroGuard] " + spy.getName() + " banned " + targetName);
        Component msg = Component.text()
                .append(Component.text("[", NamedTextColor.DARK_GRAY)).append(Component.text("NeyroGuard", ORANGE))
                .append(Component.text("] ", NamedTextColor.DARK_GRAY))
                .append(Component.text(targetName, NamedTextColor.WHITE))
                .append(Component.text(" забанен за читы.", NamedTextColor.RED)).build();
        Bukkit.getServer().sendMessage(msg);
        stop(spy, true);
    }

    // ------------------------------------------------------------ keep the spy items in place, keep the spy harmless

    @EventHandler(priority = EventPriority.LOWEST)
    public void onDrop(PlayerDropItemEvent e) { if (isSpying(e.getPlayer())) e.setCancelled(true); }

    @EventHandler(priority = EventPriority.LOWEST)
    public void onSwap(PlayerSwapHandItemsEvent e) { if (isSpying(e.getPlayer())) e.setCancelled(true); }

    @EventHandler(priority = EventPriority.LOWEST)
    public void onClick(InventoryClickEvent e) { if (e.getWhoClicked() instanceof Player p && isSpying(p)) e.setCancelled(true); }

    @EventHandler(priority = EventPriority.LOWEST)
    public void onBreak(BlockBreakEvent e) { if (isSpying(e.getPlayer())) e.setCancelled(true); }

    @EventHandler(priority = EventPriority.LOWEST)
    public void onPlace(BlockPlaceEvent e) { if (isSpying(e.getPlayer())) e.setCancelled(true); }

    @EventHandler(priority = EventPriority.LOWEST)
    public void onPickup(EntityPickupItemEvent e) { if (e.getEntity() instanceof Player p && isSpying(p)) e.setCancelled(true); }

    @EventHandler(priority = EventPriority.LOWEST)
    public void onDamage(EntityDamageEvent e) { if (e.getEntity() instanceof Player p && isSpying(p)) e.setCancelled(true); }

    /** "/grim spectate <nick>" and "/grim stopspectating" use this mode, so they get the action hotbar too. */
    @EventHandler(priority = EventPriority.LOWEST)
    public void onCommand(PlayerCommandPreprocessEvent e) {
        if (!e.getPlayer().hasPermission("neyroguard.admin")) return;
        String[] a = e.getMessage().trim().split("\\s+");
        if (a.length == 0 || !(a[0].equalsIgnoreCase("/grim") || a[0].equalsIgnoreCase("/grimac") || a[0].equalsIgnoreCase("/grimac:grim"))) return;
        if (a.length >= 3 && a[1].equalsIgnoreCase("spectate")) {
            e.setCancelled(true);
            start(e.getPlayer(), a[2]);
        } else if (a.length >= 2 && (a[1].equalsIgnoreCase("stopspectating") || (a[1].equalsIgnoreCase("spectate") && a.length == 2))) {
            e.setCancelled(true);
            if (isSpying(e.getPlayer())) stop(e.getPlayer(), true);
        }
    }

    @EventHandler
    public void onJoin(PlayerJoinEvent e) {
        Player p = e.getPlayer();
        // a spy who is watching must stay hidden from newcomers
        for (UUID id : watching.keySet()) {
            Player spy = Bukkit.getPlayer(id);
            if (spy != null && !spy.equals(p)) p.hidePlayer(plugin, spy);
        }
        // crashed or restarted mid-watch: give the admin their things back
        if (new File(dir, p.getUniqueId() + ".yml").exists()) {
            Bukkit.getScheduler().runTaskLater(plugin, () -> {
                if (!p.isOnline()) return;
                p.removePotionEffect(PotionEffectType.INVISIBILITY);
                p.removePotionEffect(PotionEffectType.NIGHT_VISION);
                p.setInvulnerable(false);
                restore(p);
            }, 40L);
        }
    }

    @EventHandler
    public void onQuit(PlayerQuitEvent e) {
        if (isSpying(e.getPlayer())) stop(e.getPlayer(), false);
    }

    // ------------------------------------------------------------ save / restore

    private boolean save(Player p) {
        try {
            dir.mkdirs();
            YamlConfiguration y = new YamlConfiguration();
            y.set("location", p.getLocation());
            y.set("gamemode", p.getGameMode().name());
            y.set("allow-flight", p.getAllowFlight());
            y.set("flying", p.isFlying());
            y.set("contents", p.getInventory().getContents());
            y.save(new File(dir, p.getUniqueId() + ".yml"));
            return true;
        } catch (IOException ex) {
            plugin.getLogger().warning("Spy save failed: " + ex);
            return false;
        }
    }

    private void restore(Player p) {
        File f = new File(dir, p.getUniqueId() + ".yml");
        if (!f.exists()) return;
        YamlConfiguration y = YamlConfiguration.loadConfiguration(f);
        p.getInventory().clear();
        List<?> list = y.getList("contents");
        if (list != null) {
            ItemStack[] items = new ItemStack[p.getInventory().getSize()];
            for (int i = 0; i < Math.min(items.length, list.size()); i++)
                if (list.get(i) instanceof ItemStack it) items[i] = it;
            p.getInventory().setContents(items);
        }
        try { p.setGameMode(GameMode.valueOf(y.getString("gamemode", "ADVENTURE"))); } catch (IllegalArgumentException ignored) { }
        p.setAllowFlight(y.getBoolean("allow-flight") || p.getGameMode() == GameMode.CREATIVE || p.getGameMode() == GameMode.SPECTATOR);
        Location back = y.getLocation("location");
        if (back != null && back.getWorld() != null) p.teleport(back);
        if (p.getAllowFlight()) p.setFlying(y.getBoolean("flying"));
        if (!f.delete()) f.deleteOnExit();
    }
}
