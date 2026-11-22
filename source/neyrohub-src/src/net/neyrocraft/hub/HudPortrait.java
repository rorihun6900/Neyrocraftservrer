package net.neyrocraft.hub;

import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.net.HttpURLConnection;
import java.net.URI;
import java.net.URL;
import java.util.LinkedHashMap;
import java.util.Map;
import javax.imageio.ImageIO;

/** Paints a player's vanilla HUD glyphs using only the public Mojang skin carried by their profile. */
final class HudPortrait {
    private static final int MAX_BYTES = 262_144;
    private static final String TEXTURE_HOST = "textures.minecraft.net";
    private static final String GLYPH_PATH = "assets/neyrocraft/textures/font/pilot_";
    private static final int HUD_ASCENT = 12;
    private static final int HUD_HEIGHT = 54;
    private static final char DIGIT = '\uE110';
    private static final char NEGATIVE_SPACE = '\uE130';
    // Larger native numbers extend beyond the badge and keep the normal vanilla rendering.
    static final int MAX_LEVEL = 999;
    private static final String[] NUMBERS = {
            "111 101 101 101 111", "010 110 010 010 111",
            "111 001 111 100 111", "111 001 111 001 111",
            "101 101 111 001 001", "111 100 111 001 111",
            "111 100 111 101 111", "111 001 010 010 010",
            "111 101 111 101 111", "111 101 111 001 111"
    };
    private static final String FONT = """
            {"providers":[
              {"type":"bitmap","file":"neyrocraft:font/pilot_open.png","ascent":%d,"height":%d,"chars":["\uE100"]},
              {"type":"bitmap","file":"neyrocraft:font/pilot_blink.png","ascent":%d,"height":%d,"chars":["\uE101"]},
              {"type":"bitmap","file":"neyrocraft:font/pilot_digits.png","ascent":-32,"height":5,"chars":["%s"]},
              {"type":"space","advances":{"\uE102":-20,"\uE103":%d,%s}}
            ]}
            """;
    private static final byte[] DIGITS = digitAtlas(NUMBERS, 3, 5);

    private HudPortrait() { }

    static String overlay(int level, boolean blink) {
        String number = Integer.toString(level);
        int halfWidth = number.length() * 2;
        char offset = (char) (NEGATIVE_SPACE + halfWidth);
        var overlay = new StringBuilder(blink ? "\uE102\uE101\uE103" : "\uE102\uE100\uE103");
        overlay.append(offset);
        for (int i = 0; i < number.length(); i++) overlay.append((char) (DIGIT + number.charAt(i) - '0'));
        return overlay.append(offset).toString();
    }

    static String textureId(URL skin) {
        if (skin == null || !TEXTURE_HOST.equalsIgnoreCase(skin.getHost())) return null;
        String path = skin.getPath();
        return path.matches("/texture/[A-Fa-f0-9]{32,64}") ? path.substring("/texture/".length()) : null;
    }

    static Map<String, byte[]> fetch(String textureId) throws IOException {
        var url = URI.create("https://" + TEXTURE_HOST + "/texture/" + textureId).toURL();
        var connection = (HttpURLConnection) url.openConnection();
        connection.setConnectTimeout(2500);
        connection.setReadTimeout(2500);
        connection.setInstanceFollowRedirects(false);
        try {
            if (connection.getResponseCode() != 200 || connection.getContentLengthLong() > MAX_BYTES)
                throw new IOException("Skin not available");
            try (var stream = connection.getInputStream()) {
                byte[] bytes = stream.readNBytes(MAX_BYTES + 1);
                if (bytes.length > MAX_BYTES) throw new IOException("Skin is too large");
                return assets(ImageIO.read(new ByteArrayInputStream(bytes)));
            }
        } finally {
            connection.disconnect();
        }
    }

