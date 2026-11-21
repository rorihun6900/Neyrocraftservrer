package net.neyrocraft.privat;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.TextComponent;
import net.kyori.adventure.text.event.ClickEvent;
import net.kyori.adventure.text.format.NamedTextColor;
import net.kyori.adventure.text.format.TextColor;
import net.kyori.adventure.text.format.TextDecoration;
import org.bukkit.Bukkit;
import org.bukkit.Color;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.OfflinePlayer;
import org.bukkit.Particle;
import org.bukkit.Tag;
import org.bukkit.World;
import org.bukkit.block.Block;
import org.bukkit.block.BlockState;
import org.bukkit.command.Command;
import org.bukkit.command.CommandSender;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.Animals;
import org.bukkit.entity.ArmorStand;
import org.bukkit.entity.Entity;
import org.bukkit.entity.Hanging;
import org.bukkit.entity.Monster;
import org.bukkit.entity.Player;
import org.bukkit.entity.Projectile;
import org.bukkit.entity.Vehicle;
import org.bukkit.entity.Villager;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.block.Action;
import org.bukkit.event.block.BlockBreakEvent;
import org.bukkit.event.block.BlockBurnEvent;
import org.bukkit.event.block.BlockDispenseEvent;
import org.bukkit.event.block.BlockExplodeEvent;
import org.bukkit.event.block.BlockFromToEvent;
import org.bukkit.event.block.BlockIgniteEvent;
import org.bukkit.event.block.BlockPistonExtendEvent;
import org.bukkit.event.block.BlockPistonRetractEvent;
import org.bukkit.event.block.BlockPlaceEvent;
import org.bukkit.event.block.BlockSpreadEvent;
import org.bukkit.event.entity.EntityChangeBlockEvent;
import org.bukkit.event.entity.EntityDamageByEntityEvent;
import org.bukkit.event.entity.EntityExplodeEvent;
import org.bukkit.event.entity.EntityInteractEvent;
import org.bukkit.event.entity.EntityPlaceEvent;
import org.bukkit.event.hanging.HangingBreakByEntityEvent;
import org.bukkit.event.hanging.HangingBreakEvent;
import org.bukkit.event.hanging.HangingPlaceEvent;
import org.bukkit.event.player.PlayerArmorStandManipulateEvent;
import org.bukkit.event.player.PlayerBucketEmptyEvent;
import org.bukkit.event.player.PlayerBucketFillEvent;
import org.bukkit.event.player.PlayerInteractEntityEvent;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.event.player.PlayerMoveEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.event.vehicle.VehicleDestroyEvent;
import org.bukkit.inventory.EquipmentSlot;
import org.bukkit.inventory.InventoryHolder;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.persistence.PersistentDataType;
import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.projectiles.ProjectileSource;

import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * NeyroPrivate — private regions ("приваты") for survival and Minecraft 1.0.
 * A region is a rectangle of columns (from bedrock to the sky). Owners manage it, members may build in it,
 * everybody else can only walk through. Select with the golden hoe (/rg wand) or claim around yourself (/rg auto).
 */
public final class NeyroPrivate extends JavaPlugin implements Listener {

    static final TextColor GREEN = TextColor.color(0x55E07A), CYAN = TextColor.color(0x7FE8FF), ORANGE = TextColor.color(0xFF8C00);
    static final String[] FLAGS = {"pvp", "chests", "doors", "explosions"};

    static final class Region {
        String id, world;
        int minX, minZ, maxX, maxZ;
        final Set<UUID> owners = new LinkedHashSet<>(), members = new LinkedHashSet<>();
        final Map<String, Boolean> flags = new HashMap<>();
        long created;

        boolean contains(Location l) {
            return l.getWorld() != null && l.getWorld().getName().equals(world)
                    && l.getBlockX() >= minX && l.getBlockX() <= maxX && l.getBlockZ() >= minZ && l.getBlockZ() <= maxZ;
        }
        boolean overlaps(String w, int x1, int z1, int x2, int z2) {
            return world.equals(w) && x1 <= maxX && x2 >= minX && z1 <= maxZ && z2 >= minZ;
        }
        boolean flag(String f) { return flags.getOrDefault(f, false); }
        int area() { return (maxX - minX + 1) * (maxZ - minZ + 1); }
        boolean isOwner(UUID u) { return owners.contains(u); }
        boolean trusted(UUID u) { return owners.contains(u) || members.contains(u); }
    }

    private final Map<String, Region> regions = new LinkedHashMap<>();
    private final Map<UUID, Location[]> selection = new HashMap<>();
    private final Map<UUID, String> lastRegion = new HashMap<>();
    private final Map<UUID, Long> lastDeny = new HashMap<>();
    private NamespacedKey wandKey;
    private File file;
    private Set<String> worlds;

    @Override
    public void onEnable() {
        saveDefaultConfig();
        wandKey = new NamespacedKey(this, "wand");
        worlds = new LinkedHashSet<>(getConfig().getStringList("worlds"));
        file = new File(getDataFolder(), "regions.yml");
        load();
        getServer().getPluginManager().registerEvents(this, this);
        Bukkit.getScheduler().runTaskTimer(this, this::showSelections, 20L, 20L);
        getLogger().info("NeyroPrivate: " + regions.size() + " region(s) in " + worlds);
    }

