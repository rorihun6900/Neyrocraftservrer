package net.neyrocraft.hub;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import org.bukkit.entity.Player;

import java.security.SecureRandom;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

/** In-game NEYRO$ slot machine; payouts include the original stake. */
final class NeyroCasino {
    private final NeyroHub plugin;
    private final SecureRandom random = new SecureRandom();
    private final Map<UUID, Long> last = new HashMap<>();

    NeyroCasino(NeyroHub plugin) { this.plugin = plugin; }

    boolean command(Player p, String[] args) {
        if (!plugin.authed(p)) return true;
        if (args.length == 0 || args[0].equalsIgnoreCase("rules")) {
            p.sendMessage(Component.text("◈ NEYRO-CASINO · ставки только NEYRO$", NamedTextColor.GOLD));
            p.sendMessage(Component.text("/casino 100 | 500 | 1000 · баланс /money", NamedTextColor.AQUA));
            p.sendMessage(Component.text("Шансы: 65% ×0, 28% ×2, 6% ×5, 1% ×10. Выплата включает ставку.", NamedTextColor.GRAY));
            return true;
        }
        int stake;
        try { stake = Integer.parseInt(args[0]); } catch (NumberFormatException e) { return command(p, new String[0]); }
        if (stake != 100 && stake != 500 && stake != 1000) { p.sendMessage("Ставки: 100, 500 или 1000 NEYRO$"); return true; }
        long now = System.currentTimeMillis();
        if (now - last.getOrDefault(p.getUniqueId(), 0L) < 5000) { p.sendMessage("Автомат перезаряжается 5 секунд"); return true; }
        if (!plugin.money().withdraw(p, stake)) { p.sendMessage("Недостаточно NEYRO$ · баланс /money"); return true; }
        last.put(p.getUniqueId(), now);
        int roll = random.nextInt(100);
        int multiplier = roll < 65 ? 0 : roll < 93 ? 2 : roll < 99 ? 5 : 10;
        long payout = (long) stake * multiplier;
        plugin.getLogger().info("Casino " + p.getName() + " stake=" + stake + " payout=" + payout);
        if (payout > 0) plugin.money().deposit(p, payout, "NEYRO-CASINO ×" + multiplier);
        else p.sendMessage(Component.text("NEYRO-CASINO: ставка $" + stake + " проиграна · баланс $" + plugin.money().balance(p), NamedTextColor.RED));
        return true;
    }
}
