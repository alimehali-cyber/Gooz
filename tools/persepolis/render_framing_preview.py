#!/usr/bin/env python3
"""
Render a framing diagram of the combined landscape in the Home scene.

This is NOT a screenshot and does not pretend to be one: the real render is
the ~149k-triangle GL model on device. What this draws is the model's
*massing* — every Persepolis frame, jamb and column from the combined
file's element tables, plus the Mil-e Azhdaha tower and the nine fixed
sheets of its limestone slope, as flat-shaded blocks — projected through
the model's own fixed camera (verify_framing.projector) with
PersepolisFraming's numbers, over the Home scene's Marvdasht ground stack
(far Zagros range, mist, Kuh-e Rahmat massif, steppe plain, the localized
earthwork around both footprints, foreground Pulvar stream, scrub and
trees) replicated from SceneGroundRenderer — the python profile functions
below mirror LandscapeProfile.kt and are where the composition was tuned.
It exists so the composition can be eyeballed — Persepolis at half size in
the left distance against the ranges, open plain, then the tower at the far
right with its slope cropped by the edge and grounded in its own clearing,
and the readout clear of stone — without a device.

Requires Pillow. Run from the repo root:
    python3 tools/persepolis/render_framing_preview.py
Writes docs/persepolis-framing.png.
"""

import math
import os
import sys

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
import verify_framing as vf  # noqa: E402

from PIL import Image, ImageDraw, ImageFont  # noqa: E402

W, H, DENSITY = 1080, 1260, 3.0  # phone 360x420dp
FRAMING = vf.for_scene(W, H, DENSITY)
PROJECT, _ = vf.projector(
    W, H, FRAMING["visible_width_m"], FRAMING["base_fraction_from_top"],
    FRAMING["center_fraction_from_left"],
)
HORIZON_Y = H * vf.HORIZON_FRACTION
GROUND_TOP = HORIZON_Y + H * 0.052
TOE_Y = FRAMING["toe_y"]


def px(p):
    ndc_x, row = PROJECT(p)
    return ((ndc_x + 1.0) / 2.0 * W, row)


def lerp(a, b, t):
    return tuple(int(a[i] + (b[i] - a[i]) * t) for i in range(3))


def depth_tone(base, z):
    """Aerial perspective for the diagram: farther (more negative z) fades toward the horizon tone."""
    t = min(max((-z) / 14.0, 0.0), 1.0) * 0.38
    return lerp(base, (226, 158, 110), t)


STONE = (184, 132, 82)
STONE_DARK = (118, 86, 56)
STONE_COL = (198, 150, 96)
PLATFORM_TONE = (170, 126, 78)


def quad(draw, pts, fill):
    draw.polygon([px(p) for p in pts], fill=fill)


def rect_xz(draw, x0, x1, y0, y1, z, fill):
    quad(draw, [(x0, y0, z), (x1, y0, z), (x1, y1, z), (x0, y1, z)], fill)


def mil_box(draw, cx, cz, y0, y1, width, depth, front, side, top):
    """A Mil-local box through the combined file's baked group transform.

    Only the camera-facing left/front faces and the top are massed; that is
    enough for a framing diagram and keeps the slope sheets readable.
    """
    x0, x1 = cx - width / 2.0, cx + width / 2.0
    z0, z1 = cz - depth / 2.0, cz + depth / 2.0
    corners = [
        (x0, y0, z0), (x1, y0, z0), (x1, y1, z0), (x0, y1, z0),
        (x0, y0, z1), (x1, y0, z1), (x1, y1, z1), (x0, y1, z1),
    ]
    world = [vf.mil_transform(p) for p in corners]
    quad(draw, [world[0], world[4], world[7], world[3]], side)    # left
    quad(draw, [world[4], world[5], world[6], world[7]], front)   # front
    quad(draw, [world[3], world[2], world[6], world[7]], top)     # top


