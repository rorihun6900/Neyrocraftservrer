package net.neyrocraft.hub;

import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.TreeType;
import org.bukkit.World;
import org.bukkit.block.Biome;
import org.bukkit.block.BlockFace;
import org.bukkit.generator.BiomeProvider;
import org.bukkit.generator.BlockPopulator;
import org.bukkit.generator.ChunkGenerator;
import org.bukkit.generator.LimitedRegion;
import org.bukkit.generator.WorldInfo;
import org.bukkit.util.noise.SimplexOctaveGenerator;

import java.util.List;
import java.util.Random;

/**
 * "Minecraft 1.0" terrain: 2011-style rolling hills with overhangs, old biomes, sea level 63,
 * a world that is only 128 blocks high, spaghetti caves with lava at the bottom,
 * old ores, oak/birch/spruce trees, flowers, tall grass, cacti and sugar cane.
 */
final class RetroGenerator extends ChunkGenerator {
    static final int SEA = 62;

    // ------------------------------------------------------------ shared noise (per seed)

    static final class Noise {
        final SimplexOctaveGenerator cont, hills, mount, detail, temp, humid, caveA, caveB, floor;
        Noise(long seed) {
            cont = gen(seed, 4, 1 / 600.0);
            hills = gen(seed + 1, 6, 1 / 170.0);
            mount = gen(seed + 2, 4, 1 / 320.0);
            detail = gen(seed + 3, 3, 1 / 42.0);
            temp = gen(seed + 4, 3, 1 / 420.0);
            humid = gen(seed + 5, 3, 1 / 360.0);
            caveA = gen(seed + 6, 2, 1 / 34.0);
            caveB = gen(seed + 7, 2, 1 / 34.0);
            floor = gen(seed + 8, 2, 1 / 24.0);
        }
        private static SimplexOctaveGenerator gen(long seed, int oct, double scale) {
            SimplexOctaveGenerator g = new SimplexOctaveGenerator(seed, oct);
            g.setScale(scale);
            return g;
        }
        double mountain(int x, int z) { return Math.max(0, mount.noise(x, z, 0.5, 0.5, true) - 0.05); }
        double base(int x, int z) {
            double m = mountain(x, z);
            return 66 + cont.noise(x, z, 0.5, 0.5, true) * 15 + hills.noise(x, z, 0.5, 0.5, true) * 9 + m * m * 70;
        }
        Biome biome(int x, int z) {
            double b = base(x, z), m = mountain(x, z);
            double t = temp.noise(x, z, 0.5, 0.5, true), h = humid.noise(x, z, 0.5, 0.5, true);
            if (b < SEA - 3) return t < -0.45 ? Biome.FROZEN_OCEAN : Biome.OCEAN;
            if (b < SEA + 2.5 && m < 0.1) return t < -0.45 ? Biome.SNOWY_BEACH : Biome.BEACH;
            if (m > 0.32) return Biome.WINDSWEPT_HILLS;
            if (t > 0.32 && h < 0.05) return Biome.DESERT;
            if (t < -0.45) return h > 0 ? Biome.SNOWY_TAIGA : Biome.SNOWY_PLAINS;
            if (t < -0.15) return Biome.TAIGA;
            if (h > 0.42 && b < SEA + 8) return Biome.SWAMP;
            if (h > 0.02) return Biome.FOREST;
            return Biome.PLAINS;
        }
    }

    private static volatile Noise noise;
    private static volatile long noiseSeed;

    static Noise noise(long seed) {
        Noise n = noise;
        if (n == null || noiseSeed != seed) {
            synchronized (RetroGenerator.class) {
                if (noise == null || noiseSeed != seed) { noise = new Noise(seed); noiseSeed = seed; }
                n = noise;
            }
        }
        return n;
    }

    // ------------------------------------------------------------ terrain

