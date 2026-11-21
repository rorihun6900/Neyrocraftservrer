package net.neyrocraft.hub;

import io.papermc.paper.dialog.Dialog;
import io.papermc.paper.registry.data.dialog.ActionButton;
import io.papermc.paper.registry.data.dialog.DialogBase;
import io.papermc.paper.registry.data.dialog.action.DialogAction;
import io.papermc.paper.registry.data.dialog.body.DialogBody;
import io.papermc.paper.registry.data.dialog.type.DialogType;
import net.kyori.adventure.key.Key;
import net.kyori.adventure.sound.Sound;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.event.ClickCallback;
import net.kyori.adventure.text.format.NamedTextColor;
import net.kyori.adventure.text.format.TextColor;
import net.kyori.adventure.text.format.TextDecoration;
import net.kyori.adventure.title.Title;
import org.bukkit.Bukkit;
import org.bukkit.Color;
import org.bukkit.GameMode;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.Particle;
import org.bukkit.World;
import org.bukkit.attribute.Attribute;
import org.bukkit.entity.Display;
import org.bukkit.entity.Entity;
import org.bukkit.entity.Player;
import org.bukkit.entity.TextDisplay;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.block.Action;
import org.bukkit.event.entity.EntityDamageByEntityEvent;
import org.bukkit.event.entity.EntityDamageEvent;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.player.PlayerAnimationEvent;
import org.bukkit.event.player.PlayerAnimationType;
import org.bukkit.event.player.PlayerDropItemEvent;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.event.player.PlayerItemHeldEvent;
import org.bukkit.event.player.PlayerMoveEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.event.player.PlayerSwapHandItemsEvent;
import org.bukkit.event.player.PlayerTeleportEvent;
import org.bukkit.event.player.PlayerToggleFlightEvent;
import org.bukkit.inventory.EquipmentSlot;
import org.bukkit.inventory.ItemFlag;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.persistence.PersistentDataType;
import org.bukkit.potion.PotionEffect;
import org.bukkit.potion.PotionEffectType;
import org.bukkit.scoreboard.DisplaySlot;
import org.bukkit.scoreboard.Objective;
import org.bukkit.scoreboard.Scoreboard;
import org.bukkit.util.RayTraceResult;
import org.bukkit.util.Transformation;
import org.bukkit.util.Vector;
import org.joml.AxisAngle4f;
import org.joml.Vector3f;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.time.Duration;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.UUID;

/**
 * "Световые мечи" — free-for-all duels with energy swords on a space arena.
 * Own items only (the player's inventory is locked away), double jump, push, lightning and dash abilities,
 * blocking with Shift, eight blade colours, first to 15 victories wins the round.
 */
final class SaberArena implements Listener {
    static final int AX = 0, AZ = -6000;
    private static final int R = 20, BUILD = 1, WIN_KILLS = 15;
    private static final String GAME = "sabers";

    static final String[] COLOR_ID = {"red", "blue", "green", "purple", "yellow", "orange", "cyan", "white"};
    static final String[] COLOR_NAME = {"Красный", "Синий", "Зелёный", "Фиолетовый", "Жёлтый", "Оранжевый", "Бирюзовый", "Белый"};
    static final int[] COLOR_RGB = {0xFF2A2A, 0x2A7BFF, 0x2AFF5A, 0xB04BFF, 0xFFE83A, 0xFF8A1E, 0x2AF5FF, 0xF2F2FF};

    private final class Fighter {
        final Player p;
        int kills, deaths;
        UUID lastHit; long lastHitAt, safeUntil;
        long cdPush, cdBolt, cdDash;
        Fighter(Player p) { this.p = p; }
    }

    private final NeyroHub plugin;
    private final World w;
    private final int fy;
    private final NamespacedKey colorKey, itemKey;
    private final Map<UUID, Fighter> fighters = new HashMap<>();
    private final List<Entity> decor = new ArrayList<>();
    private final Random rnd = new Random();
    private Scoreboard board;
    private Objective side;
    private long tick;

    SaberArena(NeyroHub plugin, World hubWorld, int floorY) {
        this.plugin = plugin;
        this.w = hubWorld;
        this.fy = floorY;
        this.colorKey = new NamespacedKey(plugin, "saber_color");
        this.itemKey = new NamespacedKey(plugin, "saber_item");
    }

    void start() {
        for (int cx = (AX - 48) >> 4; cx <= (AX + 48) >> 4; cx++)
            for (int cz = (AZ - 48) >> 4; cz <= (AZ + 48) >> 4; cz++) w.setChunkForceLoaded(cx, cz, true);
        File marker = new File(plugin.getDataFolder(), "sabers-built-v" + BUILD);
        if (!marker.exists()) {
            build();
            try { Files.writeString(marker.toPath(), "built"); } catch (IOException ignored) { }
        }
        board = Bukkit.getScoreboardManager().getNewScoreboard();
        side = board.registerNewObjective("sabers", org.bukkit.scoreboard.Criteria.DUMMY,
                Component.text("✦ СВЕТОВЫЕ МЕЧИ ✦", TextColor.color(0x7FE8FF)).decorate(TextDecoration.BOLD));
        side.setDisplaySlot(DisplaySlot.SIDEBAR);
        spawnDecor();
        Bukkit.getScheduler().runTaskTimer(plugin, this::tick, 20L, 2L);
    }

