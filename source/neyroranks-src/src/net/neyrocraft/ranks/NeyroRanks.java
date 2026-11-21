package net.neyrocraft.ranks;

import io.papermc.paper.event.player.AsyncChatEvent;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import net.kyori.adventure.text.format.TextColor;
import net.kyori.adventure.text.format.TextDecoration;
import net.kyori.adventure.text.serializer.gson.GsonComponentSerializer;
import org.bukkit.Bukkit;
import org.bukkit.NamespacedKey;
import org.bukkit.command.Command;
import org.bukkit.command.CommandSender;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.GameMode;
import org.bukkit.BanList;
import java.util.Date;
import java.util.UUID;
import org.bukkit.persistence.PersistentDataType;
import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.scoreboard.Scoreboard;
import org.bukkit.scoreboard.Team;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * NeyroRanks — unique prefixes for NeyroCraft: in chat, above the head and in the tab list
 * (NeyroTab reads the prefix this plugin stores on the player).
 */
public final class NeyroRanks extends JavaPlugin implements Listener {

    record Rank(String id, String name, String icon, TextColor color, int order) {
        Component prefix() {
            Component c = Component.text()
                    .append(Component.text("⟦", NamedTextColor.DARK_GRAY))
                    .append(Component.text(icon.isEmpty() ? name : icon + " " + name, color).decorate(TextDecoration.BOLD))
                    .append(Component.text("⟧", NamedTextColor.DARK_GRAY))
                    .build();
            return c;
        }
    }

    private final Map<String, Rank> ranks = new LinkedHashMap<>();
    private NamespacedKey prefixKey;
    private String defaultRank;

    @Override
    public void onEnable() {
        saveDefaultConfig();
        prefixKey = new NamespacedKey("neyroranks", "prefix");
        load();
        getServer().getPluginManager().registerEvents(this, this);
        for (Player p : Bukkit.getOnlinePlayers()) apply(p);
        getLogger().info("NeyroRanks: " + ranks.size() + " ranks");
    }

    private void load() {
        reloadConfig();
        ranks.clear();
        ConfigurationSection sec = getConfig().getConfigurationSection("ranks");
        int order = 0;
        if (sec != null) for (String id : sec.getKeys(false)) {
            ConfigurationSection r = sec.getConfigurationSection(id);
            if (r == null) continue;
            TextColor color = TextColor.fromHexString(r.getString("color", "#AAAAAA"));
            ranks.put(id, new Rank(id, r.getString("name", id), r.getString("icon", ""), color == null ? NamedTextColor.GRAY : color, order++));
        }
        defaultRank = getConfig().getString("default", "player");
    }

    Rank rankOf(Player p) {
        String id = getConfig().getString("players." + p.getName().toLowerCase(Locale.ROOT), defaultRank);
        Rank r = ranks.get(id);
        return r != null ? r : ranks.get(defaultRank);
    }

    /** Prefix on the player (for NeyroTab), coloured nameplate team. */
    private void apply(Player p) {
        Rank r = rankOf(p);
        if (r == null) return;
        if (power(p) < 1 && p.getGameMode() != GameMode.CREATIVE && p.getGameMode() != GameMode.SPECTATOR) {
            p.setFlying(false);
            p.setAllowFlight(false);
        }
        boolean show = !r.id().equals(defaultRank) || getConfig().getBoolean("show-default-prefix", false);
        if (show) p.getPersistentDataContainer().set(prefixKey, PersistentDataType.STRING, GsonComponentSerializer.gson().serialize(r.prefix()));
        else p.getPersistentDataContainer().remove(prefixKey);
        Scoreboard main = Bukkit.getScoreboardManager().getMainScoreboard();
        String teamName = String.format("nr%02d_%s", r.order(), r.id());
        if (teamName.length() > 16) teamName = teamName.substring(0, 16);
        Team team = main.getTeam(teamName);
        if (team == null) team = main.registerNewTeam(teamName);
        team.prefix(show ? r.prefix().append(Component.text(" ")) : Component.empty());
        team.color(NamedTextColor.WHITE);
        for (Team t : main.getTeams()) if (t != team && t.getName().startsWith("nr") && t.hasEntry(p.getName())) t.removeEntry(p.getName());
        team.addEntry(p.getName());
    }

    @EventHandler(priority = EventPriority.LOWEST)
    public void onJoin(PlayerJoinEvent e) {
        apply(e.getPlayer());
    }

    private int power(Player p) {
        if (p.isOp()) return 99;
        return switch (rankOf(p).id()) {
            case "director", "admin", "moderator" -> 99;
            case "titan" -> 4;
            case "elite" -> 3;
            case "premium" -> 2;
            case "vip" -> 1;
            default -> 0;
        };
    }

