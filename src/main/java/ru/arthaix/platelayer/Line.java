package ru.arthaix.platelayer;

import java.io.File;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.Reader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

/**
 * A line drawn somewhere else - a curve in a 3D editor, a survey, anything that can be written as points - and the
 * geometry that turns it into track. Files live in {@code config/platelayer/lines} and hold plain JSON:
 * {@code {"name": [[[x, y, z], ...], ...], ...}} - a list of lines per name, each a list of points.
 *
 * <p>Points are in the drawing's own coordinates. A world anchor and the editor's up-axis decide where they land, so
 * the same file can be laid anywhere and twice over without drifting.
 */
public final class Line {

    private Line() {}

    public static File folder() {
        return new File("config/platelayer/lines");
    }

    /** Every line of a file, by name, in the order the file lists them. */
    public static Map<String, List<double[][]>> read(File file) throws IOException {
        Map<String, List<double[][]>> out = new LinkedHashMap<>();
        try (Reader r = new InputStreamReader(Files.newInputStream(file.toPath()), StandardCharsets.UTF_8)) {
            JsonObject root = new JsonParser().parse(r).getAsJsonObject();
            for (Map.Entry<String, JsonElement> e : root.entrySet()) {
                List<double[][]> lines = new ArrayList<>();
                for (JsonElement line : e.getValue().getAsJsonArray()) {
                    JsonArray points = line.getAsJsonArray();
                    double[][] pts = new double[points.size()][3];
                    for (int i = 0; i < points.size(); i++) {
                        JsonArray p = points.get(i).getAsJsonArray();
                        for (int c = 0; c < 3; c++) pts[i][c] = p.get(c).getAsDouble();
                    }
                    if (pts.length >= 2) lines.add(pts);
                }
                out.put(e.getKey(), lines);
            }
        }
        return out;
    }

    /**
     * Drawing coordinates to world coordinates.
     *
     * @param zUp  the drawing has Z pointing up (Blender and most CAD); Minecraft has Y up, so (x, y, z) becomes
     *             (x, z, -y). With Y already up the point only moves to the anchor.
     * @param turns quarter turns around the anchor, for a drawing that faces another way.
     */
    public static double[][] toWorld(double[][] drawn, double[] anchor, boolean zUp, double scale, int turns) {
        double[][] out = new double[drawn.length][3];
        for (int i = 0; i < drawn.length; i++) {
            double x = drawn[i][0] * scale;
            double y = (zUp ? drawn[i][2] : drawn[i][1]) * scale;
            double z = (zUp ? -drawn[i][1] : drawn[i][2]) * scale;
            for (int t = 0; t < (turns & 3); t++) {
                double nx = -z;
                z = x;
                x = nx;
            }
            out[i][0] = anchor[0] + x;
            out[i][1] = anchor[1] + y;
            out[i][2] = anchor[2] + z;
        }
        return out;
    }

    /**
     * Cuts a line into the pieces track will be laid in. A piece is a smooth curve between two points that meets the
     * line's own direction at both ends, so it is stretched as far as it can go while staying within {@code tolerance}
     * blocks of the drawing - long runs down the straights, shorter ones through the curves, the way rail is laid.
     * Each piece comes back as {start, end, {heading at the start, heading at the end}}.
     */
    public static List<double[][]> pieces(double[][] line, double tolerance, double maxLength) {
        List<double[][]> out = new ArrayList<>();
        double[][] run = densify(line, maxLength / 2);
        int i = 0;
        while (i < run.length - 1) {
            int far = i;
            double length = 0;
            while (far < run.length - 1 && length + distance(run[far], run[far + 1]) <= maxLength) {
                length += distance(run[far], run[far + 1]);
                far++;
            }
            if (far <= i) break;
            float yawA = tangent(run, i);
            int best = i + 1, lo = i + 1, hi = far;
            while (lo <= hi) {
                int mid = (lo + hi) >>> 1;
                if (error(run, i, mid, yawA, tangent(run, mid)) <= tolerance) {
                    best = mid;
                    lo = mid + 1;
                } else {
                    hi = mid - 1;
                }
            }
            // a sharp kink in the drawing can otherwise end a piece where it started
            while (best < run.length - 1 && distance(run[i], run[best]) < 0.5) best++;
            out.add(new double[][] { run[i], run[best], { yawA, tangent(run, best) } });
            i = best;
        }
        return out;
    }

    /**
     * Points added along any segment longer than {@code most}. A straight mile of railway is drawn with two points,
     * and such a segment would not fit in a single piece and would end the walk along the line; the added points sit
     * on the segment, so nothing about the shape changes.
     */
    public static double[][] densify(double[][] run, double most) {
        List<double[]> out = new ArrayList<>();
        for (int i = 0; i < run.length; i++) {
            out.add(run[i]);
            if (i + 1 >= run.length) break;
            int cuts = (int) Math.ceil(distance(run[i], run[i + 1]) / most) - 1;
            for (int k = 1; k <= cuts; k++) {
                double t = k / (double) (cuts + 1);
                out.add(new double[] { run[i][0] + (run[i + 1][0] - run[i][0]) * t,
                    run[i][1] + (run[i + 1][1] - run[i][1]) * t, run[i][2] + (run[i + 1][2] - run[i][2]) * t });
            }
        }
        return out.toArray(new double[0][]);
    }

