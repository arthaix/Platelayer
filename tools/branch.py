"""Draws a branch leaving a line of a Platelayer line file.

The branch starts on the track it leaves, pointing the way that track points, and then bends away by however many
degrees are asked for - the way a turnout lets one track carry on while the other curves off. The bend is spread
over the whole branch, gently at both ends, so there is no corner where it begins and none where it ends.

    python branch.py lines.json track1 --at-end 1750 --length 400 --turn 0 --name branch_a

    --at <blocks>        where it leaves, measured along the line from its beginning
    --at-end <blocks>    ... or measured back from the end of the line
    --length <blocks>    how long the branch is
    --turn <degrees>     how far it comes round by the end; 0 carries straight on while the line curves away.
                         Positive turns one way, negative the other - the script prints where it ends up
    --level              hold the height it left at, instead of following the track it leaves
    --to <height>        come down to that height, in the drawing's own up axis: the branch keeps to the height of
                         the track it leaves until it is clear of it, then falls at the gradient given, then levels
    --hold <blocks>      how far it keeps to the height of the track it leaves before starting down (120)
    --grade <per mille>  how steeply it comes down at most (8)
    --radius <blocks>    the vertical curves the gradient eases in and out over (10000)
    --turn-over <blocks> finish turning within that distance and run straight after it
    --clearance <blocks> keep this far from track that is already there (0.9)
    --name <text>        what the new line is called (default: branch)
    --into <file>        write it into this line file instead of the one the line it leaves came from

Near the turnout the branch is still inside its parent's track, so that stretch is left out rather than laid twice.
The new line is added to the file and laid like any other:

    /platelayer lay lines branch ~ ~ ~
"""

import argparse
import json
import math

from crossover import at, clearance_of, keep_clear, length_of, smooth, stations, stretch


def tangent(points, along, distance, span=20):
    """Which way the line points there, and how steeply it climbs, per block along."""
    a = at(points, along, max(0, distance - span))
    b = at(points, along, min(along[-1], distance + span))
    flat = math.hypot(b[0] - a[0], b[1] - a[1])
    if flat < 1e-9:
        return (1.0, 0.0), 0.0
    return ((b[0] - a[0]) / flat, (b[1] - a[1]) / flat), (b[2] - a[2]) / flat


def profile(parent, along, start, length, steps, target, hold, grade, radius, level):
    """The height of the branch at every step.

    Up to the hold it keeps to the height of the track it leaves, so it neither climbs out of the embankment it is
    standing on nor sinks into it. From there it comes down the way a railway does: the gradient eases in over a
    vertical curve of the radius given, holds, and eases out again to arrive level at the height asked for. The
    gradient it starts from is the one the parent track has at that place, so there is no kink where it lets go.
    """
    step = length / steps
    here = at(parent, along, start)
    if target is None:
        return [here[2] if level else at(parent, along, min(along[-1], start + min(k * step, hold)))[2]
                for k in range(steps + 1)]
    grip = min(along[-1], start + hold)
    top = here[2] if level else at(parent, along, grip)[2]
    g0 = 0.0 if level else (at(parent, along, max(0, grip - 10))[2] - at(parent, along, min(along[-1], grip + 10))[2]) / 20
    g0 = max(0.0, g0)
    drop = top - target
    lo, hi = 0.0, grade / 1000.0
    def plan(i):
        l1, l2 = radius * abs(i - g0), radius * i
        steady = (drop - (g0 + i) / 2 * l1 - i / 2 * l2) / i if i > 0 else -1
        return l1, steady, l2
    i = hi
    if plan(i)[1] < 0:                      # not room for the full gradient: find the one that just fits
        for _ in range(60):
            mid = (lo + hi) / 2
            if plan(mid)[1] < 0: hi = mid
            else: lo = mid
        i = lo
    l1, steady, l2 = plan(i)
    steady = max(0.0, steady)
    out = []
    for k in range(steps + 1):
        walked = k * step
        if walked <= hold:
            out.append(here[2] if level else at(parent, along, min(along[-1], start + walked))[2])
            continue
        d = walked - hold
        if d < l1:
            fall = g0 * d + (i - g0) * d * d / (2 * l1) if l1 > 0 else 0
        elif d < l1 + steady:
            fall = (g0 + i) / 2 * l1 + i * (d - l1)
        elif d < l1 + steady + l2:
            e = d - l1 - steady
            fall = (g0 + i) / 2 * l1 + i * steady + i * e - i * e * e / (2 * l2)
        else:
            fall = drop
        out.append(max(target, top - fall))
    return out


