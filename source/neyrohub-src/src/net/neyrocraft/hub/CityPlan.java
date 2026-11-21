package net.neyrocraft.hub;

import org.bukkit.Bukkit;
import org.bukkit.block.data.BlockData;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Random;

/**
 * The layout of Neyro-City, the cyberpunk city at the survival spawn: a pure function from a
 * position relative to the plaza centre (dx, h, dz; h = 0 is the street surface) to a block.
 * <p>
 * Centre: a round plaza with the light beam (lift to the ship). Four avenues lead from it to the
 * gates of the city wall, cross streets and a ring street divide the rest into lots with
 * skyscrapers. Eight shop pavilions stand around the plaza.
 */
final class CityPlan {

    static final int HALF = 64;            // outer face of the city wall
    static final int WALL = 63;            // inner face of the wall
    static final int AVE = 4;              // avenues: |dx| <= 4 or |dz| <= 4
    static final int EXT = 76;             // the avenues go on outside the gates up to here
    static final int GATE = 7;             // half width of the avenue outside the gates (road + sidewalks)
    static final int SKIRT = 32;           // around the wall the land slopes smoothly back to nature
    static final int APRON = 6;            // ...after a flat ring this wide
    static final int PLAZA_R = 18;
    static final int TOP = 64;             // nothing of the city is higher than this
    static final int CROSS = 32;           // cross streets at |dx| or |dz| = 30..34
    static final int RING = 60;            // ring street along the wall: 58..62

    private static final Map<String, BlockData> CACHE = new HashMap<>();

    static BlockData bd(String id) {
        return CACHE.computeIfAbsent(id, Bukkit::createBlockData);
    }

    static final String AIR = "air";

    /** Colours of a skyscraper. */
    record Style(String wall, String glass, String neon, String band, String[] lights) {}

    static final Style[] STYLES = {
            new Style("black_concrete", "cyan_stained_glass", "pearlescent_froglight", "magenta_concrete",
                    new String[]{"sea_lantern", "pearlescent_froglight"}),
            new Style("gray_concrete", "light_blue_stained_glass", "sea_lantern", "cyan_concrete",
                    new String[]{"sea_lantern", "ochre_froglight"}),
            new Style("polished_blackstone", "purple_stained_glass", "crying_obsidian", "purple_concrete",
                    new String[]{"pearlescent_froglight", "sea_lantern"}),
            new Style("deepslate_tiles", "magenta_stained_glass", "verdant_froglight", "lime_concrete",
                    new String[]{"verdant_froglight", "pearlescent_froglight"}),
            new Style("black_concrete", "orange_stained_glass", "ochre_froglight", "orange_concrete",
                    new String[]{"ochre_froglight", "shroomlight"}),
            new Style("cyan_terracotta", "black_stained_glass", "sea_lantern", "light_blue_concrete",
                    new String[]{"sea_lantern", "ochre_froglight"}),
            new Style("gray_concrete", "pink_stained_glass", "pearlescent_froglight", "pink_concrete",
                    new String[]{"pearlescent_froglight", "ochre_froglight"}),
    };
    /** NEYRO-CORP: the brand colours. */
    static final Style HQ_STYLE = new Style("black_concrete", "light_blue_stained_glass", "shroomlight", "orange_concrete",
            new String[]{"ochre_froglight", "sea_lantern"});

    /** A skyscraper: tier 1 up to h1, optionally a narrower tier 2 (inset) up to h2, then an antenna. */
    record Tower(int index, int x0, int z0, int x1, int z1, int h1, int h2, int inset, Style s, boolean hq) {
        int top() { return h2 > h1 ? h2 : h1; }
        int cx() { return (x0 + x1) / 2; }
        int cz() { return (z0 + z1) / 2; }
    }