    @Override
    public void generateNoise(WorldInfo info, Random random, int cx, int cz, ChunkData data) {
        Noise n = noise(info.getSeed());
        Random r = new Random(info.getSeed() ^ (cx * 341873128712L + cz * 132897987541L));
        for (int lx = 0; lx < 16; lx++) {
            for (int lz = 0; lz < 16; lz++) {
                int x = cx * 16 + lx, z = cz * 16 + lz;
                double base = n.base(x, z);
                double m = n.mountain(x, z);
                double amp = 5 + m * 22;                         // overhangs grow in the hills
                Biome biome = n.biome(x, z);

                // density sampled every 4 blocks in y, interpolated between
                int top = 0;
                double[] dens = new double[34];
                for (int i = 0; i < 34; i++) {
                    int y = i * 4;
                    dens[i] = (base - y) / 6.0 + n.detail.noise(x, y, z, 0.5, 0.5, true) * amp / 6.0;
                }
                for (int y = 1; y < 128; y++) {
                    int i = y / 4; double f = (y % 4) / 4.0;
                    double d = dens[i] + (dens[Math.min(33, i + 1)] - dens[i]) * f;
                    if (d > 0) { data.setBlock(lx, y, lz, Material.STONE); top = y; }
                }

                // bedrock floor like 2011
                data.setBlock(lx, 0, lz, Material.BEDROCK);
                for (int y = 1; y <= 4; y++) if (r.nextInt(y + 1) == 0) data.setBlock(lx, y, lz, Material.BEDROCK);

                // surface layers on every exposed stone top
                boolean cold = biome == Biome.SNOWY_PLAINS || biome == Biome.SNOWY_TAIGA || biome == Biome.FROZEN_OCEAN || biome == Biome.SNOWY_BEACH;
                boolean sandy = biome == Biome.DESERT || biome == Biome.BEACH || biome == Biome.SNOWY_BEACH;
                int depth = -1;
                for (int y = 127; y > 4; y--) {
                    Material t = data.getType(lx, y, lz);
                    if (t != Material.STONE) { depth = -1; continue; }
                    depth++;
                    if (depth == 0) {
                        Material surf;
                        if (y < SEA - 1) {
                            double fl = n.floor.noise(x, z, 0.5, 0.5, true);
                            surf = fl > 0.3 ? Material.GRAVEL : fl < -0.35 ? Material.CLAY : (sandy || y > SEA - 6 ? Material.SAND : Material.DIRT);
                        } else if (sandy) surf = Material.SAND;
                        else surf = Material.GRASS_BLOCK;
                        data.setBlock(lx, y, lz, surf);
                        if (surf == Material.GRASS_BLOCK && cold && y >= SEA) data.setBlock(lx, y + 1, lz, Material.SNOW);
                    } else if (depth <= 3) {
                        Material above = data.getType(lx, y + depth, lz);
                        data.setBlock(lx, y, lz, above == Material.SAND ? Material.SAND
                                : above == Material.GRAVEL ? Material.GRAVEL : Material.DIRT);
                    } else if (depth <= 6 && sandy && y > SEA - 10) {
                        data.setBlock(lx, y, lz, Material.SANDSTONE);
                    }
                }

                // oceans, lakes left by the noise
                for (int y = SEA; y > 0; y--) {
                    if (!data.getType(lx, y, lz).isAir()) continue;
                    if (y > top && y <= SEA) data.setBlock(lx, y, lz, y == SEA && cold ? Material.ICE : Material.WATER);
                }

                // spaghetti caves; lava near the bottom
                int surface = Math.max(top, SEA);
                for (int y = 5; y < Math.min(127, top - 3); y++) {
                    Material t = data.getType(lx, y, lz);
                    if (t != Material.STONE && t != Material.DIRT && t != Material.GRAVEL && t != Material.SANDSTONE) continue;
                    double a = n.caveA.noise(x, y * 1.6, z, 0.5, 0.5, true);
                    if (Math.abs(a) > 0.085) continue;
                    double b = n.caveB.noise(x, y * 1.6, z, 0.5, 0.5, true);
                    if (Math.abs(b) > 0.085) continue;
                    if (top < SEA + 2 && y > top - 8) continue;              // don't drain the sea into caves
                    if (y > surface - 2) continue;
                    data.setBlock(lx, y, lz, y <= 10 ? Material.LAVA : Material.CAVE_AIR);
                }
            }
        }
    }

    @Override public boolean shouldGenerateNoise() { return false; }
    @Override public boolean shouldGenerateSurface() { return false; }
    @Override public boolean shouldGenerateCaves() { return false; }
    @Override public boolean shouldGenerateDecorations() { return false; }
    @Override public boolean shouldGenerateMobs() { return true; }
    @Override public boolean shouldGenerateStructures() { return false; }

