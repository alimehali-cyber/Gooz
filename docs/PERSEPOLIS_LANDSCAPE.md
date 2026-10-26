# Persepolis in the Home Landscape

**Scope:** the full-bleed Home sky scene (`HeroSkyCanvas`, `SkyCanvasPresentation.SCENE`), on the two
photographic themes. The ground band's subject is now a real place: the Apadana east façade at
Persepolis — the surviving fluted columns of the east portico, the monolithic stone door/window
frames of the palace group, the relief-carved east-stair cheek and the two-tier platform — rendered
at half the first integration's linear size, in the left distance, as a lit 3D relief by the real Sun
and Moon that light the sky behind it. A footprint-local earthwork in the painted ground makes the
platform read as part of the terrain rather than as an overlay pasted across it.

```bash
./gradlew :app:testDebugUnitTest --tests '*PersepolisFramingTest*'
python3 tools/persepolis/verify_framing.py
```

![Framing diagram: the model's massing projected with its own camera over the scene's ground stack](persepolis-framing.png)

*Framing proof, not a screenshot: every frame, jamb and column from the model's element tables as a
flat block, projected with the model's fixed lookAt/ortho camera at the framing ZIG computes, over the
scene's ridges, mist and ground at 360 × 420 dp. On device the GL layer renders the full procedural
relief — fluted drums, composite capitals, relief registers, limestone grain. Regenerate with
`tools/persepolis/render_framing_preview.py`.*

---

## 1. What was integrated, and how exactly

The model arrived as one self-contained Kotlin file (`Persepolis.kt`: OpenGL ES, no assets, the
geometry generated procedurally into static VBOs — 139,517 triangles — with a fixed camera from the
south-east and all "3D behaviour" in the lighting). It now lives at
`app/src/main/java/com/alijafari/red/astronomy/ui/persepolis/Persepolis.kt`, **integrated exactly** —
same geometry builder, same element tables, same GLSL (Lambert + hemisphere ambient + specular/rim +
procedural limestone: tonal patches, grain, rain streaks, bedding planes, settled dust), same camera,
same Sun/Moon light derivation — with six changes, each recorded in the file's header note:

| # | Change | Why |
|---|---|---|
| 1 | Package line | As the file's own first line instructed |
| 2 | `PersepolisScene.init()` drops stale GL names before rebuilding | `init()` runs per fresh EGL context; Home's scene is a `LazyColumn` item, so the surface *will* be destroyed and recreated on scroll. Unpatched, the second `init()` appended a second chunk set and drew buffer names from the dead context |
| 3 | CPU mesh arrays memoised per process | `buildScene()` is deterministic (fixed LCG seed), so the arrays are bit-identical; only the VBO upload repeats per context. Scroll-back-to-top stays near-instant instead of rebuilding ~140 k triangles on the GL thread. ~7 MB retained |
| 4 | `setPreserveEGLContextOnPause(true)` | Backgrounding the app need not re-upload static VBOs where the driver keeps the context; change 2 covers the rest |
| 5 | GL calls via the ES 3.0 API on an ES 3.0 context (was ES 2.0) | The app declares ES 3.0 in its manifest and keeps every renderer on it, guarded by `AnimationRecordPrecisionTest.legacyRenderersStayOnGles3…` — which duly failed the first CI run of this change. ES 3.0 is a strict superset for everything the file calls and compiles its GLSL ES 1.00 shaders unchanged, so this is a namespace change (`GLES30`, client version 3) plus three comment updates; the shaders are byte-identical |
| 6 | Optional `centerFractionFromLeft` framing input | Places the unchanged fixed-camera model left of centre by shifting only the orthographic window. The default remains 0.5 (the supplied centred framing); ZIG passes 0.30 for the Home landscape |

Hosting uses the file's **Option A** — its own `PersepolisView`, a transparent `GLSurfaceView`
(RGBA8888 + depth, `RENDERMODE_WHEN_DIRTY`) composited over the sky — wrapped by
`PersepolisLandscape` (`ui/persepolis/PersepolisLandscape.kt`) and inserted in `HeroSkyCanvas`
between the sky canvas and the cloud-motion canvas. Option B (drawing `PersepolisScene` inside a host
GL renderer) was not used: the Home sky is a Compose `Canvas`, not a GL renderer, and Option A is the
integration the file designs for exactly this situation.

**What did not change:** the Compose ground band ([`SceneGroundRenderer`](HOME_SKY_SCENE.md)) is
painted exactly as before underneath the ruin — far ridge, mist shelf, mid ridge, ground, grass —
because it is the ruin's environment: the ridges and mist are its distance backdrop, the field below
the platform toe is its foreground. The fullscreen viewer (`HOME_HERO`/`LandscapeRenderer`), the Live
Sky backdrop, and the four stylised themes are untouched.

### Theme gate

`PersepolisFeature.isEnabledFor` (mirroring `SkyPanoramaFeature`) enables the layer for **Real Sky**
(the default theme) and **Atmospheric Sky** — the two photographic themes a realistically lit
limestone relief belongs to. Papercraft, Kids Watercolor and Monochrome Scientific are deliberate flat
styles and keep their ground; Observatory exists to emit nothing but red light, and a full-colour GL
layer would defeat it. The grounding paint in the sky pass reads the same gate, so disabling the
feature removes every trace of it.