def branch(parent, start, length, turn, level, target=None, hold=120, grade=8, radius=10000, turn_over=None, steps=None):
    """The branch as a list of points, beginning on the line it leaves."""
    along = stations(parent)
    here = at(parent, along, start)
    (ux, uy), _ = tangent(parent, along, start)
    steps = steps or max(40, int(length))
    step = length / steps
    turn = math.radians(turn)
    bend = turn_over or length
    heights = profile(parent, along, start, length, steps, target, hold, grade, radius, level)
    points, x, y = [[here[0], here[1], heights[0]]], here[0], here[1]
    for k in range(1, steps + 1):
        a = turn * smooth(min(1.0, (k - 0.5) * step / bend))
        dx = ux * math.cos(a) - uy * math.sin(a)
        dy = ux * math.sin(a) + uy * math.cos(a)
        x, y = x + dx * step, y + dy * step
        points.append([x, y, heights[k]])
    return points


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("file")
    ap.add_argument("parent")
    ap.add_argument("--at", type=float)
    ap.add_argument("--at-end", type=float)
    ap.add_argument("--length", type=float, default=300)
    ap.add_argument("--turn", type=float, default=0)
    ap.add_argument("--level", action="store_true")
    ap.add_argument("--to", type=float, dest="target", help="come down to this height, in the drawing's up axis")
    ap.add_argument("--hold", type=float, default=120)
    ap.add_argument("--grade", type=float, default=8, help="per mille")
    ap.add_argument("--radius", type=float, default=10000, help="of the vertical curves")
    ap.add_argument("--turn-over", type=float, help="finish turning within this many blocks, then run straight")
    ap.add_argument("--clearance", type=float, default=0.9)
    ap.add_argument("--name", default="branch")
    ap.add_argument("--into", help="write the branch into this file instead of the one the line came from")
    args = ap.parse_args()

    lines = json.load(open(args.file, encoding="utf-8"))
    parent = lines[args.parent][0]
    total = stations(parent)[-1]
    if args.at is not None:
        start = args.at
    elif args.at_end is not None:
        start = total - args.at_end
    else:
        raise SystemExit("say where it leaves: --at <blocks along> or --at-end <blocks from the end>")

    whole = branch(parent, start, args.length, args.turn, args.level, args.target, args.hold, args.grade,
                   args.radius, args.turn_over)
    others = []
    for name, runs in lines.items():
        if name == args.name:
            continue
        for run in runs:
            near = stretch(run, max(0, start - 200), start + args.length + 200) if name == args.parent else run
            others.append(near)
    runs = keep_clear(whole, others, args.clearance)
    into = args.into or args.file
    kept = lines if into == args.file else json.load(open(into, encoding="utf-8"))
    kept[args.name] = runs
    with open(into, "w", encoding="utf-8") as f:
        json.dump(kept, f, ensure_ascii=False)

    radius = args.length / math.radians(abs(args.turn)) if args.turn else 0
    print("branch off %s at %.0f along it (%.0f back from its end)" % (args.parent, start, total - start))
    print("  %.0f blocks long, coming round %.1f degrees%s"
          % (args.length, args.turn, ", sharpest radius about %.0f blocks" % (radius / 1.5) if radius else ""))
    print("  ends %.1f blocks from where it left, %.1f from the line itself, %.1f lower"
          % (math.dist(whole[0], whole[-1]), clearance_of(whole[-1], [parent]), whole[0][2] - whole[-1][2]))
    print("  laid as %d stretch(es), %s blocks (the rest is inside the track it leaves)"
          % (len(runs), ", ".join("%.0f" % length_of(r) for r in runs)))


if __name__ == "__main__":
    main()