    @Override
    public BiomeProvider getDefaultBiomeProvider(WorldInfo info) {
        return new BiomeProvider() {
            @Override public Biome getBiome(WorldInfo w, int x, int y, int z) { return noise(w.getSeed()).biome(x, z); }
            @Override public List<Biome> getBiomes(WorldInfo w) {
                return List.of(Biome.OCEAN, Biome.FROZEN_OCEAN, Biome.BEACH, Biome.SNOWY_BEACH, Biome.WINDSWEPT_HILLS,
                        Biome.DESERT, Biome.SNOWY_PLAINS, Biome.SNOWY_TAIGA, Biome.TAIGA, Biome.SWAMP, Biome.FOREST, Biome.PLAINS);
            }
        };
    }

    @Override
    public List<BlockPopulator> getDefaultPopulators(World world) {
        return List.of(new Ores(), new Plants());
    }

    @Override
    public Location getFixedSpawnLocation(World world, Random random) {
        Noise n = noise(world.getSeed());
        for (int i = 0; i < 400; i++) {
            int x = (i % 20) * 24 - 240, z = (i / 20) * 24 - 240;
            Biome b = n.biome(x, z);
            double h = n.base(x, z);
            if (h > SEA + 2 && h < 90 && (b == Biome.PLAINS || b == Biome.FOREST))
                return new Location(world, x + 0.5, Math.min(120, (int) h + 12), z + 0.5);
        }
        return new Location(world, 0.5, 100, 0.5);
    }

    // ------------------------------------------------------------ ores (2011 distribution)

    private static final class Ores extends BlockPopulator {
        @Override
        public void populate(WorldInfo info, Random random, int cx, int cz, LimitedRegion region) {
            vein(region, random, cx, cz, Material.COAL_ORE, 20, 16, 5, 127);
            vein(region, random, cx, cz, Material.IRON_ORE, 20, 8, 5, 63);
            vein(region, random, cx, cz, Material.GOLD_ORE, 2, 8, 5, 31);
            vein(region, random, cx, cz, Material.REDSTONE_ORE, 8, 7, 5, 15);
            vein(region, random, cx, cz, Material.DIAMOND_ORE, 1, 7, 5, 15);
            vein(region, random, cx, cz, Material.LAPIS_ORE, 1, 6, 8, 30);
            vein(region, random, cx, cz, Material.DIRT, 20, 30, 5, 127);
            vein(region, random, cx, cz, Material.GRAVEL, 10, 30, 5, 127);
        }

        private static void vein(LimitedRegion region, Random r, int cx, int cz, Material ore, int count, int size, int minY, int maxY) {
            for (int i = 0; i < count; i++) {
                int x = cx * 16 + r.nextInt(16), z = cz * 16 + r.nextInt(16), y = minY + r.nextInt(maxY - minY + 1);
                for (int k = 0; k < size; k++) {
                    if (region.isInRegion(x, y, z) && region.getType(x, y, z) == Material.STONE) region.setType(x, y, z, ore);
                    switch (r.nextInt(6)) {
                        case 0 -> x++; case 1 -> x--; case 2 -> y++; case 3 -> y--; case 4 -> z++; default -> z--;
                    }
                }
            }
        }
    }

    // ------------------------------------------------------------ trees and plants

