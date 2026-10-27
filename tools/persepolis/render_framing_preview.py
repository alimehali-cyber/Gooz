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
(far Zagros range, folded Kuh-e Rahmat massif with benches/water-gap/
talus, steppe plain, the localized earthwork around both footprints,
broad layered Pulvar river, space-colonization tree parkland, three-band
batched grass with Voronoi clumps and steppe species)
replicated from SceneGroundRenderer — the python profile functions below
mirror LandscapeProfile.kt and are where the composition was tuned.
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


def mountain_samples(base_y, max_h, env_pts, seed, n=140, sharpness=1.15,
                     bench_amp=0.0, bench_step=0.14, notches=()):
    out = []
    for i in range(n + 1):
        p = i / n
        h = max_h * envelope(p, env_pts) * (0.42 + 0.58 * ridged1(p, seed) ** sharpness)
        if bench_amp > 0:  # soft terracing: limestone benches between marl slopes
            step = max_h * bench_step
            q = h / step
            fl = math.floor(q)
            fr = min(max((q - fl - 0.35) / 0.30, 0.0), 1.0)
            terraced = (fl + fr * fr * (3 - 2 * fr)) * step
            h += (terraced - h) * bench_amp
        for np_, nw, nd in notches:  # water-gap valleys
            d = (p - np_) / nw
            h *= 1.0 - nd * math.exp(-d * d)
        out.append((p * W, base_y - h, max(h, 0.0)))
    return out


def draw_mountain(d, samples, base_y, lit, shade, haze, haze_amt, sun_sign,
                  facet=0.55, seed=7, strata_amp=0.0, dip=0.0, hinge_x=None, cap=0.0):
    """Facet strips: brightness from profile slope vs sun side + micro noise,
    six horizontal slices per strip for top-light -> hazy-base falloff.
    dip/hinge_x chevron the strata phase across an anticline hinge; cap
    lightens bare limestone near the highest crests."""
    slices = 6
    max_h = max(h for _, _, h in samples) or 1.0
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
            if strata_amp:  # whisper of bedding: bands dipping away from the fold hinge
                phase = (base_y - ya) * 0.085 + 1.7 * math.sin(i * 0.11)
                if hinge_x is not None:
                    phase += dip * abs(x0 - hinge_x) * 0.020
                k = 1.0 + strata_amp * math.sin(phase)
                c = tuple(min(255, int(v * k)) for v in c)
            if cap and s < 2:  # bare pale limestone on the highest crests
                t = min(max(((h0 + h1) / 2 / max_h - 0.72) / 0.2, 0.0), 1.0)
                c = lerp(c, (216, 196, 164), cap * t * (1.0 - s / 2.0))
            d.polygon([(x0, ta), (x1, td), (x1, tc), (x0, tb)], fill=c)
    # Crest catch-light, kept whisper-thin.
    crest = [(x, t) for x, t, _ in samples if _ > 2.0]
    if len(crest) > 1:
        d.line(crest, fill=(*lerp(lit, (255, 240, 220), 0.45), 70), width=2)


