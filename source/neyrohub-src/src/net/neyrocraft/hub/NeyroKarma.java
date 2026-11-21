package net.neyrocraft.hub;

import net.kyori.adventure.key.Key;
import net.kyori.adventure.sound.Sound;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import net.kyori.adventure.text.format.TextColor;
import net.kyori.adventure.text.format.TextDecoration;
import net.kyori.adventure.title.Title;
import org.bukkit.Bukkit;
import org.bukkit.Color;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.Statistic;
import org.bukkit.World;
import org.bukkit.attribute.Attribute;
import org.bukkit.entity.Enemy;
import org.bukkit.entity.Entity;
import org.bukkit.entity.IronGolem;
import org.bukkit.entity.LivingEntity;
import org.bukkit.entity.Player;
import org.bukkit.entity.Projectile;
import org.bukkit.entity.Tameable;
import org.bukkit.entity.Villager;
import org.bukkit.entity.WanderingTrader;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.entity.EntityDamageByEntityEvent;
import org.bukkit.event.entity.EntityDeathEvent;
import org.bukkit.event.entity.PlayerDeathEvent;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.inventory.InventoryDragEvent;
import org.bukkit.event.player.PlayerSwapHandItemsEvent;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.InventoryHolder;
import org.bukkit.inventory.ItemFlag;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.LeatherArmorMeta;
import org.bukkit.inventory.meta.SkullMeta;
import org.bukkit.persistence.PersistentDataType;
import org.bukkit.scheduler.BukkitTask;

import java.time.Duration;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * The light and the dark side. Killing players (except in self-defence or a villain), villagers
 * and other people's pets pulls a player towards the dark side; killing monsters and time slowly
 * bring them back. Villains pay more in Neyro-City and the guards watch them; anyone who is not a
 * villain gets a bounty for killing one.
 * <p>
 * The character screen (Shift+F or /char) shows all of it in the style of an old sci-fi RPG:
 * the light/dark bar from blue to red, the player, their equipment and their numbers.
 */
final class NeyroKarma implements Listener {

    static final int MIN = -100, MAX = 100;
    static final int HERO = 60, LIGHT = 30, DARK = -30, VILLAIN = -60;
    static final int LIGHT_RGB = 0x46C8FF, DARK_RGB = 0xEB1E28, NEUTRAL_RGB = 0xB48CFF;

    private final NeyroHub plugin;
    private final NamespacedKey karmaKey, monstersKey, bountiesKey;
    private final Map<UUID, Map<UUID, Long>> lastHit = new HashMap<>();     // attacker -> victim -> time
    private final Map<String, Long> bountyPaid = new HashMap<>();           // killer|victim -> time
    private BukkitTask task;

    NeyroKarma(NeyroHub plugin) {
        this.plugin = plugin;
        karmaKey = new NamespacedKey(plugin, "karma");
        monstersKey = new NamespacedKey(plugin, "karma_monsters");
        bountiesKey = new NamespacedKey(plugin, "bounties");
    }

    private BukkitTask sideTask;

    void start() {
        // time heals: every 10 minutes online, a dark-sided player comes one step back
        task = Bukkit.getScheduler().runTaskTimer(plugin, () -> {
            for (Player p : Bukkit.getOnlinePlayers()) if (get(p) < 0) add(p, 1, null);
        }, 12000L, 12000L);
        sideTask = Bukkit.getScheduler().runTaskTimer(plugin, () -> {
            for (Player p : Bukkit.getOnlinePlayers()) applySide(p);
        }, 40L, 100L);
    }

    void shutdown() {
        if (task != null) task.cancel();
        if (sideTask != null) sideTask.cancel();
        for (Player p : Bukkit.getOnlinePlayers()) clearSide(p);
    }

