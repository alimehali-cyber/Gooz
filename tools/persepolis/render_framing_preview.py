#!/usr/bin/env python3
"""
Render a framing diagram of the Persepolis landscape in the Home scene.

This is NOT a screenshot and does not pretend to be one: the ruin's real
render is the ~140k-triangle GL model on device. What this draws is the
model's *massing* — every frame, jamb and column from Persepolis.kt's
element tables as a flat-shaded block — projected through the model's own
fixed camera (verify_framing.projector) with PersepolisFraming's numbers,
over the Home scene's ground stack (ridges, mist, ground, and the localized
earthwork around the platform footprint) replicated from
SceneGroundRenderer. It exists so the composition can be eyeballed — the
ruin at half size in the left distance, skyline against the sky, platform
in the ground band, readout clear of stone — without a device.

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


STONE = (198, 183, 154)
STONE_DARK = (116, 109, 100)
STONE_COL = (208, 193, 162)
PLATFORM_TONE = (172, 158, 132)


def quad(draw, pts, fill):
    draw.polygon([px(p) for p in pts], fill=fill)


def rect_xz(draw, x0, x1, y0, y1, z, fill):
    quad(draw, [(x0, y0, z), (x1, y0, z), (x1, y1, z), (x0, y1, z)], fill)


# Element tables from Persepolis.kt (massing only).
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

    # Localized earthwork around the footprint (SceneGroundRenderer.drawPersepolisGrounding):
    # an irregular worn-earth apron tailing past the platform's right end, a bank, a broken
    # contact shadow under the stone only, drift hummocks and debris flecks.
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
    apron_l = max(plat_l - W * 0.030, -W * 0.02)
    apron_r = min(plat_r + W * 0.175, W * 1.02)
    segs = 36

    def tail_taper(p):
        t = min(max((p - 0.76) / 0.24, 0.0), 1.0)
        return t * t * (3 - 2 * t)

    apron_pts = []
    for i in range(segs + 1):
        p = i / segs
        taper = tail_taper(p)
        top_depth = 0.020 + 0.004 * math.sin(p * math.pi * 4 + 0.7) + 0.002 * math.sin(p * math.pi * 9 + 2.0)
        apron_pts.append((
            apron_l + (apron_r - apron_l) * p,
            TOE_Y - H * top_depth * (1 - taper) + H * 0.012 * taper,
        ))
    for i in range(segs, -1, -1):
        p = i / segs
        taper = tail_taper(p)
        bottom_depth = 0.064 + 0.010 * math.sin(p * math.pi * 4 + 1.3) + 0.005 * math.sin(p * math.pi * 7 + 0.4)
        depth = bottom_depth * (1 - taper) + 0.020 * taper
        apron_pts.append((
            apron_l + (apron_r - apron_l) * p,
            min(TOE_Y + H * depth, H),
        ))
    d.polygon(apron_pts, fill=(190, 160, 110, 64))

    bank_l = plat_r - (plat_r - plat_l) * 0.16
    bank_r = plat_r + W * 0.155
    soft_drift(
        (bank_l + bank_r) / 2,
        min(TOE_Y + H * 0.028, H),
        bank_r - bank_l,
        H * 0.068,
        peak_alpha=0.30,
        layers=16,
        rgb=(183, 154, 108),
    )

    shadow_pts = [(max(plat_l, -W * 0.01), TOE_Y - H * 0.003), (min(plat_r + W * 0.018, W), TOE_Y - H * 0.002)]
    for i in range(32, -1, -1):
        p = i / 32
        depth = 0.018 + 0.005 * math.sin(p * math.pi * 6 + 0.8) + 0.002 * math.sin(p * math.pi * 11 + 2.2)
        shadow_pts.append((
            max(plat_l, -W * 0.01) + (min(plat_r + W * 0.018, W) - max(plat_l, -W * 0.01)) * p,
            min(TOE_Y + H * depth, H),
        ))
    d.polygon(shadow_pts, fill=(0, 0, 0, 58))
    for i in range(7):
        rw_ = (plat_r - plat_l) * (0.13 + rnd(i * 23.7 + 5.7) * 0.16)
        rh_ = H * (0.010 + rnd(i * 31.3 + 0.5) * 0.018)
        cx = apron_l + (apron_r - apron_l) * (0.08 + 0.84 * ((i + rnd(i * 17.1 + 10.1) * 0.8) / 7))
        cy = TOE_Y + H * (0.012 + rnd(i * 23.7 + 5.7) * 0.055)
        soft_drift(cx, cy, rw_, rh_, peak_alpha=0.10, layers=10, rgb=(196, 168, 118))
    for i in range(44):
        x = apron_l + (apron_r - apron_l) * ((i + rnd(i * 41.0 + 21.1) * 0.9) / 44)
        y = min(TOE_Y - H * 0.004 + H * 0.082 * rnd(i * 13.9 + 3.7) ** 2, H)
        ln = (2 + rnd(i * 29.3 + 8.3) * 7) * 1.4
        d.line([(x, y), (x + ln, y + (rnd(i * 41.0 + 21.1) - 0.5) * ln * 0.35)],
               fill=(205, 178, 126, 90), width=2)

    # Platform fronts + floor.
    rect_xz(d, -62, 62, 0.5, 2.6, 7.25, lerp(PLATFORM_TONE, (90, 76, 58), 0.25))       # lower front wall
    rect_xz(d, -60, 60, 2.85, 4.35, 4.24, lerp(PLATFORM_TONE, (90, 76, 58), 0.12))     # upper front
    quad(d, [(12, 0.5, 7.25), (43.5, 0.5, 7.25), (43.5, 2.6, 7.25), (36, 4.35, 7.25), (12, 4.35, 7.25)],
         lerp(PLATFORM_TONE, (240, 170, 110), 0.12))                                    # stair facade
    quad(d, [(-62, 4.35, 4.7), (62, 4.35, 4.7), (62, 4.35, -13), (-62, 4.35, -13)],
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
    items.append((-11.7, lambda: rect_xz(d, 27, 42.5, 4.35, 8.05, -11.7, depth_tone(STONE, -11.7))))
    for _, fn in sorted(items, key=lambda it: it[0]):
        fn()

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

    label((36, H - 74), "Framing diagram: half scale, left distance; massing via PersepolisScene's camera.", small)
    label((36, H - 44), "On device, the GL layer renders the full procedural relief.", small)

    out = os.path.join(os.path.dirname(os.path.abspath(__file__)), "..", "..", "docs", "persepolis-framing.png")
    img.save(os.path.normpath(out))
    print(f"wrote {out}")


if __name__ == "__main__":
    main()
