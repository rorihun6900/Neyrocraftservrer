package net.neyrocraft.hub;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import net.kyori.adventure.text.format.TextColor;
import net.kyori.adventure.text.format.TextDecoration;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;
import org.bukkit.persistence.PersistentDataType;

import java.util.ArrayList;
import java.util.List;

import net.neyrocraft.hub.Dialogue.Choice;
import net.neyrocraft.hub.Dialogue.Kind;
import net.neyrocraft.hub.Dialogue.Session;
import net.neyrocraft.hub.Dialogue.Speaker;

/**
 * The people of Neyro-City from all over the galaxy: a Twi'lek who meets everyone coming down the
 * beam, a Togruta medic, a Zabrak veteran, a Rodian bounty hunter, a Chiss scout and a Wookiee
 * mechanic. Each one talks on the Mass Effect wheel and reacts to the player's Light / Dark side.
 *
 * Skins and head pieces (lekku, montrals, horns, snout) are in the NeyroCraft pack, drawn by
 * source\voicepack-src\races.py. Players without the pack see a plain look-alike.
 */
final class CityFolk {

    /** A people: pack skin (neyrocraft:entity/race/...), slim arms, head piece model (or null), size. */
    record Race(String name, String skin, boolean slim, String head, double scale, String plainSkin, int outfit) { }

    static final Race TWILEK = new Race("твилек", "twilek", true, "race_lekku", 1.0, "noor", 0x101018);
    static final Race TOGRUTA = new Race("тогрута", "togruta", true, "race_montrals", 1.0, "zuri", 0x2E5A3A);
    static final Race ZABRAK = new Race("забрак", "zabrak", false, "race_horns", 1.0, "ari", 0x3A2A1E);
    static final Race RODIAN = new Race("родианец", "rodian", false, "race_rodian", 0.95, "steve", 0x5A4630);
    static final Race CHISS = new Race("чисс", "chiss", true, null, 1.0, "makena", 0x1E2A44);
    static final Race WOOKIEE = new Race("вуки", "wookiee", false, null, 1.22, "steve", 0x5A3A22);

    /** One inhabitant: where they stand (plaza-relative) and how they talk. */
    record Npc(String id, String name, Race race, String job, double x, double z, int color) {
        Speaker speaker() { return new Speaker(name.toUpperCase(), race.name + " · " + job, color, windowFor(color)); }
    }

    static final Npc NIRA = new Npc("nira", "Нира", TWILEK, "встречает гостей", -2.5, 3.5, 0x6FD6FF);
    static final List<Npc> ALL = List.of(
            NIRA,
            new Npc("aela", "Аэла Рин", TOGRUTA, "медик", 12.5, -2.5, 0xFF9A5A),
            new Npc("kor", "Кор Ваддо", ZABRAK, "ветеран", -11.5, 3.5, 0xE05040),
            new Npc("vudo", "Вудо Кеш", RODIAN, "охотник за головами", 2.5, 14.5, 0x7CD860),
            new Npc("valkar", "Вэл'кар", CHISS, "разведчица", -1.5, -14.5, 0xFF4A4A),
            new Npc("grrak", "Гррак", WOOKIEE, "механик спидеров", 8.5, 6.5, 0xC89060));

    private static int windowFor(int c) {
        int r = ((c >> 16) & 255) / 6, g = ((c >> 8) & 255) / 6, b = (c & 255) / 6;
        return (0xD8 << 24) | ((10 + r) << 16) | ((20 + g) << 8) | (40 + b);
    }

    private final NeyroHub plugin;
    private final NamespacedKey metNira, giftNira, robbedNira;

    CityFolk(NeyroHub plugin) {
        this.plugin = plugin;
        metNira = new NamespacedKey(plugin, "met_nira");
        giftNira = new NamespacedKey(plugin, "gift_nira");
        robbedNira = new NamespacedKey(plugin, "robbed_nira");
    }