# Element tables from the combined file's Persepolis builder (massing only).
FRAMES = [  # x, z, w, h, opening, baseY, breakRight, window
    (-39.7, 4.9, 4.9, 5.6, 1.9, 2.85, 1.0, False),
    (-25.6, -3.4, 6.1, 10.4, 2.15, 4.35, 1.0, False),
    (-19.6, -6.0, 5.4, 7.9, 2.2, 4.35, 0.55, False),
    (-12.4, -6.8, 4.6, 6.1, 2.65, 4.35, 1.0, True),
    (-5.9, -4.2, 5.3, 7.8, 2.2, 4.35, 1.0, False),
    (1.4, -3.0, 6.0, 9.9, 2.55, 4.35, 1.0, False),
    (9.6, -7.4, 5.2, 7.4, 2.2, 4.35, 0.8, False),
    (16.4, -8.2, 4.6, 6.4, 2.65, 4.35, 1.0, True),
    (-16.6, -10.5, 4.2, 5.2, 2.0, 4.35, 1.0, True),
    (3.7, -10.5, 4.4, 5.7, 2.0, 4.35, 1.0, True),
    (23.0, -10.5, 5.0, 6.6, 2.2, 4.35, 0.85, False),
    (-34.4, -9.5, 5.2, 7.0, 2.2, 4.35, 0.7, False),
]
JAMBS = [  # x, z, w, h
    (-16.4, -1.6, 1.5, 6.6), (-9.6, -3.2, 1.3, 5.2), (-3.2, -9.0, 1.6, 7.6),
    (5.7, -3.6, 1.35, 6.0), (13.5, -2.6, 1.25, 4.7), (20.3, -9.2, 1.5, 5.8),
    (-30.7, -7.5, 1.3, 5.4), (34.8, -9.4, 1.4, 4.3), (-22.8, -9.2, 1.2, 4.4),
    (14.9, -8.8, 1.2, 5.5),
]
COLUMNS = [  # x, z, total height above platform (approx from the file's tables), has capital
    (8.9, -1.6, 12.6, True), (10.4, -8.6, 11.0, True), (15.5, -1.8, 8.2, False),
    (17.3, -8.8, 8.9, False), (20.2, -1.6, 5.2, False), (21.7, -8.6, 9.0, False),
    (26.2, -2.0, 19.2, True), (29.3, -2.8, 15.7, True), (32.4, -8.6, 6.7, False),
    (36.9, -2.2, 8.4, True), (39.3, -8.4, 8.6, True), (2.9, -8.8, 4.2, False),
]

# The combined scene bakes Persepolis 9.7 m left before rendering.
PERSEPOLIS_X = -9.7
FRAMES = [(x + PERSEPOLIS_X, *rest) for x, *rest in FRAMES]
JAMBS = [(x + PERSEPOLIS_X, *rest) for x, *rest in JAMBS]
COLUMNS = [(x + PERSEPOLIS_X, *rest) for x, *rest in COLUMNS]


def ridge_points(baseline, amplitude, phase, waves, detail):
    pts = []
    for i in range(37):
        p = i / 36.0
        hh = ((math.sin(p * 2 * math.pi * waves + phase) * 0.65 +
               math.sin(p * 2 * math.pi * detail + phase * 1.7) * 0.35) * 0.5 + 0.5) * amplitude
        pts.append((p * W, baseline - hh))
    return pts + [(W, baseline + amplitude * 2), (0, baseline + amplitude * 2)]


# --- Marvdasht landscape prototype (mirrors LandscapeProfile.kt) ------------
# 1D value noise -> fBm -> Musgrave ridged multifractal with domain warp.
# These functions are the design sandbox for the Kotlin photographic ground;
# keep the two implementations algorithmically in step.

def _hash01(n):
    v = math.sin(n * 127.1 + 311.7) * 43758.5453
    return v - math.floor(v)


def vnoise1(x, seed):
    i = math.floor(x)
    f = x - i
    u = f * f * (3.0 - 2.0 * f)
    a = _hash01(seed * 57.0 + i)
    b = _hash01(seed * 57.0 + i + 1)
    return (a + (b - a) * u) * 2.0 - 1.0


def fbm1(x, seed, octaves=4):
    s, amp, freq, norm = 0.0, 0.55, 1.0, 0.0
    for o in range(octaves):
        s += amp * vnoise1(x * freq, seed + o * 17)
        norm += amp
        amp *= 0.5
        freq *= 2.13
    return s / norm


def ridged1(x, seed, octaves=5):
    xx = x + 0.10 * fbm1(x * 1.7, seed + 51, 3)  # domain warp: folded, not wavy
    s, amp, freq, norm, w = 0.0, 0.55, 2.6, 0.0, 1.0
    for o in range(octaves):
        r = (1.0 - abs(vnoise1(xx * freq, seed + o * 23))) ** 2 * w
        w = min(max(r * 1.7, 0.0), 1.0)  # detail piles up on ridges (Musgrave)
        s += r * amp
        norm += amp
        amp *= 0.5
        freq *= 2.17
    return s / norm


def envelope(p, pts):
    """Smoothstep interpolation through (x, height) control points."""
    if p <= pts[0][0]:
        return pts[0][1]
    for (x0, y0), (x1, y1) in zip(pts, pts[1:]):
        if p <= x1:
            t = (p - x0) / max(x1 - x0, 1e-6)
            t = t * t * (3.0 - 2.0 * t)
            return y0 + (y1 - y0) * t
    return pts[-1][1]


def mountain_samples(base_y, max_h, env_pts, seed, n=140, sharpness=1.15):
    out = []
    for i in range(n + 1):
        p = i / n
        h = max_h * envelope(p, env_pts) * (0.42 + 0.58 * ridged1(p, seed) ** sharpness)
        out.append((p * W, base_y - h, h))
    return out


