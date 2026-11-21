package net.neyrocraft.hub;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import net.kyori.adventure.text.format.TextDecoration;
import org.bukkit.Bukkit;
import org.bukkit.GameMode;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.Particle;
import org.bukkit.Sound;
import org.bukkit.Tag;
import org.bukkit.attribute.Attribute;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Arrow;
import org.bukkit.entity.LivingEntity;
import org.bukkit.entity.Player;
import org.bukkit.entity.Projectile;
import org.bukkit.entity.SpectralArrow;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.entity.EntityDamageByEntityEvent;
import org.bukkit.event.entity.EntityDamageEvent;
import org.bukkit.event.entity.EntityRegainHealthEvent;
import org.bukkit.event.entity.EntityShootBowEvent;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.inventory.InventoryDragEvent;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.InventoryHolder;
import org.bukkit.inventory.ItemStack;
import org.bukkit.persistence.PersistentDataType;

import java.util.List;

/** One saved specialization per player, with perks confined to Survival and Anarchy. */
final class NeyroClasses implements Listener {
    private static final long SWITCH_COOLDOWN_MS = 120_000;
    private static final long COMBAT_LOCK_MS = 12_000;
    private static final long HEAL_COOLDOWN_MS = 60_000;

    private enum Role {
        WARRIOR("Воин", Material.IRON_AXE, NamedTextColor.GOLD, 10,
                "Мечи и топоры наносят на 12% больше урона."),
        TROOPER("Штурмовик", Material.CROSSBOW, NamedTextColor.AQUA, 12,
                "Стрелы из лука и арбалета наносят на 15% больше урона."),
        GUARDIAN("Страж Света", Material.DIAMOND_SWORD, NamedTextColor.BLUE, 14,
                "С мечом в главной руке получаешь на 15% меньше урона от ударов."),
        CONSULAR("Консул Света", Material.AMETHYST_SHARD, NamedTextColor.LIGHT_PURPLE, 16,
                "Исцеляет до двух сердец раз в минуту.", "/classes ability — исцеление");

        final String display;
        final Material icon;
        final NamedTextColor color;
        final int slot;
        final List<String> lore;

        Role(String display, Material icon, NamedTextColor color, int slot, String... lore) {
            this.display = display;
            this.icon = icon;
            this.color = color;
            this.slot = slot;
            this.lore = List.of(lore);
        }
    }

    private final NeyroHub plugin;
    private final NamespacedKey roleKey;
    private final NamespacedKey switchKey;
    private final NamespacedKey combatKey;
    private final NamespacedKey healKey;
    private final NamespacedKey trooperShotKey;

    NeyroClasses(NeyroHub plugin) {
        this.plugin = plugin;
        roleKey = new NamespacedKey(plugin, "player_class");
        switchKey = new NamespacedKey(plugin, "class_switch_at");
        combatKey = new NamespacedKey(plugin, "class_combat_at");
        healKey = new NamespacedKey(plugin, "class_heal_at");
        trooperShotKey = new NamespacedKey(plugin, "class_trooper_arrow");
    }

    Component label(Player player) {
        Role role = role(player);
        return role == null ? Component.text("Без класса", NamedTextColor.DARK_GRAY) : text(role.display, role.color);
    }

    boolean command(CommandSender sender, String[] args) {
        if (!(sender instanceof Player player)) {
            sender.sendMessage("Меню «Классы» доступно в игре.");
            return true;
        }
        if (!plugin.authed(player)) return true;
        if (!supportedWorld(player)) {
            player.sendMessage(text("Классы доступны в мирах выживания и анархии.", NamedTextColor.GRAY));
            return true;
        }
        if (args.length == 1 && args[0].equalsIgnoreCase("ability")) useAbility(player);
        else open(player);
        return true;
    }

    private boolean supportedWorld(Player player) {
        return plugin.inSurvival(player) || player.getWorld().getName().equals(DarkAnarchy.WORLD_NAME);
    }

    private boolean active(Player player) {
        return player.getGameMode() == GameMode.SURVIVAL && plugin.authed(player) && supportedWorld(player);
    }

    private Role role(Player player) {
        String value = player.getPersistentDataContainer().get(roleKey, PersistentDataType.STRING);
        if (value == null) return null;
        try {
            return Role.valueOf(value);
        } catch (IllegalArgumentException invalid) {
            return null;
        }
    }

