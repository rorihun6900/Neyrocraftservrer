package net.neyrocraft.hub;

import net.kyori.adventure.key.Key;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.TextComponent;
import net.kyori.adventure.text.format.ShadowColor;
import net.kyori.adventure.text.format.TextColor;
import net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;

import javax.imageio.ImageIO;
import java.awt.Color;
import java.awt.Font;
import java.awt.GradientPaint;
import java.awt.Graphics2D;
import java.awt.Polygon;
import java.awt.RenderingHints;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.lang.reflect.Method;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.StringJoiner;
import java.util.UUID;

/** The 26.3 vanilla pack anchors these action-bar glyphs in the corner of the viewport. */
final class HudCorner {
    private static final int PROTOCOL = 777;
    private static final String OVERLAY = "profile_hud_26_3";
    private static final String ASSETS = OVERLAY + "/assets/neyrocraft/";
    static final String SHADER = OVERLAY + "/assets/minecraft/shaders/core/text.vsh";
    private static final int WIDTH = 142, HEIGHT = 39, GLYPH_WIDTH = 5, GLYPH_HEIGHT = 9;
    private static final char MOVE = '\uE400', FRAME = '\uE500', DARK_FRAME = '\uE501';
    private static final int MARKER = 0x096dc9;
    private static final Key PANEL = Key.key("neyrocraft", "profile_card");
    private static final Key NAME = Key.key("neyrocraft", "profile_name");
    private static final Key RANK = Key.key("neyrocraft", "profile_rank");
    private static final Key XP = Key.key("neyrocraft", "profile_xp");
    private static final Key LEVEL = Key.key("neyrocraft", "profile_level");
    private static final String LETTERS = "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789_[]•.:-/✦+"
            + "АБВГДЕЁЖЗИЙКЛМНОПРСТУФХЦЧШЩЪЫЬЭЮЯабвгдеёжзийклмнопрстуфхцчшщъыьэюя";
    private static final byte[] TEXT = glyphs();
    private static final String SPACING = spacing();
    private static final ClassValue<ViaProtocol> VIA = new ClassValue<>() {
        @Override
        protected ViaProtocol computeValue(Class<?> pluginType) {
            try {
                ClassLoader loader = pluginType.getClassLoader();
                Class<?> via = Class.forName("com.viaversion.viaversion.api.Via", false, loader);
                Class<?> api = Class.forName("com.viaversion.viaversion.api.ViaAPI", false, loader);
                return new ViaProtocol(via.getMethod("getAPI").invoke(null), api.getMethod("getPlayerVersion", UUID.class));
            } catch (ReflectiveOperationException | LinkageError ex) {
                return new ViaProtocol(null, null);
            }
        }
    };

    private record ViaProtocol(Object api, Method version) {
        boolean supports(Player player) {
            if (version == null) return false;
            try { return (Integer) version.invoke(api, player.getUniqueId()) == PROTOCOL; }
            catch (ReflectiveOperationException | LinkageError ex) { return false; }
        }
    }

    private HudCorner() { }

    static boolean supports(Player player) {
        var via = Bukkit.getPluginManager().getPlugin("ViaVersion");
        return via != null && via.isEnabled() ? VIA.get(via.getClass()).supports(player) : player.getProtocolVersion() == PROTOCOL;
    }

    static Component render(Player player, Component rank, boolean dark) {
        TextComponent.Builder card = Component.text();
        row(card, PANEL, Character.toString(dark ? DARK_FRAME : FRAME), 0, WIDTH + 1, MARKER);
        label(card, NAME, player.getName(), 33, 17, MARKER + 1);
        String role = PlainTextComponentSerializer.plainText().serialize(rank)
                .replaceAll("[^\\p{L}\\p{N} .:_-]", "").strip();
        if (role.isBlank()) role = player.isOp() ? "Администратор" : "Игрок";
        label(card, RANK, "[" + role + "]", 33, 17, rankMarker(rank, dark));
        int need = Math.max(1, player.getExpToLevel());
        int xp = Math.round(player.getExp() * need);
        label(card, XP, "XP " + xp + "/" + need, 36, 16, MARKER + 1);
        String level = Integer.toString(Math.min(999, Math.max(0, player.getLevel())));
        label(card, LEVEL, level, 17 - level.length() * 3, 3, MARKER + 4);
        return card.build();
    }

