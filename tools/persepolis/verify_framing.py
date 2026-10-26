#!/usr/bin/env python3
"""
Verify the Persepolis framing inside the Home sky scene, exactly.

PersepolisFraming.kt places the ruin with an isotropic scale approximation
(px per metre = viewport width / visible world width, anchored at the
platform toe). The GL side, PersepolisScene.draw(), projects with a real
off-axis lookAt + ortho. This script replicates those matrices (the Android
Matrix.setLookAtM / orthoM constructions, and draw()'s centre-solving code
line for line) and projects landmark world points from the model — column
tops, the great doorway's cornice, the facade ends, the forecourt edge —
through the framing PersepolisFraming.forScene computes (also ported here).

It proves, for a matrix of scene sizes:
  * the toe lands exactly on baseFractionFromTop (port sanity),
  * the whole facade is inside the frame on phone-shaped scenes,
  * the platform sits below the horizon and the tall columns rise above it,
  * the columns stay clear of the header band,
  * the great doorway's cornice (the tallest left-hand stone) stays below
    the scene readout under the EXACT projection — the approximation in
    PersepolisFraming is not conservative in that one direction, because a
    structure 11 m behind the toe projects slightly higher,
  * the forecourt's front edge falls inside the grounding bands painted by
    SceneGroundRenderer, and
  * how large the approximation error actually is (reported in dp).

Stdlib only. Run from the repo root:  python3 tools/persepolis/verify_framing.py
Exit code 0 = all checks passed.
"""

import math
import sys

# --------------------------------------------------------------------------
# Android Matrix.setLookAtM, replicated:  v = R (p - eye),
# rows of R are side / up' / -forward.
# --------------------------------------------------------------------------

CAM = (-17.0, 15.5, 110.0)
CAM_TARGET_Y = 9.0


def _norm(v):
    l = math.sqrt(sum(c * c for c in v))
    return tuple(c / l for c in v)


def _cross(a, b):
    return (
        a[1] * b[2] - a[2] * b[1],
        a[2] * b[0] - a[0] * b[2],
        a[0] * b[1] - a[1] * b[0],
    )


def look_at_basis():
    eye = CAM
    center = (0.0, CAM_TARGET_Y, 0.0)
    forward = _norm(tuple(center[i] - eye[i] for i in range(3)))
    side = _norm(_cross(forward, (0.0, 1.0, 0.0)))
    up2 = _cross(side, forward)
    return eye, side, up2, forward


EYE, SIDE, UP2, FWD = look_at_basis()


def view_point(p):
    d = tuple(p[i] - EYE[i] for i in range(3))
    return (
        sum(SIDE[i] * d[i] for i in range(3)),
        sum(UP2[i] * d[i] for i in range(3)),
        -sum(FWD[i] * d[i] for i in range(3)),
    )


# --------------------------------------------------------------------------
# PersepolisScene.draw() viewport math, ported line for line.
# Returns a function world -> (ndc_x, row_from_top_px).
# --------------------------------------------------------------------------

def projector(width_px, height_px, visible_width_m, base_fraction_from_top,
              center_fraction_from_left=0.5):
    aspect = width_px / height_px
    vis_w = visible_width_m
    # autoFitHeight (always on in the integration):
    need_w = aspect * (25.0 / max(base_fraction_from_top, 0.05))
    vis_w = max(vis_w, need_w)
    vis_h = vis_w / aspect

    centre_x = view_point((0.0, 0.0, -2.0))[0] + (
        0.5 - min(max(center_fraction_from_left, 0.0), 1.0)
    ) * vis_w
    toe_view_y = view_point((0.0, 0.0, 8.0))[1]
    centre_y = toe_view_y + (2.0 * base_fraction_from_top - 1.0) * vis_h / 2.0

    left, right = centre_x - vis_w / 2.0, centre_x + vis_w / 2.0
    bottom, top = centre_y - vis_h / 2.0, centre_y + vis_h / 2.0

    def project(p):
        vx, vy, _ = view_point(p)
        ndc_x = 2.0 * (vx - left) / (right - left) - 1.0
        ndc_y = 2.0 * (vy - bottom) / (top - bottom) - 1.0
        row_from_top = (1.0 - ndc_y) / 2.0 * height_px
        return ndc_x, row_from_top

    return project, vis_w


