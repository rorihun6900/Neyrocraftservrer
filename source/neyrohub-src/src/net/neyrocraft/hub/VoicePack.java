package net.neyrocraft.hub;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import net.kyori.adventure.key.Key;
import net.kyori.adventure.resource.ResourcePackInfo;
import net.kyori.adventure.resource.ResourcePackRequest;
import net.kyori.adventure.resource.ResourcePackStatus;
import net.kyori.adventure.sound.Sound;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.TextColor;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;

import java.io.ByteArrayOutputStream;
import java.io.ByteArrayInputStream;
import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.StandardCopyOption;
import java.security.MessageDigest;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;
import java.util.zip.ZipOutputStream;

/**
 * Samantha's voice and the deck music: a small resource pack served over HTTP by the plugin itself.
 * Every player gets the pack with their own name spoken by Samantha (synthesised once on this PC,
 * then cached), so the greeting really says "Добро пожаловать …, <ник>".
 */
final class VoicePack {
    private static final String BASE_PATH = "/neyrocraft-voice.zip";
    private static final String PLAYER_PATH = "/p/";
    private static final UUID PACK_ID = UUID.nameUUIDFromBytes("neyrocraft-voice".getBytes());
    private static final String RETRO_PATH = "/retro.zip";
    private static final UUID RETRO_ID = UUID.nameUUIDFromBytes("neyrocraft-retro".getBytes());
    private Pack retroPack;
    private static final Key PART_A = Key.key("neyrocraft", "sam_welcome_a");
    private static final Key PART_B = Key.key("neyrocraft", "sam_welcome_b");
    // lengths of the recorded parts (seconds), minus the reverb tail that may overlap the next part
    private static final double A_SPEECH = 4.585 - 0.6;
    private static final double B_LENGTH = 14.138;
    private static final double TAIL = 0.6;
    private static final String NAME_VERSION = "v1";

    private record Pack(byte[] zip, String sha1) { }
    private record NameClip(String key, double seconds) { }

    private final NeyroHub plugin;
    private final Map<UUID, String> loadedKey = new ConcurrentHashMap<>();     // which pack the client has
    private final Map<String, Pack> packs = new ConcurrentHashMap<>();         // "base" or name key
    private final Map<String, NameClip> clips = new ConcurrentHashMap<>();
    private final Set<UUID> offered = ConcurrentHashMap.newKeySet();
    private final ExecutorService synth = Executors.newSingleThreadExecutor(r -> { Thread t = new Thread(r, "Samantha-Voice"); t.setDaemon(true); return t; });
    private HttpServer http;
    private int port;
    private String publicBase;          // e.g. http://neyrocraft.duckdns.org:8165
    private List<String> localPlayers;
    private File namesDir, toolsDir;
    private String blender;

    VoicePack(NeyroHub plugin) {
        this.plugin = plugin;
    }

