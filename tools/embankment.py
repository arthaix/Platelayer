"""Builds the earthwork a branch needs, in the shape of the one already under the line.

A branch that leaves a line built on an embankment runs off the side of it within a hundred blocks and is then left
in the air, where Immersive Railroading will not keep it. This draws the ground it needs: a flat top at the height
of the track, a rounded shoulder, and a slope down into the terrain - all measured off the embankment that is there,
so the new work comes out the same shape as the old.

    python embankment.py branches.json branch_north --line vsm.json --out fill.obj

    --line <file>        the line file the main tracks are in, so the two earthworks meet without a trench
    --tracks <a> <b>     which two lines in it are the main tracks (path1 path2)
    --main <blocks>      half the width of the old flat top (7, as measured on the line)
    --top <blocks>       half the width of the new flat top (4.5, what the double track's top gives each track)
    --slope <run>        how far out the sides run per block down (1.24)
    --floor <height>     how far down they go, in the drawing's own up axis
    --step <blocks>      how often a cross section is cut (2)

Only the ground that is missing gets drawn. Every cross section is cut off at the old embankment's shoulder: where
the branch is still over the old top nothing is drawn at all, where it is just clear the new work reaches back to
that shoulder, and further out it stands on its own. What is cut off would have been buried in the old bank anyway.

The result is one object as a Wavefront .obj with a single material, in the same coordinates as the drawing it came
from, so it is placed exactly as the rest of the model is.
"""

import argparse
import json
import math

SHOULDER = ((1.5, 0.17), (2.5, 0.64), (3.5, 1.27))   # out from the top edge, and down - as measured on the line

MATERIAL = """newmtl Grass
Ns 250.000000
Ka 1.000000 1.000000 1.000000
Kd 0.800000 0.800000 0.800000
Ks 0.500000 0.500000 0.500000
Ke 0.000000 0.000000 0.000000
Ni 1.500000
d 1.000000
illum 2
"""


def centre_line(first, second):
    """Halfway between the two tracks, which is what the old embankment is built about."""
    return [[(a[i] + b[i]) / 2 for i in range(3)] for a, b in zip(first, second)]


def nearest(points, target, was, window=400):
    """The nearest point of the line, looked for near the last one found - everywhere, the first time."""
    lo, hi = (0, len(points)) if was is None else (max(0, was - window), min(len(points), was + window))
    best, at = None, lo
    for i in range(lo, hi):
        d = (points[i][0] - target[0]) ** 2 + (points[i][1] - target[1]) ** 2
        if best is None or d < best:
            best, at = d, i
    return at


def section(top, top_half, slope, floor):
    """The whole cross section, left toe up over the flat top and down to the right toe, as (offset, height)."""
    shape = [(top_half, 0.0)]
    for run, drop in SHOULDER:
        shape.append((top_half + run, drop))
    deep = max(0.0, top - floor)
    if deep > shape[-1][1]:
        shape.append((top_half + SHOULDER[-1][0] + (deep - shape[-1][1]) * slope, deep))
    return [(-o, top - d) for o, d in reversed(shape)] + [(o, top - d) for o, d in shape]


def build(branch, centre, top_half, slope, floor, step, main_half):
    """Cross sections along the branch, each cut off at the old embankment's shoulder."""
    rings, was = [], None
    for i in range(0, len(branch), step):
        p = branch[i]
        after = branch[min(i + step, len(branch) - 1)]
        before = branch[max(i - step, 0)]
        tx, ty = after[0] - before[0], after[1] - before[1]
        run = math.hypot(tx, ty)
        if run < 1e-9:
            continue
        nx, ny = -ty / run, tx / run                        # across the branch
        was = nearest(centre, p, was)
        c = centre[was]
        along = (c[0] - p[0]) * nx + (c[1] - p[1]) * ny     # where the old line lies, measured across the branch
        if abs(along) <= main_half:
            continue                                        # still over the old top: the ground is already there
        edge = along + main_half if along < 0 else along - main_half   # its shoulder, on the branch's side
        ring = section(p[2], top_half, slope, floor)
        ring = [(max(o, edge), h) for o, h in ring] if along < 0 else [(min(o, edge), h) for o, h in ring]
        rings.append([(p[0] + nx * o, p[1] + ny * o, h) for o, h in ring])
    return rings


def to_obj(pieces, material, material_name="Grass"):
    """The cross sections bridged into surfaces, as Wavefront text. Blender own axes: up is Y, forward is -Z."""
    out = ["# earthwork drawn to the shape of the line own embankment", "mtllib %s" % material]
    done = 0
    for name, rings in pieces:
        out.append("o %s" % name)
        for ring in rings:
            for x, y, z in ring:
                out.append("v %.4f %.4f %.4f" % (x, z, -y))
        out.append("usemtl %s" % material_name)
        width = len(rings[0])
        for r in range(len(rings) - 1):
            first, second = done + r * width + 1, done + (r + 1) * width + 1
            for k in range(width - 1):
                out.append("f %d %d %d %d" % (first + k, first + k + 1, second + k + 1, second + k))
        first, last = done + 1, done + (len(rings) - 1) * width + 1
        for k in range(1, width - 1):                        # the ends, so the fill is not hollow to look into
            out.append("f %d %d %d" % (first, first + 1 + k, first + k))
            out.append("f %d %d %d" % (last, last + k, last + k + 1))
        done += len(rings) * width
    return nl_join(out)


def nl_join(lines):
    """One line each, and a newline at the end."""
    return chr(10).join(lines) + chr(10)


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("file")
    ap.add_argument("branch", nargs="+")
    ap.add_argument("--line", required=True)
    ap.add_argument("--tracks", nargs=2, default=["path1", "path2"])
    ap.add_argument("--main", type=float, default=7.0)
    ap.add_argument("--top", type=float, default=4.5)
    ap.add_argument("--slope", type=float, default=1.24)
    ap.add_argument("--floor", type=float, default=-10.0)
    ap.add_argument("--step", type=int, default=2)
    ap.add_argument("--out", required=True)
    args = ap.parse_args()

    drawn = json.load(open(args.file, encoding="utf-8"))
    lines = json.load(open(args.line, encoding="utf-8"))
    centre = centre_line(lines[args.tracks[0]][0], lines[args.tracks[1]][0])
    pieces = []
    for name in args.branch:
        rings = build(drawn[name][0], centre, args.top, args.slope, args.floor, args.step, args.main)
        if not rings:
            raise SystemExit("%s never leaves the embankment it is on: there is nothing to build" % name)
        pieces.append((name, rings))
        begin, end = rings[0][len(rings[0]) // 2], rings[-1][len(rings[-1]) // 2]
        print("%s: %d cross sections, widest %.1f blocks"
              % (name, len(rings), max(math.dist(r[0], r[-1]) for r in rings)))
        print("   from %.1f %.1f %.1f to %.1f %.1f %.1f in the drawing" % (tuple(begin) + tuple(end)))
    material = args.out.replace("\\", "/").rsplit("/", 1)[-1].replace(".obj", ".mtl")
    text = to_obj(pieces, material)
    open(args.out, "w", encoding="utf-8").write(text)
    open(args.out[:-4] + ".mtl", "w", encoding="utf-8").write(MATERIAL)
    print("top %.1f wide at the height of the track, sides 1:%.2f, down to %.1f; %d faces"
          % (args.top * 2, args.slope, args.floor, text.count(chr(10) + "f ")))
    print("written to", args.out)


if __name__ == "__main__":
    main()
