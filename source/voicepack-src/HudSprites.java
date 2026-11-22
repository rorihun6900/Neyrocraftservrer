import java.awt.image.BufferedImage;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import javax.imageio.ImageIO;

/**
 * Rebuilds NeyroCraft's vanilla-compatible HUD sprites without external tools.
 * From the source directory: java voicepack-src/HudSprites.java neyrohub-src/resources/voice/hud
 */
final class HudSprites {
    private static final int VOID = 0xEE050D1A;
    private static final int WALL = 0xF40B192E;
    private static final int PANEL_TOP = 0xF50D1E36;
    private static final int PANEL_BOTTOM = 0xF9091326;
    private static final int EDGE = 0xFF215D7C;
    private static final int CIRCUIT = 0xFF2787A8;
    private static final int CYAN = 0xFF66DBEF;
    private static final int ICE = 0xFFC0F2FC;
    private static final int COPPER = 0xFF9A572B;
    private static final int AMBER = 0xFFFFB35A;
    private static final int CLEAR = 0x00000000;
    private static final int ARC_GLASS = 0xEC071C35;
    private static final int ARC_NAVY = 0xFF071124;
    private static final int ARC_BLUE = 0xFF2365E7;
    private static final int ARC_ELECTRIC = 0xFF237CFF;
    private static final int ARC_LIT = 0xFF65D8FF;
    private static final int ARC_WHITE = 0xFFF0FBFF;
    private static final int ARC_GLOW = 0x642177FF;
    private static final int ARC_DUSK = 0xFF0D315D;

    private HudSprites() {}

    public static void main(String[] args) throws IOException {
        if (args.length != 1) throw new IllegalArgumentException("Supply the output directory for HUD sprites.");
        Path output = Path.of(args[0]);
        Files.createDirectories(output);
        write(output, "hotbar", hotbar());
        write(output, "hotbar_selection", selection());
        write(output, "hotbar_offhand_left", offhand(3, false));
        write(output, "hotbar_offhand_right", offhand(10, true));
        write(output, "experience_bar_background", experience(false));
        write(output, "experience_bar_progress", experience(true));
        write(output, "locator_bar_background", locator());
        write(output, "portrait_frame", portraitFrame());
    }

    private static BufferedImage hotbar() {
        // Vanilla renders inventory slots at x = 3 + 20 × slot, y = 3.
        BufferedImage image = canvas(182, 22);
        rect(image, 2, 0, 179, 21, ARC_NAVY);
        rect(image, 0, 2, 181, 19, ARC_NAVY);
        h(image, 3, 178, 0, ARC_LIT);
        h(image, 3, 178, 21, ARC_ELECTRIC);
        v(image, 0, 4, 17, ARC_BLUE);
        v(image, 181, 4, 17, ARC_BLUE);
        for (int slot = 0; slot < 9; slot++) {
            int itemX = 3 + slot * 20;
            well(image, itemX, 3);
            h(image, itemX + 4, itemX + 11, 0, slot == 4 ? ARC_WHITE : ICE);
            h(image, itemX + 4, itemX + 11, 21, ARC_LIT);
        }
        image.setRGB(2, 1, ARC_WHITE);
        image.setRGB(179, 1, ARC_WHITE);
        image.setRGB(2, 20, ARC_LIT);
        image.setRGB(179, 20, ARC_LIT);
        return image;
    }

    private static BufferedImage selection() {
        // Vanilla draws the selector at (20 × slot - 1, -1); keep the center transparent.
        BufferedImage image = canvas(24, 23);
        clippedOutline(image, 0, 0, 23, 22, 3, ARC_ELECTRIC);
        clippedOutline(image, 1, 1, 22, 21, 2, ARC_WHITE);
        h(image, 8, 15, 1, ARC_WHITE);
        h(image, 9, 14, 21, ICE);
        v(image, 1, 6, 10, ARC_LIT);
        v(image, 22, 12, 16, ARC_LIT);
        return image;
    }

    private static BufferedImage offhand(int itemX, boolean right) {
        // Off-hand uses different left/right padding, but the item baseline is identical.
        BufferedImage image = canvas(29, 24);
        rect(image, itemX - 2, 2, itemX + 17, 21, WALL);
        clippedOutline(image, itemX - 3, 1, itemX + 18, 22, 2, ARC_ELECTRIC);
        well(image, itemX, 4);
        h(image, itemX + 3, itemX + 12, 1, CYAN);
        h(image, itemX + 6, itemX + 9, 22, CIRCUIT);
        int outer = right ? 28 : 0;
        v(image, outer, 8, 15, COPPER);
        v(image, outer, 9, 11, AMBER);
        return image;
    }