    void start() {
        if (!plugin.getConfig().getBoolean("voice-pack.enabled", true)) return;
        port = plugin.getConfig().getInt("voice-pack.port", 8165);
        String pub = plugin.getConfig().getString("voice-pack.public-url", "").trim();
        publicBase = pub.isEmpty() ? "" : pub.replaceAll("/[^/]*$", "");
        localPlayers = plugin.getConfig().getStringList("voice-pack.local-players");
        blender = plugin.getConfig().getString("voice-pack.blender", "c:\\games\\steam\\steamapps\\common\\Blender\\blender.exe");
        namesDir = new File(plugin.getDataFolder(), "voice-names");
        toolsDir = new File(plugin.getDataFolder(), "voice-tools");
        namesDir.mkdirs();
        toolsDir.mkdirs();
        try {
            for (String t : new String[]{"samantha.ps1", "SamanthaFx.cs", "wav2ogg.py"})
                try (InputStream in = plugin.getResource("voice-tools/" + t)) {
                    if (in != null) Files.copy(in, new File(toolsDir, t).toPath(), StandardCopyOption.REPLACE_EXISTING);
                }
            byte[] base;
            try (InputStream in = plugin.getResource("voice/neyrocraft-voice.zip")) {
                if (in == null) throw new IllegalStateException("pack missing from jar");
                base = in.readAllBytes();
            }
            try (InputStream track = plugin.getResource("voice/temple_ambience.ogg")) {
                if (track != null) base = addTempleAmbience(base, track);
            } catch (IOException ex) {
                plugin.getLogger().warning("Temple soundtrack unavailable: " + ex.getMessage());
            }
            try {
                base = addHudTextures(base);
            } catch (IOException ex) {
                plugin.getLogger().warning("HUD textures unavailable: " + ex.getMessage());
            }
            packs.put("base", new Pack(base, sha1(base)));
            try {
                packs.put("dark-ui", darkUi(base));
            } catch (IOException ex) {
                plugin.getLogger().warning("Dark interface unavailable: " + ex.getMessage());
            }
            try (InputStream in = plugin.getResource("retro/neyrocraft-retro.zip")) {
                if (in != null) { byte[] r = in.readAllBytes(); retroPack = new Pack(r, sha1(r)); }
            }
            String bind = plugin.getConfig().getString("voice-pack.bind", "127.0.0.1").trim();
            http = HttpServer.create(new InetSocketAddress(InetAddress.getByName(bind), port), 16);
            http.createContext(BASE_PATH, ex -> send(ex, packs.get("base")));
            http.createContext(RETRO_PATH, ex -> send(ex, retroPack));
            http.createContext(PLAYER_PATH, ex -> {
                String k = ex.getRequestURI().getPath().substring(PLAYER_PATH.length()).replace(".zip", "");
                send(ex, packs.get(k));
            });
            http.setExecutor(Executors.newFixedThreadPool(2, r -> { Thread t = new Thread(r, "NeyroVoice-HTTP"); t.setDaemon(true); return t; }));
            http.start();
            plugin.getLogger().info("Voice pack served on " + bind + ":" + port + " (Samantha, per-player names)"
                    + (publicBase.isEmpty() ? "" : ", public " + publicBase));
        } catch (Exception e) {
            plugin.getLogger().warning("Voice pack disabled: " + e);
            http = null;
        }
    }

    void stop() {
        if (http != null) http.stop(0);
        http = null;
        synth.shutdownNow();
    }

    private static void send(HttpExchange ex, Pack pack) throws IOException {
        if (pack == null) { ex.sendResponseHeaders(404, -1); ex.close(); return; }
        ex.getResponseHeaders().add("Content-Type", "application/zip");
        if ("HEAD".equalsIgnoreCase(ex.getRequestMethod())) {
            ex.getResponseHeaders().add("Content-Length", String.valueOf(pack.zip().length));
            ex.sendResponseHeaders(200, -1);
            ex.close();
            return;
        }
        ex.sendResponseHeaders(200, pack.zip().length);
        try (OutputStream out = ex.getResponseBody()) { out.write(pack.zip()); }
    }

    // ------------------------------------------------------------ offering the pack

    private String hostFor(Player p) {
        InetAddress a = p.getAddress() == null ? null : p.getAddress().getAddress();
        boolean local = a != null && (a.isLoopbackAddress() || a.isSiteLocalAddress());
        for (String n : localPlayers) if (n.equalsIgnoreCase(p.getName())) local = true;
        if (local) return "http://127.0.0.1:" + port;
        return publicBase.isEmpty() ? null : publicBase;
    }

    /** Called on join: prepares Samantha's recording of this player's name, then offers the pack. */
    void offer(Player p) {
        if (http == null || loadedKey.containsKey(p.getUniqueId()) || !offered.add(p.getUniqueId())) return;
        if (hostFor(p) == null) return;
        String name = p.getName();
        UUID id = p.getUniqueId();
        long started = System.currentTimeMillis();
        synth.submit(() -> {
            NameClip clip = null;
            try { clip = nameClip(name); } catch (Exception e) {
                plugin.getLogger().warning("Samantha could not record the name " + name + ": " + e.getMessage());
            }
            String key = clip == null ? "base" : clip.key();
            if (clip != null && !packs.containsKey(key)) {
                try { packs.put(key, buildPlayerPack(clip)); } catch (IOException e) { key = "base"; }
            }
            final String k = key;
            long wait = Math.max(0, 1000 - (System.currentTimeMillis() - started));
            Bukkit.getScheduler().runTaskLater(plugin, () -> {
                Player pl = Bukkit.getPlayer(id);
                if (pl != null && pl.isOnline()) send(pl, k);
            }, wait / 50 + 1);
        });
    }

