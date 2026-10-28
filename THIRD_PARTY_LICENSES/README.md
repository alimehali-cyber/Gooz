# Third-party licences

Files in this directory are reproduced verbatim from the upstream projects they cover.

## Vazirmatn (Persian / Arabic typeface)

* Project: Vazirmatn by Saber Rastikerdar and contributors — `https://github.com/rastikerdar/vazirmatn`
* Release used: tag `v33.003` (font `name` table version string: `Version 33.003`)
* Licence: SIL Open Font License 1.1 — see `Vazirmatn-OFL.txt` (verbatim copy of upstream `OFL.txt`)
* Author list: see `Vazirmatn-AUTHORS.txt` (verbatim copy of upstream `AUTHORS.txt`)
* Files bundled, as *static instances* (not the variable-axis font), one per weight actually used by
  the RED type scale (Normal 400 / Medium 500 / SemiBold 600 / Bold 700). They are used for Persian
  text only; English text keeps the platform default font.
* Provenance: each file was fetched from the GitHub blob API for the tag above and verified by
  recomputing its git blob SHA-1 against `git ls-tree` for that tag, so the bytes are the upstream
  ones and not a repackaging.

| file | bytes | sha256 |
|---|---|---|
| `res/font/vazirmatn_regular.ttf` | 122,752 | `b69fd4c680b8f3f225feabcc655a2c585d97627b8f5f5c0f9985e894069f3a56` |
| `res/font/vazirmatn_medium.ttf` | 122,656 | `b986623e4ddef10755e04be39f8ea7bcb1dc08bfe8dd0aa6af395736f256ad4a` |
| `res/font/vazirmatn_semibold.ttf` | 122,920 | `3f239d8364c14ee32c46b6839fa197c28958358b82a9673d2c6acc8506d78bc5` |
| `res/font/vazirmatn_bold.ttf` | 123,036 | `f635fdbea28f265de395ba83b4b1570dcf2f58d13c65469e61903b1c2d2ae723` |

They are applied in `ui/theme/Type.kt` (`VazirmatnFontFamily`) and switched on per locale in
`ui/theme/Theme.kt` (`REDTheme(isPersian = ...)`).

## blackhole-simulation (Kerr ray-marching reference)

* Project: blackhole-simulation by Mayank / steeltroops-ai and contributors —
  `https://github.com/steeltroops-ai/blackhole-simulation`
* Commit studied: the default branch as cloned (shallow, `--depth 1`); the GLSL ES 3.00 shader source,
  the Kerr metric closed forms, the accretion-disk model, the camera choreography and the
  bloom/TAA post-processing chain were ported into `app/src/main/java/com/zig/chal/**`.
* Licence: MIT — see `blackhole-simulation-MIT.txt` (verbatim copy of upstream `LICENSE`).