    private long until(Player player, NamespacedKey key) {
        return player.getPersistentDataContainer().getOrDefault(key, PersistentDataType.LONG, 0L);
    }

    private static long seconds(long millis) {
        return Math.max(0, (millis + 999) / 1000);
    }

    private void open(Player player) {
        Menu menu = new Menu(player);
        menu.inventory = Bukkit.createInventory(menu, 27, text("✦ Выбор класса", NamedTextColor.DARK_PURPLE));
        render(player, menu.inventory);
        player.openInventory(menu.inventory);
    }

    private void render(Player player, Inventory inventory) {
        ItemStack frame = item(Material.BLACK_STAINED_GLASS_PANE, Component.empty(), List.of(), false);
        for (int slot = 0; slot < inventory.getSize(); slot++) inventory.setItem(slot, frame);
        Role selected = role(player);
        inventory.setItem(4, item(Material.COMPASS, text("Твой класс: ", NamedTextColor.WHITE).append(label(player)),
                List.of(text("Эффекты работают в выживании и анархии.", NamedTextColor.GRAY),
                        text("Смена класса доступна раз в две минуты вне боя.", NamedTextColor.GRAY)), false));
        for (Role role : Role.values()) {
            var lore = new java.util.ArrayList<Component>();
            role.lore.forEach(line -> lore.add(text(line, NamedTextColor.GRAY)));
            lore.add(text(selected == role ? "✓ Твой текущий класс" : "Нажми, чтобы выбрать",
                    selected == role ? NamedTextColor.GREEN : NamedTextColor.YELLOW));
            inventory.setItem(role.slot, item(role.icon, text(role.display, role.color), lore, selected == role));
        }
        if (selected == Role.CONSULAR)
            inventory.setItem(22, item(Material.GHAST_TEAR, text("✧ Исцеление", Role.CONSULAR.color),
                    List.of(text("Нажми здесь или введи /classes ability.", NamedTextColor.GRAY)), false));
    }

    private void select(Player player, Role choice) {
        Role previous = role(player);
        if (previous == choice || !plugin.authed(player) || !supportedWorld(player)) return;
        long now = System.currentTimeMillis();
        if (until(player, combatKey) > now) {
            player.sendMessage(text("Выбор класса доступен через " + seconds(until(player, combatKey) - now) + " с после боя.",
                    NamedTextColor.RED));
            return;
        }
        long blocked = until(player, switchKey);
        if (blocked > now) {
            player.sendMessage(text("Смена класса снова доступна через " + seconds(blocked - now) + " с.",
                    NamedTextColor.RED));
            return;
        }
        var data = player.getPersistentDataContainer();
        data.set(roleKey, PersistentDataType.STRING, choice.name());
        data.set(switchKey, PersistentDataType.LONG, now + SWITCH_COOLDOWN_MS);
        player.sendMessage(text("✦ Выбран класс: ", NamedTextColor.GRAY).append(label(player)));
    }

    private void useAbility(Player player) {
        if (role(player) != Role.CONSULAR) {
            player.sendMessage(text("Эта способность доступна Консулу Света.", NamedTextColor.GRAY));
            return;
        }
        if (!active(player)) {
            player.sendMessage(text("Исцеление работает в режиме выживания.", NamedTextColor.GRAY));
            return;
        }
        long now = System.currentTimeMillis();
        long cooldown = until(player, healKey);
        if (cooldown > now) {
            player.sendMessage(text("Исцеление восстановится через " + seconds(cooldown - now) + " с.", NamedTextColor.GRAY));
            return;
        }
        var maxHealth = player.getAttribute(Attribute.MAX_HEALTH);
        if (maxHealth == null) return;
        double before = player.getHealth();
        if (before >= maxHealth.getValue()) {
            player.sendMessage(text("У тебя полное здоровье.", NamedTextColor.GRAY));
            return;
        }
        var data = player.getPersistentDataContainer();
        data.set(healKey, PersistentDataType.LONG, now + HEAL_COOLDOWN_MS);
        player.heal(Math.min(4, maxHealth.getValue() - before), EntityRegainHealthEvent.RegainReason.CUSTOM);
        if (player.getHealth() <= before) {
            data.remove(healKey);
            return;
        }
        player.getWorld().spawnParticle(Particle.END_ROD, player.getLocation().add(0, 1, 0), 22, .5, .6, .5, .025);
        player.playSound(player.getLocation(), Sound.BLOCK_AMETHYST_BLOCK_CHIME, 1f, 1.2f);
        HudBoard.notice(player, text("✧ Исцеление Консула", Role.CONSULAR.color));
    }

