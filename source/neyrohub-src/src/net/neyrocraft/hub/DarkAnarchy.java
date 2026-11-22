package net.neyrocraft.hub;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import net.kyori.adventure.text.format.TextColor;
import net.kyori.adventure.text.format.TextDecoration;
import net.kyori.adventure.title.Title;
import org.bukkit.*;
import org.bukkit.entity.Mannequin;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.EventPriority;
import org.bukkit.event.entity.EntityDamageEvent;
import org.bukkit.event.entity.PlayerDeathEvent;
import org.bukkit.event.player.PlayerChangedWorldEvent;
import org.bukkit.event.player.PlayerInteractEntityEvent;
import org.bukkit.event.player.PlayerMoveEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.event.player.PlayerRespawnEvent;
import org.bukkit.event.player.PlayerTeleportEvent;
import org.bukkit.inventory.ItemStack;
import org.bukkit.persistence.PersistentDataType;
import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.scheduler.BukkitRunnable;
import org.bukkit.util.Vector;

import java.time.Duration;
import java.util.HashSet;
import java.util.HashMap;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/** Standalone dark-side/anarchy world. The protection-free rules are scoped to this world only. */
final class DarkAnarchy implements Listener {
    static final String WORLD_NAME = "neyro_anarchy";
    private final JavaPlugin plugin;
    private final NamespacedKey darkKey;
    private final Set<UUID> pending = new HashSet<>();
    private final Set<UUID> deaths = new HashSet<>();
    private World world;
    private World lobby;
    private World deadRealm;
    private Mannequin npc;
    private Mannequin returnNpc;
    private record Gate(World source, int floor, int z) {
        Location center() { return new Location(source, 0.5, floor + 3.35, z + 0.5); }

        boolean contains(Location location) {
            return source.equals(location.getWorld()) && Math.abs(location.getZ() - (z + 0.5)) <= 2.0
                    && Math.abs(location.getX() - 0.5) < 1.5
                    && location.getY() >= floor + 0.8 && location.getY() < floor + 7;
        }
    }

    private record OpenGate(Gate gate, long expiresAt) { }

    private final Map<UUID, OpenGate> gates = new HashMap<>();
    private final Map<UUID, BukkitRunnable> openingGates = new HashMap<>();
    private final Map<UUID, RiftCrossing> crossings = new HashMap<>();
    private Gate entrance;
    private Gate exit;
    private static final Particle.DustOptions CRIMSON = new Particle.DustOptions(Color.fromRGB(255, 24, 45), 1.45f);
    private static final Particle.DustOptions EMBER = new Particle.DustOptions(Color.fromRGB(153, 3, 28), 1.65f);
    private static final Particle.DustOptions CINDER = new Particle.DustOptions(Color.fromRGB(255, 107, 57), 0.72f);
    private final net.kyori.adventure.bossbar.BossBar banner = net.kyori.adventure.bossbar.BossBar.bossBar(
            Component.text("☠  АНАРХИЯ  ☠", NamedTextColor.RED), 1,
            net.kyori.adventure.bossbar.BossBar.Color.RED, net.kyori.adventure.bossbar.BossBar.Overlay.PROGRESS);

