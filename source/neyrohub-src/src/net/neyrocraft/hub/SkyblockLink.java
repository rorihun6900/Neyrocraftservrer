package net.neyrocraft.hub;

import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.World;
import org.bukkit.block.Block;
import org.bukkit.entity.Player;
import org.bukkit.plugin.Plugin;

import java.util.UUID;
import java.util.logging.Logger;

/**
 * The player's skyblock island, read from BentoBox directly (reflection: no compile-time dependency).
 * The portal used to run /island and leave the rest to BentoBox; now the plugin finds the island's
 * home itself, picks a safe spot and brings the player there down a beam of light.
 */
final class SkyblockLink {

    private SkyblockLink() { }

    static World world() {
        for (World w : Bukkit.getWorlds())
            if (w.getName().startsWith("bskyblock") && w.getEnvironment() == World.Environment.NORMAL) return w;
        return null;
    }

    /** Home of the player's island, or null when they have none (or BentoBox can't be read). */
    static Location home(Plugin bento, Player p, Logger log) {
        World w = world();
        if (w == null) return null;
        try {
            Object islands = bento.getClass().getMethod("getIslands").invoke(bento);
            Object island = islands.getClass().getMethod("getIsland", World.class, UUID.class).invoke(islands, w, p.getUniqueId());
            if (island == null) return null;
            Location l = call(island, "getHome", new Class<?>[]{String.class}, "");
            if (l == null) l = call(island, "getSpawnPoint", new Class<?>[]{World.Environment.class}, World.Environment.NORMAL);
            if (l == null) l = call(island, "getProtectionCenter", new Class<?>[0]);
            if (l == null) l = call(island, "getCenter", new Class<?>[0]);
            if (l != null && l.getWorld() == null) l.setWorld(w);
            return l == null ? null : l.clone();
        } catch (ReflectiveOperationException | RuntimeException e) {
            log.warning("Skyblock: could not read the island from BentoBox: " + e);
            return null;
        }
    }

    private static Location call(Object o, String name, Class<?>[] types, Object... args) {
        try {
            Object r = o.getClass().getMethod(name, types).invoke(o, args);
            return r instanceof Location l ? l : null;
        } catch (ReflectiveOperationException | RuntimeException e) {
            return null;
        }
    }

    /** The home itself if one can stand there, otherwise the nearest such spot (up to 6 blocks away). */
    static Location safe(Location home) {
        World w = home.getWorld();
        int x0 = home.getBlockX(), y0 = home.getBlockY(), z0 = home.getBlockZ();
        int[] dys = {0, 1, -1, 2, -2, 3, -3, 4, -4, 6, -6, 8, -8};
        for (int r = 0; r <= 6; r++)
            for (int dx = -r; dx <= r; dx++)
                for (int dz = -r; dz <= r; dz++) {
                    if (Math.max(Math.abs(dx), Math.abs(dz)) != r) continue;
                    for (int dy : dys)
                        if (standable(w, x0 + dx, y0 + dy, z0 + dz))
                            return new Location(w, x0 + dx + 0.5, y0 + dy, z0 + dz + 0.5, home.getYaw(), home.getPitch());
                }
        return null;
    }

    private static boolean standable(World w, int x, int y, int z) {
        if (y <= w.getMinHeight() || y >= w.getMaxHeight() - 2) return false;
        Block feet = w.getBlockAt(x, y, z), head = w.getBlockAt(x, y + 1, z), floor = w.getBlockAt(x, y - 1, z);
        if (!feet.isPassable() || feet.isLiquid() || !head.isPassable() || head.isLiquid()) return false;
        Material f = floor.getType();
        return f.isSolid() && f != Material.MAGMA_BLOCK && f != Material.CACTUS && f != Material.CAMPFIRE && f != Material.SOUL_CAMPFIRE;
    }
}
