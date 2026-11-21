package net.neyrocraft.hub;

import net.kyori.adventure.key.Key;
import net.kyori.adventure.sound.Sound;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.TextComponent;
import net.kyori.adventure.text.format.NamedTextColor;
import net.kyori.adventure.text.format.TextColor;
import net.kyori.adventure.text.format.TextDecoration;
import org.bukkit.Bukkit;
import org.bukkit.Color;
import org.bukkit.Location;
import org.bukkit.World;
import org.bukkit.attribute.Attribute;
import org.bukkit.attribute.AttributeInstance;
import org.bukkit.entity.Display;
import org.bukkit.entity.Entity;
import org.bukkit.entity.Interaction;
import org.bukkit.entity.Player;
import org.bukkit.entity.TextDisplay;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.block.Action;
import org.bukkit.event.player.PlayerChangedWorldEvent;
import org.bukkit.event.player.PlayerInteractEntityEvent;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.event.player.PlayerItemHeldEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.event.player.PlayerToggleSneakEvent;
import org.bukkit.inventory.EquipmentSlot;
import org.bukkit.scheduler.BukkitTask;
import org.bukkit.util.Transformation;
import org.bukkit.util.Vector;
import org.joml.AxisAngle4f;
import org.joml.Vector3f;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.function.Consumer;

/**
 * Conversations with NPCs in the style of Mass Effect: the NPC speaks in a window on the left of the
 * view (typed out, with a voice when there is one), then the answers float on a wheel at the bottom of
 * the view. Right side: Light (top, blue), neutral (middle), Dark (bottom, red). Left side: questions,
 * actions and leaving. Picking a Light / Dark answer nudges the player's karma (once per answer), and
 * the scripts read the karma to make the NPC react to who the player is.
 */
final class Dialogue implements Listener {

    /** Who speaks: name, a short title, the name colour and the colour of the text window (ARGB). */
    record Speaker(String name, String title, int color, int window) { }

    /** One line. {@code sound} (a neyrocraft: key) and {@code seconds} only when it is voiced. */
    record Line(String sound, double seconds, String text) {
        static Line of(String text) { return new Line(null, 0, text); }
    }

    /** What an answer is: decides its place on the wheel and its colour. */
    enum Kind {
        LIGHT(0x5AC8FF, "◆ "), NEUTRAL(0xE8E8E8, ""), DARK(0xFF5A5A, "◆ "),
        ASK(0xB9A6FF, "? "), ACTION(0xFFB347, "» "), LEAVE(0x9AA0A6, "");
        final int color;
        final String mark;
        Kind(int color, String mark) { this.color = color; this.mark = mark; }
    }

    /** An answer. {@code karma}: how it moves the player towards the Light (+) or the Dark (-), once. */
    record Choice(String label, Kind kind, int karma, Consumer<Session> then) {
        static Choice of(String label, Kind kind, Consumer<Session> then) { return new Choice(label, kind, 0, then); }
    }

    private record Option(int number, Choice choice, double angleDeg, TextDisplay text, Interaction hit) { }

    private static final float WHEEL_SCALE = 0.33f;

    /** One player talking to one NPC. */
    final class Session {
        final Player player;
        final Speaker speaker;
        final Location focus;
        private TextDisplay panel;
        private BukkitTask typing, timeout, hover;
        private final List<Option> options = new ArrayList<>();
        private final List<Entity> wheelBits = new ArrayList<>();
        private int hovered = -1;
        private float walkSpeed;
        private double jump;
        private Runnable onEnd;
        private boolean over;

        private Session(Player p, Speaker speaker, Location focus) {
            this.player = p;
            this.speaker = speaker;
            this.focus = focus;
        }

        /** The player's karma: -100 (Dark) .. +100 (Light). */
        int karma() { return plugin.karma() == null ? 0 : plugin.karma().get(player); }