    private boolean ability(CommandSender sender, String cmd, String[] args) {
        if (!(sender instanceof Player p)) { sender.sendMessage("Используйте команду в игре"); return true; }
        int level = power(p);
        int needed = switch (cmd) { case "fly" -> 1; case "heal" -> 2; case "kill" -> 3; default -> 4; };
        if (level < needed) { p.sendMessage("Эта команда доступна с ранга " + switch (needed) { case 1 -> "VIP"; case 2 -> "PREMIUM"; case 3 -> "ELITE"; default -> "TITAN"; }); return true; }
        if (cmd.equals("fly")) {
            if (p.getGameMode() == GameMode.SPECTATOR) return true;
            boolean on = !p.getAllowFlight();
            p.setAllowFlight(on);
            if (!on) p.setFlying(false);
            p.sendMessage("Режим полёта " + (on ? "включён" : "выключен"));
            return true;
        }
        if (cmd.equals("heal")) { p.setHealth(p.getMaxHealth()); p.setFoodLevel(20); p.sendMessage("Здоровье восстановлено"); return true; }
        if (args.length != 1) { p.sendMessage("Использование: /" + cmd + " <ник>"); return true; }
        Player target = Bukkit.getPlayerExact(args[0]);
        if (target == null) { p.sendMessage("Игрок должен быть в сети"); return true; }
        if (target == p || target.isOp() || power(target) >= level) { p.sendMessage("Этого игрока нельзя выбрать целью"); return true; }
        long now = System.currentTimeMillis();
        String key = "cooldowns." + cmd + "." + p.getUniqueId();
        long previous = getConfig().getLong(key, 0L);
        long cooldown = cmd.equals("kill") ? 3_600_000L : 86_400_000L;
        if (level < 99 && now - previous < cooldown) {
            p.sendMessage("Команда ещё восстанавливается: " + ((cooldown - now + previous) / 60_000 + 1) + " мин."); return true;
        }
        getConfig().set(key, now);
        saveConfig();
        if (cmd.equals("kill")) {
            target.setHealth(0);
            Bukkit.broadcastMessage("NEYRO-CORP: " + p.getName() + " применил /kill к " + target.getName());
        } else {
            Bukkit.getBanList(BanList.Type.NAME).addBan(target.getName(), "Временный бан NEYRO-CORP (1 час)", new Date(now + 3_600_000L), p.getName());
            target.kick(Component.text("Временный бан NEYRO-CORP на 1 час"));
            getLogger().warning(p.getName() + " temporarily banned " + target.getName() + " for 1 hour");
        }
        return true;
    }

    /** Chat: ⟦★ Директор⟧ Ник » сообщение (NeyroSpell later underlines mistakes inside this). */
    @EventHandler(priority = EventPriority.LOW)
    public void onChat(AsyncChatEvent e) {
        Rank r = rankOf(e.getPlayer());
        boolean show = r != null && (!r.id().equals(defaultRank) || getConfig().getBoolean("show-default-prefix", false));
        TextColor nameColor = r != null && show ? r.color() : NamedTextColor.WHITE;
        // StarPass title chosen by the player, shown after the name
        Component title = Component.empty();
        String json = e.getPlayer().getPersistentDataContainer().get(new NamespacedKey("starpass", "title"), PersistentDataType.STRING);
        if (json != null) {
            try { title = Component.text(" ").append(GsonComponentSerializer.gson().deserialize(json)); } catch (RuntimeException ignored) { }
        }
        final Component badge = title;
        e.renderer((source, displayName, message, viewer) -> Component.text()
                .append(show ? r.prefix().append(Component.text(" ")) : Component.empty())
                .append(Component.text(source.getName(), nameColor))
                .append(badge)
                .append(Component.text(" » ", NamedTextColor.DARK_GRAY))
                .append(message.colorIfAbsent(NamedTextColor.WHITE))
                .build());
    }

    private static boolean admin(CommandSender s) {
        return s.hasPermission("neyroranks.admin");
    }

