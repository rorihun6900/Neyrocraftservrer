package net.neyrocraft.hub;

import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.World;
import org.bukkit.block.Biome;
import org.bukkit.generator.BiomeProvider;
import org.bukkit.generator.ChunkGenerator;
import org.bukkit.generator.WorldInfo;
import org.bukkit.util.noise.SimplexOctaveGenerator;

import java.util.List;
import java.util.Random;

/**
 * Planet Terrax: a red desert of dunes and terraced mesas in bands of terracotta, purple crystal spires,
 * and the speeder circuit cut right into the landscape — road, curbs, canyons through the mesas,
 * a lit tunnel through the great mesa, checkpoint arches, the start grandstand and the pit plaza.
 */
final class TerraxGenerator extends ChunkGenerator {
    static final int FLOOR = 30;                     // nothing is generated below

    private final RaceTrack track;
    private SimplexOctaveGenerator dunes, ripples, mesas, rough;
    private long seed = Long.MIN_VALUE;

    TerraxGenerator(RaceTrack track) {
        this.track = track;
    }

    private synchronized void noise(long s) {
        if (seed == s && dunes != null) return;
        seed = s;
        dunes = gen(s, 3, 1 / 46.0);
        ripples = gen(s + 1, 2, 1 / 9.0);
        mesas = gen(s + 2, 4, 1 / 110.0);
        rough = gen(s + 3, 2, 1 / 18.0);
    }

    private static SimplexOctaveGenerator gen(long s, int oct, double scale) {
        SimplexOctaveGenerator g = new SimplexOctaveGenerator(s, oct);
        g.setScale(scale);
        return g;
    }

    // ------------------------------------------------------------ natural terrain

    /** Height of the landscape without the track. */
    double natural(int x, int z) {
        double h = 67 + dunes.noise(x, z * 1.6, 0.5, 0.5, true) * 3.2 + ripples.noise(x, z, 0.5, 0.5, true) * 0.7;
        // terraced mesas, kept away from the pit area
        double away = Math.max(0, Math.min(1, (Math.hypot(x, z - 10) - 80) / 50.0));
        double m = mesas.noise(x, z, 0.5, 0.5, true) * away;
        if (m > 0.32) {
            double raw = Math.min(30, (m - 0.32) * 140) + rough.noise(x, z, 0.5, 0.5, true) * 1.5;
            h = Math.max(h, 69 + Math.floor(raw / 4.0) * 4);
        }
        return Math.max(h, RaceTrack.mesaHeight(x, z));
    }

    private static final Material[] BANDS = {
            Material.TERRACOTTA, Material.ORANGE_TERRACOTTA, Material.ORANGE_TERRACOTTA, Material.YELLOW_TERRACOTTA,
            Material.TERRACOTTA, Material.RED_TERRACOTTA, Material.BROWN_TERRACOTTA, Material.WHITE_TERRACOTTA,
            Material.ORANGE_TERRACOTTA, Material.RED_TERRACOTTA, Material.LIGHT_GRAY_TERRACOTTA, Material.TERRACOTTA};

    private static Material band(int y) {
        return BANDS[Math.floorMod(y, BANDS.length)];
    }

    /** Ground column: red sand over red sandstone in the dunes, layered terracotta in the mesas. */
    private static void column(ChunkData data, int lx, int lz, int top) {
        boolean rock = top > 74;
        for (int y = FLOOR; y <= top; y++) {
            Material m;
            if (y < top - 12) m = Material.STONE;
            else if (rock && y > 68) m = band(y);
            else if (y > top - 3) m = Material.RED_SAND;
            else m = Material.RED_SANDSTONE;
            data.setBlock(lx, y, lz, m);
        }
    }

    // ------------------------------------------------------------ chunk

    @Override
    public void generateNoise(WorldInfo info, Random random, int cx, int cz, ChunkData data) {
        noise(info.getSeed());
        for (int lx = 0; lx < 16; lx++) {
            for (int lz = 0; lz < 16; lz++) {
                int x = cx * 16 + lx, z = cz * 16 + lz;
                if (plaza(data, lx, lz, x, z)) continue;
                double h0 = natural(x, z);
                int idx = track.nearest(x, z);
                if (idx < 0) {
                    column(data, lx, lz, (int) Math.floor(h0));
                    crystal(data, lx, lz, x, z, (int) Math.floor(h0), 99);
                    continue;
                }
                double d = track.distance(x, z), hw = RaceTrack.HW;
                double ry = track.y[idx];
                int fy = (int) Math.floor(ry);
                boolean half = ry - fy >= 0.5;
                boolean tun = track.tunnel[idx];
                if (d <= hw + 2) {
                    road(data, lx, lz, x, z, idx, d, fy, half);
                    if (tun) {
                        int top = (int) Math.floor(h0);
                        for (int y = fy + 8; y <= top; y++) data.setBlock(lx, y, lz, band(y));
                        if (d < 1 && idx % 5 == 0) data.setBlock(lx, fy + 8, lz, Material.SEA_LANTERN);
                    } else {
                        arch(data, lx, lz, idx, d, fy, fy);
                    }
                    continue;
                }
                double e = d - (hw + 2);
                if (tun && d <= hw + 3.5) {
                    // tunnel wall with light strips
                    int top = (int) Math.max(Math.floor(h0), fy + 9);
                    column(data, lx, lz, fy);
                    for (int y = fy + 1; y <= top; y++) data.setBlock(lx, y, lz, band(y));
                    if (idx % 6 == 0) data.setBlock(lx, fy + 3, lz, Material.SEA_LANTERN);
                    if (idx % 6 == 3) data.setBlock(lx, fy + 1, lz, Material.PEARLESCENT_FROGLIGHT);
                    continue;
                }
                double h = tun ? Math.max(h0, fy - e) : Math.max(fy - e, Math.min(h0, fy + e * 2.4));
                if (grandstand(data, lx, lz, x, z)) continue;
                int top = (int) Math.floor(h);
                column(data, lx, lz, top);
                if (!tun) arch(data, lx, lz, idx, d, fy, top);
                if (!tun && idx % 34 == 17 && d <= hw + 3.5 && top <= fy + 1 && track.checkpointAt(idx) < 0) lamp(data, lx, lz, top);
                if (d > hw + 9) crystal(data, lx, lz, x, z, top, d);
            }
        }
    }

