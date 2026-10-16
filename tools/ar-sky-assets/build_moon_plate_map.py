#!/usr/bin/env python3
"""
Builds `app/src/main/assets/ar/textures/moon_plate.jpg`: an equirectangular lunar surface map whose
near side *is* the photographic plate of the Home Moon card
(`app/src/main/res/drawable/img_full_moon_photo_1785673146290.jpg`), so the AR Moon and the Sky
Canvas Moon carry exactly the card's look while keeping physically exact libration, axis position
angle and phase (they are ray-cast from this map, see GlyphRaster).

Method
  1. Limb fit: the plate's disc centre and radius are fitted to the steepest-descent limb points
     along 720 radii (algebraic circle fit with outlier rejection).
  2. Registration: the plate is an orthographic view of the near side. Its sub-observer point
     (optical libration at the time of exposure) and the position angle of lunar north in the image
     are found by maximising the normalised cross-correlation of high-pass log-albedo between the
     plate and orthographic renderings of the Solar System Scope map (CC BY 4.0) — with a scale and
     sub-pixel centre refinement. Result: λ0 = −0.30°, φ0 = +1.00°, ρ = −4.04°, r = 1.0027 R_limb,
     correlation 0.78 (0.32 without registration).
  3. Reprojection: every texel (lat, lon) is mapped through the fitted orthographic projection into
     the plate (bilinear). Weight 1 up to 72° from the plate's sub-observer point, smoothly to 0 at
     82° (foreshortening and the soft photographic limb).
  4. Libration zones and far side (never on the plate) come from the Solar System Scope map, tone
     matched to the plate in the overlap (per-channel quantile mapping of linear values within 70°),
     then blended with the weights of step 3.
The plate already is a normal-albedo image: at full Moon the lunar regolith shows no limb darkening
(Lommel–Seeliger/Hapke behaviour), and the plate's radial mean profile rises towards the bright
highland limb instead of falling.

Usage:  python3 tools/ar-sky-assets/build_moon_plate_map.py
Needs:  numpy, scipy, pillow.
"""
import os
import sys
import numpy as np
from PIL import Image
from scipy import ndimage as ndi

ROOT = os.path.abspath(os.path.join(os.path.dirname(__file__), '..', '..'))
PLATE = os.path.join(ROOT, 'app/src/main/res/drawable/img_full_moon_photo_1785673146290.jpg')
SSS = os.path.join(ROOT, 'tools/ar-sky-assets/sources/moon_sss_1024x512.jpg')
OUT = os.path.join(ROOT, 'app/src/main/assets/ar/textures/moon_plate.jpg')
OUT_W, OUT_H = 2048, 1024

# Registration (see the module docstring); re-derive with --register.
LAMBDA0, PHI0, RHO, SCALE, DX, DY = -0.30, 1.00, -4.0375, 1.0027, -0.3125, 0.125


def lin(a):
    return np.where(a <= 0.04045, a / 12.92, ((a + 0.055) / 1.055) ** 2.4)


def srgb(a):
    a = np.clip(a, 0.0, 1.0)
    return np.where(a <= 0.0031308, a * 12.92, 1.055 * a ** (1 / 2.4) - 0.055)


def fit_limb(g):
    h, w = g.shape
    cx0, cy0 = w / 2, h / 2
    pts = []
    for ang in np.linspace(0, 2 * np.pi, 720, endpoint=False):
        rs = np.arange(300, min(w, h) / 2 - 1, 0.5)
        xs = cx0 + rs * np.cos(ang)
        ys = cy0 + rs * np.sin(ang)
        v = np.convolve(g[ys.astype(int), xs.astype(int)], np.ones(5) / 5, mode='same')
        i = int(np.argmin(np.gradient(v)))
        pts.append((xs[i], ys[i]))
    pts = np.array(pts)
    a = np.c_[2 * pts[:, 0], 2 * pts[:, 1], np.ones(len(pts))]
    b = (pts ** 2).sum(1)
    sol = np.linalg.lstsq(a, b, rcond=None)[0]
    cx, cy = sol[0], sol[1]
    r = np.sqrt(sol[2] + cx * cx + cy * cy)
    res = np.hypot(pts[:, 0] - cx, pts[:, 1] - cy) - r
    m = np.abs(res) < 3 * np.median(np.abs(res)) + 1
    sol = np.linalg.lstsq(a[m], b[m], rcond=None)[0]
    cx, cy = sol[0], sol[1]
    return cx, cy, np.sqrt(sol[2] + cx * cx + cy * cy)


