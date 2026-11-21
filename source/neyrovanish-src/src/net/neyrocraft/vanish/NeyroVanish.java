package net.neyrocraft.vanish;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import org.bukkit.Bukkit;
import org.bukkit.command.Command;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.plugin.java.JavaPlugin;

import java.util.HashSet;
import java.util.Set;
import java.util.UUID;

public final class NeyroVanish extends JavaPlugin implements Listener {
    private final Set<UUID> vanished = new HashSet<>();

    @Override public void onEnable() {
        getServer().getPluginManager().registerEvents(this, this);
    }

    @Override public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        if (!(sender instanceof Player player)) {
            sender.sendMessage("/vanish доступна в игре.");
            return true;
        }
        if (args.length != 0) return false;
        UUID id = player.getUniqueId();
        boolean hidden = vanished.add(id);
        if (!hidden) vanished.remove(id);
        for (Player viewer : Bukkit.getOnlinePlayers()) update(viewer, player, hidden);
        player.sendMessage(Component.text(hidden ? "Vanish включён. Обычные игроки тебя не видят."
                : "Vanish выключен.", NamedTextColor.AQUA));
        return true;
    }

    private void update(Player viewer, Player target, boolean hidden) {
        if (viewer.equals(target)) return;
        if (hidden && !viewer.hasPermission("neyrovanish.see")) viewer.hidePlayer(this, target);
        else viewer.showPlayer(this, target);
    }

    @EventHandler public void onJoin(PlayerJoinEvent event) {
        Player viewer = event.getPlayer();
        for (UUID id : vanished) {
            Player target = Bukkit.getPlayer(id);
            if (target != null) update(viewer, target, true);
        }
    }

    @EventHandler(priority = EventPriority.HIGHEST)
    public void onQuit(PlayerQuitEvent event) {
        if (vanished.remove(event.getPlayer().getUniqueId())) event.quitMessage(null);
    }

    @Override public void onDisable() {
        for (UUID id : vanished) {
            Player target = Bukkit.getPlayer(id);
            if (target != null) for (Player viewer : Bukkit.getOnlinePlayers()) update(viewer, target, false);
        }
        vanished.clear();
    }
}
