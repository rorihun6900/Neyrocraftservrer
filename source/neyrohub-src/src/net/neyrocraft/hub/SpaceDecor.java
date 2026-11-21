package net.neyrocraft.hub;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.TextComponent;
import net.kyori.adventure.text.format.TextColor;
import org.bukkit.Color;
import org.bukkit.Location;
import org.bukkit.Particle;
import org.bukkit.World;
import org.bukkit.entity.Display;
import org.bukkit.entity.TextDisplay;
import org.bukkit.util.Transformation;
import org.joml.AxisAngle4f;
import org.joml.Vector3f;

import java.util.ArrayList;
import java.util.List;
import java.util.Random;

/** Painted planets (pixel art on text displays) and stars rushing past the ship. */
final class SpaceDecor {

    enum Kind { TERRA, MARS, GAS, ICE, MOON }

    private static final int ROWS = 26;

    private SpaceDecor() {
    }

    /** Spawns a planet facing the viewer. {@code size} is roughly its diameter in blocks. */
    static List<TextDisplay> planet(NeyroHub plugin, World w, Location at, Kind kind, float size, long seed) {
        w.setChunkForceLoaded(at.getBlockX() >> 4, at.getBlockZ() >> 4, true);
        List<TextDisplay> out = new ArrayList<>();
        float s = size / 4.0f;                                  // ~26 glyphs ≈ 4 blocks at scale 1
        // ring behind / in front of a gas giant
        if (kind == Kind.GAS) out.add(ring(plugin, w, at.clone().add(0, size * 0.18, 0), size, 0xC9A27A));
        TextDisplay body = w.spawn(at, TextDisplay.class, d -> {
            plugin.prepare(d);
            d.setBillboard(Display.Billboard.CENTER);
            d.setBackgroundColor(Color.fromARGB(0, 0, 0, 0));
            d.setShadowed(false);
            d.setSeeThrough(false);
            d.setAlignment(TextDisplay.TextAlignment.CENTER);
            d.setLineWidth(1000);
            d.setBrightness(new Display.Brightness(15, 15));
            d.setViewRange(8f);
            d.setTransformation(new Transformation(new Vector3f(), new AxisAngle4f(),
                    new Vector3f(s, s * 0.72f, s), new AxisAngle4f()));
            d.text(paint(kind, seed));
        });
        out.add(body);
        return out;
    }

    private static TextDisplay ring(NeyroHub plugin, World w, Location at, float size, int color) {
        return w.spawn(at, TextDisplay.class, d -> {
            plugin.prepare(d);
            d.setBillboard(Display.Billboard.CENTER);
            d.setBackgroundColor(Color.fromARGB(0, 0, 0, 0));
            d.setShadowed(false);
            d.setAlignment(TextDisplay.TextAlignment.CENTER);
            d.setLineWidth(2000);
            d.setBrightness(new Display.Brightness(15, 15));
            d.setViewRange(8f);
            float s = size / 4.0f;
            d.setTransformation(new Transformation(new Vector3f(), new AxisAngle4f((float) Math.toRadians(-12), 0, 0, 1),
                    new Vector3f(s, s * 0.5f, s), new AxisAngle4f()));
            TextComponent.Builder b = Component.text();
            String[] rows = {"━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━", "━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━"};
            int[] cols = {color, 0x8A6A4C};
            for (int i = 0; i < rows.length; i++) {
                if (i > 0) b.append(Component.newline());
                b.append(Component.text(rows[i], TextColor.color(cols[i])));
            }
            d.text(b.build());
        });
    }

    /** Renders a lit sphere as rows of full-block glyphs. */
    static Component paint(Kind kind, long seed) {
        double lx = -0.55, ly = 0.45, lz = 0.70;
        double ll = Math.sqrt(lx * lx + ly * ly + lz * lz);
        lx /= ll; ly /= ll; lz /= ll;
        TextComponent.Builder b = Component.text();
        for (int r = 0; r < ROWS; r++) {
            double v = (r + 0.5) / ROWS * 2 - 1;
            double half = Math.sqrt(Math.max(0, 1 - v * v));
            int cols = Math.max(1, (int) Math.round(half * ROWS * 1.15));
            if (r > 0) b.append(Component.newline());
            for (int c = 0; c < cols; c++) {
                double u = ((c + 0.5) / cols * 2 - 1) * half;
                double z = Math.sqrt(Math.max(0, 1 - u * u - v * v));
                double nx = u, ny = -v, nz = z;
                double lambert = Math.max(0, nx * lx + ny * ly + nz * lz);
                double light = 0.10 + 0.90 * Math.pow(lambert, 0.85);
                int surface = surface(kind, nx, ny, nz, seed);
                int color = shade(surface, light);
                int atmo = atmosphere(kind);
                if (atmo != 0 && z < 0.35) color = mix(color, atmo, (0.35 - z) / 0.35 * 0.75 * Math.max(0.25, lambert + 0.2));
                b.append(Component.text("█", TextColor.color(color)));
            }
        }
        return b.build();
    }