    private void help(CommandSender sender, String label) {
        sender.sendMessage(Component.text("━━━ Префиксы NeyroCraft ━━━", NamedTextColor.GOLD));
        if (sender instanceof Player p) {
            Rank r = rankOf(p);
            sender.sendMessage(Component.text("Твой префикс: ", NamedTextColor.GRAY).append(r != null ? r.prefix() : Component.text("нет")));
        }
        for (Rank r : ranks.values()) sender.sendMessage(Component.text("  " + r.id() + " → ", NamedTextColor.DARK_GRAY).append(r.prefix()));
        if (!admin(sender)) return;
        sender.sendMessage(Component.text("/" + label + " set <ник> <ранг>", NamedTextColor.YELLOW).append(Component.text(" — выдать префикс", NamedTextColor.GRAY)));
        sender.sendMessage(Component.text("/" + label + " remove <ник>", NamedTextColor.YELLOW).append(Component.text(" — убрать префикс", NamedTextColor.GRAY)));
        sender.sendMessage(Component.text("/" + label + " create <id> <#цвет> <значок|-> <название>", NamedTextColor.YELLOW).append(Component.text(" — новый префикс", NamedTextColor.GRAY)));
        sender.sendMessage(Component.text("/" + label + " delete <id>", NamedTextColor.YELLOW).append(Component.text(" — удалить префикс", NamedTextColor.GRAY)));
        sender.sendMessage(Component.text("/" + label + " reload", NamedTextColor.YELLOW).append(Component.text(" — перечитать config.yml", NamedTextColor.GRAY)));
    }

    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        String ability = command.getName().toLowerCase(Locale.ROOT);
        if (List.of("fly", "heal", "kill", "ban").contains(ability)) return ability(sender, ability, args);
        if (args.length == 0 || args[0].equalsIgnoreCase("list")) {
            help(sender, label);
            return true;
        }
        if (!admin(sender)) {
            sender.sendMessage(Component.text("Менять префиксы может только администрация", NamedTextColor.RED));
            return true;
        }
        if (args[0].equalsIgnoreCase("remove") && args.length == 2) {
            getConfig().set("players." + args[1].toLowerCase(Locale.ROOT), null);
            saveConfig();
            Player t = Bukkit.getPlayerExact(args[1]);
            if (t != null) apply(t);
            sender.sendMessage(Component.text("Префикс у " + args[1] + " убран", NamedTextColor.GRAY));
            return true;
        }
        if (args[0].equalsIgnoreCase("create") && args.length >= 5) {
            String id = args[1].toLowerCase(Locale.ROOT);
            TextColor color = TextColor.fromHexString(args[2].startsWith("#") ? args[2] : "#" + args[2]);
            if (color == null) { sender.sendMessage(Component.text("Цвет в формате #RRGGBB, например #FF8C00", NamedTextColor.RED)); return true; }
            String icon = args[3].equals("-") ? "" : args[3];
            String name = String.join(" ", java.util.Arrays.copyOfRange(args, 4, args.length));
            getConfig().set("ranks." + id + ".name", name);
            getConfig().set("ranks." + id + ".icon", icon);
            getConfig().set("ranks." + id + ".color", color.asHexString().toUpperCase(Locale.ROOT));
            saveConfig();
            load();
            for (Player p : Bukkit.getOnlinePlayers()) apply(p);
            sender.sendMessage(Component.text("Создан префикс " + id + ": ", NamedTextColor.GRAY).append(ranks.get(id).prefix()));
            return true;
        }
        if (args[0].equalsIgnoreCase("delete") && args.length == 2) {
            String id = args[1].toLowerCase(Locale.ROOT);
            if (id.equals(defaultRank) || !ranks.containsKey(id)) { sender.sendMessage(Component.text("Этот префикс удалить нельзя", NamedTextColor.RED)); return true; }
            getConfig().set("ranks." + id, null);
            saveConfig();
            load();
            for (Player p : Bukkit.getOnlinePlayers()) apply(p);
            sender.sendMessage(Component.text("Префикс " + id + " удалён", NamedTextColor.GRAY));
            return true;
        }
        if (args[0].equalsIgnoreCase("set") && args.length == 3) {
            String id = args[2].toLowerCase(Locale.ROOT);
            if (!ranks.containsKey(id)) { sender.sendMessage(Component.text("Нет такого ранга: " + id, NamedTextColor.RED)); return true; }
            getConfig().set("players." + args[1].toLowerCase(Locale.ROOT), id);
            saveConfig();
            Player t = Bukkit.getPlayerExact(args[1]);
            if (t != null) apply(t);
            sender.sendMessage(Component.text(args[1] + " теперь ", NamedTextColor.GRAY).append(ranks.get(id).prefix()));
            return true;
        }
        if (args[0].equalsIgnoreCase("reload")) {
            load();
            for (Player p : Bukkit.getOnlinePlayers()) apply(p);
            sender.sendMessage(Component.text("NeyroRanks перезагружен", NamedTextColor.GREEN));
            return true;
        }
        help(sender, label);
        return true;
    }

    @Override
    public List<String> onTabComplete(CommandSender sender, Command command, String alias, String[] args) {
        List<String> out = new ArrayList<>();
        if (args.length == 1) for (String s : admin(sender) ? new String[]{"list", "set", "remove", "create", "delete", "reload"} : new String[]{"list"}) if (s.startsWith(args[0])) out.add(s);
        if (args.length == 2 && (args[0].equalsIgnoreCase("set") || args[0].equalsIgnoreCase("remove"))) for (Player p : Bukkit.getOnlinePlayers()) out.add(p.getName());
        if (args.length == 2 && args[0].equalsIgnoreCase("delete")) out.addAll(ranks.keySet());
        if (args.length == 3 && args[0].equalsIgnoreCase("set")) for (String id : ranks.keySet()) if (id.startsWith(args[2])) out.add(id);
        return out;
    }
}