        /** -2 villain, -1 dark, 0 neutral, 1 light, 2 hero. */
        int tier() { return NeyroKarma.tier(karma()); }

        boolean active() { return !over && sessions.get(player.getUniqueId()) == this; }

        void onEnd(Runnable r) { onEnd = r; }

        void say(String text, Runnable after) { say(Line.of(text), after); }

        /** Types the line into the window (and plays its voice), then runs {@code after}. */
        void say(Line line, Runnable after) {
            if (!active()) return;
            Player p = player;
            if (typing != null) typing.cancel();
            if (timeout != null) timeout.cancel();
            if (panel == null || !panel.isValid()) panel = spawnPanel(this);
            VoicePack voice = plugin.voice();
            boolean spoken = line.sound() != null && voice != null && voice.hasVoice(p);
            if (spoken) voice.speak(p, Key.key("neyrocraft", line.sound()), line.seconds());
            String text = line.text();
            long totalTicks = spoken ? Math.round((line.seconds() - 0.5) * 20) : Math.max(20, Math.round(text.length() * 0.8));
            double perTick = Math.max(1.0, text.length() / (double) Math.max(1, totalTicks));
            final double[] shown = {0};
            typing = Bukkit.getScheduler().runTaskTimer(plugin, () -> {
                if (!p.isOnline() || panel == null || !panel.isValid()) { end(false); return; }
                shown[0] = Math.min(text.length(), shown[0] + perTick);
                int n = (int) shown[0];
                panel.text(panelText(speaker, text.substring(0, n), n < text.length()));
                if (!spoken && n % 3 == 0 && n < text.length())
                    p.playSound(Sound.sound(Key.key("minecraft", "block.note_block.hat"), Sound.Source.MASTER, 0.22f, 1.8f));
                if (n >= text.length()) {
                    typing.cancel();
                    typing = null;
                    long pause = spoken ? 14L : Math.min(50L, 16L + text.length() / 6);
                    Bukkit.getScheduler().runTaskLater(plugin, () -> { if (active() && after != null) after.run(); }, pause);
                }
            }, 1L, 1L);
        }

        /** Shows the wheel with these answers (numbered in this order: keys 1..n). */
        void choose(List<Choice> choices) {
            if (!active()) return;
            showWheel(this, choices);
        }

        void choose(Choice... choices) { choose(List.of(choices)); }

        /** Says the line, then closes the conversation. */
        void sayAndEnd(String text) { say(text, () -> end(true)); }

        void end(boolean online) { finish(this, online); }
    }

    private final NeyroHub plugin;
    private final Map<UUID, Session> sessions = new HashMap<>();
    private final Map<UUID, Set<String>> karmaTaken = new HashMap<>();

    Dialogue(NeyroHub plugin) {
        this.plugin = plugin;
    }

    boolean talking(Player p) {
        return sessions.containsKey(p.getUniqueId());
    }

    /**
     * Starts a conversation: the player stops and looks at {@code focus} (the NPC's face), then the
     * script runs (it calls {@link Session#say} and {@link Session#choose}).
     */
    Session start(Player p, Speaker speaker, Location focus, Consumer<Session> script) {
        if (talking(p)) return null;
        VoicePack vp = plugin.voice();
        if (vp != null && vp.busy(p)) {
            HudBoard.notice(p, Component.text(speaker.name() + " ждёт, пока Саманта договорит…", TextColor.color(speaker.color())));
            return null;
        }
        Session s = new Session(p, speaker, focus.clone());
        sessions.put(p.getUniqueId(), s);
        s.walkSpeed = p.getWalkSpeed();
        AttributeInstance jump = p.getAttribute(Attribute.JUMP_STRENGTH);
        s.jump = jump == null ? 0.42 : jump.getBaseValue();
        p.setWalkSpeed(0f);
        if (jump != null) jump.setBaseValue(0);
        Location look = p.getLocation();
        look.setDirection(focus.toVector().subtract(p.getEyeLocation().toVector()));
        p.teleport(look);
        p.playSound(Sound.sound(Key.key("minecraft", "block.beacon.power_select"), Sound.Source.MASTER, 0.45f, 1.9f));
        script.accept(s);
        return s;
    }