    private static final UUID DARK_UI_ID = UUID.fromString("52852927-ee0e-406c-8d8f-9862e3689eea");

    void syncDark(Player p) {
        String world = p.getWorld().getName();
        if (!world.equals("neyro_hub3") && !world.equals(DarkAnarchy.WORLD_NAME)) {
            p.removeResourcePacks(DARK_UI_ID);
            return;
        }
        Pack pack = packs.get("dark-ui");
        String host = hostFor(p);
        if (http == null || pack == null || host == null) return;
        p.sendResourcePacks(ResourcePackRequest.resourcePackRequest()
                .packs(ResourcePackInfo.resourcePackInfo(DARK_UI_ID,
                        URI.create(host + PLAYER_PATH + "dark-ui.zip"), pack.sha1()))
                .replace(false).required(false));
    }

    private byte[] addTempleAmbience(byte[] base, InputStream track) throws IOException {
        ByteArrayOutputStream bytes = new ByteArrayOutputStream(base.length + 320_000);
        try (ZipInputStream originals = new ZipInputStream(new ByteArrayInputStream(base));
             ZipOutputStream zip = new ZipOutputStream(bytes)) {
            ZipEntry entry;
            while ((entry = originals.getNextEntry()) != null) {
                putTemplePackEntry(zip, entry.getName());
                originals.transferTo(zip);
                zip.closeEntry();
            }
            putTemplePackEntry(zip, "assets/neyrotemple/sounds.json");
            zip.write("""
                    {"ambience":{"sounds":[{"name":"neyrotemple:ambience","stream":true}]},"warden":{"sounds":["neyrotemple:warden"]}}
                    """.getBytes(java.nio.charset.StandardCharsets.UTF_8));
            zip.closeEntry();
            putTemplePackEntry(zip, "assets/neyrotemple/sounds/ambience.ogg");
            track.transferTo(zip);
            zip.closeEntry();
            try (InputStream warden = plugin.getResource("voice/anarchy_warden.ogg")) {
                if (warden != null) {
                    putTemplePackEntry(zip, "assets/neyrotemple/sounds/warden.ogg");
                    warden.transferTo(zip);
                    zip.closeEntry();
                }
            }
        }
        return bytes.toByteArray();
    }

    private static void putTemplePackEntry(ZipOutputStream zip, String name) throws IOException {
        ZipEntry entry = new ZipEntry(name);
        entry.setTime(0L);
        zip.putNextEntry(entry);
    }

    private byte[] addHudTextures(byte[] base) throws IOException {
        Map<String, byte[]> textures = new LinkedHashMap<>();
        for (String name : List.of("hotbar", "hotbar_selection", "hotbar_offhand_left", "hotbar_offhand_right",
                "experience_bar_background", "experience_bar_progress", "locator_bar_background")) {
            try (InputStream resource = plugin.getResource("voice/hud/" + name + ".png")) {
                if (resource == null) throw new IOException("Missing HUD sprite: " + name);
                textures.put("assets/minecraft/textures/gui/sprites/hud/" + name + ".png", resource.readAllBytes());
            }
        }
        ByteArrayOutputStream output = new ByteArrayOutputStream(base.length);
        try (ZipInputStream input = new ZipInputStream(new ByteArrayInputStream(base));
             ZipOutputStream zip = new ZipOutputStream(output)) {
            ZipEntry entry;
            while ((entry = input.getNextEntry()) != null) {
                if (textures.containsKey(entry.getName())) continue;
                putTemplePackEntry(zip, entry.getName());
                input.transferTo(zip);
                zip.closeEntry();
            }
            for (var texture : textures.entrySet()) {
                putTemplePackEntry(zip, texture.getKey());
                zip.write(texture.getValue());
                zip.closeEntry();
            }
        }
        return output.toByteArray();
    }