def body_to_view(lat, lon):
    """Unit vectors of (lat, lon) in the plate's view frame (east, north, towards the observer)."""
    l0, p0 = np.radians(LAMBDA0), np.radians(PHI0)
    obs = np.array([np.cos(p0) * np.cos(l0), np.cos(p0) * np.sin(l0), np.sin(p0)])
    east = np.array([-np.sin(l0), np.cos(l0), 0.0])
    north = np.cross(obs, east)
    b = np.stack([np.cos(lat) * np.cos(lon), np.cos(lat) * np.sin(lon), np.sin(lat)], axis=-1)
    return b @ east, b @ north, b @ obs


def quantile_map(src, ref, src_mask, ref_vals):
    """Monotonic mapping of src values so that their distribution within src_mask matches ref_vals."""
    qs = np.linspace(0.0, 1.0, 257)
    sq = np.quantile(src[src_mask], qs)
    rq = np.quantile(ref_vals, qs)
    sq = np.maximum.accumulate(sq + np.arange(len(sq)) * 1e-9)
    return np.interp(src, sq, rq)


def main():
    plate = np.asarray(Image.open(PLATE).convert('RGB')).astype(np.float64) / 255.0
    sss = np.asarray(Image.open(SSS).convert('RGB')).astype(np.float64) / 255.0
    plate_lin = lin(plate)
    # The limb is fitted on the encoded (perceptual) values: in linear light the steepest radial
    # descent can fall on a bright highland/mare boundary instead of the limb.
    cx, cy, r_limb = fit_limb(plate.mean(axis=2))
    cx += DX
    cy += DY
    radius = r_limb * SCALE
    print(f'plate disc: centre ({cx:.2f}, {cy:.2f}) px, radius {radius:.2f} px')

    # Output grid (longitude 0 at the centre column, east to the right, north up).
    lon = (np.arange(OUT_W) + 0.5) / OUT_W * 2 * np.pi - np.pi
    lat = np.pi / 2 - (np.arange(OUT_H) + 0.5) / OUT_H * np.pi
    lon_g, lat_g = np.meshgrid(lon, lat)
    xe, yn, zo = body_to_view(lat_g, lon_g)
    rho = np.radians(RHO)
    xp = xe * np.cos(rho) + yn * np.sin(rho)
    yp = -xe * np.sin(rho) + yn * np.cos(rho)
    px = cx + xp * radius
    py = cy - yp * radius
    plate_s = np.stack([ndi.map_coordinates(plate_lin[..., c], [py, px], order=1, mode='nearest') for c in range(3)], -1)
    ang = np.degrees(np.arccos(np.clip(zo, -1, 1)))
    t = np.clip((82.0 - ang) / (82.0 - 72.0), 0.0, 1.0)
    w = t * t * (3 - 2 * t)

    # Solar System Scope map resampled to the output grid, tone matched to the plate within 70°.
    sh, sw = sss.shape[:2]
    u = (0.5 + lon_g / (2 * np.pi)) % 1.0
    v = 0.5 - lat_g / np.pi
    sss_lin = lin(sss)
    sss_s = np.stack([ndi.map_coordinates(sss_lin[..., c], [v * sh - 0.5, u * sw - 0.5], order=1, mode='wrap') for c in range(3)], -1)
    overlap = ang < 70.0
    matched = np.empty_like(sss_s)
    for c in range(3):
        matched[..., c] = quantile_map(sss_s[..., c], plate_s[..., c], overlap, plate_s[..., c][overlap])

    out = w[..., None] * plate_s + (1.0 - w[..., None]) * matched
    img = Image.fromarray((srgb(out) * 255.0 + 0.5).astype(np.uint8), 'RGB')
    img.save(OUT, quality=92, optimize=True, progressive=False, subsampling=0)
    print(f'wrote {OUT} ({os.path.getsize(OUT)} bytes)')


if __name__ == '__main__':
    sys.exit(main())
