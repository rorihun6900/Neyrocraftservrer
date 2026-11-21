package net.neyrocraft.hub;

import net.kyori.adventure.key.Key;
import net.kyori.adventure.sound.Sound;
import net.kyori.adventure.sound.SoundStop;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.entity.Player;
import org.bukkit.scheduler.BukkitTask;

import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

/**
 * Local soundtracks for the main deck and the two anarchy sanctuaries.
 */
final class HubMusic {
    private static final Key AMBIENT = Key.key("neyrocraft", "hub_ambient");
    private static final long AMBIENT_TICKS = 96 * 20;          // the track is exactly 96 s and loops seamlessly
    private static final Key FALLBACK = Key.key("minecraft", "music.menu");
    private static final long FALLBACK_TICKS = 5 * 60 * 20;
    private static final Key TEMPLE = Key.key("neyrotemple", "ambience");
    private static final Key TEMPLE_FALLBACK = Key.key("minecraft", "music.nether.soul_sand_valley");
    private static final long TEMPLE_TICKS = 64 * 20;
    private static final long PERIOD = 10;

    private static final class State {
        Key playing;           // null = silent
        long nextStart;        // tick when the track has to be started again
        long holdUntil;        // no music before this tick (AI greeting)
        long awaitUntil;       // waiting for the AI greeting to start (safety deadline)
    }

    private static final float AMBIENT_VOLUME = 0.25f;
    private static final float FALLBACK_VOLUME = 0.2f;

    private final NeyroHub plugin;
    private final VoicePack voice;
    private final Map<UUID, State> states = new HashMap<>();
    private BukkitTask task;
    private long now;

    HubMusic(NeyroHub plugin, VoicePack voice) {
        this.plugin = plugin;
        this.voice = voice;
    }

    void start() {
        task = Bukkit.getScheduler().runTaskTimer(plugin, this::tick, 40L, PERIOD);
    }

    void shutdown() {
        if (task != null) task.cancel();
        for (Player p : Bukkit.getOnlinePlayers()) stop(p);
        states.clear();
    }

    /** Keep quiet for a while (the AI is speaking). */
    void hold(Player p, long ticks) {
        State s = states.computeIfAbsent(p.getUniqueId(), k -> new State());
        s.holdUntil = Math.max(s.holdUntil, now + ticks);
        if (s.playing != null) stop(p);
    }

    /** The player is on the way to the deck through the jump: stay silent until the AI has spoken. */
    void awaitGreeting(Player p) {
        State s = states.computeIfAbsent(p.getUniqueId(), k -> new State());
        s.awaitUntil = now + 3 * 60 * 20;   // never wait forever
        if (s.playing != null) stop(p);
    }

    /** The AI greeting has started; music follows after {@code ticks}. */
    void greeted(Player p, long ticks) {
        State s = states.computeIfAbsent(p.getUniqueId(), k -> new State());
        s.awaitUntil = 0;
        hold(p, ticks);
    }

    void forget(Player p) {
        states.remove(p.getUniqueId());
    }

    private void tick() {
        now += PERIOD;
        for (Player p : Bukkit.getOnlinePlayers()) {
            State s = states.computeIfAbsent(p.getUniqueId(), k -> new State());
            boolean temple = plugin.authed(p) && inTemple(p.getLocation());
            boolean deck = !temple && plugin.onDeck(p);
            if (!temple && !deck) {
                if (s.playing != null) stop(p);
                continue;
            }
            if (deck && (now < s.holdUntil || now < s.awaitUntil)) continue;
            boolean custom = voice != null && voice.hasVoice(p);
            Key want = temple ? (custom ? TEMPLE : TEMPLE_FALLBACK) : (custom ? AMBIENT : FALLBACK);
            long duration = custom ? (temple ? TEMPLE_TICKS : AMBIENT_TICKS) : FALLBACK_TICKS;
            float volume = temple && custom ? .38f : custom ? AMBIENT_VOLUME : FALLBACK_VOLUME;
            if (s.playing == null || !s.playing.equals(want) || now >= s.nextStart) {
                if (s.playing != null) p.stopSound(SoundStop.namedOnSource(s.playing, Sound.Source.RECORD));
                p.stopSound(SoundStop.source(Sound.Source.MUSIC));   // the game's own soundtrack would clash
                p.playSound(Sound.sound(want, Sound.Source.RECORD, volume, 1f), Sound.Emitter.self());
                s.playing = want;
                s.nextStart = now + duration;
            } else if (now % 200 == 0) {
                p.stopSound(SoundStop.source(Sound.Source.MUSIC));
            }
        }
    }

    private static boolean inTemple(Location at) {
        String world = at.getWorld().getName();
        if (world.equals("neyro_hub3"))
            return Math.abs(at.getBlockX()) <= 16 && at.getBlockY() >= 98 && at.getBlockY() <= 119
                    && at.getBlockZ() >= -15 && at.getBlockZ() <= 13;
        if (world.equals(DarkAnarchy.WORLD_NAME))
            return Math.abs(at.getBlockX()) <= 16 && at.getBlockY() >= 62 && at.getBlockY() <= 84
                    && at.getBlockZ() >= -3 && at.getBlockZ() <= 25;
        return false;
    }

    private void stop(Player p) {
        State s = states.get(p.getUniqueId());
        if (s == null || s.playing == null) return;
        if (p.isOnline()) p.stopSound(SoundStop.namedOnSource(s.playing, Sound.Source.RECORD));
        s.playing = null;
    }
}
