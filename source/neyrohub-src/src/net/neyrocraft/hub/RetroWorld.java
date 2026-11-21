package net.neyrocraft.hub;

import net.kyori.adventure.key.Key;
import net.kyori.adventure.sound.Sound;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import net.kyori.adventure.text.format.TextColor;
import net.kyori.adventure.text.format.TextDecoration;
import net.kyori.adventure.title.Title;
import org.bukkit.Bukkit;
import org.bukkit.Difficulty;
import org.bukkit.GameMode;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.World;
import org.bukkit.WorldCreator;
import org.bukkit.attribute.Attribute;
import org.bukkit.attribute.AttributeInstance;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.Ageable;
import org.bukkit.entity.EntityType;
import org.bukkit.entity.Player;
import org.bukkit.entity.Villager;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.block.BlockPlaceEvent;
import org.bukkit.event.entity.CreatureSpawnEvent;
import org.bukkit.event.entity.EntityDamageByEntityEvent;
import org.bukkit.event.entity.EntityDamageEvent;
import org.bukkit.event.entity.EntityPortalEvent;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.inventory.PrepareItemCraftEvent;
import org.bukkit.event.player.PlayerChangedWorldEvent;
import org.bukkit.event.player.PlayerInteractEntityEvent;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerPortalEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.event.player.PlayerRespawnEvent;
import org.bukkit.event.player.PlayerSwapHandItemsEvent;
import org.bukkit.event.player.PlayerTeleportEvent;
import org.bukkit.inventory.ItemStack;
import org.bukkit.persistence.PersistentDataType;
import org.bukkit.potion.PotionEffect;
import org.bukkit.potion.PotionEffectType;

import java.io.File;
import java.io.IOException;
import java.time.Duration;
import java.util.EnumSet;
import java.util.List;
import java.util.Set;

/**
 * Portal #3 — "Minecraft 1.0": a world generated like 2011, with the rules of that version:
 * only blocks/items/mobs that existed in 1.0, combat without the attack cooldown, no off-hand,
 * one kind of planks, no villager trading, no Nether/End portals out of this world.
 * Inventories are kept apart from the rest of the server.
 */
final class RetroWorld implements Listener {
    static final String WORLD = "neyro_retro";
    private static final TextColor RETRO = TextColor.color(0x55FF55);

    private final NeyroHub plugin;
    private final NamespacedKey locKey;
    private final File dir;
    private World world;

    RetroWorld(NeyroHub plugin) {
        this.plugin = plugin;
        this.locKey = new NamespacedKey(plugin, "retro_location");
        this.dir = new File(plugin.getDataFolder(), "retro");
    }

    void start() {
        dir.mkdirs();
        world = new WorldCreator(WORLD).generator(new RetroGenerator()).generateStructures(false).createWorld();
        if (world == null) { plugin.getLogger().warning("Minecraft 1.0 world could not be created"); return; }
        world.setDifficulty(Difficulty.NORMAL);
        world.setKeepSpawnInMemory(false);
        Bukkit.getScheduler().runTaskTimer(plugin, this::sweepInventories, 100L, 40L);
        plugin.getLogger().info("Minecraft 1.0 world ready (spawn " + world.getSpawnLocation().getBlockX() + ", "
                + world.getSpawnLocation().getBlockY() + ", " + world.getSpawnLocation().getBlockZ() + ")");
    }

    boolean in(World w) {
        return w != null && w.getName().equals(WORLD);
    }

    int online() {
        return world == null ? 0 : world.getPlayers().size();
    }

    // ------------------------------------------------------------ entering

