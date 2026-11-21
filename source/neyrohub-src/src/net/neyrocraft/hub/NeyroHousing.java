package net.neyrocraft.hub;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.World;
import org.bukkit.block.Block;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.Entity;
import org.bukkit.entity.Player;
import org.bukkit.plugin.java.JavaPlugin;

import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;

/** Four furnished residences in the first row of Neyro-City towers. */
final class NeyroHousing {
    record House(int number, CityPlan.Tower tower, long price) { }

    private final NeyroHub plugin;
    private final NeyroCity city;
    private final File file;
    private final Map<Integer, UUID> owners = new HashMap<>();
    private final Map<UUID, Integer> agents = new HashMap<>();
    private final List<House> houses = new ArrayList<>();
    private World world;
    private int cx, cz, y0;

    NeyroHousing(NeyroHub plugin, NeyroCity city) {
        this.plugin = plugin;
        this.city = city;
        this.file = new File(plugin.getDataFolder(), "housing.yml");
        YamlConfiguration y = YamlConfiguration.loadConfiguration(file);
        for (int number = 1; number <= 4; number++) {
            String raw = y.getString("owners." + number, "");
            try { if (!raw.isBlank()) owners.put(number, UUID.fromString(raw)); }
            catch (IllegalArgumentException ex) { plugin.getLogger().warning("Invalid house owner for " + number); }
        }
    }

    void prepare(World world, int cx, int cz, int y0, CityPlan plan) {
        this.world = world;
        this.cx = cx;
        this.cz = cz;
        this.y0 = y0;
        houses.clear();
        long[] prices = {35000, 45000, 60000, 80000};
        for (int i = 0; i < 4; i++) houses.add(new House(i + 1, plan.towers().get(i * 6), prices[i]));
    }

    List<House> houses() { return houses; }

    void clearAgents() { agents.clear(); }

    void registerAgent(Entity entity, House house) { if (entity != null) agents.put(entity.getUniqueId(), house.number()); }

    boolean agentClick(Player p, Entity entity) {
        Integer number = agents.get(entity.getUniqueId());
        if (number == null) return false;
        House house = houses.get(number - 1);
        Dialogue dialogue = plugin.dialogue();
        if (dialogue == null) { show(p, house); return true; }
        dialogue.start(p, new Dialogue.Speaker("АГЕНТ NEYRO-CORP", "Недвижимость · VIP", 0xFFB000, 0xD7101830),
                entity.getLocation().add(0, 1.5, 0), s -> menu(s, house));
        return true;
    }

    private void menu(Dialogue.Session s, House house) {
        s.say("Резиденция №" + house.number() + " ждёт владельца. Цена $" + house.price()
                + ". Жить в Neyro-City могут VIP и выше.", () -> s.choose(
                Dialogue.Choice.of("Узнать цену и владельца", Dialogue.Kind.ASK, x -> { show(x.player, house); x.sayAndEnd("Все сделки фиксируются NEYRO-CORP. Баланс: /money."); }),
                Dialogue.Choice.of("Купить резиденцию", Dialogue.Kind.ACTION, x -> { buy(x.player, house); x.end(true); }),
                Dialogue.Choice.of("Показать вход", Dialogue.Kind.NEUTRAL, x -> { x.player.sendMessage(Component.text("Вход: " + outside(house).getBlockX() + " " + outside(house).getBlockZ(), NamedTextColor.AQUA)); x.end(true); }),
                Dialogue.Choice.of("Уйти", Dialogue.Kind.LEAVE, x -> x.end(true))));
    }

    boolean resident(Player p) {
        if (p.isOp() || p.hasPermission("neyrocity.resident")) return true;
        if (!(Bukkit.getPluginManager().getPlugin("NeyroRanks") instanceof JavaPlugin ranks)) return false;
        String rank = ranks.getConfig().getString("players." + p.getName().toLowerCase(Locale.ROOT),
                ranks.getConfig().getString("default", "player"));
        return rank.equalsIgnoreCase("vip") || ranks.getConfig().getBoolean("ranks." + rank + ".city-resident", false);
    }

    boolean ownsInterior(Player p, Location at) {
        if (p.isOp()) return true;
        if (!resident(p)) return false;
        for (House house : houses) if (p.getUniqueId().equals(owners.get(house.number())) && interior(house, at, false)) return true;
        return false;
    }

    boolean canEdit(Player p, Location at) {
        if (!ownsInterior(p, at)) return false;
        for (House house : houses) if (p.getUniqueId().equals(owners.get(house.number())) && interior(house, at, true)) return true;
        return false;
    }

    private boolean interior(House house, Location at, boolean edit) {
        if (at.getWorld() == null || !at.getWorld().equals(world)) return false;
        CityPlan.Tower t = house.tower();
        int x = at.getBlockX() - cx, z = at.getBlockZ() - cz, y = at.getBlockY() - y0;
        int margin = edit ? 2 : 1;
        return x >= t.x0() + margin && x <= t.x1() - margin && z >= t.z0() + margin && z <= t.z1() - margin
                && y >= 1 && y <= (edit ? 3 : 4);
    }

    Location outside(House house) {
        CityPlan.Tower t = house.tower();
        int doorZ = t.cz() > 0 ? t.z0() : t.z1();
        int away = t.cz() > 0 ? -1 : 1;
        return new Location(world, cx + t.cx() + 0.5, y0 + 1, cz + doorZ + away + 0.5,
                t.cz() > 0 ? 0f : 180f, 0f);
    }