def draw_mountain(d, samples, base_y, lit, shade, haze, haze_amt, sun_sign,
                  facet=0.55, seed=7, strata_amp=0.0):
    """Facet strips: brightness from profile slope vs sun side + micro noise,
    three horizontal slices per strip for top-light -> hazy-base falloff."""
    slices = 6
    for i in range(len(samples) - 1):
        x0, t0, h0 = samples[i]
        x1, t1, h1 = samples[i + 1]
        if h0 < 1.0 and h1 < 1.0:
            continue
        lo = samples[max(i - 2, 0)][2]
        hi = samples[min(i + 3, len(samples) - 1)][2]
        slope = (hi - lo) / max((x1 - x0) * 4.0, 1.0)  # wide window: facets, not stripes
        facing = max(-1.0, min(1.0, -slope * 1.05 * sun_sign))
        micro = 0.95 + 0.10 * _hash01(seed * 91.0 + i * 3.7)
        fcol = lerp(shade, lit, 0.5 + 0.5 * facing * facet)
        fcol = tuple(min(255, int(c * micro)) for c in fcol)
        top = min(t0, t1)
        for s in range(slices):
            ya = top + (base_y - top) * s / slices
            ta = t0 + (base_y - t0) * s / slices
            tb = t0 + (base_y - t0) * (s + 1) / slices
            tc = t1 + (base_y - t1) * (s + 1) / slices
            td = t1 + (base_y - t1) * s / slices
            c = lerp(fcol, haze, haze_amt * (0.25 + 0.75 * (s / (slices - 1.0))))
            if strata_amp:  # whisper of bedding: wavy brightness bands by altitude
                k = 1.0 + strata_amp * math.sin((base_y - ya) * 0.085 + 1.7 * math.sin(i * 0.11))
                c = tuple(min(255, int(v * k)) for v in c)
            d.polygon([(x0, ta), (x1, td), (x1, tc), (x0, tb)], fill=c)
    # Crest catch-light, kept whisper-thin.
    crest = [(x, t) for x, t, _ in samples if _ > 2.0]
    if len(crest) > 1:
        d.line(crest, fill=(*lerp(lit, (255, 240, 220), 0.45), 70), width=2)


