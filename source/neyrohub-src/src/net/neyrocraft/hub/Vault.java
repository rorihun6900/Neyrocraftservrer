package net.neyrocraft.hub;

import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;
import org.bukkit.potion.PotionEffect;

import java.io.File;
import java.io.IOException;
import java.util.List;

/**
 * Mini-games use only their own unique items: the player's real inventory is locked away on entry
 * (saved to disk, so a crash can't lose it) and handed back on exit.
 */
final class Vault {
    private final NeyroHub plugin;
    private final File dir;

    Vault(NeyroHub plugin) {
        this.plugin = plugin;
        this.dir = new File(plugin.getDataFolder(), "vault");
        dir.mkdirs();
    }

    private File file(Player p, String game) {
        return new File(dir, p.getUniqueId() + "-" + game + ".yml");
    }

    boolean holds(Player p, String game) {
        return file(p, game).exists();
    }

    /** Saves and clears the inventory. Does nothing if this game already holds the player's things. */
    void lock(Player p, String game) {
        File f = file(p, game);
        if (f.exists()) { clear(p); return; }
        try {
            YamlConfiguration y = new YamlConfiguration();
            y.set("contents", p.getInventory().getContents());
            y.set("level", p.getLevel());
            y.set("exp", (double) p.getExp());
            y.save(f);
        } catch (IOException e) {
            plugin.getLogger().warning("Vault: could not save " + p.getName() + " for " + game + ": " + e);
            return;                                  // keep their items rather than lose them
        }
        clear(p);
    }

    private static void clear(Player p) {
        p.getInventory().clear();
        p.setItemOnCursor(null);
        for (PotionEffect e : p.getActivePotionEffects()) p.removePotionEffect(e.getType());
    }

    /** Gives the saved inventory back (the game's own items are thrown away). */
    void unlock(Player p, String game) {
        File f = file(p, game);
        if (!f.exists()) return;
        YamlConfiguration y = YamlConfiguration.loadConfiguration(f);
        p.getInventory().clear();
        p.setItemOnCursor(null);
        List<?> list = y.getList("contents");
        if (list != null) {
            ItemStack[] items = new ItemStack[p.getInventory().getSize()];
            for (int i = 0; i < Math.min(items.length, list.size()); i++) if (list.get(i) instanceof ItemStack it) items[i] = it;
            p.getInventory().setContents(items);
        }
        p.setLevel(y.getInt("level"));
        p.setExp((float) y.getDouble("exp"));
        if (!f.delete()) f.deleteOnExit();
    }

    /** After a crash: anything still locked is returned when the player is no longer in that game. */
    void recover(Player p, String... games) {
        for (String g : games) if (holds(p, g)) unlock(p, g);
    }
}