    private static final class Plants extends BlockPopulator {
        @Override
        public void populate(WorldInfo info, Random r, int cx, int cz, LimitedRegion region) {
            Noise n = noise(info.getSeed());
            Biome b = n.biome(cx * 16 + 8, cz * 16 + 8);
            boolean taiga = b == Biome.TAIGA || b == Biome.SNOWY_TAIGA;
            int trees = b == Biome.FOREST ? 6 + r.nextInt(5)
                    : taiga ? 5 + r.nextInt(4)
                    : b == Biome.SWAMP ? 2 + r.nextInt(2)
                    : b == Biome.WINDSWEPT_HILLS ? r.nextInt(3)
                    : b == Biome.PLAINS ? (r.nextInt(10) == 0 ? 1 : 0) : 0;
            for (int i = 0; i < trees; i++) {
                int x = cx * 16 + r.nextInt(16), z = cz * 16 + r.nextInt(16);
                int y = top(region, x, z);
                if (y < 0) continue;
                Material ground = region.getType(x, y, z);
                if (ground != Material.GRASS_BLOCK && ground != Material.DIRT) continue;
                TreeType type = taiga ? (r.nextInt(3) == 0 ? TreeType.TALL_REDWOOD : TreeType.REDWOOD)
                        : b == Biome.SWAMP ? TreeType.SWAMP
                        : b == Biome.FOREST ? (r.nextInt(5) == 0 ? TreeType.BIRCH : r.nextInt(10) == 0 ? TreeType.BIG_TREE : TreeType.TREE)
                        : (r.nextInt(10) == 0 ? TreeType.BIG_TREE : TreeType.TREE);
                region.generateTree(new Location(null, x, y + 1, z), r, type);
            }

            int grass = b == Biome.PLAINS ? 40 : (b == Biome.FOREST || b == Biome.SWAMP) ? 14 : (b == Biome.TAIGA || b == Biome.WINDSWEPT_HILLS) ? 6 : 0;
            for (int i = 0; i < grass; i++) place(region, r, cx, cz, r.nextInt(5) == 0 && b == Biome.TAIGA ? Material.FERN : Material.SHORT_GRASS, Material.GRASS_BLOCK);
            if (b == Biome.PLAINS || b == Biome.FOREST || b == Biome.SWAMP) {
                int flowers = b == Biome.PLAINS ? 4 : 2;
                Material f = r.nextBoolean() ? Material.DANDELION : Material.POPPY;
                for (int i = 0; i < flowers; i++) place(region, r, cx, cz, f, Material.GRASS_BLOCK);
            }
            if (r.nextInt(6) == 0) place(region, r, cx, cz, r.nextBoolean() ? Material.BROWN_MUSHROOM : Material.RED_MUSHROOM, Material.GRASS_BLOCK);
            if (r.nextInt(48) == 0) place(region, r, cx, cz, Material.PUMPKIN, Material.GRASS_BLOCK);
            if (b == Biome.DESERT) {
                for (int i = 0; i < 3; i++) place(region, r, cx, cz, Material.DEAD_BUSH, Material.SAND);
                for (int i = 0; i < 4; i++) cactus(region, r, cx, cz);
            }
            for (int i = 0; i < 8; i++) sugarCane(region, r, cx, cz);
        }

        private static int top(LimitedRegion region, int x, int z) {
            for (int y = 127; y > 1; y--) {
                if (!region.isInRegion(x, y, z)) continue;
                Material t = region.getType(x, y, z);
                if (t.isAir() || t == Material.SNOW) continue;
                return t.isSolid() ? y : -1;
            }
            return -1;
        }

        private static void place(LimitedRegion region, Random r, int cx, int cz, Material plant, Material on) {
            int x = cx * 16 + r.nextInt(16), z = cz * 16 + r.nextInt(16);
            int y = top(region, x, z);
            if (y < 0 || region.getType(x, y, z) != on || !region.getType(x, y + 1, z).isAir()) return;
            region.setType(x, y + 1, z, plant);
        }

        private static void cactus(LimitedRegion region, Random r, int cx, int cz) {
            int x = cx * 16 + 1 + r.nextInt(14), z = cz * 16 + 1 + r.nextInt(14);
            int y = top(region, x, z);
            if (y < 0 || region.getType(x, y, z) != Material.SAND) return;
            int h = 1 + r.nextInt(3);
            for (int k = 1; k <= h; k++) {
                for (BlockFace f : new BlockFace[]{BlockFace.NORTH, BlockFace.SOUTH, BlockFace.EAST, BlockFace.WEST})
                    if (!region.getType(x + f.getModX(), y + k, z + f.getModZ()).isAir()) return;
                region.setType(x, y + k, z, Material.CACTUS);
            }
        }

        private static void sugarCane(LimitedRegion region, Random r, int cx, int cz) {
            int x = cx * 16 + 1 + r.nextInt(14), z = cz * 16 + 1 + r.nextInt(14);
            int y = top(region, x, z);
            if (y != SEA) return;
            Material g = region.getType(x, y, z);
            if (g != Material.GRASS_BLOCK && g != Material.SAND && g != Material.DIRT) return;
            boolean water = false;
            for (BlockFace f : new BlockFace[]{BlockFace.NORTH, BlockFace.SOUTH, BlockFace.EAST, BlockFace.WEST})
                if (region.getType(x + f.getModX(), y, z + f.getModZ()) == Material.WATER) water = true;
            if (!water) return;
            int h = 1 + r.nextInt(3);
            for (int k = 1; k <= h; k++) if (region.getType(x, y + k, z).isAir()) region.setType(x, y + k, z, Material.SUGAR_CANE);
        }
    }
}
