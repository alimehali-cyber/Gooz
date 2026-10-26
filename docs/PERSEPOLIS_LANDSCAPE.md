# Persepolis + Mil-e Azhdaha in the Home Landscape

**Scope:** the full-bleed Home sky scene (`HeroSkyCanvas`, `SkyCanvasPresentation.SCENE`), on the two
photographic themes. The ground band's subjects are now two Iranian stone monuments in one supplied
fixed-camera composition: the Apadana east façade at Persepolis in the left distance, and the
Mil-e Azhdaha fire-temple tower near the centre, standing on its stepped limestone hillside. Both are
rendered as lit procedural 3D reliefs by the same real Sun and Moon that light the sky behind them.
Persepolis retains the approved half-size, leftward and farther-back placement from the previous
iteration; a footprint-local earthwork in the painted ground seats both the cut-back platform and
the nearer tower slope in the terrain.

```bash
./gradlew :app:testDebugUnitTest --tests '*PersepolisFramingTest*'
python3 tools/persepolis/verify_framing.py
```

![Framing diagram: the combined model's massing projected with its own camera over the scene's ground stack](persepolis-framing.png)

*Framing proof, not a screenshot: the Persepolis frames/jambs/columns, the Mil-e Azhdaha tower and
the nine fixed sheets of its slope as flat blocks, projected with the model's fixed lookAt/ortho
camera at the framing ZIG computes, over the scene's ridges, mist and ground at 360 × 420 dp. On
device the GL layer renders the full procedural relief — fluted drums, composite capitals, relief
registers, ashlar tower courses and two procedural limestone shaders. Regenerate with
`tools/persepolis/render_framing_preview.py`.*

---

## 1. What was integrated, and how exactly

The replacement arrived as one self-contained Kotlin file (`PersepolisMilAzhdaha.kt`: OpenGL ES,
no assets, geometry generated procedurally into static VBOs — 148,985 triangles / 165,492 vertices —
with one fixed camera and all "3D behaviour" in the lighting). It now lives at
`app/src/main/java/com/alijafari/red/astronomy/ui/persepolis/PersepolisMilAzhdaha.kt`,
**integrated exactly** — same builders, same element tables, same combined-scene transforms, same
two GLSL fragment shaders, same camera and same Sun/Moon light derivation — with six changes, each
recorded in the file's header note:

| # | Change | Why |
|---|---|---|
| 1 | Package line | As the file's own first line instructed |
| 2 | `PersepolisMilAzhdahaScene.init()` drops stale GL names before rebuilding | `init()` runs per fresh EGL context; Home's scene is a `LazyColumn` item, so the surface *will* be destroyed and recreated on scroll. Unpatched, a later `init()` appended second chunk sets for **both** programs and drew buffer names from the dead context |
| 3 | CPU mesh arrays for both monument groups memoised per process | Each `MeshBuilder` is deterministic (fixed LCG seed), and the caches preserve the supplied Persepolis-then-Mil build order and baked transforms, so the arrays are bit-identical; only VBO uploads repeat per context. Scroll-back stays near-instant instead of rebuilding ~149 k triangles on the GL thread. Roughly 8 MB retained |
| 4 | `setPreserveEGLContextOnPause(true)` | Backgrounding the app need not re-upload static VBOs where the driver keeps the context; change 2 covers the rest |
| 5 | GL calls via the ES 3.0 API on an ES 3.0 context (was ES 2.0) | The app declares ES 3.0 in its manifest and keeps every renderer on it, guarded by `AnimationRecordPrecisionTest.legacyRenderersStayOnGles3…`. ES 3.0 compiles the supplied GLSL ES 1.00 shaders unchanged; this is an Android API/context change, not a shader rewrite |
| 6 | Optional `centerFractionFromLeft` framing input | Places the unchanged fixed-camera combined group left of centre by shifting only the orthographic window. The default remains 0.5 (the supplied centred framing); ZIG passes 0.30 for the Home landscape |

The combined file's own placement is preserved: Persepolis is baked at `PERSEPOLIS_X = -9.7 m`, its
empty platform wings are cut back, and the Mil group is baked at 2.04× scale, −0.20 rad yaw and
translation `(40.7, 1.1, 15.6)`. The retained Mil geometry is the tower, its local rubble/sand/scrub
foot group and `milSlope()`; the standalone wide bedrock pavement and ledge set excluded by the
combined builder stay excluded.