    void ensureInteriors() {
        for (House house : houses) {
            CityPlan.Tower t = house.tower();
            int doorZ = t.cz() > 0 ? t.z0() : t.z1();
            Block center = world.getBlockAt(cx + t.cx(), y0 + 1, cz + doorZ);
            if (!center.getType().isAir()) { // A city rebuild restored the original sealed tower.
                for (int x = t.x0() + 1; x <= t.x1() - 1; x++)
                    for (int z = t.z0() + 1; z <= t.z1() - 1; z++)
                        for (int y = 1; y <= 3; y++) world.getBlockAt(cx + x, y0 + y, cz + z).setType(Material.AIR, false);
                for (int x = t.cx() - 1; x <= t.cx() + 1; x++)
                    for (int y = 1; y <= 3; y++) world.getBlockAt(cx + x, y0 + y, cz + doorZ).setType(Material.AIR, false);
            }
            if (owners.containsKey(house.number())) continue; // Never overwrite a buyer's furniture.
            Block foot = world.getBlockAt(cx + t.x0() + 2, y0 + 1, cz + t.z0() + 2);
            Block head = world.getBlockAt(cx + t.x0() + 3, y0 + 1, cz + t.z0() + 2);
            if (foot.getType() != Material.WHITE_BED || head.getType() != Material.WHITE_BED) {
                foot.setBlockData(Bukkit.createBlockData("white_bed[facing=east,part=foot]"), false);
                head.setBlockData(Bukkit.createBlockData("white_bed[facing=east,part=head]"), false);
            }
            Block lamp = world.getBlockAt(cx + t.cx(), y0 + 4, cz + t.cz());
            if (lamp.getType().isAir()) lamp.setType(Material.SEA_LANTERN, false);
            Block barrel = world.getBlockAt(cx + t.x1() - 2, y0 + 1, cz + t.z1() - 2);
            if (barrel.getType().isAir()) barrel.setType(Material.BARREL, false);
            Block table = world.getBlockAt(cx + t.x1() - 3, y0 + 1, cz + t.z1() - 2);
            if (table.getType().isAir()) table.setType(Material.CRAFTING_TABLE, false);
        }
        plugin.getLogger().info("Neyro-City residences ready: " + houses.size());
    }

    private void show(Player p, House house) {
        UUID owner = owners.get(house.number());
        p.sendMessage(Component.text("Резиденция №" + house.number() + " · $" + house.price() + " · "
                + (owner == null ? "свободна" : "продана") + " · вход " + outside(house).getBlockX() + ", "
                + outside(house).getBlockZ(), NamedTextColor.AQUA));
    }

    private void buy(Player p, House house) {
        if (!plugin.authed(p) || !resident(p)) { p.sendMessage(Component.text("Покупка жилья в городе доступна с ранга VIP", NamedTextColor.RED)); return; }
        if (owners.containsKey(house.number())) { p.sendMessage(Component.text("Этот дом уже продан", NamedTextColor.RED)); return; }
        if (owners.containsValue(p.getUniqueId())) { p.sendMessage(Component.text("У вас уже есть резиденция в городе", NamedTextColor.RED)); return; }
        NeyroMoney money = plugin.money();
        if (money == null || money.balance(p) < house.price()) {
            p.sendMessage(Component.text("Нужно $" + house.price() + " NEYRO$. Сейчас: $" + (money == null ? 0 : money.balance(p)), NamedTextColor.RED)); return;
        }
        if (!money.withdraw(p, house.price())) { p.sendMessage(Component.text("Оплата не прошла", NamedTextColor.RED)); return; }
        owners.put(house.number(), p.getUniqueId());
        if (!save()) {
            owners.remove(house.number());
            money.deposit(p, house.price(), "возврат за несостоявшуюся покупку");
            p.sendMessage(Component.text("Сделка не сохранилась; деньги возвращены", NamedTextColor.RED));
            return;
        }
        p.sendMessage(Component.text("Поздравляем! Резиденция №" + house.number() + " ваша. Внутри можно поставить /sethome city.", NamedTextColor.GREEN));
    }

    private boolean save() {
        YamlConfiguration y = new YamlConfiguration();
        owners.forEach((number, id) -> y.set("owners." + number, id.toString()));
        try { y.save(file); return true; }
        catch (IOException ex) { plugin.getLogger().severe("NeyroHousing could not save owners: " + ex); return false; }
    }

    boolean command(Player p, String[] args) {
        if (args.length == 0 || args[0].equalsIgnoreCase("list")) {
            p.sendMessage(Component.text("NEYRO-CORP · Резиденции Neyro-City", NamedTextColor.GOLD));
            for (House house : houses) show(p, house);
            return true;
        }
        if (args.length < 2) { p.sendMessage(Component.text("/house list | visit <1-4> | buy <1-4>", NamedTextColor.YELLOW)); return true; }
        int number;
        try { number = Integer.parseInt(args[1]); } catch (NumberFormatException ex) { number = 0; }
        if (number < 1 || number > houses.size()) { p.sendMessage(Component.text("Номер дома: 1–4", NamedTextColor.RED)); return true; }
        House house = houses.get(number - 1);
        if (args[0].equalsIgnoreCase("visit")) {
            if (!plugin.authed(p) || !plugin.survivalWorld(p.getWorld())) return true;
            p.teleport(outside(house));
            show(p, house);
        } else if (args[0].equalsIgnoreCase("buy")) buy(p, house);
        else p.sendMessage(Component.text("/house list | visit <1-4> | buy <1-4>", NamedTextColor.YELLOW));
        return true;
    }
}