def main():
    img = Image.new("RGB", (W, H), (20, 30, 58))
    d = ImageDraw.Draw(img, "RGBA")

    # Sunset sky gradient (schematic — the app's sky is the real ephemeris sky).
    for y in range(H):
        t = y / max(HORIZON_Y, 1)
        if t < 0.62:
            c = lerp((23, 36, 74), (104, 86, 128), t / 0.62)
        else:
            c = lerp((104, 86, 128), (240, 152, 84), min((t - 0.62) / 0.38, 1.0))
        d.line([(0, y), (W, y)], fill=c)

    # Marvdasht photographic ground prototype (SceneGroundRenderer's
    # photographic path): far Zagros range -> mist -> steppe plain ->
    # Kuh-e Rahmat massif -> piedmont foothills + alluvial fans -> field
    # mosaic. Sunset schematic: sun low at the left.
    SUN_SIGN, SUN_X = -1.0, 0.22 * W
    far_base = GROUND_TOP + H * 0.006  # strips fill to the plain: no sky sliver under the range
    far_samples = mountain_samples(
        far_base, H * 0.125,
        [(0, .70), (.18, .95), (.38, .75), (.55, 1.0), (.72, .88), (.88, 1.0), (1, .90)],
        seed=11, sharpness=1.15,
    )
    draw_mountain(d, far_samples, far_base, lit=(126, 108, 140), shade=(94, 84, 116),
                  haze=(240, 170, 118), haze_amt=0.50, sun_sign=SUN_SIGN, facet=0.32, seed=3)
    mist = Image.new("RGBA", (W, H), (0, 0, 0, 0))
    md = ImageDraw.Draw(mist)
    top, bot = HORIZON_Y - H * 0.055, HORIZON_Y + H * 0.045
    for y in range(int(top), int(bot)):
        a = int(80 * math.sin(math.pi * (y - top) / (bot - top)))
        md.line([(0, y), (W, y)], fill=(244, 196, 150, a))
    img = Image.alpha_composite(img.convert("RGBA"), mist).convert("RGB")
    d = ImageDraw.Draw(img, "RGBA")
    # Semi-arid steppe plain (dry olive-straw, darkening to the viewer).
    for y in range(int(GROUND_TOP), H):
        t = (y - GROUND_TOP) / (H - GROUND_TOP)
        d.line([(0, y), (W, y)], fill=lerp((96, 86, 58), (18, 20, 14), max(t, 0.0) ** 0.8))
    # Kuh-e Rahmat: the great limestone wall at Persepolis' back. Envelope
    # keeps a low shoulder behind the ruins/readout, crest at the right.
    massif_base = TOE_Y - H * 0.004
    massif = mountain_samples(
        massif_base, H * 0.190,
        [(0, .30), (.14, .38), (.30, .48), (.45, .60), (.58, .78), (.70, .96),
         (.80, 1.0), (.90, .86), (1, .70)],
        seed=29,
    )
    draw_mountain(d, massif, massif_base, lit=(150, 108, 82), shade=(86, 64, 60),
                  haze=(226, 158, 110), haze_amt=0.30, sun_sign=SUN_SIGN, facet=0.65,
                  seed=5, strata_amp=0.045)
    # Erosion gullies: dark seams running crest -> fan apex, light lip beside.
    def sample_at(samples, x):
        i = min(max(int(x / W * (len(samples) - 1)), 0), len(samples) - 2)
        return samples[i]

    for k in range(11):
        p = 0.35 + 0.63 * _hash01(31.7 * k + 4.1)
        x0, top0, h0 = sample_at(massif, p * W)
        if h0 < H * 0.04:
            continue
        drift = (_hash01(17.3 * k + 9.2) - 0.5) * W * 0.05
        pts = []
        for s in range(9):
            t = s / 8.0
            x = x0 + drift * t + math.sin(t * 5.0 + k * 1.7) * 5.0 * (1 - t)
            y = top0 + (massif_base - top0) * t
            y = max(y, sample_at(massif, x)[1] + 3)
            pts.append((x, y))
        d.line(pts, fill=(50, 38, 34, 72), width=2)
        d.line([(x + 2, y) for x, y in pts], fill=(210, 170, 130, 30), width=1)
    # Haze pooling at the massif foot.
    for i in range(26):
        y = massif_base - H * 0.028 + i * (H * 0.038 / 26)
        a = int(38 * math.sin(math.pi * i / 26))
        d.line([(0, y), (W, y)], fill=(232, 168, 118, a))
    # Piedmont foothills: one low, smooth undulation in front of the massif.
    foot_base = TOE_Y + H * 0.008
    foot = []
    for i in range(81):
        p = i / 80.0
        h = (0.45 + 0.55 * (0.5 + 0.5 * fbm1(p * 3.1, 77, 4))) * H * 0.021 * \
            envelope(p, [(0, .7), (.5, 1.0), (1, .5)])
        foot.append((p * W, foot_base - h))
    d.polygon(foot + [(W, foot_base + H * 0.02), (0, foot_base + H * 0.02)], fill=(80, 70, 50))
    d.line(foot, fill=(150, 126, 92, 90), width=2)
    # Alluvial fans spilling from the gully mouths onto the plain.
    for p, spread in [(0.56, .075), (0.68, .09), (0.82, .08), (0.93, .07)]:
        ax, ay = p * W, massif_base - 2
        by = TOE_Y + H * 0.036
        rim = [(ax + W * spread * (t * 2 - 1), by + H * 0.007 * math.sin(math.pi * t))
               for t in (i / 6.0 for i in range(7))]
        d.polygon([(ax, ay)] + rim, fill=(178, 144, 102, 30))
        for r in (-0.55, 0.0, 0.55):
            d.line([(ax, ay), (ax + W * spread * r, by)], fill=(210, 178, 130, 16), width=1)
    # Cultivated-plain mosaic: huge soft plots, irrigated green vs fallow straw.
    for cx, cy, wdt, hgt, col in [
        (0.16, 0.875, 0.55, 0.040, (76, 92, 58, 30)),
        (0.62, 0.855, 0.62, 0.034, (150, 124, 84, 26)),
        (0.40, 0.935, 0.70, 0.050, (70, 86, 54, 28)),
        (0.85, 0.920, 0.55, 0.044, (148, 120, 78, 24)),
        (0.08, 0.960, 0.50, 0.046, (144, 118, 76, 22)),
    ]:
        d.ellipse([W * (cx - wdt / 2), H * (cy - hgt / 2), W * (cx + wdt / 2), H * (cy + hgt / 2)], fill=col)
    # An irrigation canal with its tree line, crossing the mid-foreground.
    canal_y = TOE_Y + H * 0.048
    d.line([(W * 0.015, canal_y - H * 0.004), (W * 0.985, canal_y + H * 0.006)],
           fill=(46, 58, 40, 130), width=3)
    for i in range(26):
        x = W * (0.03 + 0.94 * (i + _hash01(i * 7.7)) / 26)
        y = canal_y - H * 0.004 + (x / W) * H * 0.010
        r = 3 + 4 * _hash01(i * 3.3 + 1.1)
        d.ellipse([x - r, y - r, x + r, y + r * 0.7], fill=(40, 52, 36, 150))

    # Localized earthwork around each footprint separately
    # (SceneGroundRenderer.drawCombinedLandscapeGrounding): two irregular worn-earth
    # clearings with ordinary field between them, limestone dust at the slope, contact
    # shadows under both contact rows, drift hummocks and debris flecks.
    def rnd(seed):
        v = math.sin(seed * 12.9898) * 43758.5453
        return v - math.floor(v)

    def soft_drift(cx, cy, width, height, peak_alpha, layers, rgb=(190, 160, 110)):
        shell_alpha = int(round(255 * (1 - (1 - peak_alpha) ** (1 / layers))))
        for layer in range(layers):
            scale = 1 - 0.92 * (layer / (layers - 1))
            w, h = width * scale, height * scale
            d.ellipse(
                [cx - w / 2, cy - h / 2, cx + w / 2, cy + h / 2],
                fill=(*rgb, shell_alpha),
            )

    plat_l, plat_r = FRAMING["platform_left_x"], FRAMING["platform_right_x"]
    slope_l, slope_r = FRAMING["mil_slope_left_x"], FRAMING["mil_slope_right_x"]
    slope_front = min(FRAMING["mil_slope_front_y"], H)
    papron_l = max(plat_l - W * 0.030, -W * 0.02)
    papron_r = plat_r + W * 0.105
    sapron_l = slope_l - W * 0.065
    sapron_r = W * 1.03

    def smooth01(t):
        t = min(max(t, 0.0), 1.0)
        return t * t * (3 - 2 * t)

    def apron_points(left, right, base_y, phase):
        pts = []
        segs = 36
        for i in range(segs + 1):
            p = i / segs
            taper = max(smooth01((0.16 - p) / 0.16), smooth01((p - 0.76) / 0.24))
            top_depth = (
                0.020
                + 0.004 * math.sin(p * math.pi * 4 + phase)
                + 0.002 * math.sin(p * math.pi * 9 + phase * 2.7)
            )
            pts.append((
                left + (right - left) * p,
                base_y - H * top_depth * (1 - taper) + H * 0.012 * taper,
            ))
        for i in range(segs, -1, -1):
            p = i / segs
            taper = max(smooth01((0.16 - p) / 0.16), smooth01((p - 0.76) / 0.24))
            bottom_depth = (
                0.064
                + 0.010 * math.sin(p * math.pi * 4 + phase + 0.6)
                + 0.005 * math.sin(p * math.pi * 7 + phase * 0.6)
            )
            depth = bottom_depth * (1 - taper) + 0.020 * taper
            pts.append((left + (right - left) * p, min(base_y + H * depth, H)))
        return pts

    d.polygon(apron_points(papron_l, papron_r, TOE_Y, 0.7), fill=(190, 160, 110, 64))
    d.polygon(apron_points(sapron_l, sapron_r, slope_front, 2.3), fill=(199, 160, 107, 50))

    soft_drift(
        slope_l + (slope_r - slope_l) * 0.20,
        min(slope_front + H * 0.020, H),
        (slope_r - slope_l) * 0.66,
        H * 0.058,
        peak_alpha=0.27,
        layers=16,
        rgb=(199, 160, 107),
    )
    soft_drift(
        (slope_l + slope_r) / 2,
        min(slope_front + H * 0.016, H),
        (slope_r - slope_l) * 1.14,
        H * 0.038,
        peak_alpha=0.20,
        layers=14,
        rgb=(199, 160, 107),
    )

    shadow_l = max(plat_l, -W * 0.01)
    shadow_r = min(plat_r + W * 0.018, W)
    shadow_pts = [(shadow_l, TOE_Y - H * 0.003), (shadow_r, TOE_Y - H * 0.002)]
    for i in range(32, -1, -1):
        p = i / 32
        depth = 0.018 + 0.005 * math.sin(p * math.pi * 6 + 0.8) + 0.002 * math.sin(p * math.pi * 11 + 2.2)
        shadow_pts.append((
            shadow_l + (shadow_r - shadow_l) * p,
            min(TOE_Y + H * depth, H),
        ))
    d.polygon(shadow_pts, fill=(0, 0, 0, 58))
    soft_drift(
        (slope_l + slope_r) / 2,
        min(slope_front + H * 0.004, H),
        (slope_r - slope_l) * 1.06,
        H * 0.024,
        peak_alpha=0.26,
        layers=14,
        rgb=(0, 0, 0),
    )
    for count, left, right, base_y, site_w, seed in (
        (5, papron_l, papron_r, TOE_Y, plat_r - plat_l, 10.1),
        (3, sapron_l, sapron_r, slope_front, slope_r - slope_l, 40.5),
    ):
        for i in range(count):
            rw_ = site_w * (0.13 + rnd(i * 23.7 + seed) * 0.16)
            rh_ = H * (0.010 + rnd(i * 31.3 + seed) * 0.018)
            cx = left + (right - left) * (
                0.08 + 0.84 * ((i + rnd(i * 17.1 + seed) * 0.8) / count)
            )
            cy = base_y + H * (0.012 + rnd(i * 23.7 + seed) * 0.055)
            soft_drift(cx, cy, rw_, rh_, peak_alpha=0.10, layers=10, rgb=(199, 160, 107))
    for count, left, right, base_y, seed in (
        (30, papron_l, papron_r, TOE_Y, 21.1),
        (18, sapron_l, sapron_r, slope_front, 61.9),
    ):
        for i in range(count):
            x = left + (right - left) * ((i + rnd(i * 41.0 + seed) * 0.9) / count)
            y = min(base_y - H * 0.004 + H * 0.082 * rnd(i * 13.9 + seed) ** 2, H)
            ln = (2 + rnd(i * 29.3 + seed) * 7) * 1.4
            d.line(
                [(x, y), (x + ln, y + (rnd(i * 41.0 + seed) - 0.5) * ln * 0.35)],
                fill=(205, 168, 118, 90),
                width=2,
            )

    # --- Pulvar foreground stream + vegetation (prototype) -------------------
    river_y = min(max(FRAMING["mil_slope_front_y"] + H * 0.055, H * 0.885), H * 0.94)
    def river_at(x):
        p = min(max((x + 20) / (W + 40), 0.0), 1.0)
        return (river_y + H * 0.016 * math.sin(p * 4.6 + 0.7)
                + H * 0.008 * fbm1(p * 3.1, 41, 3) + p * H * 0.014)

    def river_halfw(x, y):
        p = min(max((x + 20) / (W + 40), 0.0), 1.0)
        depth = min(max((y / H - 0.86) / 0.12, 0.0), 1.0)
        pool = 1.0 + 0.5 * max(0.0, math.sin(p * 4.6 + 2.0))  # wider pools at bends
        return H * (0.0055 + 0.0065 * depth) * pool

    center = [(-20 + (W + 40) * i / 28.0, river_at(-20 + (W + 40) * i / 28.0)) for i in range(29)]

    top_edge = [(x, y - river_halfw(x, y)) for x, y in center]
    bot_edge = [(x, y + river_halfw(x, y)) for x, y in reversed(center)]
    d.polygon(top_edge + bot_edge, fill=(36, 50, 56))
    inner_top = [(x, y - river_halfw(x, y) * 0.55) for x, y in center]
    inner_bot = [(x, y + river_halfw(x, y) * 0.55) for x, y in reversed(center)]
    d.polygon(inner_top + inner_bot, fill=(188, 124, 88, 205))  # sunset sky in the water
    for i in range(8):  # dark slow water breaking the reflection
        x = W * _hash01(i * 9.7 + 4.4)
        soft_drift(x, river_at(x), W * 0.10, river_halfw(x, river_at(x)) * 1.1,
                   peak_alpha=0.28, layers=8, rgb=(40, 54, 60))
    d.line(top_edge, fill=(24, 30, 26, 120), width=2)
    d.line([(x, y + river_halfw(x, y)) for x, y in center], fill=(172, 144, 106, 110), width=2)
    for i in range(12):  # riparian green along the far bank
        x = W * (i + _hash01(i * 5.1)) / 12
        soft_drift(x, river_at(x) - river_halfw(x, river_at(x)) - H * 0.004,
                   W * 0.13, H * 0.016, peak_alpha=0.20, layers=10, rgb=(56, 72, 44))
    for i in range(46):  # sun glitter, concentrated under the sun's column
        x = W * _hash01(i * 13.7 + 2.2)
        yc = river_at(x)
        hw = river_halfw(x, yc)
        y = yc + (_hash01(i * 7.9) - 0.5) * 1.4 * hw
        g = math.exp(-(((x - SUN_X) / (W * 0.17)) ** 2))
        a = int(230 * g * (0.35 + 0.65 * _hash01(i * 3.1)))
        ln = 8 + 26 * _hash01(i * 5.7) * (0.4 + g)
        d.line([(x, y), (x + ln, y)], fill=(255, 214, 150, a), width=3)
    for i in range(44):  # reeds on both banks
        x = W * _hash01(i * 11.3 + 6.4)
        yc = river_at(x)
        side = 1 if i % 2 else -1
        y = yc + side * river_halfw(x, yc) * 1.05
        bh = H * (0.006 + 0.008 * _hash01(i * 4.3))
        d.line([(x, y), (x + (_hash01(i * 2.9) - .5) * 6, y - bh)],
               fill=(70, 84, 52, 180), width=2)

    def bush(cx, by, s):
        d.ellipse([cx - s * 1.1, by - s * 0.16, cx + s * 1.9, by + s * 0.22], fill=(16, 18, 12, 90))
        for ox, oy, r, col in [(-0.5, -0.42, 0.62, (40, 50, 34, 235)), (0.35, -0.5, 0.7, (46, 58, 38, 235)),
                               (0.0, -0.72, 0.62, (52, 64, 42, 235)), (-0.18, -0.78, 0.32, (96, 108, 68, 120))]:
            d.ellipse([cx + (ox - r) * s, by + (oy - r * 0.62) * s,
                       cx + (ox + r) * s, by + (oy + r * 0.62) * s], fill=col)

    def tree(cx, by, hgt):
        d.ellipse([cx - hgt * 0.34, by - hgt * 0.035, cx + hgt * 0.95, by + hgt * 0.05],
                  fill=(14, 16, 10, 110))
        d.line([(cx, by), (cx + hgt * 0.05, by - hgt * 0.42)], fill=(52, 40, 30), width=max(3, int(hgt * 0.06)))
        for ox, oy, r, col in [(-0.30, -0.62, 0.34, (38, 48, 32, 240)), (0.22, -0.66, 0.38, (44, 56, 36, 240)),
                               (0.0, -0.84, 0.36, (50, 62, 40, 240)), (-0.10, -0.90, 0.24, (98, 110, 70, 140)),
                               (0.34, -0.52, 0.26, (40, 52, 34, 240))]:
            d.ellipse([cx + (ox - r) * hgt, by + (oy - r * 0.66) * hgt,
                       cx + (ox + r) * hgt, by + (oy + r * 0.66) * hgt], fill=col)

    for i in range(16):  # scrub: foothills + plain, depth-scaled
        x = W * _hash01(i * 19.7 + 3.3)
        if i < 7:
            y, s = TOE_Y - H * 0.004 + H * 0.010 * _hash01(i * 3.9), 6 + 5 * _hash01(i * 7.1)
        else:
            y = H * (0.90 + 0.075 * _hash01(i * 5.3))
            if abs(y - river_at(x)) < H * 0.02:
                continue
            s = 10 + 12 * _hash01(i * 7.1)
        bush(x, y, s)
    tree((plat_r + slope_l) / 2, TOE_Y + H * 0.020, H * 0.040)      # lone pistachio in the gap
    tree(W * 0.115, river_at(W * 0.115) + H * 0.034, H * 0.068)     # foreground, left bank
    tree(W * 0.875, river_at(W * 0.875) + H * 0.036, H * 0.058)     # foreground, right bank
    # Dry steppe grass speckle across the whole plain, growing to the viewer.
    for i in range(210):
        x = W * _hash01(i * 23.9 + 1.7)
        depth = _hash01(i * 17.3 + 8.8) ** 1.4
        y = GROUND_TOP + (H - GROUND_TOP) * depth
        if abs(y - river_at(x)) < river_halfw(x, y) * 1.25:
            continue
        if fbm1(x / W * 6.0 + depth * 3.0, 91, 3) < -0.12:  # patchy, not uniform
            continue
        bh = H * (0.003 + 0.009 * depth) * (0.6 + 0.7 * _hash01(i * 3.7))
        col = (170, 144, 94, 82) if _hash01(i * 9.1) > 0.42 else (94, 106, 68, 88)
        d.line([(x, y), (x + (_hash01(i * 6.1) - .5) * bh * 0.6, y - bh)], fill=col, width=2)
    soft_drift(W * 0.02, H * 1.0, W * 0.5, H * 0.07, peak_alpha=0.14, layers=10, rgb=(8, 10, 6))
    soft_drift(W * 0.98, H * 1.0, W * 0.5, H * 0.07, peak_alpha=0.14, layers=10, rgb=(8, 10, 6))

    # Cut-back platform fronts + floor (world x includes the -9.7 m bake).
    rect_xz(d, -56.17, 39.50, 0.5, 2.6, 7.25, lerp(PLATFORM_TONE, (90, 76, 58), 0.25))
    rect_xz(d, -55.77, 39.10, 2.85, 4.35, 4.24, lerp(PLATFORM_TONE, (90, 76, 58), 0.12))
    quad(d, [(2.3, 0.5, 7.25), (33.8, 0.5, 7.25), (33.8, 2.6, 7.25),
             (26.3, 4.35, 7.25), (2.3, 4.35, 7.25)],
         lerp(PLATFORM_TONE, (240, 170, 110), 0.12))                                    # stair facade
    quad(d, [(-56.38, 4.35, 4.7), (39.70, 4.35, 4.7),
             (39.70, 4.35, -13), (-56.38, 4.35, -13)],
         lerp(PLATFORM_TONE, (255, 220, 170), 0.25))                                    # platform floor

    # Structures, far to near.
    def draw_frame(fr):
        x, z, w, h, opening, base, brk, window = fr
        tone = depth_tone(STONE_DARK, z)
        rect_xz(d, x - w / 2, x - opening / 2, base, base + h, z, tone)
        rect_xz(d, x + opening / 2, x + w / 2, base, base + h * (brk if brk < 1 else 1.0), z, tone)
        if brk >= 1.0:
            rect_xz(d, x - w / 2 - 0.3, x + w / 2 + 0.3, base + h - 1.35, base + h, z, tone)
        if window:
            rect_xz(d, x - opening / 2, x + opening / 2, base, base + h * 0.34, z, tone)

    def draw_column(col):
        x, z, total, cap = col
        tone = depth_tone(STONE_COL, z)
        base = 4.35
        rect_xz(d, x - 1.36, x + 1.36, base, base + 1.77, z, lerp(tone, (80, 70, 55), 0.25))
        shaft_top = base + total - (3.2 if cap else 0.0)
        rect_xz(d, x - 0.8, x + 0.8, base + 1.77, shaft_top, z, tone)
        if cap:
            rect_xz(d, x - 1.05, x + 1.05, shaft_top, base + total, z, lerp(tone, (90, 78, 60), 0.18))

    items = [(fr[1], lambda fr=fr: draw_frame(fr)) for fr in FRAMES]
    items += [(j[1], lambda j=j: rect_xz(d, j[0] - j[2] / 2, j[0] + j[2] / 2, 4.35, 4.35 + j[3], j[1],
                                          depth_tone(STONE_DARK, j[1]))) for j in JAMBS]
    items += [(c[1], lambda c=c: draw_column(c)) for c in COLUMNS]
    items.append((-11.7, lambda: rect_xz(d, 17.3, 32.8, 4.35, 8.05, -11.7, depth_tone(STONE, -11.7))))
    for _, fn in sorted(items, key=lambda it: it[0]):
        fn()

    # Mil-e Azhdaha: the nine fixed limestone sheets, far to near, then the
    # tower's socle/shaft/ruined top. All points go through the same baked
    # scale/yaw/translation and fixed camera as the GL scene.
    slope_boxes = sorted(
        vf.MIL_SLOPE_BOXES,
        key=lambda b: vf.mil_transform((b[0], b[1], b[2]))[2],
    )
    for cx, cy, cz, sx, sy, sz in slope_boxes:
        mil_box(
            d, cx, cz, cy - sy / 2.0, cy + sy / 2.0, sx, sz,
            front=(148, 104, 62), side=(114, 80, 48), top=(188, 138, 86),
        )
    mil_box(d, 0, 0, 0.0, 1.0, 4.85, 4.85,
            front=(162, 116, 70), side=(122, 86, 52), top=(196, 148, 94))
    mil_box(d, 0, 0, 1.0, 5.7, 3.80, 3.80,
            front=(188, 136, 82), side=(140, 98, 58), top=(204, 158, 102))
    mil_box(d, 0, 0, 5.7, 7.45, 3.35, 3.35,
            front=(176, 126, 76), side=(130, 92, 56), top=(198, 150, 96))

    # Annotations.
    try:
        font = ImageFont.load_default(size=26)
        small = ImageFont.load_default(size=21)
    except TypeError:
        font = small = ImageFont.load_default()

    def label(xy, text, fnt=font, fill=(255, 255, 255)):
        for dx, dy in ((1, 0), (-1, 0), (0, 1), (0, -1)):
            d.text((xy[0] + dx, xy[1] + dy), text, font=fnt, fill=(10, 10, 16))
        d.text(xy, text, font=fnt, fill=fill)

    def dashed_h(y, colour=(255, 255, 255, 170)):
        for x in range(0, W, 34):
            d.line([(x, y), (min(x + 20, W), y)], fill=colour, width=2)

    dashed_h(HORIZON_Y)
    label((W - 330, HORIZON_Y - 34), "horizon 0.70")
    dashed_h(TOE_Y, (255, 214, 150, 190))
    label((W - 330, TOE_Y + 8), f"platform toe {FRAMING['base_fraction_from_top']:.2f}")
    for x in range(int(slope_l), int(slope_r), 34):
        d.line([(x, slope_front), (min(x + 20, slope_r), slope_front)],
               fill=(170, 230, 255, 190), width=2)
    label((slope_l + 8, slope_front + 34), "Mil slope front", small)

    readout_bottom = FRAMING["readout_bottom"]
    rx, ry, rw, rh = 60, readout_bottom - 45 * DENSITY, 690, 45 * DENSITY
    for x in range(int(rx), int(rx + rw), 22):
        d.line([(x, ry), (min(x + 13, rx + rw), ry)], fill=(255, 255, 255, 210), width=2)
        d.line([(x, ry + rh), (min(x + 13, rx + rw), ry + rh)], fill=(255, 255, 255, 210), width=2)
    for y in range(int(ry), int(ry + rh), 22):
        d.line([(rx, y), (rx, min(y + 13, ry + rh))], fill=(255, 255, 255, 210), width=2)
        d.line([(rx + rw, y), (rx + rw, min(y + 13, ry + rh))], fill=(255, 255, 255, 210), width=2)
    label((rx + 12, ry + 12), "readout - kept clear of stone", small)
    label((rx + 12, ry + 44), "22:41 | Astronomical night", font)

    col_x, col_y = px(vf.TALLEST_COLUMN_TOP)
    label((col_x + 18, col_y + 6), "19.2 m column", small)

    tower_x, tower_y = px(vf.MIL_TOWER_TOP)
    label((tower_x - 202, tower_y + 4), "Mil-e Azhdaha", small)

    label((36, H - 74), "Framing diagram: Persepolis left; open plain; Mil tower/slope cropped at right.", small)
    label((36, H - 44), "Massing via the combined model's camera; on device, GL renders the full relief.", small)

    out = os.path.join(os.path.dirname(os.path.abspath(__file__)), "..", "..", "docs", "persepolis-framing.png")
    img.save(os.path.normpath(out))
    print(f"wrote {out}")


if __name__ == "__main__":
    main()