# --------------------------------------------------------------------------
# PersepolisFraming.forScene, ported line for line.
# --------------------------------------------------------------------------

DEFAULT_VISIBLE_WIDTH_M = 89.0
LINEAR_SCALE_FRACTION = 0.5
REFERENCE_MAX_BUILDING_HEIGHT_FRACTION = 0.34
BUILDING_HEIGHT_M = 23.6
PLATFORM_TOP_M = 4.35
GREAT_DOORWAY_TOP_M = PLATFORM_TOP_M + 10.4
CENTER_FRACTION_FROM_LEFT = 0.30
DISTANT_TOE_BELOW_HORIZON_FRACTION = 0.098
MAX_TOE_FRACTION = 0.92
READOUT_INSET_DP = 12.0
READOUT_CLEARANCE_DP = 5.0
READOUT_RIGHT_DP = 250.0
HORIZON_FRACTION = 0.70

GUARD_STRUCTURES = [  # world x, top above toe, half width (metres)
    (-39.7, 8.45, 2.5), (-34.4, 9.0, 2.7), (-25.6, GREAT_DOORWAY_TOP_M, 3.1),
    (-5.9, PLATFORM_TOP_M + 7.8, 2.7), (1.4, PLATFORM_TOP_M + 9.9, 3.0),
    (8.9, 16.92, 1.4), (26.2, BUILDING_HEIGHT_M, 1.1),
    (29.3, PLATFORM_TOP_M + 15.7, 1.1),
]


def for_scene(width_px, height_px, density):
    w, h = max(width_px, 1.0), max(height_px, 1.0)
    horizon_y = h * HORIZON_FRACTION
    readout_bottom = horizon_y - READOUT_INSET_DP * density
    readout_clearance = READOUT_CLEARANCE_DP * density
    readout_right = READOUT_RIGHT_DP * density
    aspect = w / h
    reference_visible_width = max(
        DEFAULT_VISIBLE_WIDTH_M,
        aspect * BUILDING_HEIGHT_M / REFERENCE_MAX_BUILDING_HEIGHT_FRACTION,
    )
    visible_width = reference_visible_width / LINEAR_SCALE_FRACTION
    px_per_meter = w / visible_width
    model_center_x = w * CENTER_FRACTION_FROM_LEFT
    guard_top_m = max(
        (top for (x, top, half) in GUARD_STRUCTURES
         if model_center_x + (x - half) * px_per_meter < readout_right),
        default=0.0,
    )
    distant_toe_y = horizon_y + h * DISTANT_TOE_BELOW_HORIZON_FRACTION
    toe_for_readout = readout_bottom + readout_clearance + guard_top_m * px_per_meter
    toe_y = min(max(distant_toe_y, toe_for_readout), h * MAX_TOE_FRACTION)
    return {
        "visible_width_m": visible_width,
        "reference_visible_width_m": reference_visible_width,
        "base_fraction_from_top": toe_y / h,
        "center_fraction_from_left": CENTER_FRACTION_FROM_LEFT,
        "toe_y": toe_y,
        "px_per_meter": px_per_meter,
        "platform_top_y": toe_y - PLATFORM_TOP_M * px_per_meter,
        "column_top_y": toe_y - BUILDING_HEIGHT_M * px_per_meter,
        "doorway_top_y": toe_y - GREAT_DOORWAY_TOP_M * px_per_meter,
        "model_center_x": model_center_x,
        "platform_left_x": model_center_x - 66.0 * px_per_meter,
        "platform_right_x": model_center_x + 66.0 * px_per_meter,
        "readout_bottom": readout_bottom,
        "readout_right": readout_right,
        "guard_top_y": toe_y - guard_top_m * px_per_meter,
    }