Hosting uses the file's **Option A** — its own `PersepolisMilAzhdahaView`, a transparent
`GLSurfaceView` (RGBA8888 + depth, `RENDERMODE_WHEN_DIRTY`) composited over the sky — wrapped by
`PersepolisLandscape` (`ui/persepolis/PersepolisLandscape.kt`) and inserted in `HeroSkyCanvas`
between the sky canvas and the cloud-motion canvas. Option B (drawing the scene inside a host GL
renderer) was not used: the Home sky is a Compose `Canvas`, not a GL renderer, and Option A is the
integration the file designs for exactly this situation.

**What did not change:** the Compose ground band ([`SceneGroundRenderer`](HOME_SKY_SCENE.md)) is
painted underneath the GL stone — far ridge, mist shelf, mid ridge, ground, grass — because it is
the monuments' environment: the ridges and mist are their distance backdrop, and the field below
their feet is the foreground. The fullscreen viewer (`HOME_HERO`/`LandscapeRenderer`), the Live Sky
backdrop, and the four stylised themes are untouched.

### Theme gate

`PersepolisFeature.isEnabledFor` (mirroring `SkyPanoramaFeature`) enables the layer for **Real Sky**
(the default theme) and **Atmospheric Sky** — the two photographic themes realistically lit
limestone reliefs belong to. Papercraft, Kids Watercolor and Monochrome Scientific are deliberate
flat styles and keep their ground; Observatory exists to emit nothing but red light, and a
full-colour GL layer would defeat it. The grounding paint in the sky pass reads the same gate, so
disabling the feature removes every trace of it.

## 2. The lighting feed

No new astronomy. `SkySceneFrame` already computes, for the exact instant the sky is drawn:

- `sunHoriz` — Sun azimuth/altitude from `CoordinateEngine.equatorialToHorizontal`, azimuth measured
  **from North = 0°, East = 90°** — precisely the convention
  `PersepolisMilAzhdahaView.setSun` takes (its world axes are +X = north, +Z = east;
  `dirFromAzAlt` needs no conversion);
- `moonData` — Moon azimuth/altitude/illumination from `MoonEngine`.

`PersepolisLandscape` forwards both, with the Moon's intensity scaled by the illuminated fraction
as the file prescribes (`0.22 × fraction`, `persepolisMoonIntensity`). One shared light state feeds
both monument programs: Persepolis keeps its cooler distant-limestone fragment response, while
Mil-e Azhdaha keeps its warmer close-range stone shader with noise-gradient bump, tooling, desert
varnish, salt, calcite and lichen detail. Colour, intensity, twilight fade and hemisphere ambient
are derived inside `PersepolisMilAzhdahaScene.setSun` from altitude, exactly as supplied — in
agreement with the Compose sky's own `LightingState`, because both read the same Sun altitude.

Updates are `LaunchedEffect`s keyed on the ephemeris values, so the GL view renders **one frame per
sky-model change**: every 15 s bucket at idle (zero cost between), per frame while the finger
scrubs time (the light visibly sweeps across both ruins with the sky), and on location or theme
change. The 24 fps cloud clock never touches it.

## 3. Framing — `PersepolisFraming`

`ui/rendering/PersepolisFraming.kt` (pure pixel arithmetic, `SkySceneLayout` style) turns the scene
size into the view's three inputs (`visibleWidthMeters`, `baseFractionFromTop`,
`centerFractionFromLeft`) and the platform/slope pixel bounds used by the ground painter. Rules, in
priority order:

1. **Exactly half the first Persepolis linear size, retained.** The previous framing's visible
   width — the earlier model's 89 m window on phones, or the aspect-grown width required by its
   34 % building-height cap on wide scenes — is still doubled. The combined file's own 116 m
   default is therefore deliberately not used in Home: it would undo the approved 50 % reduction.
2. **A left-hand combined group.** The combined-frame anchor `(0, 0, -2)` sits at 0.30 of the
   width. Exact projection puts Persepolis' detailed façade at about 2–50 % of a phone scene; its
   cut-back platform ends near 53 %. The tower centre is at 54.1 %, and its slope spans
   39.6–73.2 %, leaving the right quarter open. On wider scenes the same group contracts toward
   the left/centre: the slope ends at 55.8 % on the tested tablet and 51.6 % on the wide-short
   scene.
3. **Farther back in the ground plane.** The Persepolis toe targets 0.098 of the scene below the
   horizon — about 0.798 of the scene — as before. The platform top and slope top stay below the
   horizon, both tall Persepolis columns still cross it, and a deep foreground field remains in
   front of the stone. The Mil slope is nearer the camera: its fixed front step projects about
   1.55 view-metres below the Persepolis toe, so the framing exposes a separate slope-front row
   instead of forcing both monuments onto one contact line.
