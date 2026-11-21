package net.neyrocraft.hub;

import org.bukkit.Material;
import org.bukkit.World;
import org.bukkit.block.Block;
import org.bukkit.entity.ArmorStand;
import org.bukkit.entity.Entity;
import org.bukkit.entity.Hanging;
import org.bukkit.entity.ItemFrame;
import org.bukkit.entity.Player;
import org.bukkit.entity.Projectile;
import org.bukkit.entity.Vehicle;
import org.bukkit.event.Cancellable;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.block.Action;
import org.bukkit.event.block.BlockBreakEvent;
import org.bukkit.event.block.BlockBurnEvent;
import org.bukkit.event.block.BlockExplodeEvent;
import org.bukkit.event.block.BlockFadeEvent;
import org.bukkit.event.block.BlockFormEvent;
import org.bukkit.event.block.BlockFromToEvent;
import org.bukkit.event.block.BlockIgniteEvent;
import org.bukkit.event.block.BlockPlaceEvent;
import org.bukkit.event.block.BlockSpreadEvent;
import org.bukkit.event.block.LeavesDecayEvent;
import org.bukkit.event.entity.EntityChangeBlockEvent;
import org.bukkit.event.entity.EntityDamageByEntityEvent;
import org.bukkit.event.entity.EntityExplodeEvent;
import org.bukkit.event.hanging.HangingBreakEvent;
import org.bukkit.event.hanging.HangingPlaceEvent;
import org.bukkit.event.player.PlayerArmorStandManipulateEvent;
import org.bukkit.event.player.PlayerBucketEmptyEvent;
import org.bukkit.event.player.PlayerBucketFillEvent;
import org.bukkit.event.player.PlayerInteractEntityEvent;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.event.vehicle.VehicleDestroyEvent;

/**
 * The hub (main deck, Orion, the arenas) and planet Terrax cannot be changed by players:
 * no breaking or placing, no doors/trapdoors/levers/item frames/armor stands, no buckets, fire,
 * explosions, leaf decay, ice or snow forming. Operators in creative mode are builders and may edit.
 */
final class HubShield implements Listener {
    private final NeyroHub plugin;

    HubShield(NeyroHub plugin) {
        this.plugin = plugin;
    }

    private boolean guarded(World w) {
        if (w == null) return false;
        if (plugin.isHubWorld(w)) return true;
        return plugin.race() != null && plugin.race().in(w);
    }

    private boolean free(Player p) {
        return p != null && plugin.isBuilder(p);
    }

    private void stop(Player p, World w, Cancellable e) {
        if (guarded(w) && !free(p)) e.setCancelled(true);
    }

    private static Player playerOf(Entity e) {
        if (e instanceof Player p) return p;
        if (e instanceof Projectile pr && pr.getShooter() instanceof Player p) return p;
        return null;
    }

    @EventHandler(priority = EventPriority.LOW, ignoreCancelled = true)
    public void onBreak(BlockBreakEvent e) { stop(e.getPlayer(), e.getBlock().getWorld(), e); }

    @EventHandler(priority = EventPriority.LOW, ignoreCancelled = true)
    public void onPlace(BlockPlaceEvent e) { stop(e.getPlayer(), e.getBlock().getWorld(), e); }

    @EventHandler(priority = EventPriority.LOW, ignoreCancelled = true)
    public void onBucket(PlayerBucketEmptyEvent e) { stop(e.getPlayer(), e.getBlock().getWorld(), e); }

    @EventHandler(priority = EventPriority.LOW, ignoreCancelled = true)
    public void onBucketFill(PlayerBucketFillEvent e) { stop(e.getPlayer(), e.getBlock().getWorld(), e); }

    /** Doors, trapdoors, levers, buttons, chests, beds, pots… and trampling: nothing in the hub changes state. */
    @EventHandler(priority = EventPriority.LOW)
    public void onInteract(PlayerInteractEvent e) {
        Block b = e.getClickedBlock();
        if (b == null || !guarded(b.getWorld()) || free(e.getPlayer())) return;
        Material m = b.getType();
        if (e.getAction() == Action.PHYSICAL) {
            if (m == Material.FARMLAND || m == Material.TURTLE_EGG || m == Material.SNIFFER_EGG) e.setCancelled(true);
            return;
        }
        if (e.getAction() == Action.RIGHT_CLICK_BLOCK && m.isInteractable()) e.setCancelled(true);
    }

