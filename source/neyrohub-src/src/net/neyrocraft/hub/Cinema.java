package net.neyrocraft.hub;

import net.kyori.adventure.key.Key;
import net.kyori.adventure.sound.Sound;
import net.kyori.adventure.sound.SoundStop;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.TextComponent;
import net.kyori.adventure.text.format.NamedTextColor;
import net.kyori.adventure.text.format.TextColor;
import net.kyori.adventure.text.format.TextDecoration;
import org.bukkit.Axis;
import org.bukkit.Bukkit;
import org.bukkit.Color;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.Particle;
import org.bukkit.World;
import org.bukkit.block.Block;
import org.bukkit.block.BlockFace;
import org.bukkit.block.data.Orientable;
import org.bukkit.block.data.type.Stairs;
import org.bukkit.entity.Display;
import org.bukkit.entity.Entity;
import org.bukkit.entity.ItemDisplay;
import org.bukkit.entity.Player;
import org.bukkit.entity.TextDisplay;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.block.Action;
import org.bukkit.event.entity.EntityDismountEvent;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.event.player.PlayerMoveEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.util.Transformation;
import org.joml.Quaternionf;
import org.joml.Vector3f;

import java.io.DataInputStream;
import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.zip.GZIPInputStream;

/**
 * "Космокино" — a cinema hub floating in open space: a stadium of 680 red seats rising from the stage,
 * open to the stars, and a giant LED screen (36×20 blocks, 160×90 pixels) that shows the NeyroCraft
 * trailer in sessions with its soundtrack. Right-click a seat to sit, Shift to stand up.
 */
final class Cinema implements Listener {
    static final int CX = 6000, CZ = 0;
    private static final int BUILD = 1;
    private static final int HALF_W = 24, FRONT = -30, BACK = 30;     // hall walls (relative)
    private static final int ROWS = 18;
    private static final int STRIPS = 6;
    private static final double SCREEN_BOTTOM = 5, CELL = 9 * 0.025;  // blocks per screen pixel at scale 1
    private static final int INTERMISSION = 80;                       // frames (8 s) between sessions
    private static final Key FONT = Key.key("neyrocraft", "screen");
    private static final Key SOUND = Key.key("neyrocraft", "cinema_trailer");
    private static final String PIXEL = "";

    private final NeyroHub plugin;
    private final World w;
    private final int fy;
    private short[][] frames;
    private int fw, fh;
    private final List<TextDisplay> strips = new ArrayList<>();
    private TextDisplay caption;
    private final List<TextDisplay> endCard = new ArrayList<>();   // crisp text over the last scene (too fine for 160×90 pixels)
    private boolean endShown;
    private final List<Entity> decor = new ArrayList<>();
    private final Map<Long, ItemDisplay> seats = new HashMap<>();
    private final Map<UUID, Long> sitting = new HashMap<>();
    private final Set<UUID> listening = new HashSet<>();
    private int lastShown = -1;                // frame currently on the screen
    private int frame = -INTERMISSION;         // negative: intermission countdown
    private boolean anyone;
    private long tick;

    Cinema(NeyroHub plugin, World hub, int floorY) {
        this.plugin = plugin;
        this.w = hub;
        this.fy = floorY;
    }

    void start() {
        for (int cx = (CX - HALF_W - 2) >> 4; cx <= (CX + HALF_W + 2) >> 4; cx++)
            for (int cz = (CZ + FRONT - 2) >> 4; cz <= (CZ + BACK + 2) >> 4; cz++) w.setChunkForceLoaded(cx, cz, true);
        File marker = new File(plugin.getDataFolder(), "cinema-built-v" + BUILD);
        if (!marker.exists()) {
            build();
            try { Files.writeString(marker.toPath(), "built"); } catch (IOException ignored) { }
        }
        loadFrames();
        spawnDisplays();
        Bukkit.getScheduler().runTaskTimer(plugin, this::tick, 20L, 2L);
        plugin.getLogger().info("Cinema ready: " + (frames == null ? 0 : frames.length) + " frames " + fw + "x" + fh);
    }