    void shutdown() {
        for (Fighter f : new ArrayList<>(fighters.values())) leave(f.p);
        for (Entity e : decor) if (e.isValid()) e.remove();
        decor.clear();
    }

    boolean inGame(Player p) { return fighters.containsKey(p.getUniqueId()); }

    boolean contains(Location l) {
        if (l == null || !w.equals(l.getWorld())) return false;
        double dx = l.getX() - (AX + 0.5), dz = l.getZ() - (AZ + 0.5);
        return dx * dx + dz * dz < (R + 30) * (R + 30) && l.getY() > fy - 60 && l.getY() < fy + 60;
    }

    // ------------------------------------------------------------ joining / leaving

    void join(Player p) {
        if (inGame(p)) return;
        plugin.vault().lock(p, GAME);                    // own things stay outside — only the arena's kit inside
        Fighter f = new Fighter(p);
        fighters.put(p.getUniqueId(), f);
        p.setGameMode(GameMode.ADVENTURE);
        p.setHealth(p.getAttribute(Attribute.MAX_HEALTH).getValue());
        p.setFoodLevel(20);
        p.setScoreboard(board);
        side.getScore(p.getName()).setScore(0);
        p.teleport(spawnPoint());
        kit(p);
        p.setAllowFlight(true);
        f.safeUntil = System.currentTimeMillis() + 3000;
        p.showTitle(Title.title(Component.text("СВЕТОВЫЕ МЕЧИ", TextColor.color(0x7FE8FF)).decorate(TextDecoration.BOLD),
                Component.text("дуэли на энергоклинках · до " + WIN_KILLS + " побед", NamedTextColor.GRAY),
                Title.Times.times(Duration.ofMillis(300), Duration.ofMillis(2500), Duration.ofMillis(600))));
        msg(p, Component.text("ЛКМ — удар · Shift — блок клинком · пробел в воздухе — двойной прыжок", NamedTextColor.GRAY));
        msg(p, Component.text("ПКМ предметами: ✋ Толчок, ⚡ Молния, ➤ Рывок, ★ цвет клинка · /hub — выйти", NamedTextColor.GRAY));
        broadcast(Component.text(p.getName(), NamedTextColor.WHITE).append(Component.text(" вышел на арену", NamedTextColor.GRAY)));
    }

    void leave(Player p) {
        Fighter f = fighters.remove(p.getUniqueId());
        if (f == null) return;
        board.resetScores(p.getName());
        if (p.isOnline()) {
            p.setAllowFlight(p.getGameMode() == GameMode.CREATIVE || p.getGameMode() == GameMode.SPECTATOR);
            p.setFlying(false);
            p.setScoreboard(Bukkit.getScoreboardManager().getMainScoreboard());
            p.setHealth(p.getAttribute(Attribute.MAX_HEALTH).getValue());
            plugin.vault().unlock(p, GAME);
            p.stopSound(net.kyori.adventure.sound.SoundStop.namedOnSource(Key.key("neyrocraft", "saber.hum"), Sound.Source.PLAYER));
        }
    }

    private Location spawnPoint() {
        double a = Math.PI / 8 + rnd.nextInt(8) * Math.PI / 4;
        Location l = new Location(w, AX + 0.5 + Math.cos(a) * 11, fy + 1, AZ + 0.5 + Math.sin(a) * 11);
        l.setDirection(new Vector(AX + 0.5, fy + 1, AZ + 0.5).subtract(l.toVector()));
        return l;
    }

    // ------------------------------------------------------------ kit

    private int colorIndex(Player p) {
        String c = p.getPersistentDataContainer().get(colorKey, PersistentDataType.STRING);
        for (int i = 0; i < COLOR_ID.length; i++) if (COLOR_ID[i].equals(c)) return i;
        return 1;   // blue by default
    }

    private ItemStack tagged(Material m, String type, Component name, Component lore) {
        ItemStack it = new ItemStack(m);
        ItemMeta meta = it.getItemMeta();
        meta.displayName(name.decoration(TextDecoration.ITALIC, false));
        if (lore != null) meta.lore(List.of(lore.decoration(TextDecoration.ITALIC, false)));
        meta.getPersistentDataContainer().set(itemKey, PersistentDataType.STRING, type);
        meta.setUnbreakable(true);
        meta.addItemFlags(ItemFlag.HIDE_ATTRIBUTES, ItemFlag.HIDE_UNBREAKABLE);
        it.setItemMeta(meta);
        return it;
    }