    private static Pack darkUi(byte[] base) throws IOException {
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        try (ZipInputStream input = new ZipInputStream(new ByteArrayInputStream(base));
             ZipOutputStream zip = new ZipOutputStream(output)) {
            ZipEntry entry;
            while ((entry = input.getNextEntry()) != null) {
                String path = entry.getName();
                boolean gui = path.contains("/textures/gui/") && path.endsWith(".png");
                if (!gui && !path.equals("pack.mcmeta")) continue;
                zip.putNextEntry(new ZipEntry(path));
                if (gui) {
                    var decoded = javax.imageio.ImageIO.read(new ByteArrayInputStream(input.readAllBytes()));
                    if (decoded == null) throw new IOException("Invalid texture: " + path);
                    var texture = new java.awt.image.BufferedImage(decoded.getWidth(), decoded.getHeight(),
                            java.awt.image.BufferedImage.TYPE_INT_ARGB);
                    texture.setRGB(0, 0, decoded.getWidth(), decoded.getHeight(),
                            decoded.getRGB(0, 0, decoded.getWidth(), decoded.getHeight(), null, 0, decoded.getWidth()),
                            0, decoded.getWidth());
                    for (int y = 0; y < texture.getHeight(); y++) {
                        for (int x = 0; x < texture.getWidth(); x++) {
                            int pixel = texture.getRGB(x, y);
                            int red = (pixel >>> 16) & 255, green = (pixel >>> 8) & 255, blue = pixel & 255;
                            if (blue > red + 8 || path.endsWith("/hotbar_selection.png") && green > red + 8)
                                texture.setRGB(x, y, (pixel & 0xff000000) | (Math.max(green, blue) << 16)
                                        | ((red / 2) << 8) | (red / 2));
                        }
                    }
                    javax.imageio.ImageIO.write(texture, "PNG", zip);
                } else input.transferTo(zip);
                zip.closeEntry();
            }
            zip.putNextEntry(new ZipEntry("assets/minecraft/textures/entity/player/wide/neyro_dark_lord.png"));
            javax.imageio.ImageIO.write(darkLordSkin(), "PNG", zip);
            zip.closeEntry();
        }
        byte[] bytes = output.toByteArray();
        return new Pack(bytes, sha1(bytes));
    }

    private static java.awt.image.BufferedImage darkLordSkin() {
        // Minecraft's classic 64x64 skin atlas. Unused UVs and empty overlay pixels stay transparent.
        var skin = new java.awt.image.BufferedImage(64, 64, java.awt.image.BufferedImage.TYPE_INT_ARGB);
        skinCuboid(skin, 0, 0, 8, 8, 8, 0x342c3a);
        skinCuboid(skin, 16, 16, 8, 12, 4, 0x2b2431);
        skinCuboid(skin, 40, 16, 4, 12, 4, 0x382c3b);
        skinCuboid(skin, 32, 48, 4, 12, 4, 0x382c3b);
        skinCuboid(skin, 0, 16, 4, 12, 4, 0x251e2a);
        skinCuboid(skin, 16, 48, 4, 12, 4, 0x251e2a);

        // Lacquered visor under a raised, split crest.
        skinFace(skin, 8, 8,
                "sSSbbSSs",
                "SbcRRcbS",
                "SbRRRRbS",
                "bgoRRogb",
                "bbyRRybb",
                "SsRRRRsS",
                "SssccssS",
                "bsbbbbSb");
        skinFace(skin, 40, 8,
                "..g..g..",
                ".sSccSs.",
                "sS....Ss",
                "s......s",
                "s......s",
                "S......S",
                ".s....s.",
                "........");
        skinFace(skin, 0, 8,
                "sSSSSsss", "SSssbbbs", "sScRRccs", "sSccccss",
                "bssccsSb", "bsSSSSsb", "bbssssbb", "bbbbbbbb");
        skinFace(skin, 16, 8,
                "sssSSSSs", "sbbbssSS", "sccRRcSs", "ssccccSs",
                "bSscsssb", "bsSSSSsb", "bbssssbb", "bbbbbbbb");
        skinFace(skin, 24, 8,
                "sSSSSSSs", "SssssssS", "SsbccbsS", "SbsccsbS",
                "sbsRRsbs", "bssccssb", "bbssssbb", "bbbbbbbb");

        // Ribbed cuirass, diagonal sash and a central dark metal buckle.
        skinFace(skin, 20, 20,
                "SSsbbSSS", "sSScbSss", "bsSRcsSb", "bsScRcsb",
                "bssscRcb", "bssSscRb", "bssssscb", "bshgghsb",
                "bssssssb", "bscbbcsb", "bscbbcsb", "bbbbbbbb");
        skinFace(skin, 32, 20,
                "sSSbbSSs", "SSsccsSS", "SsccRcsS", "bsccccsb",
                "bssssssb", "bsShhSsb", "bsSssSsb", "bshgghsb",
                "bssccssb", "bsccRcsb", "bsccccsb", "bbbbbbbb");
        skinFace(skin, 20, 36,
                ".SS..SS.", "........", ".....c..", "......c.",
                ".......c", "........", "........", "..sggs..",
                "........", "........", "........", "........");

        for (int[] arm : new int[][]{{44, 20}, {36, 52}}) {
            skinFace(skin, arm[0], arm[1],
                    "SSSS", "SccS", "sRRs", "ssss", "bSSb", "bssb",
                    "bssb", "SccS", "SggS", "sSSs", "sbbS", "bbbb");
            int side = arm[0] == 44 ? arm[0] - 4 : arm[0] + 4;
            skinFace(skin, side, arm[1],
                    "SSSS", "SccS", "sRRs", "ssss", "bSSb", "bssb",
                    "bssb", "SccS", "SggS", "sSSs", "sbbS", "bbbb");
        }
        for (int[] leg : new int[][]{{4, 20}, {20, 52}}) {
            skinFace(skin, leg[0], leg[1],
                    "scbs", "scbs", "scbs", "sSss", "SSSs", "ssss",
                    "bsSb", "SccS", "Sssh", "Ssss", "shhs", "bbbb");
        }
        return skin;
    }