    void shutdown() {
        lastShown = -1;
        for (TextDisplay d : strips) if (d.isValid()) d.remove();
        strips.clear();
        if (caption != null && caption.isValid()) caption.remove();
        for (TextDisplay d : endCard) if (d.isValid()) d.remove();
        endCard.clear();
        endShown = false;
        for (Entity e : decor) if (e.isValid()) e.remove();
        decor.clear();
        for (ItemDisplay s : seats.values()) { s.eject(); s.remove(); }
        seats.clear();
        sitting.clear();
        for (UUID id : listening) { Player p = Bukkit.getPlayer(id); if (p != null) p.stopSound(SoundStop.named(SOUND)); }
        listening.clear();
    }

    Location spawn() {
        return new Location(w, CX + 0.5, fy + ROWS + 1, CZ + 27.5, 180f, 8f);
    }

    boolean contains(Location l) {
        if (l == null || !w.equals(l.getWorld())) return false;
        double x = l.getX() - CX, z = l.getZ() - CZ;
        return x > -HALF_W - 6 && x < HALF_W + 6 && z > FRONT - 6 && z < BACK + 6 && l.getY() > fy - 40 && l.getY() < fy + 60;
    }

    // ------------------------------------------------------------ frames

    private void loadFrames() {
        try (InputStream raw = openFrames(); DataInputStream in = new DataInputStream(new GZIPInputStream(raw))) {
            byte[] magic = new byte[4];
            in.readFully(magic);
            int n = Integer.reverseBytes(in.readInt());
            fw = Integer.reverseBytes(in.readInt());
            fh = Integer.reverseBytes(in.readInt());
            in.readInt();                                                     // fps (10)
            frames = new short[n][fw * fh];
            for (int f = 0; f < n; f++) for (int i = 0; i < fw * fh; i++) frames[f][i] = Short.reverseBytes(in.readShort());
        } catch (IOException | RuntimeException e) {
            plugin.getLogger().warning("Cinema: no trailer frames (" + e + ")");
            frames = null;
            fw = 160; fh = 90;
        }
    }

    private InputStream openFrames() throws IOException {
        File f = new File(plugin.getDataFolder(), "cinema/trailer.nct.gz");
        if (f.exists()) return Files.newInputStream(f.toPath());
        InputStream in = plugin.getResource("cinema/trailer.nct.gz");
        if (in == null) throw new IOException("cinema/trailer.nct.gz missing");
        return in;
    }

    /** One horizontal strip of the screen as coloured pixel glyphs; equal neighbours are merged. */
    private Component strip(short[] img, int k) {
        int rows = fh / STRIPS;
        TextComponent.Builder b = Component.text().font(FONT);
        for (int r = k * rows; r < (k + 1) * rows; r++) {
            int x = 0;
            while (x < fw) {
                short c = img[r * fw + x];
                int run = 1;
                while (x + run < fw && img[r * fw + x + run] == c) run++;
                int rr = ((c >> 8) & 15) * 17, gg = ((c >> 4) & 15) * 17, bb = (c & 15) * 17;
                b.append(Component.text(PIXEL.repeat(run), TextColor.color(rr, gg, bb)));
                x += run;
            }
            if (r < (k + 1) * rows - 1) b.append(Component.newline());
        }
        return b.build();
    }

    private void show(int f) {
        if (frames == null || f < 0 || f >= frames.length) return;
        short[] img = frames[f];
        short[] prev = lastShown >= 0 ? frames[lastShown] : null;
        lastShown = f;
        int rows = fh / STRIPS;
        for (int k = 0; k < strips.size(); k++) {
            TextDisplay d = strips.get(k);
            if (!d.isValid()) continue;
            if (prev != null && same(img, prev, k * rows * fw, (k + 1) * rows * fw)) continue;  // nothing changed in this strip
            d.text(strip(img, k));
        }
    }

