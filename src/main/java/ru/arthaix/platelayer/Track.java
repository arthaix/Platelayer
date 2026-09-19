package ru.arthaix.platelayer;

import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.function.Consumer;

import net.minecraft.entity.player.EntityPlayerMP;
import net.minecraft.item.ItemStack;
import net.minecraft.util.ResourceLocation;
import net.minecraft.util.math.BlockPos;
import net.minecraft.world.World;

/**
 * Immersive Railroading, reached only by reflection - Platelayer neither compiles against it nor breaks where it is
 * missing.
 *
 * <p>What it takes to build a piece from code, learned by reading a piece a player had laid by hand: both ends of a
 * piece are written <em>relative to the block it is built from</em>, and the placement constructor that takes an item
 * is the one for a player clicking a block - it rounds the heading to the blueprint's segmentation and snaps the
 * position by its position mode, which throws any drawn shape away. The one used here keeps exactly what it is given.
 * And {@code build(player, pos)} reports only that a list came back non-null, which it does after a refusal as well,
 * so the builder is asked whether it can build before it is told to.
 *
 * <p>A piece is one anchor block and a crowd of gag blocks around it, and a gag always gives way to a builder that
 * says it may ({@code overrideFlexible}); only the anchor is held. That is how two routes come to share ground at a
 * turnout, and it is what {@code over} asks for. Without it a crossover cannot be laid at all: the tracks it joins
 * are five blocks apart and a track reserves four, so every part of the connection falls inside one or the other.
 */
public final class Track {

    private static boolean looked;
    private static Constructor<?> umcStack, umcPlayer, umcVec3d, umcVec3i, placement, railInfo;
    private static Method settingsFrom, withSettings, build, getBuilder, canBuild, worldGet;
    private static Field overrideFlexible;
    private static Object trackCustom, directionNone;
    private static Field mType, mLength, mPreview;
    /** The first few refusals are explained in the log; after that they are only counted. */
    private static int complained;

    private Track() {}

    public static boolean available() {
        return lookup();
    }

    /** True when the item is an Immersive Railroading track blueprint. */
    public static boolean isBlueprint(ItemStack stack) {
        if (stack.isEmpty()) return false;
        ResourceLocation name = stack.getItem().getRegistryName();
        return name != null && "immersiverailroading".equals(name.getNamespace()) && name.getPath().contains("rail");
    }

    /** The gauge, bed and style the track will be built from, for the player to see before it starts. */
    public static String describe(ItemStack blueprint) {
        if (!lookup()) return "Immersive Railroading is not here";
        try {
            Object settings = settingsFrom.invoke(null, umcStack.newInstance(blueprint));
            Class<?> c = settings.getClass();
            return "gauge " + c.getField("gauge").get(settings) + ", style " + c.getField("track").get(settings)
                + ", bed " + c.getField("railBed").get(settings);
        } catch (ReflectiveOperationException e) {
            return "unreadable blueprint";
        }
    }

    /**
     * Builds some of the pieces of a line.
     *
     * @param pieces each {start, end, {heading at the start, heading at the end}} in world coordinates
     * @return {laid, refused}
     */
    public static int[] lay(EntityPlayerMP player, ItemStack blueprint, List<double[][]> pieces, boolean over,
        int from, int count) {
        if (!lookup()) return new int[] { 0, 0 };
        int laid = 0, refused = 0;
        try {
            Object stack = umcStack.newInstance(blueprint);
            Object who = umcPlayer.newInstance(player);
            Object world = worldGet.invoke(null, player.getServerWorld());
            for (int i = from; i < pieces.size() && i < from + count; i++) {
                double[][] piece = pieces.get(i);
                double[] a = piece[0], b = piece[1];
                float yawA = (float) piece[2][0], yawB = (float) piece[2][1];
                double chord = Line.distance(a, b);

                double bx = Math.floor(a[0]), by = Math.floor(a[1]), bz = Math.floor(a[2]);
                double[] ra = { a[0] - bx, a[1] - by, a[2] - bz };
                double[] rb = { b[0] - bx, b[1] - by, b[2] - bz };
                double arm = chord / 3, rise = (rb[1] - ra[1]) / 3;
                Object start = placement.newInstance(vec3d(ra), directionNone, yawA, vec3d(Line.control(ra, yawA, arm, rise)));
                Object end = placement.newInstance(vec3d(rb), directionNone, yawB, vec3d(Line.control(rb, yawB + 180, arm, -rise)));

                Object info = railInfo.newInstance(stack, start, end);
                final int length = Math.max(1, (int) Math.ceil(chord));
                info = withSettings.invoke(info, (Consumer<Object>) mutable -> {
                    try {
                        mType.set(mutable, trackCustom);
                        mLength.setInt(mutable, length);
                        mPreview.setBoolean(mutable, false);
                    } catch (IllegalAccessException e) {
                        throw new IllegalStateException(e);
                    }
                });

                // the ground has to be there to be asked about: a piece far from the player is in no loaded chunk
                for (double t = 0; t <= 1.0001; t += 8.0 / Math.max(8, chord))
                    player.getServerWorld().getChunk(new BlockPos(a[0] + (b[0] - a[0]) * t, a[1], a[2] + (b[2] - a[2]) * t));

                Object pos = umcVec3i.newInstance(bx, by, bz);
                // the builder is kept by position, so the one asked here is the one that does the building
                Object builder = getBuilder.invoke(info, world, pos);
                if (over) overrideFlexible.setBoolean(builder, true);
                if (!Boolean.TRUE.equals(canBuild.invoke(builder))) {
                    refused++;
                    if (complained++ < 5)
                        Platelayer.LOG.info(String.format("no room for track at %.1f %.1f %.1f - something is already there,"
                            + " or the ground under it is not solid", a[0], a[1], a[2]));
                    continue;
                }
                build.invoke(info, who, pos, true);
                laid++;
            }
        } catch (ReflectiveOperationException | RuntimeException e) {
            Platelayer.LOG.warn("Laying stopped after " + laid + " pieces: " + e);
        }
        return new int[] { laid, refused };
    }