    private ItemStack saber(Player p, int stage) {
        int c = colorIndex(p);
        ItemStack it = tagged(Material.NETHERITE_SWORD, "saber",
                Component.text("✦ Световой меч", TextColor.color(COLOR_RGB[c])).decorate(TextDecoration.BOLD),
                Component.text("Клинок: " + COLOR_NAME[c], NamedTextColor.GRAY));
        ItemMeta meta = it.getItemMeta();
        meta.setItemModel(new NamespacedKey("neyrocraft", "saber_" + COLOR_ID[c] + "_" + stage));
        it.setItemMeta(meta);
        return it;
    }

    private void kit(Player p) {
        var inv = p.getInventory();
        inv.clear();
        inv.setItem(0, saber(p, 0));
        inv.setItem(1, tagged(Material.AMETHYST_SHARD, "push", Component.text("✋ Толчок", TextColor.color(0x9FD8FF)),
                Component.text("ПКМ — отбросить врагов перед собой", NamedTextColor.GRAY)));
        inv.setItem(2, tagged(Material.BLAZE_ROD, "bolt", Component.text("⚡ Молния", TextColor.color(0xC08CFF)),
                Component.text("ПКМ — молнии в противника", NamedTextColor.GRAY)));
        inv.setItem(3, tagged(Material.FEATHER, "dash", Component.text("➤ Рывок", TextColor.color(0xFFE6A0)),
                Component.text("ПКМ — рывок вперёд", NamedTextColor.GRAY)));
        inv.setItem(8, tagged(Material.NETHER_STAR, "color", Component.text("★ Цвет клинка", TextColor.color(0xFFFFFF)),
                Component.text("ПКМ — выбрать цвет", NamedTextColor.GRAY)));
        inv.setHeldItemSlot(0);
        ignite(p);
    }

    private String typeOf(ItemStack it) {
        if (it == null || !it.hasItemMeta()) return null;
        return it.getItemMeta().getPersistentDataContainer().get(itemKey, PersistentDataType.STRING);
    }

    private boolean holdingSaber(Player p) { return "saber".equals(typeOf(p.getInventory().getItemInMainHand())); }

    /** The blade grows out of the hilt over four ticks. */
    private void ignite(Player p) {
        play(p.getLocation(), "saber.on", 1f, 1f);
        for (int k = 0; k <= 3; k++) {
            int stage = k;
            Bukkit.getScheduler().runTaskLater(plugin, () -> {
                if (!p.isOnline() || !inGame(p)) return;
                ItemStack cur = p.getInventory().getItem(0);
                if ("saber".equals(typeOf(cur))) p.getInventory().setItem(0, saber(p, stage));
            }, 1L + k * 2L);
        }
    }

    private void retract(Player p) {
        play(p.getLocation(), "saber.off", 0.8f, 1f);
        ItemStack cur = p.getInventory().getItem(0);
        if ("saber".equals(typeOf(cur))) p.getInventory().setItem(0, saber(p, 0));
    }

    private void chooseColor(Player p) {
        ClickCallback.Options once = ClickCallback.Options.builder().uses(1).lifetime(Duration.ofMinutes(2)).build();
        List<ActionButton> buttons = new ArrayList<>();
        for (int i = 0; i < COLOR_ID.length; i++) {
            int c = i;
            // orange, cyan and white blades are StarPass rewards
            int passLevel = c == 5 ? 4 : c == 6 ? 9 : c == 7 ? 20 : 0;
            boolean locked = passLevel > 0 && (plugin.starPass() == null || !plugin.starPass().unlocked(p, "saber:" + COLOR_ID[c]));
            if (locked) {
                buttons.add(ActionButton.builder(Component.text("🔒 " + COLOR_NAME[i], NamedTextColor.DARK_GRAY))
                        .tooltip(Component.text("Открывается в StarPass на уровне " + passLevel + " — /pass"))
                        .width(120)
                        .action(DialogAction.customClick((view, audience) -> Bukkit.getScheduler().runTask(plugin, () ->
                                p.sendActionBar(Component.text("Этот цвет — награда StarPass, уровень " + passLevel, NamedTextColor.GRAY))), once)).build());
                continue;
            }
            buttons.add(ActionButton.builder(Component.text("█ " + COLOR_NAME[i], TextColor.color(COLOR_RGB[i])))
                    .width(120)
                    .action(DialogAction.customClick((view, audience) -> Bukkit.getScheduler().runTask(plugin, () -> {
                        p.getPersistentDataContainer().set(colorKey, PersistentDataType.STRING, COLOR_ID[c]);
                        if (inGame(p)) { p.getInventory().setItem(0, saber(p, 0)); p.getInventory().setHeldItemSlot(0); ignite(p); }
                        p.sendActionBar(Component.text("Клинок: " + COLOR_NAME[c], TextColor.color(COLOR_RGB[c])));
                    }), once)).build());
        }
        p.showDialog(Dialog.create(b -> b.empty()
                .base(DialogBase.builder(Component.text("★ ЦВЕТ КЛИНКА ★", NamedTextColor.WHITE).decorate(TextDecoration.BOLD))
                        .canCloseWithEscape(true).pause(false).afterAction(DialogBase.DialogAfterAction.CLOSE)
                        .body(List.of(DialogBody.plainMessage(Component.text("Выбери цвет своего светового меча", NamedTextColor.GRAY), 260)))
                        .build())
                .type(DialogType.multiAction(buttons).columns(2).build())));
    }