    /** A shop pavilion: 9 wide, 7 deep, open front facing (fx, fz) towards the plaza. */
    record Shop(CityShops.Type type, int cx, int cz, int fx, int fz) {
        /** Local (u across the front, d depth from the front wall) or null outside. */
        int[] local(int dx, int dz) {
            int px = dx - cx, pz = dz - cz;
            int d = 3 - (px * fx + pz * fz);
            int u = px * -fz + pz * fx;
            if (Math.abs(u) > 4 || d < -1 || d > 6) return null;
            return new int[]{u, d};
        }

        /** Plaza-relative position at local (u, d). */
        double[] at(double u, double d) {
            double pf = 3 - d;
            return new double[]{cx + fx * pf + -fz * u, cz + fz * pf + fx * u};
        }

        float yaw() {
            return (float) Math.toDegrees(Math.atan2(-fx, fz));
        }
    }

    final List<Tower> towers = new ArrayList<>();
    final List<Shop> shops = new ArrayList<>();

    List<Tower> towers() { return towers; }

    List<Shop> shops() { return shops; }

    /** Plaza pylons (neon columns). */
    static final int[][] PYLONS = {{14, 6}, {6, 14}, {-6, 14}, {-14, 6}, {-14, -6}, {-6, -14}, {6, -14}, {14, -6}};

    CityPlan() {
        Random r = new Random(304);
        int[][] quads = {{1, -1}, {1, 1}, {-1, 1}, {-1, -1}};
        int shop = 0;
        for (int[] q : quads) {
            int sx = q[0], sz = q[1];
            shops.add(new Shop(CityShops.TYPES[shop++], sx * 13, sz * 23, 0, -sz));
            shops.add(new Shop(CityShops.TYPES[shop++], sx * 23, sz * 13, -sx, 0));
            boolean hq = sx == 1 && sz == -1;          // NEYRO-CORP stands in the north-east
            tower(r, sx, sz, 20, 28, 20, 28, 26, 44, null);
            tower(r, sx, sz, 36, 45, 8, 27, 16, 36, null);
            tower(r, sx, sz, 49, 56, 8, 27, 14, 30, null);
            tower(r, sx, sz, 8, 27, 36, 45, 16, 36, null);
            tower(r, sx, sz, 8, 27, 49, 56, 14, 30, null);
            if (hq) tower(r, sx, sz, 37, 56, 37, 56, 40, 40, HQ_STYLE);
            else tower(r, sx, sz, 37, 56, 37, 56, 24, 46, null);
        }
    }

    /** Adds a tower on the lot |dx| in [a0, a1], |dz| in [b0, b1] of quadrant (sx, sz). */
    private void tower(Random r, int sx, int sz, int a0, int a1, int b0, int b1, int hMin, int hMax, Style hq) {
        int x0 = Math.min(sx * a0, sx * a1), x1 = Math.max(sx * a0, sx * a1);
        int z0 = Math.min(sz * b0, sz * b1), z1 = Math.max(sz * b0, sz * b1);
        int h1 = hMin + r.nextInt(hMax - hMin + 1);
        h1 = h1 - h1 % 4;                                   // whole floors
        int h2 = h1, inset = 0;
        if (hq != null) {
            h2 = 56; inset = 4;
        } else if (r.nextDouble() < 0.5 && Math.min(x1 - x0, z1 - z0) >= 9) {
            inset = 2 + r.nextInt(2);
            h2 = Math.min(56, h1 + 8 + 4 * r.nextInt(3));
        }
        Style s = hq != null ? hq : STYLES[r.nextInt(STYLES.length)];
        towers.add(new Tower(towers.size(), x0, z0, x1, z1, h1, h2, inset, s, hq != null));
    }

    // ------------------------------------------------------------------ the field

    static boolean inFootprint(int dx, int dz) {
        int a = Math.abs(dx), b = Math.abs(dz);
        if (a <= HALF && b <= HALF) return true;
        return (a <= GATE && b <= EXT) || (b <= GATE && a <= EXT);
    }

    /** Inside the city wall. */
    static boolean insideWall(int dx, int dz) {
        return Math.abs(dx) <= HALF && Math.abs(dz) <= HALF;
    }

