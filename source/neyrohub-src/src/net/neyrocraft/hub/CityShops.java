package net.neyrocraft.hub;

import org.bukkit.Color;
import org.bukkit.Material;
import org.bukkit.enchantments.Enchantment;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.MerchantRecipe;
import org.bukkit.inventory.meta.EnchantmentStorageMeta;
import org.bukkit.inventory.meta.PotionMeta;
import org.bukkit.potion.PotionType;

import java.util.ArrayList;
import java.util.List;
import java.util.function.Consumer;

/**
 * The shops of Neyro-City. Everything is paid in emeralds; the pawnshop buys resources for emeralds,
 * so a new player earns their first money by selling what they mined or farmed.
 * No item is bought back for more than it is sold for, so there is no endless money loop.
 */
final class CityShops {

    private CityShops() { }

    /**
     * One shop: sign, colours, the trader, what stands on the counter and the trades.
     * accent/glow/awning/shelf are block ids of the pavilion.
     */
    record Type(String id, String title, String subtitle, int color, String accent, String glow, String awning, String shelf,
                String npc, String skin, boolean slim, Color outfit, String greeting, Material[] showcase,
                Consumer<List<MerchantRecipe>> trades) {
        List<MerchantRecipe> recipes() {
            List<MerchantRecipe> list = new ArrayList<>();
            trades.accept(list);
            return list;
        }

        /**
         * Trades for a player with this price multiplier (karma): what they buy costs more (or less)
         * emeralds, what they sell needs more goods for the same emeralds. Exchanges stay as they are.
         */
        List<MerchantRecipe> recipes(double mult) {
            List<MerchantRecipe> base = recipes();
            if (mult == 1.0) return base;
            List<MerchantRecipe> out = new ArrayList<>();
            for (MerchantRecipe r : base) {
                boolean paysEmeralds = isMoney(r.getResult());
                boolean allMoney = r.getIngredients().stream().allMatch(CityShops::isMoney);
                if (paysEmeralds && allMoney) { out.add(r); continue; }          // emerald <-> block
                List<ItemStack> ing = new ArrayList<>();
                if (paysEmeralds) {                                               // the shop buys goods
                    for (ItemStack i : r.getIngredients()) {
                        ItemStack c = i.clone();
                        if (mult > 1) c.setAmount(Math.min(c.getMaxStackSize(), (int) Math.ceil(c.getAmount() * mult)));
                        ing.add(c);
                    }
                } else {                                                          // the player buys
                    int total = 0;
                    for (ItemStack i : r.getIngredients()) {
                        if (i.getType() == Material.EMERALD) total += i.getAmount();
                        else if (i.getType() == Material.EMERALD_BLOCK) total += 9 * i.getAmount();
                        else ing.add(i.clone());
                    }
                    int price = Math.max(1, (int) Math.ceil(total * mult));
                    if (price <= 64) ing.add(0, it(Material.EMERALD, price));
                    else {
                        ing.add(0, it(Material.EMERALD_BLOCK, Math.min(64, price / 9)));
                        if (price % 9 > 0) ing.add(1, it(Material.EMERALD, price % 9));
                    }
                }
                MerchantRecipe n = new MerchantRecipe(r.getResult(), 0, Integer.MAX_VALUE, false, 0, 0f);
                n.setIngredients(ing.subList(0, Math.min(2, ing.size())));
                n.setIgnoreDiscounts(true);
                out.add(n);
            }
            return out;
        }
    }

