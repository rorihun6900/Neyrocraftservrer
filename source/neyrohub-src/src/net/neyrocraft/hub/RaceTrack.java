package net.neyrocraft.hub;

/**
 * Geometry of the speeder circuit on planet Terrax: a closed Catmull-Rom loop through the desert,
 * resampled every block of arc length, plus a lookup grid "nearest sample / distance" for every column
 * near the track (used by the terrain generator and by the race logic).
 *
 * Start/finish line is sample 0 at (0, 0), heading east (+X). The loop runs clockwise seen from above:
 * east along the start straight, a long right-hander up a ridge, downhill esses, a chicane, a tunnel
 * through the great mesa, a jump, and back onto the start straight.
 */
final class RaceTrack {
    static final double HW = 6.5;                    // half width of the road
    static final int ROAD_Y = 70;                    // height of the start straight (block level of the road)
    static final int CHECKPOINTS = 12;               // including the finish line
    static final int GRID_R = 34;                    // lookup radius around the centre line

    // control points: x, z, road height
    private static final double[][] CTRL = {
            {0, 0, 70}, {120, 0, 70}, {220, -28, 71}, {278, -108, 74}, {262, -205, 79}, {178, -266, 82},
            {82, -250, 79}, {14, -196, 74}, {-48, -222, 71}, {-130, -262, 70}, {-228, -236, 70},
            {-292, -150, 72}, {-290, -60, 75}, {-232, -10, 71}, {-118, 0, 70}};

    // the great mesa the tunnel goes through
    static final double MESA_X = -262, MESA_Z = -196, MESA_R = 46;
    static final int MESA_TOP = 104;

    final int n;
    final double[] x, z, y, dirX, dirZ;
    final boolean[] tunnel;
    final int[] cps;                                 // sample of every checkpoint; the last one is n (finish)
    final int jumpStart, jumpPeak, jumpEnd;

    // lookup grid
    final int gx0, gz0, gw, gh;
    private final int[] near;
    private final float[] dist;

    RaceTrack() {
        // dense spline
        int seg = CTRL.length, sub = 200;
        double[] sx = new double[seg * sub + 1], sz = new double[sx.length], sy = new double[sx.length];
        for (int i = 0; i < seg; i++) {
            double[] p0 = CTRL[(i - 1 + seg) % seg], p1 = CTRL[i], p2 = CTRL[(i + 1) % seg], p3 = CTRL[(i + 2) % seg];
            for (int j = 0; j < sub; j++) {
                double t = j / (double) sub;
                int k = i * sub + j;
                sx[k] = cr(p0[0], p1[0], p2[0], p3[0], t);
                sz[k] = cr(p0[1], p1[1], p2[1], p3[1], t);
                sy[k] = cr(p0[2], p1[2], p2[2], p3[2], t);
            }
        }
        sx[seg * sub] = sx[0]; sz[seg * sub] = sz[0]; sy[seg * sub] = sy[0];
        double[] len = new double[sx.length];
        for (int k = 1; k < sx.length; k++) len[k] = len[k - 1] + Math.hypot(sx[k] - sx[k - 1], sz[k] - sz[k - 1]);
        double total = len[len.length - 1];
        n = (int) Math.floor(total);
        x = new double[n]; z = new double[n]; y = new double[n]; dirX = new double[n]; dirZ = new double[n];
        int k = 0;
        for (int i = 0; i < n; i++) {
            double target = i * total / n;
            while (k < len.length - 2 && len[k + 1] < target) k++;
            double f = (target - len[k]) / Math.max(1e-9, len[k + 1] - len[k]);
            x[i] = sx[k] + (sx[k + 1] - sx[k]) * f;
            z[i] = sz[k] + (sz[k + 1] - sz[k]) * f;
            y[i] = sy[k] + (sy[k + 1] - sy[k]) * f;
        }
        for (int i = 0; i < n; i++) {
            int a = (i - 2 + n) % n, b = (i + 2) % n;
            double dx = x[b] - x[a], dz = z[b] - z[a], l = Math.max(1e-9, Math.hypot(dx, dz));
            dirX[i] = dx / l; dirZ[i] = dz / l;
        }
        // the jump: a ramp, then the ground drops away
        int js = nearestGlobal(-291, -104);
        jumpStart = js; jumpPeak = (js + 28) % n; jumpEnd = (js + 34) % n;
        for (int i = 0; i < 34; i++) {
            int s = (js + i) % n;
            y[s] += i < 28 ? 3.5 * i / 28.0 : 3.5 * (1 - (i - 28) / 6.0);
        }
        // start straight perfectly flat
        for (int i = -40; i <= 40; i++) y[(i + n) % n] = ROAD_Y;
        tunnel = new boolean[n];
        for (int i = 0; i < n; i++) tunnel[i] = mesaHeight(x[i], z[i]) >= Math.floor(y[i]) + 11;
        cps = new int[CHECKPOINTS];
        for (int c = 0; c < CHECKPOINTS; c++) cps[c] = (c + 1) * n / CHECKPOINTS;
        cps[CHECKPOINTS - 1] = n;

        // lookup grid
        double minX = 1e9, maxX = -1e9, minZ = 1e9, maxZ = -1e9;
        for (int i = 0; i < n; i++) { minX = Math.min(minX, x[i]); maxX = Math.max(maxX, x[i]); minZ = Math.min(minZ, z[i]); maxZ = Math.max(maxZ, z[i]); }
        gx0 = (int) Math.floor(minX) - GRID_R - 1; gz0 = (int) Math.floor(minZ) - GRID_R - 1;
        gw = (int) Math.ceil(maxX) + GRID_R + 2 - gx0; gh = (int) Math.ceil(maxZ) + GRID_R + 2 - gz0;
        near = new int[gw * gh];
        dist = new float[gw * gh];
        java.util.Arrays.fill(near, -1);
        java.util.Arrays.fill(dist, Float.MAX_VALUE);
        for (int i = 0; i < n; i++) {
            int cx = (int) Math.floor(x[i]), cz = (int) Math.floor(z[i]);
            for (int dx = -GRID_R; dx <= GRID_R; dx++) {
                for (int dz = -GRID_R; dz <= GRID_R; dz++) {
                    int bx = cx + dx, bz = cz + dz;
                    double ddx = bx + 0.5 - x[i], ddz = bz + 0.5 - z[i];
                    double d2 = ddx * ddx + ddz * ddz;
                    if (d2 > GRID_R * GRID_R) continue;
                    int gi = (bx - gx0) * gh + (bz - gz0);
                    float d = (float) Math.sqrt(d2);
                    if (d < dist[gi]) { dist[gi] = d; near[gi] = i; }
                }
            }
        }
    }