    DarkAnarchy(JavaPlugin plugin) {
        this.plugin = plugin;
        this.darkKey = new NamespacedKey(plugin, "dark_side");
        world = new WorldCreator(WORLD_NAME).createWorld();
        if (world == null) return;
        world.setDifficulty(Difficulty.HARD);
        world.setPVP(true);
        world.setSpawnFlags(true, true);
        world.setGameRule(GameRule.KEEP_INVENTORY, false);
        world.setGameRule(GameRule.MOB_GRIEFING, true);
        world.setGameRule(GameRule.DO_IMMEDIATE_RESPAWN, false);
        var templeMarker = plugin.getDataFolder().toPath().resolve("anarchy-temple-v4");
        if (!java.nio.file.Files.exists(templeMarker)) {
            if (!java.nio.file.Files.exists(templeMarker.resolveSibling("anarchy-temple-v1"))
                    && !java.nio.file.Files.exists(templeMarker.resolveSibling("anarchy-temple-v3"))) {
                world.getWorldBorder().setCenter(0, 0);
                world.getWorldBorder().setSize(30000);
            }
            if (DarkTemple.build((NeyroHub) plugin, world, 65, 0, false)) {
                world.setSpawnLocation(0, 66, 11);
                for (var entity : world.getNearbyEntities(new Location(world, 0.5, 66, 5.5), 8, 8, 8)) {
                    if (entity.getScoreboardTags().contains("neyro-dark-npc")) entity.remove();
                }
                world.save();
                try {
                    java.nio.file.Files.createDirectories(templeMarker.getParent());
                    java.nio.file.Files.writeString(templeMarker, "built");
                } catch (java.io.IOException ex) {
                    plugin.getLogger().warning("Could not record anarchy temple version: " + ex.getMessage());
                }
            }
        }
        world.setChunkForceLoaded(0, 0, true);
        exit = new Gate(world, 65, 0);
        lobby = new WorldCreator("neyro_hub3").generator(new NeyroHub.VoidGenerator()).createWorld();
        if (lobby != null) {
            lobby.setDifficulty(Difficulty.PEACEFUL);
            lobby.setTime(18000);
            lobby.setGameRule(GameRule.DO_DAYLIGHT_CYCLE, false);
            lobby.setSpawnLocation(0, 101, 0);
            entrance = new Gate(lobby, 100, 10);
            DarkTemple.build((NeyroHub) plugin, lobby, 100, 10, true);
            lobby.setChunkForceLoaded(0, 0, true);
        }
        Bukkit.getScheduler().runTaskTimer(plugin, () -> {
            if (lobby != null && (npc == null || !npc.isValid())) spawnNpc();
            if (returnNpc == null || !returnNpc.isValid())
                returnNpc = spawnLord(new Location(world, 3.5, 66, 5.5, 0, 0), "◆ ПКМ — вернуться в главный хаб");
        }, 1, 40);
        Bukkit.getScheduler().runTaskTimer(plugin, this::showGates, 5, 5);
        Bukkit.getScheduler().runTaskTimer(plugin, this::showTempleMist, 12, 12);
        if (lobby != null) Bukkit.getScheduler().runTask(plugin, this::prepareDeadRealm);
        Bukkit.getPluginManager().registerEvents(this, plugin);
    }

    World world() { return world; }
    boolean inLobby(World candidate) { return candidate.equals(lobby); }
    boolean isDark(Player p) { return p.getPersistentDataContainer().has(darkKey, PersistentDataType.BYTE); }

    private void spawnNpc() {
        npc = spawnLord(new Location(lobby, -2.5, 101, 4.5, 180, 0), "◆ ПКМ — говорить");
    }

    private Mannequin spawnLord(Location location, String prompt) {
        return location.getWorld().spawn(location, Mannequin.class, a -> {
            a.setPersistent(false);
            a.setRemoveWhenFarAway(false);
            a.setGravity(false); a.setInvulnerable(true); a.setImmovable(true);
            a.setDescription(Component.text(prompt, NamedTextColor.RED));
            a.setProfile(io.papermc.paper.datacomponent.item.ResolvableProfile.resolvableProfile()
                    .skinPatch(s -> s.body(net.kyori.adventure.key.Key.key("minecraft", "entity/player/wide/neyro_dark_lord"))
                            .model(org.bukkit.profile.PlayerTextures.SkinModel.CLASSIC))
                    .build());
            a.customName(Component.text("✦ ТЁМНЫЙ ЛОРД ✦", TextColor.color(0xFF2020)).decorate(TextDecoration.BOLD));
            a.setCustomNameVisible(true); a.addScoreboardTag("neyro-dark-npc");
            a.getEquipment().setItemInMainHand(new ItemStack(Material.REDSTONE_TORCH));
        });
    }