    /**
     * The side is visible in the player's own inventory (E) and in the corner of the screen:
     * Luck / Bad Luck, renamed and redrawn by the NeyroCraft pack as "Light side" (blue) and
     * "Dark side" (red); level II for heroes and dark lords. Neutral players have neither.
     * A real luck potion the player drank is left alone (ours are endless and ambient).
     */
    void applySide(Player p) {
        int t = tier(get(p));
        if (plugin.inRetro(p)) t = 0;                       // no such thing in Minecraft 1.0
        org.bukkit.potion.PotionEffectType want = t > 0 ? org.bukkit.potion.PotionEffectType.LUCK
                : t < 0 ? org.bukkit.potion.PotionEffectType.UNLUCK : null;
        int amp = Math.abs(t) - 1;
        for (org.bukkit.potion.PotionEffectType type : new org.bukkit.potion.PotionEffectType[]{
                org.bukkit.potion.PotionEffectType.LUCK, org.bukkit.potion.PotionEffectType.UNLUCK}) {
            org.bukkit.potion.PotionEffect cur = p.getPotionEffect(type);
            boolean ours = cur != null && cur.isInfinite() && cur.isAmbient();
            if (type == want) {
                if (cur == null || (ours && cur.getAmplifier() != amp))
                    p.addPotionEffect(new org.bukkit.potion.PotionEffect(type, org.bukkit.potion.PotionEffect.INFINITE_DURATION, amp, true, false, true));
            } else if (ours) {
                p.removePotionEffect(type);
            }
        }
    }

    private static void clearSide(Player p) {
        for (org.bukkit.potion.PotionEffectType type : new org.bukkit.potion.PotionEffectType[]{
                org.bukkit.potion.PotionEffectType.LUCK, org.bukkit.potion.PotionEffectType.UNLUCK}) {
            org.bukkit.potion.PotionEffect cur = p.getPotionEffect(type);
            if (cur != null && cur.isInfinite() && cur.isAmbient()) p.removePotionEffect(type);
        }
    }

    // ================================================================== karma

    int get(Player p) {
        Integer v = p.getPersistentDataContainer().get(karmaKey, PersistentDataType.INTEGER);
        return v == null ? 0 : v;
    }

    int bounties(Player p) {
        Integer v = p.getPersistentDataContainer().get(bountiesKey, PersistentDataType.INTEGER);
        return v == null ? 0 : v;
    }

    boolean dark(Player p) { return get(p) <= DARK; }

    void add(Player p, int delta, String reason) {
        int before = get(p);
        int after = Math.max(MIN, Math.min(MAX, before + delta));
        if (after == before) return;
        p.getPersistentDataContainer().set(karmaKey, PersistentDataType.INTEGER, after);
        applySide(p);
        if (reason == null) return;
        boolean toDark = delta < 0;
        p.sendActionBar(Component.text()
                .append(Component.text(toDark ? "◄ Тьма " : "► Свет ", TextColor.color(toDark ? DARK_RGB : LIGHT_RGB)).decorate(TextDecoration.BOLD))
                .append(Component.text((delta > 0 ? "+" : "") + delta + " · " + reason + " · карма " + signed(after), NamedTextColor.GRAY))
                .build());
        sound(p, toDark ? "entity.wither.ambient" : "block.beacon.power_select", 0.35f, toDark ? 0.6f : 1.6f);
        if (tier(before) != tier(after)) {
            p.showTitle(Title.title(Component.text(className(after), TextColor.color(color(after))).decorate(TextDecoration.BOLD),
                    Component.text(sideName(after), NamedTextColor.GRAY),
                    Title.Times.times(Duration.ofMillis(300), Duration.ofMillis(2500), Duration.ofMillis(800))));
            sound(p, toDark ? "entity.elder_guardian.curse" : "ui.toast.challenge_complete", 0.5f, 1f);
        }
    }

    static int tier(int k) {
        return k >= HERO ? 2 : k >= LIGHT ? 1 : k > DARK ? 0 : k > VILLAIN ? -1 : -2;
    }

    static String sideName(int k) {
        return switch (tier(k)) {
            case 2 -> "Герой светлой стороны";
            case 1 -> "Светлая сторона";
            case 0 -> "Нейтралитет";
            case -1 -> "Тёмная сторона";
            default -> "Злодей тёмной стороны";
        };
    }

    /** A class in the spirit of the side, shown on the character screen. */
    static String className(int k) {
        return switch (tier(k)) {
            case 2 -> "Страж Света";
            case 1 -> "Хранитель";
            case 0 -> "Странник";
            case -1 -> "Отступник";
            default -> "Лорд Тьмы";
        };
    }