4. **No stone over the readout.** The GL surface composites *above* its Compose siblings. The toe
   is lowered only as needed until the tallest Persepolis structure entering the protected
   250 dp readout width clears its bottom edge by a nominal 5 dp; every guard x is now in
   combined-scene world coordinates after the −9.7 m bake. The tower needs no separate Kotlin
   guard because it is lower than and to the right of the tallest Persepolis column, but the
   exact-projection verifier checks the tower explicitly. The binding exact clearance across the
   size matrix is **+3.5…+6.2 dp**.

The Persepolis pixel landmarks continue to use the isotropic `widthPx / visibleWidthMeters`
approximation. The Mil tower and slope instead use precomputed fixed-camera **view-space offsets**:
the tower's baked world centre `(40.7, 15.6)` in XZ projects to +42.91 view-metres from the anchor,
and the nine fixed slope sheets project to +17.12…+76.85 view-metres. In world terms those sheets
span x 13.68…75.72 m, y 0…6.06 m and z 0.39…43.24 m; their bottoms land on world y = 0. Using the
view-space values keeps the painted contact patch registered with the nearer slope to within
0.02 dp in the exact verifier, rather than allowing a multi-metre depth-coupling error.

Measured results (exact lookAt/ortho, `verify_framing.py`):

| Scene | Visible width | Toe at | Sky silhouette, two tall columns | Slope span | Binding readout clearance |
|---|---|---|---|---|---|
| 320 × 380 dp | 178.0 m | 0.798 | 6.3 / 0.2 dp above horizon | 0.396–0.732 | tallest column +5.7 dp |
| 360 × 420 dp | 178.0 m | 0.798 | 7.9 / 0.9 dp | 0.396–0.732 | tallest column +4.1 dp |
| 430 × 480 dp | 178.0 m | 0.804 | 8.5 / 0.3 dp | 0.396–0.732 | tallest column +3.5 dp |
| 360 × 312 dp | 178.0 m | 0.831 | 8.3 / 1.4 dp | 0.396–0.732 | tallest column +3.7 dp |
| 1200 × 560 dp | 297.5 m | 0.798 | 42.9 / 29.1 dp | 0.358–0.558 | great doorway +6.2 dp |
| 800 × 312 dp | 356.0 m | 0.799 | 23.4 / 15.7 dp | 0.348–0.516 | centre stack +3.8 dp |

The framing also guarantees the scene's internal `autoFitHeight` never widens the view past the
chosen width, so the scale the ground painter assumes is the scale the camera uses.

### Grounding (why it does not look pasted)

A transparent overlay ends on a hard line unless the host paints the contact. `SkySceneRenderer`
passes the full framing to `SceneGroundRenderer.drawCombinedLandscapeGrounding`, which paints one
worn archaeological site around both footprints, after the ground gradient and before the grass:

- an **irregular worn-earth apron**, with wavy top/bottom edges and both vertical and horizontal
  feathering, strongest at the platform toe and dissolving down and to the right into the field;
- an **earth bank** against the slope's open right/upslope side, spilling toward the viewer so the
  stepped limestone sheets do not terminate as clipped rectangles;
- a pale **limestone-dust wash** immediately in front of the slope, so its sheets appear to shed
  their own grit onto the darker field;
- one continuous **broken contact shadow** across the overlapping Persepolis/slope footprint,
  plus a deeper soft contact crescent at the slope's nearer front step;
- low, soft **drift hummocks** and deterministic light/dark **debris flecks**, concentrated near
  the stone and thinning into the foreground.

The soft drifts are built from many equal-alpha shells whose source-over accumulation reaches the
intended peak gradually; earlier few-step ellipses produced visible concentric contour rings and
were removed. Colours are mixed from the active ground palette toward sand and scaled by
`LightingState.ambientBrightness`, so the patch follows the terrain's warmth and brightness and
vanishes into astronomical night with it. Exact projection puts the forecourt front edge 0.6–1.3 dp
above the toe row and the slope front inside the contact-shadow band on every tested size. The
grass then paints over the foreground part of the patch, tying the site back into the field.

## 4. Lifecycle, cost, interaction

