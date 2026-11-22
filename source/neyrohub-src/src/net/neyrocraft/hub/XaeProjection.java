package net.neyrocraft.hub;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import org.bukkit.Color;
import org.bukkit.GameMode;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.Particle;
import org.bukkit.World;
import org.bukkit.entity.BlockDisplay;
import org.bukkit.entity.Display;
import org.bukkit.entity.Player;
import org.bukkit.util.Transformation;
import org.joml.Quaternionf;
import org.joml.Vector3f;

/** Non-colliding, resource-pack-independent volumetric model and projection effects. */
final class XaeProjection {
    enum Pose { GREETING, TALKING, HAPPY, THINKING, GOODBYE }
    private enum Rig { PLATFORM, BODY, HEAD, EYES, MOUTH, LEFT_LEKKU, RIGHT_LEKKU, LEFT_ARM, RIGHT_ARM, COAT }

    private static final Material SUIT = Material.BLACK_CONCRETE;
    private static final Material TRIM = Material.POLISHED_BLACKSTONE;
    private static final Material COAT = Material.WHITE_CONCRETE;
    private static final Material SKIN = Material.BLUE_ICE;
    private static final Material SHADE = Material.LIGHT_BLUE_TERRACOTTA;
    private static final Material LEKKU = Material.PACKED_ICE;
    private static final Material NEON = Material.SEA_LANTERN;
    private static final Display.Brightness LIGHT = new Display.Brightness(15, 15);
    private static final Vector3f NECK = new Vector3f(0, 1.90f, 0);
    private static final Vector3f LEFT_ARM = new Vector3f(-.39f, 1.76f, 0);
    private static final Vector3f RIGHT_ARM = new Vector3f(.39f, 1.76f, 0);
    private static final Vector3f COAT_HEM = new Vector3f(0, 1.13f, -.13f);
    private static final Particle.DustOptions CYAN = new Particle.DustOptions(Color.fromRGB(52, 203, 255), .62f);
    private static final Particle.DustOptions LILAC = new Particle.DustOptions(Color.fromRGB(171, 114, 255), .62f);
    private static final Particle.DustTransition SCAN = new Particle.DustTransition(
            Color.fromRGB(111, 223, 255), Color.fromRGB(177, 145, 255), .46f);
    private final NeyroHub plugin;
    private final Location origin;
    private final World world;
    private final List<Part> parts = new ArrayList<>();

    private long clock;
    private long poseSince;
    private long poseUntil;
    private Player nearest;
    private Player audience;
    private Pose pose;
    private float yaw;
    private float pitch;
    private float gesture;
    private float lastBodyYaw = Float.NaN;

    private record Part(BlockDisplay display, Rig rig, Vector3f center, Vector3f size, Quaternionf rest) { }

    XaeProjection(NeyroHub plugin, Location base) {
        this.plugin = plugin;
        this.origin = new Location(base.getWorld(), base.getX(), base.getY(), base.getZ());
        this.world = base.getWorld();
    }

    void spawn() {
        remove();
        platform();
        anatomy();
        updateRig();
    }

    void remove() {
        for (Part part : parts) part.display().remove();
        parts.clear();
        audience = null;
        nearest = null;
        pose = null;
        poseUntil = 0;
        gesture = 0;
        lastBodyYaw = Float.NaN;
    }

    void react(Pose next, Player player, long durationTicks) {
        pose = next;
        poseSince = clock;
        poseUntil = clock + Math.max(3L, (durationTicks + 1L) / 2L);
        audience = player;
    }

    /** Called every two game ticks. Stop sending updates when nobody can see the projection. */
    void tick() {
        if (parts.isEmpty()) return;
        clock++;
        if (clock % 5 == 0 || clock == 1) {
            Player old = nearest;
            nearest = world.getNearbyPlayers(origin, 20, p -> !p.isDead() && p.getGameMode() != GameMode.SPECTATOR && plugin.authed(p))
                    .stream().filter(p -> p.getLocation().distanceSquared(origin) <= 20 * 20)
                    .min(Comparator.comparingDouble(p -> p.getLocation().distanceSquared(origin))).orElse(null);
            if (old == null && nearest != null && clock >= poseUntil) react(Pose.GREETING, nearest, 24);
        }
        if (nearest == null) return;

        Player target = clock < poseUntil && valid(audience) ? audience : nearest;
        Location eye = target.getEyeLocation();
        double x = eye.getX() - origin.getX();
        double z = eye.getZ() - origin.getZ();
        double horizontal = Math.hypot(x, z);
        if (horizontal > .7) {
            double wanted = Math.atan2(x, z);
            double delta = Math.atan2(Math.sin(wanted - yaw), Math.cos(wanted - yaw));
            yaw += (float) Math.clamp(delta * .23, -.24, .24);
            float lookPitch = (float) Math.clamp(-Math.atan2(eye.getY() - origin.getY() - 2.08, horizontal), -.24, .24);
            pitch += (lookPitch - pitch) * .20f;
        }
        if (clock % 3 == 0) updateRig();
        if (clock % 2 == 0) platformLights();
    }