    static int color(int k) {
        if (k >= LIGHT) return LIGHT_RGB;
        if (k <= DARK) return DARK_RGB;
        return NEUTRAL_RGB;
    }

    /** Prices in Neyro-City: villains pay more, heroes get a small discount. */
    double priceMultiplier(Player p) {
        int k = get(p);
        if (k <= VILLAIN) return 2.0;
        if (k <= DARK) return 1.5;
        if (k >= HERO) return 0.9;
        return 1.0;
    }

    static String signed(int v) {
        return (v > 0 ? "+" : "") + v;
    }

    // ================================================================== what changes it

    private boolean counts(World w) {
        return w != null && plugin.survivalWorld(w);
    }

    private static Player attacker(Entity damager) {
        if (damager instanceof Player p) return p;
        if (damager instanceof Projectile pr && pr.getShooter() instanceof Player p) return p;
        return null;
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onHit(EntityDamageByEntityEvent e) {
        if (!(e.getEntity() instanceof Player victim)) return;
        Player a = attacker(e.getDamager());
        if (a == null || a.equals(victim) || !counts(victim.getWorld())) return;
        lastHit.computeIfAbsent(a.getUniqueId(), k -> new HashMap<>()).put(victim.getUniqueId(), System.currentTimeMillis());
    }

    private boolean attackedFirst(Player victim, Player killer) {
        Map<UUID, Long> m = lastHit.get(victim.getUniqueId());
        Long t = m == null ? null : m.get(killer.getUniqueId());
        return t != null && System.currentTimeMillis() - t < 30_000;
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onPlayerDeath(PlayerDeathEvent e) {
        Player victim = e.getEntity();
        Player killer = victim.getKiller();
        if (killer == null || killer.equals(victim) || !counts(victim.getWorld())) return;
        int vk = get(victim), kk = get(killer);
        if (vk <= DARK && kk > DARK) {
            String pair = killer.getUniqueId() + "|" + victim.getUniqueId();
            Long last = bountyPaid.get(pair);
            if (last != null && System.currentTimeMillis() - last < 30 * 60_000L) {
                killer.sendMessage(Component.text("◈ За эту голову ты уже получал награду недавно.", NamedTextColor.GRAY));
                return;
            }
            bountyPaid.put(pair, System.currentTimeMillis());
            int reward = Math.max(3, Math.min(15, 3 + (-vk) / 10));
            give(killer, new ItemStack(Material.EMERALD, reward));
            killer.getPersistentDataContainer().set(bountiesKey, PersistentDataType.INTEGER, bounties(killer) + 1);
            add(killer, 5, "награда за голову");
            add(victim, 10, "расплата за злодеяния");
            Component msg = Component.text()
                    .append(Component.text("⚔ ", TextColor.color(LIGHT_RGB)))
                    .append(Component.text(killer.getName(), TextColor.color(LIGHT_RGB)))
                    .append(Component.text(" остановил злодея ", NamedTextColor.GRAY))
                    .append(Component.text(victim.getName(), TextColor.color(DARK_RGB)))
                    .append(Component.text(" и получил награду: " + reward + " изумр.", NamedTextColor.GRAY)).build();
            for (Player p : Bukkit.getOnlinePlayers()) if (counts(p.getWorld())) p.sendMessage(msg);
            return;
        }
        if (attackedFirst(victim, killer)) {
            killer.sendActionBar(Component.text("Самооборона — карма не изменилась", NamedTextColor.GRAY));
            return;
        }
        add(killer, vk >= LIGHT ? -35 : -25, "убийство игрока");
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onKill(EntityDeathEvent e) {
        LivingEntity dead = e.getEntity();
        if (dead instanceof Player) return;
        Player k = dead.getKiller();
        if (k == null || !counts(dead.getWorld())) return;
        if (dead instanceof WanderingTrader) add(k, -6, "убийство торговца");
        else if (dead instanceof Villager) add(k, -10, "убийство жителя");
        else if (dead instanceof IronGolem) add(k, -6, "убийство стража деревни");
        else if (dead instanceof Tameable t && t.isTamed() && t.getOwnerUniqueId() != null && !t.getOwnerUniqueId().equals(k.getUniqueId()))
            add(k, -15, "убийство чужого питомца");
        else if (dead instanceof Enemy && get(k) < 50) {
            Integer n = k.getPersistentDataContainer().get(monstersKey, PersistentDataType.INTEGER);
            int c = (n == null ? 0 : n) + 1;
            if (c >= 10) { c = 0; add(k, 1, "защита от монстров"); }
            k.getPersistentDataContainer().set(monstersKey, PersistentDataType.INTEGER, c);
        }
    }

    private static void give(Player p, ItemStack s) {
        for (ItemStack left : p.getInventory().addItem(s).values()) p.getWorld().dropItemNaturally(p.getLocation(), left);
    }

    // ================================================================== character screen

    private static final class Screen implements InventoryHolder {
        final Player player;
        Inventory inv;

        Screen(Player player) { this.player = player; }

        @Override public Inventory getInventory() { return inv; }
    }

    /** Shift + F (swap hands while sneaking) opens the character screen. */
    @EventHandler(priority = EventPriority.HIGH)
    public void onSwap(PlayerSwapHandItemsEvent e) {
        Player p = e.getPlayer();
        if (!p.isSneaking() || e.isCancelled()) return;
        if (!plugin.authed(p) || plugin.inCutscene(p)) return;
        e.setCancelled(true);
        open(p);
    }

    void open(Player p) {
        Screen s = new Screen(p);
        boolean pack = plugin.voice() != null && plugin.voice().hasVoice(p);
        int k = get(p);
        Component title = pack
                ? Component.text("", NamedTextColor.WHITE).font(Key.key("neyrocraft", "gui"))
                        .append(Component.text(p.getName().toUpperCase(java.util.Locale.ROOT), TextColor.color(0x9CFFB0)).font(Key.key("minecraft", "default")))
                : Component.text("◈ ПЕРСОНАЖ · " + p.getName(), TextColor.color(color(k)));
        s.inv = Bukkit.createInventory(s, 54, title);
        fill(s.inv, p, k, pack);
        p.openInventory(s.inv);
        sound(p, "block.beacon.activate", 0.4f, 1.8f);
    }

    private void fill(Inventory inv, Player p, int k, boolean pack) {
        ItemStack blank = pack ? null : pane(Material.BLACK_STAINED_GLASS_PANE, " ");
        for (int i = 0; i < 54; i++) inv.setItem(i, blank);

        // ---- tabs
        inv.setItem(0, glint(head(p, "Персонаж", TextColor.color(0x9CFFB0), List.of("Ты на этой вкладке"))));
        inv.setItem(1, item(Material.NETHER_STAR, "StarPass", 0xFFD84A, List.of("Награды сезона", "Нажми — открыть")));
        inv.setItem(2, item(Material.BEACON, "В Neyro-City", 0x40E8FF, List.of("Безопасный город на спавне", "Нажми — /city")));
        inv.setItem(3, item(Material.END_CRYSTAL, "На корабль NEYRO-1", NeyroHub.ORANGE, List.of("Главная палуба", "Нажми — /hub")));
        inv.setItem(4, item(Material.EMERALD, "Торговля", 0x7CFF4F, List.of("Магазины — вокруг площади города",
                "Изумруды — в «Ломбарде 24/7»", "Твои цены: ×" + fmt(priceMultiplier(p)))));
        inv.setItem(5, item(Material.WRITABLE_BOOK, "Свет и Тьма: правила", 0xE0E0E0, List.of(
                "Убийство игрока: −25 (светлого: −35)",
                "Самооборона (он ударил первым): 0",
                "Убить злодея: +5 и награда изумрудами",
                "Житель −10, чужой питомец −15, голем −6",
                "Каждые 10 монстров: +1 (до +50)",
                "Каждые 10 минут в игре: тьма слабеет на 1",
                "Злодеям — цены ×1.5–2, стражи следят",
                "Нажми — правила в чат")));
        inv.setItem(8, item(Material.BARRIER, "Закрыть", 0xFF5555, List.of()));

        // ---- the light/dark bar (drawn by the pack) and the marker
        Material[] bar = {Material.LIGHT_BLUE_STAINED_GLASS_PANE, Material.BLUE_STAINED_GLASS_PANE, Material.PURPLE_STAINED_GLASS_PANE,
                Material.MAGENTA_STAINED_GLASS_PANE, Material.RED_STAINED_GLASS_PANE};
        int row = Math.max(0, Math.min(4, (int) Math.round((MAX - k) / (double) (MAX - MIN) * 4)));
        for (int r = 0; r < 5; r++) {
            if (!pack) inv.setItem(9 + r * 9, pane(bar[r], r == 0 ? "СВЕТ" : r == 4 ? "ТЬМА" : " "));
            inv.setItem(10 + r * 9, pack ? null : pane(Material.GRAY_STAINED_GLASS_PANE, " "));
        }
        Material marker = k >= LIGHT ? Material.HEART_OF_THE_SEA : k <= DARK ? Material.REDSTONE : Material.AMETHYST_SHARD;
        inv.setItem(10 + row * 9, glint(item(marker, "◄ " + sideName(k), color(k), List.of("Карма: " + signed(k) + " из ±100"))));

        // ---- the portrait: head, armour, both hands
        if (!pack) for (int r = 1; r <= 4; r++) for (int c = 2; c <= 4; c++) inv.setItem(r * 9 + c, pane(Material.LIGHT_BLUE_STAINED_GLASS_PANE, " "));
        inv.setItem(12, head(p, p.getName(), TextColor.color(color(k)), List.of(className(k) + " · " + sideName(k))));
        var eq = p.getInventory();
        inv.setItem(21, copyOr(eq.getChestplate(), "Нет нагрудника"));
        inv.setItem(30, copyOr(eq.getLeggings(), "Нет поножей"));
        inv.setItem(39, copyOr(eq.getBoots(), "Нет ботинок"));
        inv.setItem(20, copyOr(eq.getItemInMainHand(), "Пустая рука"));
        inv.setItem(22, copyOr(eq.getItemInOffHand(), "Пустая рука"));
        if (eq.getHelmet() != null && !eq.getHelmet().getType().isAir()) inv.setItem(11 + 2, eq.getHelmet().clone());

        // ---- numbers
        int level = p.getLevel();
        double hp = p.getHealth(), max = p.getAttribute(Attribute.MAX_HEALTH) == null ? 20 : p.getAttribute(Attribute.MAX_HEALTH).getValue();
        int armor = p.getAttribute(Attribute.ARMOR) == null ? 0 : (int) Math.round(p.getAttribute(Attribute.ARMOR).getValue());
        int emeralds = count(p, Material.EMERALD) + 9 * count(p, Material.EMERALD_BLOCK);
        int mobs = p.getStatistic(Statistic.MOB_KILLS), pvp = p.getStatistic(Statistic.PLAYER_KILLS), deaths = p.getStatistic(Statistic.DEATHS);
        int hours = p.getStatistic(Statistic.PLAY_ONE_MINUTE) / 20 / 3600;
        inv.setItem(14, item(k >= LIGHT ? Material.NETHER_STAR : k <= DARK ? Material.WITHER_SKELETON_SKULL : Material.COMPASS,
                "Класс: " + className(k), color(k), List.of(sideName(k))));
        inv.setItem(15, amount(item(Material.EXPERIENCE_BOTTLE, "Уровень " + level, 0x7CFF4F, List.of("Опыт: " + p.getTotalExperience())), level));
        inv.setItem(16, amount(item(Material.GOLDEN_APPLE, "Здоровье " + (int) Math.ceil(hp) + "/" + (int) max, 0xFF5555, List.of()), (int) Math.ceil(hp)));
        inv.setItem(17, amount(item(Material.SHIELD, "Защита " + armor, 0xB0C4DE, List.of("Очки брони")), armor));
        inv.setItem(23, amount(item(Material.COOKED_BEEF, "Сытость " + p.getFoodLevel() + "/20", 0xFFAA55, List.of()), p.getFoodLevel()));
        inv.setItem(24, amount(item(Material.EMERALD, "Изумруды: " + emeralds, 0x7CFF4F, List.of("В инвентаре")), emeralds));
        inv.setItem(25, item(Material.GOLD_INGOT, "Цены в городе: ×" + fmt(priceMultiplier(p)), 0xFFD84A,
                List.of(k <= DARK ? "Злодеям в Neyro-City всё дороже" : k >= HERO ? "Героям — скидка 10%" : "Обычные цены")));
        inv.setItem(26, trooperHelmet(k));
        inv.setItem(32, amount(item(Material.BONE, "Убито монстров и мобов: " + mobs, 0xE0E0E0, List.of()), mobs));
        inv.setItem(33, amount(item(Material.IRON_SWORD, "Убито игроков: " + pvp, 0xFF7070, List.of()), pvp));
        inv.setItem(34, amount(item(Material.SKELETON_SKULL, "Смертей: " + deaths, 0xB0B0B0, List.of()), deaths));
        inv.setItem(35, amount(item(Material.GOLD_NUGGET, "Наград за головы: " + bounties(p), 0xFFD84A, List.of("Злодеи, остановленные тобой")), bounties(p)));
        inv.setItem(41, amount(item(Material.CLOCK, "В игре: " + hours + " ч", 0xE0E0E0, List.of()), hours));
        inv.setItem(42, amount(item(k >= LIGHT ? Material.LIGHT_BLUE_DYE : k <= DARK ? Material.RED_DYE : Material.PURPLE_DYE,
                "Карма: " + signed(k), color(k), List.of(sideName(k))), Math.abs(k)));
        Location l = p.getLocation();
        inv.setItem(43, item(Material.FILLED_MAP, "Где ты", 0x40E8FF, List.of(worldName(l.getWorld()),
                l.getBlockX() + " " + l.getBlockY() + " " + l.getBlockZ())));
        inv.setItem(44, item(p.getRespawnLocation() != null ? Material.RED_BED : Material.WHITE_BED,
                p.getRespawnLocation() != null ? "Точка возрождения: есть" : "Возрождение: Neyro-City", 0xE0E0E0, List.of()));

        // ---- experience bar
        int filled = (int) Math.round(p.getExp() * 7);
        for (int i = 0; i < 7; i++)
            inv.setItem(47 + i, pane(i < filled ? Material.LIME_STAINED_GLASS_PANE : Material.GRAY_STAINED_GLASS_PANE,
                    "Опыт до уровня " + (level + 1) + ": " + Math.round(p.getExp() * 100) + "%"));
    }

    private static String worldName(World w) {
        String n = w.getName();
        if (n.startsWith("neyro_hub")) return "Корабль NEYRO-1";
        if (n.startsWith("bskyblock")) return "Скайблок";
        if (n.endsWith("_nether")) return "Незер";
        if (n.endsWith("_the_end")) return "Энд";
        if (n.equals("world")) return "Выживание";
        return n;
    }

    private ItemStack trooperHelmet(int k) {
        ItemStack s = new ItemStack(Material.LEATHER_HELMET);
        s.editMeta(LeatherArmorMeta.class, m -> {
            m.setColor(Color.fromRGB(0xF0, 0xF0, 0xF0));
            m.displayName(plain(k <= VILLAIN ? "Стражи: ты в розыске" : k <= DARK ? "Стражи: следят за тобой" : k >= HERO ? "Стражи: салютуют" : "Стражи: спокойны",
                    k <= DARK ? DARK_RGB : 0xE0E0E0));
            m.lore(List.of(plain(k <= DARK ? "Не нападают, но не доверяют" : "Клоны-стражи Neyro-City", 0x909090)));
            m.addItemFlags(ItemFlag.values());
        });
        return s;
    }

    @EventHandler(priority = EventPriority.HIGH)
    public void onClick(InventoryClickEvent e) {
        if (!(e.getInventory().getHolder() instanceof Screen s)) return;
        e.setCancelled(true);
        if (e.getClickedInventory() != e.getInventory()) return;
        Player p = s.player;
        switch (e.getRawSlot()) {
            case 1 -> { p.closeInventory(); if (plugin.starPass() != null) plugin.starPass().openMenu(p, 0); }
            case 2 -> { p.closeInventory(); Bukkit.getScheduler().runTask(plugin, () -> p.performCommand("city")); }
            case 3 -> { p.closeInventory(); Bukkit.getScheduler().runTask(plugin, () -> p.performCommand("hub")); }
            case 5 -> rules(p);
            case 8 -> p.closeInventory();
            default -> { return; }
        }
        sound(p, "ui.button.click", 0.4f, 1.4f);
    }

    @EventHandler
    public void onDrag(InventoryDragEvent e) {
        if (e.getInventory().getHolder() instanceof Screen) e.setCancelled(true);
    }

    private void rules(Player p) {
        p.sendMessage(Component.text("◈ Свет и Тьма", TextColor.color(LIGHT_RGB)).decorate(TextDecoration.BOLD));
        String[] lines = {"Убийство игрока тянет к тьме: −25, светлого — −35.",
                "Если он ударил тебя первым (30 с) — это самооборона, карма не меняется.",
                "Злодея (карма ≤ −30) убивать можно: +5 карма и изумруды в награду.",
                "Житель −10, торговец −6, железный голем −6, чужой питомец −15.",
                "Каждые 10 монстров: +1 (до +50). Каждые 10 минут онлайн тьма слабеет на 1.",
                "Злодеям в Neyro-City цены ×1.5 (×2 для Лорда Тьмы), стражи не доверяют.",
                "Твоя сторона видна в инвентаре (E) — синий значок Света или красный значок Тьмы.",
                "Shift+F или /char — экран персонажа со всеми цифрами."};
        for (String l : lines) p.sendMessage(Component.text("  • " + l, TextColor.color(0xFFE6C8)));
    }

    // ================================================================== items

    private static Component plain(String s, int rgb) {
        return Component.text(s, TextColor.color(rgb)).decoration(TextDecoration.ITALIC, false);
    }

    private static ItemStack item(Material m, String name, int rgb, List<String> lore) {
        ItemStack s = new ItemStack(m);
        s.editMeta(meta -> {
            meta.displayName(plain(name, rgb).decorate(TextDecoration.BOLD));
            List<Component> l = new ArrayList<>();
            for (String line : lore) l.add(plain(line, 0xA0A0A0));
            meta.lore(l);
            meta.addItemFlags(ItemFlag.values());
        });
        return s;
    }

    private static ItemStack pane(Material m, String name) {
        ItemStack s = new ItemStack(m);
        s.editMeta(meta -> {
            meta.displayName(plain(name, 0xE0E0E0));
            meta.setHideTooltip(name.isBlank());
        });
        return s;
    }

    private static ItemStack head(Player p, String name, TextColor c, List<String> lore) {
        ItemStack s = new ItemStack(Material.PLAYER_HEAD);
        s.editMeta(SkullMeta.class, m -> {
            m.setOwningPlayer(p);
            m.displayName(Component.text(name, c).decorate(TextDecoration.BOLD).decoration(TextDecoration.ITALIC, false));
            List<Component> l = new ArrayList<>();
            for (String line : lore) l.add(plain(line, 0xA0A0A0));
            m.lore(l);
        });
        return s;
    }

    private static ItemStack glint(ItemStack s) {
        s.editMeta(m -> m.setEnchantmentGlintOverride(true));
        return s;
    }

    /** Shows a number as the stack size (1..99): the screen's way of printing values. */
    private static ItemStack amount(ItemStack s, int n) {
        s.editMeta(m -> m.setMaxStackSize(99));
        s.setAmount(Math.max(1, Math.min(99, n)));
        return s;
    }

    private static ItemStack copyOr(ItemStack s, String emptyName) {
        if (s != null && !s.getType().isAir()) {
            ItemStack c = s.clone();
            c.setAmount(1);
            return c;
        }
        return null;
    }

    private static int count(Player p, Material m) {
        int n = 0;
        for (ItemStack s : p.getInventory().getContents()) if (s != null && s.getType() == m) n += s.getAmount();
        return n;
    }

    private static String fmt(double v) {
        return v == Math.floor(v) ? String.valueOf((int) v) : String.valueOf(v);
    }

    private static void sound(Player p, String key, float vol, float pitch) {
        p.playSound(Sound.sound(Key.key("minecraft", key), Sound.Source.MASTER, vol, pitch));
    }
}
