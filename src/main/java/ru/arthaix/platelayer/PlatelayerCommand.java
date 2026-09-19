package ru.arthaix.platelayer;

import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import java.util.Map;

import javax.annotation.Nullable;

import net.minecraft.command.CommandBase;
import net.minecraft.command.CommandException;
import net.minecraft.command.ICommandSender;
import net.minecraft.entity.player.EntityPlayerMP;
import net.minecraft.item.ItemStack;
import net.minecraft.server.MinecraftServer;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.text.TextFormatting;

/**
 * {@code /platelayer lay <file> <line|all> <x> <y> <z> [options]} - lays track along a drawn line, anchored at those
 * coordinates ({@code ~ ~ ~} for where you stand). Options in any order: a whole number is the longest piece in
 * blocks (200), a decimal is how far a piece may ever stray from the drawing (0.01), {@code clear} takes old track
 * out first, {@code yup} says the drawing already has Y up, {@code turn1..3} turns it by quarter turns, {@code x2}
 * or {@code x0.5} scales it, and {@code 0-2500} lays only that stretch along the line.
 */
public class PlatelayerCommand extends CommandBase {

    @Override
    public String getName() {
        return "platelayer";
    }

    @Override
    public String getUsage(ICommandSender sender) {
        return "/platelayer lay <file> <line|all> <x> <y> <z> [longest] [tolerance] [clear|clearonly] [over] [yup] [turn1-3] [xScale] [from-to] | list | stop";
    }

    @Override
    public int getRequiredPermissionLevel() {
        return 2;
    }

    @Override
    public void execute(MinecraftServer server, ICommandSender sender, String[] args) throws CommandException {
        if (args.length == 0) throw new CommandException(getUsage(sender));
        switch (args[0].toLowerCase(Locale.ROOT)) {
            case "list":
                list(sender);
                break;
            case "stop":
                Platelayer.INSTANCE.stop(getCommandSenderAsPlayer(sender));
                break;
            case "lay":
                lay(sender, args);
                break;
            default:
                throw new CommandException(getUsage(sender));
        }
    }

    private void list(ICommandSender sender) throws CommandException {
        File[] files = Line.folder().listFiles((d, n) -> n.endsWith(".json"));
        if (files == null || files.length == 0)
            throw new CommandException("No lines in " + Line.folder().getPath() + " - export one from your editor first");
        for (File f : files) {
            StringBuilder names = new StringBuilder();
            try {
                for (Map.Entry<String, List<double[][]>> e : Line.read(f).entrySet()) {
                    double length = 0;
                    int points = 0;
                    for (double[][] line : e.getValue()) {
                        points += line.length;
                        for (int i = 1; i < line.length; i++) length += Line.distance(line[i - 1], line[i]);
                    }
                    names.append(String.format(Locale.ROOT, "%n  %s: %.0f long, %d points", e.getKey(), length, points));
                }
            } catch (IOException e) {
                names.append(" (unreadable: ").append(e.getMessage()).append(')');
            }
            sender.sendMessage(new net.minecraft.util.text.TextComponentString(
                TextFormatting.GRAY + f.getName().replace(".json", "") + names));
        }
    }

