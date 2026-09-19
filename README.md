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
| `clearonly` | take it out and stop there, laying nothing |
| `over` | lay through track that is already there — see *Crossovers* |
| `yup` | the drawing already has Y up (Blender and most CAD have Z up) |
| `turn1`, `turn2`, `turn3` | quarter turns around the anchor |
| `x2`, `x0.5` | scale the drawing |
| `0-2500` | lay only that stretch along the line, in blocks |

`/platelayer stop` ends a run that is under way.

## Crossovers and branches

Two tools draw the shapes a plain curve cannot give you. Both add their lines to an existing line file and print
what came out — the length, the sharpest radius, where it sits.

```
python tools/crossover.py track.json "Track 1" "Track 2" --at-end 2225 --length 240
python tools/branch.py    track.json "Track 1" --at-end 1750 --length 400 --turn 0 --drop 5
```

A crossover's two connections leave and join along each track's own direction, and the sideways move follows a
smooth curve rather than a corner, so the sharpest radius is the length squared over six times the track spacing —
240 blocks between tracks 5 apart comes out at about 1900. A branch leaves pointing the way its track points and
bends away by the degrees asked for; `--drop` brings it down to the ground it is heading for, holding the height
until it is clear of the track it left and then falling at the gradient given.

A branch usually needs ground to stand on, and `tools/embankment.py` draws it in the shape of the one already
under the line: it measures nothing itself, you give it the numbers off your own earthwork (top width, shoulder,
side slope), and it sweeps that section along the branch, cut off at the old embankment's shoulder so the two meet
without a trench and nothing is drawn where the ground is already there. The result is a Wavefront .obj in the
drawing's own coordinates, to be placed the same way the rest of the model is.

```
python tools/embankment.py branches.json branch_north branch_south --line track.json --out fill.obj
```

Laying either one needs `over`. Immersive Railroading reserves four blocks of width for a track, and a double track
is commonly five apart, so every part of a connection falls inside one track or the other. A piece is one anchor
block and a crowd of gag blocks, and a gag gives way to a builder that says it may — only the anchor is held. So the
connections are laid over the tracks they join, the way a turnout shares ground with the line it leaves, and the
only thing that must be kept out of the way is the anchor of a piece already there. Lay the plain line in two
commands with `clearonly` first and a `from-to` boundary of your choosing, and its anchors land where you put them.

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
