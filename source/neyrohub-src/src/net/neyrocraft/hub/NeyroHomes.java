package net.neyrocraft.hub;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.Player;

import java.io.File;
import java.io.IOException;
import java.util.HashMap;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;

/** Named player homes in the survival worlds. City addresses require a purchased residence. */
final class NeyroHomes {
    private final NeyroHub plugin;
    private final File dir;
    private final Map<UUID, Map<String, Location>> homes = new HashMap<>();

    NeyroHomes(NeyroHub plugin) {
        this.plugin = plugin;
        dir = new File(plugin.getDataFolder(), "homes");
        dir.mkdirs();
    }

    private Map<String, Location> list(UUID id) {
        return homes.computeIfAbsent(id, key -> {
            Map<String, Location> result = new HashMap<>();
            File file = new File(dir, key + ".yml");
            if (!file.exists()) return result;
            YamlConfiguration y = YamlConfiguration.loadConfiguration(file);
            if (y.getConfigurationSection("homes") != null)
                for (String name : y.getConfigurationSection("homes").getKeys(false)) {
                    Location l = y.getLocation("homes." + name);
                    if (l != null) result.put(name, l);
                }
            return result;
        });
    }

    private void save(UUID id) {
        YamlConfiguration y = new YamlConfiguration();
        list(id).forEach((name, location) -> y.set("homes." + name, location));
        try { y.save(new File(dir, id + ".yml")); }
        catch (IOException ex) { plugin.getLogger().severe("NeyroHomes could not save " + id + ": " + ex); }
    }

    boolean command(Player p, String command, String[] args) {
        if (!plugin.authed(p)) return true;
        Map<String, Location> playerHomes = list(p.getUniqueId());
        String name = args.length == 0 ? "home" : args[0].toLowerCase(Locale.ROOT);
        if (!name.matches("[a-zа-яё0-9_-]{1,16}")) {
            p.sendMessage(Component.text("Название дома: до 16 букв, цифр, _ или -", NamedTextColor.RED));
            return true;
        }
        if (command.equals("homes")) {
            p.sendMessage(Component.text("Ваши дома: " + (playerHomes.isEmpty() ? "нет" : String.join(", ", playerHomes.keySet())), NamedTextColor.AQUA));
            return true;
        }
        if (command.equals("sethome")) {
            if (!plugin.survivalWorld(p.getWorld())) {
                p.sendMessage(Component.text("Дом можно сохранить только в выживании", NamedTextColor.RED)); return true;
            }
            NeyroHousing housing = plugin.housing();
            if (plugin.city() != null && plugin.city().safe(p.getLocation())
                    && (housing == null || !housing.ownsInterior(p, p.getLocation()))) {
                p.sendMessage(Component.text("В Neyro-City /sethome доступен только в купленном доме", NamedTextColor.RED)); return true;
            }
            int max = housing != null && housing.resident(p) ? 3 : 1;
            if (!playerHomes.containsKey(name) && playerHomes.size() >= max) {
                p.sendMessage(Component.text("Лимит домов: " + max + ". VIP получает 3 дома.", NamedTextColor.RED)); return true;
            }
            playerHomes.put(name, p.getLocation().clone());
            save(p.getUniqueId());
            p.sendMessage(Component.text("Дом «" + name + "» сохранён. Вернуться: /home " + name, NamedTextColor.GREEN));
            return true;
        }
        if (command.equals("delhome")) {
            if (playerHomes.remove(name) == null) p.sendMessage(Component.text("Такого дома нет", NamedTextColor.RED));
            else { save(p.getUniqueId()); p.sendMessage(Component.text("Дом «" + name + "» удалён", NamedTextColor.GREEN)); }
            return true;
        }
        if (!plugin.survivalWorld(p.getWorld())) {
            p.sendMessage(Component.text("/home работает из миров выживания", NamedTextColor.RED)); return true;
        }
        Location target = playerHomes.get(name);
        if (target == null || target.getWorld() == null) {
            p.sendMessage(Component.text("Дома «" + name + "» нет. Список: /homes", NamedTextColor.RED)); return true;
        }
        NeyroHousing housing = plugin.housing();
        if (plugin.city() != null && plugin.city().safe(target)
                && (housing == null || !housing.ownsInterior(p, target))) {
            p.sendMessage(Component.text("Доступ к этому городскому дому закрыт", NamedTextColor.RED)); return true;
        }
        p.teleportAsync(target.clone()).thenAccept(done -> Bukkit.getScheduler().runTask(plugin, () -> {
            if (done && p.isOnline()) p.sendMessage(Component.text("Добро пожаловать домой: " + name, NamedTextColor.GREEN));
        }));
        return true;
    }
}
