package net.neyrocraft.hub;

import org.bukkit.World;

/** Places the Blender block plan at the existing portal without moving either gateway. */
final class DarkTemple {
    private DarkTemple() {}

    static boolean build(NeyroHub plugin, World world, int floor, int gateZ, boolean enclosed) {
        String path = enclosed ? "ship/hub3_temple.ship.gz" : "ship/anarchy_temple.ship.gz";
        ShipModel model = ShipModel.load(plugin, path);
        if (model == null) return false;
        int minZ = gateZ + model.minZ;
        int maxZ = gateZ + model.maxZ;
        int minY = floor + model.minDy;
        int maxY = floor + model.maxDy;

        // The upgraded temple extends past the old footprint. Preserve stored inventories
        // and other tile entities if players have built in that section of anarchy.
        if (!enclosed) {
            for (int cx = model.minX >> 4; cx <= model.maxX >> 4; cx++) {
                for (int cz = minZ >> 4; cz <= maxZ >> 4; cz++) {
                    for (var tile : world.getChunkAt(cx, cz).getTileEntities(false)) {
                        if (tile.getX() < model.minX || tile.getX() > model.maxX
                                || tile.getY() < minY || tile.getY() > maxY
                                || tile.getZ() < minZ || tile.getZ() > maxZ) continue;
                        plugin.getLogger().warning("Anarchy temple upgrade deferred: occupied block at "
                                + tile.getX() + ", " + tile.getY() + ", " + tile.getZ());
                        return false;
                    }
                }
            }
        }

        model.placeSome(world, 0, floor, gateZ, 0, Long.MAX_VALUE);
        plugin.getLogger().info("Loaded Blender temple in " + world.getName() + " (" + model.blocks + " cells).");
        return true;
    }
}