## 2. The lighting feed

No new astronomy. `SkySceneFrame` already computes, for the exact instant the sky is drawn:

- `sunHoriz` — Sun azimuth/altitude from `CoordinateEngine.equatorialToHorizontal`, azimuth measured
  **from North = 0°, East = 90°** — which is precisely the convention `PersepolisView.setSun` takes
  (its world axes are +X = north, +Z = east; `dirFromAzAlt` needs no conversion);
- `moonData` — Moon azimuth/altitude/illumination from `MoonEngine`.

`PersepolisLandscape` forwards both, with the Moon's intensity scaled by the illuminated fraction as
the file prescribes (`0.22 × fraction`, `persepolisMoonIntensity`). Colour, intensity, twilight fade
and the hemisphere ambient are derived inside `PersepolisScene.setSun` from altitude, exactly as
supplied: east faces catch sunrise, south faces noon, the group goes backlit silhouette at sunset and
dims to blue ambient + moonlight at astronomical night — in agreement with the Compose sky's own
`LightingState`, because both read the same Sun altitude.

Updates are `LaunchedEffect`s keyed on the ephemeris values, so the GL view renders **one frame per
sky-model change**: every 15 s bucket at idle (zero cost between), per frame while the finger scrubs
time (the light visibly sweeps across the façade with the sky), on location or theme change. The 24 fps
cloud clock never touches it.

## 3. Framing — `PersepolisFraming`

`ui/rendering/PersepolisFraming.kt` (pure pixel arithmetic, `SkySceneLayout` style) turns the scene
size into the view's three inputs (`visibleWidthMeters`, `baseFractionFromTop`,
`centerFractionFromLeft`) and the platform pixel bounds used by the ground painter. Rules, in
priority order:

1. **Exactly half the first linear size.** The first framing's visible width — the model's own 89 m
   window on phones, or the aspect-grown width needed by its 34 % building-height cap on wide
   scenes — is doubled. Width, height and platform depth on screen therefore halve at every scene
   size; the effective height cap is now 17 %.
2. **Left-hand subject, whole façade retained.** The platform centroid sits at 0.30 of the width.
   Exact projection puts the detailed façade at about 7–55 % of a phone scene (16–45 % on the
   tested tablet), while the platform ends by 68 % on phones and earlier on wide scenes; the rest
   remains open landscape.
3. **Farther back in the ground plane.** The toe targets 0.098 of the scene below the horizon —
   about 0.798 of the scene — instead of the first framing's 0.84 floor. The platform top stays
   below the horizon, both tall columns still cross it, and roughly 20 % of foreground field remains
   in front of the stone on most sizes.
4. **No stone over the readout.** The GL surface composites *above* its Compose siblings, and moving
   the model left puts several structures under the caption. The toe is therefore lowered only as
   needed until the tallest structure that actually enters the protected 250 dp readout width clears
   its bottom edge by a nominal 5 dp. Under the model's exact projection the binding clearance is
   **+3.3…+8.6 dp** across the size matrix (§5); it is a tall column on phones and the great doorway
   on the wide scenes.

Measured results (exact lookAt/ortho, `verify_framing.py`):

| Scene | Visible width | Toe at | Sky silhouette, two tall columns | Binding readout clearance |
|---|---|---|---|---|
| 320 × 380 dp | 178.0 m | 0.798 | 6.5 / 0.3 dp above horizon | tallest column +5.5 dp |
| 360 × 420 dp | 178.0 m | 0.798 | 8.0 / 1.1 dp | tallest column +4.0 dp |
| 430 × 480 dp | 178.0 m | 0.804 | 8.7 / 0.5 dp | tallest column +3.3 dp |
| 360 × 312 dp | 178.0 m | 0.831 | 8.5 / 1.5 dp | tallest column +3.5 dp |
| 1200 × 560 dp | 297.5 m | 0.798 | 43.2 / 29.4 dp | great doorway +5.9 dp |
| 800 × 312 dp | 356.0 m | 0.798 | 24.1 / 16.4 dp | great doorway +8.6 dp |

The framing also guarantees `PersepolisScene`'s internal `autoFitHeight` never widens the view past
the chosen width, so the scale the ground painter assumes is the scale the camera uses.

### Grounding (why it does not look pasted)

A transparent overlay ends on a hard line unless the host paints the contact. The first integration
used full-width sand/contact-shadow bands because the platform ran off both edges. At half scale on
the left that would read as an artificial stripe, so `SkySceneRenderer` now passes the full framing
to `SceneGroundRenderer`, which paints a worn site only around the platform footprint, after the
ground gradient and before the grass:

- an **irregular worn-earth apron**, with wavy top/bottom edges and both vertical and horizontal
  feathering, strongest at the toe and dissolving down and to the right into the field;
- an **earth bank** against the platform's open right end, spilling toward the viewer so the slab
  does not terminate as a clipped rectangle;
