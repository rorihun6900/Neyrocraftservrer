package net.neyrocraft.tab;

import net.kyori.adventure.key.Key;
import net.kyori.adventure.sound.Sound;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.TextComponent;
import net.kyori.adventure.text.format.NamedTextColor;
import net.kyori.adventure.text.format.TextColor;
import net.kyori.adventure.text.format.TextDecoration;
import net.kyori.adventure.title.Title;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.bukkit.event.Event;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.plugin.java.JavaPlugin;

import java.time.Duration;

/**
 * NeyroTab: animated tab header with the server name, ping + TPS next to every
 * player in the tab list, and a welcome title with the player's nickname.
 */
public final class NeyroTab extends JavaPlugin implements Listener {

    private static final String NEYRO = "Neyro";
    private static final String CRAFT = "Craft";

    // Base and highlight colours for the moving "shine" across the name.
    private static final int NEYRO_BASE = 0xFF8C00;   // orange
    private static final int NEYRO_SHINE = 0xFFE0A0;  // light orange
    private static final int CRAFT_BASE = 0x4FC3FF;   // cosmic blue
    private static final int CRAFT_SHINE = 0xD8F4FF;  // pale starlight

    private static final int HEADER_PERIOD_TICKS = 2;   // header animation step (0.1 s)
    private static final int NAMES_PERIOD_TICKS = 40;   // ping/TPS refresh (2 s)

    private int frame = 0;
    private boolean authMePresent = false;

    @Override
    public void onEnable() {
        getServer().getPluginManager().registerEvents(this, this);
        hookAuthMeLogin();
        Bukkit.getScheduler().runTaskTimer(this, this::tickHeader, 20L, HEADER_PERIOD_TICKS);
        Bukkit.getScheduler().runTaskTimer(this, this::tickNames, 20L, NAMES_PERIOD_TICKS);
        getLogger().info("NeyroTab enabled" + (authMePresent ? " (welcome after AuthMe login)" : ""));
    }

    @Override
    public void onDisable() {
        for (Player p : Bukkit.getOnlinePlayers()) {
            p.sendPlayerListHeaderAndFooter(Component.empty(), Component.empty());
            p.playerListName(null);
        }
    }

    // ---------------------------------------------------------------- welcome