    void shutdown() {
        for (Session s : new ArrayList<>(sessions.values())) finish(s, true);
    }

    // ------------------------------------------------------------ the text window

    private TextDisplay spawnPanel(Session s) {
        Player p = s.player;
        Location eye = p.getEyeLocation();
        Vector fwd = eye.getDirection().setY(0).normalize();
        Vector left = new Vector(fwd.getZ(), 0, -fwd.getX());
        Location at = eye.clone().add(fwd.clone().multiply(2.0)).add(left.multiply(1.05)).add(0, -0.55, 0);
        int w = s.speaker.window();
        TextDisplay d = p.getWorld().spawn(at, TextDisplay.class, t -> {
            plugin.prepare(t);
            t.setVisibleByDefault(false);
            t.setBillboard(Display.Billboard.CENTER);
            t.setBackgroundColor(Color.fromARGB(w));
            t.setLineWidth(190);
            t.setShadowed(true);
            t.setAlignment(TextDisplay.TextAlignment.LEFT);
            t.setBrightness(new Display.Brightness(15, 15));
            t.setTransformation(scaleT(0.42f));
            t.setSeeThrough(true);
        });
        p.showEntity(plugin, d);
        return d;
    }

    private static Component panelText(Speaker sp, String shown, boolean typing) {
        int c = sp.color();
        TextComponent.Builder b = Component.text()
                .append(Component.text("◈ " + sp.name(), TextColor.color(c)).decorate(TextDecoration.BOLD))
                .append(Component.text(" · " + sp.title(), TextColor.color(0x9FB8E8)))
                .append(Component.newline())
                .append(Component.text("━━━━━━━━━━━━━━━━━━━━━━", TextColor.color(mix(c, 0x203060))))
                .append(Component.newline())
                .append(Component.text(shown, NamedTextColor.WHITE));
        if (typing) b.append(Component.text("▌", TextColor.color(c)));
        return b.build();
    }

    private static int mix(int a, int b) {
        int r = (((a >> 16) & 255) + ((b >> 16) & 255)) / 2, g = (((a >> 8) & 255) + ((b >> 8) & 255)) / 2, bl = ((a & 255) + (b & 255)) / 2;
        return (r << 16) | (g << 8) | bl;
    }

    private static Transformation scaleT(float s) {
        return new Transformation(new Vector3f(), new AxisAngle4f(), new Vector3f(s, s, s), new AxisAngle4f());
    }

    // ------------------------------------------------------------ the wheel

    /** Angles on the wheel: right side Light / neutral / Dark from top to bottom, left side the rest. */
    private static double[] angles(List<Choice> choices) {
        double[] a = new double[choices.size()];
        Map<Kind, List<Integer>> by = new HashMap<>();
        List<Integer> left = new ArrayList<>();
        for (int i = 0; i < choices.size(); i++) {
            Kind k = choices.get(i).kind();
            if (k == Kind.LIGHT || k == Kind.NEUTRAL || k == Kind.DARK) by.computeIfAbsent(k, x -> new ArrayList<>()).add(i);
            else left.add(i);
        }
        double[][] right = {{38}, {0}, {-38}};
        Kind[] order = {Kind.LIGHT, Kind.NEUTRAL, Kind.DARK};
        for (int r = 0; r < 3; r++) {
            List<Integer> idx = by.get(order[r]);
            if (idx == null) continue;
            double base = right[r][0];
            for (int j = 0; j < idx.size(); j++) a[idx.get(j)] = base + (idx.size() == 1 ? 0 : (j - (idx.size() - 1) / 2.0) * -17);
        }
        // the left side, top to bottom: questions, actions, leaving
        left.sort((x, y) -> Integer.compare(choices.get(x).kind().ordinal(), choices.get(y).kind().ordinal()));
        int n = left.size();
        for (int j = 0; j < n; j++) {
            double span = n <= 1 ? 0 : Math.min(84, 26.0 * (n - 1));
            a[left.get(j)] = 180 - span / 2 + (n <= 1 ? 0 : span * j / (n - 1));
        }
        return a;
    }