    void visit(Player p) {
        if (lobby == null || !((NeyroHub) plugin).authed(p)) return;
        if (p.teleport(new Location(lobby, 0.5, 101, 0.5, 0, 0))) {
            if (npc == null || !npc.isValid()) spawnNpc();
            if (!p.isOp()) p.setGameMode(GameMode.ADVENTURE);
            p.sendMessage(Component.text("✦ HUB 3 · Нажми ПКМ по Тёмному Лорду, чтобы поговорить.", NamedTextColor.RED));
        }
    }

    private void offer(Player p) {
        NeyroHub hub = (NeyroHub) plugin;
        if (!hub.authed(p) || hub.dialogue().talking(p) || gateActive(p)) return;
        hub.dialogue().start(p, new Dialogue.Speaker("Тёмный Лорд", "Хранитель врат", 0xFF3030, 0xE040080C),
                npc.getEyeLocation(), conversation -> conversation.say(
                        "За этими вратами — анархия. Здесь выживает тот, кто умеет держать удар. В обычном мире за тобой могут начать охоту.",
                        () -> conversation.choose(
                                Dialogue.Choice.of("Принять тёмную сторону", Dialogue.Kind.DARK, choice -> {
                                    choice.end(true);
                                    open(p, entrance, true);
                                }),
                                Dialogue.Choice.of("Вернуться позже", Dialogue.Kind.LEAVE, choice -> choice.end(true)))));
    }

    boolean command(Player p, String sub) {
        if (sub.equalsIgnoreCase("accept") && p.getWorld().equals(lobby)
                && ((NeyroHub) plugin).authed(p) && pending.remove(p.getUniqueId())) {
            open(p, entrance);
            return true;
        }
        if (sub.equalsIgnoreCase("cancel")) {
            boolean inTransit = p.getWorld().equals(deadRealm);
            forgetGate(p);
            if (inTransit) p.teleport(lobby.getSpawnLocation());
            p.sendMessage(Component.text("Переход отменён.", NamedTextColor.GRAY));
            return true;
        }
        return false;
    }

    private void open(Player player, Gate gate) {
        open(player, gate, false);
    }

    private void open(Player player, Gate gate, boolean immediateAfterOpening) {
        UUID id = player.getUniqueId();
        if (openingGates.containsKey(id) || crossings.containsKey(id)) return;
        gates.remove(id);
        Location center = gate.center();
        player.playSound(center, Sound.BLOCK_PORTAL_TRIGGER, 0.8f, 0.65f);
        BukkitRunnable animation = new BukkitRunnable() {
            private int step;

            @Override public void run() {
                if (!player.isOnline() || !player.getWorld().equals(gate.source())) {
                    openingGates.remove(id);
                    cancel();
                    return;
                }
                drawVortex(player, gate, step * 0.35, Math.min(1.0, (step + 1) / 10.0));
                if (++step < 10) return;
                openingGates.remove(id);
                OpenGate pass = new OpenGate(gate, System.currentTimeMillis() + 60_000);
                gates.put(id, pass);
                player.playSound(center, Sound.BLOCK_BEACON_ACTIVATE, 0.8f, 0.65f);
                if (immediateAfterOpening && gates.remove(id, pass)) {
                    cancel();
                    cross(player, pass);
                    return;
                }
                tryCross(player, pass);
                HudBoard.notice(player, Component.text("Врата открыты на минуту · войди в алый проход", NamedTextColor.RED));
                cancel();
            }
        };
        openingGates.put(id, animation);
        animation.runTaskTimer(plugin, 0L, 2L);
    }

    private void showGates() {
        long now = System.currentTimeMillis();
        gates.entrySet().removeIf(entry -> entry.getValue().expiresAt() < now);
        double spin = now % 4000L / 4000.0 * 2 * Math.PI;
        for (var entry : Map.copyOf(gates).entrySet()) {
            Player player = Bukkit.getPlayer(entry.getKey());
            Gate gate = entry.getValue().gate();
            if (player != null && player.getWorld().equals(gate.source())) {
                tryCross(player, entry.getValue());
                if (crossings.containsKey(player.getUniqueId())) continue;
                drawVortex(player, gate, spin, 1.0);
            }
        }
    }