    // ------------------------------------------------------------ fighting

    private boolean facing(Player defender, Player attacker) {
        Vector look = defender.getLocation().getDirection().setY(0).normalize();
        Vector to = attacker.getLocation().toVector().subtract(defender.getLocation().toVector()).setY(0);
        return to.lengthSquared() > 0 && look.dot(to.normalize()) > 0.35;
    }

    private boolean blocking(Player defender, Player attacker) {
        return defender.isSneaking() && holdingSaber(defender) && facing(defender, attacker);
    }

    @EventHandler(priority = EventPriority.HIGH)
    public void onHit(EntityDamageByEntityEvent e) {
        if (!(e.getEntity() instanceof Player victim) || !inGame(victim)) return;
        if (!(e.getDamager() instanceof Player attacker) || !inGame(attacker)) { e.setCancelled(true); return; }
        e.setCancelled(false);
        Fighter vf = fighters.get(victim.getUniqueId());
        if (System.currentTimeMillis() < vf.safeUntil) { e.setCancelled(true); return; }
        if (!holdingSaber(attacker)) { e.setDamage(1.0); mark(vf, attacker); return; }
        Location mid = victim.getEyeLocation().add(attacker.getEyeLocation()).multiply(0.5);
        if (blocking(victim, attacker)) {
            e.setDamage(0.5);
            play(mid, "saber.clash", 1.1f, 0.9f + rnd.nextFloat() * 0.25f);
            w.spawnParticle(Particle.ELECTRIC_SPARK, mid, 30, 0.2, 0.2, 0.2, 0.4);
            w.spawnParticle(Particle.FIREWORK, mid, 8, 0.1, 0.1, 0.1, 0.12);
            attacker.setVelocity(attacker.getLocation().toVector().subtract(victim.getLocation().toVector()).setY(0).normalize().multiply(0.7).setY(0.25));
            mark(vf, attacker);
            return;
        }
        boolean crit = attacker.getFallDistance() > 0 && !attacker.isOnGround();
        e.setDamage(crit ? 8.0 : 6.0);
        play(victim.getLocation(), "saber.hit", 1f, 0.9f + rnd.nextFloat() * 0.2f);
        w.spawnParticle(Particle.DUST, victim.getLocation().add(0, 1.1, 0), 18, 0.25, 0.4, 0.25, 0,
                new Particle.DustOptions(Color.fromRGB(COLOR_RGB[colorIndex(attacker)]), 1.2f));
        w.spawnParticle(Particle.SMALL_FLAME, victim.getLocation().add(0, 1.1, 0), 6, 0.2, 0.3, 0.2, 0.01);
        mark(vf, attacker);
    }

    private void mark(Fighter vf, Player attacker) { vf.lastHit = attacker.getUniqueId(); vf.lastHitAt = System.currentTimeMillis(); }

    @EventHandler(priority = EventPriority.HIGHEST)
    public void onDamage(EntityDamageEvent e) {
        if (!(e.getEntity() instanceof Player p) || !inGame(p)) return;
        if (e.getCause() == EntityDamageEvent.DamageCause.FALL) { e.setCancelled(true); return; }
        if (e.isCancelled()) return;
        if (p.getHealth() - e.getFinalDamage() <= 0) {
            e.setCancelled(true);
            die(p);
        }
    }

    /** Ability damage goes through here (no vanilla event, so blocking/armour rules are ours). */
    private void hurt(Player victim, Player by, double amount) {
        Fighter vf = fighters.get(victim.getUniqueId());
        if (vf == null || System.currentTimeMillis() < vf.safeUntil) return;
        mark(vf, by);
        double h = victim.getHealth() - amount;
        if (h <= 0) { die(victim); return; }
        victim.setHealth(h);
        victim.playHurtAnimation(0);
    }