# --------------------------------------------------------------------------
# Landmarks (world metres, from Persepolis.kt's element tables).
# --------------------------------------------------------------------------

TOE = (0.0, 0.0, 8.0)
CENTROID = (0.0, 0.0, -2.0)                 # the world point centreFractionFromLeft places
FORECOURT_FRONT_TOP = (0.0, 0.5, 11.0)      # forecourt slab: y 0..0.5, z ..11
PLATFORM_FRONT_TOP = (0.0, 4.35, 4.7)       # upper slab front edge
PLATFORM_CORNERS = [                        # forecourt slab corners (x +-66, z -13..11)
    (-66.0, 0.5, 11.0), (66.0, 0.5, 11.0), (-66.0, 0.5, -13.0), (66.0, 0.5, -13.0),
]
TALLEST_COLUMN_TOP = (26.2, 23.55, -2.0)    # 4.35 platform + 19.2 column
SECOND_COLUMN_TOP = (29.3, 20.05, -2.8)     # 4.35 + 15.7
DOORWAY_TOP = (-25.6, 14.75, -3.4)          # great doorway cornice
DOORWAY_TOP_LEFT = (-28.65, 14.75, -3.4)
FAR_LEFT_FRAME_TOP = (-39.7, 8.45, 4.9)     # lowered ledge base 2.85 + 5.6
FAR_LEFT_EDGE = (-42.15, 5.0, 4.9)          # leftmost modelled stone
FAR_COLUMN_TOP = (39.3, 12.9, -8.4)         # far isolated broken stack (approx)
STAIR_FACADE_RIGHT = (43.5, 2.6, 7.25)      # east-stair facade end / lion panel

# Tall silhouettes for the exact readout check: (name, top point).
READOUT_LANDMARKS = [
    ("far-left frame", FAR_LEFT_FRAME_TOP),
    ("great doorway", DOORWAY_TOP_LEFT),
    ("mid door frame", (-5.9, 12.15, -4.2)),
    ("central niche", (1.4, 14.25, -3.0)),
    ("centre stack", (8.9, 16.92, -1.6)),
    ("tallest column", TALLEST_COLUMN_TOP),
    ("second column", SECOND_COLUMN_TOP),
]

SIZES = [
    (960.0, 1140.0, 3.0, "small phone 320x380dp"),
    (1080.0, 1260.0, 3.0, "phone 360x420dp"),
    (1290.0, 1440.0, 3.0, "large phone 430x480dp"),
    (1080.0, 936.0, 3.0, "short phone scene 360x312dp"),
    (2400.0, 1120.0, 2.0, "tablet 1200x560dp"),
    (2400.0, 936.0, 3.0, "wide short scene 800x312dp"),
]