    private void drawVortex(Player player, Gate gate, double spin, double scale) {
        Location center = gate.center();
        for (int point = 0; point < 18; point++) {
            double angle = point * Math.PI / 9;
            Location edge = center.clone().add(Math.cos(angle) * 1.28 * scale, Math.sin(angle) * 2.19 * scale, 0);
            player.spawnParticle(Particle.DUST, edge, 1, CRIMSON);
            Location spiral = center.clone().add(Math.cos(angle + spin) * 0.94 * scale,
                    Math.sin(angle * 2 + spin) * 1.91 * scale, 0);
            player.spawnParticle(Particle.DUST, spiral, 1, EMBER);
        }
        player.spawnParticle(Particle.SMOKE, center, 6, 0.85 * scale, 1.5 * scale, 0.09, 0.01);
    }

    private void pulse(Player player, Location at) {
        player.playSound(at, Sound.ENTITY_ENDERMAN_TELEPORT, 0.75f, 0.65f);
        player.spawnParticle(Particle.DUST, at.clone().add(0, 1, 0), 48, 0.65, 0.9, 0.65, 0, CRIMSON);
        player.spawnParticle(Particle.SMOKE, at.clone().add(0, 0.7, 0), 22, 0.6, 0.7, 0.6, 0.03);
    }

    private void showTempleMist() {
        if (entrance != null) showTempleMist(entrance, -1);
        showTempleMist(exit, 1);
    }

    private void showTempleMist(Gate gate, int aisle) {
        if (gate.source().getPlayers().isEmpty()) return;
        for (int side : new int[]{-1, 1}) {
            for (int distance : new int[]{2, 8, 14}) {
                Location brazier = new Location(gate.source(), side * 6 + 0.5,
                        gate.floor() + 4, gate.z() + aisle * distance + 0.5);
                if (brazier.getBlock().isEmpty() && brazier.clone().subtract(0, 1, 0)
                        .getBlock().getType() == Material.SHROOMLIGHT) {
                    gate.source().spawnParticle(Particle.SMOKE, brazier, 4, 0.18, 0.07, 0.18, 0.008);
                    gate.source().spawnParticle(Particle.DUST, brazier, 3, 0.2, 0.13, 0.2, 0, CINDER);
                }
            }
        }
    }

    private void cross(Player player, OpenGate pass) {
        World source = pass.gate().source();
        var rift = new RiftCrossing(player, pass);
        crossings.put(player.getUniqueId(), rift);
        player.showTitle(Title.title(Component.text("АЛЫЙ РАЗЛОМ", TextColor.color(0xFF283D))
                .decorate(TextDecoration.BOLD), Component.text("Грань миров", TextColor.color(0xCE8294)),
                Title.Times.times(Duration.ofMillis(100), Duration.ofMillis(1700), Duration.ofMillis(300))));
        player.playSound(player.getLocation(), Sound.BLOCK_RESPAWN_ANCHOR_CHARGE, 0.85f, 0.58f);
        source.spawnParticle(Particle.SMOKE, pass.gate().center(), 22, 0.8, 1.2, 0.1, 0.025);
        rift.runTaskTimer(plugin, 1, 2);
    }

    /** A short client-side passage. It doesn't change flight, game mode or potion effects. */
    private final class RiftCrossing extends BukkitRunnable {
        private final Player player;
        private final OpenGate pass;
        private org.bukkit.entity.TextDisplay veil;
        private double veilOffset;
        private int frame;
        private boolean walking;
        private boolean changingWorld;

        RiftCrossing(Player player, OpenGate pass) {
            this.player = player;
            this.pass = pass;
        }