    private boolean valid(Player player) {
        return player != null && player.isOnline() && !player.isDead() && player.getWorld().equals(world)
                && player.getLocation().distanceSquared(origin) <= 20 * 20;
    }

    private void platform() {
        // Tiled octagon, with adjacent panels and no coplanar overlaps.
        part(Rig.PLATFORM, TRIM, 0, .046f, 0, 1.82f, .09f, 1.29f);
        part(Rig.PLATFORM, TRIM, 0, .046f, -.77f, 1.29f, .09f, .25f);
        part(Rig.PLATFORM, TRIM, 0, .046f, .77f, 1.29f, .09f, .25f);
        part(Rig.PLATFORM, Material.CYAN_STAINED_GLASS, 0, .108f, 0, 1.34f, .035f, 1.05f);
        part(Rig.PLATFORM, NEON, 0, .134f, 0, .62f, .020f, .55f);
        for (float sign : new float[]{-1, 1}) {
            part(Rig.PLATFORM, Material.CYAN_CONCRETE, sign * .76f, .103f, 0, .075f, .025f, .82f);
            part(Rig.PLATFORM, Material.LIGHT_BLUE_CONCRETE, 0, .101f, sign * .71f, 1.03f, .023f, .04f);
        }
    }

    private void anatomy() {
        // Long clean panels frame the dark undersuit instead of forming a square apron.
        for (float side : new float[]{-1, 1}) {
            part(Rig.BODY, TRIM, side * .143f, .282f, .055f, .177f, .217f, .322f);
            part(Rig.BODY, Material.IRON_BLOCK, side * .143f, .443f, .022f, .183f, .327f, .217f);
            part(Rig.BODY, Material.BLUE_ICE, side * .143f, .455f, .143f, .092f, .206f, .024f);
            part(Rig.BODY, SUIT, side * .144f, .683f, -.01f, .193f, .367f, .216f);
            part(Rig.COAT, COAT, side * .138f, .661f, -.213f, .241f, .743f, .079f, -.15f, 0, side * .21f);
            part(Rig.COAT, Material.QUARTZ_BLOCK, side * .269f, .696f, -.119f, .144f, .649f, .113f, -.06f, side * .35f, side * .18f);
            part(Rig.COAT, Material.BLUE_ICE, side * .31f, .596f, -.015f, .036f, .489f, .035f, -.05f, 0, side * .17f);
        }
        part(Rig.BODY, SUIT, .018f, .994f, 0, .416f, .302f, .261f, 0, 0, -.045f);
        part(Rig.BODY, SUIT, 0, 1.230f, .016f, .367f, .285f, .262f);
        part(Rig.BODY, SUIT, -.011f, 1.524f, .017f, .436f, .344f, .294f, 0, 0, -.032f);
        part(Rig.BODY, COAT, 0, 1.415f, -.163f, .492f, .632f, .123f);
        part(Rig.BODY, Material.BLUE_CONCRETE, 0, 1.113f, .030f, .442f, .071f, .296f);
        part(Rig.BODY, NEON, 0, 1.116f, .189f, .069f, .056f, .025f);
        part(Rig.BODY, SKIN, 0, 1.808f, 0, .227f, .268f, .225f);
        part(Rig.BODY, TRIM, 0, 1.766f, .006f, .276f, .099f, .256f);
        part(Rig.BODY, NEON, 0, 1.77f, .146f, .062f, .059f, .024f);
        for (float side : new float[]{-1, 1}) {
            Rig arm = side < 0 ? Rig.LEFT_ARM : Rig.RIGHT_ARM;
            part(Rig.BODY, SUIT, side * .144f, 1.556f, .103f, .202f, .251f, .180f, -.16f, side * -.30f, -.19f * side);
            part(Rig.BODY, Material.LIGHT_BLUE_CONCRETE, side * .095f, 1.357f, .181f, .018f, .195f, .016f, -.26f, 0, .47f * side);
            part(Rig.BODY, COAT, side * .229f, 1.426f, .091f, .157f, .548f, .195f, 0, side * -.29f, -.13f * side);
            part(Rig.BODY, Material.QUARTZ_BLOCK, side * .172f, 1.589f, .212f, .109f, .285f, .049f, 0, side * -.12f, -.48f * side);
            part(Rig.BODY, Material.BLUE_ICE, side * .276f, 1.384f, .198f, .026f, .256f, .017f, .07f, 0, -.16f * side);
            part(Rig.BODY, Material.CYAN_CONCRETE, side * .047f, 1.235f, .156f, .020f, .094f, .016f, 0, 0, .38f * side);
            part(arm, SKIN, side * .350f, 1.697f, -.008f, .184f, .222f, .191f, 0, 0, .24f * side);
            part(arm, TRIM, side * .372f, 1.622f, .007f, .195f, .052f, .200f, 0, 0, .24f * side);
            part(arm, COAT, side * .397f, 1.483f, -.010f, .187f, .236f, .201f, 0, 0, .11f * side);
            part(arm, Material.QUARTZ_BLOCK, side * .388f, 1.576f, .011f, .204f, .079f, .229f, 0, 0, .16f * side);
            part(arm, COAT, side * .432f, 1.293f, .014f, .167f, .275f, .184f, -.09f, 0, side * .10f);
            part(arm, Material.QUARTZ_BLOCK, side * .447f, 1.172f, .023f, .167f, .069f, .188f);
            part(arm, Material.BLUE_CONCRETE, side * .45f, 1.079f, .049f, .134f, .142f, .146f);
            part(arm, NEON, side * .444f, 1.184f, .125f, .073f, .021f, .026f);
        }
        part(Rig.BODY, TRIM, -.216f, 1.476f, .224f, .116f, .153f, .033f, 0, -.11f, -.07f);
        part(Rig.BODY, Material.QUARTZ_BLOCK, -.216f, 1.467f, .246f, .087f, .117f, .015f, 0, -.11f, -.07f);
        part(Rig.BODY, Material.CYAN_CONCRETE, -.218f, 1.483f, .258f, .054f, .043f, .01f, 0, -.11f, -.07f);
        part(Rig.BODY, Material.AMETHYST_BLOCK, .244f, 1.290f, .186f, .032f, .138f, .042f, 0, 0, -.1f);
        part(Rig.BODY, Material.BLUE_ICE, .290f, 1.282f, .185f, .022f, .114f, .039f, 0, 0, -.1f);

        // Thin chamfers around the cheekbones and three widths on the cap soften the outline.
        part(Rig.HEAD, SHADE, 0, 2.061f, -.052f, .436f, .344f, .373f);
        part(Rig.HEAD, SKIN, 0, 2.083f, .073f, .391f, .263f, .333f);
        part(Rig.HEAD, SKIN, 0, 1.978f, .104f, .405f, .135f, .293f);
        part(Rig.HEAD, SKIN, 0, 1.907f, .123f, .295f, .059f, .245f);
        part(Rig.HEAD, Material.QUARTZ_BLOCK, 0, 2.296f, -.047f, .559f, .118f, .428f, -.13f, 0, 0);
        part(Rig.HEAD, COAT, 0, 2.354f, -.063f, .459f, .078f, .347f, -.13f, 0, 0);
        part(Rig.HEAD, Material.QUARTZ_BLOCK, 0, 2.394f, -.068f, .330f, .032f, .277f, -.13f, 0, 0);
        part(Rig.HEAD, Material.BLUE_CONCRETE, 0, 2.240f, .011f, .621f, .051f, .466f, -.12f, 0, 0);
        part(Rig.HEAD, TRIM, 0, 2.232f, .240f, .505f, .036f, .137f, -.23f, 0, 0);
        part(Rig.HEAD, Material.BLUE_ICE, 0, 2.224f, .304f, .421f, .019f, .035f, -.22f, 0, 0);
        part(Rig.HEAD, NEON, 0, 2.288f, .181f, .074f, .044f, .035f, -.13f, 0, 0);
        for (float side : new float[]{-1, 1}) {
            Rig lekku = side < 0 ? Rig.LEFT_LEKKU : Rig.RIGHT_LEKKU;
            part(Rig.HEAD, SKIN, side * .189f, 2.071f, .108f, .081f, .247f, .235f, 0, side * .28f, -.14f * side);
            part(Rig.HEAD, Material.PURPUR_BLOCK, side * .174f, 2.005f, .247f, .063f, .035f, .008f, 0, side * -.24f, .16f * side);
            part(Rig.HEAD, Material.PURPLE_CONCRETE, side * .111f, 2.139f, .243f, .114f, .013f, .014f, 0, 0, -.07f * side);
            part(Rig.EYES, Material.BLUE_CONCRETE, side * .112f, 2.079f, .248f, .143f, .074f, .026f, 0, 0, .20f * side);
            part(Rig.EYES, Material.QUARTZ_BLOCK, side * .108f, 2.077f, .267f, .119f, .042f, .022f, 0, 0, .18f * side);
            part(Rig.EYES, Material.PURPLE_CONCRETE, side * .105f, 2.077f, .280f, .065f, .045f, .015f);
            part(Rig.EYES, Material.MAGENTA_CONCRETE, side * .111f, 2.068f, .287f, .043f, .024f, .009f);
            part(Rig.EYES, TRIM, side * .105f, 2.078f, .292f, .022f, .027f, .007f);
            part(Rig.EYES, NEON, side * .093f, 2.089f, .296f, .016f, .016f, .006f);
            part(lekku, LEKKU, side * .258f, 2.123f, .040f, .180f, .264f, .185f, -.09f, 0, .22f * side);
            part(lekku, LEKKU, side * .318f, 1.905f, .074f, .174f, .270f, .194f, -.21f, 0, .29f * side);
            part(lekku, Material.BLUE_ICE, side * .377f, 1.679f, .141f, .145f, .259f, .178f, -.33f, 0, .22f * side);
            part(lekku, LEKKU, side * .391f, 1.459f, .221f, .114f, .234f, .149f, -.31f, 0, -.12f * side);
            part(lekku, Material.BLUE_ICE, side * .360f, 1.290f, .277f, .075f, .158f, .109f, -.31f, 0, -.32f * side);
            part(lekku, Material.LIGHT_BLUE_STAINED_GLASS, side * .408f, 1.688f, .230f, .034f, .230f, .055f, -.33f, 0, .22f * side);
            part(lekku, Material.BLUE_CONCRETE, side * .312f, 1.940f, .178f, .153f, .045f, .039f, -.21f, 0, .29f * side);
            part(lekku, NEON, side * .351f, 1.901f, .188f, .035f, .039f, .025f, -.21f, 0, .29f * side);
            part(Rig.MOUTH, Material.PURPLE_CONCRETE, side * .054f, 1.963f, .254f, .036f, .016f, .016f, 0, side * .16f, side * .45f);
        }
        part(Rig.HEAD, SKIN, 0, 2.022f, .255f, .055f, .049f, .035f);
        part(Rig.MOUTH, Material.PURPLE_CONCRETE, 0, 1.955f, .257f, .100f, .022f, .020f);
        part(Rig.MOUTH, Material.MAGENTA_CONCRETE, 0, 1.949f, .267f, .061f, .010f, .008f);
    }

