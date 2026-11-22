package net.neyrocraft.hub;

import net.kyori.adventure.bossbar.BossBar;
import net.kyori.adventure.key.Key;
import net.kyori.adventure.sound.Sound;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import net.kyori.adventure.text.format.TextColor;
import net.kyori.adventure.text.format.TextDecoration;
import net.kyori.adventure.title.Title;
import org.bukkit.Bukkit;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.server.ServerCommandEvent;
import org.bukkit.scheduler.BukkitTask;

import java.io.File;
import java.time.Duration;

/**
 * Restarts and shutdowns announced by Samantha: her voice ("Внимание, экипаж…"), a title, a countdown bar,
 * then everyone is sent off with a friendly message and the server stops.
 *   /reboot [seconds]  — restart: a helper waits for this server to exit and starts NeyroCraft.bat again;
 *   /reboot off        — shutdown without coming back;
 *   /reboot cancel     — stop the countdown.
 * A plain "stop" typed in the console always gets Samantha's announcement and a 10-second countdown first.
 */
final class ServerReboot implements Listener, CommandExecutor {
    private static final Key RESTART = Key.key("neyrocraft", "sam_restart"), SHUTDOWN = Key.key("neyrocraft", "sam_shutdown");
    private static final int ORANGE = NeyroHub.ORANGE, BLUE = 0x4FC3FF;

    private final NeyroHub plugin;
    private BukkitTask task;
    private BossBar bar;
    private boolean restart, passStop;
    private int left, total, voiceLead;

    ServerReboot(NeyroHub plugin) {
        this.plugin = plugin;
    }

    boolean running() {
        return task != null;
    }

    void begin(boolean restart, int seconds) {
        begin(restart, seconds, 0);
    }

    private void begin(boolean restart, int seconds, int voiceLead) {
        if (task != null) return;
        this.restart = restart;
        this.total = this.left = Math.max(10, seconds);
        this.voiceLead = voiceLead;
        Key line = restart ? RESTART : SHUTDOWN;
        double len = restart ? 8.9 : 9.2;
        Component head = Component.text(restart ? "⟳ ПЕРЕЗАПУСК СЕРВЕРА" : "⏻ СЕРВЕР ВЫКЛЮЧАЕТСЯ", TextColor.color(ORANGE)).decorate(TextDecoration.BOLD);
        Component said = Component.text()
                .append(Component.text("[Саманта] ", TextColor.color(BLUE)).decorate(TextDecoration.BOLD))
                .append(Component.text(restart ? "Внимание, экипаж! Перезагружаю системы корабля. Скоро вернёмся!"
                        : "Внимание, экипаж! Корабль уходит на обслуживание. До скорой встречи.", NamedTextColor.WHITE))
                .build();
        bar = BossBar.bossBar(barName(), 1f, BossBar.Color.BLUE, BossBar.Overlay.NOTCHED_10);
        for (Player p : Bukkit.getOnlinePlayers()) {
            VoicePack v = plugin.voice();
            if (v != null && v.hasVoice(p)) v.speak(p, line, len);
            p.sendMessage(NeyroChat.systemMessage(said));
            p.showTitle(Title.title(head, Component.text(restart ? "вернёмся через минуту" : "до скорой встречи", NamedTextColor.GRAY),
                    Title.Times.times(Duration.ofMillis(300), Duration.ofMillis(3500), Duration.ofMillis(800))));
            p.showBossBar(bar);
            p.playSound(Sound.sound(Key.key("minecraft", "block.beacon.deactivate"), Sound.Source.MASTER, 0.8f, 0.8f));
        }
        plugin.getLogger().info((restart ? "Restart" : "Shutdown") + " in " + left + " s, announced by Samantha");
        task = Bukkit.getScheduler().runTaskTimer(plugin, this::second, 20L, 20L);
    }

    private Component barName() {
        return Component.text()
                .append(Component.text(restart ? "⟳ Перезапуск через " : "⏻ Выключение через ", TextColor.color(ORANGE)))
                .append(Component.text(left + " сек", NamedTextColor.WHITE).decorate(TextDecoration.BOLD))
                .build();
    }

