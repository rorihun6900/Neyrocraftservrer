package net.neyrocraft.hub;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import net.kyori.adventure.text.format.TextDecoration;
import org.bukkit.Bukkit;
import org.bukkit.GameMode;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.inventory.InventoryDragEvent;
import org.bukkit.event.player.PlayerChangedWorldEvent;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.InventoryHolder;
import org.bukkit.inventory.ItemStack;
import org.bukkit.persistence.PersistentDataType;

import java.util.List;
import java.util.UUID;

/** The anarchy reward track draws on the existing persisted mode statistics. */
final class EvilPass implements Listener {
    private static final int XP_PER_LEVEL = 100;
    private static final int[] REWARD_SLOTS = {10, 11, 12, 13, 14, 19, 20, 21, 22, 23};
    private static final List<Reward> REWARDS = List.of(
            new Reward(Material.IRON_INGOT, 12, "Железо"),
            new Reward(Material.ENDER_PEARL, 4, "Жемчуг Эндера"),
            new Reward(Material.GOLDEN_APPLE, 2, "Золотое яблоко"),
            new Reward(Material.OBSIDIAN, 16, "Обсидиан"),
            new Reward(Material.EXPERIENCE_BOTTLE, 16, "Пузырёк опыта"),
            new Reward(Material.DIAMOND, 3, "Алмаз"),
            new Reward(Material.GLOWSTONE_DUST, 16, "Светокаменная пыль"),
            new Reward(Material.GOLDEN_APPLE, 4, "Золотое яблоко"),
            new Reward(Material.DIAMOND, 6, "Алмаз"),
            new Reward(Material.NETHERITE_SCRAP, 1, "Незеритовый скрап")
    );

    private record Reward(Material material, int amount, String name) {}

    private final NeyroHub plugin;
    private final NamespacedKey claimsKey;

    EvilPass(NeyroHub plugin) {
        this.plugin = plugin;
        claimsKey = new NamespacedKey(plugin, "evil_pass_claims");
    }

    long experience(Player player) {
        HudBoard hud = plugin.hud();
        return hud.get(player, "anarchy.pvp") * 25 + hud.get(player, "anarchy.mobs") * 2
                + hud.get(player, "time.anarchy") / 60;
    }

    long level(Player player) {
        return 1 + experience(player) / XP_PER_LEVEL;
    }

    long untilNextLevel(Player player) {
        return XP_PER_LEVEL - experience(player) % XP_PER_LEVEL;
    }

    private boolean inMode(Player player) {
        return plugin.authed(player) && player.getWorld().getName().equals(DarkAnarchy.WORLD_NAME);
    }

    void open(Player player) {
        if (!inMode(player)) {
            player.sendMessage(Component.text("Evil-Pass открывается в мире анархии.", NamedTextColor.RED));
            return;
        }
        Menu menu = new Menu(player);
        menu.inventory = Bukkit.createInventory(menu, 36, label("☠ Evil-Pass ☠", NamedTextColor.DARK_RED));
        render(player, menu.inventory);
        player.openInventory(menu.inventory);
    }

    private void render(Player player, Inventory menu) {
        ItemStack border = icon(Material.BLACK_STAINED_GLASS_PANE, label(" ", NamedTextColor.BLACK), List.of());
        for (int slot = 0; slot < menu.getSize(); slot++) menu.setItem(slot, border);
        long level = level(player);
        int claims = claims(player);
        menu.setItem(4, icon(Material.NETHER_STAR, label("Твой уровень: " + level, NamedTextColor.RED), List.of(
                label("Опыт: " + experience(player) + " XP", NamedTextColor.WHITE),
                label("До следующего уровня: " + untilNextLevel(player) + " XP", NamedTextColor.GRAY),
                label("25 XP за PvP, 2 XP за моба, 1 XP за минуту", NamedTextColor.GRAY)
        )));
        for (int index = 0; index < REWARDS.size(); index++) {
            Reward reward = REWARDS.get(index);
            boolean claimed = (claims & (1 << index)) != 0;
            boolean unlocked = level >= index + 2;
            String status = claimed ? "Получено" : unlocked ? "Нажми, чтобы забрать" : "Ещё закрыто";
            menu.setItem(REWARD_SLOTS[index], icon(claimed ? Material.GRAY_DYE : unlocked ? reward.material() : Material.RED_STAINED_GLASS_PANE,
                    label("Уровень " + (index + 2), unlocked ? NamedTextColor.RED : NamedTextColor.DARK_RED),
                    List.of(label(reward.name() + " ×" + reward.amount(), NamedTextColor.WHITE),
                            label(status, claimed ? NamedTextColor.GRAY : unlocked ? NamedTextColor.GOLD : NamedTextColor.GRAY))));
        }
        menu.setItem(31, icon(Material.BARRIER, label("Закрыть", NamedTextColor.RED), List.of()));
    }

