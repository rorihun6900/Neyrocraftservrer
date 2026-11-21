package net.neyrocraft.hub;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.TextComponent;
import net.kyori.adventure.text.format.NamedTextColor;
import net.kyori.adventure.text.format.TextColor;
import net.kyori.adventure.text.format.TextDecoration;
import org.bukkit.Color;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.Particle;
import org.bukkit.World;
import org.bukkit.entity.BlockDisplay;
import org.bukkit.entity.Display;
import org.bukkit.entity.Entity;
import org.bukkit.entity.Interaction;
import org.bukkit.entity.Player;
import org.bukkit.entity.TextDisplay;
import org.bukkit.util.Transformation;
import org.joml.AxisAngle4f;
import org.joml.Vector3f;

import java.io.BufferedReader;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Random;

import net.neyrocraft.hub.Dialogue.Choice;
import net.neyrocraft.hub.Dialogue.Kind;
import net.neyrocraft.hub.Dialogue.Line;
import net.neyrocraft.hub.Dialogue.Session;

/**
 * Ami — the hologram of a young Twi'lek scientist (drawn in Blender: source\ami-src). Right-click her:
 * she speaks (voice + an RPG-style text window on the left of the screen), then the player answers on
 * a Mass Effect style wheel. She reacts to the player's side: heroes of the Light, villains of the Dark.
 */
final class Ami {
    enum Station { MAIN, ORION }

    private static final TextColor CYAN = TextColor.color(0x7FE8FF);
    private static final net.kyori.adventure.key.Key HOLO_FONT = net.kyori.adventure.key.Key.key("neyrocraft", "holo");
    static final Dialogue.Speaker SPEAKER = new Dialogue.Speaker("АМИ", "научный сотрудник · твилек", 0x7FE8FF, 0xD70C2260);

    // voiced lines (the recordings in voicepack-src\ami_*.ogg)
    private static final Line HELLO = new Line("ami_hello", 8.684, "Привет! Я Ами, научный сотрудник станции NeyroCraft. Чем могу помочь?");
    private static final Line WHO = new Line("ami_who", 12.37, "Я Ами — голограмма учёной этой станции. Я изучаю порталы и помогаю новым пилотам. А Саманта, Ядро Сервера, — моя коллега.");
    private static final Line WHERE = new Line("ami_where", 13.435, "Ты на главной палубе корабля NeyroCraft, в открытом космосе. Порталы вокруг ведут в разные миры: выживание, скайблок, лазертаг и старый майнкрафт.");
    private static final Line TO_ORION = new Line("ami_to_orion", 5.875, "Запускаю перенос на станцию «Орион». Держись крепче!");
    private static final Line TO_MAIN = new Line("ami_to_main", 6.077, "Возвращаю тебя на главный корабль. До встречи на палубе!");
    private static final Line HELLO_ORION = new Line("ami_hello_orion", 11.524, "Добро пожаловать на станцию «Орион»! Здесь всё устроено иначе. Арки этой станции пока пусты — скоро они откроют новые миры.");
    private static final Line WHERE_ORION = new Line("ami_where_orion", 14.69, "Это станция «Орион» — научная обсерватория на краю туманности. В центре — кристаллическое ядро. Арки вокруг — порталы в новые миры, которые мы ещё исследуем.");
    private static final Line BYE = new Line("ami_bye", 5.399, "Удачи, пилот! Если что — я здесь.");

    private final NeyroHub plugin;
    private final World world;
    private final Location base;
    private final Station station;
    private final List<Entity> entities = new ArrayList<>();
    private final Random rnd = new Random();
    private final java.util.function.Consumer<Player> transferTo;   // MAIN: go to Orion, ORION: back to the main hub
    private TextDisplay holoImage, holoPixels;
    private Interaction hitbox;
    private long tick;
    private int glitch;

    Ami(NeyroHub plugin, Location base, Station station, java.util.function.Consumer<Player> transfer) {
        this.plugin = plugin;
        this.world = base.getWorld();
        this.base = base;
        this.station = station;
        this.transferTo = transfer;
    }