    private static int rankMarker(Component rank, boolean dark) {
        if (dark) return MARKER + 2;
        TextColor color = rank.color();
        if (color == null) {
            for (Component child : rank.children()) {
                color = child.color();
                if (color != null && color.value() != 0x555555) break;
            }
        }
        if (color == null) return MARKER + 4;
        if (color.red() > color.green() * 1.5 && color.red() > color.blue() * 1.5) return MARKER + 2;
        if (color.red() > color.blue() * 1.5) return MARKER + 3;
        if (color.blue() > color.green() * 1.5 && color.red() > color.green()) return MARKER + 5;
        if (color.green() > color.red() * 1.4 && color.green() > color.blue() * 1.3) return MARKER + 6;
        return MARKER + 4;
    }

    private static void label(TextComponent.Builder card, Key font, String text, int x, int limit, int marker) {
        if (text.length() > limit) text = text.substring(0, limit - 1) + (text.endsWith("]") ? "]" : "•");
        var safe = new StringBuilder(text.length());
        for (int i = 0; i < text.length(); i++) {
            char c = text.charAt(i);
            safe.append(c == ' ' || LETTERS.indexOf(c) >= 0 ? c : '•');
        }
        row(card, font, safe.toString(), x, safe.length() * (GLYPH_WIDTH + 1), marker);
    }

    private static void row(TextComponent.Builder card, Key font, String text, int x, int advance, int marker) {
        card.append(Component.text(Character.toString(MOVE + x) + text + (char) (MOVE - x - advance))
                .font(font).color(TextColor.color(marker)).shadowColor(ShadowColor.none()));
    }

    static byte[] metadata(byte[] data) throws IOException {
        String base = new String(data, StandardCharsets.UTF_8).stripTrailing();
        if (!base.endsWith("}") || base.contains("\"overlays\"")) throw new IOException("Unexpected voice pack metadata");
        String overlays = """
                ,"overlays":{"entries":[{"directory":"%s","min_format":[97,1],"max_format":[97,1]}]}}
                """.formatted(OVERLAY);
        return (base.substring(0, base.length() - 1) + overlays).getBytes(StandardCharsets.UTF_8);
    }

    static Map<String, byte[]> assets(BufferedImage face) throws IOException {
        var assets = new LinkedHashMap<String, byte[]>();
        assets.put(ASSETS + "textures/font/profile.png", panel(face, false));
        assets.put(ASSETS + "textures/font/profile_dark.png", panel(face, true));
        assets.put(ASSETS + "textures/font/profile_glyphs.png", TEXT);
        define(assets, "profile_card", bitmap("profile", 7, HEIGHT, Character.toString(FRAME))
                + ',' + bitmap("profile_dark", 7, HEIGHT, Character.toString(DARK_FRAME)));
        textFont(assets, "profile_name", 5);
        textFont(assets, "profile_rank", 15);
        textFont(assets, "profile_xp", 26);
        textFont(assets, "profile_level", 27);
        try (var input = HudCorner.class.getResourceAsStream("/voice/profile_hud_26_3.vsh")) {
            if (input == null) throw new IOException("26.3 corner HUD shader missing");
            assets.put(SHADER, input.readAllBytes());
        }
        return assets;
    }

    private static String bitmap(String texture, int ascent, int height, String chars) {
        return """
                {"type":"bitmap","file":"neyrocraft:font/%s.png","ascent":%d,"height":%d,"chars":["%s"]}
                """.formatted(texture, ascent, height, chars).strip();
    }

    private static void textFont(Map<String, byte[]> assets, String name, int y) {
        define(assets, name, bitmap("profile_glyphs", 7 - y, GLYPH_HEIGHT, LETTERS));
    }