    private static void skinCuboid(java.awt.image.BufferedImage skin, int u, int v,
                                   int width, int height, int depth, int color) {
        skinRect(skin, u + depth, v, 2 * width, depth, color + 0x080808);
        skinRect(skin, u, v + depth, 2 * (width + depth), height, color);
    }

    private static void skinRect(java.awt.image.BufferedImage skin, int x0, int y0, int width, int height, int color) {
        for (int y = 0; y < height; y++) {
            for (int x = 0; x < width; x++) skin.setRGB(x0 + x, y0 + y, 0xff000000 | color);
        }
    }

    private static void skinFace(java.awt.image.BufferedImage skin, int x0, int y0, String... rows) {
        for (int y = 0; y < rows.length; y++) {
            for (int x = 0; x < rows[y].length(); x++) {
                int pixel = switch (rows[y].charAt(x)) {
                    case '.' -> 0;
                    case 'b' -> 0x1a1520;
                    case 's' -> 0x3b3041;
                    case 'S' -> 0x686073;
                    case 'h' -> 0x76604d;
                    case 'c' -> 0x7d172e;
                    case 'R' -> 0xc73949;
                    case 'g' -> 0xbb8052;
                    case 'o' -> 0xffa645;
                    case 'y' -> 0xffe39a;
                    default -> throw new IllegalArgumentException("Unknown skin color");
                };
                skin.setRGB(x0 + x, y0 + y, pixel == 0 ? 0 : pixel | 0xff000000);
            }
        }
    }

    private void send(Player p, String key) {
        String host = hostFor(p);
        Pack pack = packs.get(key);
        if (host == null || pack == null) return;
        String url = key.equals("base") ? host + BASE_PATH : host + PLAYER_PATH + key + ".zip";
        ResourcePackInfo info = ResourcePackInfo.resourcePackInfo(PACK_ID, URI.create(url), pack.sha1());
        p.sendResourcePacks(ResourcePackRequest.resourcePackRequest()
                .packs(info)
                .required(false)
                .replace(false)
                .prompt(Component.text("Интерфейс, голоса Саманты и Ами, музыка и модели NeyroCraft", TextColor.color(0xFF8A1E)))
                .callback((pid, status, audience) -> {
                    if (status == ResourcePackStatus.SUCCESSFULLY_LOADED) loadedKey.put(p.getUniqueId(), key);
                    else if (!status.intermediate()) loadedKey.remove(p.getUniqueId());
                }));
        if (p.getWorld().getName().equals("neyro_hub3") || p.getWorld().getName().equals(DarkAnarchy.WORLD_NAME)) {
            p.removeResourcePacks(DARK_UI_ID);
            syncDark(p);
        }
    }

    /** Our own retro textures, only while the player is in the "Minecraft 1.0" world. */
    void sendRetro(Player p) {
        String host = hostFor(p);
        if (http == null || retroPack == null || host == null) return;
        p.sendResourcePacks(ResourcePackRequest.resourcePackRequest()
                .packs(ResourcePackInfo.resourcePackInfo(RETRO_ID, URI.create(host + RETRO_PATH), retroPack.sha1()))
                .required(false)
                .replace(false)
                .prompt(Component.text("Ретро-текстуры и звуки режима Minecraft 1.0 (0,2 МБ)", TextColor.color(0x55FF55))));
    }