    private void die(Player p) {
        Fighter vf = fighters.get(p.getUniqueId());
        if (vf == null) return;
        vf.deaths++;
        Player killer = vf.lastHit != null && System.currentTimeMillis() - vf.lastHitAt < 12000 ? Bukkit.getPlayer(vf.lastHit) : null;
        Location at = p.getLocation().add(0, 1, 0);
        w.spawnParticle(Particle.DUST, at, 60, 0.4, 0.7, 0.4, 0, new Particle.DustOptions(Color.fromRGB(COLOR_RGB[colorIndex(p)]), 1.6f));
        w.spawnParticle(Particle.END_ROD, at, 25, 0.2, 0.4, 0.2, 0.15);
        play(at, "saber.off", 1f, 0.7f);
        Fighter kf = killer == null ? null : fighters.get(killer.getUniqueId());
        if (kf != null && kf != vf) {
            kf.kills++;
            side.getScore(killer.getName()).setScore(kf.kills);
            broadcast(Component.text("⚔ ", TextColor.color(COLOR_RGB[colorIndex(killer)]))
                    .append(Component.text(killer.getName(), NamedTextColor.WHITE))
                    .append(Component.text(" победил ", NamedTextColor.GRAY))
                    .append(Component.text(p.getName(), NamedTextColor.WHITE))
                    .append(Component.text("  (" + kf.kills + "/" + WIN_KILLS + ")", NamedTextColor.DARK_GRAY)));
            killer.playSound(Sound.sound(Key.key("minecraft", "entity.player.levelup"), Sound.Source.MASTER, 0.6f, 1.6f));
            if (plugin.starPass() != null) plugin.starPass().award(killer, 10, "победа в дуэли");
            if (plugin.hud() != null) plugin.hud().add(killer, "sabers.kills", 1);
        } else {
            broadcast(Component.text(p.getName(), NamedTextColor.WHITE).append(Component.text(" выбыл", NamedTextColor.GRAY)));
        }
        vf.lastHit = null;
        // back in the fight at once, a little dazed
        p.setHealth(p.getAttribute(Attribute.MAX_HEALTH).getValue());
        p.setFireTicks(0);
        p.setFallDistance(0);
        p.teleport(spawnPoint());
        p.addPotionEffect(new PotionEffect(PotionEffectType.BLINDNESS, 25, 0, false, false, false));
        vf.safeUntil = System.currentTimeMillis() + 2500;
        p.getInventory().setHeldItemSlot(0);
        ignite(p);
        if (kf != null && kf.kills >= WIN_KILLS) win(killer);
    }

    private void win(Player champion) {
        if (plugin.starPass() != null) plugin.starPass().award(champion, 100, "мастер клинка");
        if (plugin.hud() != null) plugin.hud().add(champion, "sabers.wins", 1);
        for (Fighter f : fighters.values()) {
            f.p.showTitle(Title.title(Component.text(champion.getName(), TextColor.color(COLOR_RGB[colorIndex(champion)])).decorate(TextDecoration.BOLD),
                    Component.text("— мастер клинка! Новый раунд…", NamedTextColor.GRAY),
                    Title.Times.times(Duration.ofMillis(300), Duration.ofMillis(3000), Duration.ofMillis(700))));
            f.p.playSound(Sound.sound(Key.key("minecraft", "ui.toast.challenge_complete"), Sound.Source.MASTER, 0.8f, 1f));
            f.kills = 0; f.deaths = 0;
            side.getScore(f.p.getName()).setScore(0);
        }
        for (int i = 0; i < 6; i++) {
            int k = i;
            Bukkit.getScheduler().runTaskLater(plugin, () -> w.spawnParticle(Particle.FIREWORK,
                    champion.getLocation().add(0, 2 + k * 0.3, 0), 40, 0.6, 0.6, 0.6, 0.15), k * 6L);
        }
    }

    // ------------------------------------------------------------ abilities

    @EventHandler(priority = EventPriority.HIGH)
    public void onUse(PlayerInteractEvent e) {
        Player p = e.getPlayer();
        if (!inGame(p) || e.getHand() != EquipmentSlot.HAND) return;
        if (e.getAction() != Action.RIGHT_CLICK_AIR && e.getAction() != Action.RIGHT_CLICK_BLOCK) return;
        String type = typeOf(e.getItem());
        if (type == null || type.equals("saber")) return;
        e.setCancelled(true);
        Fighter f = fighters.get(p.getUniqueId());
        long now = System.currentTimeMillis();
        switch (type) {
            case "push" -> { if (now < f.cdPush) return; f.cdPush = now + 8000; p.setCooldown(Material.AMETHYST_SHARD, 160); push(p); }
            case "bolt" -> { if (now < f.cdBolt) return; f.cdBolt = now + 12000; p.setCooldown(Material.BLAZE_ROD, 240); bolt(p); }
            case "dash" -> { if (now < f.cdDash) return; f.cdDash = now + 5000; p.setCooldown(Material.FEATHER, 100); dash(p); }
            case "color" -> chooseColor(p);
            default -> { }
        }
    }

    private void push(Player p) {
        Location eye = p.getEyeLocation();
        Vector dir = eye.getDirection().normalize();
        play(p.getLocation(), "saber.push", 1.1f, 1f);
        for (int d = 1; d <= 6; d++) {
            int dist = d;
            Bukkit.getScheduler().runTaskLater(plugin, () -> {
                Location c = eye.clone().add(dir.clone().multiply(dist));
                w.spawnParticle(Particle.CLOUD, c, 6 + dist * 2, 0.25 * dist * 0.4, 0.25 * dist * 0.4, 0.25 * dist * 0.4, 0.02);
                w.spawnParticle(Particle.DUST, c, 8, 0.3 * dist * 0.4, 0.3 * dist * 0.4, 0.3 * dist * 0.4, 0,
                        new Particle.DustOptions(Color.fromRGB(0x9FD8FF), 1.4f));
            }, d);
        }
        for (Fighter f : fighters.values()) {
            Player t = f.p;
            if (t.equals(p)) continue;
            Vector to = t.getLocation().add(0, 1, 0).toVector().subtract(eye.toVector());
            double dist = to.length();
            if (dist > 7.5 || dist < 0.1 || dir.dot(to.clone().normalize()) < 0.55) continue;
            Vector kick = to.normalize().multiply(1.7 - dist * 0.08).setY(0.55);
            t.setVelocity(kick);
            hurt(t, p, 2.0);
            t.playSound(Sound.sound(Key.key("neyrocraft", "saber.push"), Sound.Source.PLAYER, 0.8f, 0.8f));
        }
    }

