# Platelayer

Lays [Immersive Railroading](https://www.curseforge.com/minecraft/mc-mods/immersive-railroading) track along a line
drawn somewhere else — a curve in Blender, a survey, anything that can be written down as points. Minecraft 1.12.2,
Forge.

A *platelayer* is the railway worker who lays and keeps the track.

Author: Aleksei Usenko (arthaix). All rights reserved: you may use the released jar, but not modify or redistribute it
(see LICENSE).

## Why

Laying a railway by hand means clicking out a blueprint every few dozen blocks and hoping the curve comes out the way
it was designed. If the alignment already exists in a 3D editor — and for anything built from a model it does — the
track can simply follow it.

## What it does

- **Follows the drawing.** Every piece is built as Immersive Railroading's own curve between two points, meeting the
  line's direction at both ends. A tolerance you set (a centimetre by default) is the furthest the track may ever be
  from the drawn line.
- **Lays it the way rail is laid.** A piece stretches as far as the shape allows — hundreds of blocks down the
  straights, shorter through the curves — instead of being chopped at a fixed step. A 15 km line takes about 165
  pieces at a centimetre of tolerance; a fixed 16-block step takes 836 and is six times further off.
- **Keeps the server running.** The work goes in a few pieces per tick, with progress in chat, and can be stopped
  at any point.
- **Takes the blueprint you hold** for the gauge, the rail bed and the track style. The shape comes from the line, not
  from the blueprint's type and length.
- **Clears first, if asked.** `clear` takes the old track out along the whole line before laying.

Immersive Railroading is reached by reflection only. Platelayer does not compile against it and does nothing where it
is absent.

## Using it

**1. Export the line.** With Blender closed:

```
blender -b railway.blend --factory-startup -P tools/export_curves.py -- track.json
```

Every curve object becomes an entry. Put `track.json` in `config/platelayer/lines` on the server.

**2. Check what came out:**

```
/platelayer list
```

**3. Lay it.** Hold a track blueprint with the gauge, bed and style you want, then:

```
/platelayer lay track all ~ ~ ~ clear
```

`~ ~ ~` anchors the drawing's origin to where you stand; explicit coordinates work too. Before it starts, the command
says how many blocks of line, how many pieces, their lengths and the tolerance.

### Options

Any order, after the coordinates:

| Option | Meaning |
| --- | --- |
| `200` | longest piece in blocks (default 200) |
| `0.01` | how far a piece may stray from the drawing, in blocks (default 0.01) |
| `clear` | take old Immersive Railroading track out along the line first |
| `yup` | the drawing already has Y up (Blender and most CAD have Z up) |
| `turn1`, `turn2`, `turn3` | quarter turns around the anchor |
| `x2`, `x0.5` | scale the drawing |
| `0-2500` | lay only that stretch along the line, in blocks |

`/platelayer stop` ends a run that is under way.

## The line file

```json
{
  "Track 1": [[[0, 0, 0], [10, 0, 0], [20, 1, 0]]],
  "Track 2": [[[0, 5, 0], [10, 5, 0]]]
}
```

A name, then its lines, then their points. Anything that can write that can feed Platelayer — the Blender script is
only the convenient way in.

## Building

```
./gradlew build
```

Needs a JDK for Gradle and network access the first time (RetroFuturaGradle sets up the Minecraft workspace). The jar
lands in `build/libs`.