    private static boolean same(short[] a, short[] b, int from, int to) {
        for (int i = from; i < to; i++) if (a[i] != b[i]) return false;
        return true;
    }

    // ------------------------------------------------------------ sessions

    private static final Object[][] CAPTIONS = {
            {0.0, 4.0, "где-то в глубоком космосе…", 0xC8D2F0},
            {4.0, 8.0, "варп-прыжок", 0x7FE8FF},
            {8.0, 11.5, "NeyroCraft — сервер на космическом корабле", 0xFF8C00},
            {11.5, 15.0, "ГОНКИ НА СПИДЕРАХ", 0xFFA040},
            {15.0, 18.5, "СВЕТОВЫЕ МЕЧИ", 0x7FE8FF},
            {18.5, 22.0, "ЛАЗЕРТАГ", 0xFF5A5A},
            {22.0, 25.5, "MINECRAFT 1.0", 0x55FF55},
            {25.5, 29.0, "SKYBLOCK И ВЫЖИВАНИЕ", 0x55FFFF},
            {29.0, 32.5, "STARPASS — 30 уровней наград", 0xB48CFF},
            {32.5, 36.0, "СТАНЦИЯ «ОРИОН»", 0xE07BFF},
            {36.0, 99.0, "ЗАХОДИ НА БОРТ!  neyrocraft.space", 0xFFD54A},
    };

    private void tick() {
        tick++;
        anyone = false;
        for (Player p : w.getPlayers()) if (contains(p.getLocation())) { anyone = true; break; }
        // listeners who left the hall stop hearing the film
        for (UUID id : new ArrayList<>(listening)) {
            Player p = Bukkit.getPlayer(id);
            if (p == null || !contains(p.getLocation())) { if (p != null) p.stopSound(SoundStop.named(SOUND)); listening.remove(id); }
        }
        if (!anyone) { frame = -INTERMISSION; return; }
        if (frames == null) return;

        if (frame < 0) {
            // intermission: the logo frame and a countdown
            if (frame == -INTERMISSION) show(106);
            int sec = (-frame + 9) / 10;
            if (frame % 10 == 0) caption(Component.text()
                    .append(Component.text("✦ КОСМОКИНО ✦  ", TextColor.color(0xE07BFF)).decorate(TextDecoration.BOLD))
                    .append(Component.text("сеанс через " + sec + " сек  ·  ПКМ по креслу — сесть", NamedTextColor.WHITE)).build());
            frame++;
            if (frame == 0) startSession();
            return;
        }
        show(frame);
        boolean end = frame >= 368 && frame < 418;
        if (end != endShown) endCard(end);
        double t = frame / 10.0;
        if (frame % 5 == 0) for (Object[] c : CAPTIONS)
            if (t >= (double) c[0] && t < (double) c[1]) { caption(Component.text((String) c[2], TextColor.color((int) c[3])).decorate(TextDecoration.BOLD)); break; }
        // the screen lights the stage a little
        if (frame % 4 == 0) {
            short px = frames[frame][fw * (fh / 2) + fw / 2];
            Color col = Color.fromRGB(((px >> 8) & 15) * 17, ((px >> 4) & 15) * 17, (px & 15) * 17);
            w.spawnParticle(Particle.DUST, CX + 0.5, fy + 2.5, CZ - 20, 6, 14, 1.2, 4, 0, new Particle.DustOptions(col, 1.6f));
        }
        frame++;
        if (frame >= frames.length) frame = -INTERMISSION;
        if (frame < 0 && endShown) endCard(false);
    }

    private void startSession() {
        for (Player p : w.getPlayers()) {
            if (!contains(p.getLocation())) continue;
            p.stopSound(SoundStop.named(SOUND));
            p.playSound(Sound.sound(SOUND, Sound.Source.RECORD, 1f, 1f), Sound.Emitter.self());
            listening.add(p.getUniqueId());
        }
    }

