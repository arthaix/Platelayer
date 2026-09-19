"""Draws a crossover between two lines of a Platelayer line file.

A crossover is two connections that swap the tracks over, crossing in the middle - the scissors an approach to a
station is built with. Both connections leave and join their tracks along the track's own direction, so there is no
kink where they meet, and the sideways move follows a smooth curve rather than a corner: the sharpest radius comes
out as the length squared over six times the track spacing, and the script prints it.

    python crossover.py lines.json track1 track2 --at-end 960 --length 200

    --at <blocks>        where it starts, measured along the first line from its beginning
    --at-end <blocks>    ... or measured back from the end of the line (usually what you want)
    --length <blocks>    how long the whole crossover is; longer is gentler
    --clearance <blocks> keep this far from track that is already there (0.9); 0 draws the connections whole
    --name <text>        what the two new lines are called (default: crossover)

A long crossover is a shallow one: near its ends each connection runs within a block of the track it leaves, and in
the middle the two connections all but touch. Those stretches already have rail in them, so the connections are cut
there - the first one also around the crossing, the second one running through it. Nothing is missing on the ground,
and no piece is refused for want of room. Pass --clearance 0 to draw them whole and lay the crossing by hand.

The new lines are added to the file and laid like any other:

    /platelayer lay lines crossover_a ~ ~ ~
"""

import argparse
import json
import math


def stations(points):
    """Distance along the line at every point."""
    out = [0.0]
    for a, b in zip(points, points[1:]):
        out.append(out[-1] + math.dist(a, b))
    return out


def at(points, along, distance):
    """The point that far along the line, between vertices where it falls."""
    if distance <= 0:
        return points[0]
    if distance >= along[-1]:
        return points[-1]
    lo, hi = 0, len(along) - 1
    while lo + 1 < hi:
        mid = (lo + hi) // 2
        if along[mid] <= distance:
            lo = mid
        else:
            hi = mid
    span = along[hi] - along[lo]
    t = 0.0 if span < 1e-9 else (distance - along[lo]) / span
    return [points[lo][i] + (points[hi][i] - points[lo][i]) * t for i in range(3)]


def to_segment(a, b, target):
    """How far the point is from the stretch between a and b, and how far along it the nearest place lies."""
    span = [b[k] - a[k] for k in range(3)]
    length2 = sum(c * c for c in span)
    t = 0.0 if length2 < 1e-12 else sum((target[k] - a[k]) * span[k] for k in range(3)) / length2
    t = max(0.0, min(1.0, t))
    near = [a[k] + span[k] * t for k in range(3)]
    return math.dist(near, target), t * math.sqrt(length2)


def nearest_station(points, along, target):
    """Where on this line the given point lies, as a distance along it.

    Measured against the line itself rather than its corners: a straight drawn with two points hundreds of blocks
    apart is still only as far away as the straight.
    """
    best, best_d = 0.0, float("inf")
    for i in range(len(points) - 1):
        d, forward = to_segment(points[i], points[i + 1], target)
        if d < best_d:
            best, best_d = along[i] + forward, d
    return best


def clearance_of(target, others):
    """How far the point is from the nearest of the lines already there."""
    best = float("inf")
    for points in others:
        for i in range(len(points) - 1):
            d, _ = to_segment(points[i], points[i + 1], target)
            if d < best:
                best = d
    return best


def smooth(t):
    """0 to 1 with no sideways kick at either end."""
    return t * t * (3 - 2 * t)


def connections(first, second, start, length, steps=None):
    """The two connections whole, as lists of points, and how far apart the tracks are where they begin."""
    s1, s2 = stations(first), stations(second)
    begin2 = nearest_station(second, s2, at(first, s1, start))
    steps = steps or max(40, int(length))
    a_to_b, b_to_a = [], []
    for k in range(steps + 1):
        t = k / steps
        w = smooth(t)
        pa = at(first, s1, start + t * length)
        pb = at(second, s2, begin2 + t * length)
        a_to_b.append([pa[i] + (pb[i] - pa[i]) * w for i in range(3)])
        b_to_a.append([pb[i] + (pa[i] - pb[i]) * w for i in range(3)])
    spacing = math.dist(at(first, s1, start), at(second, s2, begin2))
    return a_to_b, b_to_a, spacing


def keep_clear(line, others, clearance):
    """The stretches of the line that are further than that from anything already there."""
    runs, current = [], []
    for p in line:
        if clearance <= 0 or clearance_of(p, others) >= clearance:
            current.append(p)
        else:
            if len(current) >= 2:
                runs.append(current)
            current = []
    if len(current) >= 2:
        runs.append(current)
    return [[[round(c, 4) for c in p] for p in run] for run in runs]


def stretch(points, begin, end):
    """Just the part of the line between those two distances along it, with a point at each end."""
    along = stations(points)
    out = [at(points, along, begin)]
    out += [p for p, s in zip(points, along) if begin < s < end]
    out.append(at(points, along, end))
    return out


def length_of(run):
    return sum(math.dist(a, b) for a, b in zip(run, run[1:]))


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("file")
    ap.add_argument("first")
    ap.add_argument("second")
    ap.add_argument("--at", type=float)
    ap.add_argument("--at-end", type=float)
    ap.add_argument("--length", type=float, default=200)
    ap.add_argument("--clearance", type=float, default=0.9)
    ap.add_argument("--name", default="crossover")
    args = ap.parse_args()

    lines = json.load(open(args.file, encoding="utf-8"))
    first, second = lines[args.first][0], lines[args.second][0]
    total = stations(first)[-1]
    if args.at is not None:
        start = args.at
    elif args.at_end is not None:
        start = total - args.at_end
    else:
        raise SystemExit("say where it goes: --at <blocks along> or --at-end <blocks from the end>")

    whole_a, whole_b, spacing = connections(first, second, start, args.length)
    near = [stretch(first, start - 100, start + args.length + 100),
            stretch(second, start - 100, start + args.length + 100)]
    b = keep_clear(whole_b, near, args.clearance)
    a = keep_clear(whole_a, near + [run for run in b], args.clearance)
    lines[args.name + "_a"] = a
    lines[args.name + "_b"] = b
    with open(args.file, "w", encoding="utf-8") as f:
        json.dump(lines, f, ensure_ascii=False)

    radius = args.length ** 2 / (6 * spacing) if spacing > 0 else 0
    print("crossover between %s and %s, %.2f blocks apart" % (args.first, args.second, spacing))
    print("  from %.0f to %.0f along the line (%.0f to %.0f back from its end)"
          % (start, start + args.length, total - start, total - start - args.length))
    print("  %.0f blocks long, sharpest radius about %.0f blocks" % (args.length, radius))
    for name, runs in ((args.name + "_a", a), (args.name + "_b", b)):
        print("  %s: %d stretch(es), %s blocks"
              % (name, len(runs), ", ".join("%.0f" % length_of(r) for r in runs)))
    print("  written to", args.file)


if __name__ == "__main__":
    main()
