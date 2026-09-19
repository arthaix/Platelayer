package ru.arthaix.platelayer;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import net.minecraft.entity.player.EntityPlayerMP;
import net.minecraft.item.ItemStack;
import net.minecraft.server.MinecraftServer;
import net.minecraft.util.text.TextComponentString;
import net.minecraft.util.text.TextFormatting;
import net.minecraftforge.common.MinecraftForge;
import net.minecraftforge.fml.common.Mod;
import net.minecraftforge.fml.common.event.FMLPreInitializationEvent;
import net.minecraftforge.fml.common.event.FMLServerStartingEvent;
import net.minecraftforge.fml.common.eventhandler.SubscribeEvent;
import net.minecraftforge.fml.common.gameevent.TickEvent;

import org.apache.logging.log4j.Logger;

/**
 * Lays Immersive Railroading track along a line drawn somewhere else.
 *
 * <p>The work is spread over ticks: a whole railway is thousands of blocks of track and building it in one go would
 * stop the server for minutes. Pieces go in a few per tick with the count reported in chat, and the run can be
 * stopped at any point.
 */
@Mod(modid = Platelayer.MOD_ID, name = "Platelayer", version = "1.0.0", acceptableRemoteVersions = "*")
public class Platelayer {

    public static final String MOD_ID = "platelayer";
    public static Logger LOG;
    public static final Platelayer INSTANCE = new Platelayer();

    private Job job;

    @Mod.EventHandler
    public void preInit(FMLPreInitializationEvent event) {
        LOG = event.getModLog();
        Line.folder().mkdirs();
        MinecraftForge.EVENT_BUS.register(INSTANCE);
    }

    @Mod.EventHandler
    public void serverStarting(FMLServerStartingEvent event) {
        event.registerServerCommand(new PlatelayerCommand());
    }

    /** A line on its way into the world. */
    private static final class Job {
        UUID player;
        ItemStack blueprint;
        List<double[][]> pieces;
        int perTick, at, laid, refused, told;
        String name;
    }

    public boolean busy() {
        return job != null;
    }

    public void start(EntityPlayerMP player, ItemStack blueprint, List<double[][]> pieces, int perTick, String name) {
        Job fresh = new Job();
        fresh.player = player.getUniqueID();
        fresh.blueprint = blueprint.copy();
        fresh.pieces = new ArrayList<>(pieces);
        fresh.perTick = Math.max(1, perTick);
        fresh.name = name;
        job = fresh;
        say(player, TextFormatting.GRAY + "Laying " + pieces.size() + " pieces along " + name + "...");
    }

    public void stop(EntityPlayerMP player) {
        Job stopping = job;
        job = null;
        if (stopping != null && player != null)
            say(player, TextFormatting.YELLOW + "Stopped after " + stopping.laid + " pieces of " + stopping.name);
    }

    @SubscribeEvent
    public void onServerTick(TickEvent.ServerTickEvent event) {
        if (event.phase != TickEvent.Phase.END || job == null) return;
        Job current = job;
        MinecraftServer server = net.minecraftforge.fml.common.FMLCommonHandler.instance().getMinecraftServerInstance();
        EntityPlayerMP player = server == null ? null : server.getPlayerList().getPlayerByUUID(current.player);
        if (player == null) {
            job = null;
            return;
        }
        int[] done = Track.lay(player, current.blueprint, current.pieces, current.at, current.perTick);
        current.laid += done[0];
        current.refused += done[1];
        current.at += current.perTick;
        if (current.at < current.pieces.size()) {
            int percent = 100 * current.at / Math.max(1, current.pieces.size());
            if (percent >= current.told + 10) {
                current.told = percent;
                say(player, TextFormatting.GRAY + current.name + ": " + percent + "%, " + current.laid + " pieces laid");
            }
            return;
        }
        job = null;
        say(player, TextFormatting.GREEN + current.name + " done: " + current.laid + " pieces laid"
            + (current.refused > 0 ? TextFormatting.YELLOW + ", " + current.refused + " refused (something was in the way)" : ""));
        LOG.info("track along " + current.name + ": " + current.laid + " laid, " + current.refused + " refused");
    }

    public static void say(EntityPlayerMP player, String text) {
        player.sendMessage(new TextComponentString(text));
    }
}