- a **broken contact shadow** that follows only the forecourt (the file ships no shadow maps —
  correctly, for static render-on-demand geometry — so this is the host-side occlusion that seats it);
- low, soft **drift hummocks** and deterministic light/dark **debris flecks**, concentrated near the
  toe and thinning into the foreground.

The colours are mixed from the active ground palette toward sand and scaled by
`LightingState.ambientBrightness`, so the patch follows the terrain's warmth and brightness and
vanishes into astronomical night with it. The exact projection puts the forecourt's front edge
0.6–1.3 dp above the toe row — inside the painted grounding on every tested size. The grass then
paints over the foreground part of the patch, tying the site back into the surrounding field.

## 4. Lifecycle, cost, interaction

- `PersepolisLandscape` forwards `ON_RESUME`/`ON_PAUSE` to the `GLSurfaceView` from the composition's
  lifecycle owner (the `CompassARScreen`/`LocalLifecycleOwner` pattern) and frees the scene on the GL
  thread in `onRelease`. First surface creation runs the one procedural build on the GL thread while
  the sky and ground are already visible through the transparent surface; every later creation
  re-uploads from the memoised arrays.
- The view is non-clickable/non-focusable and consumes no touches: tap-to-identify and finger time
  travel on the canvas are unaffected (its geometry is not hit-tested — a tap on a star projected over
  a column still identifies the star).
- Per-repaint cost is the file's own: three static-VBO draw calls, on demand. At idle that is one
  frame per 15 s model bucket; the surface does no work between light changes.

### Known trade-offs (honest list)

- **Surface above siblings.** `setZOrderOnTop` places the GL surface above the Compose layers inside
  its bounds. Framing (§3.4) keeps stone off the readout and header; what remains beneath is where it
  belongs physically — cloud veils drift behind the columns, and a near-horizon selection ring is
  occluded by the stone that occludes its object. If that ever stops being acceptable, the upgrade
  path is the file's Option B: host the untouched `PersepolisScene` on a `TextureView` the way
  `SkyPanoramaSurfaceView` hosts the panorama, gaining true Compose z-order at the cost of a
  hand-rolled EGL thread. The scene's `RectangleShape` clip is why the surface needs no clipping here,
  unlike the panorama's squircle card.
- **Scroll.** The scene is the first `LazyColumn` item; a `SurfaceView` layer follows its window
  position per frame on current Android, but can lag a frame behind fast flings on some devices.
  The TextureView path above removes that too.
- **Fixed view.** One camera, from the south-east, telephoto-compressed like the reference photograph
  (the file's header documents the scope: plan-true sizes, ~24 m compressed depth). It does not orbit,
  and it is not tied to the observer's location — Persepolis is the scene's *place*, the way the
  ridge line was, now with the Sun and Moon of the observer's own sky moving across it. It must not
  be described as the view from the user's location.
- **No cast shadows.** Depth occlusion is real; column-on-platform shadows would need a shadow-map
  pass around `PersepolisScene.draw`, which the geometry already allows if it is ever wanted.

## 5. Verification

- `PersepolisFramingTest` (JVM): the half-scale, left-placement, distant-toe and protected-readout
  rules over six scene sizes, the footprint ordering consumed by the ground painter, the theme gate,
  and the moonlight scaling.
- `tools/persepolis/verify_framing.py` (stdlib): replicates `Matrix.setLookAtM`/`orthoM` and
  `PersepolisScene.draw`'s centre-solving (including the 0.30 horizontal offset), projects the toe,
  centroid, forecourt edge, platform top/corners, both tall columns, doorway and façade ends through
  the framing, and asserts the composition — including every tall landmark that enters the readout's
  protected width, the direction where the isotropic estimate is *not* conservative because a
  structure behind the toe projects higher. Measured approximation error across the matrix is
  ≤ 2.9 dp for the column, ≤ 1.6 dp for the doorway, ≤ 0.7 dp for the platform top and ≤ 4.9 dp for
  the platform's right edge; the exact binding readout clearance remains +3.3…+8.6 dp.
- `tools/persepolis/render_framing_preview.py` (Pillow): regenerates the diagram above.
- The GL code itself needs a device or emulator: `assembleDebug`, open Home on Real Sky, scrub time
  across sunrise/noon/sunset/night, scroll the feed away and back (surface recreation via change 2/3),
  background and resume the app, switch to Papercraft and back (gate).

## 6. Tuning

- On/off and themes: `PersepolisFeature` (`ui/persepolis/PersepolisLandscape.kt`).
- Linear scale, horizontal centre, distant-toe position and readout clearance: `PersepolisFraming`
  constants (`LINEAR_SCALE_FRACTION`, `CENTER_FRACTION_FROM_LEFT`,
  `DISTANT_TOE_BELOW_HORIZON_FRACTION`, `READOUT_CLEARANCE_DP`).
- Worn-earth, bank, shadow, hummock and debris strength/reach:
  `SceneGroundRenderer.drawPersepolisGrounding`.
- Light character (colour ramp, exposure): inside `Persepolis.kt` (`PersepolisScene.setSun`, the
  fragment shader) — changing these changes the supplied model, prefer the host-side knobs.