    static Map<String, byte[]> assets(BufferedImage skin) throws IOException {
        if (skin == null || skin.getWidth() != 64 || (skin.getHeight() != 64 && skin.getHeight() != 32))
            throw new IOException("Unexpected Minecraft skin format");

        BufferedImage face = new BufferedImage(8, 8, BufferedImage.TYPE_INT_ARGB);
        Graphics2D pixels = face.createGraphics();
        try {
            pixels.drawImage(skin.getSubimage(8, 8, 8, 8), 0, 0, null);
            pixels.drawImage(skin.getSubimage(40, 8, 8, 8), 0, 0, null);
        } finally {
            pixels.dispose();
        }

        Map<String, byte[]> sprites = new LinkedHashMap<>();
        // Negative spaces center the drawing without causing an action-bar background to span it.
        int advance = 31; // The level backing ends at x=29 followed by one pixel of advance.
        sprites.put("assets/neyrocraft/font/pilot_hud.json",
                FONT.formatted(HUD_ASCENT, HUD_HEIGHT, HUD_ASCENT, HUD_HEIGHT,
                                digitCharacters(DIGIT), 20 - advance, numberOffsets())
                        .getBytes(java.nio.charset.StandardCharsets.UTF_8));
        sprites.put(GLYPH_PATH + "digits.png", DIGITS);
        sprites.put(GLYPH_PATH + "open.png", card(face));
        // Close only the usual two eye bands. Keep hair and outer skin layers intact.
        for (int x : new int[]{1, 2, 5, 6}) {
            int skinTone = face.getRGB(x, 3);
            int outline = face.getRGB(x, 4);
            if ((skinTone >>> 24) != 255 || (outline >>> 24) != 255) continue;
            face.setRGB(x, 4, skinTone);
            int red = ((skinTone >>> 16) & 255) * 2 / 3;
            int green = ((skinTone >>> 8) & 255) * 2 / 3;
            int blue = (skinTone & 255) * 2 / 3;
            face.setRGB(x, 5, 0xff000000 | red << 16 | green << 8 | blue);
        }
        sprites.put(GLYPH_PATH + "blink.png", card(face));
        return sprites;
    }

    private static String digitCharacters(char first) {
        var chars = new StringBuilder(10);
        for (int i = 0; i < 10; i++) chars.append((char) (first + i));
        return chars.toString();
    }

    private static String numberOffsets() {
        var entries = new java.util.StringJoiner(",");
        for (int pixels : new int[]{2, 4, 6}) {
            entries.add("\"" + (char) (NEGATIVE_SPACE + pixels) + "\":" + -pixels);
        }
        return entries.toString();
    }

    private static byte[] digitAtlas(String[] digits, int width, int height) {
        var image = new BufferedImage(width * digits.length, height, BufferedImage.TYPE_INT_ARGB);
        for (int digit = 0; digit < digits.length; digit++) {
            String[] rows = digits[digit].split(" ");
            for (int y = 0; y < height; y++) {
                int color = y < height / 2 ? 0xffa3e8ff : 0xff54affe;
                for (int x = 0; x < width; x++) {
                    if (rows[y].charAt(x) == '1') image.setRGB(digit * width + x, y, color);
                }
            }
        }
        try {
            return png(image);
        } catch (IOException ex) {
            throw new IllegalStateException("Could not create HUD digits", ex);
        }
    }

    private static byte[] card(BufferedImage face) throws IOException {
        BufferedImage image = new BufferedImage(40, HUD_HEIGHT, BufferedImage.TYPE_INT_ARGB);
        Graphics2D draw = image.createGraphics();
        try {
            draw.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_NEAREST_NEIGHBOR);
            draw.drawImage(face, 12, 21, 16, 16, null);
            // Mask the original green level digits without touching the XP bar underneath.
            draw.setColor(new java.awt.Color(0xff091426, true));
            draw.fillRect(10, 41, 20, 7);
        } finally {
            draw.dispose();
        }
        return png(image);
    }

    private static byte[] png(BufferedImage image) throws IOException {
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        ImageIO.write(image, "PNG", output);
        return output.toByteArray();
    }
}
