package net.neyrocraft.hub;

import net.kyori.adventure.key.Key;
import net.kyori.adventure.sound.Sound;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.TextColor;
import org.bukkit.Bukkit;
import org.bukkit.Color;
import org.bukkit.GameMode;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.Particle;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Display;
import org.bukkit.entity.ItemDisplay;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.block.Action;
import org.bukkit.event.player.PlayerAnimationEvent;
import org.bukkit.event.player.PlayerAnimationType;
import org.bukkit.event.player.PlayerGameModeChangeEvent;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.event.player.PlayerItemHeldEvent;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.event.entity.EntityPickupItemEvent;
import org.bukkit.event.entity.EntityDamageByEntityEvent;
import org.bukkit.inventory.EquipmentSlot;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.persistence.PersistentDataType;
import org.bukkit.scheduler.BukkitTask;
import org.bukkit.util.Transformation;
import org.bukkit.util.Vector;
import org.joml.AxisAngle4f;
import org.joml.Vector3f;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

/** Operator equipment issued in creative mode and usable in survival. */
final class NeyroGear implements Listener, CommandExecutor {
    private final NeyroHub plugin;
    private final NamespacedKey jetKey, saberKey;
    private final Map<UUID, ItemDisplay> backpacks = new HashMap<>();
    private final Map<UUID, Long> boostAt = new HashMap<>();
    private final Map<UUID, Integer> bladeEpoch = new HashMap<>();
    private BukkitTask task;

    NeyroGear(NeyroHub plugin) {
        this.plugin = plugin;
        jetKey = new NamespacedKey(plugin, "admin_jetpack");
        saberKey = new NamespacedKey(plugin, "admin_saber");
    }

    void start() { task = Bukkit.getScheduler().runTaskTimer(plugin, this::tick, 5L, 5L); }
    void shutdown() {
        if (task != null) task.cancel();
        backpacks.values().forEach(ItemDisplay::remove);
        backpacks.clear();
    }

    private ItemStack item(boolean jet) {
        ItemStack item = new ItemStack(jet ? Material.ELYTRA : Material.NETHERITE_SWORD);
        item.editMeta(meta -> {
            meta.displayName(Component.text(jet ? "◈ NEYRO-JETPACK" : "◆ Меч директора NEYRO-CORP",
                    TextColor.color(jet ? 0x46D9FF : 0xFF9A37)));
            meta.setItemModel(new NamespacedKey("neyrocraft", jet ? "admin_jetpack" : "saber_cyan_0"));
            meta.setUnbreakable(true);
            if (jet) {
                var equipment = meta.getEquippable();
                equipment.setModel(new NamespacedKey("neyrocraft", "admin_jetpack"));
                meta.setEquippable(equipment);
            }
            meta.getPersistentDataContainer().set(jet ? jetKey : saberKey, PersistentDataType.BYTE, (byte) 1);
        });
        return item;
    }

    private boolean tagged(ItemStack item, NamespacedKey key) {
        return item != null && item.hasItemMeta() && item.getItemMeta().getPersistentDataContainer().has(key, PersistentDataType.BYTE);
    }
    private boolean gear(ItemStack item) { return tagged(item, jetKey) || tagged(item, saberKey); }
    private boolean holdingSaber(Player p) { return tagged(p.getInventory().getItemInMainHand(), saberKey); }

    private void stage(Player p, int slot, int number) {
        ItemStack blade = p.getInventory().getItem(slot);
        if (!tagged(blade, saberKey)) return;
        blade.editMeta(meta -> meta.setItemModel(new NamespacedKey("neyrocraft", "saber_cyan_" + number)));
        p.getInventory().setItem(slot, blade);
    }

    private void ignite(Player p) {
        if (!p.isOnline() || !p.isOp() || !holdingSaber(p)) return;
        int slot = p.getInventory().getHeldItemSlot();
        int epoch = bladeEpoch.merge(p.getUniqueId(), 1, Integer::sum);
        stage(p, slot, 0);
        p.playSound(Sound.sound(Key.key("neyrocraft", "saber.on"), Sound.Source.PLAYER, 1f, 1f));
        for (int k = 1; k <= 3; k++) {
            int next = k;
            Bukkit.getScheduler().runTaskLater(plugin, () -> {
                if (p.isOnline() && p.getInventory().getHeldItemSlot() == slot
                        && bladeEpoch.getOrDefault(p.getUniqueId(), 0) == epoch && holdingSaber(p)) stage(p, slot, next);
            }, k * 2L);
        }
    }