    private void second() {
        if (voiceLead > 0) {
            voiceLead--;
            return;
        }
        left--;
        if (bar != null) {
            bar.name(barName());
            bar.progress(Math.max(0f, left / (float) total));
        }
        if (left <= 10 && left > 0)
            for (Player p : Bukkit.getOnlinePlayers()) {
                p.playSound(Sound.sound(Key.key("minecraft", "block.note_block.pling"), Sound.Source.MASTER, 0.8f, 1.2f));
                p.showTitle(Title.title(Component.text(String.valueOf(left), TextColor.color(ORANGE)).decorate(TextDecoration.BOLD), Component.empty(),
                        Title.Times.times(Duration.ZERO, Duration.ofMillis(900), Duration.ofMillis(100))));
            }
        if (left > 0) return;
        task.cancel();
        task = null;
        if (restart) { plugin.saveRestartSessions(); spawnStarter(); }
        Component bye = Component.text()
                .append(NeyroHub.serverName()).append(Component.newline()).append(Component.newline())
                .append(Component.text(restart ? "Сервер перезапускается — заходи через минуту!" : "Сервер на обслуживании. До скорой встречи!", NamedTextColor.WHITE))
                .append(Component.newline())
                .append(Component.text("Саманта", TextColor.color(BLUE)))
                .build();
        for (Player p : Bukkit.getOnlinePlayers()) p.kick(bye);
        passStop = true;
        Bukkit.getScheduler().runTaskLater(plugin, () -> Bukkit.dispatchCommand(Bukkit.getConsoleSender(), "stop"), 10L);
    }

    void cancel() {
        if (task == null) return;
        task.cancel();
        task = null;
        for (Player p : Bukkit.getOnlinePlayers()) {
            if (bar != null) p.hideBossBar(bar);
            VoicePack v = plugin.voice();
            if (v != null) v.silence(p);
            p.sendMessage(NeyroChat.systemMessage(Component.text("[Саманта] ", TextColor.color(BLUE)).decorate(TextDecoration.BOLD)
                    .append(Component.text("Отбой! Перезапуск отменён, летим дальше.", NamedTextColor.WHITE).decoration(TextDecoration.BOLD, false))));
        }
    }

    /** A hidden helper waits for this JVM to exit and starts the server launcher again (with its console window). */
    private void spawnStarter() {
        try {
            File bat = new File("NeyroCraft.bat").getAbsoluteFile();
            if (!bat.exists()) { plugin.getLogger().warning("Restart: NeyroCraft.bat not found, the server will only stop"); return; }
            long pid = ProcessHandle.current().pid();
            String ps = "Wait-Process -Id " + pid + " -ErrorAction SilentlyContinue; Start-Sleep -Seconds 3; "
                    + "Start-Process cmd -ArgumentList '/c','\"" + bat.getPath().replace("'", "''") + "\" now' -WindowStyle Normal";
            new ProcessBuilder("powershell", "-NoProfile", "-WindowStyle", "Hidden", "-ExecutionPolicy", "Bypass", "-Command", ps)
                    .directory(bat.getParentFile()).start();
            plugin.getLogger().info("Restart: the server will start again after it stops");
        } catch (Exception e) {
            plugin.getLogger().warning("Restart helper failed: " + e);
        }
    }

    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        if (args.length > 0 && args[0].equalsIgnoreCase("cancel")) {
            if (running()) { cancel(); sender.sendMessage("Countdown cancelled"); } else sender.sendMessage("Nothing to cancel");
            return true;
        }
        boolean off = args.length > 0 && (args[0].equalsIgnoreCase("off") || args[0].equalsIgnoreCase("stop"));
        int sec = 10;
        for (String a : args) try { sec = Integer.parseInt(a); } catch (NumberFormatException ignored) { }
        if (running()) { sender.sendMessage("A countdown is already running (/reboot cancel)"); return true; }
        begin(!off, sec);
        sender.sendMessage((off ? "Shutdown" : "Restart") + " in " + Math.max(10, sec) + " s");
        return true;
    }

    /** "stop" in the console: Samantha finishes the maintenance announcement, then the 10-second countdown starts. */
    @EventHandler
    public void onConsole(ServerCommandEvent e) {
        String c = e.getCommand().trim().toLowerCase(java.util.Locale.ROOT);
        if (c.startsWith("/")) c = c.substring(1);
        if (c.equals("рестарт") || c.equals("restart")) {
            e.setCancelled(true);
            if (running()) {
                e.getSender().sendMessage("A countdown is already running (/reboot cancel)");
            } else {
                begin(true, 10, 9);
                e.getSender().sendMessage("Samantha announces the restart, then a 10-second countdown begins");
            }
            return;
        }
        if (!c.equals("stop")) return;
        if (passStop || running()) return;
        e.setCancelled(true);
        begin(false, 10, 9);
        e.getSender().sendMessage("Samantha announces maintenance, then a 10-second shutdown countdown begins");
    }

    @EventHandler
    public void onJoin(PlayerJoinEvent e) {
        if (bar != null && running()) e.getPlayer().showBossBar(bar);
    }
}