    // ------------------------------------------------------------ the hologram

    void spawn() {
        Location l = base.clone();
        // projector: a glowing disc on the floor
        entities.add(world.spawn(l.clone().add(0, 0.02, 0), BlockDisplay.class, d -> {
            plugin.prepare(d);
            d.setBlock(Material.LIGHT_BLUE_STAINED_GLASS.createBlockData());
            d.setTransformation(new Transformation(new Vector3f(-0.65f, 0, -0.65f), new AxisAngle4f(), new Vector3f(1.3f, 0.12f, 1.3f), new AxisAngle4f()));
            d.setBrightness(new Display.Brightness(15, 15));
        }));
        entities.add(world.spawn(l.clone().add(0, 0.02, 0), BlockDisplay.class, d -> {
            plugin.prepare(d);
            d.setBlock(Material.SEA_LANTERN.createBlockData());
            d.setTransformation(new Transformation(new Vector3f(-0.3f, 0, -0.3f), new AxisAngle4f(), new Vector3f(0.6f, 0.14f, 0.6f), new AxisAngle4f()));
            d.setBrightness(new Display.Brightness(15, 15));
        }));
        // the rendered hologram (resource pack glyph, drawn in Blender) …
        holoImage = world.spawn(l.clone().add(0, 0.15, 0), TextDisplay.class, d -> {
            plugin.prepare(d);
            d.setVisibleByDefault(false);
            d.setBillboard(Display.Billboard.VERTICAL);
            d.setBackgroundColor(Color.fromARGB(0, 0, 0, 0));
            d.setShadowed(false);
            d.setBrightness(new Display.Brightness(15, 15));
            d.setTransformation(scaleT(1.35f, 0));
            d.setInterpolationDuration(3);
            d.text(Component.text("").font(HOLO_FONT));
        });
        // … and the same picture made of text pixels for players without the pack
        holoPixels = world.spawn(l.clone().add(0, 0.15, 0), TextDisplay.class, d -> {
            plugin.prepare(d);
            d.setVisibleByDefault(false);
            d.setBillboard(Display.Billboard.VERTICAL);
            d.setBackgroundColor(Color.fromARGB(0, 0, 0, 0));
            d.setShadowed(false);
            d.setLineWidth(1000);
            d.setBrightness(new Display.Brightness(15, 15));
            d.setTransformation(scaleT(0.21f, 0));
            d.setInterpolationDuration(3);
            d.text(pixelArt());
        });
        entities.add(holoImage);
        entities.add(holoPixels);
        entities.add(world.spawn(l.clone().add(0, 2.75, 0), TextDisplay.class, d -> {
            plugin.prepare(d);
            d.setBillboard(Display.Billboard.CENTER);
            d.setBackgroundColor(Color.fromARGB(110, 6, 30, 70));
            d.setTransformation(scaleT(0.85f, 0));
            d.text(Component.text()
                    .append(Component.text("◈ АМИ ◈", CYAN).decorate(TextDecoration.BOLD)).append(Component.newline())
                    .append(Component.text("научный сотрудник · твилек", NamedTextColor.GRAY)).append(Component.newline())
                    .append(Component.text("[ПКМ — поговорить]", NamedTextColor.YELLOW)).build());
        }));
        hitbox = world.spawn(l.clone(), Interaction.class, e -> {
            e.addScoreboardTag(NeyroHub.TAG);
            e.setPersistent(false);
            e.setInteractionWidth(1.1f);
            e.setInteractionHeight(2.5f);
            e.setResponsive(true);
        });
        entities.add(hitbox);
    }

    void remove() {
        for (Entity e : entities) if (e.isValid()) e.remove();
        entities.clear();
    }

    boolean isHitbox(Entity e) {
        return hitbox != null && hitbox.equals(e);
    }