    /** If AuthMe is installed, greet the player after a successful /login or /register. */
    @SuppressWarnings("unchecked")
    private void hookAuthMeLogin() {
        if (Bukkit.getPluginManager().getPlugin("AuthMe") == null) return;
        try {
            Class<? extends Event> loginEvent =
                    (Class<? extends Event>) Class.forName("fr.xephi.authme.events.LoginEvent");
            Bukkit.getPluginManager().registerEvent(loginEvent, this, EventPriority.MONITOR, (listener, event) -> {
                if (!loginEvent.isInstance(event)) return;
                try {
                    Object player = event.getClass().getMethod("getPlayer").invoke(event);
                    if (player instanceof Player p) {
                        Bukkit.getScheduler().runTaskLater(this, () -> welcome(p), 10L);
                    }
                } catch (ReflectiveOperationException e) {
                    getLogger().warning("Could not read AuthMe LoginEvent: " + e);
                }
            }, this);
            authMePresent = true;
        } catch (ClassNotFoundException e) {
            getLogger().warning("AuthMe found but LoginEvent class is missing, greeting on join instead");
        }
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onJoin(PlayerJoinEvent e) {
        Player p = e.getPlayer();
        updateName(p);
        Bukkit.getScheduler().runTaskLater(this, () -> { if (p.isOnline()) updateName(p); }, 10L);
        // NeyroHub greets players itself after they log in
        if (!authMePresent && Bukkit.getPluginManager().getPlugin("NeyroHub") == null) {
            Bukkit.getScheduler().runTaskLater(this, () -> welcome(p), 20L);
        }
    }

    private void welcome(Player p) {
        if (!p.isOnline()) return;
        Component title = serverName(-100, true);
        Component subtitle = Component.text()
                .append(Component.text("Добро пожаловать, ", NamedTextColor.GRAY))
                .append(Component.text(p.getName(), TextColor.color(NEYRO_BASE)).decorate(TextDecoration.BOLD))
                .append(Component.text("!", NamedTextColor.GRAY))
                .build();
        // NeyroHub plays its own arrival cinematic with a title; don't overlap it
        if (Bukkit.getPluginManager().getPlugin("NeyroHub") == null) {
            p.showTitle(Title.title(title, subtitle,
                    Title.Times.times(Duration.ofMillis(800), Duration.ofMillis(3000), Duration.ofMillis(1000))));
            p.playSound(Sound.sound(Key.key("minecraft", "entity.player.levelup"), Sound.Source.MASTER, 0.7f, 1.3f));
        }
        p.sendMessage(Component.text()
                .append(Component.text("✦ ", TextColor.color(NEYRO_BASE)))
                .append(Component.text("Привет, ", NamedTextColor.GRAY))
                .append(Component.text(p.getName(), TextColor.color(NEYRO_BASE)))
                .append(Component.text("! Приятной игры на ", NamedTextColor.GRAY))
                .append(serverName(-100, false))
                .append(Component.text(" ✦", TextColor.color(NEYRO_BASE)))
                .build());
    }

    // ------------------------------------------------------------------- tab

    private void tickHeader() {
        frame++;
        if (Bukkit.getOnlinePlayers().isEmpty()) return;
        int cycle = (NEYRO.length() + CRAFT.length()) + 14;   // shine travels across, then pauses
        int shinePos = (frame % cycle) - 2;
        Component name = serverName(shinePos, true);
        Component deco = decoLine(frame);
        double tps = Math.min(20.0, Bukkit.getTPS()[0]);
        int online = Bukkit.getOnlinePlayers().size();
        int max = Bukkit.getMaxPlayers();

        for (Player p : Bukkit.getOnlinePlayers()) {
            Component header = Component.text()
                    .append(starfield(frame, 11, 1)).append(Component.newline())
                    .append(cometLine(frame)).append(Component.newline())
                    .append(deco).append(Component.text("  ")).append(name).append(Component.text("  ")).append(deco)
                    .append(Component.newline())
                    .append(nebula(frame)).append(Component.newline())
                    .append(Component.text("Привет, ", NamedTextColor.GRAY))
                    .append(Component.text(p.getName(), TextColor.color(NEYRO_BASE)))
                    .append(Component.text("! Добро пожаловать на борт.", NamedTextColor.GRAY))
                    .append(Component.newline())
                    .append(starfield(frame, 29, -1))
                    .build();
            Component footer = Component.text()
                    .append(Component.newline())
                    .append(Component.text("Онлайн: ", NamedTextColor.GRAY))
                    .append(Component.text(online + "/" + max, NamedTextColor.WHITE))
                    .append(Component.text("   •   ", NamedTextColor.DARK_GRAY))
                    .append(Component.text("TPS: ", NamedTextColor.GRAY))
                    .append(Component.text(String.format("%.1f", tps), tpsColor(tps)))
                    .append(Component.text("   •   ", NamedTextColor.DARK_GRAY))
                    .append(Component.text("Твой пинг: ", NamedTextColor.GRAY))
                    .append(Component.text(p.getPing() + " мс", pingColor(p.getPing())))
                    .append(Component.newline())
                    .append(planets(frame))
                    .build();
            p.sendPlayerListHeaderAndFooter(header, footer);
        }
    }

    private void tickNames() {
        for (Player p : Bukkit.getOnlinePlayers()) updateName(p);
    }

    private void updateName(Player p) {
        double tps = Math.min(20.0, Bukkit.getTPS()[0]);
        int ping = p.getPing();
        // rank prefix stored by NeyroRanks
        Component prefix = Component.empty();
        String json = p.getPersistentDataContainer().get(new org.bukkit.NamespacedKey("neyroranks", "prefix"), org.bukkit.persistence.PersistentDataType.STRING);
        if (json != null) {
            try { prefix = net.kyori.adventure.text.serializer.gson.GsonComponentSerializer.gson().deserialize(json).append(Component.text(" ")); }
            catch (RuntimeException ignored) { }
        }
        p.playerListName(Component.text()
                .append(prefix)
                .append(Component.text(p.getName(), NamedTextColor.WHITE))
                .append(Component.text("  " + ping + "ms", pingColor(ping)))
                .append(Component.text(" | ", NamedTextColor.DARK_GRAY))
                .append(Component.text(String.format("%.1f", tps) + " TPS", tpsColor(tps)))
                .build());
    }

    // ------------------------------------------------------------- space animation

    private static final int WIDTH = 46;

    /** A drifting field of twinkling stars ({@code dir} = scroll direction). */
    private static Component starfield(int f, int seed, int dir) {
        TextComponent.Builder b = Component.text();
        int shift = Math.floorDiv(f * dir, 3);
        for (int i = 0; i < WIDTH; i++) {
            int x = Math.floorMod(i + shift, 97);
            int h = hash(x * 31 + seed);
            if (h % 100 < 22) {
                String[] glyphs = {"·", "⋆", "✦", "✧", "∗"};
                String g = glyphs[h % glyphs.length];
                double tw = (Math.sin((f + h % 50) * 0.35) + 1) / 2;                 // twinkle
                int[] tints = {0xFFFFFF, 0xB8D8FF, 0xD8B8FF, 0xFFE8B0};
                int c = mix(0x2A3050, tints[h % tints.length], 0.35 + tw * 0.65);
                b.append(Component.text(g, TextColor.color(c)));
            } else {
                b.append(Component.text(" "));
            }
        }
        return b.build();
    }

    /** A comet crossing the sky with a fading tail, then a pause. */
    private static Component cometLine(int f) {
        int period = WIDTH + 30;
        int head = f % period;
        TextComponent.Builder b = Component.text();
        for (int i = 0; i < WIDTH; i++) {
            int d = head - i;
            if (d == 0) b.append(Component.text("☄", TextColor.color(0xFFF4C8)));
            else if (d > 0 && d < 9) b.append(Component.text(d < 3 ? "━" : d < 6 ? "─" : "·", TextColor.color(mix(0x4FC3FF, 0x14182A, d / 9.0))));
            else b.append(Component.text(" "));
        }
        return b.build();
    }

    /** A nebula ribbon: a slowly flowing band of orange-violet-blue. */
    private static Component nebula(int f) {
        TextComponent.Builder b = Component.text();
        int[] stops = {0xFF8C00, 0xB04BFF, 0x4FC3FF, 0xB04BFF, 0xFF8C00};
        for (int i = 0; i < 30; i++) {
            double pos = ((i + f * 0.4) % 30) / 30.0 * (stops.length - 1);
            int k = (int) Math.floor(pos);
            int c = mix(stops[k], stops[Math.min(stops.length - 1, k + 1)], pos - k);
            double wave = (Math.sin(i * 0.5 + f * 0.2) + 1) / 2;
            String[] bars = {"▁", "▂", "▃", "▂"};
            b.append(Component.text(bars[(int) (wave * 3.99)], TextColor.color(mix(0x10101C, c, 0.45 + wave * 0.45))));
        }
        return b.build();
    }

    /** Footer: planets slowly changing phase. */
    private static Component planets(int f) {
        String[] phases = {"◯", "◔", "◑", "◕", "●", "◕", "◑", "◔"};
        int[] colors = {0xFF8C00, 0x4FC3FF, 0xB04BFF};
        TextComponent.Builder b = Component.text();
        for (int i = 0; i < 3; i++) {
            if (i > 0) b.append(Component.text("      ", NamedTextColor.DARK_GRAY));
            b.append(Component.text(phases[((f / 8) + i * 3) % phases.length], TextColor.color(colors[i])));
        }
        return b.build();
    }

    private static int hash(int n) {
        n = (n ^ 61) ^ (n >>> 16); n *= 9; n ^= n >>> 4; n *= 0x27d4eb2d; n ^= n >>> 15;
        return n & 0x7fffffff;
    }

    // --------------------------------------------------------------- visuals

    /** "NeyroCraft" in bold, with a bright band at {@code shinePos} (negative = no shine). */
    private Component serverName(int shinePos, boolean bold) {
        TextComponent.Builder b = Component.text();
        String full = NEYRO + CRAFT;
        for (int i = 0; i < full.length(); i++) {
            boolean neyro = i < NEYRO.length();
            int base = neyro ? NEYRO_BASE : CRAFT_BASE;
            int shine = neyro ? NEYRO_SHINE : CRAFT_SHINE;
            int d = Math.abs(i - shinePos);
            double t = d == 0 ? 1.0 : d == 1 ? 0.55 : d == 2 ? 0.2 : 0.0;
            Component c = Component.text(String.valueOf(full.charAt(i)), TextColor.color(mix(base, shine, t)));
            if (bold) c = c.decorate(TextDecoration.BOLD);
            b.append(c);
        }
        return b.build();
    }

    /** Small pulsing decoration "━━ ✦" that breathes between dark and bright orange. */
    private Component decoLine(int f) {
        double t = (Math.sin(f * 0.25) + 1) / 2;
        int color = mix(0x5A3200, NEYRO_BASE, t);
        String star = (f / 6) % 2 == 0 ? "✦" : "✧";
        return Component.text("━━ " + star + " ━━", TextColor.color(color));
    }

    private static int mix(int a, int b, double t) {
        int r = (int) Math.round(((a >> 16) & 0xFF) * (1 - t) + ((b >> 16) & 0xFF) * t);
        int g = (int) Math.round(((a >> 8) & 0xFF) * (1 - t) + ((b >> 8) & 0xFF) * t);
        int bl = (int) Math.round((a & 0xFF) * (1 - t) + (b & 0xFF) * t);
        return (r << 16) | (g << 8) | bl;
    }

    private static TextColor pingColor(int ping) {
        if (ping < 80) return NamedTextColor.GREEN;
        if (ping < 150) return NamedTextColor.YELLOW;
        if (ping < 250) return NamedTextColor.GOLD;
        return NamedTextColor.RED;
    }

    private static TextColor tpsColor(double tps) {
        if (tps >= 19.0) return NamedTextColor.GREEN;
        if (tps >= 16.0) return NamedTextColor.YELLOW;
        return NamedTextColor.RED;
    }
}