        @Override public void run() {
            NeyroHub hub = (NeyroHub) plugin;
            if (!player.isOnline() || player.isDead() || !hub.authed(player)
                    || !(walking ? deadRealm : pass.gate().source()).equals(player.getWorld())) {
                cancelCrossing(player, false);
                return;
            }
            try {
                if (walking) {
                    if (!exploreDeadRealm()) return;
                } else effects(hub);
            } catch (RuntimeException ex) {
                plugin.getLogger().warning("Rift effects failed: " + ex.getMessage());
                frame = walking ? 64 : 20;
            }
            if (++frame < (walking ? 65 : 21)) return;
            if (!walking && pass.gate().source().equals(lobby) && startDeadRealm()) return;

            crossings.remove(player.getUniqueId(), this);
            cancel();
            clearVeil();
            if (pass.gate().source().equals(lobby)) {
                enter(player);
                if (!world.equals(player.getWorld())) {
                    if (walking) player.teleport(lobby.getSpawnLocation());
                    player.sendMessage(Component.text("Сервер отклонил перенос в анархию. Поговори с Лордом снова.", NamedTextColor.RED));
                }
            } else hub.sendToHub(player, false);
        }

        private boolean startDeadRealm() {
            clearVeil();
            try {
                if (!prepareDeadRealm()) return false;
                changingWorld = true;
                walking = player.teleport(new Location(deadRealm, .5, 101, 1.5, 0, 0));
                if (walking) {
                    player.showTitle(Title.title(Component.text("МЁРТВЫЙ МИР", NamedTextColor.DARK_RED),
                            Component.text("Осмотрись · алые врата ведут в анархию", NamedTextColor.GRAY),
                            Title.Times.times(Duration.ofMillis(150), Duration.ofSeconds(3), Duration.ofMillis(500))));
                    player.playSound(player.getLocation(), Sound.AMBIENT_SOUL_SAND_VALLEY_MOOD, 1f, .65f);
                }
            } catch (RuntimeException ex) {
                plugin.getLogger().warning("Dead realm: " + ex.getMessage());
            } finally {
                changingWorld = false;
            }
            return walking;
        }

        private int explorationTicks;
        private boolean leavingRealm;

        private boolean exploreDeadRealm() {
            Location at = player.getLocation();
            if (leavingRealm) {
                effects((NeyroHub) plugin);
                return true;
            }
            if (Math.abs(at.getX() - .5) <= 2.5 && at.getZ() >= 24.5 && at.getZ() <= 26.4
                    && at.getY() >= 100.8 && at.getY() <= 105.5) {
                leavingRealm = true;
                frame = 57;
                player.playSound(at, Sound.BLOCK_RESPAWN_ANCHOR_CHARGE, .8f, .6f);
                return true;
            }
            if (at.getY() < 98 || at.getY() > 113 || Math.abs(at.getX() - .5) > 8
                    || at.getZ() < -2 || at.getZ() > 30) {
                player.teleport(deadRealm.getSpawnLocation().add(.5, 0, .5));
                player.setFallDistance(0);
            }
            if (++explorationTicks % 3 == 0) {
                player.spawnParticle(Particle.ASH, at.clone().add(0, 1.5, 0), 42, 4, 1.1, 5, .025);
                player.spawnParticle(Particle.SOUL, new Location(deadRealm, .5, 102, 25.2),
                        7, 1.4, .9, .2, .02);
            }
            if (explorationTicks % 85 == 0) player.playSound(at, Sound.ENTITY_WARDEN_HEARTBEAT, .35f, .55f);
            return false;
        }

        private void effects(NeyroHub hub) {
            if (frame == 4 || frame == 13) player.playSound(player.getLocation(), Sound.ENTITY_WARDEN_HEARTBEAT, 0.65f, frame == 4 ? 0.78f : 1.1f);
            if (frame >= 12) {
                Location eye = player.getEyeLocation();
                Location panel = eye.clone().add(eye.getDirection().multiply(0.36));
                if (veil == null) {
                    veil = Hyperjump.whitePanel(hub, player, panel, 0);
                    veilOffset = panel.getY() - veil.getY();
                } else veil.teleport(panel.subtract(0, veilOffset, 0));
                veil.setBackgroundColor(Color.fromARGB(Math.min(248, (frame - 12) * 31), 53, 2, 14));
            }
            drawRift(player, frame);
        }