    /** Block id at plaza-relative position; null where the city leaves the world as it is. */
    String id(int dx, int h, int dz) {
        if (!inFootprint(dx, dz) || h < -3) return null;
        if (h > TOP) return AIR;
        if (h < 0) {
            if (h == -1 && surface(dx, dz).endsWith("stained_glass")) return "sea_lantern";   // glowing lines
            return h == -1 ? "stone_bricks" : "stone";
        }
        if (h == 0) return surface(dx, dz);
        String s = structure(dx, h, dz);
        return s == null ? AIR : s;
    }

    BlockData block(int dx, int h, int dz) {
        String id = id(dx, h, dz);
        return id == null ? null : bd(id);
    }

    String surface(int dx, int dz) {
        int a = Math.abs(dx), b = Math.abs(dz);
        if (a > HALF || b > HALF) {                                       // the avenue outside the gate
            int across = a <= GATE ? a : b, along = a <= GATE ? dz : dx;
            if (across <= AVE) return a <= GATE ? road(dx, dz) : road(dz, dx);
            return across == GATE ? "polished_blackstone" : "polished_andesite";
        }
        double r = Math.sqrt(dx * dx + dz * dz);
        if (r <= 2.5) return "light_blue_stained_glass";                  // the lift pad
        if (r <= PLAZA_R + 0.5) {
            if (r >= 6.5 && r < 7.5) return "cyan_stained_glass";
            if (r >= 17 && r < 18) return "magenta_stained_glass";
            if ((dx == 0 || dz == 0 || a == b) && r > 7.5 && r < 17) return "black_concrete";
            return ((dx + dz) & 1) == 0 ? "polished_deepslate" : "deepslate_tiles";
        }
        if (a >= WALL || b >= WALL) return "polished_blackstone_bricks";
        for (Shop s : shops) {
            int[] l = s.local(dx, dz);
            if (l != null && l[1] >= 0) return "polished_blackstone_bricks";
        }
        if (a <= AVE) return road(dx, dz);
        if (b <= AVE) return road(dz, dx);
        if (Math.abs(a - CROSS) <= 2) return street(a - CROSS, dz, "magenta_stained_glass");
        if (Math.abs(b - CROSS) <= 2) return street(b - CROSS, dx, "magenta_stained_glass");
        if (Math.abs(a - RING) <= 2) return street(a - RING, dz, "purple_stained_glass");
        if (Math.abs(b - RING) <= 2) return street(b - RING, dx, "purple_stained_glass");
        return (Math.floorMod(dx, 4) == 0 || Math.floorMod(dz, 4) == 0) ? "polished_blackstone_bricks" : "polished_blackstone";
    }

    /** Avenue cross-section: asphalt with a glowing centre line, curbs, sidewalks. */
    private static String road(int across, int along) {
        int c = Math.abs(across);
        if (c <= 2) return c == 0 && Math.floorMod(along, 6) < 3 ? "light_blue_stained_glass" : "gray_concrete";
        if (c == 3) return "polished_blackstone";
        return "polished_andesite";
    }

    private static String street(int c, int along, String line) {
        if (Math.abs(c) <= 1) return c == 0 && Math.floorMod(along, 6) < 3 ? line : "gray_concrete";
        return "polished_andesite";
    }

    String structure(int dx, int h, int dz) {
        int a = Math.abs(dx), b = Math.abs(dz);
        if (a > HALF || b > HALF) {                                // the avenues outside the gates
            int across = a <= GATE ? a : b, out = a <= GATE ? b : a;
            if (across >= AVE + 1 && out <= HALF + 2) return gateTower(across, h);   // towers stick out of the wall
            if (across == GATE - 1 && (out == 70 || out == 75)) {                    // lamps along the road
                if (h <= 4) return "polished_blackstone_wall";
                if (h == 5) return "sea_lantern";
            }
            return null;
        }
        if (a >= WALL || b >= WALL) return wall(dx, h, dz);
        for (Shop s : shops) {
            int[] l = s.local(dx, dz);
            if (l != null) return shop(s, l[0], l[1], h);
        }
        for (Tower t : towers) {
            if (dx >= t.x0 && dx <= t.x1 && dz >= t.z0 && dz <= t.z1) return tower(t, dx, h, dz);
        }
        String l = lamp(dx, h, dz);
        if (l != null) return l;
        for (int i = 0; i < PYLONS.length; i++) {
            if (PYLONS[i][0] == dx && PYLONS[i][1] == dz) {
                if (h <= 3) return "polished_blackstone_bricks";
                if (h == 4) return i % 2 == 0 ? "sea_lantern" : "pearlescent_froglight";
                if (h == 5) return "end_rod";
            }
        }
        return null;
    }