    private void lay(ICommandSender sender, String[] args) throws CommandException {
        if (args.length < 6) throw new CommandException(getUsage(sender));
        if (Platelayer.INSTANCE.busy()) throw new CommandException("Already laying; /platelayer stop");
        EntityPlayerMP player = getCommandSenderAsPlayer(sender);
        ItemStack blueprint = player.getHeldItemMainhand();
        if (!Track.isBlueprint(blueprint)) throw new CommandException("Hold an Immersive Railroading track blueprint");

        File file = new File(Line.folder(), args[1].endsWith(".json") ? args[1] : args[1] + ".json");
        if (!file.isFile()) throw new CommandException("No line file " + file.getPath());
        BlockPos at = parseBlockPos(sender, args, 3, false);
        double[] anchor = { at.getX(), at.getY(), at.getZ() };

        double longest = 200, tolerance = 0.01, scale = 1;
        boolean clear = false, clearOnly = false, over = false, zUp = true;
        int turns = 0;
        double from = 0, to = Double.MAX_VALUE;
        String range = null;
        for (int i = 6; i < args.length; i++) {
            String arg = args[i].toLowerCase(Locale.ROOT);
            if (arg.matches("[0-9]+")) longest = parseDouble(arg, 8, 400);
            else if (arg.matches("0[.][0-9]+")) tolerance = parseDouble(arg, 0.001, 1);
            else if (arg.equals("clear")) clear = true;
            // take the old track out and stop there: laying a line in two commands needs it, because clearing
            // reaches a little past the stretch it is given and would eat into track the other command just laid
            else if (arg.equals("clearonly")) clear = clearOnly = true;
            // let a piece be laid through track already there, the way a turnout shares ground with its line
            else if (arg.equals("over")) over = true;
            else if (arg.equals("yup")) zUp = false;
            else if (arg.matches("turn[1-3]")) turns = arg.charAt(4) - '0';
            else if (arg.matches("x[0-9.]+")) scale = parseDouble(arg.substring(1), 0.01, 100);
            else if (arg.matches("[0-9]+-[0-9]+")) {
                range = arg;
                from = Double.parseDouble(arg.split("-")[0]);
                to = Double.parseDouble(arg.split("-")[1]);
            } else throw new CommandException("Cannot make sense of '" + args[i] + "'");
        }

        Map<String, List<double[][]>> lines;
        try {
            lines = Line.read(file);
        } catch (IOException e) {
            throw new CommandException("Cannot read " + file.getName() + ": " + e.getMessage());
        }

        List<double[][]> pieces = new ArrayList<>();
        List<double[]> along = new ArrayList<>();
        int used = 0;
        double total = 0, longestPiece = 0, shortestPiece = Double.MAX_VALUE;
        for (Map.Entry<String, List<double[][]>> e : lines.entrySet()) {
            if (!"all".equalsIgnoreCase(args[2]) && !e.getKey().equalsIgnoreCase(args[2])) continue;
            used++;
            for (double[][] drawn : e.getValue())
                for (double[][] run : Line.split(Line.toWorld(drawn, anchor, zUp, scale, turns), 1000)) {
                    double[][] wanted = run;
                    if (range != null) {
                        List<double[]> cut = new ArrayList<>();
                        double walked = 0;
                        for (int i = 0; i < run.length; i++) {
                            if (i > 0) walked += Line.distance(run[i - 1], run[i]);
                            if (walked >= from && walked <= to) cut.add(run[i]);
                        }
                        if (cut.size() < 2) continue;
                        wanted = cut.toArray(new double[0][]);
                    }
                    along.addAll(Line.every(wanted, 1));
                    for (double[][] piece : Line.pieces(wanted, tolerance, longest)) {
                        pieces.add(piece);
                        double length = Line.distance(piece[0], piece[1]);
                        total += length;
                        longestPiece = Math.max(longestPiece, length);
                        shortestPiece = Math.min(shortestPiece, length);
                    }
                }
        }
        if (used == 0)
            throw new CommandException("No line called " + args[2] + " in " + file.getName() + " (it holds: " + String.join(", ", lines.keySet()) + ")");
        if (pieces.isEmpty()) throw new CommandException("Nothing of that line to lay");

        if (clear) {
            int removed = Track.clear(player.getServerWorld(), along, 3);
            Platelayer.say(player, TextFormatting.GRAY + "Took out " + removed + " blocks of old track along the line");
        }
        if (clearOnly) return;
        Platelayer.say(player, TextFormatting.GRAY + "Blueprint: " + Track.describe(blueprint));
        Platelayer.say(player, TextFormatting.GRAY + String.format(Locale.ROOT,
            "%.0f blocks of line in %d pieces of %.0f to %.0f blocks, never over %.0f cm off the drawing",
            total, pieces.size(), shortestPiece, longestPiece, tolerance * 100));
        Platelayer.INSTANCE.start(player, blueprint, pieces, over, 2, args[2] + " of " + file.getName());
    }

    @Override
    public List<String> getTabCompletions(MinecraftServer server, ICommandSender sender, String[] args, @Nullable BlockPos targetPos) {
        if (args.length == 1) return getListOfStringsMatchingLastWord(args, "lay", "list", "stop");
        if (args.length == 2 && "lay".equalsIgnoreCase(args[0])) {
            List<String> names = new ArrayList<>();
            File[] files = Line.folder().listFiles((d, n) -> n.endsWith(".json"));
            if (files != null) for (File f : files) names.add(f.getName().replace(".json", ""));
            return getListOfStringsMatchingLastWord(args, names);
        }
        if (args.length >= 4 && args.length <= 6 && "lay".equalsIgnoreCase(args[0]))
            return getTabCompletionCoordinate(args, 3, targetPos);
        if (args.length > 6) return getListOfStringsMatchingLastWord(args, "clear", "clearonly", "over", "yup", "turn1", "turn2", "turn3", "200", "0.01");
        return Arrays.asList();
    }
}