    private static String spacing() {
        StringJoiner advances = new StringJoiner(",", "{\"type\":\"space\",\"advances\":{", "}}");
        advances.add("\" \":" + (GLYPH_WIDTH + 1));
        for (int size = -160; size <= 40; size++) {
            advances.add("\"" + (char) (MOVE + size) + "\":" + size);
        }
        return advances.toString();
    }

    private static void define(Map<String, byte[]> assets, String name, String providers) {
        String font = "{\"providers\":[" + providers + ',' + SPACING + "]}";
        assets.put(ASSETS + "font/" + name + ".json", font.getBytes(StandardCharsets.UTF_8));
    }

    private static byte[] panel(BufferedImage face, boolean dark) throws IOException {
        BufferedImage image = new BufferedImage(WIDTH, HEIGHT, BufferedImage.TYPE_INT_ARGB);
        Graphics2D g = image.createGraphics();
        try {
            g.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_NEAREST_NEIGHBOR);
            g.setColor(new Color(dark ? 0x992f1833 : 0x99234878, true));
            g.fillPolygon(chamfer(0, 0, WIDTH - 1, HEIGHT - 1, 9));
            g.setColor(new Color(dark ? 0xe42e0713 : 0xe4081630, true));
            g.fillPolygon(chamfer(2, 2, WIDTH - 5, HEIGHT - 5, 8));
            g.setPaint(new GradientPaint(0, 2, new Color(dark ? 0xff727e : 0x8ce9ff), WIDTH, HEIGHT,
                    new Color(dark ? 0xc23050 : 0x2262ed)));
            g.drawPolygon(chamfer(2, 2, WIDTH - 5, HEIGHT - 5, 8));
            g.setColor(new Color(dark ? 0x331638 : 0x08101d));
            g.fillPolygon(chamfer(5, 5, 25, 28, 4));
            g.setColor(new Color(dark ? 0x884454 : 0x3265ac));
            g.drawPolygon(chamfer(5, 5, 25, 28, 4));
            g.drawImage(face, 8, 7, 18, 18, null);
            g.setPaint(new GradientPaint(33, 29, new Color(dark ? 0x821b46 : 0x471d8c), 135, 29,
                    new Color(dark ? 0x321234 : 0x211b50)));
            g.fillPolygon(chamfer(33, 27, 103, 9, 3));
        } finally {
            g.dispose();
        }
        image.setRGB(WIDTH - 1, HEIGHT - 1, 0x01000000);
        return png(image);
    }

    private static Polygon chamfer(int x, int y, int w, int h, int corner) {
        return new Polygon(new int[]{x + corner, x + w - corner, x + w, x + w, x + w - corner, x + corner, x, x},
                new int[]{y, y, y + corner, y + h - corner, y + h, y + h, y + h - corner, y + corner}, 8);
    }

    private static byte[] glyphs() {
        BufferedImage atlas = new BufferedImage(LETTERS.length() * GLYPH_WIDTH, GLYPH_HEIGHT, BufferedImage.TYPE_INT_ARGB);
        Graphics2D g = atlas.createGraphics();
        try {
            g.setFont(new Font(Font.MONOSPACED, Font.BOLD, 8));
            g.setColor(Color.WHITE);
            g.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING, RenderingHints.VALUE_TEXT_ANTIALIAS_OFF);
            for (int i = 0; i < LETTERS.length(); i++) {
                int x = i * GLYPH_WIDTH;
                g.setClip(x, 0, GLYPH_WIDTH, GLYPH_HEIGHT);
                g.drawString(LETTERS.substring(i, i + 1), x, 7);
                atlas.setRGB(x + GLYPH_WIDTH - 1, GLYPH_HEIGHT - 1, 0x01000000);
            }
        } finally {
            g.dispose();
        }
        try { return png(atlas); }
        catch (IOException ex) { throw new IllegalStateException("Corner HUD font could not be rendered", ex); }
    }

    private static byte[] png(BufferedImage image) throws IOException {
        ByteArrayOutputStream data = new ByteArrayOutputStream();
        ImageIO.write(image, "PNG", data);
        return data.toByteArray();
    }
}