    private void bolt(Player p) {
        Location eye = p.getEyeLocation();
        RayTraceResult hit = w.rayTraceEntities(eye, eye.getDirection(), 14, 0.7,
                en -> en instanceof Player tp && !tp.equals(p) && inGame(tp));
        Player target = hit != null && hit.getHitEntity() instanceof Player tp ? tp : null;
        play(p.getLocation(), "saber.zap", 1.1f, 1f);
        for (int k = 0; k < 4; k++) {
            int pulse = k;
            Bukkit.getScheduler().runTaskLater(plugin, () -> {
                if (!p.isOnline() || !inGame(p)) return;
                Location from = p.getEyeLocation().add(p.getLocation().getDirection().multiply(0.6)).add(0, -0.4, 0);
                Location to = target != null && target.isOnline() && inGame(target)
                        ? target.getLocation().add(0, 1.1, 0) : from.clone().add(p.getLocation().getDirection().multiply(10));
                zigzag(from, to);
                if (target != null && target.isOnline() && inGame(target)) {
                    boolean guard = blocking(target, p);
                    hurt(target, p, guard ? 0.75 : 1.5);
                    target.addPotionEffect(new PotionEffect(PotionEffectType.SLOWNESS, 20, 1, false, false, false));
                    if (guard) w.spawnParticle(Particle.ELECTRIC_SPARK, target.getEyeLocation(), 14, 0.2, 0.2, 0.2, 0.3);
                }
            }, pulse * 6L);
        }
    }

    private void zigzag(Location a, Location b) {
        Vector ab = b.toVector().subtract(a.toVector());
        int steps = Math.max(6, (int) (ab.length() * 3));
        Vector prev = a.toVector();
        Particle.DustOptions violet = new Particle.DustOptions(Color.fromRGB(0xB08CFF), 0.9f);
        for (int i = 1; i <= steps; i++) {
            double t = i / (double) steps;
            Vector pt = a.toVector().add(ab.clone().multiply(t));
            if (i < steps) pt.add(new Vector(rnd.nextGaussian() * 0.22, rnd.nextGaussian() * 0.22, rnd.nextGaussian() * 0.22));
            Vector seg = pt.clone().subtract(prev);
            for (int s = 0; s < 3; s++) {
                Location l = prev.clone().add(seg.clone().multiply(s / 3.0)).toLocation(w);
                w.spawnParticle(Particle.ELECTRIC_SPARK, l, 1, 0, 0, 0, 0);
                w.spawnParticle(Particle.DUST, l, 1, 0, 0, 0, 0, violet);
            }
            prev = pt;
        }
    }

    private void dash(Player p) {
        Vector dir = p.getLocation().getDirection().setY(0).normalize().multiply(1.9);
        dir.setY(Math.max(0.15, Math.min(0.35, p.getLocation().getDirection().getY() + 0.2)));
        p.setVelocity(dir);
        play(p.getLocation(), "saber.dash", 1f, 1f);
        Particle.DustOptions dust = new Particle.DustOptions(Color.fromRGB(COLOR_RGB[colorIndex(p)]), 1.1f);
        for (int k = 1; k <= 6; k++) Bukkit.getScheduler().runTaskLater(plugin, () -> {
            if (p.isOnline()) w.spawnParticle(Particle.DUST, p.getLocation().add(0, 1, 0), 6, 0.25, 0.4, 0.25, 0, dust);
        }, k);
    }

    @EventHandler(priority = EventPriority.HIGH)
    public void onDoubleJump(PlayerToggleFlightEvent e) {
        Player p = e.getPlayer();
        if (!inGame(p) || p.getGameMode() == GameMode.CREATIVE || p.getGameMode() == GameMode.SPECTATOR) return;
        e.setCancelled(true);
        p.setFlying(false);
        p.setAllowFlight(false);
        Vector v = p.getLocation().getDirection().setY(0).normalize().multiply(0.55).setY(0.9);
        p.setVelocity(v);
        play(p.getLocation(), "saber.jump", 0.9f, 1f);
        for (int i = 0; i < 14; i++) {
            double a = i * Math.PI / 7;
            w.spawnParticle(Particle.CLOUD, p.getLocation().add(Math.cos(a) * 0.7, 0.1, Math.sin(a) * 0.7), 1, 0, 0, 0, 0.01);
        }
    }