    /** Screen pixel row (0 = top of the 90-pixel screen) to world height. */
    private double rowY(double row) {
        return fy + SCREEN_BOTTOM + (fh - row) * CELL;
    }

    private void endCard(boolean on) {
        endShown = on;
        if (endCard.isEmpty()) {
            endCard.add(overlay(rowY(45), 6.0f));
            endCard.add(overlay(rowY(60.5), 4.4f));
            endCard.add(overlay(rowY(70), 3.2f));
        }
        endCard.get(0).text(on ? Component.text("ЗАХОДИ НА БОРТ!", TextColor.color(0xFFD54A)).decorate(TextDecoration.BOLD) : Component.empty());
        endCard.get(1).text(on ? Component.text("neyrocraft.space", NamedTextColor.WHITE).decorate(TextDecoration.BOLD) : Component.empty());
        endCard.get(2).text(on ? Component.text("версия 26.3+  ·  пиратка ✓  ·  вход по паролю", TextColor.color(0xC8D2F0)) : Component.empty());
    }

    private TextDisplay overlay(double y, float scale) {
        return w.spawn(new Location(w, CX + 0.5, y, CZ + FRONT + 1.2, 0f, 0f), TextDisplay.class, t -> {
            plugin.prepare(t);
            t.setViewRange(8f);
            t.setBillboard(Display.Billboard.FIXED);
            t.setBackgroundColor(Color.fromARGB(0, 0, 0, 0));
            t.setShadowed(true);
            t.setLineWidth(1000);
            t.setBrightness(new Display.Brightness(15, 15));
            t.setTransformation(new Transformation(new Vector3f(), new Quaternionf(), new Vector3f(scale, scale, scale), new Quaternionf()));
            t.text(Component.empty());
        });
    }

    private void caption(Component c) {
        if (caption != null && caption.isValid()) caption.text(c);
    }

    // ------------------------------------------------------------ building

    private void set(int x, int y, int z, Material m) { w.getBlockAt(CX + x, y, CZ + z).setType(m, false); }

    private void stairs(int x, int y, int z, Material m, BlockFace face) {
        Block b = w.getBlockAt(CX + x, y, CZ + z);
        b.setType(m, false);
        Stairs s = (Stairs) b.getBlockData();
        s.setFacing(face);
        b.setBlockData(s, false);
    }

    private static int rowZ(int r) { return -10 + 2 * r; }