    @Override
    public void onDisable() {
        save();
    }

    // ------------------------------------------------------------ storage

    private void load() {
        regions.clear();
        YamlConfiguration y = YamlConfiguration.loadConfiguration(file);
        for (String id : y.getKeys(false)) {
            ConfigurationSection s = y.getConfigurationSection(id);
            if (s == null) continue;
            Region r = new Region();
            r.id = id; r.world = s.getString("world");
            r.minX = s.getInt("minX"); r.minZ = s.getInt("minZ"); r.maxX = s.getInt("maxX"); r.maxZ = s.getInt("maxZ");
            for (String u : s.getStringList("owners")) r.owners.add(UUID.fromString(u));
            for (String u : s.getStringList("members")) r.members.add(UUID.fromString(u));
            ConfigurationSection f = s.getConfigurationSection("flags");
            if (f != null) for (String k : f.getKeys(false)) r.flags.put(k, f.getBoolean(k));
            r.created = s.getLong("created");
            regions.put(id, r);
        }
    }

    private void save() {
        YamlConfiguration y = new YamlConfiguration();
        for (Region r : regions.values()) {
            String p = r.id + ".";
            y.set(p + "world", r.world);
            y.set(p + "minX", r.minX); y.set(p + "minZ", r.minZ); y.set(p + "maxX", r.maxX); y.set(p + "maxZ", r.maxZ);
            List<String> o = new ArrayList<>(), m = new ArrayList<>();
            for (UUID u : r.owners) o.add(u.toString());
            for (UUID u : r.members) m.add(u.toString());
            y.set(p + "owners", o); y.set(p + "members", m);
            for (Map.Entry<String, Boolean> e : r.flags.entrySet()) y.set(p + "flags." + e.getKey(), e.getValue());
            y.set(p + "created", r.created);
        }
        try { y.save(file); } catch (IOException e) { getLogger().warning("Could not save regions: " + e); }
    }

    // ------------------------------------------------------------ lookup and rules

    boolean protectedWorld(World w) {
        return w != null && worlds.contains(w.getName());
    }

    Region at(Location l) {
        if (l == null || !protectedWorld(l.getWorld())) return null;
        for (Region r : regions.values()) if (r.contains(l)) return r;
        return null;
    }

    private static boolean admin(Player p) {
        return p.hasPermission("neyroprivate.admin");
    }

    /** May this player build at this place? */
    boolean canBuild(Player p, Location l) {
        Region r = at(l);
        return r == null || r.trusted(p.getUniqueId()) || admin(p);
    }

    private void deny(Player p, Region r) {
        long now = System.currentTimeMillis();
        Long last = lastDeny.get(p.getUniqueId());
        if (last != null && now - last < 1500) return;
        lastDeny.put(p.getUniqueId(), now);
        p.sendActionBar(Component.text()
                .append(Component.text("🔒 ", ORANGE))
                .append(Component.text("Это приват «" + r.id + "»", NamedTextColor.RED))
                .append(Component.text(" · владелец " + ownerNames(r), NamedTextColor.GRAY)).build());
    }

    private String ownerNames(Region r) {
        List<String> n = new ArrayList<>();
        for (UUID u : r.owners) n.add(name(u));
        return n.isEmpty() ? "—" : String.join(", ", n);
    }

    private static String name(UUID u) {
        OfflinePlayer o = Bukkit.getOfflinePlayer(u);
        return o.getName() == null ? u.toString().substring(0, 8) : o.getName();
    }

    private boolean check(Player p, Location l, org.bukkit.event.Cancellable e) {
        Region r = at(l);
        if (r == null || r.trusted(p.getUniqueId()) || admin(p)) return true;
        e.setCancelled(true);
        deny(p, r);
        return false;
    }

    private static Player playerOf(Entity damager) {
        if (damager instanceof Player p) return p;
        if (damager instanceof Projectile pr) {
            ProjectileSource s = pr.getShooter();
            if (s instanceof Player p) return p;
        }
        return null;
    }

    // ------------------------------------------------------------ protection: blocks

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onBreak(BlockBreakEvent e) {
        if (isWand(e.getPlayer().getInventory().getItemInMainHand())) { e.setCancelled(true); return; }
        check(e.getPlayer(), e.getBlock().getLocation(), e);
    }

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onPlace(BlockPlaceEvent e) {
        check(e.getPlayer(), e.getBlock().getLocation(), e);
    }

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onBucket(PlayerBucketEmptyEvent e) {
        check(e.getPlayer(), e.getBlock().getLocation(), e);
    }

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onBucketFill(PlayerBucketFillEvent e) {
        check(e.getPlayer(), e.getBlock().getLocation(), e);
    }

    private static final Set<Material> OPEN_FOR_ALL = Set.of(Material.CRAFTING_TABLE, Material.ENCHANTING_TABLE, Material.ENDER_CHEST,
            Material.CARTOGRAPHY_TABLE, Material.LOOM, Material.SMITHING_TABLE, Material.STONECUTTER, Material.GRINDSTONE);