    private void part(Rig rig, Material material, float x, float y, float z, float width, float height, float depth) {
        part(rig, material, x, y, z, width, height, depth, 0, 0, 0);
    }

    private void part(Rig rig, Material material, float x, float y, float z, float width, float height, float depth,
                      float pitch, float yaw, float roll) {
        var center = new Vector3f(x, y, z);
        var size = new Vector3f(width, height, depth);
        var rest = new Quaternionf().rotationXYZ(pitch, yaw, roll);
        BlockDisplay display = world.spawn(origin, BlockDisplay.class, d -> {
            plugin.prepare(d);
            d.setBlock(material.createBlockData());
            d.setBrightness(LIGHT);
            d.setViewRange(.6f);
            d.setDisplayWidth(2.6f);
            d.setDisplayHeight(2.6f);
            d.setInterpolationDuration(6);
        });
        parts.add(new Part(display, rig, center, size, rest));
    }

    private void updateRig() {
        boolean reacting = pose != null && clock < poseUntil;
        float wanted = reacting ? 1 : 0;
        gesture += (wanted - gesture) * .35f;
        Pose motion = pose == null ? Pose.TALKING : pose;
        float cycle = (clock - poseSince) * .19f;
        float tilt = motion == Pose.GREETING || motion == Pose.HAPPY ? .22f : motion == Pose.THINKING ? -.18f : .04f;
        float bow = motion == Pose.GOODBYE ? .18f * (float) Math.sin(cycle * .42f) * gesture : 0;
        float bob = (float) Math.sin(clock * .12) * .022f;
        float roll = .075f + (float) Math.sin(clock * .085) * .025f;
        float close = clock % 61 >= 57 ? .24f : 1;
        float speech = reacting && (motion == Pose.TALKING || motion == Pose.GREETING || motion == Pose.HAPPY)
                ? 1 + 1.12f * Math.abs((float) Math.sin(cycle * 2.43)) : 1;
        float armPitch = switch (motion) {
            case GREETING -> -1.17f;
            case TALKING -> -.49f - .28f * (float) Math.sin(cycle);
            case HAPPY -> -.81f;
            case THINKING -> -1.28f;
            case GOODBYE -> -1.37f + .19f * (float) Math.sin(cycle * 2.6);
        };
        float armRoll = switch (motion) {
            case GREETING, THINKING -> -.44f;
            case GOODBYE -> .44f + .18f * (float) Math.sin(cycle * 2.6);
            default -> .09f;
        };
        Quaternionf facing = new Quaternionf().rotationY(yaw);
        Quaternionf head = new Quaternionf().rotationXYZ(pitch + bow, 0, roll + tilt * gesture);
        boolean firstUpdate = Float.isNaN(lastBodyYaw);
        boolean turnBody = firstUpdate || Math.abs(yaw - lastBodyYaw) > .018f;
        for (Part part : parts) {
            if (part.rig() == Rig.PLATFORM && !firstUpdate || part.rig() == Rig.BODY && !turnBody) continue;
            Quaternionf articulated = new Quaternionf();
            Vector3f pivot = new Vector3f();
            Vector3f size = new Vector3f(part.size());
            Vector3f point = new Vector3f(part.center());
            Quaternionf root = facing;
            switch (part.rig()) {
                case PLATFORM -> root = new Quaternionf();
                case HEAD, LEFT_LEKKU, RIGHT_LEKKU, EYES, MOUTH -> {
                    articulated.set(head);
                    pivot.set(NECK);
                    if (part.rig() == Rig.EYES) {
                        // Collapse each layered eye around the same vertical center, including the highlights.
                        point.y = 2.079f + (point.y - 2.079f) * close;
                        size.y *= close;
                    } else if (part.rig() == Rig.MOUTH) size.y *= speech;
                    else if (part.rig() == Rig.LEFT_LEKKU || part.rig() == Rig.RIGHT_LEKKU)
                        articulated.rotateX(roll * 1.9f);
                }
                case LEFT_ARM -> {
                    pivot.set(LEFT_ARM);
                    articulated.rotationXYZ(roll * 1.8f - gesture * .16f, 0, -.045f - roll);
                }
                case RIGHT_ARM -> {
                    pivot.set(RIGHT_ARM);
                    articulated.rotationXYZ(roll * 1.8f + armPitch * gesture, roll, .05f + armRoll * gesture);
                }
                case COAT -> {
                    pivot.set(COAT_HEM);
                    articulated.rotationXYZ(-roll * 2, 0, roll);
                }
                case BODY -> { }
            }
            articulated.transform(point.sub(pivot)).add(pivot);
            if (part.rig() != Rig.PLATFORM && part.rig() != Rig.BODY) point.y += bob;
            root.transform(point);
            Quaternionf rotation = new Quaternionf(root).mul(articulated).mul(part.rest());
            point.add(rotation.transform(new Vector3f(size).mul(-.5f)));
            part.display().setInterpolationDelay(0);
            part.display().setTransformation(new Transformation(point, rotation, size, new Quaternionf()));
        }
        if (turnBody) lastBodyYaw = yaw;
    }