    // ------------------------------------------------------------ saber feel: hum, swings, ignition

    @EventHandler
    public void onSwing(PlayerAnimationEvent e) {
        Player p = e.getPlayer();
        if (e.getAnimationType() != PlayerAnimationType.ARM_SWING || !inGame(p) || !holdingSaber(p)) return;
        play(p.getLocation(), "saber.swing", 0.9f, 0.9f + rnd.nextFloat() * 0.25f);
        // a coloured arc in front of the player
        Location eye = p.getEyeLocation();
        Vector fwd = eye.getDirection().normalize();
        Vector right = fwd.clone().crossProduct(new Vector(0, 1, 0)).normalize();
        Particle.DustOptions dust = new Particle.DustOptions(Color.fromRGB(COLOR_RGB[colorIndex(p)]), 1.0f);
        boolean ltr = rnd.nextBoolean();
        for (int i = 0; i <= 10; i++) {
            double a = (ltr ? -1 : 1) * (-0.9 + i * 0.18);
            Vector off = fwd.clone().multiply(1.6 * Math.cos(a)).add(right.clone().multiply(1.6 * Math.sin(a))).add(new Vector(0, -0.3 + i * 0.03, 0));
            w.spawnParticle(Particle.DUST, eye.clone().add(off), 1, 0, 0, 0, 0, dust);
        }
    }

    @EventHandler
    public void onSlot(PlayerItemHeldEvent e) {
        Player p = e.getPlayer();
        if (!inGame(p)) return;
        if (e.getNewSlot() == 0 && e.getPreviousSlot() != 0) Bukkit.getScheduler().runTask(plugin, () -> ignite(p));
        else if (e.getPreviousSlot() == 0 && e.getNewSlot() != 0) retract(p);
    }

    private void tick() {
        tick++;
        if (fighters.isEmpty()) return;
        for (Fighter f : fighters.values()) {
            Player p = f.p;
            if (tick % 15 == 0 && holdingSaber(p)) play(p.getLocation(), "saber.hum", 0.45f, 1f);
            if (!p.getAllowFlight() && p.isOnGround() && p.getGameMode() == GameMode.ADVENTURE) p.setAllowFlight(true);
            if (tick % 10 == 0) {
                long now = System.currentTimeMillis();
                p.sendActionBar(Component.text()
                        .append(Component.text("⚔ " + f.kills + "  ", NamedTextColor.WHITE))
                        .append(cd("✋", f.cdPush, now)).append(Component.text("  "))
                        .append(cd("⚡", f.cdBolt, now)).append(Component.text("  "))
                        .append(cd("➤", f.cdDash, now)).build());
            }
        }
    }

    private static Component cd(String icon, long until, long now) {
        if (now >= until) return Component.text(icon + " готово", NamedTextColor.GREEN);
        return Component.text(icon + " " + ((until - now) / 1000 + 1) + "с", NamedTextColor.DARK_GRAY);
    }

    // ------------------------------------------------------------ keeping the kit and the arena tidy