    private int claims(Player player) {
        return player.getPersistentDataContainer().getOrDefault(claimsKey, PersistentDataType.INTEGER, 0);
    }

    private void claim(Player player, int index) {
        int saved = claims(player);
        int mask = 1 << index;
        if (!inMode(player) || level(player) < index + 2 || (saved & mask) != 0) return;
        if (player.getGameMode() != GameMode.SURVIVAL) {
            player.sendMessage(Component.text("Награды Evil-Pass выдаются в режиме выживания.", NamedTextColor.RED));
            return;
        }
        Reward reward = REWARDS.get(index);
        ItemStack stack = new ItemStack(reward.material(), reward.amount());
        int space = 0;
        for (ItemStack item : player.getInventory().getStorageContents()) {
            if (item == null || item.getType().isAir()) space += stack.getMaxStackSize();
            else if (item.isSimilar(stack)) space += Math.max(0, item.getMaxStackSize() - item.getAmount());
        }
        if (space < reward.amount()) {
            player.sendMessage(Component.text("Освободи место в инвентаре и забери награду снова.", NamedTextColor.RED));
            return;
        }
        player.getInventory().addItem(stack);
        player.getPersistentDataContainer().set(claimsKey, PersistentDataType.INTEGER, saved | mask);
        player.saveData();
        player.sendMessage(Component.text("☠ Награда Evil-Pass получена.", NamedTextColor.RED));
    }

    @EventHandler
    public void onClick(InventoryClickEvent event) {
        if (!(event.getInventory().getHolder() instanceof Menu menu)) return;
        event.setCancelled(true);
        if (!(event.getWhoClicked() instanceof Player player) || !menu.owner.equals(player.getUniqueId())
                || event.getClickedInventory() != menu.inventory) return;
        if (event.getSlot() == 31) {
            Bukkit.getScheduler().runTask(plugin, () -> {
                if (player.getOpenInventory().getTopInventory() == menu.inventory) player.closeInventory();
            });
            return;
        }
        for (int index = 0; index < REWARD_SLOTS.length; index++) {
            if (event.getSlot() != REWARD_SLOTS[index]) continue;
            claim(player, index);
            render(player, menu.inventory);
            return;
        }
    }

    @EventHandler
    public void onDrag(InventoryDragEvent event) {
        if (event.getInventory().getHolder() instanceof Menu) event.setCancelled(true);
    }

    @EventHandler
    public void onWorldChange(PlayerChangedWorldEvent event) {
        Player player = event.getPlayer();
        if (!inMode(player) && player.getOpenInventory().getTopInventory().getHolder() instanceof Menu)
            player.closeInventory();
    }

    private static ItemStack icon(Material material, Component name, List<Component> lore) {
        ItemStack item = new ItemStack(material);
        item.editMeta(meta -> {
            meta.displayName(name);
            meta.lore(lore);
        });
        return item;
    }

    private static Component label(String text, NamedTextColor color) {
        return Component.text(text, color).decoration(TextDecoration.ITALIC, false);
    }

    private static final class Menu implements InventoryHolder {
        private final UUID owner;
        private Inventory inventory;

        Menu(Player player) { owner = player.getUniqueId(); }

        @Override public Inventory getInventory() { return inventory; }
    }
}