    private void build() {
        plugin.getLogger().info("Building the cinema...");
        for (int x = -HALF_W - 1; x <= HALF_W + 1; x++)
            for (int z = FRONT - 1; z <= BACK + 1; z++)
                for (int y = fy - 3; y <= fy + 34; y++)
                    if (!w.getBlockAt(CX + x, y, CZ + z).getType().isAir()) set(x, y, z, Material.AIR);
        // floating base and the stage
        for (int x = -HALF_W; x <= HALF_W; x++)
            for (int z = FRONT; z <= BACK; z++) {
                set(x, fy - 1, z, Material.POLISHED_DEEPSLATE);
                set(x, fy - 2, z, (x + z) % 5 == 0 ? Material.AMETHYST_BLOCK : Material.DEEPSLATE);
                set(x, fy, z, z < rowZ(0) ? ((x + z) % 2 == 0 ? Material.DEEPSLATE_TILES : Material.POLISHED_DEEPSLATE) : Material.POLISHED_DEEPSLATE);
            }
        for (int x = -20; x <= 20; x++) set(x, fy, FRONT + 1, Material.SEA_LANTERN);                 // stage light line
        // the stadium: each row is one block higher and two blocks deeper
        for (int r = 0; r < ROWS; r++) {
            int z0 = rowZ(r), lvl = fy + r;
            for (int x = -HALF_W + 1; x <= HALF_W - 1; x++) {
                for (int dz = 0; dz <= 1; dz++)
                    for (int y = fy; y <= lvl; y++) set(x, y, z0 + dz, y == lvl ? (dz == 0 ? Material.DEEPSLATE_TILES : Material.POLISHED_DEEPSLATE) : Material.DEEPSLATE);
                boolean aisle = Math.abs(x) <= 2 || Math.abs(x) >= 22;
                if (aisle) stairs(x, lvl + 1, z0 + 1, Material.POLISHED_BLACKSTONE_STAIRS, BlockFace.SOUTH);
                else stairs(x, lvl + 1, z0 + 1, Material.CRIMSON_STAIRS, BlockFace.SOUTH);
                if (Math.abs(x) == 3 || Math.abs(x) == 21) set(x, lvl, z0, Material.SEA_LANTERN);          // aisle lights
            }
        }
        // the top walkway behind the last row
        int top = fy + ROWS;
        for (int x = -HALF_W + 1; x <= HALF_W - 1; x++)
            for (int z = rowZ(ROWS); z < BACK; z++) for (int y = fy; y <= top; y++) set(x, y, z, y == top ? Material.DEEPSLATE_TILES : Material.DEEPSLATE);
        // walls: dark with glowing purple ribs, open to the sky
        for (int z = FRONT; z <= BACK; z++)
            for (int y = fy + 1; y <= fy + 32; y++)
                for (int s : new int[]{-HALF_W, HALF_W}) {
                    boolean rib = Math.floorMod(z, 6) == 0;
                    set(s, y, z, rib ? (y % 4 == 0 ? Material.PEARLESCENT_FROGLIGHT : Material.PURPLE_STAINED_GLASS) : Material.POLISHED_DEEPSLATE);
                }
        for (int x = -HALF_W; x <= HALF_W; x++)
            for (int y = fy + 1; y <= fy + 32; y++) {
                set(x, y, BACK, Math.floorMod(x, 6) == 0 ? Material.PURPLE_STAINED_GLASS : Material.POLISHED_DEEPSLATE);
                set(x, y, FRONT, Material.BLACK_CONCRETE);                                            // behind the screen
            }
        for (int x = -HALF_W; x <= HALF_W; x++) for (int z : new int[]{FRONT, BACK}) set(x, fy + 33, z, Material.SEA_LANTERN);
        for (int z = FRONT; z <= BACK; z++) for (int s : new int[]{-HALF_W, HALF_W}) set(s, fy + 33, z, Material.SEA_LANTERN);
        // glowing frame around the screen
        int sb = (int) Math.floor(fy + SCREEN_BOTTOM) - 1, st = (int) Math.ceil(fy + SCREEN_BOTTOM + 90 / 6.0 * 6 * CELL) + 1;
        for (int x = -19; x <= 19; x++) { set(x, sb, FRONT + 1, Material.SEA_LANTERN); set(x, st, FRONT + 1, Material.SEA_LANTERN); }
        for (int y = sb; y <= st; y++) { set(-19, y, FRONT + 1, Material.SEA_LANTERN); set(19, y, FRONT + 1, Material.SEA_LANTERN); }
        // the exit portal in the back wall
        org.bukkit.block.data.BlockData portal = Bukkit.createBlockData(Material.NETHER_PORTAL, bd -> ((Orientable) bd).setAxis(Axis.X));
        for (int x = -3; x <= 3; x++)
            for (int y = top + 1; y <= top + 6; y++) {
                boolean edge = Math.abs(x) == 3 || y == top + 6;
                if (edge) set(x, y, BACK - 1, Material.PURPUR_PILLAR);
                else w.getBlockAt(CX + x, y, CZ + BACK - 1).setBlockData(portal, false);
            }
        plugin.getLogger().info("Cinema built");
    }

    // ------------------------------------------------------------ displays

