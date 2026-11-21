package net.neyrocraft.hub;

import io.papermc.paper.event.player.AsyncChatEvent;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.event.ClickEvent;
import net.kyori.adventure.text.format.NamedTextColor;
import net.kyori.adventure.text.format.TextDecoration;
import net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerCommandPreprocessEvent;
import org.bukkit.event.player.PlayerQuitEvent;

import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

final class NeyroChat implements Listener {
    private enum Channel { LOCAL, GLOBAL, GROUP, GUILD }
    private final NeyroHub hub;
    private final Map<UUID, Channel> channels = new HashMap<>();
    private final Map<UUID, UUID> groups = new HashMap<>();
    private final Map<UUID, UUID> invites = new HashMap<>();
    private final Map<UUID, UUID> guildInvites = new HashMap<>();

    NeyroChat(NeyroHub hub) { this.hub = hub; }

    private String guild(Player p) {
        return p.getPersistentDataContainer().get(new org.bukkit.NamespacedKey(hub, "chat_guild"),
                org.bukkit.persistence.PersistentDataType.STRING);
    }

    private void guild(Player p, String id) {
        var key = new org.bukkit.NamespacedKey(hub, "chat_guild");
        if (id == null) p.getPersistentDataContainer().remove(key);
        else p.getPersistentDataContainer().set(key, org.bukkit.persistence.PersistentDataType.STRING, id);
    }

    private static Component tab(String label, String command) {
        return Component.text("[" + label + "] ", NamedTextColor.AQUA).clickEvent(ClickEvent.runCommand(command));
    }

    private static NamedTextColor accent(Player p) {
        return p.getWorld().getName().equals(DarkAnarchy.WORLD_NAME) ? NamedTextColor.RED : NamedTextColor.AQUA;
    }

    private void menu(Player p) {
        Channel active = channels.getOrDefault(p.getUniqueId(), Channel.LOCAL);
        NamedTextColor color = accent(p);
        Component row = Component.text("╭─ ", color);
        row = row.append(channelTab("Чат", "local", color, active == Channel.LOCAL))
                .append(channelTab("Гильдия", "guild", color, active == Channel.GUILD))
                .append(channelTab("Группа", "group", color, active == Channel.GROUP))
                .append(channelTab("Глобальный", "global", color, active == Channel.GLOBAL))
                .append(channelTab("Шёпот", "whisper", color, false))
                .append(channelTab("☺", "emoji", color, false));
        p.sendMessage(row);
    }