    private void showWheel(Session s, List<Choice> choices) {
        Player p = s.player;
        World w = p.getWorld();
        clearWheel(s);
        Location eye = p.getEyeLocation();
        Vector fwd = new Location(w, 0, 0, 0, eye.getYaw(), 0).getDirection();
        Vector right = fwd.clone().crossProduct(new Vector(0, 1, 0)).normalize();
        Location centre = eye.clone().add(fwd.clone().multiply(2.2)).add(0, -0.95, 0);
        int side = NeyroKarma.color(s.karma());
        s.wheelBits.add(wheelText(p, centre.clone().add(0, -0.16, 0), Component.text()
                .append(Component.text("╭──────╮", TextColor.color(0x3F6FD8))).append(Component.newline())
                .append(Component.text("  ◈  ", TextColor.color(side))).append(Component.newline())
                .append(Component.text("╰──────╯", TextColor.color(0x3F6FD8))).build(), 0.42f, Color.fromARGB(90, 6, 16, 40)));
        double[] ang = angles(choices);
        for (int i = 0; i < choices.size(); i++) {
            Choice c = choices.get(i);
            double a = Math.toRadians(ang[i]);
            int n = i + 1;
            String label = c.kind().mark + c.label();
            double halfWidth = (label.length() + 5) * 6 * 0.025 * WHEEL_SCALE / 2;
            double sideSign = Math.cos(a) >= -1e-6 ? 1 : -1;
            Location at = centre.clone().add(right.clone().multiply(Math.cos(a) * 0.62 + sideSign * halfWidth)).add(0, Math.sin(a) * 0.36, 0);
            TextDisplay td = wheelText(p, at.clone().add(0, -0.07, 0), optionText(n, c, false), WHEEL_SCALE, Color.fromARGB(175, 10, 28, 70));
            Interaction hit = w.spawn(at.clone().add(0, -0.14, 0), Interaction.class, en -> {
                en.addScoreboardTag(NeyroHub.TAG);
                en.setPersistent(false);
                en.setVisibleByDefault(false);
                en.setInteractionWidth((float) Math.max(0.5, halfWidth * 2));
                en.setInteractionHeight(0.3f);
                en.setResponsive(true);
            });
            p.showEntity(plugin, hit);
            s.options.add(new Option(n, c, ang[i], td, hit));
        }
        s.hovered = -1;
        s.hover = Bukkit.getScheduler().runTaskTimer(plugin, () -> hover(s), 1L, 2L);
        p.playSound(Sound.sound(Key.key("minecraft", "block.note_block.chime"), Sound.Source.MASTER, 0.4f, 1.6f));
        HudBoard.notice(p, Component.text()
                .append(Component.text("Ответ: 1–" + s.options.size() + " или взгляд + ПКМ · ", NamedTextColor.GRAY))
                .append(Component.text("◆ Свет", TextColor.color(Kind.LIGHT.color)))
                .append(Component.text(" · ", NamedTextColor.DARK_GRAY))
                .append(Component.text("◆ Тьма", TextColor.color(Kind.DARK.color)))
                .append(Component.text(" · Shift — уйти", NamedTextColor.GRAY)).build());
        s.timeout = Bukkit.getScheduler().runTaskLater(plugin, () -> { if (s.active()) finish(s, true); }, 20L * 45);
    }