The port is confined to the `com.zig.chal` package (the Lab screen's "Chal" feature) and shares no
code with any other renderer in this application.

## AR sky (Compass/AR screen) — data and imagery

Everything below is used by the AR sky (`app/src/main/java/com/alijafari/red/astronomy/ar/**` and
`ui/screens/ar/**`). The asset files in `app/src/main/assets/ar/` are generated reproducibly by
`tools/ar-sky-assets/build_ar_assets.py`, which refuses to run unless every downloaded source matches
the git-blob / SHA-1 recorded in the script; the resulting files, their SHA-256 and source hashes are
listed in `tools/ar-sky-assets/provenance.json`.

| What | Source | Licence / terms |
|---|---|---|
| Star catalogue `ar/stars_bsc5.bin` (8,404 stars to V 6.5, J2000 positions, FK5 proper motions, V, B−V, spectral class) and `ar/star_names.txt` | Yale Bright Star Catalogue, 5th revised ed. (Hoffleit & Warren 1991, NASA ADC catalogue V/50), via the JSON conversion in `https://github.com/brettonw/YaleBrightStarCatalog` (`bsc5-all.json`) | Catalogue: freely distributed scientific data (NASA Astronomical Data Center). JSON conversion: MIT — see `YaleBrightStarCatalog-json-MIT.txt` |
| Constellation figures and names `ar/constellations.txt` | d3-celestial by Olaf Frohn, `https://github.com/ofrohn/d3-celestial` (`data/constellations.lines.json`, `data/constellations.json`); figure vertices snapped to BSC5 stars (worst 0.0084°) | BSD 3-Clause — see `d3-celestial-BSD-3-Clause.txt` |
| Moon map `ar/textures/moon_plate.jpg` (AR Moon and Sky Canvas Moon) | The Home Moon card's photographic plate (`res/drawable/img_full_moon_photo_1785673146290.jpg`, already shipped by the app), orthographically re-projected onto the sphere for the near side by `tools/ar-sky-assets/build_moon_plate_map.py`; libration zones and far side from the Solar System Scope 2k Moon map (kept as `tools/ar-sky-assets/sources/moon_sss_1024x512.jpg`), tone-matched | The plate: the app's own asset (as for the Moon card). Solar System Scope part: Creative Commons Attribution 4.0 International — attribution: "Textures © Solar System Scope, CC BY 4.0" |
| Mercury, Venus (atmosphere), Jupiter, Saturn (+ ring profile), Uranus, Neptune surface maps `ar/textures/*` | Solar System Scope, `https://www.solarsystemscope.com/textures/` (2k maps; byte-identical to the Wikimedia Commons uploads, SHA-1 verified) | Creative Commons Attribution 4.0 International, `https://creativecommons.org/licenses/by/4.0/` — attribution: "Textures © Solar System Scope, CC BY 4.0"; the maps were downsampled to 1024×512 / 512×256 |
| Mars surface map `ar/textures/mars.jpg` | NASA 3D Resources, `https://github.com/nasa/NASA-3D-Resources` (`Images and Textures/Mars/Mars.jpg`) | NASA imagery, not subject to copyright in the United States (NASA media usage guidelines); credit: NASA |
| Milky Way panorama `sky/panorama/milkyway_2020_4k.jpg` (already shipped for the Sky Panorama; reused by the camera-off AR sky) | NASA Scientific Visualization Studio, "Deep Star Maps 2020" (Gaia DR2 / Hipparcos-2 / Tycho-2) | NASA/Goddard Space Flight Center Scientific Visualization Studio; Gaia data: ESA/Gaia/DPAC. NASA SVS media are free to use with credit |
| Planetary theory `astro_engine/Vsop87dData.kt` (VSOP87D series, truncated) | VSOP87 (Bretagnon & Francou 1988, Bureau des Longitudes / IMCCE) via `https://github.com/gmiller123456/vsop87-multilang` (`vsop87d.csv`); generated by `tools/ar-sky-assets/gen_vsop87d_kotlin.py` | The conversion is released to the public domain — see `vsop87-multilang-LICENSE.txt`; VSOP87 itself is published scientific data of the Bureau des Longitudes |
| Geomagnetic model `ar/core/Wmm2025.kt` (embedded coefficients) | World Magnetic Model 2025, NOAA NCEI and British Geological Survey (`WMM.COF`, verified against NOAA's official `WMM2025_TEST_VALUES.txt` in `Wmm2025Test`) | US Government work, public domain |
| Satellite element sets in `astro_engine/SatelliteCatalog.kt` / `ISSEngine.kt` (offline fallbacks) | CelesTrak GP data, `https://celestrak.org` (epoch 2026-10-15) | Public orbital data (US Space Force 18th SDS via CelesTrak) |

Algorithms (implemented from the publications, no code copied): IAU 2006 precession (Fukushima-Williams,
Capitaine et al. 2003) and IAU 2000B nutation, Earth rotation angle and GAST (IERS Conventions 2010,
formulated as in SOFA); relativistic annual + diurnal aberration, proper motion and annual parallax; refraction by Sæmundsson (1986)
and Bennett (1982) scaled by measured pressure/temperature; Meeus, *Astronomical Algorithms* (2nd ed.)
for the Moon, the Sun, phases and the Galilean satellites; IAU WGCCRE rotation models (Archinal et al.
2011, 2018) for the glyph orientations; Bruneton & Neyret (2008) / Hillaire (2020) spectral sky-view LUTs; Krisciunas &
Schaefer (1991) dark-sky brightness; Schaefer (1990) limiting magnitude; Narkowicz (2015) ACES fit.
Moon and earthshine (`ar/core/LunarAppearance.kt`): Krisciunas & Schaefer (1991) lunar magnitude and
illuminance; Lommel–Seeliger law and its disc-integrated phase function; earthshine from the Big Bear
earthshine programme (Qiu et al. 2003; Pallé et al. 2003, 2016: apparent albedo p* ≈ 0.30, Lambert
phase function of the Earth) and its colour from Thejll et al. (2014, A&A 563, A38: 0.15 mag bluer in
B−V); visibility from Crumey (2014, MNRAS 442, 2600) contrast thresholds and CIE 146:2002 disability glare.