    @EventHandler(priority = EventPriority.HIGH)
    public void onMove(PlayerMoveEvent e) {
        Player p = e.getPlayer();
        if (!inGame(p)) return;
        if (e.getTo().getY() < fy - 25) die(p);
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onTeleport(PlayerTeleportEvent e) {
        if (inGame(e.getPlayer()) && !contains(e.getTo())) Bukkit.getScheduler().runTask(plugin, () -> leave(e.getPlayer()));
    }

    @EventHandler public void onDrop(PlayerDropItemEvent e) { if (inGame(e.getPlayer())) e.setCancelled(true); }
    @EventHandler public void onSwap(PlayerSwapHandItemsEvent e) { if (inGame(e.getPlayer())) e.setCancelled(true); }
    @EventHandler public void onClick(InventoryClickEvent e) { if (e.getWhoClicked() instanceof Player p && inGame(p)) e.setCancelled(true); }
    @EventHandler public void onQuit(PlayerQuitEvent e) { leave(e.getPlayer()); }

    private void play(Location l, String key, float vol, float pitch) {
        w.playSound(Sound.sound(Key.key("neyrocraft", key), Sound.Source.PLAYER, vol, pitch), l.getX(), l.getY(), l.getZ());
    }

    private void msg(Player p, Component c) {
        p.sendMessage(Component.text("[Мечи] ", TextColor.color(0x7FE8FF)).append(c));
    }

    private void broadcast(Component c) {
        for (Fighter f : fighters.values()) msg(f.p, c);
    }

    // ------------------------------------------------------------ the arena

    private void set(int x, int y, int z, Material m) { w.getBlockAt(AX + x, y, AZ + z).setType(m, false); }

    private static int hash(int x, int z) {
        int h = x * 73856093 ^ z * 83492791; h ^= (h >>> 13); h *= 0x5bd1e995;
        return Math.floorMod(h ^ (h >>> 15), 1000);
    }

    private void build() {
        plugin.getLogger().info("Building the energy-sword arena...");
        for (int x = -R - 4; x <= R + 4; x++)
            for (int z = -R - 4; z <= R + 4; z++)
                for (int y = fy - 16; y <= fy + 16; y++)
                    if (!w.getBlockAt(AX + x, y, AZ + z).getType().isAir()) set(x, y, z, Material.AIR);
        for (int x = -R - 1; x <= R + 1; x++)
            for (int z = -R - 1; z <= R + 1; z++) {
                double r = Math.sqrt(x * x + z * z);
                if (r > R + 0.5) continue;
                Material f;
                boolean redSide = x > 0;
                if (r < 2.5) f = Material.SEA_LANTERN;
                else if (r < 3.5) f = Material.CRYING_OBSIDIAN;
                else if (Math.abs(r - 8) < 0.5 || Math.abs(r - 16) < 0.5) f = redSide ? Material.RED_CONCRETE : Material.BLUE_CONCRETE;
                else if (x == 0) f = Material.WHITE_CONCRETE;
                else if (Math.abs(Math.abs(x) - Math.abs(z)) == 0 && r < 15) f = redSide ? Material.REDSTONE_LAMP : Material.SEA_LANTERN;
                else {
                    int h = hash(x, z);
                    f = h < 500 ? Material.POLISHED_BLACKSTONE_BRICKS : h < 850 ? Material.POLISHED_BLACKSTONE : Material.CRACKED_POLISHED_BLACKSTONE_BRICKS;
                }
                set(x, fy, z, f);
                if (r > R - 0.6) {                              // force-field wall
                    set(x, fy + 1, z, Material.POLISHED_BLACKSTONE_WALL);
                    for (int y = 2; y <= 4; y++) set(x, fy + y, z, redSide ? Material.RED_STAINED_GLASS : Material.LIGHT_BLUE_STAINED_GLASS);
                    set(x, fy + 5, z, (Math.round(Math.toDegrees(Math.atan2(z, x))) % 30 == 0) ? Material.SEA_LANTERN : Material.POLISHED_BLACKSTONE_SLAB);
                }
                int depth = (int) Math.round(Math.pow(Math.max(0, 1 - r / (R + 0.5)), 0.7) * 10);
                for (int d = 1; d <= depth; d++) set(x, fy - d, z, d == depth && hash(x, d) % 6 == 0 ? Material.SHROOMLIGHT : Material.POLISHED_DEEPSLATE);
            }
        // eight pillars to fight around, alternating colours
        for (int i = 0; i < 8; i++) {
            double a = i * Math.PI / 4;
            int px = (int) Math.round(Math.cos(a) * 13), pz = (int) Math.round(Math.sin(a) * 13);
            boolean red = px > 0;
            for (int y = 1; y <= 4; y++) set(px, fy + y, pz, Material.QUARTZ_PILLAR);
            set(px, fy + 5, pz, red ? Material.REDSTONE_LAMP : Material.SEA_LANTERN);
        }
        // four floating platforms for double-jump play
        for (int i = 0; i < 4; i++) {
            double a = Math.PI / 4 + i * Math.PI / 2;
            int cx = (int) Math.round(Math.cos(a) * 17), cz = (int) Math.round(Math.sin(a) * 17);
            for (int x = -1; x <= 1; x++) for (int z = -1; z <= 1; z++) set(cx + x, fy + 3, cz + z, Material.POLISHED_BLACKSTONE_SLAB);
            set(cx, fy + 3, cz, Material.SEA_LANTERN);
        }
        plugin.getLogger().info("Energy-sword arena built");
    }

    private void spawnDecor() {
        decor.add(w.spawn(new Location(w, AX + 0.5, fy + 9, AZ + 0.5), TextDisplay.class, t -> {
            plugin.prepare(t);
            t.setBillboard(Display.Billboard.CENTER);
            t.setBackgroundColor(Color.fromARGB(0, 0, 0, 0));
            t.setShadowed(true);
            t.setTransformation(new Transformation(new Vector3f(), new AxisAngle4f(), new Vector3f(4f, 4f, 4f), new AxisAngle4f()));
            t.text(Component.text()
                    .append(Component.text("СВЕТОВЫЕ ", TextColor.color(0x2A7BFF)).decorate(TextDecoration.BOLD))
                    .append(Component.text("МЕЧИ", TextColor.color(0xFF2A2A)).decorate(TextDecoration.BOLD))
                    .append(Component.newline())
                    .append(Component.text("дуэли на энергоклинках · до " + WIN_KILLS + " побед", NamedTextColor.GRAY)).build());
        }));
        SpaceDecor.planet(plugin, w, new Location(w, AX + 60, fy + 30, AZ - 55), SpaceDecor.Kind.MARS, 34f, 77);
        SpaceDecor.planet(plugin, w, new Location(w, AX - 60, fy + 22, AZ + 50), SpaceDecor.Kind.ICE, 18f, 78);
    }
}