        void clearVeil() {
            if (veil != null) veil.remove();
            veil = null;
        }
    }

    private boolean prepareDeadRealm() {
        if (deadRealm != null) return true;
        World realm = new WorldCreator("neyro_dead_realm").generator(new NeyroHub.VoidGenerator()).createWorld();
        if (realm == null) return false;
        realm.setDifficulty(Difficulty.PEACEFUL);
        realm.setTime(18000);
        realm.setGameRule(GameRule.DO_DAYLIGHT_CYCLE, false);
        realm.setSpawnLocation(0, 101, 1);
        for (int chunkX = -1; chunkX <= 0; chunkX++) {
            for (int chunkZ = 0; chunkZ <= 1; chunkZ++) realm.setChunkForceLoaded(chunkX, chunkZ, true);
        }
        for (int z = 0; z <= 27; z++) {
            for (int x = -6; x <= 6; x++) {
                Material floor = Math.abs(x) <= 1 ? Material.POLISHED_BLACKSTONE
                        : (x * x + z) % 5 == 0 ? Material.SOUL_SOIL : Material.BASALT;
                realm.getBlockAt(x, 100, z).setType(floor, false);
                if (Math.abs(x) == 6) realm.getBlockAt(x, 101, z).setType(Material.BLACKSTONE, false);
                if (z % 6 == 4 && Math.abs(x) == 4) {
                    for (int y = 101; y <= 105; y++) {
                        realm.getBlockAt(x, y, z).setType(y == 103 ? Material.SOUL_LANTERN : Material.POLISHED_BASALT, false);
                    }
                }
            }
        }
        for (int x = -3; x <= 3; x++) for (int y = 101; y <= 107; y++) {
            Material block = Math.abs(x) == 3 || y == 107 ? Material.CRYING_OBSIDIAN : Material.RED_STAINED_GLASS;
            realm.getBlockAt(x, y, 26).setType(block, false);
        }
        deadRealm = realm;
        return true;
    }

    private static void drawRift(Player player, int frame) {
        Location eye = player.getEyeLocation();
        Vector forward = eye.getDirection().normalize();
        Vector right = forward.clone().crossProduct(new Vector(0, 1, 0));
        if (right.lengthSquared() < 0.0001) right.setX(1);
        right.normalize();
        Vector up = right.clone().crossProduct(forward).normalize();
        // Nested rings rush toward the eye while their staggered rotation pulls the sparks into a spiral.
        for (int ring = 0; ring < 4; ring++) {
            double phase = ((frame * 0.26 + ring * 0.61) % 2.44) / 2.44;
            double distance = 1.48 - phase * 1.13;
            double radius = 0.17 + Math.pow(phase, 1.3) * 0.57;
            Location center = eye.clone().add(forward.clone().multiply(distance));
            for (int point = 0; point < 20; point++) {
                double angle = point * Math.PI / 10 + frame * 0.35 + ring * 0.58;
                double offset = radius * (1 + 0.1 * Math.sin(angle * 3 - frame * 0.3));
                Location at = center.clone()
                        .add(right.clone().multiply(Math.cos(angle) * offset))
                        .add(up.clone().multiply(Math.sin(angle) * offset));
                player.spawnParticle(Particle.DUST, at, 1, point % 5 == 0 ? CINDER : ring % 2 == 0 ? CRIMSON : EMBER);
            }
        }
        player.spawnParticle(Particle.SMOKE, eye.add(forward.clone().multiply(0.75)),
                9, 0.28, 0.23, 0.21, 0.018);
    }

    private void cancelCrossing(Player player, boolean reopen) {
        UUID id = player.getUniqueId();
        RiftCrossing rift = crossings.remove(id);
        if (rift == null) return;
        rift.cancel();
        rift.clearVeil();
        player.clearTitle();
        if (reopen && rift.pass.expiresAt() >= System.currentTimeMillis()
                && rift.pass.gate().source().equals(player.getWorld())) gates.put(id, rift.pass);
    }