    private void road(ChunkData data, int lx, int lz, int x, int z, int idx, double d, int fy, boolean half) {
        double hw = RaceTrack.HW;
        for (int y = FLOOR; y < fy; y++) data.setBlock(lx, y, lz, y < fy - 10 ? Material.STONE : Material.RED_SANDSTONE);
        Material full, slab;
        boolean start = idx <= 2 || idx >= track.n - 2;
        if (start) {
            // chequered start / finish line
            int lat = (int) Math.floor(track.lateral(idx, x + 0.5, z + 0.5));
            boolean dark = Math.floorMod(lat + x, 2) == 0;
            full = dark ? Material.BLACK_CONCRETE : Material.WHITE_CONCRETE; slab = null;
        } else if (d > hw) {
            full = Material.SMOOTH_RED_SANDSTONE; slab = Material.SMOOTH_RED_SANDSTONE_SLAB;
        } else if (d > hw - 1.5) {
            full = (idx / 3) % 2 == 0 ? Material.RED_CONCRETE : Material.WHITE_CONCRETE; slab = full;
        } else if (d < 0.6 && idx % 8 < 4) {
            full = Material.SMOOTH_QUARTZ; slab = Material.SMOOTH_QUARTZ_SLAB;
        } else if (track.tunnel[idx] && idx % 12 == 0 && d > hw - 3) {
            full = Material.SEA_LANTERN; slab = Material.POLISHED_BLACKSTONE_SLAB;
        } else {
            full = Material.POLISHED_BLACKSTONE; slab = Material.POLISHED_BLACKSTONE_SLAB;
        }
        data.setBlock(lx, fy, lz, full);
        if (half && slab != null) data.setBlock(lx, fy + 1, lz, slab);
    }

    /** Checkpoint arches: two pillars beside the road and a glowing beam across it. */
    private void arch(ChunkData data, int lx, int lz, int idx, double d, int fy, int ground) {
        int c = track.checkpointAt(idx);
        if (c < 0) return;
        double hw = RaceTrack.HW;
        boolean finish = c == RaceTrack.CHECKPOINTS - 1;
        int height = finish ? 12 : 9;
        Material pillar = finish ? Material.POLISHED_BLACKSTONE_BRICKS : Material.POLISHED_DEEPSLATE;
        Material light = finish ? Material.OCHRE_FROGLIGHT : Material.SEA_LANTERN;
        if (d > hw + 2 && d <= hw + 3.6) {
            for (int y = Math.min(ground, fy) + 1; y <= fy + height; y++) data.setBlock(lx, y, lz, y % 3 == 0 ? light : pillar);
            data.setBlock(lx, fy + height + 1, lz, Material.END_ROD);
        } else if (d <= hw + 2) {
            data.setBlock(lx, fy + height, lz, finish ? Material.ORANGE_STAINED_GLASS : Material.LIGHT_BLUE_STAINED_GLASS);
            data.setBlock(lx, fy + height + 1, lz, light);
        }
    }

    private void lamp(ChunkData data, int lx, int lz, int fy) {
        for (int y = fy + 1; y <= fy + 4; y++) data.setBlock(lx, y, lz, Material.POLISHED_DEEPSLATE_WALL);
        data.setBlock(lx, fy + 5, lz, Material.SHROOMLIGHT);
    }

    // ------------------------------------------------------------ start area

    static final int PLAZA_X0 = -30, PLAZA_X1 = 30, PLAZA_Z0 = 9, PLAZA_Z1 = 36;