    boolean metNira(Player p) {
        return p.getPersistentDataContainer().has(metNira, PersistentDataType.BYTE);
    }

    void markMet(Player p) {
        p.getPersistentDataContainer().set(metNira, PersistentDataType.BYTE, (byte) 1);
    }

    private boolean flag(Player p, NamespacedKey k) {
        return p.getPersistentDataContainer().has(k, PersistentDataType.BYTE);
    }

    private void setFlag(Player p, NamespacedKey k) {
        p.getPersistentDataContainer().set(k, PersistentDataType.BYTE, (byte) 1);
    }

    /** A short line in chat when an old friend comes down the beam again. */
    static String niraHello(int tier) {
        return switch (tier) {
            case 2 -> "С возвращением, герой! Город спит спокойно, когда ты рядом.";
            case 1 -> "Рада тебя видеть! Как полёт?";
            case -1 -> "Опять ты… Веди себя прилично, стража на взводе.";
            case -2 -> "Стража, внимание… Это всего лишь я, Нира. Проходи. Быстро.";
            default -> "С возвращением в Neyro-City!";
        };
    }

    static Component chat(Npc n, String text) {
        return Component.text()
                .append(Component.text("◈ " + n.name() + ": ", TextColor.color(n.color())).decorate(TextDecoration.BOLD))
                .append(Component.text(text, TextColor.color(0xFFE6C8))).build();
    }

    /** The conversation of this inhabitant. */
    void talk(Npc n, Session s) {
        switch (n.id()) {
            case "nira" -> niraGreet(s);
            case "aela" -> generic(s, n,
                    new String[]{"Ты ранен? Нет? Тогда… от тебя веет холодом. Тьма тоже оставляет шрамы, просто их не видно.",
                            "Привет! Я Аэла, медик. Если монстры за стеной потреплют — приходи.",
                            "Твоя аура такая светлая, что мои монтралы звенят. Чем помочь, защитник?"},
                    "Что у тебя на голове?", "Монтралы. Мы, тогруты, слышим ими пространство — как эхо. Я знаю, что ты у меня за спиной, даже не оборачиваясь.",
                    "Спасибо, что лечишь людей.", "Это мой путь. Береги себя — и других. Свет растёт, когда им делятся.",
                    "Лечи лучше, а то пожалеешь.", "Угрозы не лечатся… но я попробую. Иди с миром, пока он у тебя есть.",
                    "Я лечу всех. Даже тех, кто угрожает. Но не испытывай меня.");
            case "kor" -> generic(s, n,
                    new String[]{"Знакомый взгляд. Я сам ходил по Тьме двадцать лет. Ничего хорошего там нет — поверь ветерану.",
                            "Кор Ваддо, забрак. Служил, воевал, теперь сторожу эту площадь от скуки.",
                            "Уважаю. Свет даётся труднее, чем кажется. Ты сильнее, чем выглядишь."},
                    "Откуда татуировки?", "Каждая линия — история. Эта — первый бой. Эта — первый друг, которого я не спас. Забраки носят память на лице.",
                    "Твоя служба окончена, отдохни.", "Ха. Спасибо, пилот. Редко кто так говорит со старым рогатым солдатом.",
                    "Старик, твоё время прошло.", "Может быть. Но рога у меня ещё крепкие. Проверим? …Шучу. Иди.",
                    "Тебе нравится сила, да? Сила без чести — просто шум. Я это проходил.");
            case "vudo" -> vudo(s, n);
            case "valkar" -> generic(s, n,
                    new String[]{"Цель опознана: тёмная сторона, уровень угрозы — повышенный. Не беспокойся, я просто наблюдаю. Пока.",
                            "Вэл'кар. Разведка. Не смотри так на мои глаза — у чиссов они всегда светятся.",
                            "Твоё досье впечатляет. Свет — тоже тактика, и ты в ней хорош."},
                    "Что ты разведываешь?", "Земли за стеной. Там руины, пещеры и много того, что лучше не будить. Карту не дам — но совет: не ходи ночью без света.",
                    "Хочешь, помогу с разведкой?", "Неожиданно. Принимаю. Убивай монстров у стен — каждый минус один для моей карты угроз.",
                    "Отстань, синяя.", "Записала. Пункт: грубость. Пункт: недальновидность. Хорошего дня.",
                    "Твоя Тьма — это нехватка дисциплины. Я бы взяла тебя в обучение. Но не сегодня.");
            case "grrak" -> generic(s, n,
                    new String[]{"Ррррр-УХ! [перевод: «Пахнешь бедой. Мой ключ на 12 — тяжёлый. Просто говорю.»]",
                            "Ааррргх-ух! [перевод: «Гррак. Чиню спидеры. Ты — новенький?»]",
                            "Уаааррргх! [перевод: «Друг! Гррак обнимет! …Осторожно, рёбра.»]"},
                    "Что ты сейчас чинишь?", "Ррр-ух, грах. [перевод: «Спидер для гонок на Терраксе. /race — там проверишь.»]",
                    "Можно помочь с ремонтом?", "УААРГХ! [перевод: «Держи вот тут. Нет, не тут. Да. Хороший друг!»]",
                    "Шерсть у тебя как ковёр.", "РРРРАААХ!! [перевод: «Вуки отрывают руки за такие слова. Обычно. Сегодня Гррак добрый.»]",
                    "Грр… [перевод: «Тёмные шутят про шерсть. Гррак запомнит.»]");
            default -> s.sayAndEnd("…");
        }
    }