    void removeRetro(Player p) {
        if (p.isOnline()) p.removeResourcePacks(RETRO_ID);
    }

    // ------------------------------------------------------------ one voice at a time, per player

    private final Map<UUID, List<Key>> speaking = new ConcurrentHashMap<>();
    private final Map<UUID, Long> busyUntil = new ConcurrentHashMap<>();

    /** True while Samantha or Ami is still talking to this player. */
    boolean busy(Player p) {
        Long until = busyUntil.get(p.getUniqueId());
        return until != null && System.currentTimeMillis() < until;
    }

    /** Stops whatever voice line this player is hearing right now. */
    private final Map<UUID, Integer> sessions = new ConcurrentHashMap<>();

    void silence(Player p) {
        sessions.merge(p.getUniqueId(), 1, Integer::sum);          // cancels the rest of a multi-part line
        List<Key> keys = speaking.remove(p.getUniqueId());
        busyUntil.remove(p.getUniqueId());
        if (keys != null && p.isOnline())
            for (Key k : keys) p.stopSound(net.kyori.adventure.sound.SoundStop.namedOnSource(k, Sound.Source.VOICE));
    }

    /** Plays a voice line only for this player, cutting any line still playing; busy for {@code seconds}. */
    void speak(Player p, Key key, double seconds) {
        silence(p);
        voiceLine(p, key, seconds);
    }

    private void voiceLine(Player p, Key key, double seconds) {
        speaking.computeIfAbsent(p.getUniqueId(), k -> new java.util.concurrent.CopyOnWriteArrayList<>()).add(key);
        busyUntil.merge(p.getUniqueId(), System.currentTimeMillis() + Math.round(seconds * 1000), Math::max);
        p.playSound(Sound.sound(key, Sound.Source.VOICE, 1f, 1f), Sound.Emitter.self());
    }

    boolean hasVoice(Player p) {
        return loadedKey.containsKey(p.getUniqueId());
    }

    void forget(Player p) {
        loadedKey.remove(p.getUniqueId());
        offered.remove(p.getUniqueId());
    }

    /**
     * "Добро пожаловать на сервер НейроКрафт," — name — "Я — Ядро Сервера, Саманта…".
     * Returns how long she speaks (ticks), or -1 if the player can't hear her.
     */
    long playWelcome(Player p) {
        String key = loadedKey.get(p.getUniqueId());
        if (key == null) return -1;
        NameClip clip = key.equals("base") ? null : clips.get(key);
        double nameAt = A_SPEECH, bAt = A_SPEECH + (clip != null ? Math.max(0.4, clip.seconds() - TAIL) : 0.3);
        double total = bAt + B_LENGTH;
        // only this player hears it, and nothing else talks over it
        speak(p, PART_A, total);
        int session = sessions.merge(p.getUniqueId(), 1, Integer::sum);
        if (clip != null) {
            Key nameKey = Key.key("neyrocraft", "name_" + clip.key());
            later(p, nameAt, () -> { if (sessions.get(p.getUniqueId()) == session) voiceLine(p, nameKey, total - nameAt); });
        }
        later(p, bAt, () -> { if (sessions.get(p.getUniqueId()) == session) voiceLine(p, PART_B, B_LENGTH); });
        return Math.round(total * 20);
    }

    private void later(Player p, double seconds, Runnable r) {
        Bukkit.getScheduler().runTaskLater(plugin, () -> { if (p.isOnline()) r.run(); }, Math.max(1, Math.round(seconds * 20)));
    }

    // ------------------------------------------------------------ name recordings

