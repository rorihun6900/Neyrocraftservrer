package net.neyrocraft.hub;

import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import java.security.SecureRandom;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

/** Temporary, one-use verification codes, never written to logs or disk. */
final class EmailChallenge {
    private static final SecureRandom RANDOM=new SecureRandom();
    private final NeyroHub plugin;
    private final Map<UUID, Challenge> pending=new HashMap<>();
    private final Map<UUID, Long> nextSend=new HashMap<>();
    private static final class Challenge {
        final String code,email,kind; final long expires; int attempts;
        Challenge(String c,String e,String k) {code=c;email=e;kind=k;expires=System.currentTimeMillis()+600_000;}
    }
    EmailChallenge(NeyroHub plugin) {this.plugin=plugin;}
    void send(Player p,String email,String kind) {
        if (!email.matches("(?i)[a-z0-9._%+\\-]{1,64}@[a-z0-9\\-]+(?:\\.[a-z0-9\\-]+)+") || email.length()>254) {p.sendMessage("Укажи корректный адрес почты.");return;}
        long now=System.currentTimeMillis();
        if (now < nextSend.getOrDefault(p.getUniqueId(),0L)) {p.sendMessage("Повторная отправка доступна через минуту.");return;}
        nextSend.put(p.getUniqueId(),now+60_000L);
        Challenge c=new Challenge(String.format("%06d",RANDOM.nextInt(1_000_000)),email,kind);
        pending.remove(p.getUniqueId());
        p.sendMessage("Отправляем код на почту...");
        Bukkit.getScheduler().runTaskAsynchronously(plugin, () -> {
            try {
                String secret=NeyroMail.mailSecret();
                if(secret==null || secret.isBlank()) throw new java.io.IOException("mail unavailable");
                NeyroMail.send(email,secret,c.code);
                Bukkit.getScheduler().runTask(plugin, () -> {if(p.isOnline()) {pending.put(p.getUniqueId(),c);p.sendMessage(kind.equals("recover") ? "Код отправлен. Введи /resetpass <код> <новый пароль> <повтор>." : "Код отправлен. Введи /emailcode <код> (10 минут).");}});
            } catch(Exception e) { Bukkit.getScheduler().runTask(plugin, () -> {if(p.isOnline()) p.sendMessage("Письмо не отправлено. Повтори через минуту или обратись к администрации.");}); }
        });
    }
    String verify(Player p,String code,String kind) {
        Challenge c=pending.get(p.getUniqueId());
        if(c==null || !c.kind.equals(kind) || System.currentTimeMillis()>c.expires) {pending.remove(p.getUniqueId());p.sendMessage("Код истёк или не запрошен.");return null;}
        if(!java.security.MessageDigest.isEqual(c.code.getBytes(java.nio.charset.StandardCharsets.UTF_8),code.getBytes(java.nio.charset.StandardCharsets.UTF_8))) {
            if(++c.attempts>=5){pending.remove(p.getUniqueId());p.sendMessage("Попытки исчерпаны, запроси новый код.");}
            else p.sendMessage("Неверный код. Осталось попыток: "+(5-c.attempts)); return null;
        }
        pending.remove(p.getUniqueId());return c.email;
    }
    void clear(Player p) {pending.remove(p.getUniqueId());}
}
