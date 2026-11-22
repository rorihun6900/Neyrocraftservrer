package net.neyrocraft.hub;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import org.bukkit.*;
import org.bukkit.entity.BlockDisplay;
import org.bukkit.entity.Display;
import org.bukkit.inventory.ItemStack;
import org.bukkit.scheduler.BukkitRunnable;
import org.bukkit.util.Transformation;
import org.joml.Quaternionf;
import org.joml.Vector3f;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ThreadLocalRandom;

final class AnarchyDrop {
    private static final long INTERVAL = 30 * 60 * 20L;
    private final NeyroHub plugin;
    private final World world;
    private final List<BlockDisplay> displays = new ArrayList<>();
    private boolean active;

    AnarchyDrop(NeyroHub plugin, World world) {
        this.plugin = plugin;
        this.world = world;
        Bukkit.getScheduler().runTaskTimer(plugin, () -> {
            if (!world.getPlayers().isEmpty()) launch();
        }, INTERVAL, INTERVAL);
    }

    boolean launch() {
        if (active) return false;
        var random = ThreadLocalRandom.current();
        Location target = null;
        for (int attempt = 0; attempt < 16; attempt++) {
            int x = random.nextInt(-64, 65), z = random.nextBoolean() ? 55 : -55;
            var ground = world.getHighestBlockAt(x, z, HeightMap.MOTION_BLOCKING_NO_LEAVES);
            if (!ground.getType().isSolid() || !ground.getRelative(0, 1, 0).isEmpty()
                    || ground.getY() + 25 >= world.getMaxHeight()) continue;
            target = ground.getLocation().add(.5, 1.1, .5);
            break;
        }
        if (target == null) return false;
        Location landing = target;
        Location start = landing.clone().add(-42, 22, 0);
        active = true;
        announce("☠ ИСТРЕБИТЕЛЬ В ПУТИ · сброс X " + landing.getBlockX() + " / Z " + landing.getBlockZ());
        BlockDisplay fuselage = display(start, Material.POLISHED_BLACKSTONE, 5, .65f, .85f);
        BlockDisplay wing = display(start, Material.RED_NETHER_BRICKS, 1.5f, .3f, 6);
        BlockDisplay cockpit = display(start, Material.REDSTONE_BLOCK, 1, .45f, .72f);
        new BukkitRunnable() {
            private int frame;
            private BlockDisplay cargo;

            @Override public void run() {
                frame++;
                Location ship = start.clone().add(frame * .7, 0, 0);
                if (frame <= 120) {
                    fuselage.teleport(ship);
                    wing.teleport(ship.clone().add(-.7, 0, 0));
                    cockpit.teleport(ship.clone().add(.9, .55, 0));
                    world.spawnParticle(Particle.FLAME, ship.clone().add(-2.5, .25, 0), 6, .1, .1, .1, .02);
                }
                if (frame == 60) {
                    cargo = display(landing.clone().add(0, 21, 0), Material.BARREL, .85f, .85f, .85f);
                    world.playSound(ship, Sound.ENTITY_FIREWORK_ROCKET_LAUNCH, 2, .7f);
                }
                if (cargo != null) {
                    Location falling = landing.clone().add(0, Math.max(0, 21 - (frame - 60) * .35), 0);
                    cargo.teleport(falling);
                    world.spawnParticle(Particle.SMOKE, falling.clone().add(0, .8, 0), 3, .15, .1, .15, 0);
                }
                if (frame < 120) return;
                cancel();
                clear();
                world.playSound(landing, Sound.ENTITY_GENERIC_EXPLODE, 1, .7f);
                world.spawnParticle(Particle.CLOUD, landing, 28, .65, .35, .65, .04);
                for (ItemStack loot : List.of(new ItemStack(Material.IRON_INGOT, 12),
                        new ItemStack(Material.GOLDEN_APPLE, 2), new ItemStack(Material.DIAMOND, 2),
                        new ItemStack(Material.EXPERIENCE_BOTTLE, 8)))
                    world.dropItemNaturally(landing, loot);
                announce("☠ ГРУЗ НА ЗЕМЛЕ · X " + landing.getBlockX() + " / Y " + landing.getBlockY() + " / Z " + landing.getBlockZ());
            }
        }.runTaskTimer(plugin, 1, 1);
        return true;
    }

    private BlockDisplay display(Location at, Material material, float x, float y, float z) {
        BlockDisplay sprite = world.spawn(at, BlockDisplay.class, entity -> {
            entity.setPersistent(false);
            entity.setBlock(material.createBlockData());
            entity.setTeleportDuration(1);
            entity.setBrightness(new Display.Brightness(15, 15));
            entity.setTransformation(new Transformation(new Vector3f(-x / 2, 0, -z / 2),
                    new Quaternionf(), new Vector3f(x, y, z), new Quaternionf()));
        });
        displays.add(sprite);
        return sprite;
    }

    private void announce(String message) {
        Component notice = NeyroChat.systemMessage(Component.text(message, NamedTextColor.RED));
        for (var player : world.getPlayers()) player.sendMessage(notice);
    }

    void clear() {
        displays.forEach(BlockDisplay::remove);
        displays.clear();
        active = false;
    }
}