    private static double cr(double p0, double p1, double p2, double p3, double t) {
        double t2 = t * t, t3 = t2 * t;
        return 0.5 * ((2 * p1) + (-p0 + p2) * t + (2 * p0 - 5 * p1 + 4 * p2 - p3) * t2 + (-p0 + 3 * p1 - 3 * p2 + p3) * t3);
    }

    /** Height of the great mesa (0 outside it). */
    static double mesaHeight(double px, double pz) {
        double d = Math.hypot(px - MESA_X, pz - MESA_Z);
        if (d < MESA_R) return MESA_TOP - Math.floor(Math.max(0, d - MESA_R + 10) / 3.0) * 3;
        if (d < MESA_R + 16) return MESA_TOP - 10 - (d - MESA_R) * 2.2;
        return 0;
    }

    /** Nearest sample of a block column, or -1 when farther than {@link #GRID_R}. */
    int nearest(int bx, int bz) {
        int gxi = bx - gx0, gzi = bz - gz0;
        if (gxi < 0 || gzi < 0 || gxi >= gw || gzi >= gh) return -1;
        return near[gxi * gh + gzi];
    }

    double distance(int bx, int bz) {
        int gxi = bx - gx0, gzi = bz - gz0;
        if (gxi < 0 || gzi < 0 || gxi >= gw || gzi >= gh) return Double.MAX_VALUE;
        return dist[gxi * gh + gzi];
    }

    /** Nearest sample to a point by brute force. */
    int nearestGlobal(double px, double pz) {
        int best = 0; double bd = Double.MAX_VALUE;
        for (int i = 0; i < n; i++) {
            double d = (x[i] - px) * (x[i] - px) + (z[i] - pz) * (z[i] - pz);
            if (d < bd) { bd = d; best = i; }
        }
        return best;
    }

    /** Nearest sample searched around a previous one (follows a racer cheaply). */
    int nearestAround(double px, double pz, int around, int back, int ahead) {
        int best = around; double bd = Double.MAX_VALUE;
        for (int o = -back; o <= ahead; o++) {
            int i = Math.floorMod(around + o, n);
            double d = (x[i] - px) * (x[i] - px) + (z[i] - pz) * (z[i] - pz);
            if (d < bd) { bd = d; best = i; }
        }
        return best;
    }

    /** Signed lateral offset of a point from the centre line at a sample (+ = right of the driving direction). */
    double lateral(int i, double px, double pz) {
        return (px - x[i]) * -dirZ[i] + (pz - z[i]) * dirX[i];
    }

    /** Driving direction at a sample as a Minecraft yaw. */
    float yaw(int i) {
        return (float) Math.toDegrees(Math.atan2(-dirX[i], dirZ[i]));
    }

    /** Index of the checkpoint near this sample (within ±1), or -1. */
    int checkpointAt(int i) {
        for (int c = 0; c < CHECKPOINTS; c++) {
            int s = cps[c] % n;
            int d = Math.abs(i - s);
            d = Math.min(d, n - d);
            if (d <= 1) return c;
        }
        return -1;
    }

    double length() { return n; }
}