- `PersepolisLandscape` forwards `ON_RESUME`/`ON_PAUSE` to the `GLSurfaceView` from the composition's
  lifecycle owner and frees both program/chunk sets on the GL thread in `onRelease`. First surface
  creation runs the two procedural builds on the GL thread while the sky and ground are already
  visible through the transparent surface; every later creation re-uploads from memoised arrays.
- The view is non-clickable/non-focusable and consumes no touches: tap-to-identify and finger time
  travel on the canvas are unaffected (its geometry is not hit-tested — a tap on a star projected
  over a column still identifies the star).
- Per-repaint cost is the file's own: two shader programs drawing their static ≤60 k-vertex VBO
  chunks, on demand. At idle that is one frame per 15 s model bucket; the surface does no work
  between light changes.

### Known trade-offs (honest list)

- **Surface above siblings.** `setZOrderOnTop` places the GL surface above the Compose layers
  inside its bounds. Framing (§3.4) keeps stone off the readout and header; what remains beneath
  is where it belongs physically — cloud veils drift behind the ruins, and a near-horizon selection
  ring is occluded by the stone that occludes its object. If that ever stops being acceptable, the
  upgrade path is the file's Option B: host the untouched scene on a `TextureView` the way
  `SkyPanoramaSurfaceView` hosts the panorama, gaining true Compose z-order at the cost of a
  hand-rolled EGL thread. The scene's `RectangleShape` clip is why the surface needs no clipping
  here, unlike the panorama's squircle card.
- **Scroll.** The scene is the first `LazyColumn` item; a `SurfaceView` layer follows its window
  position per frame on current Android, but can lag a frame behind fast flings on some devices.
  The TextureView path above removes that too.
- **Fixed combined view.** One fixed camera and the supplied composite placement. Persepolis and
  the Nurabad-area Mil-e Azhdaha are real places, but this is not a literal single viewpoint from
  which both are visible together, and it is not tied to the observer's location. It is the Home
  scene's archaeological landscape composition, crossed by the Sun and Moon of the observer's own
  sky; it must not be described as the view from the user's location or as the monuments'
  geographic relationship.
- **No cast shadows.** Depth occlusion is real and both shaders model direct/ambient response;
  monument-on-ground or column-on-platform shadow maps would need an additional pass around the
  supplied draw calls if they are ever wanted.

## 5. Verification

- `PersepolisFramingTest` (JVM): the retained half scale, left placement, distant toe,
  protected-readout rules, cut-back platform and Mil slope/tower footprint ordering over six scene
  sizes, the theme gate, and the moonlight scaling.
- `tools/persepolis/verify_framing.py` (stdlib): replicates `Matrix.setLookAtM`/`orthoM` and
  `PersepolisMilAzhdahaScene.draw`'s centre-solving (including the 0.30 horizontal offset),
  projects the toe, anchor, forecourt edge, platform top/corners, tall columns, doorway, façade
  ends, tower top and all corners of the slope's nine fixed sheets through the framing. It
  transforms those slope corners with the file's baked Mil scale/yaw/translation rather than
  trusting the Kotlin constants, and checks every tall landmark — the tower included — that enters
  the readout's protected width. Measured Persepolis approximation error across the matrix is
  ≤ 2.6 dp for the column, ≤ 1.3 dp for the doorway, ≤ 0.7 dp for the platform top and ≤ 6.1 dp
  for the platform's right edge; tower/slope landmark error is ≤ 0.02 dp.
- `tools/persepolis/render_framing_preview.py` (Pillow): regenerates the diagram above.
- The GL code itself needs a device or emulator: `assembleDebug`, open Home on Real Sky, scrub
  time across sunrise/noon/sunset/night, scroll the feed away and back (surface recreation via
  changes 2/3), background and resume the app, switch to Papercraft and back (gate).

## 6. Tuning

- On/off and themes: `PersepolisFeature` (`ui/persepolis/PersepolisLandscape.kt`).
- Linear scale, horizontal centre, distant-toe position, slope footprint and readout clearance:
  `PersepolisFraming` constants (`LINEAR_SCALE_FRACTION`, `CENTER_FRACTION_FROM_LEFT`,
  `DISTANT_TOE_BELOW_HORIZON_FRACTION`, `MIL_SLOPE_*`, `READOUT_CLEARANCE_DP`).
- Worn-earth, slope bank/dust, shadows, hummock and debris strength/reach:
  `SceneGroundRenderer.drawCombinedLandscapeGrounding`.
- Monument transforms and light character: inside `PersepolisMilAzhdaha.kt` — changing these
  changes the supplied model, so prefer the host-side knobs above.