    private void retract(Player p, int slot) {
        bladeEpoch.merge(p.getUniqueId(), 1, Integer::sum);
        stage(p, slot, 0);
        p.playSound(Sound.sound(Key.key("neyrocraft", "saber.off"), Sound.Source.PLAYER, 0.8f, 1f));
    }

    @Override public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        if (!(sender instanceof Player p)) { sender.sendMessage("Only players can take NeyroGear"); return true; }
        if (!p.isOp() || p.getGameMode() != GameMode.CREATIVE) {
            p.sendMessage(Component.text("NeyroGear выдаётся только оператору в творческом режиме.", TextColor.color(0xFF7070)));
            return true;
        }
        if (args.length == 0 || (!args[0].equalsIgnoreCase("jetpack") && !args[0].equalsIgnoreCase("saber"))) {
            p.sendMessage("/neyrogear jetpack | saber"); return true;
        }
        boolean jet = args[0].equalsIgnoreCase("jetpack");
        p.getInventory().addItem(item(jet));
        if (!jet) Bukkit.getScheduler().runTask(plugin, () -> { if (holdingSaber(p)) ignite(p); });
        p.sendMessage(Component.text(jet ? "Джетпак получен. Надень и нажми ПКМ для рывка." : "Админский световой меч получен.", TextColor.color(0x46D9FF)));
        return true;
    }

    private void tick() {
        for (Player p : Bukkit.getOnlinePlayers()) {
            if (!p.isOp()) { purge(p); remove(p); continue; }
            if (!tagged(p.getInventory().getChestplate(), jetKey)) { remove(p); continue; }
            ItemDisplay d = backpacks.get(p.getUniqueId());
            if (d == null || !d.isValid()) {
                d = p.getWorld().spawn(p.getLocation(), ItemDisplay.class, e -> {
                    e.setItemStack(item(true));
                    e.setItemDisplayTransform(ItemDisplay.ItemDisplayTransform.FIXED);
                    e.setBillboard(Display.Billboard.FIXED);
                    e.setPersistent(false);
                    e.setBrightness(new Display.Brightness(15, 15));
                    e.setTransformation(new Transformation(new Vector3f(-0.5f, -0.5f, -0.5f), new AxisAngle4f(),
                            new Vector3f(0.6f, 0.6f, 0.6f), new AxisAngle4f()));
                });
                backpacks.put(p.getUniqueId(), d);
            }
            if (!d.getWorld().equals(p.getWorld())) { remove(p); continue; }
            Vector behind = p.getLocation().getDirection().setY(0).normalize().multiply(-0.32);
            var loc = p.getLocation().add(behind).add(0, 1.15, 0);
            loc.setYaw(p.getLocation().getYaw() + 180);
            d.teleport(loc);
            if (p.isFlying() && p.getTicksLived() % 5 == 0)
                p.getWorld().spawnParticle(Particle.FLAME, loc.clone().add(0, -0.5, 0), 3, 0.1, 0.1, 0.1, 0.02);
        }
        for (Player p : Bukkit.getOnlinePlayers())
            if (p.isOp() && holdingSaber(p) && p.getTicksLived() % 15 == 0)
                p.playSound(Sound.sound(Key.key("neyrocraft", "saber.hum"), Sound.Source.PLAYER, 0.45f, 1f));
    }

    private void remove(Player p) {
        ItemDisplay d = backpacks.remove(p.getUniqueId());
        if (d != null) d.remove();
    }
    private void purge(Player p) {
        var inv = p.getInventory();
        for (int i = 0; i < inv.getSize(); i++) if (gear(inv.getItem(i))) inv.setItem(i, null);
        if (gear(inv.getChestplate())) inv.setChestplate(null);
        if (gear(inv.getItemInOffHand())) inv.setItemInOffHand(null);
    }

    @EventHandler public void onMode(PlayerGameModeChangeEvent e) {
        if (!e.getPlayer().isOp())
            Bukkit.getScheduler().runTask(plugin, () -> { if (!e.getPlayer().isOp()) purge(e.getPlayer()); });
    }
    @EventHandler public void onJoin(PlayerJoinEvent e) {
        Bukkit.getScheduler().runTaskLater(plugin, () -> {
            if (!e.getPlayer().isOp()) purge(e.getPlayer());
            else if (holdingSaber(e.getPlayer())) ignite(e.getPlayer());
        }, 5L);
    }
    @EventHandler public void onQuit(PlayerQuitEvent e) {
        remove(e.getPlayer());
        boostAt.remove(e.getPlayer().getUniqueId());
        bladeEpoch.remove(e.getPlayer().getUniqueId());
    }
    @EventHandler public void onPickup(EntityPickupItemEvent e) {
        if (e.getEntity() instanceof Player p && !p.isOp() && gear(e.getItem().getItemStack())) e.setCancelled(true);
    }
    @EventHandler public void onUse(PlayerInteractEvent e) {
        if (e.getHand() != EquipmentSlot.HAND || (e.getAction() != Action.RIGHT_CLICK_AIR && e.getAction() != Action.RIGHT_CLICK_BLOCK)) return;
        Player p = e.getPlayer();
        if (!p.isOp() || !tagged(p.getInventory().getChestplate(), jetKey)) return;
        long now = System.currentTimeMillis();
        if (now - boostAt.getOrDefault(p.getUniqueId(), 0L) < 1800) return;
        boostAt.put(p.getUniqueId(), now);
        p.setVelocity(p.getLocation().getDirection().multiply(1.15).add(new Vector(0, 0.5, 0)));
        p.getWorld().spawnParticle(Particle.FLAME, p.getLocation().add(0, 1, 0), 18, 0.25, 0.3, 0.25, 0.1);
        p.playSound(Sound.sound(Key.key("neyrocraft", "speeder.boost"), Sound.Source.PLAYER, 0.6f, 1.2f));
    }
    @EventHandler public void onSwing(PlayerAnimationEvent e) {
        Player p = e.getPlayer();
        if (e.getAnimationType() != PlayerAnimationType.ARM_SWING || !p.isOp() || !holdingSaber(p)) return;
        p.playSound(Sound.sound(Key.key("neyrocraft", "saber.swing"), Sound.Source.PLAYER, 0.9f, 1f));
        Location eye = p.getEyeLocation();
        Vector fwd = eye.getDirection().normalize();
        Vector right = fwd.clone().crossProduct(new Vector(0, 1, 0)).normalize();
        Particle.DustOptions dust = new Particle.DustOptions(Color.fromRGB(0x46D9FF), 1f);
        for (int i = 0; i <= 10; i++) {
            double a = -0.9 + i * 0.18;
            Vector off = fwd.clone().multiply(1.6 * Math.cos(a)).add(right.clone().multiply(1.6 * Math.sin(a)))
                    .add(new Vector(0, -0.3 + i * 0.03, 0));
            p.getWorld().spawnParticle(Particle.DUST, eye.clone().add(off), 1, 0, 0, 0, dust);
        }
    }
    @EventHandler public void onSlot(PlayerItemHeldEvent e) {
        Player p = e.getPlayer();
        if (!p.isOp()) return;
        if (tagged(p.getInventory().getItem(e.getPreviousSlot()), saberKey)) retract(p, e.getPreviousSlot());
        if (tagged(p.getInventory().getItem(e.getNewSlot()), saberKey))
            Bukkit.getScheduler().runTask(plugin, () -> ignite(p));
    }
    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onSaberHit(EntityDamageByEntityEvent e) {
        if (!(e.getDamager() instanceof Player admin) || !(e.getEntity() instanceof Player target)) return;
        if (!admin.isOp() || !holdingSaber(admin)) return;
        target.getWorld().spawnParticle(Particle.ELECTRIC_SPARK, target.getLocation().add(0, 1, 0), 24, 0.3, 0.4, 0.3, 0.08);
        target.playSound(Sound.sound(Key.key("neyrocraft", "saber.hit"), Sound.Source.PLAYER, 1f, 1f));
        target.ban("Забанен админским световым мечом NEYRO-CORP", (java.util.Date) null, admin.getName(), true);
        admin.sendMessage(Component.text("Игрок " + target.getName() + " забанен мечом NEYRO-CORP.", TextColor.color(0xFF9A37)));
        plugin.getLogger().info(admin.getName() + " banned " + target.getName() + " with the admin saber");
    }
}
