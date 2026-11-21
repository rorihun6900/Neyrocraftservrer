package net.neyrocraft.hub;

import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.World;
import org.bukkit.block.Block;
import org.bukkit.configuration.file.YamlConfiguration;

import java.io.File;
import java.io.IOException;

/** Adds usable lobbies and upper floors to the existing hollow city towers. */
final class NeyroInteriors {
    static void ensure(NeyroHub plugin, World world, int cx, int cz, int y0, CityPlan plan) {
        File marker = new File(plugin.getDataFolder(), "interiors.yml");
        YamlConfiguration state = YamlConfiguration.loadConfiguration(marker);
        if (state.getInt("version") >= 1) return;
        int count = 0;
        for (CityPlan.Tower t : plan.towers()) {
            boolean residence = t.index() % 6 == 0 && t.index() / 6 < 4;
            int doorZ = t.cz() > 0 ? t.z0() : t.z1();
            if (!residence) {
                for (int x = t.cx() - 1; x <= t.cx() + 1; x++)
                    for (int y = 1; y <= 3; y++) set(world, cx + x, y0 + y, cz + doorZ, Material.AIR);
                for (int x = t.x0() + 2; x <= t.x1() - 2; x++)
                    for (int z = t.z0() + 2; z <= t.z1() - 2; z++)
                        for (int y = 1; y <= 3; y++) {
                            Block b = world.getBlockAt(cx + x, y0 + y, cz + z);
                            if (b.getType() == Material.BLACK_CONCRETE || b.getType() == Material.OCHRE_FROGLIGHT || b.getType() == Material.SEA_LANTERN) b.setType(Material.AIR, false);
                        }
                set(world, cx + t.cx(), y0 + 4, cz + t.cz(), Material.SEA_LANTERN);
                set(world, cx + t.x0() + 2, y0 + 1, cz + t.z1() - 2, Material.ENDER_CHEST);
                set(world, cx + t.x1() - 2, y0 + 1, cz + t.z1() - 2, Material.CRAFTING_TABLE);
                set(world, cx + t.x1() - 2, y0 + 1, cz + t.z0() + 2, Material.BARREL);
                for (int x = t.x0() + 3; x <= t.x1() - 3; x++) {
                    set(world, cx + x, y0 + 1, cz + t.z0() + 2, Material.POLISHED_BLACKSTONE_SLAB);
                    set(world, cx + x, y0 + 1, cz + t.z1() - 2, Material.POLISHED_BLACKSTONE_SLAB);
                }
            }
            int maxFloor = Math.min(t.h1() - 3, 28);
            int accessX = t.x1() - 3, accessZ = t.z0() + 3;
            for (int floor = 5; floor <= maxFloor; floor += 4) {
                for (int x = t.x0() + 2; x <= t.x1() - 2; x++)
                    for (int z = t.z0() + 2; z <= t.z1() - 2; z++)
                        if (residence || x != accessX || z != accessZ)
                            set(world, cx + x, y0 + floor, cz + z, Material.SMOOTH_QUARTZ);
                set(world, cx + t.cx(), y0 + floor, cz + t.cz(), Material.SEA_LANTERN);
                set(world, cx + t.x1() - 2, y0 + floor + 1, cz + t.z1() - 2, Material.BARREL);
            }
            if (!residence) for (int y = 1; y <= maxFloor + 3; y++)
                set(world, cx + accessX, y0 + y, cz + accessZ, Material.SCAFFOLDING);
            count++;
        }
        state.set("version", 1);
        try { state.save(marker); } catch (IOException ex) { plugin.getLogger().warning("Interior marker save failed: " + ex); }
        plugin.getLogger().info("Neyro-City interiors furnished: " + count + " towers");
    }

    private static void set(World world, int x, int y, int z, Material m) { world.getBlockAt(x, y, z).setType(m, false); }
}