    void send(Player p) {
        if (world == null) { p.sendMessage(Component.text("Мир 1.0 сейчас недоступен", NamedTextColor.RED)); return; }
        Location target = savedLocation(p);
        if (target == null) target = safeSpawn();
        p.addPotionEffect(new PotionEffect(PotionEffectType.BLINDNESS, 30, 0, false, false, false));
        p.playSound(Sound.sound(Key.key("minecraft", "entity.enderman.teleport"), Sound.Source.MASTER, 0.8f, 0.6f));
        p.showTitle(Title.title(
                Component.text("Minecraft 1.0", RETRO).decorate(TextDecoration.BOLD),
                Component.text("18 ноября 2011 · старый добрый майнкрафт", NamedTextColor.GRAY),
                Title.Times.times(Duration.ofMillis(300), Duration.ofMillis(2200), Duration.ofMillis(600))));
        Location to = target;
        Bukkit.getScheduler().runTaskLater(plugin, () -> {
            if (!p.isOnline()) return;
            p.teleportAsync(to).thenRun(() -> Bukkit.getScheduler().runTask(plugin, () -> {
                if (p.getGameMode() == GameMode.ADVENTURE) p.setGameMode(GameMode.SURVIVAL);
                p.sendMessage(Component.text()
                        .append(Component.text("⛏ Minecraft 1.0 ", RETRO).decorate(TextDecoration.BOLD))
                        .append(Component.text("— мир и правила как в 2011: только старые блоки и мобы, бой без перезарядки, "
                                + "без второй руки. Вещи здесь свои, отдельно от выживания. Вернуться: /hub", NamedTextColor.GRAY))
                        .build());
                p.sendMessage(Component.text("Совет: для старых текстур включи «Programmer Art» в Настройки → Пакеты ресурсов.",
                        NamedTextColor.DARK_GRAY));
            }));
        }, 10L);
    }

    private Location safeSpawn() {
        Location s = world.getSpawnLocation();
        int y = world.getHighestBlockYAt(s.getBlockX(), s.getBlockZ());
        return new Location(world, s.getBlockX() + 0.5, y + 1, s.getBlockZ() + 0.5);
    }

    private Location savedLocation(Player p) {
        String s = p.getPersistentDataContainer().get(locKey, PersistentDataType.STRING);
        if (s == null || world == null) return null;
        String[] a = s.split(";");
        if (a.length != 5) return null;
        return new Location(world, Double.parseDouble(a[0]), Double.parseDouble(a[1]), Double.parseDouble(a[2]),
                Float.parseFloat(a[3]), Float.parseFloat(a[4]));
    }

    private void saveLocation(Player p, Location l) {
        if (l == null || !in(l.getWorld())) return;
        p.getPersistentDataContainer().set(locKey, PersistentDataType.STRING,
                l.getX() + ";" + l.getY() + ";" + l.getZ() + ";" + l.getYaw() + ";" + l.getPitch());
    }

    // ------------------------------------------------------------ separate inventories