    /**
     * A conversation from parts: greeting for dark / neutral / light players, a question, a Light answer
     * and a Dark answer (the reply to the Dark answer differs when the player is already on the Dark side).
     */
    private void generic(Session s, Npc n, String[] greet, String askQ, String askA, String lightQ, String lightA,
                         String darkQ, String darkA, String darkA_toDark) {
        int t = s.tier();
        s.say(greet[t < 0 ? 0 : t == 0 ? 1 : 2], () -> genericMenu(s, n, askQ, askA, lightQ, lightA, darkQ, darkA, darkA_toDark));
    }

    private void genericMenu(Session s, Npc n, String askQ, String askA, String lightQ, String lightA,
                             String darkQ, String darkA, String darkA_toDark) {
        Runnable back = () -> genericMenu(s, n, askQ, askA, lightQ, lightA, darkQ, darkA, darkA_toDark);
        s.choose(
                new Choice(lightQ, Kind.LIGHT, 2, x -> x.say(lightA, back)),
                Choice.of("Как жизнь в городе?", Kind.NEUTRAL, x -> x.say(cityLife(n, x.tier()), back)),
                new Choice(darkQ, Kind.DARK, -2, x -> x.say(x.tier() < 0 ? darkA_toDark : darkA, back)),
                Choice.of(askQ, Kind.ASK, x -> x.say(askA, back)),
                Choice.of("Мне пора.", Kind.LEAVE, x -> x.sayAndEnd(bye(x.tier()))));
    }

    private static String cityLife(Npc n, int tier) {
        if (tier <= -1) return "Было спокойно. Пока не пришли такие, как ты. Стража удвоила патрули — догадайся, почему.";
        if (tier >= 1) return "Хорошо! Когда герои рядом, даже монстры у стен ведут себя тише. Торговцы делают скидки своим.";
        return "Как обычно: неон, рамен и ломбард 24/7. Стражи снимают монстров со стен, а мы торгуем.";
    }

    private static String bye(int tier) {
        return tier >= 1 ? "Да хранит тебя Свет." : tier <= -1 ? "Иди. И не оглядывайся." : "Удачи, пилот.";
    }

    // ------------------------------------------------------------ Nira, the Twi'lek at the beam

