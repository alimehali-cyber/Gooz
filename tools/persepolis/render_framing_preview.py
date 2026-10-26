#!/usr/bin/env python3
"""
Render a framing diagram of the combined landscape in the Home scene.

This is NOT a screenshot and does not pretend to be one: the real render is
the ~149k-triangle GL model on device. What this draws is the model's
*massing* — every Persepolis frame, jamb and column from the combined
file's element tables, plus the Mil-e Azhdaha tower and the nine fixed
sheets of its limestone slope, as flat-shaded blocks — projected through
the model's own fixed camera (verify_framing.projector) with
PersepolisFraming's numbers, over the Home scene's ground stack (ridges,
mist, ground, and the localized earthwork around both footprints)
replicated from SceneGroundRenderer. It exists so the composition can be
eyeballed — Persepolis at half size in the left distance, open plain, then
the tower at the far right with its slope cropped by the edge and grounded
in its own clearing, and the readout clear of stone — without a device.

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

    # SceneGroundRenderer stack: far ridge, mist, mid ridge, ground.
    d.polygon(ridge_points(HORIZON_Y + H * 0.012, H * 0.034, 0.6, 3.0, 7.0), fill=(86, 84, 112))
    mist = Image.new("RGBA", (W, H), (0, 0, 0, 0))
    md = ImageDraw.Draw(mist)
    top, bot = HORIZON_Y - H * 0.055, HORIZON_Y + H * 0.045
    for y in range(int(top), int(bot)):
        a = int(72 * math.sin(math.pi * (y - top) / (bot - top)))
        md.line([(0, y), (W, y)], fill=(244, 196, 150, a))
    img = Image.alpha_composite(img.convert("RGBA"), mist).convert("RGB")
    d = ImageDraw.Draw(img, "RGBA")
    d.polygon(ridge_points(GROUND_TOP, H * 0.020, 2.1, 2.0, 9.0), fill=(48, 56, 50))
    for y in range(int(GROUND_TOP), H):
        t = (y - GROUND_TOP) / (H - GROUND_TOP)
        d.line([(0, y), (W, y)], fill=lerp((62, 72, 52), (12, 18, 14), t))

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