    @EventHandler(priority = EventPriority.LOWEST)
    public void onLeaveByTeleport(PlayerTeleportEvent e) {
        if (in(e.getFrom().getWorld()) && !in(e.getTo().getWorld())) saveLocation(e.getPlayer(), e.getFrom());
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onWorldChange(PlayerChangedWorldEvent e) {
        Player p = e.getPlayer();
        boolean from = in(e.getFrom()), to = in(p.getWorld());
        if (to && !from) {
            store(p, "main");
            load(p, "retro", true);
            oldCombat(p, true);
            hideModernRecipes(p);
            if (plugin.voice() != null) plugin.voice().sendRetro(p);
        } else if (from && !to) {
            store(p, "retro");
            load(p, "main", false);
            oldCombat(p, false);
            restoreRecipes(p);
            if (plugin.voice() != null) plugin.voice().removeRetro(p);
        }
    }

    private void store(Player p, String slot) {
        try {
            YamlConfiguration y = new YamlConfiguration();
            y.set("contents", p.getInventory().getContents());
            y.set("level", p.getLevel());
            y.set("exp", (double) p.getExp());
            y.set("health", p.getHealth());
            y.set("food", p.getFoodLevel());
            y.set("saturation", (double) p.getSaturation());
            y.save(new File(dir, p.getUniqueId() + "-" + slot + ".yml"));
        } catch (IOException ex) {
            plugin.getLogger().warning("Could not save " + slot + " inventory of " + p.getName() + ": " + ex);
        }
    }

    private void load(Player p, String slot, boolean freshIfMissing) {
        File f = new File(dir, p.getUniqueId() + "-" + slot + ".yml");
        if (!f.exists()) {
            if (!freshIfMissing) return;
            p.getInventory().clear();
            p.setLevel(0);
            p.setExp(0);
            p.setHealth(p.getAttribute(Attribute.MAX_HEALTH).getValue());
            p.setFoodLevel(20);
            p.setSaturation(5);
            return;
        }
        YamlConfiguration y = YamlConfiguration.loadConfiguration(f);
        p.getInventory().clear();
        List<?> list = y.getList("contents");
        if (list != null) {
            ItemStack[] items = new ItemStack[p.getInventory().getSize()];
            for (int i = 0; i < Math.min(items.length, list.size()); i++) if (list.get(i) instanceof ItemStack it) items[i] = it;
            p.getInventory().setContents(items);
        }
        p.setLevel(y.getInt("level"));
        p.setExp((float) y.getDouble("exp"));
        double max = p.getAttribute(Attribute.MAX_HEALTH).getValue();
        p.setHealth(Math.max(1, Math.min(max, y.getDouble("health", max))));
        p.setFoodLevel(y.getInt("food", 20));
        p.setSaturation((float) y.getDouble("saturation", 5));
    }

    /** 2011 combat: no attack cooldown. */
    private void oldCombat(Player p, boolean on) {
        AttributeInstance a = p.getAttribute(Attribute.ATTACK_SPEED);
        if (a != null) a.setBaseValue(on ? 1024 : 4.0);
    }

    @EventHandler
    public void onJoin(PlayerJoinEvent e) {
        oldCombat(e.getPlayer(), in(e.getPlayer().getWorld()));
        if (!in(e.getPlayer().getWorld())) restoreRecipes(e.getPlayer());   // crashed while inside 1.0
    }

    // ------------------------------------------------------------ recipe book: only 2011 recipes

    private static boolean modernRecipe(org.bukkit.inventory.Recipe r) {
        if (r == null) return false;
        ItemStack res = r.getResult();
        return res != null && !res.getType().isAir() && oldVersionOf(res.getType()) == null;
    }

    /** Removes every recipe of later versions from the recipe book; remembers them to give back on leaving. */
    private void hideModernRecipes(Player p) {
        List<NamespacedKey> hidden = new java.util.ArrayList<>();
        for (NamespacedKey key : p.getDiscoveredRecipes()) {
            if (modernRecipe(Bukkit.getRecipe(key))) hidden.add(key);
        }
        if (hidden.isEmpty()) return;
        try {
            YamlConfiguration y = new YamlConfiguration();
            y.set("recipes", hidden.stream().map(NamespacedKey::toString).toList());
            y.save(new File(dir, p.getUniqueId() + "-recipes.yml"));
        } catch (IOException ex) {
            plugin.getLogger().warning("Could not save hidden recipes of " + p.getName() + ": " + ex);
        }
        p.undiscoverRecipes(hidden);
    }

    private void restoreRecipes(Player p) {
        File f = new File(dir, p.getUniqueId() + "-recipes.yml");
        if (!f.exists()) return;
        List<NamespacedKey> keys = new java.util.ArrayList<>();
        for (String s : YamlConfiguration.loadConfiguration(f).getStringList("recipes")) {
            NamespacedKey k = NamespacedKey.fromString(s);
            if (k != null) keys.add(k);
        }
        p.discoverRecipes(keys);
        f.delete();
    }

    /** No new recipe pops up in the book while playing 1.0. */
    @EventHandler(ignoreCancelled = true)
    public void onRecipeDiscover(org.bukkit.event.player.PlayerRecipeDiscoverEvent e) {
        if (!in(e.getPlayer().getWorld())) return;
        if (modernRecipe(Bukkit.getRecipe(e.getRecipe()))) e.setCancelled(true);
    }

    /** Creative menu: modern items can't be taken out inside the 1.0 world. */
    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onCreativePick(org.bukkit.event.inventory.InventoryCreativeEvent e) {
        if (!(e.getWhoClicked() instanceof Player p) || !in(p.getWorld())) return;
        ItemStack it = e.getCursor();
        if (it == null || it.getType().isAir()) return;
        ItemStack old = oldVersionOf(it);
        if (old == null) {
            e.setCancelled(true);
            p.sendActionBar(Component.text("Этого предмета ещё нет в Minecraft 1.0", NamedTextColor.RED));
        } else if (old != it) {
            e.setCursor(old);
        }
    }

    @EventHandler
    public void onQuit(PlayerQuitEvent e) {
        if (in(e.getPlayer().getWorld())) saveLocation(e.getPlayer(), e.getPlayer().getLocation());
    }

    @EventHandler(priority = EventPriority.HIGH)
    public void onRespawn(PlayerRespawnEvent e) {
        if (!in(e.getPlayer().getWorld()) || in(e.getRespawnLocation().getWorld())) return;
        e.setRespawnLocation(safeSpawn());
    }

    // ------------------------------------------------------------ 1.0 rules

    @EventHandler(ignoreCancelled = true)
    public void onSpawn(CreatureSpawnEvent e) {
        if (!in(e.getLocation().getWorld())) return;
        if (!MOBS.contains(e.getEntityType())) { e.setCancelled(true); return; }
        if (e.getEntity() instanceof Ageable a && e.getEntityType() == EntityType.ZOMBIE) a.setAdult();   // no baby zombies yet
    }

    @EventHandler(ignoreCancelled = true)
    public void onCraft(PrepareItemCraftEvent e) {
        if (!(e.getView().getPlayer() instanceof Player p) || !in(p.getWorld())) return;
        ItemStack result = e.getInventory().getResult();
        if (result == null) return;
        Material m = result.getType();
        if (m == Material.SPRUCE_PLANKS || m == Material.BIRCH_PLANKS) {           // one kind of planks in 1.0
            e.getInventory().setResult(new ItemStack(Material.OAK_PLANKS, result.getAmount()));
        } else if (!ITEMS.contains(m)) {
            e.getInventory().setResult(null);
        }
    }

    @EventHandler(ignoreCancelled = true)
    public void onPlace(BlockPlaceEvent e) {
        if (!in(e.getBlock().getWorld()) || ITEMS.contains(e.getItemInHand().getType())) return;
        e.setCancelled(true);
        e.getPlayer().sendActionBar(Component.text("Этого блока ещё нет в Minecraft 1.0", NamedTextColor.RED));
    }

    @EventHandler(ignoreCancelled = true)
    public void onSweep(EntityDamageByEntityEvent e) {
        if (in(e.getEntity().getWorld()) && e.getCause() == EntityDamageEvent.DamageCause.ENTITY_SWEEP_ATTACK) e.setCancelled(true);
    }

    @EventHandler(ignoreCancelled = true)
    public void onSwapHands(PlayerSwapHandItemsEvent e) {
        if (in(e.getPlayer().getWorld())) e.setCancelled(true);
    }

    @EventHandler(ignoreCancelled = true)
    public void onOffhandSlot(InventoryClickEvent e) {
        if (e.getWhoClicked() instanceof Player p && in(p.getWorld()) && e.getSlot() == 40
                && e.getClickedInventory() == p.getInventory()) e.setCancelled(true);
    }

    @EventHandler(ignoreCancelled = true)
    public void onTrade(PlayerInteractEntityEvent e) {
        if (in(e.getPlayer().getWorld()) && e.getRightClicked() instanceof Villager) e.setCancelled(true);   // no trading until 1.3
    }

    @EventHandler(ignoreCancelled = true)
    public void onPortal(PlayerPortalEvent e) {
        if (!in(e.getFrom().getWorld())) return;
        e.setCancelled(true);
        e.getPlayer().sendActionBar(Component.text("Незер и Энд мира 1.0 пока закрыты", NamedTextColor.RED));
    }

    @EventHandler(ignoreCancelled = true)
    public void onEntityPortal(EntityPortalEvent e) {
        if (in(e.getFrom().getWorld())) e.setCancelled(true);
    }

    // ------------------------------------------------------------ no modern items can appear here

    /** Modern drops that had an old equivalent (1.17+ ores drop raw metal; in 2011 they dropped the ore). */
    private static Material oldVersionOf(Material m) {
        return switch (m) {
            case RAW_IRON -> Material.IRON_ORE;
            case RAW_GOLD -> Material.GOLD_ORE;
            case SPRUCE_PLANKS, BIRCH_PLANKS -> Material.OAK_PLANKS;
            case DEEPSLATE, COBBLED_DEEPSLATE, TUFF, ANDESITE, DIORITE, GRANITE, CALCITE -> Material.COBBLESTONE;
            default -> ITEMS.contains(m) ? m : null;
        };
    }

    private static ItemStack oldVersionOf(ItemStack it) {
        if (it == null || it.getType().isAir()) return it;
        Material m = oldVersionOf(it.getType());
        if (m == null) return null;
        if (m == it.getType()) return it;
        return new ItemStack(m, it.getAmount());
    }

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onItemSpawn(org.bukkit.event.entity.ItemSpawnEvent e) {
        if (!in(e.getLocation().getWorld())) return;
        ItemStack old = oldVersionOf(e.getEntity().getItemStack());
        if (old == null) e.setCancelled(true);
        else e.getEntity().setItemStack(old);
    }

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onBlockDrops(org.bukkit.event.block.BlockDropItemEvent e) {
        if (!in(e.getBlock().getWorld())) return;
        e.getItems().removeIf(item -> {
            ItemStack old = oldVersionOf(item.getItemStack());
            if (old == null) return true;
            item.setItemStack(old);
            return false;
        });
    }

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onMobDrops(org.bukkit.event.entity.EntityDeathEvent e) {
        if (!in(e.getEntity().getWorld())) return;
        List<ItemStack> keep = new java.util.ArrayList<>();
        for (ItemStack it : e.getDrops()) { ItemStack old = oldVersionOf(it); if (old != null) keep.add(old); }
        e.getDrops().clear();
        e.getDrops().addAll(keep);
    }

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onSmelt(org.bukkit.event.inventory.FurnaceSmeltEvent e) {
        if (!in(e.getBlock().getWorld())) return;
        ItemStack old = oldVersionOf(e.getResult());
        if (old == null) e.setCancelled(true);
        else e.setResult(old);
    }

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onFish(org.bukkit.event.player.PlayerFishEvent e) {
        if (!in(e.getPlayer().getWorld()) || !(e.getCaught() instanceof org.bukkit.entity.Item item)) return;
        if (oldVersionOf(item.getItemStack()) == null) item.setItemStack(new ItemStack(Material.COD));   // 2011: only raw fish
    }

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onPickup(org.bukkit.event.entity.EntityPickupItemEvent e) {
        if (!in(e.getEntity().getWorld()) || !(e.getEntity() instanceof Player)) return;
        ItemStack old = oldVersionOf(e.getItem().getItemStack());
        if (old == null) { e.setCancelled(true); e.getItem().remove(); }
        else if (old != e.getItem().getItemStack()) e.getItem().setItemStack(old);
    }

    /** Bone meal must not grow cornflowers, tall grass and other plants from later versions. */
    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onFertilize(org.bukkit.event.block.BlockFertilizeEvent e) {
        if (!in(e.getBlock().getWorld())) return;
        e.getBlocks().removeIf(state -> !state.getType().isAir() && !ITEMS.contains(state.getType())
                && !OLD_PLANT_BLOCKS.contains(state.getType()));
    }

    /** Only the enchantments of 2011 (no Mending, Sweeping Edge, Frost Walker…). */
    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onEnchant(org.bukkit.event.enchantment.EnchantItemEvent e) {
        if (!in(e.getEnchanter().getWorld())) return;
        e.getEnchantsToAdd().keySet().removeIf(en -> !OLD_ENCHANTS.contains(en.getKey().getKey()));
        if (e.getEnchantsToAdd().isEmpty()) e.getEnchantsToAdd().put(org.bukkit.enchantments.Enchantment.UNBREAKING, 1);
    }

    /** Anything modern that still slipped into a pocket (e.g. given by command) is taken away. */
    void sweepInventories() {
        if (world == null) return;
        for (Player p : world.getPlayers()) {
            if (p.getGameMode() == GameMode.SPECTATOR) continue;
            ItemStack[] c = p.getInventory().getContents();
            boolean removed = false, changed = false;
            for (int i = 0; i < c.length; i++) {
                if (c[i] == null || c[i].getType().isAir()) continue;
                ItemStack old = oldVersionOf(c[i]);
                if (old == null) { c[i] = null; removed = true; changed = true; }
                else if (old != c[i]) { c[i] = old; changed = true; }
            }
            if (changed) p.getInventory().setContents(c);
            if (removed) p.sendActionBar(Component.text("Предметы из нового майнкрафта убраны — в 1.0 их ещё нет", NamedTextColor.RED));
        }
    }

    private static final Set<Material> OLD_PLANT_BLOCKS = EnumSet.of(Material.WHEAT, Material.PUMPKIN_STEM,
            Material.MELON_STEM, Material.ATTACHED_PUMPKIN_STEM, Material.ATTACHED_MELON_STEM, Material.FARMLAND,
            Material.SHORT_GRASS, Material.FERN, Material.DANDELION, Material.POPPY);

    private static final Set<String> OLD_ENCHANTS = Set.of("protection", "fire_protection", "feather_falling",
            "blast_protection", "projectile_protection", "respiration", "aqua_affinity", "sharpness", "smite",
            "bane_of_arthropods", "knockback", "fire_aspect", "looting", "efficiency", "silk_touch", "unbreaking", "fortune");

    // ------------------------------------------------------------ what existed in 1.0

    private static final Set<EntityType> MOBS = EnumSet.of(
            EntityType.ZOMBIE, EntityType.SKELETON, EntityType.CREEPER, EntityType.SPIDER, EntityType.CAVE_SPIDER,
            EntityType.ENDERMAN, EntityType.SLIME, EntityType.GHAST, EntityType.ZOMBIFIED_PIGLIN, EntityType.BLAZE,
            EntityType.MAGMA_CUBE, EntityType.SILVERFISH, EntityType.PIG, EntityType.COW, EntityType.MOOSHROOM,
            EntityType.SHEEP, EntityType.CHICKEN, EntityType.SQUID, EntityType.WOLF, EntityType.VILLAGER,
            EntityType.SNOW_GOLEM, EntityType.ENDER_DRAGON, EntityType.GIANT, EntityType.ITEM, EntityType.EXPERIENCE_ORB);

    private static final Set<Material> ITEMS = EnumSet.noneOf(Material.class);
    static {
        String[] names = (
                "STONE GRASS_BLOCK DIRT COBBLESTONE OAK_PLANKS OAK_SAPLING SPRUCE_SAPLING BIRCH_SAPLING BEDROCK SAND GRAVEL "
                + "GOLD_ORE IRON_ORE COAL_ORE OAK_LOG SPRUCE_LOG BIRCH_LOG OAK_LEAVES SPRUCE_LEAVES BIRCH_LEAVES SPONGE GLASS "
                + "LAPIS_ORE LAPIS_BLOCK DISPENSER SANDSTONE NOTE_BLOCK RED_BED POWERED_RAIL DETECTOR_RAIL STICKY_PISTON COBWEB "
                + "SHORT_GRASS FERN DEAD_BUSH PISTON WHITE_WOOL ORANGE_WOOL MAGENTA_WOOL LIGHT_BLUE_WOOL YELLOW_WOOL LIME_WOOL "
                + "PINK_WOOL GRAY_WOOL LIGHT_GRAY_WOOL CYAN_WOOL PURPLE_WOOL BLUE_WOOL BROWN_WOOL GREEN_WOOL RED_WOOL BLACK_WOOL "
                + "DANDELION POPPY BROWN_MUSHROOM RED_MUSHROOM GOLD_BLOCK IRON_BLOCK SMOOTH_STONE_SLAB SANDSTONE_SLAB OAK_SLAB "
                + "COBBLESTONE_SLAB BRICK_SLAB STONE_BRICK_SLAB BRICKS TNT BOOKSHELF MOSSY_COBBLESTONE OBSIDIAN TORCH OAK_STAIRS "
                + "CHEST DIAMOND_ORE DIAMOND_BLOCK CRAFTING_TABLE FURNACE OAK_SIGN OAK_DOOR LADDER RAIL COBBLESTONE_STAIRS LEVER "
                + "STONE_PRESSURE_PLATE IRON_DOOR OAK_PRESSURE_PLATE REDSTONE_ORE REDSTONE_TORCH STONE_BUTTON SNOW ICE SNOW_BLOCK "
                + "CACTUS CLAY SUGAR_CANE JUKEBOX OAK_FENCE PUMPKIN NETHERRACK SOUL_SAND GLOWSTONE CARVED_PUMPKIN JACK_O_LANTERN "
                + "CAKE REPEATER OAK_TRAPDOOR STONE_BRICKS MOSSY_STONE_BRICKS CRACKED_STONE_BRICKS BROWN_MUSHROOM_BLOCK "
                + "RED_MUSHROOM_BLOCK MUSHROOM_STEM IRON_BARS GLASS_PANE MELON VINE OAK_FENCE_GATE BRICK_STAIRS STONE_BRICK_STAIRS "
                + "MYCELIUM LILY_PAD NETHER_BRICKS NETHER_BRICK_FENCE NETHER_BRICK_STAIRS ENCHANTING_TABLE BREWING_STAND CAULDRON "
                + "END_PORTAL_FRAME END_STONE DRAGON_EGG "
                + "IRON_SHOVEL IRON_PICKAXE IRON_AXE FLINT_AND_STEEL APPLE BOW ARROW COAL CHARCOAL DIAMOND IRON_INGOT GOLD_INGOT "
                + "IRON_SWORD WOODEN_SWORD WOODEN_SHOVEL WOODEN_PICKAXE WOODEN_AXE STONE_SWORD STONE_SHOVEL STONE_PICKAXE STONE_AXE "
                + "DIAMOND_SWORD DIAMOND_SHOVEL DIAMOND_PICKAXE DIAMOND_AXE STICK BOWL MUSHROOM_STEW GOLDEN_SWORD GOLDEN_SHOVEL "
                + "GOLDEN_PICKAXE GOLDEN_AXE STRING FEATHER GUNPOWDER WOODEN_HOE STONE_HOE IRON_HOE DIAMOND_HOE GOLDEN_HOE "
                + "WHEAT_SEEDS WHEAT BREAD LEATHER_HELMET LEATHER_CHESTPLATE LEATHER_LEGGINGS LEATHER_BOOTS CHAINMAIL_HELMET "
                + "CHAINMAIL_CHESTPLATE CHAINMAIL_LEGGINGS CHAINMAIL_BOOTS IRON_HELMET IRON_CHESTPLATE IRON_LEGGINGS IRON_BOOTS "
                + "DIAMOND_HELMET DIAMOND_CHESTPLATE DIAMOND_LEGGINGS DIAMOND_BOOTS GOLDEN_HELMET GOLDEN_CHESTPLATE GOLDEN_LEGGINGS "
                + "GOLDEN_BOOTS FLINT PORKCHOP COOKED_PORKCHOP PAINTING GOLDEN_APPLE BUCKET WATER_BUCKET LAVA_BUCKET MINECART SADDLE "
                + "REDSTONE SNOWBALL OAK_BOAT LEATHER MILK_BUCKET BRICK CLAY_BALL PAPER BOOK SLIME_BALL CHEST_MINECART "
                + "FURNACE_MINECART EGG COMPASS FISHING_ROD CLOCK GLOWSTONE_DUST COD COOKED_COD INK_SAC RED_DYE GREEN_DYE "
                + "COCOA_BEANS LAPIS_LAZULI PURPLE_DYE CYAN_DYE LIGHT_GRAY_DYE GRAY_DYE PINK_DYE LIME_DYE YELLOW_DYE LIGHT_BLUE_DYE "
                + "MAGENTA_DYE ORANGE_DYE BONE_MEAL BONE SUGAR COOKIE MAP FILLED_MAP SHEARS MELON_SLICE PUMPKIN_SEEDS MELON_SEEDS "
                + "BEEF COOKED_BEEF CHICKEN COOKED_CHICKEN ROTTEN_FLESH ENDER_PEARL BLAZE_ROD GHAST_TEAR GOLD_NUGGET NETHER_WART "
                + "POTION GLASS_BOTTLE SPIDER_EYE FERMENTED_SPIDER_EYE BLAZE_POWDER MAGMA_CREAM ENDER_EYE GLISTERING_MELON_SLICE "
                + "MUSIC_DISC_13 MUSIC_DISC_CAT MUSIC_DISC_BLOCKS MUSIC_DISC_CHIRP MUSIC_DISC_FAR MUSIC_DISC_MALL MUSIC_DISC_MELLOHI "
                + "MUSIC_DISC_STAL MUSIC_DISC_STRAD MUSIC_DISC_WARD MUSIC_DISC_11 MUSIC_DISC_WAIT").split("\\s+");
        for (String n : names) {
            Material m = Material.matchMaterial(n);
            if (m != null) ITEMS.add(m);
        }
    }
}
