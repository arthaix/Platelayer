"""The head of a turnout: the short, decided curve a diverging route leaves a track by.

A connection drawn as one smooth sweep from track to track is right in the large and wrong at the join: it leaves
along the track and pulls away so slowly that there is no turnout to see, only two rails running side by side for
tens of blocks. A railway does the opposite - it commits at once, on a tight curve, to a fixed angle, and only then
settles onto whatever alignment it is heading for. That angle is what a turnout is named by: 1:9, 1:11, 1:18.

    head(point, heading, radius, degrees, side)  -> the points of the curve, the point it ends at, its heading there

Lengths come out as the arc: a 6 degree head on a 290 block radius is 30 blocks long and gains 1.6 sideways, which
is about the 1:9.5 of a yard turnout. Bigger radius, same angle: longer head, same look.
"""

import math


def head(start, heading, radius, degrees, side, step=1.0):
    """The arc a diverging route leaves by, as points; side is +1 to turn one way, -1 the other."""
    turn = math.radians(degrees)
    length = radius * turn
    steps = max(4, int(round(length / step)))
    out, x, y = [list(start)], start[0], start[1]
    for k in range(1, steps + 1):
        a = heading + side * turn * (k - 0.5) / steps
        x += math.cos(a) * length / steps
        y += math.sin(a) * length / steps
        out.append([x, y, start[2]])
    return out, heading + side * turn


def sideways(radius, degrees):
    """How far the head moves across, and how far along."""
    turn = math.radians(degrees)
    return radius * (1 - math.cos(turn)), radius * math.sin(turn)


def crossover(spacing, radius, degrees):
    """A crossover of two heads and the straight between them: its length, and the straight's length."""
    across, along = sideways(radius, degrees)
    turn = math.radians(degrees)
    straight = (spacing - 2 * across) / math.sin(turn)
    if straight < 0:
        raise ValueError("%.1f degrees on radius %.0f already moves further than the %.1f between the tracks"
                         % (degrees, radius, spacing))
    return 2 * along + straight * math.cos(turn), straight