    @EventHandler(priority = EventPriority.HIGH)
    public void onInteract(PlayerInteractEvent e) {
        Player p = e.getPlayer();
        if (e.getHand() == EquipmentSlot.HAND && isWand(e.getItem()) && e.getClickedBlock() != null
                && (e.getAction() == Action.LEFT_CLICK_BLOCK || e.getAction() == Action.RIGHT_CLICK_BLOCK)) {
            e.setCancelled(true);
            select(p, e.getClickedBlock().getLocation(), e.getAction() == Action.LEFT_CLICK_BLOCK ? 0 : 1);
            return;
        }
        Block b = e.getClickedBlock();
        if (b == null) return;
        Region r = at(b.getLocation());
        if (r == null || r.trusted(p.getUniqueId()) || admin(p)) return;
        Material m = b.getType();
        if (e.getAction() == Action.PHYSICAL) {
            boolean plate = Tag.PRESSURE_PLATES.isTagged(m);
            if (!(plate && r.flag("doors"))) e.setCancelled(true);
            return;
        }
        if (e.getAction() != Action.RIGHT_CLICK_BLOCK) return;
        boolean door = Tag.DOORS.isTagged(m) || Tag.TRAPDOORS.isTagged(m) || Tag.FENCE_GATES.isTagged(m) || Tag.BUTTONS.isTagged(m) || m == Material.LEVER;
        boolean container = b.getState() instanceof InventoryHolder;
        if (OPEN_FOR_ALL.contains(m)) return;
        if (door && r.flag("doors")) return;
        if (container && r.flag("chests")) return;
        if (m.isInteractable() || container || door) { e.setCancelled(true); deny(p, r); return; }
        // using an item on a block: spawn eggs, bone meal, flint and steel, armor stands, boats...
        ItemStack it = e.getItem();
        if (it != null && it.getType() != Material.AIR && !it.getType().isEdible()) { e.setCancelled(true); deny(p, r); }
    }

    // ------------------------------------------------------------ protection: entities

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onDamage(EntityDamageByEntityEvent e) {
        Player p = playerOf(e.getDamager());
        if (p == null) return;
        Entity v = e.getEntity();
        Region r = at(v.getLocation());
        if (r == null) return;
        if (v instanceof Player) {
            if (!r.flag("pvp")) { e.setCancelled(true); p.sendActionBar(Component.text("⚔ В привате «" + r.id + "» PvP выключено", NamedTextColor.RED)); }
            return;
        }
        if (v instanceof Monster) return;                                   // monsters may always be fought
        if (r.trusted(p.getUniqueId()) || admin(p)) return;
        if (v instanceof Animals || v instanceof Villager || v instanceof ArmorStand || v instanceof Hanging || v instanceof Vehicle
                || v instanceof org.bukkit.entity.Golem || v instanceof org.bukkit.entity.Allay) {
            e.setCancelled(true);
            deny(p, r);
        }
    }

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onInteractEntity(PlayerInteractEntityEvent e) {
        Entity v = e.getRightClicked();
        Region r = at(v.getLocation());
        if (r == null || r.trusted(e.getPlayer().getUniqueId()) || admin(e.getPlayer())) return;
        boolean storage = v instanceof InventoryHolder && !(v instanceof Player);
        if (v instanceof org.bukkit.entity.ItemFrame || v instanceof ArmorStand || v instanceof Animals && e.getPlayer().getInventory().getItem(e.getHand()).getType() != Material.AIR
                || storage && !r.flag("chests") || v instanceof org.bukkit.entity.Allay) {
            e.setCancelled(true);
            deny(e.getPlayer(), r);
        }
    }

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onStand(PlayerArmorStandManipulateEvent e) {
        check(e.getPlayer(), e.getRightClicked().getLocation(), e);
    }

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onHangingBreak(HangingBreakByEntityEvent e) {
        Player p = playerOf(e.getRemover());
        if (p != null) check(p, e.getEntity().getLocation(), e);
        else if (at(e.getEntity().getLocation()) != null) e.setCancelled(true);
    }

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onHangingExplode(HangingBreakEvent e) {
        if (e.getCause() == HangingBreakEvent.RemoveCause.EXPLOSION) {
            Region r = at(e.getEntity().getLocation());
            if (r != null && !r.flag("explosions")) e.setCancelled(true);
        }
    }

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onHangingPlace(HangingPlaceEvent e) {
        if (e.getPlayer() != null) check(e.getPlayer(), e.getEntity().getLocation(), e);
    }

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onEntityPlace(EntityPlaceEvent e) {
        if (e.getPlayer() != null) check(e.getPlayer(), e.getEntity().getLocation(), e);
    }

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onVehicle(VehicleDestroyEvent e) {
        Player p = e.getAttacker() == null ? null : playerOf(e.getAttacker());
        if (p != null) check(p, e.getVehicle().getLocation(), e);
    }