def main():
    failures = []

    def check(cond, msg):
        print(("  PASS  " if cond else "  FAIL  ") + msg)
        if not cond:
            failures.append(msg)

    for w, h, density, label in SIZES:
        print(f"\n{label}  ({w:.0f}x{h:.0f}px @density {density:g})")
        f = for_scene(w, h, density)
        project, vis_w_effective = projector(
            w, h, f["visible_width_m"], f["base_fraction_from_top"],
            f["center_fraction_from_left"],
        )
        horizon_y = h * HORIZON_FRACTION
        dp = density

        check(
            abs(f["visible_width_m"] - 2.0 * f["reference_visible_width_m"]) < 1e-6,
            f"visible width is exactly twice the first framing ({f['visible_width_m']:.1f} m vs {f['reference_visible_width_m']:.1f} m)",
        )
        check(
            abs(vis_w_effective - f["visible_width_m"]) < 1e-6,
            f"autoFit never widens the framing (chosen {f['visible_width_m']:.1f} m)",
        )

        ndc_centre, _ = project(CENTROID)
        centre_frac = (ndc_centre + 1.0) / 2.0
        check(
            abs(centre_frac - CENTER_FRACTION_FROM_LEFT) < 1e-6,
            f"platform centroid on the left-third column (fraction {centre_frac:.2f})",
        )

        _, toe_row = project(TOE)
        check(
            abs(toe_row - f["toe_y"]) < 0.01,
            f"toe lands on the framed row (exact {toe_row:.1f}px = {toe_row / h:.3f} of scene)",
        )
        check(
            toe_row / h <= 0.86,
            f"toe set farther back ({toe_row / h:.3f} of scene vs the first framing's 0.84 floor)",
        )

        _, platform_row = project(PLATFORM_FRONT_TOP)
        check(platform_row > horizon_y, f"platform front top below horizon (row {platform_row:.0f} > {horizon_y:.0f})")

        _, col_row = project(TALLEST_COLUMN_TOP)
        _, col2_row = project(SECOND_COLUMN_TOP)
        check(col_row < horizon_y, f"tallest column above horizon ({(horizon_y - col_row) / dp:.1f}dp of sky silhouette)")
        check(col2_row < horizon_y, f"second column above horizon ({(horizon_y - col2_row) / dp:.1f}dp)")
        check(col_row > 0.36 * h, f"tallest column clear of the header band (top at {col_row / h:.2f} of scene)")

        _, door_row = project(DOORWAY_TOP)
        # Every tall landmark that falls inside the readout's protected width
        # (250dp from the left) must clear the readout under the exact projection.
        guard_clearances = []
        for name, point in READOUT_LANDMARKS:
            ndc_x, row = project(point)
            x_px = (ndc_x + 1.0) / 2.0 * w
            if x_px < f["readout_right"]:
                guard_clearances.append((name, (row - f["readout_bottom"]) / dp))
        binding = min(guard_clearances, key=lambda item: item[1]) if guard_clearances else ("none", 0.0)
        check(
            all(clear > 0.0 for _, clear in guard_clearances),
            f"all {len(guard_clearances)} structures under the readout clear it (binding: {binding[0]} +{binding[1]:.1f}dp)",
        )

        ndc_left, _ = project(FAR_LEFT_EDGE)
        ndc_right, _ = project(STAIR_FACADE_RIGHT)
        left_frac = (ndc_left + 1.0) / 2.0
        right_frac = (ndc_right + 1.0) / 2.0
        check(
            0.0 <= left_frac and right_frac <= 0.62,
            f"whole facade in frame on the left (spans {left_frac:.2f}..{right_frac:.2f} of width)",
        )

        corner_fracs = [(project(c)[0] + 1.0) / 2.0 for c in PLATFORM_CORNERS]
        exact_platform_right = max(corner_fracs) * w
        check(
            max(corner_fracs) <= 0.70,
            f"platform ends in the left of the landscape (right edge at {max(corner_fracs):.2f} of width)",
        )

        _, fore_row = project(FORECOURT_FRONT_TOP)
        band_lo, band_hi = f["toe_y"] - 0.032 * h, f["toe_y"] + 0.030 * h
        check(
            band_lo <= fore_row <= band_hi,
            f"forecourt front edge inside the painted grounding bands ({(fore_row - f['toe_y']) / dp:+.1f}dp from toe row)",
        )

        # Approximation quality (informational, with a generous bound):
        err_col = abs(col_row - f["column_top_y"]) / dp
        err_door = abs(door_row - f["doorway_top_y"]) / dp
        err_plat = abs(platform_row - f["platform_top_y"]) / dp
        err_edge = abs(exact_platform_right - f["platform_right_x"]) / dp
        check(
            max(err_col, err_door, err_plat, err_edge) < 8.0,
            f"isotropic-scale approximation error < 8dp (column {err_col:.1f}, doorway {err_door:.1f}, platform {err_plat:.1f}, platform edge {err_edge:.1f})",
        )

    print("\n" + ("ALL CHECKS PASSED" if not failures else f"{len(failures)} FAILURE(S)"))
    return 1 if failures else 0


if __name__ == "__main__":
    sys.exit(main())