    void enter(Player p) {
        if (world == null) return;
        Location arrival = world.getSpawnLocation().add(0.5, 0, 0.5);
        arrival.setYaw(0);
        if (!p.teleport(arrival)) return;
        ((NeyroHub) plugin).refreshMusic(p);
        p.sendMessage(Component.text("Хранитель · Здесь Саманта бессильна. Она не видит вас и не слышит.", NamedTextColor.RED));
        Bukkit.getScheduler().runTaskLater(plugin, () -> {
            VoicePack voice = ((NeyroHub) plugin).voice();
            if (p.isOnline() && p.getWorld().equals(world) && voice != null && voice.hasVoice(p)) {
                p.playSound(p.getLocation(), "neyrotemple:warden", org.bukkit.SoundCategory.VOICE, 1f, 0.9f);
            }
        }, 25L);
        pulse(p, arrival);
        p.getPersistentDataContainer().set(darkKey, PersistentDataType.BYTE, (byte) 1);
        p.setPlayerListName("§c" + p.getName());
        p.displayName(Component.text(p.getName(), TextColor.color(0xFF3030)));
        p.sendTitle("§4ТЁМНАЯ СТОРОНА", "§cДобро пожаловать в анархию", 10, 60, 20);
        p.sendMessage(Component.text("Античит и правила основного мира здесь не меняются; анархия действует только в этом мире.", NamedTextColor.DARK_GRAY));
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onDeath(PlayerDeathEvent event) {
        if (event.getPlayer().getWorld().equals(world)) deaths.add(event.getPlayer().getUniqueId());
    }

    @EventHandler(priority = EventPriority.HIGHEST)
    public void onRespawn(PlayerRespawnEvent event) {
        Player player = event.getPlayer();
        if (!deaths.remove(player.getUniqueId()) || world == null) return;
        Location spawn = world.getSpawnLocation();
        event.setRespawnLocation(new Location(world, spawn.getBlockX() + .5, spawn.getBlockY(),
                spawn.getBlockZ() + .5, 0, 0));
        Bukkit.getScheduler().runTask(plugin, () -> {
            if (player.isOnline() && player.getWorld().equals(world)) ((NeyroHub) plugin).refreshMusic(player);
        });
    }

    @EventHandler public void onNpc(PlayerInteractEntityEvent e) {
        if (e.getHand() != org.bukkit.inventory.EquipmentSlot.HAND) return;
        if (returnNpc != null && e.getRightClicked().equals(returnNpc)) {
            e.setCancelled(true);
            Player player = e.getPlayer();
            NeyroHub hub = (NeyroHub) plugin;
            if (!hub.authed(player) || hub.dialogue().talking(player) || gateActive(player)) return;
            hub.dialogue().start(player, new Dialogue.Speaker("Тёмный Лорд", "Храм анархии", 0xFF3030, 0xE040080C),
                    returnNpc.getEyeLocation(), session -> session.choose(
                            Dialogue.Choice.of("Вернуться в главный хаб", Dialogue.Kind.DARK, choice -> {
                                choice.end(true);
                                open(player, exit, true);
                            }),
                            Dialogue.Choice.of("Остаться в анархии", Dialogue.Kind.LEAVE, choice -> choice.end(true))));
            return;
        }
        if (npc != null && e.getRightClicked().equals(npc)) { e.setCancelled(true); offer(e.getPlayer()); }
    }
    @EventHandler(ignoreCancelled = true)
    public void onGate(PlayerMoveEvent e) {
        if (e instanceof PlayerTeleportEvent) return;
        Player p = e.getPlayer();
        if (crossings.containsKey(p.getUniqueId())) {
            RiftCrossing rift = crossings.get(p.getUniqueId());
            if (rift.walking && !rift.leavingRealm) return;
            if (!e.hasChangedPosition()) return;
            Location fixed = e.getFrom().clone();
            fixed.setDirection(e.getTo().getDirection());
            e.setTo(fixed);
            return;
        }
        OpenGate pass = gates.get(p.getUniqueId());
        if (pass == null || pass.expiresAt() < System.currentTimeMillis()) return;
        Gate gate = pass.gate();
        Location to = e.getTo();
        if (!gate.contains(to)) return;
        Bukkit.getScheduler().runTask(plugin, () -> tryCross(p, pass));
    }

    private boolean gateActive(Player p) {
        UUID id = p.getUniqueId();
        return openingGates.containsKey(id) || crossings.containsKey(id)
                || (gates.containsKey(id) && gates.get(id).expiresAt() >= System.currentTimeMillis());
    }

    private void tryCross(Player p, OpenGate pass) {
        if (!p.isOnline() || p.isDead() || pass.expiresAt() < System.currentTimeMillis()
                || !pass.gate().contains(p.getLocation()) || !((NeyroHub) plugin).authed(p)
                || !gates.remove(p.getUniqueId(), pass)) return;
        cross(p, pass);
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onCrossingInterrupted(EntityDamageEvent event) {
        if (event.getFinalDamage() <= 0 || !(event.getEntity() instanceof Player player)
                || !crossings.containsKey(player.getUniqueId())) return;
        if (player.getWorld().equals(deadRealm)) return;
        cancelCrossing(player, true);
        HudBoard.notice(player, Component.text("Разлом прерван · попробуй войти снова", NamedTextColor.RED));
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onTeleport(PlayerTeleportEvent event) {
        RiftCrossing rift = crossings.get(event.getPlayer().getUniqueId());
        if (rift != null && rift.changingWorld) return;
        if (!event.getFrom().getWorld().equals(event.getTo().getWorld())) {
            cancelCrossing(event.getPlayer(), false);
        }
    }

    private void forgetGate(Player player) {
        UUID id = player.getUniqueId();
        pending.remove(id);
        BukkitRunnable animation = openingGates.remove(id);
        if (animation != null) animation.cancel();
        gates.remove(id);
        cancelCrossing(player, false);
    }

    @EventHandler public void onQuit(PlayerQuitEvent e) {
        deaths.remove(e.getPlayer().getUniqueId());
        forgetGate(e.getPlayer());
    }

    @EventHandler public void onReturnFromDisconnect(org.bukkit.event.player.PlayerJoinEvent event) {
        Player player = event.getPlayer();
        if (!player.getWorld().getName().equals("neyro_dead_realm")) return;
        Bukkit.getScheduler().runTask(plugin, () -> {
            if (player.isOnline() && player.getWorld().getName().equals("neyro_dead_realm") && lobby != null) {
                player.teleport(lobby.getSpawnLocation().add(.5, 0, .5));
            }
        });
    }

    @EventHandler(ignoreCancelled = true)
    public void protectDeadRealm(EntityDamageEvent event) {
        if (event.getEntity().getWorld().equals(deadRealm)) event.setCancelled(true);
    }
    @EventHandler public void onWorld(PlayerChangedWorldEvent e) {
        RiftCrossing rift = crossings.get(e.getPlayer().getUniqueId());
        if (rift != null && rift.changingWorld) return;
        VoicePack voice = ((NeyroHub) plugin).voice();
        if (voice != null) voice.syncDark(e.getPlayer());
        forgetGate(e.getPlayer());
        if (e.getPlayer().getWorld().equals(world)) e.getPlayer().showBossBar(banner);
        else e.getPlayer().hideBossBar(banner);
        if (world != null && e.getPlayer().getWorld().equals(world)) e.getPlayer().sendMessage(Component.text("☠ Анархия: берегись каждого игрока.", TextColor.color(0xFF2020)));
        if (world != null && e.getFrom().equals(world) && e.getPlayer().getWorld().getName().equals("world") && isDark(e.getPlayer())) {
            Bukkit.broadcast(Component.text("☠ Охота началась: тёмный игрок ", TextColor.color(0xFF3030)).append(Component.text(e.getPlayer().getName(), NamedTextColor.RED)));
        }
    }

}