    private void niraGreet(Session s) {
        Player p = s.player;
        boolean first = !metNira(p);
        markMet(p);
        String text = switch (s.tier()) {
            case 2 -> "С возвращением, герой! Весь город говорит о тебе. Я Нира — встречаю каждого, кто спускается по лучу. Для тебя — с особой радостью.";
            case 1 -> "Добро пожаловать в Neyro-City, пилот! Я Нира, встречаю всех, кто спускается с корабля. От тебя так и веет Светом.";
            case -1 -> "Хм. От тебя пахнет неприятностями… Я Нира. Встречаю гостей у луча — даже таких, как ты.";
            case -2 -> "Ты?! Стража предупреждала о тебе. Я Нира, и я не боюсь… почти. Говори, что нужно, — и проходи.";
            default -> first
                    ? "Добро пожаловать в Neyro-City! Я Нира, твилек, — встречаю каждого, кто спускается с корабля по лучу. Ты здесь впервые?"
                    : "С возвращением! Это снова я, Нира. Чем помочь?";
        };
        s.say(text, () -> niraMenu(s));
    }

    private void niraMenu(Session s) {
        Player p = s.player;
        int tier = s.tier();
        List<Choice> c = new ArrayList<>();
        c.add(new Choice("Рад встрече, Нира. Чем помочь городу?", Kind.LIGHT, 2, x -> x.say(
                "Как приятно слышать! Охоться на монстров у стен — стражам помощь, а тебе Свет. И не трогай жителей: город всё помнит.", () -> niraMenu(x))));
        c.add(Choice.of("Расскажи о городе.", Kind.NEUTRAL, x -> x.say(
                "Магазины — вокруг площади, платим изумрудами. Нет изумрудов — «Ломбард 24/7» купит добычу. Луч за мной — лифт обратно на корабль. А /city вернёт сюда из любой точки.",
                () -> niraMenu(x))));
        c.add(new Choice("Не твоё дело. С дороги.", Kind.DARK, -2, x -> x.say(x.tier() <= -1
                ? "Как скажешь. Стража уже смотрит на тебя — я бы не делала резких движений."
                : "Ой… Ладно. Я просто хотела помочь. Лекку от обиды холодеют, знаешь ли.", () -> niraMenu(x))));
        c.add(Choice.of("Ты твилек?", Kind.ASK, x -> x.say(
                "Да! Видишь лекку — два «хвоста» на голове? Ими мы чувствуем и даже говорим без слов. А повязка — чтобы их не обжигало неоном. Мода Neyro-City!",
                () -> niraMenu(x))));
        c.add(Choice.of("Кто ещё живёт в городе?", Kind.ASK, x -> x.say(
                "О, кого тут только нет! Аэла — тогрута и медик, Кор — старый забрак-ветеран, Вудо — родианец, охотник за головами, "
                        + "Вэл'кар — чисс из разведки, а Гррак — вуки, чинит спидеры. Поболтай с ними, они на площади.", () -> niraMenu(x))));
        // Mass Effect style special answers: only for those far enough along their path
        if (tier >= 1 && !flag(p, giftNira))
            c.add(new Choice("[Свет] Ты встревожена. Что случилось?", Kind.LIGHT, 3, this::niraTrust));
        if (tier <= -1 && !flag(p, robbedNira))
            c.add(new Choice("[Тьма] Гони изумруды, твилек.", Kind.DARK, -5, this::niraRobbed));
        c.add(Choice.of("Мне пора.", Kind.LEAVE, x -> x.sayAndEnd(bye(x.tier()))));
        s.choose(c);
    }

    private void niraTrust(Session s) {
        Player p = s.player;
        setFlag(p, giftNira);
        s.say("Ты заметил? Ночами у ворот бродят тени, и стражи их не видят… Возьми — это от меня. Будь осторожен за стеной, ладно?", () -> {
            give(p, new ItemStack(Material.EMERALD, 3));
            p.sendMessage(Component.text("  + 3 изумруда от Ниры", TextColor.color(0x7CFF4F)));
            niraMenu(s);
        });
    }