    static final Type[] TYPES = {
            new Type("food", "НЕОН-РАМЕН", "еда · 24/7", 0xFF55D2, "magenta_concrete", "pearlescent_froglight",
                    "magenta_stained_glass", "smoker", "Кэндзи", "kai", false, Color.fromRGB(0xE0, 0x30, 0xB0),
                    "Свежий рамен! Плати изумрудами, остальное — моя забота.",
                    new Material[]{Material.COOKED_BEEF, Material.BREAD, Material.GOLDEN_APPLE},
                    l -> {
                        buy(l, 1, it(Material.BREAD, 8));
                        buy(l, 1, it(Material.COOKED_BEEF, 5));
                        buy(l, 1, it(Material.COOKED_PORKCHOP, 5));
                        buy(l, 1, it(Material.COOKED_SALMON, 6));
                        buy(l, 1, it(Material.BAKED_POTATO, 6));
                        buy(l, 1, it(Material.PUMPKIN_PIE, 4));
                        buy(l, 2, it(Material.CAKE, 1));
                        buy(l, 3, it(Material.GOLDEN_CARROT, 6));
                        buy(l, 8, it(Material.GOLDEN_APPLE, 1));
                    }),
            new Type("arms", "КИБЕР-АРСЕНАЛ", "оружие и броня", 0xFF4040, "red_concrete", "shroomlight",
                    "red_stained_glass", "smithing_table", "Рейна", "zuri", true, Color.fromRGB(0xC0, 0x10, 0x20),
                    "Сталь, стрелы, щиты. Без лицензии — но и без вопросов.",
                    new Material[]{Material.IRON_SWORD, Material.BOW, Material.SHIELD},
                    l -> {
                        buy(l, 3, it(Material.IRON_SWORD, 1));
                        buy(l, 3, it(Material.BOW, 1));
                        buy(l, 1, it(Material.ARROW, 16));
                        buy(l, 2, it(Material.SPECTRAL_ARROW, 8));
                        buy(l, 5, it(Material.CROSSBOW, 1));
                        buy(l, 4, it(Material.SHIELD, 1));
                        buy(l, 4, it(Material.IRON_HELMET, 1));
                        buy(l, 7, it(Material.IRON_CHESTPLATE, 1));
                        buy(l, 6, it(Material.IRON_LEGGINGS, 1));
                        buy(l, 3, it(Material.IRON_BOOTS, 1));
                        buy(l, 14, it(Material.DIAMOND_SWORD, 1));
                        buy(l, 20, it(Material.DIAMOND_CHESTPLATE, 1));
                    }),
            new Type("tools", "ТЕХНО-ЛАВКА", "инструменты", 0xFFD84A, "yellow_concrete", "ochre_froglight",
                    "yellow_stained_glass", "anvil", "Винт", "efe", false, Color.fromRGB(0xE0, 0xB0, 0x10),
                    "Кирки, вёдра, факелы. Всё, чтобы копать глубже.",
                    new Material[]{Material.IRON_PICKAXE, Material.TORCH, Material.SPYGLASS},
                    l -> {
                        buy(l, 3, it(Material.IRON_PICKAXE, 1));
                        buy(l, 3, it(Material.IRON_AXE, 1));
                        buy(l, 2, it(Material.IRON_SHOVEL, 1));
                        buy(l, 2, it(Material.IRON_HOE, 1));
                        buy(l, 12, it(Material.DIAMOND_PICKAXE, 1));
                        buy(l, 1, it(Material.TORCH, 32));
                        buy(l, 3, it(Material.BUCKET, 1));
                        buy(l, 2, it(Material.SHEARS, 1));
                        buy(l, 2, it(Material.FISHING_ROD, 1));
                        buy(l, 2, it(Material.FLINT_AND_STEEL, 1));
                        buy(l, 2, it(Material.COMPASS, 1));
                        buy(l, 3, it(Material.CLOCK, 1));
                        buy(l, 4, it(Material.SPYGLASS, 1));
                        buy(l, 2, it(Material.RED_BED, 1));
                    }),
            new Type("blocks", "БЛОК-МАРКЕТ", "строительные блоки", 0x40E8FF, "cyan_concrete", "sea_lantern",
                    "cyan_stained_glass", "bricks", "Кубик", "steve", false, Color.fromRGB(0x10, 0xA0, 0xC0),
                    "Строй свой неоновый район. Бетон любого цвета — по курсу.",
                    new Material[]{Material.SEA_LANTERN, Material.CYAN_CONCRETE, Material.OAK_LOG},
                    l -> {
                        buy(l, 2, it(Material.OAK_LOG, 16));
                        buy(l, 2, it(Material.SPRUCE_LOG, 16));
                        buy(l, 3, it(Material.CHERRY_LOG, 16));
                        buy(l, 2, it(Material.GLASS, 32));
                        buy(l, 2, it(Material.STONE_BRICKS, 32));
                        buy(l, 2, it(Material.BRICKS, 16));
                        buy(l, 1, it(Material.SAND, 32));
                        buy(l, 2, it(Material.BLACK_CONCRETE, 16));
                        buy(l, 2, it(Material.WHITE_CONCRETE, 16));
                        buy(l, 2, it(Material.CYAN_CONCRETE, 16));
                        buy(l, 2, it(Material.MAGENTA_CONCRETE, 16));
                        buy(l, 3, it(Material.QUARTZ_BLOCK, 16));
                        buy(l, 3, it(Material.DEEPSLATE_TILES, 16));
                        buy(l, 3, it(Material.GLOWSTONE, 8));
                        buy(l, 4, it(Material.SEA_LANTERN, 8));
                        buy(l, 3, it(Material.END_ROD, 8));
                    }),
            new Type("pawn", "ЛОМБАРД 24/7", "скупка ресурсов", 0x7CFF4F, "lime_concrete", "verdant_froglight",
                    "lime_stained_glass", "chest", "Ворон", "noor", false, Color.fromRGB(0x40, 0xC0, 0x20),
                    "Принёс добычу? Плачу изумрудами, сразу и без налогов.",
                    new Material[]{Material.EMERALD, Material.DIAMOND, Material.IRON_INGOT},
                    l -> {
                        sell(l, it(Material.COAL, 16), 1);
                        sell(l, it(Material.IRON_INGOT, 6), 1);
                        sell(l, it(Material.GOLD_INGOT, 3), 1);
                        sell(l, it(Material.DIAMOND, 1), 3);
                        sell(l, it(Material.NETHERITE_SCRAP, 1), 8);
                        sell(l, it(Material.REDSTONE, 12), 1);
                        sell(l, it(Material.LAPIS_LAZULI, 6), 1);
                        sell(l, it(Material.QUARTZ, 8), 1);
                        sell(l, it(Material.COBBLESTONE, 32), 1);
                        sell(l, it(Material.OAK_LOG, 24), 1);
                        sell(l, it(Material.WHEAT, 16), 1);
                        sell(l, it(Material.CARROT, 20), 1);
                        sell(l, it(Material.POTATO, 20), 1);
                        sell(l, it(Material.PUMPKIN, 4), 1);
                        sell(l, it(Material.MELON_SLICE, 16), 1);
                        sell(l, it(Material.SUGAR_CANE, 8), 1);
                        sell(l, it(Material.LEATHER, 8), 1);
                        sell(l, it(Material.ROTTEN_FLESH, 16), 1);
                        sell(l, it(Material.BONE, 12), 1);
                        sell(l, it(Material.STRING, 12), 1);
                        sell(l, it(Material.SPIDER_EYE, 4), 1);
                        sell(l, it(Material.GUNPOWDER, 6), 1);
                        sell(l, it(Material.SLIME_BALL, 4), 1);
                        sell(l, it(Material.ENDER_PEARL, 2), 1);
                        sell(l, it(Material.BLAZE_ROD, 1), 1);
                        trade(l, it(Material.EMERALD, 9), null, it(Material.EMERALD_BLOCK, 1));
                        trade(l, it(Material.EMERALD_BLOCK, 1), null, it(Material.EMERALD, 9));
                    }),
            new Type("pharm", "НЕЙРО-ФАРМА", "зелья и опыт", 0x6FD0FF, "light_blue_concrete", "sea_lantern",
                    "light_blue_stained_glass", "brewing_stand", "Доктор Ио", "sunny", true, Color.fromRGB(0x60, 0xC0, 0xFF),
                    "Лечим тело, разгоняем нейроны. Побочные эффекты — твои проблемы.",
                    new Material[]{Material.POTION, Material.EXPERIENCE_BOTTLE, Material.GLISTERING_MELON_SLICE},
                    l -> {
                        buy(l, 4, potion(PotionType.HEALING, false));
                        buy(l, 6, potion(PotionType.STRONG_HEALING, false));
                        buy(l, 5, potion(PotionType.HEALING, true));
                        buy(l, 5, potion(PotionType.REGENERATION, false));
                        buy(l, 4, potion(PotionType.SWIFTNESS, false));
                        buy(l, 3, potion(PotionType.LONG_NIGHT_VISION, false));
                        buy(l, 5, potion(PotionType.LONG_FIRE_RESISTANCE, false));
                        buy(l, 4, potion(PotionType.LONG_WATER_BREATHING, false));
                        buy(l, 6, potion(PotionType.STRENGTH, false));
                        buy(l, 2, it(Material.EXPERIENCE_BOTTLE, 4));
                        buy(l, 3, it(Material.MILK_BUCKET, 1));
                    }),
            new Type("black", "ЧЁРНЫЙ РЫНОК", "редкости · без вопросов", 0xB060FF, "purple_concrete", "crying_obsidian",
                    "purple_stained_glass", "ender_chest", "Тень", "ari", false, Color.fromRGB(0x30, 0x10, 0x50),
                    "Тсс. Здесь есть всё — если хватит изумрудов.",
                    new Material[]{Material.ELYTRA, Material.TOTEM_OF_UNDYING, Material.ENCHANTED_BOOK},
                    l -> {
                        buy(l, 3, it(Material.ENDER_PEARL, 1));
                        buy(l, 4, it(Material.DIAMOND, 1));
                        buy(l, 6, it(Material.NAME_TAG, 1));
                        buy(l, 5, it(Material.SADDLE, 1));
                        buy(l, 1, it(Material.FIREWORK_ROCKET, 4));
                        buy(l, 12, it(Material.SHULKER_SHELL, 1));
                        buy(l, 20, it(Material.NETHERITE_SCRAP, 1));
                        buy(l, 24, book(Enchantment.MENDING, 1));
                        buy(l, 10, book(Enchantment.EFFICIENCY, 4));
                        buy(l, 10, book(Enchantment.UNBREAKING, 3));
                        buy(l, 12, book(Enchantment.PROTECTION, 4));
                        buy(l, 12, book(Enchantment.SHARPNESS, 4));
                        buy(l, 14, book(Enchantment.FORTUNE, 3));
                        buy(l, 16, book(Enchantment.LOOTING, 3));
                        buy(l, 10, book(Enchantment.FEATHER_FALLING, 4));
                        buy(l, 30, it(Material.TOTEM_OF_UNDYING, 1));
                        buy(l, 48, it(Material.ELYTRA, 1));
                    }),
            new Type("farm", "АГРО-ЛАБ", "семена и саженцы", 0x3DDC84, "green_concrete", "verdant_froglight",
                    "green_stained_glass", "composter", "Флора", "makena", true, Color.fromRGB(0x20, 0x90, 0x40),
                    "Гидропоника, саженцы, семена. Город тоже должен что-то есть.",
                    new Material[]{Material.WHEAT_SEEDS, Material.CHERRY_SAPLING, Material.SUGAR_CANE},
                    l -> {
                        buy(l, 1, it(Material.WHEAT_SEEDS, 16));
                        buy(l, 1, it(Material.CARROT, 8));
                        buy(l, 1, it(Material.POTATO, 8));
                        buy(l, 1, it(Material.BEETROOT_SEEDS, 16));
                        buy(l, 1, it(Material.PUMPKIN_SEEDS, 4));
                        buy(l, 1, it(Material.MELON_SEEDS, 4));
                        buy(l, 1, it(Material.SUGAR_CANE, 4));
                        buy(l, 1, it(Material.OAK_SAPLING, 4));
                        buy(l, 1, it(Material.SPRUCE_SAPLING, 4));
                        buy(l, 1, it(Material.BIRCH_SAPLING, 4));
                        buy(l, 2, it(Material.CHERRY_SAPLING, 4));
                        buy(l, 1, it(Material.BONE_MEAL, 16));
                        buy(l, 1, it(Material.SWEET_BERRIES, 4));
                        buy(l, 1, it(Material.CACTUS, 4));
                        buy(l, 2, it(Material.BAMBOO, 8));
                        buy(l, 3, it(Material.NETHER_WART, 4));
                        buy(l, 2, it(Material.HAY_BLOCK, 3));
                    }),
    };