    private void spawnDisplays() {
        shutdown();
        double width = fw * CELL, stripH = (fh / STRIPS) * CELL;
        double topY = fy + SCREEN_BOTTOM + STRIPS * stripH;
        for (int k = 0; k < STRIPS; k++) {
            double y = topY - (k + 1) * stripH;
            TextDisplay d = w.spawn(new Location(w, CX + 0.5, y, CZ + FRONT + 1.08, 0f, 0f), TextDisplay.class, t -> {
                plugin.prepare(t);
                t.setViewRange(8f);
                t.setBillboard(Display.Billboard.FIXED);
                t.setBackgroundColor(Color.fromARGB(0, 0, 0, 0));
                t.setShadowed(false);
                t.setSeeThrough(false);
                t.setLineWidth(4000);
                t.setBrightness(new Display.Brightness(15, 15));
                t.text(Component.empty());
            });
            strips.add(d);
        }
        caption = w.spawn(new Location(w, CX + 0.5, fy + 1.4, CZ + FRONT + 3.5, 0f, 0f), TextDisplay.class, t -> {
            plugin.prepare(t);
            t.setViewRange(8f);
            t.setBillboard(Display.Billboard.FIXED);
            t.setBackgroundColor(Color.fromARGB(150, 10, 6, 24));
            t.setShadowed(true);
            t.setLineWidth(1000);
            t.setTransformation(new Transformation(new Vector3f(), new Quaternionf(), new Vector3f(2.6f, 2.6f, 2.6f), new Quaternionf()));
            t.text(Component.text("✦ КОСМОКИНО ✦", TextColor.color(0xE07BFF)));
        });
        decor.add(w.spawn(new Location(w, CX + 0.5, topY + 3.0, CZ + FRONT + 2.2, 0f, 0f), TextDisplay.class, t -> {
            plugin.prepare(t);
            t.setViewRange(8f);
            t.setBillboard(Display.Billboard.FIXED);
            t.setBackgroundColor(Color.fromARGB(0, 0, 0, 0));
            t.setShadowed(true);
            t.setTransformation(new Transformation(new Vector3f(), new Quaternionf(), new Vector3f(4.5f, 4.5f, 4.5f), new Quaternionf()));
            t.text(Component.text()
                    .append(Component.text("КОСМОКИНО ", TextColor.color(0xE07BFF)).decorate(TextDecoration.BOLD))
                    .append(Component.text("Neyro", TextColor.color(NeyroHub.ORANGE)).decorate(TextDecoration.BOLD))
                    .append(Component.text("Craft", TextColor.color(0x4FC3FF)).decorate(TextDecoration.BOLD)).build());
        }));
        // planets hanging over the open hall
        SpaceDecor.planet(plugin, w, new Location(w, CX + 30, fy + 70, CZ - 60), SpaceDecor.Kind.GAS, 40f, 31);
        SpaceDecor.planet(plugin, w, new Location(w, CX - 45, fy + 55, CZ - 40), SpaceDecor.Kind.MARS, 18f, 5);
        SpaceDecor.planet(plugin, w, new Location(w, CX - 20, fy + 80, CZ + 40), SpaceDecor.Kind.MOON, 10f, 11);
        frame = -INTERMISSION;
    }

    // ------------------------------------------------------------ seats

    private static long key(Block b) {
        return ((long) b.getX() & 0x3FFFFFF) << 38 | ((long) b.getZ() & 0x3FFFFFF) << 12 | (b.getY() & 0xFFF);
    }