    private NameClip nameClip(String name) throws Exception {
        String key = HexFormat.of().formatHex(MessageDigest.getInstance("SHA-1")
                .digest((NAME_VERSION + ":" + name.toLowerCase(Locale.ROOT)).getBytes(StandardCharsets.UTF_8))).substring(0, 16);
        NameClip known = clips.get(key);
        if (known != null) return known;
        File ogg = new File(namesDir, key + ".ogg"), meta = new File(namesDir, key + ".txt");
        if (!ogg.exists() || !meta.exists()) {
            File wav = new File(namesDir, key + ".wav");
            String spoken = speakable(name) + ".";
            String out = run(List.of("powershell", "-NoProfile", "-ExecutionPolicy", "Bypass", "-File",
                    new File(toolsDir, "samantha.ps1").getAbsolutePath(), "-Text", spoken, "-Out", wav.getAbsolutePath()), 60);
            String secs = out.replaceAll("(?s).*OK\\s+([0-9.,]+).*", "$1").replace(',', '.');
            run(List.of(blender, "--background", "--factory-startup", "--python",
                    new File(toolsDir, "wav2ogg.py").getAbsolutePath(), "--", wav.getAbsolutePath(), ogg.getAbsolutePath(), "MONO", "96"), 120);
            if (!ogg.exists()) throw new IOException("ogg was not produced");
            Files.writeString(meta.toPath(), secs);
            wav.delete();
            plugin.getLogger().info("Samantha recorded the name " + name + " (\"" + spoken + "\", " + secs + " s)");
        }
        NameClip clip = new NameClip(key, Double.parseDouble(Files.readString(meta.toPath()).trim()));
        clips.put(key, clip);
        return clip;
    }

    private static String run(List<String> cmd, int timeoutSec) throws Exception {
        Process pr = new ProcessBuilder(cmd).redirectErrorStream(true).start();
        byte[] out = pr.getInputStream().readAllBytes();
        if (!pr.waitFor(timeoutSec, TimeUnit.SECONDS)) { pr.destroyForcibly(); throw new IOException("timeout: " + cmd.get(0)); }
        return new String(out, StandardCharsets.UTF_8);
    }

    private Pack buildPlayerPack(NameClip clip) throws IOException {
        byte[] base = packs.get("base").zip();
        ByteArrayOutputStream bos = new ByteArrayOutputStream(base.length + 64_000);
        try (ZipInputStream zin = new ZipInputStream(new java.io.ByteArrayInputStream(base));
             ZipOutputStream zout = new ZipOutputStream(bos)) {
            ZipEntry e;
            while ((e = zin.getNextEntry()) != null) {
                byte[] data = zin.readAllBytes();
                if (e.getName().equals("assets/neyrocraft/sounds.json")) {
                    String json = new String(data, StandardCharsets.UTF_8).trim();
                    json = json.substring(0, json.lastIndexOf('}')) + ",\"name_" + clip.key() + "\":{\"sounds\":[{\"name\":\"neyrocraft:names/"
                            + clip.key() + "\",\"stream\":true}],\"subtitle\":\"Саманта говорит\"}}";
                    data = json.getBytes(StandardCharsets.UTF_8);
                }
                zout.putNextEntry(new ZipEntry(e.getName()));
                zout.write(data);
                zout.closeEntry();
            }
            zout.putNextEntry(new ZipEntry("assets/neyrocraft/sounds/names/" + clip.key() + ".ogg"));
            zout.write(Files.readAllBytes(new File(namesDir, clip.key() + ".ogg").toPath()));
            zout.closeEntry();
        }
        byte[] zip = bos.toByteArray();
        return new Pack(zip, sha1(zip));
    }