    /** A jump longer than this means the drawing really is in separate lines, not one with a long straight in it. */
    public static List<double[][]> split(double[][] line, double maxJump) {
        List<double[][]> runs = new ArrayList<>();
        int start = 0;
        for (int i = 1; i <= line.length; i++) {
            if (i != line.length && distance(line[i - 1], line[i]) <= maxJump) continue;
            if (i - start >= 2) {
                double[][] run = new double[i - start][];
                System.arraycopy(line, start, run, 0, i - start);
                runs.add(run);
            }
            start = i;
        }
        return runs;
    }

    /** Points along the line every {@code step} blocks, ends kept. Used to walk over old track and take it out. */
    public static List<double[]> every(double[][] line, double step) {
        List<double[]> out = new ArrayList<>();
        if (line.length == 0) return out;
        out.add(line[0].clone());
        double carried = 0;
        for (int i = 1; i < line.length; i++) {
            double[] a = line[i - 1], b = line[i];
            double len = distance(a, b);
            if (len < 1e-9) continue;
            double travelled = -carried;
            while (travelled + step <= len) {
                travelled += step;
                double t = travelled / len;
                out.add(new double[] { a[0] + (b[0] - a[0]) * t, a[1] + (b[1] - a[1]) * t, a[2] + (b[2] - a[2]) * t });
            }
            carried = len - travelled;
        }
        out.add(line[line.length - 1].clone());
        return out;
    }

    /** Where the line points at this vertex, measured over a few blocks so a short segment cannot wobble it. */
    public static float tangent(double[][] run, int i) {
        int back = i;
        double travelled = 0;
        while (back > 0 && travelled < 4) {
            travelled += distance(run[back - 1], run[back]);
            back--;
        }
        int fwd = i;
        travelled = 0;
        while (fwd < run.length - 1 && travelled < 4) {
            travelled += distance(run[fwd], run[fwd + 1]);
            fwd++;
        }
        return yaw(run[back], run[fwd]);
    }

    /** How far the smooth curve of a piece strays from the line it stands for, at its worst. */
    public static double error(double[][] run, int i, int j, float yawA, float yawB) {
        double[] a = run[i], b = run[j];
        double chord = distance(a, b), arm = chord / 3;
        double[] ca = control(a, yawA, arm, (b[1] - a[1]) / 3);
        double[] cb = control(b, yawB + 180, arm, -(b[1] - a[1]) / 3);
        double worst = 0;
        for (int k = 1; k < 13; k++) {
            double t = k / 13.0, u = 1 - t;
            double[] q = new double[3];
            for (int c = 0; c < 3; c++)
                q[c] = u * u * u * a[c] + 3 * u * u * t * ca[c] + 3 * u * t * t * cb[c] + t * t * t * b[c];
            double best = Double.MAX_VALUE;
            for (int n = i; n < j; n++) best = Math.min(best, toSegment(q, run[n], run[n + 1]));
            worst = Math.max(worst, best);
        }
        return worst;
    }

    /** A control point of a piece: along the heading from one end, a third of the way to the other. */
    public static double[] control(double[] from, float yaw, double arm, double rise) {
        return new double[] { from[0] - Math.sin(Math.toRadians(yaw)) * arm, from[1] + rise,
            from[2] + Math.cos(Math.toRadians(yaw)) * arm };
    }

    /** Minecraft's yaw for a direction: 0 looks towards +Z, 90 towards -X. */
    public static float yaw(double[] from, double[] to) {
        return (float) Math.toDegrees(Math.atan2(-(to[0] - from[0]), to[2] - from[2]));
    }

    public static double distance(double[] a, double[] b) {
        double dx = b[0] - a[0], dy = b[1] - a[1], dz = b[2] - a[2];
        return Math.sqrt(dx * dx + dy * dy + dz * dz);
    }

    private static double toSegment(double[] q, double[] a, double[] b) {
        double vx = b[0] - a[0], vy = b[1] - a[1], vz = b[2] - a[2];
        double len2 = vx * vx + vy * vy + vz * vz;
        if (len2 < 1e-12) return distance(q, a);
        double t = ((q[0] - a[0]) * vx + (q[1] - a[1]) * vy + (q[2] - a[2]) * vz) / len2;
        t = Math.max(0, Math.min(1, t));
        return distance(q, new double[] { a[0] + vx * t, a[1] + vy * t, a[2] + vz * t });
    }
}