    // ------------------------------------------------------------------ wall and gates

    private static String wall(int dx, int h, int dz) {
        int a = Math.abs(dx), b = Math.abs(dz);
        boolean outer = a == HALF || b == HALF;
        int along = a >= WALL ? dz : dx;
        int o = Math.abs(along);
        if (a >= WALL && b >= WALL) o = HALF;                       // corners are plain wall
        if (o <= AVE) {                                             // gate opening with a lintel and a neon arch
            if (h <= 7) return null;
            if (h == 8) return "magenta_concrete";
            if (h == 9) return Math.floorMod(along, 2) == 0 ? "sea_lantern" : "black_concrete";
            if (h == 10 || h == 12) return "black_concrete";
            if (h == 13) return Math.floorMod(along, 2) == 0 ? "sea_lantern" : "cyan_concrete";
            return null;
        }
        if (o <= GATE) return gateTower(o, h);                      // gate towers
        if (h <= 9) {
            if (h == 9) return Math.floorMod(along, 4) == 0 ? "sea_lantern" : "cyan_concrete";
            if (h == 6) return "magenta_concrete";
            return "black_concrete";
        }
        if (h == 10 && outer && Math.floorMod(along, 2) == 0) return "black_concrete";
        return null;
    }

    /** A gate tower (3 wide, |along| 5..7): dark with neon stripes, a beacon on top. */
    private static String gateTower(int o, int h) {
        if (h <= 14) {
            if (h % 3 == 0) return o == AVE + 2 ? "sea_lantern" : "pearlescent_froglight";
            return o == AVE + 2 ? "polished_blackstone" : "black_concrete";
        }
        if (h == 15 && o == AVE + 2) return "end_rod";
        return null;
    }

    // ------------------------------------------------------------------ shops

    private static String shop(Shop s, int u, int d, int h) {
        CityShops.Type t = s.type();
        if (h > 7) return null;
        if (d == -1) return h == 4 ? t.awning() : null;               // awning over the entrance (sign above it)
        boolean side = Math.abs(u) == 4, front = d == 0, back = d == 6;
        if (h == 7) return front ? t.glow() : "black_concrete";
        if (side && (front || back)) return t.glow();                  // neon corners
        if (front) {
            if (h <= 4) return null;                                   // open shop front
            if (h == 5) return t.accent();                             // sign band
            return (u & 1) == 0 ? t.glow() : t.accent();
        }
        if (side) return h >= 2 && h <= 4 && d >= 2 && d <= 4 ? "black_stained_glass" : "black_concrete";
        if (back) return "black_concrete";
        if (h == 6) return Math.floorMod(u + d, 3) == 0 ? t.glow() : "black_concrete";
        if (d == 2 && h == 1) return "polished_blackstone";            // counter
        if (d == 5 && h <= 3) return (u & 1) == 0 ? "barrel" : t.shelf();
        return null;
    }

    // ------------------------------------------------------------------ towers

