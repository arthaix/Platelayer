"""Exports the curves of a Blender file as a line file for Platelayer.

Run it without opening Blender:

    blender -b railway.blend --factory-startup -P export_curves.py -- out.json

Or, to take only some of the curves:

    blender -b railway.blend --factory-startup -P export_curves.py -- out.json "Track 1" "Track 2"

Every curve object becomes one entry, its splines become the lines under it, and a curve is written in Blender's own
coordinates with the object's transform applied. Put the file in config/platelayer/lines on the server and lay it:

    /platelayer lay out track1 ~ ~ ~ clear

A curve drawn around Blender's origin lands on the block you stand on. Platelayer assumes Z points up, as Blender
does; add "yup" to the command for a drawing that already has Y up.
"""

import json
import sys

import bpy


def wanted(argv):
    """Everything after the -- of the Blender command line: the output file, then optional curve names."""
    args = argv[argv.index("--") + 1:] if "--" in argv else []
    if not args:
        raise SystemExit("give the output file: blender -b file.blend -P export_curves.py -- out.json [curve names]")
    return args[0], set(args[1:])


def points_of(curve_object):
    """Every spline of the curve as a list of world-space points, in the order they are drawn."""
    matrix = curve_object.matrix_world
    splines = []
    for spline in curve_object.data.splines:
        vertices = spline.bezier_points if spline.type == "BEZIER" else spline.points
        points = [tuple(matrix @ v.co.to_3d()) for v in vertices]
        if len(points) >= 2:
            splines.append([[round(c, 4) for c in p] for p in points])
    return splines


def main():
    out_path, names = wanted(sys.argv)
    lines = {}
    for ob in bpy.data.objects:
        if ob.type != "CURVE":
            continue
        if names and ob.name not in names:
            continue
        splines = points_of(ob)
        if splines:
            lines[ob.name] = splines
    if not lines:
        raise SystemExit("no curves found with those names" if names else "no curves in this file")
    with open(out_path, "w", encoding="utf-8") as f:
        json.dump(lines, f, ensure_ascii=False)
    for name, splines in lines.items():
        length = 0.0
        for spline in splines:
            for a, b in zip(spline, spline[1:]):
                length += sum((b[i] - a[i]) ** 2 for i in range(3)) ** 0.5
        print("%s: %d line(s), %d points, %.0f long" % (name, len(splines), sum(len(s) for s in splines), length))
    print("written to", out_path)


if __name__ == "__main__":
    main()