    @EventHandler(priority = EventPriority.LOW, ignoreCancelled = true)
    public void onEntityClick(PlayerInteractEntityEvent e) {
        Entity v = e.getRightClicked();
        if ((v instanceof ItemFrame || v instanceof ArmorStand) && guarded(v.getWorld()) && !free(e.getPlayer())) e.setCancelled(true);
    }

    @EventHandler(priority = EventPriority.LOW, ignoreCancelled = true)
    public void onStand(PlayerArmorStandManipulateEvent e) { stop(e.getPlayer(), e.getRightClicked().getWorld(), e); }

    @EventHandler(priority = EventPriority.LOW, ignoreCancelled = true)
    public void onHit(EntityDamageByEntityEvent e) {
        Entity v = e.getEntity();
        if (!(v instanceof Hanging || v instanceof ArmorStand || v instanceof Vehicle) || !guarded(v.getWorld())) return;
        if (!free(playerOf(e.getDamager()))) e.setCancelled(true);
    }

    @EventHandler(priority = EventPriority.LOW, ignoreCancelled = true)
    public void onHangingBreak(HangingBreakEvent e) {
        if (!guarded(e.getEntity().getWorld())) return;
        if (e instanceof org.bukkit.event.hanging.HangingBreakByEntityEvent be && free(playerOf(be.getRemover()))) return;
        e.setCancelled(true);
    }

    @EventHandler(priority = EventPriority.LOW, ignoreCancelled = true)
    public void onHangingPlace(HangingPlaceEvent e) { stop(e.getPlayer(), e.getEntity().getWorld(), e); }

    @EventHandler(priority = EventPriority.LOW, ignoreCancelled = true)
    public void onVehicleBreak(VehicleDestroyEvent e) {
        if (guarded(e.getVehicle().getWorld()) && !free(e.getAttacker() == null ? null : playerOf(e.getAttacker()))) e.setCancelled(true);
    }

    // ---- the world itself never changes

    @EventHandler(ignoreCancelled = true)
    public void onExplode(EntityExplodeEvent e) { if (guarded(e.getLocation().getWorld())) e.blockList().clear(); }

    @EventHandler(ignoreCancelled = true)
    public void onBlockExplode(BlockExplodeEvent e) { if (guarded(e.getBlock().getWorld())) e.blockList().clear(); }

    @EventHandler(ignoreCancelled = true)
    public void onBurn(BlockBurnEvent e) { if (guarded(e.getBlock().getWorld())) e.setCancelled(true); }

    @EventHandler(ignoreCancelled = true)
    public void onIgnite(BlockIgniteEvent e) {
        if (guarded(e.getBlock().getWorld()) && !free(e.getPlayer())) e.setCancelled(true);
    }

    @EventHandler(ignoreCancelled = true)
    public void onSpread(BlockSpreadEvent e) { if (guarded(e.getBlock().getWorld())) e.setCancelled(true); }

    @EventHandler(ignoreCancelled = true)
    public void onFade(BlockFadeEvent e) { if (guarded(e.getBlock().getWorld())) e.setCancelled(true); }

    @EventHandler(ignoreCancelled = true)
    public void onForm(BlockFormEvent e) { if (guarded(e.getBlock().getWorld())) e.setCancelled(true); }

    @EventHandler(ignoreCancelled = true)
    public void onLeaves(LeavesDecayEvent e) { if (guarded(e.getBlock().getWorld())) e.setCancelled(true); }

    @EventHandler(ignoreCancelled = true)
    public void onFlow(BlockFromToEvent e) {
        // the hub has no flowing liquids by design: anything that starts to flow was placed by mistake
        if (guarded(e.getBlock().getWorld()) && !e.getBlock().getType().isAir()) e.setCancelled(true);
    }

    @EventHandler(ignoreCancelled = true)
    public void onMobChange(EntityChangeBlockEvent e) {
        if (e.getEntity() instanceof Player p && free(p)) return;
        if (e.getEntity() instanceof org.bukkit.entity.FallingBlock) return;
        if (guarded(e.getBlock().getWorld())) e.setCancelled(true);
    }
}