    private TextDisplay wheelText(Player p, Location at, Component text, float scale, Color bg) {
        TextDisplay d = p.getWorld().spawn(at, TextDisplay.class, e -> {
            plugin.prepare(e);
            e.setVisibleByDefault(false);
            e.setBillboard(Display.Billboard.CENTER);
            e.setBackgroundColor(bg);
            e.setShadowed(true);
            e.setSeeThrough(true);
            e.setLineWidth(240);
            e.setBrightness(new Display.Brightness(15, 15));
            e.setInterpolationDuration(2);
            e.setTransformation(scaleT(scale));
            e.text(text);
        });
        p.showEntity(plugin, d);
        return d;
    }

    private static Component optionText(int n, Choice c, boolean lit) {
        int color = c.kind().color;
        return Component.text()
                .append(Component.text("[" + n + "] ", TextColor.color(lit ? 0xFFE08A : 0x9FB8E8)).decorate(TextDecoration.BOLD))
                .append(Component.text(c.kind().mark + c.label(), TextColor.color(lit ? 0xFFFFFF : color)).decoration(TextDecoration.BOLD, lit))
                .build();
    }

    private Color litBg(Kind k) {
        return switch (k) {
            case LIGHT -> Color.fromARGB(225, 30, 110, 210);
            case DARK -> Color.fromARGB(225, 170, 30, 40);
            default -> Color.fromARGB(225, 60, 90, 160);
        };
    }

    private void hover(Session s) {
        Player p = s.player;
        if (!p.isOnline() || s.options.isEmpty()) return;
        int best = s.options.indexOf(aimedOption(s));
        if (best == s.hovered) return;
        if (s.hovered >= 0 && s.hovered < s.options.size()) light(s.options.get(s.hovered), false);
        s.hovered = best;
        if (best >= 0) {
            light(s.options.get(best), true);
            p.playSound(Sound.sound(Key.key("minecraft", "ui.button.click"), Sound.Source.MASTER, 0.25f, 1.9f));
        }
    }

    private Option aimedOption(Session s) {
        Location eye = s.player.getEyeLocation();
        Vector origin = eye.toVector();
        Vector look = eye.getDirection();
        Option best = null;
        double bestDistance = 4.5 * 4.5;
        for (Option option : s.options) {
            if (!option.hit().isValid()) continue;
            var ray = option.hit().getBoundingBox().rayTrace(origin, look, 4.5);
            if (ray == null) continue;
            double distance = ray.getHitPosition().distanceSquared(origin);
            if (distance < bestDistance) {
                bestDistance = distance;
                best = option;
            }
        }
        return best;
    }

    private void light(Option o, boolean lit) {
        if (!o.text().isValid()) return;
        o.text().text(optionText(o.number(), o.choice(), lit));
        o.text().setBackgroundColor(lit ? litBg(o.choice().kind()) : Color.fromARGB(175, 10, 28, 70));
        o.text().setInterpolationDelay(0);
        o.text().setTransformation(scaleT(lit ? WHEEL_SCALE * 1.12f : WHEEL_SCALE));
    }

    private void pick(Session s, Option o) {
        Player p = s.player;
        Choice c = o.choice();
        if (s.timeout != null) s.timeout.cancel();
        clearWheel(s);
        p.playSound(Sound.sound(Key.key("minecraft", "block.amethyst_block.chime"), Sound.Source.MASTER, 0.7f, 1.4f));
        if (c.karma() != 0 && plugin.karma() != null) {
            // every answer moves the karma only once (per server run), so talking can't be farmed
            String key = s.speaker.name() + "|" + c.label();
            if (karmaTaken.computeIfAbsent(p.getUniqueId(), k -> new HashSet<>()).add(key))
                plugin.karma().add(p, c.karma(), "разговор: " + s.speaker.name());
        }
        // the player's own words, in chat, coloured by the side
        p.sendMessage(Component.text()
                .append(Component.text("  ▸ ", NamedTextColor.DARK_GRAY))
                .append(Component.text(c.label(), TextColor.color(c.kind() == Kind.LIGHT || c.kind() == Kind.DARK ? c.kind().color : 0xC8C8C8)).decorate(TextDecoration.ITALIC))
                .build());
        if (c.then() != null) c.then().accept(s);
        else finish(s, true);
    }