    // ------------------------------------------------------------------ helpers

    static ItemStack it(Material m, int n) {
        return new ItemStack(m, n);
    }

    private static boolean isMoney(ItemStack s) {
        return s != null && (s.getType() == Material.EMERALD || s.getType() == Material.EMERALD_BLOCK);
    }

    private static ItemStack potion(PotionType type, boolean splash) {
        ItemStack s = new ItemStack(splash ? Material.SPLASH_POTION : Material.POTION);
        s.editMeta(PotionMeta.class, m -> m.setBasePotionType(type));
        return s;
    }

    private static ItemStack book(Enchantment e, int level) {
        ItemStack s = new ItemStack(Material.ENCHANTED_BOOK);
        s.editMeta(EnchantmentStorageMeta.class, m -> m.addStoredEnchant(e, level, true));
        return s;
    }

    /** The player pays {@code emeralds} for {@code result}. */
    private static void buy(List<MerchantRecipe> l, int emeralds, ItemStack result) {
        if (emeralds <= 64) trade(l, it(Material.EMERALD, emeralds), null, result);
        else trade(l, it(Material.EMERALD_BLOCK, emeralds / 9), emeralds % 9 == 0 ? null : it(Material.EMERALD, emeralds % 9), result);
    }

    /** The shop buys {@code goods} for {@code emeralds}. */
    private static void sell(List<MerchantRecipe> l, ItemStack goods, int emeralds) {
        trade(l, goods, null, it(Material.EMERALD, emeralds));
    }

    private static void trade(List<MerchantRecipe> l, ItemStack a, ItemStack b, ItemStack result) {
        MerchantRecipe r = new MerchantRecipe(result, 0, Integer.MAX_VALUE, false, 0, 0f);
        r.addIngredient(a);
        if (b != null) r.addIngredient(b);
        r.setIgnoreDiscounts(true);
        l.add(r);
    }
}
