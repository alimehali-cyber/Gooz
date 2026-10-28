# Home Sky Scene — full-bleed hero

**Scope:** Home only. The sky canvas stopped being a card and became the top of the screen: one
edge-to-edge scene that runs behind the status bar and carries the header and the readout. Directly
beneath it sit two square glass cards — the Moon and the next eclipse — and everything else on Home
starts below them.

No weather data was added. Every number and every layer is derived from what ZIG already computes:
`SkySceneFrame` (Sun, Moon, planets, stars, Milky Way), `LightingState` (Sun altitude, star visibility,
moon glow) and the scene clock.

![Home sky scene, night and daylight](home-sky-scene.png)

*The same composition at astronomical night and at noon, rendered from the constants below: the same
edge-to-edge scene, the readout at caption scale above the horizon, and the pair of 40 dp squircle cards
(the Moon, the next eclipse) sitting underneath the canvas. The clouds and the mist follow the theme; the
Sun altitude drives the light.*

---

## 1. Composition

```
┌──────────────────────────────────────────┐ 0
│  status bar (scene continues behind)     │
│  ZIG            ⌕  Settings               │  header (HomeScreen)
│  ◉ Tehran • 35.69°N, 51.39°E             │
│                                          │
│  22:41 │ Astronomical night              │  readout (canvas)
│  2 Aban · Moon 64% up                    │
├──────────────────────────────────────────┤ ← horizon  (0.70 × scene)
│  distant ridge · ridge                   │
│  grass line + tufts            ⤢         │ ← expand affordance
└──────────────────────────────────────────┘ scene height = 52 % of viewport
  ╭────────────────────╮ ╭────────────────────╮
  │                    │ │      \   |   /     │   two square cards,
  │        ◗           │ │       ◐ corona     │   same size, same
  │   the Moon as it   │ │   the next eclipse │   gutter as the feed
  │      is now        │ │   drawn for its type│
  ╰────────────────────╯ ╰────────────────────╯
   What's Up Tonight …                      feed
```

Constants live in `SkySceneLayout` (`ui/rendering/SkySceneComposition.kt`):

| Constant | Value | Meaning |
|---|---|---|
| `HORIZON_FRACTION` | 0.70 | where altitude 0° lands inside the scene (≈ 39 % of the screen) |
| `GROUND_TOP_FRACTION` | 0.052 | solid ground starts this far below the horizon |
| `FAR_RIDGE_FRACTION` / `MID_RIDGE_FRACTION` | 0.034 / 0.020 | ridge amplitudes |

`HomeScreen` computes the height (`52 %` of the viewport, clamped to 312–560 dp) outside the
`LazyColumn`, because lazy items are measured with an unbounded height. The same `BoxWithConstraints`
computes the pair's size: half the feed width minus the two gutters and the gap, clamped to 132–210 dp,
so the two cards are square on every screen.

### The pair of cards

| Card | Shows | Gesture |
|---|---|---|
| Moon | `MoonPhaseRender` at full tile size and nothing else — no phase name, no percentage, no date | drag scrubs ±30 days (shared with the Moon window); tap opens the Moon window |
| Eclipse | `EclipseRender`, drawn for the type of the *next* eclipse at this location | tap opens the eclipse window for that eclipse |

Both are `squircleShape(RedCornerRadius.window)` glass tiles of identical size, so the pair reads as one
band. Neither has a caption: the numbers belong to the readout on the sky above and to the windows the
cards open, and a label on a card this small would cover the only thing worth looking at.

The eclipse tile stands for the soonest eclipse of **either** family, not for the family the "Upcoming
Eclipses" list below happens to be showing. A hero tile that changed when a chip further down the feed
was tapped would be a surprise; the next eclipse is a fact about the sky, not about the list.

`EclipseRender` draws the two families differently, because they are two different physical pictures:

- **solar** — drawn, because the subject is light, not a surface: a limb-brightened corona with ~44
  deterministic streamers of varying length and width, the chromosphere ring, and a diamond ring at one
  point of the limb for totality; a ring of fire with Baily's beads for annularity; two overlapping discs
  for a partial.
- **lunar** — the app's real Moon photograph, shaded by a radial gradient applied with `BlendMode.Multiply`
  inside an offscreen layer. Multiplying keeps the maria visible (it darkens the photograph instead of
  painting over it), and where the gradient is centred is what makes totality read as a copper disc with
  a bright limb while a partial eclipse keeps one limb in full sunlight.

## 2. Layers

Drawn in `HeroSkyCanvas`, which now has three independent Compose draw nodes plus, on the
photographic themes, the Persepolis GL layer between the first two:

1. **Sky** (`SkySceneRenderer.drawSky`) — atmosphere, Milky Way, stars, Sun, Moon, planets, ground.
   Repaints on the 1 Hz shared clock, or per frame while the finger scrubs time.
1b. **Persepolis + Mil-e Azhdaha** (`PersepolisLandscape`, photographic scene only) — the Apadana
   relief at half its first linear size in the left distance and the Mil-e Azhdaha tower on its
   limestone slope at the far right, both lit by the same ephemeris and rendered on demand. See
   [`PERSEPOLIS_LANDSCAPE.md`](PERSEPOLIS_LANDSCAPE.md).
2. **Motion** (`SkySceneRenderer.drawSceneMotion`, scene only) — the drifting cloud veils.
   ~24 fps while idle. Separate node, so the astronomy is never repainted for a drifting cloud.
3. **Interaction** — selection ring and stardust. Only alive while the user is touching the canvas.

### Ground — `SceneGroundRenderer`