    private void platformLights() {
        double spin = clock * .125;
        for (int i = 0; i < 6; i++) {
            double a = i * Math.PI / 3 + spin;
            point(a, 1.02, .15, CYAN);
            point(-a, .69, .18 + (i % 2) * .025, LILAC);
        }
        if (clock % 6 == 0) {
            double phase = ((clock / 6) % 9) / 8.0;
            point(spin, .57, .20 + 2.07 * phase, CYAN);
            point(spin + Math.PI, .57, .20 + 2.07 * phase, CYAN);
            world.spawnParticle(Particle.ELECTRIC_SPARK, origin.clone().add(0, .29, 0), 2, .39, .09, .33, .012);
        }
        if (clock % 4 == 0) {
            double phase = (clock % 68) / 68.0;
            double width = phase < .44 ? .32 : .47;
            for (int i = -2; i <= 2; i++) {
                double offset = i * width / 2;
                world.spawnParticle(Particle.DUST_COLOR_TRANSITION,
                        origin.getX() + Math.cos(yaw) * offset + Math.sin(yaw) * .35,
                        origin.getY() + .18 + 2.16 * phase,
                        origin.getZ() - Math.sin(yaw) * offset + Math.cos(yaw) * .35,
                        1, 0, 0, 0, 0, SCAN);
            }
        }
    }

    private void point(double theta, double radius, double y, Particle.DustOptions color) {
        world.spawnParticle(Particle.DUST, origin.getX() + Math.cos(theta) * radius,
                origin.getY() + y, origin.getZ() + Math.sin(theta) * radius, 1, 0, 0, 0, 0, color);
    }
}