    private static int surface(Kind kind, double x, double y, double z, long seed) {
        double n = fbm(x * 2.2 + seed, y * 2.2, z * 2.2 - seed * 0.3);
        switch (kind) {
            case TERRA -> {
                if (Math.abs(y) > 0.86) return 0xF4F8FF;                       // polar caps
                int base = n < 0.5 ? mix(0x123E8C, 0x1F63B8, n * 2)             // ocean
                        : n < 0.58 ? 0xD8C78A                                    // beach
                        : n < 0.72 ? mix(0x2E7D32, 0x4E9A3A, (n - 0.58) / 0.14)   // forest
                        : mix(0x7A6A48, 0xB8AE96, Math.min(1, (n - 0.72) / 0.2)); // mountains
                double clouds = fbm(x * 3.1 - seed, y * 4.0 + 7, z * 3.1);
                return clouds > 0.62 ? mix(base, 0xFFFFFF, Math.min(0.85, (clouds - 0.62) * 4)) : base;
            }
            case MARS -> {
                return n < 0.45 ? mix(0x8B2E0A, 0xB4410F, n / 0.45) : mix(0xC8541A, 0xE58A4E, Math.min(1, (n - 0.45) / 0.4));
            }
            case GAS -> {
                double t = y * 6.5 + Math.sin(x * 3 + seed) * 0.35 + n * 0.9;
                double f = (Math.sin(t * 2.2) + 1) / 2;
                int band = mix(mix(0xE8D2B0, 0xB98455, f), 0xD9A066, (Math.sin(t * 0.9) + 1) / 4);
                double spot = Math.hypot(x - 0.35, (y + 0.25) * 1.8);   // the great storm
                return spot < 0.16 ? mix(band, 0xB5452A, 1 - spot / 0.16) : band;
            }
            case ICE -> {
                return n > 0.6 ? 0x8FB8D6 : mix(0xDDEFF9, 0xB7D8EE, n);
            }
            default -> {   // MOON
                double crater = fbm(x * 5 + seed, y * 5, z * 5);
                return crater > 0.63 ? 0x6E6E72 : mix(0x9C9CA2, 0xC4C4C8, n);
            }
        }
    }

    private static int atmosphere(Kind kind) {
        return switch (kind) {
            case TERRA -> 0x7FC0FF;
            case GAS -> 0xF0C890;
            case ICE -> 0xC8F0FF;
            case MARS -> 0xFF9A6A;
            default -> 0;
        };
    }

    // simple value noise
    private static double fbm(double x, double y, double z) {
        double sum = 0, amp = 0.5, freq = 1;
        for (int i = 0; i < 4; i++) {
            sum += amp * noise(x * freq, y * freq, z * freq);
            freq *= 2;
            amp *= 0.5;
        }
        return Math.min(1, Math.max(0, sum / 0.9375));
    }

    private static double noise(double x, double y, double z) {
        int xi = (int) Math.floor(x), yi = (int) Math.floor(y), zi = (int) Math.floor(z);
        double xf = x - xi, yf = y - yi, zf = z - zi;
        double u = xf * xf * (3 - 2 * xf), v = yf * yf * (3 - 2 * yf), w = zf * zf * (3 - 2 * zf);
        double c000 = h(xi, yi, zi), c100 = h(xi + 1, yi, zi), c010 = h(xi, yi + 1, zi), c110 = h(xi + 1, yi + 1, zi);
        double c001 = h(xi, yi, zi + 1), c101 = h(xi + 1, yi, zi + 1), c011 = h(xi, yi + 1, zi + 1), c111 = h(xi + 1, yi + 1, zi + 1);
        double x00 = c000 + (c100 - c000) * u, x10 = c010 + (c110 - c010) * u;
        double x01 = c001 + (c101 - c001) * u, x11 = c011 + (c111 - c011) * u;
        double y0 = x00 + (x10 - x00) * v, y1 = x01 + (x11 - x01) * v;
        return y0 + (y1 - y0) * w;
    }

    private static double h(int x, int y, int z) {
        int n = x * 374761393 + y * 668265263 + z * 1274126177;
        n = (n ^ (n >>> 13)) * 1274126177;
        return ((n ^ (n >>> 16)) & 0xFFFF) / 65535.0;
    }

    private static int shade(int c, double k) {
        int r = (int) Math.min(255, ((c >> 16) & 0xFF) * k);
        int g = (int) Math.min(255, ((c >> 8) & 0xFF) * k);
        int bl = (int) Math.min(255, (c & 0xFF) * k);
        return (r << 16) | (g << 8) | bl;
    }

    static int mix(int a, int b, double t) {
        t = Math.max(0, Math.min(1, t));
        int r = (int) Math.round(((a >> 16) & 0xFF) * (1 - t) + ((b >> 16) & 0xFF) * t);
        int g = (int) Math.round(((a >> 8) & 0xFF) * (1 - t) + ((b >> 8) & 0xFF) * t);
        int bl = (int) Math.round((a & 0xFF) * (1 - t) + (b & 0xFF) * t);
        return (r << 16) | (g << 8) | bl;
    }

    /**
     * Stars streaking past the ship: particles fly along +Z through a box around {@code centre},
     * skipping the inside of the ship (radius {@code keepOut}).
     */
    static void streaks(World w, Location centre, double spanX, double spanY, double keepOut, int count, Random rnd) {
        for (int i = 0; i < count; i++) {
            double x = (rnd.nextDouble() * 2 - 1) * spanX;
            double y = (rnd.nextDouble() * 2 - 1) * spanY;
            if (Math.abs(x) < keepOut && Math.abs(y) < keepOut) x += Math.signum(x == 0 ? 1 : x) * keepOut;
            Location l = centre.clone().add(x, y, -55 - rnd.nextDouble() * 20);
            Particle p = rnd.nextInt(4) == 0 ? Particle.FIREWORK : Particle.END_ROD;
            w.spawnParticle(p, l, 0, 0, 0, 1, 1.4 + rnd.nextDouble() * 1.2, null, true);
        }
    }
}
