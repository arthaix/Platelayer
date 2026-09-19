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
    --level              hold the height it left at, instead of keeping the line's gradient
    --drop <blocks>      come down by that much: the branch holds its height while it clears the track it left,
                         then falls at the gradient given, then runs level again
    --hold <blocks>      how far it holds its height before starting down (120)
    --grade <percent>    how steeply it comes down (2)
    --clearance <blocks> keep this far from track that is already there (0.9)
    --name <text>        what the new line is called (default: branch)

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


def height(k, steps, length, climb, drop, hold, grade):
    """How far the branch has risen or fallen by that step."""
    walked = length * k / steps
    if not drop:
        return climb * walked
    fall = max(0.0, min(drop, (walked - hold) * grade / 100))
    return -fall


def branch(parent, start, length, turn, level, drop=0, hold=120, grade=2, steps=None):
    """The branch as a list of points, beginning on the line it leaves."""
    along = stations(parent)
    here = at(parent, along, start)
    (ux, uy), climb = tangent(parent, along, start)
    if level:
        climb = 0.0
    steps = steps or max(40, int(length))
    step = length / steps
    turn = math.radians(turn)
    points, x, y = [list(here)], here[0], here[1]
    for k in range(1, steps + 1):
        a = turn * smooth((k - 0.5) / steps)
        dx = ux * math.cos(a) - uy * math.sin(a)
        dy = ux * math.sin(a) + uy * math.cos(a)
        x, y = x + dx * step, y + dy * step
        points.append([x, y, here[2] + height(k, steps, length, climb, drop, hold, grade)])
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
    ap.add_argument("--drop", type=float, default=0)
    ap.add_argument("--hold", type=float, default=120)
    ap.add_argument("--grade", type=float, default=2)
    ap.add_argument("--clearance", type=float, default=0.9)
    ap.add_argument("--name", default="branch")
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

    whole = branch(parent, start, args.length, args.turn, args.level, args.drop, args.hold, args.grade)
    others = []
    for name, runs in lines.items():
        if name == args.name:
            continue
        for run in runs:
            near = stretch(run, max(0, start - 200), start + args.length + 200) if name == args.parent else run
            others.append(near)
    runs = keep_clear(whole, others, args.clearance)
    lines[args.name] = runs
    with open(args.file, "w", encoding="utf-8") as f:
        json.dump(lines, f, ensure_ascii=False)

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