    // ------------------------------------------------------------ protection: the world itself

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onExplode(EntityExplodeEvent e) {
        e.blockList().removeIf(b -> { Region r = at(b.getLocation()); return r != null && !r.flag("explosions"); });
    }

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onBlockExplode(BlockExplodeEvent e) {
        e.blockList().removeIf(b -> { Region r = at(b.getLocation()); return r != null && !r.flag("explosions"); });
    }

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onBurn(BlockBurnEvent e) {
        if (at(e.getBlock().getLocation()) != null) e.setCancelled(true);
    }

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onIgnite(BlockIgniteEvent e) {
        Region r = at(e.getBlock().getLocation());
        if (r == null) return;
        if (e.getPlayer() != null) { if (!r.trusted(e.getPlayer().getUniqueId()) && !admin(e.getPlayer())) { e.setCancelled(true); deny(e.getPlayer(), r); } return; }
        if (e.getCause() != BlockIgniteEvent.IgniteCause.FLINT_AND_STEEL) e.setCancelled(true);
    }

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onSpread(BlockSpreadEvent e) {
        if (e.getSource().getType() == Material.FIRE && at(e.getBlock().getLocation()) != null) e.setCancelled(true);
    }

    /** Water and lava may not flow into a private region from outside. */
    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onFlow(BlockFromToEvent e) {
        Region to = at(e.getToBlock().getLocation());
        if (to != null && to != at(e.getBlock().getLocation())) e.setCancelled(true);
    }

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onPistonOut(BlockPistonExtendEvent e) {
        Region home = at(e.getBlock().getLocation());
        for (Block b : e.getBlocks()) {
            if (at(b.getLocation()) != home || at(b.getRelative(e.getDirection()).getLocation()) != home) { e.setCancelled(true); return; }
        }
        if (at(e.getBlock().getRelative(e.getDirection()).getLocation()) != home) e.setCancelled(true);
    }

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onPistonIn(BlockPistonRetractEvent e) {
        Region home = at(e.getBlock().getLocation());
        for (Block b : e.getBlocks()) if (at(b.getLocation()) != home) { e.setCancelled(true); return; }
    }

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onDispense(BlockDispenseEvent e) {
        org.bukkit.block.data.BlockData d = e.getBlock().getBlockData();
        if (!(d instanceof org.bukkit.block.data.Directional dir)) return;
        Region target = at(e.getBlock().getRelative(dir.getFacing()).getLocation());
        if (target != null && target != at(e.getBlock().getLocation())) e.setCancelled(true);
    }

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onMobChange(EntityChangeBlockEvent e) {
        if (e.getEntity() instanceof Player || e.getEntity() instanceof org.bukkit.entity.FallingBlock) return;
        if (at(e.getBlock().getLocation()) != null) e.setCancelled(true);
    }

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onMobTrample(EntityInteractEvent e) {
        if (e.getBlock().getType() == Material.FARMLAND && at(e.getBlock().getLocation()) != null) e.setCancelled(true);
    }

    // ------------------------------------------------------------ entering and leaving

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onMove(PlayerMoveEvent e) {
        if (e.getFrom().getBlockX() == e.getTo().getBlockX() && e.getFrom().getBlockZ() == e.getTo().getBlockZ()
                && e.getFrom().getWorld() == e.getTo().getWorld()) return;
        Player p = e.getPlayer();
        Region r = at(e.getTo());
        String now = r == null ? null : r.id, before = lastRegion.get(p.getUniqueId());
        if (java.util.Objects.equals(now, before)) return;
        if (now == null) lastRegion.remove(p.getUniqueId()); else lastRegion.put(p.getUniqueId(), now);
        if (r != null) {
            boolean mine = r.trusted(p.getUniqueId());
            p.sendActionBar(Component.text()
                    .append(Component.text("🏠 ", GREEN))
                    .append(Component.text(mine ? "Твой приват «" + r.id + "»" : "Приват «" + r.id + "»", mine ? GREEN : CYAN))
                    .append(Component.text(mine ? "" : " · владелец " + ownerNames(r), NamedTextColor.GRAY)).build());
        } else if (before != null) {
            p.sendActionBar(Component.text("Ты покинул приват «" + before + "»", NamedTextColor.GRAY));
        }
    }

    @EventHandler
    public void onQuit(PlayerQuitEvent e) {
        lastRegion.remove(e.getPlayer().getUniqueId());
        lastDeny.remove(e.getPlayer().getUniqueId());
    }

    // ------------------------------------------------------------ selection

    private ItemStack wand() {
        ItemStack it = new ItemStack(Material.GOLDEN_HOE);
        ItemMeta m = it.getItemMeta();
        m.displayName(Component.text("Жезл привата", ORANGE).decorate(TextDecoration.BOLD).decoration(TextDecoration.ITALIC, false));
        m.lore(List.of(
                Component.text("ЛКМ по блоку — первая точка", NamedTextColor.GRAY).decoration(TextDecoration.ITALIC, false),
                Component.text("ПКМ по блоку — вторая точка", NamedTextColor.GRAY).decoration(TextDecoration.ITALIC, false),
                Component.text("затем /rg claim <название>", NamedTextColor.YELLOW).decoration(TextDecoration.ITALIC, false)));
        m.getPersistentDataContainer().set(wandKey, PersistentDataType.BYTE, (byte) 1);
        m.setUnbreakable(true);
        it.setItemMeta(m);
        return it;
    }