    private static String sha1(byte[] b) {
        try { return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-1").digest(b)); }
        catch (Exception e) { throw new IllegalStateException(e); }
    }

    // ------------------------------------------------------------ nickname -> Russian reading

    private static final Map<String, String> WORDS = new LinkedHashMap<>();
    static {
        String[] w = {"games", "геймс", "game", "гейм", "gamer", "геймер", "gaming", "гейминг", "pro", "про", "master", "мастер",
                "killer", "киллер", "craft", "крафт", "mine", "майн", "dark", "дарк", "night", "найт", "player", "плеер",
                "boy", "бой", "girl", "гёрл", "king", "кинг", "lord", "лорд", "shadow", "шэдоу", "ghost", "гоуст",
                "wolf", "вульф", "fox", "фокс", "cat", "кэт", "dog", "дог", "super", "супер", "mega", "мега", "neo", "нео",
                "the", "зе", "hun", "хан", "x", "икс", "yt", "ютуб", "tv", "тиви", "max", "макс", "alex", "алекс",
                "steve", "стив", "nick", "ник", "hero", "хиро", "star", "стар", "sky", "скай", "fire", "файр", "ice", "айс",
                "light", "лайт", "blade", "блейд", "storm", "шторм", "red", "ред", "blue", "блу", "black", "блэк",
                "white", "вайт", "green", "грин", "cool", "кул", "best", "бест", "big", "биг", "little", "литл", "mr", "мистер",
                "john", "джон", "johny", "джонни", "johnny", "джонни", "jack", "джек", "chelsea", "челси", "minecraft", "майнкрафт"};
        for (int i = 0; i < w.length; i += 2) WORDS.put(w[i], w[i + 1]);
    }
    private static final String[][] DIGRAPHS = {{"shch", "щ"}, {"sch", "ш"}, {"sh", "ш"}, {"ch", "ч"}, {"zh", "ж"}, {"kh", "х"},
            {"ts", "ц"}, {"ya", "я"}, {"yu", "ю"}, {"yo", "ё"}, {"ye", "е"}, {"ja", "я"}, {"ju", "ю"}, {"jo", "ё"}, {"ph", "ф"},
            {"th", "т"}, {"ck", "к"}, {"qu", "кв"}, {"oo", "у"}, {"ee", "и"}, {"ai", "ай"}, {"ei", "ей"}, {"oi", "ой"}};

    static String speakable(String nick) {
        String spaced = nick.replaceAll("^[xX]{1,3}_+|_+[xX]{1,3}$", "")      // xX_..._Xx decorations
                .replaceAll("[^A-Za-zА-Яа-яЁё0-9]+", " ")
                .replaceAll("(?<=[a-zа-яё])(?=[A-ZА-ЯЁ])", " ")
                .replaceAll("(?<=[A-Za-zА-Яа-яЁё])(?=[0-9])|(?<=[0-9])(?=[A-Za-zА-Яа-яЁё])", " ").trim();
        StringBuilder out = new StringBuilder();
        for (String word : spaced.split("\\s+")) {
            if (word.isEmpty()) continue;
            if (out.length() > 0) out.append(' ');
            String w = word.toLowerCase(Locale.ROOT);
            if (WORDS.containsKey(w)) { out.append(cap(WORDS.get(w))); continue; }
            if (!w.matches(".*[a-z].*")) { out.append(word); continue; }
            out.append(cap(translit(w)));
        }
        String s = out.toString().trim();
        return s.isEmpty() ? "путник" : s;
    }

    private static String translit(String w) {
        StringBuilder r = new StringBuilder();
        int i = 0;
        outer:
        while (i < w.length()) {
            for (String[] d : DIGRAPHS) {
                if (i == 0 && d[0].startsWith("j")) continue;      // word-initial J reads as "Дж" (John, Jack)
                if (w.startsWith(d[0], i)) { r.append(d[1]); i += d[0].length(); continue outer; }
            }
            char c = w.charAt(i);
            char next = i + 1 < w.length() ? w.charAt(i + 1) : ' ';
            char prev = i > 0 ? w.charAt(i - 1) : ' ';
            boolean prevVowel = "aeiouy".indexOf(prev) >= 0;
            switch (c) {
                case 'a' -> r.append('а'); case 'b' -> r.append('б');
                case 'c' -> r.append("eiy".indexOf(next) >= 0 ? 'с' : 'к');
                case 'd' -> r.append('д'); case 'e' -> r.append(i == 0 ? 'э' : 'е'); case 'f' -> r.append('ф');
                case 'g' -> r.append('г'); case 'h' -> r.append('х'); case 'i' -> r.append('и'); case 'j' -> r.append("дж");
                case 'k' -> r.append('к'); case 'l' -> r.append('л'); case 'm' -> r.append('м'); case 'n' -> r.append('н');
                case 'o' -> r.append('о'); case 'p' -> r.append('п'); case 'q' -> r.append('к'); case 'r' -> r.append('р');
                case 's' -> r.append('с'); case 't' -> r.append('т'); case 'u' -> r.append('у'); case 'v' -> r.append('в');
                case 'w' -> r.append('в'); case 'x' -> r.append("кс"); case 'y' -> r.append(prevVowel ? 'й' : 'и');
                case 'z' -> r.append('з');
                default -> r.append(c);
            }
            i++;
        }
        return r.toString();
    }

    private static String cap(String s) {
        return s.isEmpty() ? s : Character.toUpperCase(s.charAt(0)) + s.substring(1);
    }
}