    private static Component channelTab(String name, String mode, NamedTextColor color, boolean active) {
        Component button = tab(name, "/neyrochat " + mode).color(active ? NamedTextColor.WHITE : color);
        return active ? button.decorate(TextDecoration.BOLD) : button;
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onChat(AsyncChatEvent event) {
        Player p = event.getPlayer();
        String message = PlainTextComponentSerializer.plainText().serialize(event.message()).strip();
        var viewers = java.util.Set.copyOf(event.viewers());
        var renderer = event.renderer();
        var original = event.message();
        event.setCancelled(true);
        Bukkit.getScheduler().runTask(hub, () -> {
            if (!p.isOnline() || !hub.authed(p)) return;
            boolean shout = message.startsWith("!");
            String body = shout ? message.substring(1).strip() : message;
            if (body.isEmpty()) return;
            UUID id = p.getUniqueId();
            Channel channel = shout ? Channel.GLOBAL : channels.getOrDefault(id, Channel.LOCAL);
            UUID group = groups.get(id);
            String guild = guild(p);
            if (channel == Channel.GUILD && guild == null) {
                p.sendMessage("Пригласи игрока командой /neyrochat guildinvite ник");
                return;
            }
            if (channel == Channel.GROUP && group == null) {
                p.sendMessage("Пригласи игрока командой /neyrochat invite ник");
                return;
            }
            Component header = switch (channel) {
                case LOCAL -> tab("М", "/neyrochat");
                case GLOBAL -> tab("Г", "/neyrochat").color(NamedTextColor.GOLD);
                case GROUP -> tab("К", "/neyrochat").color(NamedTextColor.LIGHT_PURPLE);
                case GUILD -> tab("Гильдия", "/neyrochat");
            };
            for (var viewer : viewers) {
                if (viewer instanceof Player other) {
                    if (!other.isOnline() || !hub.authed(other)) continue;
                    if (channel == Channel.LOCAL && !p.getWorld().equals(other.getWorld())) continue;
                    if (channel == Channel.GROUP && !group.equals(groups.get(other.getUniqueId()))) continue;
                    if (channel == Channel.GUILD && !guild.equals(guild(other))) continue;
                } else if (channel == Channel.GROUP || channel == Channel.GUILD) continue;
                var color = viewer instanceof Player other ? accent(other) : NamedTextColor.AQUA;
                viewer.sendMessage(Component.text("▏", color).append(header.color(color)).append(renderer.render(p, p.displayName(),
                        shout ? Component.text(body) : original, viewer)));
            }
        });
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onCommand(PlayerCommandPreprocessEvent event) {
        String[] args = event.getMessage().split(" +", 4);
        if (!args[0].equalsIgnoreCase("/neyrochat")) return;
        event.setCancelled(true);
        handle(event.getPlayer(), args);
    }

    private void handle(Player p, String[] args) {
        if (!hub.authed(p)) return;
        UUID id = p.getUniqueId();
        String mode = args.length > 1 ? args[1].toLowerCase(java.util.Locale.ROOT) : "";
        switch (mode) {
            case "local", "global", "group", "guild" -> {
                channels.put(id, Channel.valueOf(mode.toUpperCase(java.util.Locale.ROOT)));
                if (mode.equals("group")) p.sendMessage("Группа · /neyrochat invite ник · /neyrochat leave");
                else if (mode.equals("guild")) p.sendMessage("Гильдия · /neyrochat guildinvite ник · /neyrochat guildleave");
                else p.sendMessage(mode.equals("local") ? "Местный чат · текущий мир" : "Глобальный чат · весь сервер");
            }
            case "guildinvite" -> {
                Player target = args.length == 3 ? Bukkit.getPlayerExact(args[2]) : null;
                if (target == null || target.equals(p) || !hub.authed(target) || guild(target) != null) {
                    p.sendMessage("Укажи игрока онлайн, который ещё не состоит в гильдии.");
                    return;
                }
                if (guild(p) == null) guild(p, UUID.randomUUID().toString());
                guildInvites.put(target.getUniqueId(), id);
                target.sendMessage(Component.text(p.getName() + " приглашает в гильдию · ")
                        .append(tab("Принять", "/neyrochat guildaccept " + p.getName())));
                p.sendMessage("Приглашение отправлено.");
            }
            case "guildaccept" -> {
                Player inviter = args.length == 3 ? Bukkit.getPlayerExact(args[2]) : null;
                if (inviter == null || !inviter.getUniqueId().equals(guildInvites.get(id))
                        || guild(inviter) == null || guild(p) != null) {
                    p.sendMessage("Приглашение в гильдию больше не действует.");
                    return;
                }
                guildInvites.remove(id);
                guild(p, guild(inviter));
                channels.put(id, Channel.GUILD);
            }
            case "guildleave" -> {
                guild(p, null);
                channels.put(id, Channel.LOCAL);
                p.sendMessage("Ты вышел из гильдии.");
            }
            case "whisper" -> p.sendMessage(Component.text("✦ Написать шёпотом", NamedTextColor.LIGHT_PURPLE)
                    .clickEvent(ClickEvent.suggestCommand("/msg ")));
            case "emoji" -> {
                Component palette = Component.text("Смайлики   ", NamedTextColor.GOLD);
                for (String emoji : new String[]{"☺", "♥", "✦", "☠", "⚡", "★", "ツ"}) {
                    palette = palette.append(Component.text(emoji + "   ", NamedTextColor.AQUA)
                            .clickEvent(ClickEvent.suggestCommand(emoji)));
                }
                p.sendMessage(palette);
            }
            case "invite" -> {
                Player target = args.length == 3 ? Bukkit.getPlayerExact(args[2]) : null;
                if (target == null || target.equals(p) || !hub.authed(target)) {
                    p.sendMessage("Укажи ник другого игрока онлайн.");
                    return;
                }
                groups.putIfAbsent(id, UUID.randomUUID());
                invites.put(target.getUniqueId(), id);
                p.sendMessage("Приглашение отправлено игроку " + target.getName());
                target.sendMessage(Component.text(p.getName() + " приглашает в группу · ")
                        .append(tab("Принять", "/neyrochat accept " + p.getName())));
            }
            case "accept" -> {
                Player inviter = args.length == 3 ? Bukkit.getPlayerExact(args[2]) : null;
                if (inviter == null || !inviter.getUniqueId().equals(invites.get(id))
                        || !groups.containsKey(inviter.getUniqueId())) {
                    p.sendMessage("Приглашение больше не действует.");
                    return;
                }
                invites.remove(id);
                groups.put(id, groups.get(inviter.getUniqueId()));
                channels.put(id, Channel.GROUP);
                p.sendMessage("Ты в группе игрока " + inviter.getName());
            }
            case "leave" -> {
                groups.remove(id);
                channels.put(id, Channel.LOCAL);
                p.sendMessage("Ты вышел из группы.");
            }
            default -> menu(p);
        }
    }

    @EventHandler public void onQuit(PlayerQuitEvent event) {
        UUID id = event.getPlayer().getUniqueId();
        channels.remove(id);
        groups.remove(id);
        invites.remove(id);
        invites.values().removeIf(id::equals);
        guildInvites.remove(id);
        guildInvites.values().removeIf(id::equals);
    }
}