    /** Pit plaza south of the start straight: where racers arrive and wait. */
    private boolean plaza(ChunkData data, int lx, int lz, int x, int z) {
        if (x < PLAZA_X0 || x > PLAZA_X1 || z < PLAZA_Z0 || z > PLAZA_Z1) return false;
        int fy = RaceTrack.ROAD_Y;
        for (int y = FLOOR; y < fy; y++) data.setBlock(lx, y, lz, Material.RED_SANDSTONE);
        boolean edge = x == PLAZA_X0 || x == PLAZA_X1 || z == PLAZA_Z1;
        Material floor;
        if (edge) floor = Material.POLISHED_BLACKSTONE_BRICKS;
        else if ((x + z) % 6 == 0 && (x - z) % 6 == 0) floor = Material.SEA_LANTERN;
        else if (Math.abs(x) <= 1 && z < 30) floor = Material.ORANGE_CONCRETE;          // the walkway to the track
        else floor = ((x >> 1) + (z >> 1)) % 2 == 0 ? Material.SMOOTH_QUARTZ : Material.POLISHED_DIORITE;
        data.setBlock(lx, fy, lz, floor);
        if (edge) {
            data.setBlock(lx, fy + 1, lz, Material.POLISHED_BLACKSTONE_WALL);
            if ((x + z) % 8 == 0) { data.setBlock(lx, fy + 2, lz, Material.POLISHED_BLACKSTONE_WALL); data.setBlock(lx, fy + 3, lz, Material.OCHRE_FROGLIGHT); }
        }
        return true;
    }

    private static boolean grandstandFree(int x, int z) {
        return !(x >= -44 && x <= 44 && z >= -24 && z <= -9);
    }

    /** Grandstand on the north side of the start straight: rows of seats rising away from the road. */
    private boolean grandstand(ChunkData data, int lx, int lz, int x, int z) {
        if (grandstandFree(x, z)) return false;
        int fy = RaceTrack.ROAD_Y;
        int row = -9 - z;                                 // 0 at the road side
        int top = fy + 1 + row;
        for (int y = FLOOR; y <= top; y++) data.setBlock(lx, y, lz, y < fy ? Material.RED_SANDSTONE : Material.SMOOTH_QUARTZ);
        Material seat = row % 2 == 0 ? Material.ORANGE_CONCRETE : Material.CYAN_CONCRETE;
        if (Math.abs(x) == 44 || row == 15) seat = Material.POLISHED_BLACKSTONE;
        data.setBlock(lx, top, lz, seat);
        if (row == 15) for (int y = top + 1; y <= top + 6; y++) data.setBlock(lx, y, lz, x % 6 == 0 ? Material.POLISHED_BLACKSTONE_WALL : Material.AIR);
        if (row == 15 && x % 6 == 0) data.setBlock(lx, top + 7, lz, Material.OCHRE_FROGLIGHT);
        if (row >= 12 && row <= 15) data.setBlock(lx, top + 8, lz, Material.ORANGE_STAINED_GLASS);   // canopy
        return true;
    }

    // ------------------------------------------------------------ alien decoration

    private static int hash(int x, int z) {
        int h = x * 73856093 ^ z * 83492791;
        h ^= (h >>> 13); h *= 0x5bd1e995;
        return h ^ (h >>> 15);
    }

    /** Purple crystal spires scattered over the desert (one possible spire per 9×9 cell). */
    private void crystal(ChunkData data, int lx, int lz, int x, int z, int top, double d) {
        if (d <= RaceTrack.HW + 9 || top > 76) return;
        int cellX = Math.floorDiv(x, 9), cellZ = Math.floorDiv(z, 9);
        int h = hash(cellX, cellZ);
        if (Math.floorMod(h, 7) != 0) return;
        int ox = cellX * 9 + 2 + Math.floorMod(h >> 4, 5), oz = cellZ * 9 + 2 + Math.floorMod(h >> 8, 5);
        int height = 4 + Math.floorMod(h >> 12, 7);
        double r = Math.hypot(x - ox, z - oz);
        if (r > 1.6) return;
        int hh = (int) Math.round(height * (1 - r / 2.2));
        if (Math.hypot(ox, oz - 10) < 60) return;          // none at the pit
        for (int y = top + 1; y <= top + hh; y++) data.setBlock(lx, y, lz, (y - top) % 4 == 0 ? Material.PURPLE_STAINED_GLASS : Material.AMETHYST_BLOCK);
        if (hh > 0) data.setBlock(lx, top + hh + 1, lz, Material.AMETHYST_CLUSTER);
    }

    // ------------------------------------------------------------ vanilla off

    @Override public boolean shouldGenerateNoise() { return false; }
    @Override public boolean shouldGenerateSurface() { return false; }
    @Override public boolean shouldGenerateCaves() { return false; }
    @Override public boolean shouldGenerateDecorations() { return false; }
    @Override public boolean shouldGenerateMobs() { return false; }
    @Override public boolean shouldGenerateStructures() { return false; }

    @Override
    public Location getFixedSpawnLocation(World world, Random random) {
        return new Location(world, 0.5, RaceTrack.ROAD_Y + 1, 24.5);
    }

    @Override
    public BiomeProvider getDefaultBiomeProvider(WorldInfo worldInfo) {
        return new BiomeProvider() {
            @Override public Biome getBiome(WorldInfo info, int x, int y, int z) { return Biome.BADLANDS; }
            @Override public List<Biome> getBiomes(WorldInfo info) { return List.of(Biome.BADLANDS); }
        };
    }
}