def tree_skeleton(seed, aspect=1.25, trunk_fraction=0.34, attractor_count=90, lean=0.0):
    """2D space colonization (Runions et al.) + da Vinci pipe radii.
    Unit space: base (0,0), crown top y=1, x in +-aspect/2. Returns segments
    (x0,y0,x1,y1,r0,r1,depth,terminal). Mirror of LandscapeProfile.treeSkeleton."""
    counter = [0]

    def rnd():
        counter[0] += 1
        return _hash01(seed * 131.0 + counter[0] * 31.0 + 7.0)

    crown_base = min(max(trunk_fraction, 0.05), 0.8)
    crown_h = 1.0 - crown_base
    cy = crown_base + crown_h / 2
    rx, ry = aspect / 2, crown_h / 2
    attractors = []
    guard = 0
    while len(attractors) < attractor_count and guard < attractor_count * 40:
        guard += 1
        x = (rnd() * 2 - 1) * rx
        y = cy + (rnd() * 2 - 1) * ry
        e = (x / rx) ** 2 + ((y - cy) / ry) ** 2
        if e > 1 or (e < 0.14 and rnd() < 0.65):
            continue
        if y > cy:
            y = cy + (y - cy) * 0.82
        attractors.append([x + lean * (y - crown_base), y, True])

    nx, ny, parent, depth = [], [], [], []

    def add_node(x, y, p):
        nx.append(x); ny.append(y); parent.append(p)
        depth.append(0 if p < 0 else depth[p] + 1)
        return len(nx) - 1

    root = add_node(0.0, 0.0, -1)
    mid = add_node(lean * 0.4, crown_base * 0.55, root)
    fork = add_node(lean, crown_base, mid)
    step = crown_h / 13.0
    for ang in (-52.0, -8.0, 44.0):
        a = math.radians(ang)
        add_node(nx[fork] + math.sin(a) * step * 1.7, ny[fork] + math.cos(a) * step * 1.7, fork)

    influence, kill = step * 4.4, step * 1.15
    alive = len(attractors)
    for _ in range(110):
        if alive == 0 or len(nx) >= 420:
            break
        claims = {}
        for at in attractors:
            if not at[2]:
                continue
            best, best_d = -1, 1e18
            for ni in range(len(nx)):
                if ni in (root, mid):
                    continue
                dx, dy = at[0] - nx[ni], at[1] - ny[ni]
                d = dx * dx + dy * dy
                if d < best_d:
                    best, best_d = ni, d
            if best < 0:
                continue
            dist = math.sqrt(best_d)
            if dist < kill:
                at[2] = False
                alive -= 1
            elif dist < influence:
                c = claims.setdefault(best, [0.0, 0.0, 0.0])
                c[0] += (at[0] - nx[best]) / dist
                c[1] += (at[1] - ny[best]) / dist
                c[2] += 1
        if not claims:
            break
        for node, c in claims.items():
            if len(nx) >= 420:
                break
            dx = c[0] / c[2] + (nx[node] - lean) * 0.10
            dy = c[1] / c[2] + 0.20
            ln = max(math.hypot(dx, dy), 1e-5)
            s = step * (0.85 + 0.3 * rnd())
            add_node(nx[node] + dx / ln * s, ny[node] + dy / ln * s, node)

    children = [[] for _ in nx]
    for i, p in enumerate(parent):
        if p >= 0:
            children[p].append(i)
    radius = [0.0] * len(nx)

    def compute_radius(i):
        if not children[i]:
            r = 1.0
        else:
            r = (sum(compute_radius(ch) ** 2.05 for ch in children[i])) ** (1 / 2.05) * 1.03
        radius[i] = r
        return r

    compute_radius(root)
    scale_r = 0.040 / max(radius[root], 1e-5)
    radius = [max(r * scale_r, 0.0035) for r in radius]
    return [
        (nx[p], ny[p], nx[i], ny[i], radius[p], radius[i], depth[i], not children[i])
        for i, p in enumerate(parent) if p >= 0
    ]


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
    # photographic path): far Zagros range -> steppe plain ->
    # Kuh-e Rahmat massif -> piedmont foothills + alluvial fans -> field
    # mosaic. Sunset schematic: sun low at the left.
    SUN_SIGN, SUN_X = -1.0, 0.22 * W
    far_base = GROUND_TOP + H * 0.006  # strips fill to the plain: no sky sliver under the range
    far_samples = mountain_samples(
        far_base, H * 0.130,
        [(0, .70), (.18, .95), (.38, .75), (.55, .92), (.72, .88), (.88, 1.0), (1, .90)],
        seed=11, sharpness=1.15, bench_amp=0.12, bench_step=0.16,
    )
    draw_mountain(d, far_samples, far_base, lit=(126, 108, 140), shade=(94, 84, 116),
                  haze=(240, 170, 118), haze_amt=0.50, sun_sign=SUN_SIGN, facet=0.32,
                  seed=3, cap=0.25)
    # (No horizon mist shelf — removed in the renderer: it read as a white band
    # across the canvas at the readout's day/date line.)
    img = img.convert("RGB")
    d = ImageDraw.Draw(img, "RGBA")
    # Semi-arid steppe plain (dry olive-straw, darkening to the viewer).
    for y in range(int(GROUND_TOP), H):
        t = (y - GROUND_TOP) / (H - GROUND_TOP)
        d.line([(0, y), (W, y)], fill=lerp((96, 86, 58), (18, 20, 14), max(t, 0.0) ** 0.8))
    # Kuh-e Rahmat: the great limestone wall at Persepolis' back. Envelope
    # keeps a low shoulder behind the ruins/readout, crest at the right.
    massif_base = TOE_Y - H * 0.004
    massif = mountain_samples(
        massif_base, H * 0.205,
        [(0, .32), (.14, .40), (.30, .50), (.45, .58), (.58, .62), (.68, .88),
         (.76, 1.0), (.86, .88), (1, .72)],
        seed=29, bench_amp=0.22, bench_step=0.13, notches=[(0.63, 0.030, 0.38)],
    )
    draw_mountain(d, massif, massif_base, lit=(150, 108, 82), shade=(86, 64, 60),
                  haze=(226, 158, 110), haze_amt=0.30, sun_sign=SUN_SIGN, facet=0.65,
                  seed=5, strata_amp=0.045, dip=0.9, hinge_x=W * 0.76, cap=0.38)
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
    # Talus cones under the steepest faces: scree at the angle of repose.
    steep = sorted(
        range(4, len(massif) - 4),
        key=lambda i: -abs(massif[i + 3][2] - massif[i - 3][2]),
    )
    talus_x = []
    for i in steep:
        x, _, h = massif[i]
        if h < H * 0.07 or any(abs(x - t) < W * 0.09 for t in talus_x):
            continue
        talus_x.append(x)
        apex_y = massif_base - h * 0.38
        half_w = h * 0.38 * 1.43  # ~35 degrees of repose
        d.polygon([(x, apex_y), (x - half_w, massif_base + H * 0.012),
                   (x + half_w, massif_base + H * 0.012)], fill=(196, 166, 128, 40))
        for r in (-0.5, 0.0, 0.5):
            d.line([(x, apex_y), (x + half_w * r, massif_base + H * 0.010)],
                   fill=(220, 190, 148, 24), width=1)
        if len(talus_x) >= 5:
            break
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
    for p, spread in [(0.56, .075), (0.63, .095), (0.82, .08), (0.93, .07)]:
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
    river_y = min(max(FRAMING["mil_slope_front_y"] + H * 0.062, H * 0.895), H * 0.945)
    def river_p(x):
        return min(max((x + 20) / (W + 40), 0.0), 1.0)

    def river_at(x):
        p = river_p(x)
        return (river_y + H * 0.017 * math.sin(p * 4.6 + 0.7)
                + H * 0.008 * fbm1(p * 3.1, 41, 3) + p * H * 0.014)

    def river_halfw(x, y):
        p = river_p(x)
        depth = min(max((y / H - 0.86) / 0.12, 0.0), 1.0)
        pool = 1.0 + 0.45 * max(0.0, math.sin(p * 4.6 + 2.0))  # wider pools at bends
        return H * (0.021 + 0.025 * depth) * pool  # ~3.8x the original stream (doubled twice)

    def bend(x):  # +1 bulging toward viewer (drives thalweg / point bars)
        return math.sin(river_p(x) * 4.6 + 0.7)

    def ribbon(scale, offset=0.0):
        pts = []
        for i in range(29):
            x = -20 + (W + 40) * i / 28.0
            yc = river_at(x)
            pts.append((x, yc + bend(x) * offset * river_halfw(x, yc) - river_halfw(x, yc) * scale))
        for i in range(28, -1, -1):
            x = -20 + (W + 40) * i / 28.0
            yc = river_at(x)
            pts.append((x, yc + bend(x) * offset * river_halfw(x, yc) + river_halfw(x, yc) * scale))
        return pts

    center = [(-20 + (W + 40) * i / 28.0, river_at(-20 + (W + 40) * i / 28.0)) for i in range(29)]

    d.polygon(ribbon(1.18), fill=(52, 52, 40, 200))               # wet bank band
    d.polygon(ribbon(1.0), fill=(58, 72, 66))                     # shallow edges: bed showing
    d.polygon(ribbon(0.84), fill=(196, 132, 88, 225))             # sunset sky reflection
    d.polygon(ribbon(0.22, offset=0.45), fill=(30, 44, 52, 140))  # deep thalweg, outer bank
    for i in range(8):  # dark slow water breaking the reflection
        x = W * _hash01(i * 9.7 + 4.4)
        soft_drift(x, river_at(x), W * 0.07, river_halfw(x, river_at(x)) * 1.0,
                   peak_alpha=0.18, layers=8, rgb=(40, 54, 60))
    for x, yc in center:  # pale gravel point bars on the inside of bends
        b = bend(x)
        if abs(b) > 0.72:
            soft_drift(x, yc - b * river_halfw(x, yc) * 0.82, W * 0.07,
                       river_halfw(x, yc) * 0.5, peak_alpha=0.30, layers=8, rgb=(186, 156, 116))
    for i in range(90):  # flow ripples
        x = W * _hash01(i * 17.9 + 1.3)
        yc = river_at(x)
        y = yc + (_hash01(i * 7.3) - 0.5) * 1.7 * river_halfw(x, yc)
        ln = H * (0.004 + 0.010 * _hash01(i * 3.9))
        d.line([(x, y), (x + ln, y + ln * 0.10)], fill=(238, 206, 170, 52), width=2)
    for i in range(26):  # submerged pebbles in the near shallows
        x = W * _hash01(i * 23.1 + 5.5)
        yc = river_at(x)
        y = yc + river_halfw(x, yc) * (0.62 + 0.3 * _hash01(i * 4.7))
        r = 2 + 3 * _hash01(i * 6.3)
        d.ellipse([x - r, y - r * 0.5, x + r, y + r * 0.5], fill=(150, 132, 104, 90))
    d.line([(x, y - river_halfw(x, y)) for x, y in center], fill=(24, 30, 26, 110), width=2)
    d.line([(x, y + river_halfw(x, y)) for x, y in center], fill=(172, 144, 106, 100), width=2)
    for i in range(12):  # riparian green along the far bank
        x = W * (i + _hash01(i * 5.1)) / 12
        soft_drift(x, river_at(x) - river_halfw(x, river_at(x)) - H * 0.004,
                   W * 0.13, H * 0.016, peak_alpha=0.20, layers=10, rgb=(56, 72, 44))
    # Glitter: broad sheen in the sun column + stochastic sparkles + rare stars.
    soft_drift(SUN_X, river_at(SUN_X), W * 0.52, river_halfw(SUN_X, river_at(SUN_X)) * 1.7,
               peak_alpha=0.10, layers=10, rgb=(255, 210, 150))
    soft_drift(SUN_X, river_at(SUN_X), W * 0.30, river_halfw(SUN_X, river_at(SUN_X)) * 1.5,
               peak_alpha=0.20, layers=10, rgb=(255, 218, 154))
    for i in range(64):
        x = W * _hash01(i * 13.7 + 2.2)
        yc = river_at(x)
        hw = river_halfw(x, yc)
        g = math.exp(-(((x - SUN_X) / (W * 0.17)) ** 2))
        if _hash01(i * 19.3 + 8.8) > 0.25 + 0.75 * g:  # Poisson-ish thinning
            continue
        y = yc + (_hash01(i * 7.9) - 0.5) * 1.5 * hw
        a = int(235 * g * (0.35 + 0.65 * _hash01(i * 3.1)))
        ln = 8 + 30 * _hash01(i * 5.7) * (0.4 + g)
        d.line([(x, y), (x + ln, y)], fill=(255, 220, 160, a), width=3)
        if g > 0.78 and _hash01(i * 29.7) > 0.6:  # star sparkle
            r = 3 + 3 * _hash01(i * 4.1)
            d.line([(x + ln / 2, y - r), (x + ln / 2, y + r)],
                   fill=(255, 230, 180, int(a * 0.5)), width=2)
    # Broken reflections of the two foreground trees (drawn below).
    for tx, th in [(W * 0.115, H * 0.068), (W * 0.875, H * 0.058)]:
        yc = river_at(tx)
        bot = yc + river_halfw(tx, yc)
        for j in range(6):
            y = bot - (j + 0.5) * (th * 0.55 / 6)
            if y < yc - river_halfw(tx, yc):
                break
            wdt = th * 1.1 * (0.55 - 0.06 * j)
            xo = (_hash01(j * 7.7 + tx) - 0.5) * 8
            d.line([(tx + xo - wdt / 2, y), (tx + xo + wdt / 2, y)],
                   fill=(30, 40, 30, int(80 * (1 - j / 6))), width=3)
    # Reed clumps (Phragmites) at the banks: curved stems, feathery plumes.
    for ci in range(7):
        p = 0.08 + 0.84 * (ci + _hash01(ci * 9.3)) / 7
        x0 = -20 + (W + 40) * p
        yc = river_at(x0)
        side = -1 if ci % 2 == 0 else 1
        by = yc + side * river_halfw(x0, yc) * 1.02
        for si in range(8):
            x = x0 + (_hash01(ci * 31 + si * 7) - 0.5) * W * 0.018
            bh = H * (0.014 + 0.012 * _hash01(ci * 17 + si * 3))
            lean = (_hash01(ci * 13 + si * 5) - 0.5) * bh * 0.35
            pts = [(x + lean * (t ** 1.6), by - bh * t) for t in (0, .33, .66, 1.0)]
            d.line(pts, fill=(72, 88, 54, 190), width=2)
            if si % 3 == 0:
                d.ellipse([x + lean - 2, by - bh - 6, x + lean + 2, by - bh + 2],
                          fill=(178, 150, 110, 160))

    # Space-colonization skeletons, generated once (mirrors the cached Kotlin ones).
    TREE_SKELS = [
        tree_skeleton(101, aspect=1.30, trunk_fraction=0.36, attractor_count=110, lean=0.03),
        tree_skeleton(202, aspect=1.15, trunk_fraction=0.40, attractor_count=100, lean=-0.02),
        tree_skeleton(303, aspect=1.45, trunk_fraction=0.30, attractor_count=85, lean=0.05),
        tree_skeleton(505, aspect=1.05, trunk_fraction=0.44, attractor_count=90, lean=0.08),
        tree_skeleton(606, aspect=1.55, trunk_fraction=0.26, attractor_count=95, lean=-0.06),
    ]
    BUSH_SKELS = [
        tree_skeleton(404 + k * 77, aspect=1.5, trunk_fraction=0.12, attractor_count=46)
        for k in range(3)
    ]

    def draw_skeleton(cx, by, hgt, skel, flip, foliage, seed, silvery=False):
        sx = -1.0 if flip else 1.0

        def P(x, y):
            return (cx + sx * x * hgt, by - y * hgt)

        # Ground shadow thrown away from the sun (sun at left in this schematic).
        d.ellipse([cx - hgt * 0.30, by - hgt * 0.030, cx + hgt * 1.05, by + hgt * 0.045],
                  fill=(14, 16, 10, 100))
        # Woody structure, thickest first; a darker pass offset anti-sun gives roundness.
        for x0, y0, x1, y1, r0, r1, dep, term in sorted(skel, key=lambda s: -s[4]):
            wdt = max(1, int((r0 + r1) * hgt))
            p0, p1 = P(x0, y0), P(x1, y1)
            d.line([(p0[0] + wdt * 0.22, p0[1]), (p1[0] + wdt * 0.22, p1[1])],
                   fill=(34, 26, 20), width=wdt)
            d.line([p0, p1], fill=(58, 44, 32), width=wdt)
        # Leaf-cluster dabs at the terminals: shadow core, body tones, sun rim.
        # Silvery = Artemisia sage scrub (grey-green); else pistachio olive.
        base_dark, base_body, base_rim = ((86, 96, 78), (118, 130, 106), (168, 180, 156)) if silvery \
            else ((44, 56, 36), (52, 64, 42), (104, 116, 74))
        ci = 0
        for x0, y0, x1, y1, r0, r1, dep, term in skel:
            if not term and dep < 7:
                continue
            ci += 1
            if _hash01(seed * 7.1 + ci * 3.3) > foliage:
                continue
            px, py = P(x1, y1)
            cr = hgt * (0.045 + 0.040 * _hash01(seed * 3.7 + ci * 5.1))
            tone = 0.85 + 0.3 * _hash01(seed * 5.3 + ci * 7.7)
            for dab in range(3):
                ox = (_hash01(seed * 9.7 + ci * 3.1 + dab * 0.7) - 0.5) * cr * 1.15
                oy = (_hash01(seed * 8.9 + ci * 3.7 + dab * 1.1) - 0.5) * cr * 0.75
                r = cr * (0.42 + 0.30 * _hash01(seed * 8.3 + ci * 4.1 + dab * 1.3))
                if dab == 0:
                    col, sx_ = tuple(int(c * tone * 0.72) for c in base_dark), cr * 0.2
                    alpha = 235
                elif dab == 2:
                    col, sx_ = base_rim, -cr * 0.15  # sun at left in this schematic
                    alpha = 140
                else:
                    col, sx_ = tuple(int(c * tone) for c in base_body), 0.0
                    alpha = 240
                d.ellipse([px + ox + sx_ - r, py + oy - r * 0.62,
                           px + ox + sx_ + r, py + oy + r * 0.62], fill=(*col, alpha))

    def bush(cx, by, s, idx):
        draw_skeleton(cx, by, s * 2.1, BUSH_SKELS[idx % 3], idx % 2 == 0, 0.62,
                      seed=idx * 3 + 1, silvery=(idx % 3 == 1))

    for i in range(7):  # foothill scrub only — the river's near side stays clean
        x = W * _hash01(i * 19.7 + 3.3)
        y, s = TOE_Y - H * 0.004 + H * 0.010 * _hash01(i * 3.9), 6 + 5 * _hash01(i * 7.1)
        bush(x, y, s, i)
    # Lone pistachio in the gap; foreground trees on the near bank (bases from the
    # widened river's actual edge).
    def near_bank_y(x):
        return river_at(x) + river_halfw(x, river_at(x)) * 1.35 + H * 0.006

    draw_skeleton((plat_r + slope_l) / 2, TOE_Y + H * 0.020, H * 0.040, TREE_SKELS[2], False, 0.9, seed=7)
    draw_skeleton(W * 0.115, near_bank_y(W * 0.115), H * 0.068, TREE_SKELS[0], False, 0.95, seed=11)
    draw_skeleton(W * 0.875, near_bank_y(W * 0.875), H * 0.058, TREE_SKELS[1], True, 0.95, seed=13)
    # 25 more trees: riparian gallery, canal line, foothill woodland, plain, near bank.
    def in_apron(x, y):
        return ((plat_l - W * 0.030 <= x <= plat_r + W * 0.105 and y < TOE_Y + H * 0.095) or
                (x > slope_l - W * 0.065 and y < FRAMING["mil_slope_front_y"] + H * 0.095))

    def plant(x, y, hgt, idx):
        if in_apron(x, y) and y < TOE_Y + H * 0.038:
            return
        yc = river_at(x)
        if abs(y - yc) < river_halfw(x, yc) * 1.22:
            return
        draw_skeleton(x, y, hgt, TREE_SKELS[idx % len(TREE_SKELS)], idx % 2 == 1,
                      0.82 + 0.15 * _hash01(idx * 7.01), seed=idx * 7 + 3)

    for i in range(9):
        x = W * (0.05 + 0.90 * (i + _hash01(i * 3.1 + 6.01)) / 9)
        y = river_at(x) - river_halfw(x, river_at(x)) * (1.45 + 0.30 * _hash01(i * 5.2 + 6.03))
        plant(x, y, H * (0.030 + 0.018 * _hash01(i * 7.3 + 6.07)), 100 + i)
    for i in range(6):
        x = W * (0.08 + 0.84 * (i + _hash01(i * 3.3 + 6.13)) / 6)
        y = canal_y - H * 0.004 + (x / W) * H * 0.010
        plant(x, y, H * (0.020 + 0.012 * _hash01(i * 5.7 + 6.17)), 120 + i)
    for i in range(6):
        x = W * _hash01(i * 11.5 + 6.19)
        y = TOE_Y + H * (0.006 + 0.024 * _hash01(i * 7.2 + 6.31))
        plant(x, y, H * (0.022 + 0.012 * _hash01(i * 3.3 + 6.33)), 140 + i)
    for i in range(4):
        x = W * (0.10 + 0.80 * _hash01(i * 13.7 + 6.41))
        y = H * (0.855 + 0.045 * _hash01(i * 5.3 + 6.43))
        plant(x, y, H * (0.026 + 0.014 * _hash01(i * 7.7 + 6.47)), 160 + i)
    for j, xf in enumerate((0.34, 0.63)):
        x = W * (xf + (_hash01(j * 3.3 + 6.53) - 0.5) * 0.03)
        plant(x, near_bank_y(x), H * (0.050 + 0.012 * _hash01(j * 5.5 + 6.57)), 180 + j)
    # Grass v3 mirror (simplified): carpet colour field + true Voronoi clumps +
    # cubic-Bezier blades in three bands + Stipa/Bromus/riparian hero species.
    def blade_bend(t, stiff):
        c1 = 0.08 + 0.30 * stiff
        c2 = 0.45 + 0.35 * stiff
        u = 1 - t
        return 3 * u * u * t * c1 + 3 * u * t * t * c2 + t ** 3

    def grass_clump(xf, depth):
        cw, cd_ = 0.055, 0.085
        gx, gy = int(xf / cw), int(depth / cd_)
        f1 = f2 = 1e9
        best = 0
        for cy in range(gy - 1, gy + 2):
            for cx in range(gx - 1, gx + 2):
                idx = cx + cy * 64
                jx = (cx + 0.5 + (_hash01(idx * 3.17 + 5.17) - 0.5) * 0.84) * cw
                jy = (cy + 0.5 + (_hash01(idx * 5.23 + 5.23) - 0.5) * 0.84) * cd_
                dd = (xf - jx) ** 2 + (depth - jy) ** 2
                if dd < f1:
                    f2, f1, best = f1, dd, idx
                elif dd < f2:
                    f2 = dd
        edge = min(max((math.sqrt(f2) - math.sqrt(f1)) / (cw * 0.9), 0.0), 1.0)
        return (0.70 + 0.65 * _hash01(best * 11.3 + 7.3), (_hash01(best * 17.7 + 13.5) - .5) * 1.1,
                _hash01(best * 23.9 + 19.7), _hash01(best * 31.3 + 29.9), edge)

    def g_allowed(x, y):
        if in_apron(x, y):
            return False
        yc = river_at(x)
        return abs(y - yc) >= river_halfw(x, yc) * 1.26

    def g_moist(x, y, depth):
        yc = river_at(x)
        m = 0.5 + 0.5 * fbm1(x / W * 4.2 + depth * 1.7, 91, 3)
        m += max(0.0, 1.0 - abs(y - yc) / (river_halfw(x, yc) * 2.6 + H * 0.008)) * 0.5
        cy = canal_y - H * 0.004 + (x / W) * H * 0.010
        m += max(0.0, 1.0 - abs(y - cy) / (H * 0.018)) * 0.35
        return min(m, 1.0)

    def bpt(bx, by, bh, lean, stiff, t):
        return (bx + lean * bh * blade_bend(t, stiff), by - bh * t * (1 - 0.10 * t * abs(lean)))

    ROOT_DRY, TIP_DRY = (74, 58, 30), (196, 164, 104)
    ROOT_GRN, TIP_GRN = (38, 52, 26), (126, 148, 85)
    for i in range(70):  # carpet colour field
        x = W * _hash01(i * 13.3 + 8.11)
        depth = _hash01(i * 17.5 + 8.09) ** 1.25
        y = GROUND_TOP + (H - GROUND_TOP) * depth
        if not g_allowed(x, y):
            continue
        moist = g_moist(x, y, depth)
        soft_drift(x, y, W * (0.10 + 0.10 * _hash01(i * 7.3 + 8.13)), H * (0.010 + 0.016 * depth),
                   peak_alpha=0.15, layers=6, rgb=TIP_GRN if moist > 0.52 else TIP_DRY)

    def stroke_blade(bx, by, bh, lean, stiff, dry):
        root, tip = (ROOT_DRY, TIP_DRY) if dry else (ROOT_GRN, TIP_GRN)
        pts = [bpt(bx, by, bh, lean, stiff, t) for t in (0, .28, .52, .76, 1.0)]
        d.line(pts[:3], fill=(*root, 200), width=2)
        d.line(pts[2:], fill=(*tip, 190), width=2)

    for i in range(900):  # far micro-blades
        x = W * _hash01(i * 13.7 + 8.21)
        depth = 0.46 * _hash01(i * 17.3 + 8.23) ** 1.15
        y = GROUND_TOP + (H - GROUND_TOP) * depth
        if not g_allowed(x, y):
            continue
        moist = g_moist(x, y, depth)
        hm, cl, dryn, dens, edge = grass_clump(x / W, depth)
        if _hash01(i * 19.9 + 8.27) > (0.18 + 0.82 * moist) * (0.30 + 0.70 * dens):
            continue
        soft = 0.45 + 0.55 * edge
        bh = H * (0.0035 + 0.010 * depth) * (1 + (hm - 1) * soft) * (0.75 + 0.5 * _hash01(i * 23.7 + 8.29))
        lean = cl * soft + (_hash01(i * 29.1 + 8.31) - 0.5) * 0.5
        drymix = 0.5 * (1 - moist) + 0.5 * dryn
        stroke_blade(x, y, bh, lean, 0.25 + 0.45 * (1 - drymix), drymix > 0.5)
    for i in range(380):  # mid tufts
        x = W * _hash01(i * 13.1 + 8.39)
        depth = 0.30 + 0.62 * _hash01(i * 17.7 + 8.43)
        y = GROUND_TOP + (H - GROUND_TOP) * depth
        if not g_allowed(x, y):
            continue
        moist = g_moist(x, y, depth)
        hm, cl, dryn, dens, edge = grass_clump(x / W, depth)
        if _hash01(i * 19.3 + 8.53) > (0.22 + 0.78 * moist) * (0.35 + 0.65 * dens):
            continue
        soft = 0.45 + 0.55 * edge
        drymix = 0.5 * (1 - moist) + 0.5 * dryn
        for b in range(5 + int(5 * _hash01(i * 11.1 + 8.57))):
            bx = x + (_hash01(i * 101 + b * 37 + 8.59) - 0.5) * W * 0.011
            bh = H * (0.005 + 0.013 * depth) * (1 + (hm - 1) * soft) * (0.7 + 0.6 * _hash01(i * 57 + b * 19 + 8.63))
            lean = cl * soft + (_hash01(i * 67 + b * 23 + 8.67) - 0.5) * 0.55
            stroke_blade(bx, y, bh, lean, 0.25 + 0.45 * (1 - drymix), drymix > 0.5)
    for i in range(110):  # hero tufts on the near strip
        x = W * _hash01(i * 13.3 + 8.77)
        depth = 0.84 + 0.16 * _hash01(i * 17.1 + 8.81)
        y = GROUND_TOP + (H - GROUND_TOP) * depth
        if not g_allowed(x, y):
            continue
        moist = g_moist(x, y, depth)
        hm, cl, dryn, dens, edge = grass_clump(x / W, depth)
        if _hash01(i * 19.7 + 8.83) > (0.30 + 0.70 * moist) * (0.40 + 0.60 * dens):
            continue
        soft = 0.45 + 0.55 * edge
        drymix = 0.5 * (1 - moist) + 0.5 * dryn
        stiff = 0.22 + 0.50 * (1 - drymix)
        soft_drift(x, y, H * 0.034, H * 0.008, peak_alpha=0.2, layers=5, rgb=(10, 12, 6))
        pick = _hash01(i * 3.3 + 8.87)
        species = 2 if moist > 0.62 else (0 if drymix > 0.58 and pick < 0.55 else (1 if pick < 0.82 else 2))
        fillc = lerp(ROOT_DRY, TIP_DRY, 0.45) if drymix > 0.5 else lerp(ROOT_GRN, TIP_GRN, 0.45)
        ribc = TIP_DRY if drymix > 0.5 else TIP_GRN
        blades = 7 + int(5 * _hash01(i * 11.3 + 8.89))
        for b in range(blades):
            bx = x + (_hash01(i * 101 + b * 37 + 8.93) - 0.5) * W * 0.012
            bh = H * (0.012 + 0.016 * depth) * (1 + (hm - 1) * soft) * (0.72 + 0.55 * _hash01(i * 57 + b * 19 + 8.99))
            if species == 0 and b < 2:
                bh *= 1.3
            lean = cl * soft + (_hash01(i * 67 + b * 23 + 9.01) - 0.5) * 0.6
            if species == 0:
                lean += (b - blades / 2) * 0.07
            wdt = max(1.6, bh * (0.11 if species == 2 else 0.08))
            ts = (0, .2, .4, .6, .8, 1.0)
            pts = [bpt(bx, y, bh, lean, stiff, t) for t in ts]
            left = [(px - wdt * (1 - t) / 2, py) for (px, py), t in zip(pts, ts)]
            right = [(px + wdt * (1 - t) / 2, py) for (px, py), t in zip(reversed(pts), reversed(ts))]
            d.polygon(left + right, fill=(*fillc, 215))
            d.line([pts[1], pts[3], pts[5]], fill=(*ribc, 160), width=1)
            tip = pts[5]
            if b < 2 and species == 0:  # Stipa awn fan
                for a in range(5):
                    spread = (a - 2) * 0.13
                    ln = bh * 0.5
                    d.line([tip, (tip[0] + spread * ln * 0.5, tip[1] - ln * 0.55),
                            (tip[0] + spread * ln, tip[1] - ln)], fill=(216, 192, 150, 150), width=1)
            elif b < 2 and species == 1:  # Bromus nodding panicle
                for a in range(4):
                    dx = (a - 1.5) * bh * 0.055
                    top = tip[1] - bh * (0.05 + 0.04 * (3 - a))
                    d.line([tip, (tip[0] + dx, top), (tip[0] + dx * 1.4, top + bh * 0.07)],
                           fill=(168, 132, 94, 190), width=2)
    for i in range(200):  # dry litter
        x = W * _hash01(i * 13.9 + 9.29)
        depth = 0.3 + 0.7 * _hash01(i * 17.3 + 9.31)
        y = GROUND_TOP + (H - GROUND_TOP) * depth
        if not g_allowed(x, y):
            continue
        if grass_clump(x / W, depth)[2] < 0.45:
            continue
        ln = H * (0.004 + 0.008 * depth)
        d.line([(x, y), (x + ln, y - ln * 0.08)], fill=(*TIP_DRY, 110), width=2)
    # (No corner-shade scrub at the viewer's feet any more: the scene's bottom
    # dissolves into the app backdrop instead — see the merge below.)

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

    # Bottom merge: the last fifth of the scene dissolves through an eased gradient
    # into the app backdrop (a dark stand-in colour here), mirroring HeroSkyCanvas.
    grad = Image.new("L", (1, H), 0)
    gpx = grad.load()
    for yy in range(H):
        t = (yy / H - 0.80) / 0.20
        gpx[0, yy] = 0 if t <= 0 else int(255 * min(1.0, t) ** 1.6)
    img.paste(Image.new("RGB", (W, H), (13, 15, 20)), (0, 0), grad.resize((W, H)))
    d = ImageDraw.Draw(img, "RGBA")

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