    @EventHandler(priority = EventPriority.HIGH)
    public void onSeat(PlayerInteractEvent e) {
        if (e.getAction() != Action.RIGHT_CLICK_BLOCK || e.getHand() != org.bukkit.inventory.EquipmentSlot.HAND) return;
        Block b = e.getClickedBlock();
        if (b == null || b.getType() != Material.CRIMSON_STAIRS || !contains(b.getLocation())) return;
        e.setCancelled(true);
        Player p = e.getPlayer();
        if (p.isInsideVehicle()) return;
        long k = key(b);
        ItemDisplay taken = seats.get(k);
        if (taken != null && taken.isValid() && !taken.getPassengers().isEmpty()) {
            HudBoard.notice(p, Component.text("Это кресло уже занято", NamedTextColor.GRAY));
            return;
        }
        ItemDisplay seat = w.spawn(new Location(w, b.getX() + 0.5, b.getY() + 0.42, b.getZ() + 0.5, 180f, 0f), ItemDisplay.class, plugin::prepare);
        seat.addPassenger(p);
        seats.put(k, seat);
        sitting.put(p.getUniqueId(), k);
        HudBoard.notice(p, Component.text("🍿 Приятного просмотра!  Shift — встать", TextColor.color(0xE07BFF)));
        p.playSound(Sound.sound(Key.key("minecraft", "block.wool.place"), Sound.Source.PLAYER, 0.6f, 0.9f));
    }

    @EventHandler
    public void onStand(EntityDismountEvent e) {
        if (!(e.getEntity() instanceof Player p)) return;
        Long k = sitting.remove(p.getUniqueId());
        if (k == null) return;
        ItemDisplay seat = seats.remove(k);
        if (seat == null) return;
        Location at = seat.getLocation();
        Bukkit.getScheduler().runTask(plugin, () -> {
            if (seat.isValid()) seat.remove();
            if (p.isOnline()) p.teleport(new Location(w, at.getX(), Math.floor(at.getY()), at.getZ() - 1.0, p.getLocation().getYaw(), p.getLocation().getPitch()));
        });
    }

    @EventHandler
    public void onQuit(PlayerQuitEvent e) {
        Long k = sitting.remove(e.getPlayer().getUniqueId());
        if (k != null) { ItemDisplay s = seats.remove(k); if (s != null) s.remove(); }
        listening.remove(e.getPlayer().getUniqueId());
    }

    /** The purple arch at the back takes you back to station Orion. */
    @EventHandler(ignoreCancelled = true)
    public void onMove(PlayerMoveEvent e) {
        Location to = e.getTo();
        if (!contains(to)) return;
        Player p = e.getPlayer();
        if (to.getY() < fy - 20) { p.teleport(spawn()); p.setFallDistance(0); return; }
        if (e.getFrom().getBlockX() == to.getBlockX() && e.getFrom().getBlockY() == to.getBlockY() && e.getFrom().getBlockZ() == to.getBlockZ()) return;
        if (to.getBlock().getType() != Material.NETHER_PORTAL && to.clone().add(0, 1, 0).getBlock().getType() != Material.NETHER_PORTAL) return;
        if (BeamTransfer.busy(p) || WarpTunnel.busy(p)) return;
        p.stopSound(SoundStop.named(SOUND));
        listening.remove(p.getUniqueId());
        Location orion = plugin.orionSpawn();
        BeamTransfer.run(plugin, p, orion != null ? orion : plugin.spawn(), Color.fromRGB(0xE0, 0x7B, 0xFF), Color.fromRGB(0x7F, 0xE8, 0xFF),
                BeamTransfer.title(Component.text("СТАНЦИЯ «ОРИОН»", TextColor.color(0xC08CFF)).decorate(TextDecoration.BOLD),
                        Component.text("после сеанса в Космокино", NamedTextColor.GRAY)));
    }

    // ------------------------------------------------------------ going there

    static final WarpTunnel.Style STYLE = new WarpTunnel.Style(0xE07BFF, 0x7FE8FF, 0xFFE0FF, "Космокино",
            BeamTransfer.title(Component.text("КОСМОКИНО", TextColor.color(0xE07BFF)).decorate(TextDecoration.BOLD),
                    Component.text("огромный экран под звёздами · садись в любое кресло", NamedTextColor.GRAY)));

    void send(Player p) {
        WarpTunnel.run(p, spawn(), STYLE, null);
    }
}
