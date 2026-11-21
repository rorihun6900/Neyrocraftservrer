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
    private static final int OUTER = 0xFF123349;
    private static final int EDGE = 0xFF215D7C;
    private static final int CIRCUIT = 0xFF2787A8;
    private static final int CYAN = 0xFF66DBEF;
    private static final int ICE = 0xFFC0F2FC;
    private static final int COPPER = 0xFF9A572B;
    private static final int AMBER = 0xFFFFB35A;
    private static final int PALE_AMBER = 0xFFFFDC8E;
    private static final int CLEAR = 0x00000000;

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
    }

    private static BufferedImage hotbar() {
        // Vanilla renders inventory slots at x = 3 + 20 × slot, y = 3.
        BufferedImage image = canvas(182, 22);
        rect(image, 1, 0, 180, 21, WALL);
        rect(image, 0, 2, 181, 19, WALL);
        h(image, 4, 177, 0, EDGE);
        h(image, 4, 177, 21, EDGE);
        v(image, 0, 4, 17, OUTER);
        v(image, 181, 4, 17, OUTER);
        for (int slot = 0; slot < 9; slot++) {
            int itemX = 3 + slot * 20;
            well(image, itemX, 3);
            h(image, itemX + 3, itemX + 12, 0, slot == 4 ? AMBER : CYAN);
            h(image, itemX + 6, itemX + 9, 21, slot == 4 ? COPPER : CIRCUIT);
        }
        image.setRGB(2, 1, ICE);
        image.setRGB(179, 1, ICE);
        image.setRGB(2, 20, CYAN);
        image.setRGB(179, 20, CYAN);
        return image;
    }

    private static BufferedImage selection() {
        // Vanilla draws the selector at (20 × slot - 1, -1); keep the center transparent.
        BufferedImage image = canvas(24, 23);
        clippedOutline(image, 0, 0, 23, 22, 3, COPPER);
        clippedOutline(image, 1, 1, 22, 21, 2, AMBER);
        h(image, 8, 15, 1, PALE_AMBER);
        h(image, 10, 13, 21, PALE_AMBER);
        v(image, 1, 6, 10, PALE_AMBER);
        v(image, 22, 12, 16, PALE_AMBER);
        return image;
    }

    private static BufferedImage offhand(int itemX, boolean right) {
        // Off-hand uses different left/right padding, but the item baseline is identical.
        BufferedImage image = canvas(29, 24);
        rect(image, itemX - 2, 2, itemX + 17, 21, WALL);
        clippedOutline(image, itemX - 3, 1, itemX + 18, 22, 2, EDGE);
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
        clippedOutline(image, left, top, right, bottom, 2, EDGE);
        h(image, left + 3, right - 3, top, CIRCUIT);
        h(image, left + 2, left + 5, bottom, CYAN);
        image.setRGB(right - 2, top + 1, OFF_GLOW);
        image.setRGB(left + 1, bottom - 2, OFF_GLOW);
    }

    private static final int OFF_GLOW = 0xFF254C60;

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