    /** Every tick from the hub animation timer (every 2 ticks). */
    void animate() {
        tick++;
        if (holoImage == null || !holoImage.isValid()) return;
        float bob = (float) Math.sin(tick * 0.12) * 0.05f;
        float shift = 0;
        if (glitch > 0) { glitch--; shift = (rnd.nextFloat() - 0.5f) * 0.12f; }
        else if (rnd.nextInt(90) == 0) glitch = 2 + rnd.nextInt(3);
        byte opacity = glitch > 0 ? (byte) 110 : (byte) -1;
        holoImage.setInterpolationDelay(0);
        holoImage.setTransformation(scaleT(1.35f, bob, shift));
        holoImage.setTextOpacity(opacity);
        holoPixels.setInterpolationDelay(0);
        holoPixels.setTransformation(scaleT(0.21f, bob, shift));
        holoPixels.setTextOpacity(opacity);
        if (tick % 3 == 0) world.spawnParticle(Particle.GLOW, base.clone().add((rnd.nextDouble() - 0.5) * 0.8, 0.2 + rnd.nextDouble() * 2.2,
                (rnd.nextDouble() - 0.5) * 0.8), 1, 0, 0.05, 0, 0);
        if (tick % 20 == 0) refreshVisibility();
    }

    private void refreshVisibility() {
        VoicePack voice = plugin.voice();
        for (Player p : world.getPlayers()) {
            if (p.getLocation().distanceSquared(base) > 90 * 90) continue;
            boolean pack = voice != null && voice.hasVoice(p);
            if (pack) { p.showEntity(plugin, holoImage); p.hideEntity(plugin, holoPixels); }
            else { p.showEntity(plugin, holoPixels); p.hideEntity(plugin, holoImage); }
        }
    }

    private static Transformation scaleT(float s, float dy) { return scaleT(s, dy, 0); }

    private static Transformation scaleT(float s, float dy, float dx) {
        return new Transformation(new Vector3f(dx, dy, 0), new AxisAngle4f(), new Vector3f(s, s, s), new AxisAngle4f());
    }

    private Component pixelArt() {
        TextComponent.Builder b = Component.text();
        try (InputStream in = plugin.getResource("ami/ami_pixels.txt")) {
            if (in == null) return Component.text("◈ АМИ ◈", CYAN);
            BufferedReader r = new BufferedReader(new InputStreamReader(in, StandardCharsets.UTF_8));
            String line; boolean first = true;
            while ((line = r.readLine()) != null) {
                if (line.isBlank()) continue;
                if (!first) b.append(Component.newline());
                first = false;
                for (String px : line.trim().split(" ")) {
                    if (px.startsWith("-")) b.append(Component.text("█", TextColor.color(0x0A1828)));   // dark screen behind her
                    else b.append(Component.text("█", TextColor.fromHexString("#" + px)));
                }
            }
        } catch (Exception e) {
            return Component.text("◈ АМИ ◈", CYAN);
        }
        return b.build();
    }

    // ------------------------------------------------------------ conversation

    void interact(Player p) {
        Dialogue d = plugin.dialogue();
        if (d == null || d.talking(p)) return;
        d.start(p, SPEAKER, base.clone().add(0, 1.55, 0), this::greet);
    }

    /** The first words depend on who stands in front of her. */
    private void greet(Session s) {
        int tier = s.tier();
        if (station == Station.ORION) {
            if (tier >= 0) s.say(HELLO_ORION, () -> menu(s));
            else s.say("«Орион» рад любому пилоту… почти любому. Мои сенсоры видят вокруг тебя тёмный шлейф. Здесь хрупкие приборы — будь осторожен.", () -> menu(s));
            return;
        }
        switch (tier) {
            case 2 -> s.say("Герой Света на моей палубе! Пилоты только о тебе и говорят. Мои лекку дрожат от волнения — честное слово. Чем помочь?", () -> menu(s));
            case -1 -> s.say("Привет… Мои сенсоры фиксируют вокруг тебя тьму. Я всё равно помогу — я учёная, а не судья. Но не дави на меня.", () -> menu(s));
            case -2 -> s.say("Ты… Саманта внесла тебя в список опасных пилотов. Я голограмма — ударить меня не выйдет. Говори, что нужно.", () -> menu(s));
            default -> s.say(HELLO, () -> menu(s));
        }
    }