    private static void well(BufferedImage image, int itemX, int itemY) {
        int left = itemX - 1, right = itemX + 16, top = itemY - 1, bottom = itemY + 16;
        for (int y = itemY; y < itemY + 16; y++) {
            h(image, itemX, itemX + 15, y, lerp(PANEL_TOP, PANEL_BOTTOM, y - itemY, 15));
        }
        clippedOutline(image, left, top, right, bottom, 2, ARC_ELECTRIC);
        clippedOutline(image, left + 1, top + 1, right - 1, bottom - 1, 1, ARC_DUSK);
        h(image, left + 3, right - 3, top, ARC_LIT);
        h(image, left + 3, right - 3, bottom, ARC_LIT);
        v(image, left, top + 3, bottom - 2, ARC_LIT);
        v(image, right, top + 3, bottom - 2, ARC_LIT);
        image.setRGB(left + 3, top, ARC_WHITE);
        image.setRGB(right - 3, top, ARC_WHITE);
        image.setRGB(left + 3, bottom, ICE);
    }

    private static BufferedImage portraitFrame() {
        // Atlas aperture at (8, 4): 24×24, for a threefold nearest-neighbor skin portrait.
        BufferedImage image = canvas(40, 34);
        for (int y = 0; y < 34; y++) {
            int inset = y < 9 ? 10 - y : y < 24 ? 2 : Math.min(11, y - 22);
            for (int x = inset - 1; x <= 40 - inset; x++) {
                int bevel = Math.min(Math.min(x - inset, 39 - inset - x), Math.min(y, 33 - y));
                int color = switch (bevel) {
                    case -1 -> ARC_GLOW;
                    case 0 -> ARC_ELECTRIC;
                    case 1 -> ARC_WHITE;
                    case 2 -> ARC_LIT;
                    case 3 -> ARC_DUSK;
                    default -> ARC_GLASS;
                };
                image.setRGB(x, y, color);
            }
        }
        h(image, 13, 26, 0, ARC_LIT);
        h(image, 11, 28, 1, ARC_WHITE);
        h(image, 9, 30, 3, ARC_DUSK);
        h(image, 9, 30, 28, ARC_DUSK);
        v(image, 7, 9, 24, ARC_NAVY);
        v(image, 32, 9, 24, ARC_NAVY);
        rect(image, 8, 4, 31, 27, CLEAR);
        return image;
    }

    private static BufferedImage experience(boolean filled) {
        BufferedImage image = canvas(182, 5);
        rect(image, 1, 0, 180, 4, VOID);
        if (!filled) {
            clippedOutline(image, 0, 0, 181, 4, 1, EDGE);
            h(image, 3, 178, 2, WALL);
            h(image, 87, 94, 0, COPPER);
            return image;
        }
        for (int x = 0; x < 182; x++) {
            int primary = lerp(CIRCUIT, CYAN, x, 181);
            int reflection = lerp(CYAN, ICE, x, 181);
            image.setRGB(x, 1, reflection);
            image.setRGB(x, 2, primary);
            image.setRGB(x, 3, x % 13 == 0 ? VOID : EDGE);
        }
        h(image, 1, 180, 0, EDGE);
        h(image, 1, 180, 4, EDGE);
        return image;
    }

    private static BufferedImage locator() {
        // Vanilla's inherited 12×5 nine-slice stretches the two central pixels to the HUD width.
        BufferedImage image = canvas(12, 5);
        rect(image, 1, 0, 10, 4, VOID);
        clippedOutline(image, 0, 0, 11, 4, 1, EDGE);
        for (int x : new int[] {2, 3, 8, 9}) image.setRGB(x, 2, CIRCUIT);
        return image;
    }

    private static BufferedImage canvas(int width, int height) {
        return new BufferedImage(width, height, BufferedImage.TYPE_INT_ARGB);
    }

    private static int lerp(int from, int to, int step, int max) {
        int argb = CLEAR;
        for (int shift = 0; shift < 32; shift += 8) {
            int start = from >>> shift & 0xFF;
            int end = to >>> shift & 0xFF;
            argb |= (start * (max - step) + end * step) / max << shift;
        }
        return argb;
    }

    private static void clippedOutline(BufferedImage image, int left, int top, int right, int bottom, int cut, int color) {
        h(image, left + cut, right - cut, top, color);
        h(image, left + cut, right - cut, bottom, color);
        v(image, left, top + cut, bottom - cut, color);
        v(image, right, top + cut, bottom - cut, color);
        for (int i = 1; i < cut; i++) {
            int inset = cut - i;
            image.setRGB(left + inset, top + i, color);
            image.setRGB(right - inset, top + i, color);
            image.setRGB(left + inset, bottom - i, color);
            image.setRGB(right - inset, bottom - i, color);
        }
    }

    private static void rect(BufferedImage image, int left, int top, int right, int bottom, int color) {
        for (int y = top; y <= bottom; y++) h(image, left, right, y, color);
    }

    private static void h(BufferedImage image, int left, int right, int y, int color) {
        for (int x = left; x <= right; x++) image.setRGB(x, y, color);
    }

    private static void v(BufferedImage image, int x, int top, int bottom, int color) {
        for (int y = top; y <= bottom; y++) image.setRGB(x, y, color);
    }

    private static void write(Path output, String name, BufferedImage image) throws IOException {
        if (!ImageIO.write(image, "PNG", output.resolve(name + ".png").toFile())) {
            throw new IOException("PNG image writer unavailable");
        }
    }
}