    private boolean isWand(ItemStack it) {
        return it != null && it.hasItemMeta() && it.getItemMeta().getPersistentDataContainer().has(wandKey);
    }

    private void select(Player p, Location l, int which) {
        if (!protectedWorld(l.getWorld())) { msg(p, Component.text("В этом мире приваты не нужны — здесь уже защита сервера", NamedTextColor.RED)); return; }
        Location[] s = selection.computeIfAbsent(p.getUniqueId(), k -> new Location[2]);
        if (s[1 - which] != null && !s[1 - which].getWorld().equals(l.getWorld())) s[1 - which] = null;
        s[which] = l.clone();
        msg(p, Component.text((which == 0 ? "Первая" : "Вторая") + " точка: " + l.getBlockX() + ", " + l.getBlockZ(), GREEN)
                .append(sizeText(s)));
        p.playSound(l, org.bukkit.Sound.BLOCK_NOTE_BLOCK_PLING, 0.6f, which == 0 ? 1.2f : 1.6f);
    }

    private Component sizeText(Location[] s) {
        if (s[0] == null || s[1] == null) return Component.empty();
        int w = Math.abs(s[0].getBlockX() - s[1].getBlockX()) + 1, d = Math.abs(s[0].getBlockZ() - s[1].getBlockZ()) + 1;
        return Component.text("   размер " + w + "×" + d + " (" + (w * d) + " блоков)", NamedTextColor.GRAY);
    }

    /** While holding the wand: the selection glows; everyone sees the edge of a region they stand in for a moment with /rg show. */
    private void showSelections() {
        for (Player p : Bukkit.getOnlinePlayers()) {
            if (!isWand(p.getInventory().getItemInMainHand())) continue;
            Location[] s = selection.get(p.getUniqueId());
            if (s == null || s[0] == null || s[1] == null || !s[0].getWorld().equals(p.getWorld())) continue;
            outline(p, Math.min(s[0].getBlockX(), s[1].getBlockX()), Math.min(s[0].getBlockZ(), s[1].getBlockZ()),
                    Math.max(s[0].getBlockX(), s[1].getBlockX()), Math.max(s[0].getBlockZ(), s[1].getBlockZ()), Color.fromRGB(0xFFC040));
        }
    }

    private void outline(Player p, int x1, int z1, int x2, int z2, Color c) {
        World w = p.getWorld();
        Particle.DustOptions dust = new Particle.DustOptions(c, 1.4f);
        int y = p.getLocation().getBlockY();
        int perimeter = 2 * ((x2 - x1) + (z2 - z1));
        int step = Math.max(1, perimeter / 160);
        for (int x = x1; x <= x2 + 1; x += step) for (int dy = 0; dy <= 2; dy += 2) {
            p.spawnParticle(Particle.DUST, x, y + dy + 0.2, z1, 1, 0, 0, 0, 0, dust);
            p.spawnParticle(Particle.DUST, x, y + dy + 0.2, z2 + 1, 1, 0, 0, 0, 0, dust);
        }
        for (int z = z1; z <= z2 + 1; z += step) for (int dy = 0; dy <= 2; dy += 2) {
            p.spawnParticle(Particle.DUST, x1, y + dy + 0.2, z, 1, 0, 0, 0, 0, dust);
            p.spawnParticle(Particle.DUST, x2 + 1, y + dy + 0.2, z, 1, 0, 0, 0, 0, dust);
        }
        for (int[] corner : new int[][]{{x1, z1}, {x2 + 1, z1}, {x1, z2 + 1}, {x2 + 1, z2 + 1}})
            for (int dy = 0; dy < 6; dy++) p.spawnParticle(Particle.DUST, corner[0], y + dy, corner[1], 1, 0, 0, 0, 0, dust);
    }

    // ------------------------------------------------------------ commands

    private void msg(CommandSender s, Component c) {
        s.sendMessage(Component.text().append(Component.text("[Приват] ", GREEN).decorate(TextDecoration.BOLD)).append(c.decoration(TextDecoration.BOLD, false)).build());
    }

    private void help(CommandSender s, String l) {
        s.sendMessage(Component.text("━━━━━━ ", NamedTextColor.DARK_GRAY).append(Component.text("ПРИВАТЫ NeyroCraft", GREEN).decorate(TextDecoration.BOLD)).append(Component.text(" ━━━━━━", NamedTextColor.DARK_GRAY)));
        line(s, "/" + l + " auto <название> [радиус]", "приват вокруг тебя (радиус " + getConfig().getInt("auto-radius", 15) + " по умолчанию)");
        line(s, "/" + l + " wand", "жезл выделения: ЛКМ и ПКМ по углам");
        line(s, "/" + l + " pos1 | pos2", "точка там, где стоишь");
        line(s, "/" + l + " claim <название>", "создать приват из выделения");
        line(s, "/" + l + " add <приват> <ник>", "пустить игрока строить");
        line(s, "/" + l + " remove <приват> <ник>", "убрать игрока");
        line(s, "/" + l + " addowner <приват> <ник>", "сделать совладельцем");
        line(s, "/" + l + " flag <приват> <pvp|chests|doors|explosions> <on|off>", "настройки");
        line(s, "/" + l + " info [приват]", "о привате (или где стоишь)");
        line(s, "/" + l + " list", "твои приваты");
        line(s, "/" + l + " show [приват]", "показать границы");
        line(s, "/" + l + " delete <приват>", "удалить приват");
        s.sendMessage(Component.text("Лимит: " + getConfig().getInt("max-regions", 3) + " привата, до " + getConfig().getInt("max-area", 10000) + " блоков площади каждый", NamedTextColor.DARK_GRAY));
    }