    private void menu(Session s) {
        List<Choice> c = new ArrayList<>();
        int tier = s.tier();
        c.add(new Choice(tier >= 1 ? "Для меня честь, Ами." : "Рад знакомству, Ами!", Kind.LIGHT, 2, this::kind));
        c.add(Choice.of("Где я?", Kind.NEUTRAL, x -> x.say(station == Station.MAIN ? WHERE : WHERE_ORION, () -> menu(x))));
        c.add(new Choice("Ты всего лишь программа.", Kind.DARK, -2, this::rude));
        c.add(Choice.of("Кто ты?", Kind.ASK, x -> x.say(WHO, () -> x.say(
                "А ещё я твилек. Видишь лекку — эти два «хвоста» на голове? Это не причёска: ими мы чувствуем и даже говорим без слов.", () -> menu(x)))));
        c.add(Choice.of("Что такое Свет и Тьма?", Kind.ASK, this::sides));
        if (tier >= 1) c.add(new Choice("[Свет] Как помочь экипажу?", Kind.LIGHT, 2,
                x -> x.say("Свет сильнее там, где ты защищаешь других. Помоги новичкам у порталов, а в городе оберегай жителей. Я отмечу твой поступок в журнале корабля.", () -> menu(x))));
        if (tier <= -1) c.add(new Choice("[Тьма] Что ты знаешь о моей силе?", Kind.DARK, -2,
                x -> x.say("Твои решения оставляют след в сети корабля. Я расскажу о порталах, но не помогу причинить вред экипажу. Даже сейчас ты можешь выбрать другой путь.", () -> menu(x))));
        if (station == Station.MAIN)
            c.add(Choice.of("На станцию «Орион»", Kind.ACTION, x -> x.say(TO_ORION, () -> { Player p = x.player; x.end(true); transferTo.accept(p); })));
        else
            c.add(Choice.of("В главный хаб", Kind.ACTION, x -> x.say(TO_MAIN, () -> { Player p = x.player; x.end(true); transferTo.accept(p); })));
        c.add(Choice.of("До встречи", Kind.LEAVE, x -> x.say(BYE, () -> x.end(true))));
        s.choose(c);
    }

    private void kind(Session s) {
        String text = switch (s.tier()) {
            case 2, 1 -> "Ох… спасибо! Пилоты Света всегда возвращаются. Возвращайся и ты — я буду ждать здесь, на палубе.";
            case -1, -2 -> "Приятно… и неожиданно. Может, в тебе больше Света, чем говорят мои сенсоры. Я в это верю.";
            default -> "Взаимно! Новые пилоты — моя любимая часть работы. Спрашивай, если что-то непонятно.";
        };
        s.say(text, () -> menu(s));
    }

    private void rude(Session s) {
        String text = s.tier() <= -1
                ? "Программа, которая видит твою ауру. И она темнеет с каждым словом. Тьма любит грубость — не корми её."
                : "Может быть. Но эта программа помнит имя каждого пилота, которому помогла. Твоё — тоже.";
        s.say(text, () -> menu(s));
    }

    private void sides(Session s) {
        int k = s.karma();
        String where = NeyroKarma.sideName(k) + " (" + NeyroKarma.signed(k) + ")";
        String hint = k <= NeyroKarma.DARK
                ? " Стража города следит за тобой, а торговцы поднимут цены. Охоться на монстров — так возвращаются к Свету."
                : k >= NeyroKarma.LIGHT
                ? " Город тебя любит: стража уважает, а у торговцев для героев скидка."
                : " Пока ты на распутье. Выбирай ответы и дела — мир запомнит.";
        s.say("В каждом пилоте есть Свет и Тьма. Убийства игроков, жителей и питомцев тянут к Тьме, охота на монстров — к Свету. Синие ответы в разговоре — Свет, красные — Тьма. Ты сейчас: "
                + where + "." + hint, () -> menu(s));
    }
}