Two art paths. The **stylised themes** keep the original band — far ridge → mid ridge →
solid ground gradient → horizon catch-light → grass tufts → bottom fade — with summed-sine ridges
sampled through quadratics and deterministic grass (Papercraft keeps hard cardstock edges and skips
the strokes). (The old full-width horizon "mist shelf" was removed from both paths: it read as a
white band across the canvas at the readout's day/date line.)

When the combined relief is active (the photographic themes), the band becomes the full **Marvdasht
landscape** and is painted underneath the GL stone: the far Mamasani fold range and the
Dimeh-Mil / Mil-e Azhdaha massif of Nurabad (Mamasani, Fars) — the tower's real backdrop,
cut by the Tang-e Farakh gorge — built from domain-warped ridged-multifractal profiles
(`LandscapeProfile`) with Sun-facing facet
shading, bedrock benching, a water-gap, strata dipping off the fold hinge, crest caprock, talus
cones, cliff streaks, erosion gullies and aerial perspective; piedmont foothills and alluvial
fans; a semi-arid steppe/cultivation plain with an irrigation canal; two separate footprint-local
earthworks from `PersepolisFraming` (worn apron, talus, contact shadows, debris — ordinary field
between the sites, feathered and Sun-following, so neither monument ends on a full-width stripe);
a broad layered Pulvar river (wet banks, shallows, thalweg, point bars, tree reflections, reed
clumps) whose sheen and thinned glitter track the real Sun/Moon; a 68-tree space-colonization
parkland (pistachio, *Quercus brantii* oak, flowering cherry, lavender-panicle Paulownia)
plus *Artemisia* sage scrub with pipe-model branching and leaf-cluster foliage; low
whaleback Nurabad ridges at true relief; and a uniform grass field — one textured
cool-green mass in eight bucketed fills, no individual blades, lush along the near bank.
All placement is deterministic. See
[`PERSEPOLIS_LANDSCAPE.md`](PERSEPOLIS_LANDSCAPE.md).

### Clouds — `CloudVeilRenderer`

Three veils (high cirrus, mid, low) at different speeds and parallax depths. Pre-rendered once into
two soft alpha sprites, drawn as tinted images, so a frame costs a handful of textured quads instead
of dozens of radial gradients. Tint and strength come from `ScenePalette`: Papercraft opts out (it has
its own cut-outs), Real Sky keeps a faint veil so the photograph still moves, and the strength fades at
night so a star map is never veiled into a cloudy sky.

**These are atmosphere, not a forecast.** ZIG has no weather feed; the layer must never be described as
cloud cover.

### Readout — caption scale

The typographic block sits above the horizon on the left, at caption scale, because the scene is the
product and the instant it is drawn for is the caption:

- the clock at 26 sp medium (it used to be 46 sp light, which competed with the sky);
- a 1 dp vertical hairline separating *when* from *what*;
- the sky's own state at 11 sp;
- the date and the Moon at 10 sp, on one line.

A small accent dot appears between the hairline and the state while the finger is scrubbing time, so a
simulated instant is marked without adding a caption nobody reads mid-gesture. Every value comes from
the same state the sky is drawn from, so the numbers and the sky can never disagree.

## 3. Projection

`SkyRenderTarget.HOME_SCENE` uses `SceneProjection(SkySceneLayout.HORIZON_FRACTION)` — the same
cylindrical, equator-facing Home mapping, with the horizon as an argument instead of the 0.85 constant.
`HeroProjection` is now `SkyProjection by SceneProjection()`, so the card, the fullscreen viewer and the
Live Sky backdrop are unchanged. `SkyProjection.horizonY(w, h)` was added so every layer reads the
horizon from the surface that owns it.

## 4. Measuring it

```bash
./gradlew :app:testDebugUnitTest --tests '*SkyProjectionTest*'
```

`theFullBleedSceneMovesTheHorizonAndKeepsTheSkyMapping` asserts the horizon moved, the mapping did not,
and that the ground band sits below the horizon.

## 5. Tuning

- Taller / shorter scene: `HomeSkyHero` (0.52) and the 312–560 dp clamp in `HomeScreen`.
- Sky-to-ground balance: `SkySceneLayout.HORIZON_FRACTION`.
- Size of the two cards: `heroCardSize` in `HomeScreen`. They are square by construction — one formula
  sets both their width and their height — and they never overlap the canvas, so the whole ground band
  stays visible.
- Cloud presence per theme: `ScenePalette.cloudStrength`.
- Motion cost: `SCENE_MOTION_FRAME_MS` in `HeroSkyCanvas` (42 ms ≈ 24 fps).
- Scene tail: the scene does not end at the canvas's bottom edge. The ground's own bottom
  colour continues *below* the canvas and dissolves in alpha to full transparency over
  `bottomTailHeight` (Home passes `cardSize + RedSpacing.md`, the card row's height), so
  the tail passes underneath the Moon and eclipse cards. Fading in alpha rather than to a
  colour is what makes it backdrop-agnostic: it merges into the theme background, the
  live-sky backdrop render (`LiveSkyBackdrop`), or any other app backdrop unchanged. The
  canvas itself stays crisp to its edge — nothing fades upward into it. (See "SCENE TAIL"
  in `HeroSkyCanvas`; the scene root is deliberately unclipped so the tail can paint
  there.) Static landscape geometry is cached per layout — see `VegetationGeometry` in
  `SceneGroundRenderer`.
- Horizon bands are banned: any full-width pale rect with a straight edge at the horizon
  (the old mist shelf, then the "horizon catch-light" wash in both ground paths) reads as
  a white band across the scene at the readout's day/date line, flaring while scrubbing
  sweeps through daytime. The same applies to *haze washes*: the far range used to be
  semi-transparent and lerped 55% toward a near-white `palette.haze` at its foot (plus a
  "haze pooling" rect at the massif foot), so at midday the range and the sky bleeding
  through it formed one glowing white slab on the horizon. The ranges are now opaque and
  only lightly hazed, the palette haze's daylight mix is capped, and no pooling rect
  remains. Only the ridges' own silhouettes may cross the horizon.