    private void niraRobbed(Session s) {
        Player p = s.player;
        setFlag(p, robbedNira);
        s.say("Л-ладно! Вот… всё, что есть. Но знай: лекку у твилеков — память. Я тебя запомнила. И стража тоже.", () -> {
            give(p, new ItemStack(Material.EMERALD, 1));
            p.sendMessage(Component.text("  + 1 изумруд (отнят у Ниры)", TextColor.color(0xFF7070)));
            s.end(true);
        });
    }

    private static void give(Player p, ItemStack it) {
        for (ItemStack left : p.getInventory().addItem(it).values()) p.getWorld().dropItemNaturally(p.getLocation(), left);
    }

    // ------------------------------------------------------------ Vudo, the Rodian bounty hunter

    private void vudo(Session s, Npc n) {
        Player p = s.player;
        int bounties = plugin.karma() == null ? 0 : plugin.karma().bounties(p);
        int t = s.tier();
        String greet = t <= -2
                ? "Ооо… Кеш видит награду над твоей головой. Большую. Не здесь — в городе закон. Но за стеной… Кеш умеет ждать."
                : t == -1
                ? "Ещё немного — и за тебя дадут хорошие деньги. Кеш уже присматривается. Ничего личного — работа."
                : t >= 1
                ? "Герой? Скучно. За героев не платят. Но Кеш уважает — хорошая репутация тоже валюта."
                : "Вудо Кеш, родианец. Охотник за головами. Не бойся — твоя голова пока ничего не стоит.";
        s.say(greet, () -> vudoMenu(s, n, bounties));
    }

    private void vudoMenu(Session s, Npc n, int bounties) {
        Runnable back = () -> vudoMenu(s, n, bounties);
        s.choose(
                new Choice("Помоги ловить злодеев.", Kind.LIGHT, 2, x -> x.say(
                        "Хм! Охота на злодеев — честная работа. Найдёшь игрока с красной аурой — за его голову город платит. Кеш поделится советом: бей первым.", back)),
                Choice.of("Как работают награды?", Kind.NEUTRAL, x -> x.say(
                        "Кто убивает игроков, жителей и питомцев — падает во Тьму. Злодей тёмной стороны получает награду за голову. "
                                + (bounties > 0 ? "Кстати, у тебя на счету уже " + bounties + " голов(ы). Кеш впечатлён." : "У тебя пока ни одной. Скучно."), back)),
                new Choice("Возьми меня в напарники. Кого убить?", Kind.DARK, -3, x -> x.say(x.tier() <= -1
                        ? "Хе-хе. Родственная душа. Но Кеш работает один — напарники делят награду. И иногда сдают друг друга."
                        : "Ты? Тьма тебе не к лицу, пилот. Кеш видел таких — долго не живут.", back)),
                Choice.of("Почему у тебя такие глаза?", Kind.ASK, x -> x.say(
                        "Родианцы видят в темноте и в инфракрасном. Кеш видит, как бьётся твоё сердце. Сейчас — чуть быстрее. Нервничаешь?", back)),
                Choice.of("Мне пора.", Kind.LEAVE, x -> x.sayAndEnd(x.tier() <= -1 ? "Увидимся за стеной… хе-хе." : "Кеш всегда здесь. Почти всегда.")));
    }

    /** The head piece of a race (pack players), worn in the helmet slot. */
    static ItemStack headPiece(Race r) {
        if (r.head() == null) return null;
        ItemStack it = new ItemStack(Material.PAPER);
        it.editMeta(m -> {
            m.setItemModel(new NamespacedKey("neyrocraft", r.head()));
            m.displayName(Component.text(r.name(), NamedTextColor.GRAY));
        });
        return it;
    }
}