    /** Takes the Immersive Railroading blocks near a line out, so new track has room. */
    public static int clear(World world, List<double[]> points, double radius) {
        int removed = 0, r = (int) Math.ceil(radius);
        Set<Long> done = new HashSet<>();
        for (double[] p : points) {
            world.getChunk(new BlockPos(p[0], p[1], p[2]));
            BlockPos centre = new BlockPos(p[0], p[1], p[2]);
            for (int dx = -r; dx <= r; dx++)
                for (int dy = -r; dy <= r; dy++)
                    for (int dz = -r; dz <= r; dz++) {
                        BlockPos at = centre.add(dx, dy, dz);
                        if (!done.add(at.toLong()) || !world.isBlockLoaded(at)) continue;
                        ResourceLocation name = world.getBlockState(at).getBlock().getRegistryName();
                        if (name == null || !"immersiverailroading".equals(name.getNamespace())) continue;
                        world.setBlockToAir(at);
                        removed++;
                    }
        }
        return removed;
    }

    private static Object vec3d(double[] p) throws ReflectiveOperationException {
        return umcVec3d.newInstance(p[0], p[1], p[2]);
    }

    @SuppressWarnings("unchecked")
    private static synchronized boolean lookup() {
        if (looked) return railInfo != null;
        looked = true;
        try {
            ClassLoader cl = Track.class.getClassLoader();
            Class<?> cStack = Class.forName("cam72cam.mod.item.ItemStack", false, cl);
            Class<?> cPlayer = Class.forName("cam72cam.mod.entity.Player", false, cl);
            Class<?> cVec3d = Class.forName("cam72cam.mod.math.Vec3d", false, cl);
            Class<?> cVec3i = Class.forName("cam72cam.mod.math.Vec3i", false, cl);
            Class<?> cWorld = Class.forName("cam72cam.mod.world.World", false, cl);
            Class<?> cSettings = Class.forName("cam72cam.immersiverailroading.items.nbt.RailSettings", false, cl);
            Class<?> cMutable = Class.forName("cam72cam.immersiverailroading.items.nbt.RailSettings$Mutable", false, cl);
            Class<?> cPlacement = Class.forName("cam72cam.immersiverailroading.util.PlacementInfo", false, cl);
            Class<?> cInfo = Class.forName("cam72cam.immersiverailroading.util.RailInfo", false, cl);
            Class<?> cBuilder = Class.forName("cam72cam.immersiverailroading.track.BuilderBase", false, cl);
            Class<?> cItems = Class.forName("cam72cam.immersiverailroading.library.TrackItems", false, cl);
            Class<?> cDirection = Class.forName("cam72cam.immersiverailroading.library.TrackDirection", false, cl);

            umcStack = cStack.getConstructor(net.minecraft.item.ItemStack.class);
            umcPlayer = cPlayer.getConstructor(net.minecraft.entity.player.EntityPlayer.class);
            umcVec3d = cVec3d.getConstructor(double.class, double.class, double.class);
            umcVec3i = cVec3i.getConstructor(double.class, double.class, double.class);
            worldGet = cWorld.getMethod("get", net.minecraft.world.World.class);
            placement = cPlacement.getConstructor(cVec3d, cDirection, float.class, cVec3d);
            railInfo = cInfo.getConstructor(cStack, cPlacement, cPlacement);
            settingsFrom = cSettings.getMethod("from", cStack);
            withSettings = cInfo.getMethod("withSettings", Consumer.class);
            getBuilder = cInfo.getMethod("getBuilder", cWorld, cVec3i);
            canBuild = cBuilder.getMethod("canBuild");
            overrideFlexible = cBuilder.getField("overrideFlexible");
            build = cInfo.getMethod("build", cPlayer, cVec3i, boolean.class);
            trackCustom = Enum.valueOf((Class<Enum>) cItems.asSubclass(Enum.class), "CUSTOM");
            directionNone = Enum.valueOf((Class<Enum>) cDirection.asSubclass(Enum.class), "NONE");
            mType = cMutable.getField("type");
            mLength = cMutable.getField("length");
            mPreview = cMutable.getField("isPreview");
            Platelayer.LOG.info("Immersive Railroading found: track can be laid along a line");
            return true;
        } catch (ReflectiveOperationException | LinkageError e) {
            Platelayer.LOG.warn("Immersive Railroading is missing or has changed, laying is off: " + e);
            railInfo = null;
            return false;
        }
    }
}