    private String tower(Tower t, int dx, int h, int dz) {
        String s = tier(t, t.x0, t.z0, t.x1, t.z1, 0, t.h1, dx, h, dz, true);
        if (s != null) return s;
        if (t.h2 > t.h1) {
            int i = t.inset;
            s = tier(t, t.x0 + i, t.z0 + i, t.x1 - i, t.z1 - i, t.h1 + 1, t.h2, dx, h, dz, false);
            if (s != null) return s;
        }
        int top = t.top();
        int ant = 3 + t.index % 5 + (t.hq ? 4 : 0);
        if (dx == t.cx() && dz == t.cz() && h > top + 1 && h <= Math.min(TOP, top + 1 + ant))
            return h == Math.min(TOP, top + 1 + ant) ? "end_rod" : "polished_blackstone_wall";
        return null;
    }

    /** One tier: hollow box from hb (exclusive) to ht with facade, roof (ht+1) and parapet (ht+2). */
    private String tier(Tower t, int x0, int z0, int x1, int z1, int hb, int ht, int dx, int h, int dz, boolean ground) {
        if (dx < x0 || dx > x1 || dz < z0 || dz > z1 || h <= hb || h > ht + 2) return null;
        boolean ex = dx == x0 || dx == x1, ez = dz == z0 || dz == z1;
        if (h == ht + 2) return ex || ez ? (ex && ez ? t.s.neon() : t.s.wall()) : null;
        if (h == ht + 1) return t.s.wall();
        if (ex || ez) return facade(t, x0, z0, x1, z1, hb, ht, dx, h, dz, ground);
        // inside: a lit (or dark) room right behind every window, the rest is hollow
        int[][] nb = {{dx - 1, dz}, {dx + 1, dz}, {dx, dz - 1}, {dx, dz + 1}};
        for (int[] n : nb) {
            boolean nex = n[0] == x0 || n[0] == x1, nez = n[1] == z0 || n[1] == z1;
            if (!nex && !nez) continue;
            String f = facade(t, x0, z0, x1, z1, hb, ht, n[0], h, n[1], ground);
            if (f.endsWith("stained_glass")) {
                int roll = hash(dx, h / 4, dz, t.index);
                boolean store = ground && h <= 3;
                if (roll < (store ? 750 : 450)) return t.s.lights()[roll % t.s.lights().length];
                return "black_concrete";
            }
        }
        return null;
    }

    private static String facade(Tower t, int x0, int z0, int x1, int z1, int hb, int ht, int dx, int h, int dz, boolean ground) {
        boolean ex = dx == x0 || dx == x1, ez = dz == z0 || dz == z1;
        if (ex && ez) return t.s.neon();                               // neon corner strips
        if (h == ht) return t.s.band();                                // neon band at the top of the tier
        int along = ex ? dz : dx;
        if (ground && h <= 4) {                                        // street level: shop windows
            if (h == 4) return t.s.band();
            return Math.floorMod(along, 4) == 0 ? t.s.wall() : "black_stained_glass";
        }
        int lh = h - hb;
        if (lh % 4 == 0) return t.s.wall();                            // floor slabs
        return Math.floorMod(along, 3) == 0 ? t.s.wall() : t.s.glass();
    }

    // ------------------------------------------------------------------ street lamps

    private static String lamp(int dx, int h, int dz) {
        String s = lampOnAvenue(dx, dz, h);
        return s != null ? s : lampOnAvenue(dz, dx, h);
    }

    /** Lamps along the avenue at across = 0: every 10 blocks from 22 to 52, on alternating sides. */
    private static String lampOnAvenue(int across, int along, int h) {
        int b = Math.abs(along);
        if (b < 22 || b > 52 || (b - 22) % 10 != 0) return null;
        int side = ((b - 22) / 10) % 2 == 0 ? 1 : -1;
        if (across != side * AVE) return null;
        if (h <= 4) return "polished_blackstone_wall";
        if (h == 5) return (b / 10) % 2 == 0 ? "sea_lantern" : "pearlescent_froglight";
        return null;
    }

    static int hash(int x, int y, int z, int salt) {
        int h = x * 73856093 ^ y * 19349663 ^ z * 83492791 ^ salt * 2654435;
        h ^= (h >>> 13);
        h *= 0x5bd1e995;
        return Math.floorMod(h ^ (h >>> 15), 1000);
    }
}