    private void clearWheel(Session s) {
        if (s.hover != null) { s.hover.cancel(); s.hover = null; }
        for (Option o : s.options) {
            if (o.text().isValid()) o.text().remove();
            if (o.hit().isValid()) o.hit().remove();
        }
        s.options.clear();
        for (Entity e : s.wheelBits) if (e.isValid()) e.remove();
        s.wheelBits.clear();
        s.hovered = -1;
    }

    private void finish(Session s, boolean online) {
        if (s.over) return;
        s.over = true;
        sessions.remove(s.player.getUniqueId(), s);
        if (s.typing != null) s.typing.cancel();
        if (s.timeout != null) s.timeout.cancel();
        if (s.panel != null && s.panel.isValid()) s.panel.remove();
        clearWheel(s);
        Player p = s.player;
        if (p.isOnline()) {
            p.setWalkSpeed(s.walkSpeed <= 0 ? 0.2f : s.walkSpeed);
            AttributeInstance jump = p.getAttribute(Attribute.JUMP_STRENGTH);
            if (jump != null) jump.setBaseValue(s.jump <= 0 ? 0.42 : s.jump);
        }
        if (s.onEnd != null) {
            try { s.onEnd.run(); } catch (RuntimeException e) { plugin.getLogger().warning("Dialogue end: " + e); }
        }
    }

    // ------------------------------------------------------------ input

    /** A click on an answer (its hit box) or on anything while looking at one picks it. */
    @EventHandler(priority = EventPriority.LOW)
    public void onClickEntity(PlayerInteractEntityEvent e) {
        Session s = sessions.get(e.getPlayer().getUniqueId());
        if (s == null) return;
        e.setCancelled(true);                                   // nothing else reacts while talking
        if (e.getHand() != EquipmentSlot.HAND || s.options.isEmpty()) return;
        Option pick = aimedOption(s);
        if (pick != null) pick(s, pick);
    }

    @EventHandler(priority = EventPriority.LOW)
    public void onClickAir(PlayerInteractEvent e) {
        Session s = sessions.get(e.getPlayer().getUniqueId());
        if (s == null || e.getHand() != EquipmentSlot.HAND) return;
        if (e.getAction() != Action.RIGHT_CLICK_AIR && e.getAction() != Action.RIGHT_CLICK_BLOCK) return;
        e.setCancelled(true);
        Option pick = aimedOption(s);
        if (pick != null) pick(s, pick);
    }

    /** Keys 1..9 (hotbar slots) pick the answer with that number. */
    @EventHandler(priority = EventPriority.LOW)
    public void onNumber(PlayerItemHeldEvent e) {
        Session s = sessions.get(e.getPlayer().getUniqueId());
        if (s == null || s.options.isEmpty()) return;
        e.setCancelled(true);
        int slot = e.getNewSlot();
        if (slot >= 0 && slot < s.options.size()) pick(s, s.options.get(slot));
    }

    @EventHandler
    public void onSneak(PlayerToggleSneakEvent e) {
        if (!e.isSneaking()) return;
        Session s = sessions.get(e.getPlayer().getUniqueId());
        if (s != null) finish(s, true);
    }

    @EventHandler
    public void onWorld(PlayerChangedWorldEvent e) {
        Session s = sessions.get(e.getPlayer().getUniqueId());
        if (s != null) finish(s, true);
    }

    @EventHandler
    public void onQuit(PlayerQuitEvent e) {
        Session s = sessions.get(e.getPlayer().getUniqueId());
        if (s != null) finish(s, false);                        // karmaTaken stays: no farming by rejoining
    }
}