    private static ItemStack item(Material material, Component name, List<Component> lore, boolean selected) {
        ItemStack item = new ItemStack(material);
        item.editMeta(meta -> {
            meta.displayName(name);
            meta.lore(lore);
            if (selected) meta.setEnchantmentGlintOverride(true);
        });
        return item;
    }

    private static Component text(String content, NamedTextColor color) {
        return Component.text(content, color).decoration(TextDecoration.ITALIC, false);
    }

    private static boolean melee(EntityDamageByEntityEvent event) {
        return event.getCause() == EntityDamageEvent.DamageCause.ENTITY_ATTACK
                || event.getCause() == EntityDamageEvent.DamageCause.ENTITY_SWEEP_ATTACK;
    }

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onDamage(EntityDamageByEntityEvent event) {
        if (!(event.getEntity() instanceof LivingEntity)) return;
        double damage = event.getDamage();
        if (melee(event) && event.getDamager() instanceof LivingEntity) {
            if (event.getDamager() instanceof Player attacker && active(attacker) && role(attacker) == Role.WARRIOR) {
                Material held = attacker.getInventory().getItemInMainHand().getType();
                if (Tag.ITEMS_SWORDS.isTagged(held) || Tag.ITEMS_AXES.isTagged(held)) damage *= 1.12;
            }
            if (event.getEntity() instanceof Player defender && active(defender) && role(defender) == Role.GUARDIAN
                    && Tag.ITEMS_SWORDS.isTagged(defender.getInventory().getItemInMainHand().getType())) damage *= .85;
        }
        if (event.getDamager() instanceof org.bukkit.entity.AbstractArrow arrow
                && arrow.getPersistentDataContainer().has(trooperShotKey, PersistentDataType.BYTE)
                && arrow.getShooter() instanceof Player shooter && active(shooter) && role(shooter) == Role.TROOPER) {
            damage *= 1.15;
        }
        if (damage != event.getDamage()) event.setDamage(damage);
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onShoot(EntityShootBowEvent event) {
        if (!(event.getEntity() instanceof Player shooter) || !active(shooter) || role(shooter) != Role.TROOPER) return;
        if (event.getProjectile() instanceof Arrow || event.getProjectile() instanceof SpectralArrow)
            event.getProjectile().getPersistentDataContainer().set(trooperShotKey, PersistentDataType.BYTE, (byte) 1);
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onCombat(EntityDamageByEntityEvent event) {
        if (event.getFinalDamage() <= 0 || !(event.getEntity() instanceof LivingEntity)) return;
        var damager = event.getDamager();
        Object attacker = damager instanceof Projectile projectile ? projectile.getShooter() : damager;
        if (!(attacker instanceof LivingEntity)) return;
        long lock = System.currentTimeMillis() + COMBAT_LOCK_MS;
        if (attacker instanceof Player player && active(player))
            player.getPersistentDataContainer().set(combatKey, PersistentDataType.LONG, lock);
        if (event.getEntity() instanceof Player player && active(player))
            player.getPersistentDataContainer().set(combatKey, PersistentDataType.LONG, lock);
    }

    @EventHandler
    public void onClick(InventoryClickEvent event) {
        if (!(event.getInventory().getHolder() instanceof Menu menu)) return;
        event.setCancelled(true);
        if (!(event.getWhoClicked() instanceof Player player) || !menu.owner.equals(player.getUniqueId())
                || event.getClickedInventory() != menu.inventory || !plugin.authed(player) || !supportedWorld(player)) return;
        if (event.getSlot() == 22 && role(player) == Role.CONSULAR) {
            useAbility(player);
            return;
        }
        for (Role role : Role.values()) {
            if (role.slot != event.getSlot()) continue;
            select(player, role);
            render(player, menu.inventory);
            return;
        }
    }

    @EventHandler
    public void onDrag(InventoryDragEvent event) {
        if (event.getInventory().getHolder() instanceof Menu) event.setCancelled(true);
    }

    private static final class Menu implements InventoryHolder {
        private final java.util.UUID owner;
        private Inventory inventory;

        Menu(Player player) { owner = player.getUniqueId(); }

        @Override public Inventory getInventory() { return inventory; }
    }
}