    private static void line(CommandSender s, String cmd, String what) {
        s.sendMessage(Component.text().append(Component.text(cmd, NamedTextColor.YELLOW).clickEvent(ClickEvent.suggestCommand(cmd.replaceAll(" <.*", " ").replaceAll(" \\[.*", " "))))
                .append(Component.text(" — " + what, NamedTextColor.GRAY)).build());
    }

    private Region region(CommandSender s, String id) {
        Region r = regions.get(id.toLowerCase(Locale.ROOT));
        if (r == null) msg(s, Component.text("Привата «" + id + "» нет. Твои: /rg list", NamedTextColor.RED));
        return r;
    }

    private boolean manages(CommandSender s, Region r) {
        if (!(s instanceof Player p) || r.isOwner(p.getUniqueId()) || admin(p)) return true;
        msg(s, Component.text("Управлять приватом «" + r.id + "» может только владелец", NamedTextColor.RED));
        return false;
    }

    private int owned(UUID u) {
        int n = 0;
        for (Region r : regions.values()) if (r.isOwner(u)) n++;
        return n;
    }

    @SuppressWarnings("deprecation")
    private static OfflinePlayer player(String name) {
        Player online = Bukkit.getPlayerExact(name);
        return online != null ? online : Bukkit.getOfflinePlayer(name);
    }

    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] a) {
        if (a.length == 0 || a[0].equalsIgnoreCase("help")) { help(sender, label); return true; }
        String sub = a[0].toLowerCase(Locale.ROOT);
        Player p = sender instanceof Player pl ? pl : null;
        switch (sub) {
            case "wand" -> {
                if (p == null) return true;
                p.getInventory().addItem(wand());
                msg(p, Component.text("Держи жезл: ЛКМ по блоку — первый угол, ПКМ — второй. Потом /" + label + " claim <название>", GREEN));
            }
            case "pos1", "pos2" -> { if (p != null) select(p, p.getLocation().getBlock().getLocation(), sub.equals("pos1") ? 0 : 1); }
            case "auto" -> {
                if (p == null || a.length < 2) { help(sender, label); return true; }
                int rad = a.length >= 3 ? parse(a[2], getConfig().getInt("auto-radius", 15)) : getConfig().getInt("auto-radius", 15);
                Location l = p.getLocation();
                create(p, a[1], l.getWorld(), l.getBlockX() - rad, l.getBlockZ() - rad, l.getBlockX() + rad, l.getBlockZ() + rad);
            }
            case "claim", "create" -> {
                if (p == null || a.length < 2) { help(sender, label); return true; }
                Location[] s = selection.get(p.getUniqueId());
                if (s == null || s[0] == null || s[1] == null) { msg(p, Component.text("Сначала выдели два угла: /" + label + " wand", NamedTextColor.RED)); return true; }
                create(p, a[1], s[0].getWorld(), Math.min(s[0].getBlockX(), s[1].getBlockX()), Math.min(s[0].getBlockZ(), s[1].getBlockZ()),
                        Math.max(s[0].getBlockX(), s[1].getBlockX()), Math.max(s[0].getBlockZ(), s[1].getBlockZ()));
            }
            case "add", "addmember", "remove", "removemember", "addowner", "removeowner" -> {
                if (a.length < 3) { help(sender, label); return true; }
                Region r = region(sender, a[1]);
                if (r == null || !manages(sender, r)) return true;
                OfflinePlayer t = player(a[2]);
                UUID u = t.getUniqueId();
                switch (sub) {
                    case "add", "addmember" -> { r.members.add(u); msg(sender, Component.text(a[2] + " теперь может строить в «" + r.id + "»", GREEN)); }
                    case "remove", "removemember" -> { r.members.remove(u); msg(sender, Component.text(a[2] + " больше не участник «" + r.id + "»", NamedTextColor.GRAY)); }
                    case "addowner" -> { r.owners.add(u); r.members.remove(u); msg(sender, Component.text(a[2] + " теперь совладелец «" + r.id + "»", GREEN)); }
                    default -> {
                        if (r.owners.size() <= 1 && r.owners.contains(u)) { msg(sender, Component.text("У привата должен остаться хотя бы один владелец", NamedTextColor.RED)); return true; }
                        r.owners.remove(u); msg(sender, Component.text(a[2] + " больше не владелец «" + r.id + "»", NamedTextColor.GRAY));
                    }
                }
                save();
                Player online = t.getPlayer();
                if (online != null && sender != online) msg(online, Component.text("Тебя " + (sub.startsWith("add") ? "добавили в" : "убрали из") + " приват «" + r.id + "»", CYAN));
            }
            case "flag" -> {
                if (a.length < 4) { help(sender, label); return true; }
                Region r = region(sender, a[1]);
                if (r == null || !manages(sender, r)) return true;
                String f = a[2].toLowerCase(Locale.ROOT);
                if (!List.of(FLAGS).contains(f)) { msg(sender, Component.text("Флаги: pvp, chests (сундуки для всех), doors (двери и кнопки для всех), explosions", NamedTextColor.RED)); return true; }
                boolean on = a[3].equalsIgnoreCase("on") || a[3].equalsIgnoreCase("вкл") || a[3].equalsIgnoreCase("true");
                r.flags.put(f, on);
                save();
                msg(sender, Component.text("«" + r.id + "»: " + f + " = " + (on ? "вкл" : "выкл"), GREEN));
            }
            case "info" -> {
                Region r = a.length >= 2 ? region(sender, a[1]) : (p != null ? at(p.getLocation()) : null);
                if (r == null) { if (a.length < 2) msg(sender, Component.text("Здесь нет привата", NamedTextColor.GRAY)); return true; }
                info(sender, r);
            }
            case "list" -> {
                UUID u = p != null ? p.getUniqueId() : null;
                List<Region> mine = new ArrayList<>();
                for (Region r : regions.values()) if (u == null || r.trusted(u)) mine.add(r);
                if (mine.isEmpty()) { msg(sender, Component.text("Приватов нет. Создай: /" + label + " auto <название>", NamedTextColor.GRAY)); return true; }
                msg(sender, Component.text((u == null ? "Все приваты: " : "Твои приваты: ") + mine.size(), GREEN));
                for (Region r : mine) sender.sendMessage(Component.text()
                        .append(Component.text("  ◆ " + r.id, u != null && r.isOwner(u) ? GREEN : CYAN).clickEvent(ClickEvent.runCommand("/" + label + " info " + r.id)))
                        .append(Component.text("  " + r.world + " " + r.minX + "," + r.minZ + " → " + r.maxX + "," + r.maxZ + "  (" + r.area() + " блоков)", NamedTextColor.GRAY)).build());
            }
            case "show" -> {
                if (p == null) return true;
                Region r = a.length >= 2 ? region(sender, a[1]) : at(p.getLocation());
                if (r == null) { if (a.length < 2) msg(p, Component.text("Здесь нет привата", NamedTextColor.GRAY)); return true; }
                if (!r.world.equals(p.getWorld().getName())) { msg(p, Component.text("Этот приват в другом мире", NamedTextColor.RED)); return true; }
                final Region rr = r;
                for (int i = 0; i < 10; i++) Bukkit.getScheduler().runTaskLater(this, () -> { if (p.isOnline()) outline(p, rr.minX, rr.minZ, rr.maxX, rr.maxZ, Color.fromRGB(0x55E07A)); }, i * 20L);
                msg(p, Component.text("Границы «" + r.id + "» подсвечены на 10 секунд", GREEN));
            }
            case "delete", "remove-region", "del" -> {
                if (a.length < 2) { help(sender, label); return true; }
                Region r = region(sender, a[1]);
                if (r == null || !manages(sender, r)) return true;
                regions.remove(r.id);
                save();
                msg(sender, Component.text("Приват «" + r.id + "» удалён", NamedTextColor.GRAY));
            }
            case "reload" -> {
                if (p != null && !admin(p)) return true;
                reloadConfig(); worlds = new LinkedHashSet<>(getConfig().getStringList("worlds")); load();
                msg(sender, Component.text("Перезагружено: " + regions.size() + " приватов", GREEN));
            }
            default -> help(sender, label);
        }
        return true;
    }

    private static int parse(String s, int def) {
        try { return Math.max(1, Integer.parseInt(s)); } catch (NumberFormatException e) { return def; }
    }

    private void create(Player p, String rawId, World w, int x1, int z1, int x2, int z2) {
        if (w != null && w.getName().equals("world") && !admin(p)) {
            Location spawn = w.getSpawnLocation();
            int sx = spawn.getBlockX(), sz = spawn.getBlockZ();
            long dx = Math.max(x1 - sx, Math.max(0, sx - x2));
            long dz = Math.max(z1 - sz, Math.max(0, sz - z2));
            if (dx * dx + dz * dz <= 250_000L) {
                msg(p, Component.text("Приват должен быть дальше 500 блоков от спавна", NamedTextColor.RED)); return;
            }
        }
        String id = rawId.toLowerCase(Locale.ROOT);
        if (!id.matches("[a-zа-яё0-9_\\-]{2,24}")) { msg(p, Component.text("Название: 2–24 буквы, цифры, _ или -", NamedTextColor.RED)); return; }
        if (!protectedWorld(w)) { msg(p, Component.text("Приваты работают только в выживании и в Minecraft 1.0", NamedTextColor.RED)); return; }
        if (regions.containsKey(id)) { msg(p, Component.text("Название «" + id + "» уже занято", NamedTextColor.RED)); return; }
        boolean adm = admin(p);
        int area = (x2 - x1 + 1) * (z2 - z1 + 1), min = getConfig().getInt("min-size", 3);
        if (!adm) {
            if (owned(p.getUniqueId()) >= getConfig().getInt("max-regions", 3)) { msg(p, Component.text("У тебя уже " + getConfig().getInt("max-regions", 3) + " привата — это максимум", NamedTextColor.RED)); return; }
            if (area > getConfig().getInt("max-area", 10000)) { msg(p, Component.text("Слишком большой: " + area + " блоков, можно до " + getConfig().getInt("max-area", 10000), NamedTextColor.RED)); return; }
        }
        if (x2 - x1 + 1 < min || z2 - z1 + 1 < min) { msg(p, Component.text("Слишком маленький: минимум " + min + "×" + min, NamedTextColor.RED)); return; }
        for (Region o : regions.values())
            if (o.overlaps(w.getName(), x1, z1, x2, z2) && !o.isOwner(p.getUniqueId()) && !adm) {
                msg(p, Component.text("Задевает чужой приват «" + o.id + "» (" + ownerNames(o) + ")", NamedTextColor.RED));
                return;
            }
        Region r = new Region();
        r.id = id; r.world = w.getName();
        r.minX = x1; r.minZ = z1; r.maxX = x2; r.maxZ = z2;
        r.owners.add(p.getUniqueId());
        r.created = System.currentTimeMillis();
        regions.put(id, r);
        save();
        selection.remove(p.getUniqueId());
        lastRegion.put(p.getUniqueId(), at(p.getLocation()) == r ? id : lastRegion.get(p.getUniqueId()));
        msg(p, Component.text("Приват «" + id + "» создан: " + (x2 - x1 + 1) + "×" + (z2 - z1 + 1) + ", от земли до неба", GREEN));
        msg(p, Component.text("Добавить друга: /rg add " + id + " <ник>", NamedTextColor.GRAY));
        p.playSound(p.getLocation(), org.bukkit.Sound.UI_TOAST_CHALLENGE_COMPLETE, 0.6f, 1.4f);
        for (int i = 0; i < 6; i++) Bukkit.getScheduler().runTaskLater(this, () -> { if (p.isOnline() && p.getWorld().equals(w)) outline(p, r.minX, r.minZ, r.maxX, r.maxZ, Color.fromRGB(0x55E07A)); }, i * 20L);
    }

    private void info(CommandSender s, Region r) {
        List<String> m = new ArrayList<>();
        for (UUID u : r.members) m.add(name(u));
        msg(s, Component.text("«" + r.id + "»", GREEN).decorate(TextDecoration.BOLD));
        line(s, "Мир", r.world + "  " + r.minX + "," + r.minZ + " → " + r.maxX + "," + r.maxZ + "  (" + (r.maxX - r.minX + 1) + "×" + (r.maxZ - r.minZ + 1) + ")");
        line(s, "Владельцы", ownerNames(r));
        line(s, "Участники", m.isEmpty() ? "—" : String.join(", ", m));
        TextComponent.Builder f = Component.text().append(Component.text("Флаги", NamedTextColor.YELLOW)).append(Component.text(" — ", NamedTextColor.GRAY));
        for (String fl : FLAGS) f.append(Component.text(fl + " ", r.flag(fl) ? GREEN : NamedTextColor.DARK_GRAY));
        s.sendMessage(f.build());
    }

    @Override
    public List<String> onTabComplete(CommandSender sender, Command command, String alias, String[] a) {
        List<String> out = new ArrayList<>();
        String[] subs = {"auto", "wand", "pos1", "pos2", "claim", "add", "remove", "addowner", "removeowner", "flag", "info", "list", "show", "delete", "help"};
        if (a.length == 1) { for (String s : subs) if (s.startsWith(a[0].toLowerCase(Locale.ROOT))) out.add(s); return out; }
        String sub = a[0].toLowerCase(Locale.ROOT);
        UUID u = sender instanceof Player p ? p.getUniqueId() : null;
        if (a.length == 2 && !List.of("auto", "claim", "create", "wand", "pos1", "pos2", "list", "help").contains(sub))
            for (Region r : regions.values()) if ((u == null || r.trusted(u)) && r.id.startsWith(a[1].toLowerCase(Locale.ROOT))) out.add(r.id);
        if (a.length == 3 && sub.matches("add|addmember|remove|removemember|addowner|removeowner"))
            for (Player p : Bukkit.getOnlinePlayers()) if (p.getName().toLowerCase(Locale.ROOT).startsWith(a[2].toLowerCase(Locale.ROOT))) out.add(p.getName());
        if (a.length == 3 && sub.equals("flag")) for (String f : FLAGS) if (f.startsWith(a[2])) out.add(f);
        if (a.length == 4 && sub.equals("flag")) { out.add("on"); out.add("off"); }
        return out;
    }
}
