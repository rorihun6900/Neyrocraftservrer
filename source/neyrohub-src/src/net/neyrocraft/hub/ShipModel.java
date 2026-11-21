package net.neyrocraft.hub;

import org.bukkit.Bukkit;
import org.bukkit.ChunkSnapshot;
import org.bukkit.Material;
import org.bukkit.World;
import org.bukkit.block.data.BlockData;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.logging.Logger;
import java.util.zip.GZIPInputStream;

/**
 * The hull of the starship NEYRO-1 around the main deck: modelled in Blender
 * (source/ship-src), turned into blocks by voxelize.py and shipped inside the jar as
 * {@code ship/neyro1.ship.gz}.
 * <p>
 * File (gzip, UTF-8 text): {@code NEYRO_SHIP 1}, {@code bounds x0 dy0 z0 x1 dy1 z1},
 * {@code palette n} + n block data strings, {@code runs m} + m lines {@code x dy z length index}
 * (a run of {@code length} equal blocks along +x; {@code dy} is relative to the deck floor).
 */
final class ShipModel {

    static final String RESOURCE = "ship/neyro1.ship.gz";

    private final BlockData[] palette;
    private final int[] runs;                 // x, dy, z, length, index per run
    final int minX, minDy, minZ, maxX, maxDy, maxZ;
    final int blocks;

    private ShipModel(BlockData[] palette, int[] runs, int[] bounds, int blocks) {
        this.palette = palette;
        this.runs = runs;
        this.minX = bounds[0]; this.minDy = bounds[1]; this.minZ = bounds[2];
        this.maxX = bounds[3]; this.maxDy = bounds[4]; this.maxZ = bounds[5];
        this.blocks = blocks;
    }

    /** The complete ship (with the deck, dome and core) that hangs in the sky over the survival city. */
    static final String SKY_RESOURCE = "ship/neyro1_sky.ship.gz";

    /** Reads the hub model from the jar; null (with a log line) when it is missing or broken. */
    static ShipModel load(NeyroHub plugin) {
        return load(plugin, RESOURCE);
    }

    static ShipModel load(NeyroHub plugin, String resource) {
        Logger log = plugin.getLogger();
        try (InputStream raw = plugin.getResource(resource)) {
            if (raw == null) {
                log.warning("Ship model " + resource + " is not in the jar");
                return null;
            }
            BufferedReader in = new BufferedReader(new InputStreamReader(new GZIPInputStream(raw), StandardCharsets.UTF_8));
            if (!"NEYRO_SHIP 1".equals(in.readLine())) throw new IOException("unknown format");
            int[] bounds = numbers(in.readLine(), "bounds", 6);
            int n = numbers(in.readLine(), "palette", 1)[0];
            BlockData[] palette = new BlockData[n];
            for (int i = 0; i < n; i++) {
                String id = in.readLine().trim();
                try {
                    palette[i] = Bukkit.createBlockData(id);
                } catch (IllegalArgumentException e) {
                    log.warning("Ship model: unknown block '" + id + "', using white concrete");
                    palette[i] = Material.WHITE_CONCRETE.createBlockData();
                }
            }
            int m = numbers(in.readLine(), "runs", 1)[0];
            int[] runs = new int[m * 5];
            int blocks = 0;
            for (int r = 0; r < m; r++) {
                String[] p = in.readLine().trim().split(" ");
                for (int k = 0; k < 5; k++) runs[r * 5 + k] = Integer.parseInt(p[k]);
                if (runs[r * 5 + 4] < 0 || runs[r * 5 + 4] >= n) throw new IOException("bad palette index in run " + r);
                blocks += runs[r * 5 + 3];
            }
            return new ShipModel(palette, runs, bounds, blocks);
        } catch (IOException | RuntimeException e) {
            log.severe("Could not read the ship model: " + e);
            return null;
        }
    }

    private static int[] numbers(String line, String key, int count) throws IOException {
        if (line == null || !line.startsWith(key + " ")) throw new IOException("expected '" + key + "'");
        String[] p = line.substring(key.length() + 1).trim().split(" ");
        if (p.length != count) throw new IOException("bad '" + key + "' line");
        int[] out = new int[count];
        for (int i = 0; i < count; i++) out[i] = Integer.parseInt(p[i]);
        return out;
    }

    /** Places every block of the hull; {@code floorY} is the deck floor. */
    void place(World w, int floorY) {
        placeSome(w, 0, floorY, 0, 0, Long.MAX_VALUE);
    }

    int runCount() {
        return runs.length / 5;
    }

    /**
     * Places runs starting at {@code fromRun} with the deck centre at (ox, floorY, oz) until
     * {@code deadline} (System.nanoTime) passes; returns the next run to place (runCount() when done).
     */
    int placeSome(World w, int ox, int floorY, int oz, int fromRun, long deadline) {
        int r = fromRun * 5;
        for (; r < runs.length; r += 5) {
            int x = ox + runs[r], y = floorY + runs[r + 1], z = oz + runs[r + 2], len = runs[r + 3];
            BlockData d = palette[runs[r + 4]];
            for (int i = 0; i < len; i++) w.getBlockAt(x + i, y, z).setBlockData(d, false);
            if (((r / 5) & 63) == 63 && System.nanoTime() > deadline) return r / 5 + 1;
        }
        return runs.length / 5;
    }

    /** Sets every non-air block in the box to air, chunk by chunk (fast for a mostly empty void). */
    static int clear(World w, int x0, int y0, int z0, int x1, int y1, int z1) {
        BlockData air = Material.AIR.createBlockData();
        int cleared = 0;
        for (int cx = x0 >> 4; cx <= x1 >> 4; cx++) {
            for (int cz = z0 >> 4; cz <= z1 >> 4; cz++) {
                ChunkSnapshot snap = w.getChunkAt(cx, cz).getChunkSnapshot(false, false, false);
                int bx0 = Math.max(x0, cx << 4), bx1 = Math.min(x1, (cx << 4) + 15);
                int bz0 = Math.max(z0, cz << 4), bz1 = Math.min(z1, (cz << 4) + 15);
                for (int y = y0; y <= y1; y++) {
                    for (int x = bx0; x <= bx1; x++)
                        for (int z = bz0; z <= bz1; z++)
                            if (!snap.getBlockType(x & 15, y, z & 15).isAir()) {
                                w.getBlockAt(x, y, z).setBlockData(air, false);
                                cleared++;
                            }
                }
            }
        }
        return cleared;
    }
}
