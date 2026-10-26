#!/usr/bin/env python3
"""
Verify the combined Persepolis + Mil-e Azhdaha framing in the Home scene, exactly.

PersepolisFraming.kt places Persepolis with an isotropic scale approximation
(px per metre = viewport width / visible world width, anchored at the
platform toe), and places the nearer Mil-e Azhdaha group with precomputed
fixed-camera view-space offsets. The GL side,
PersepolisMilAzhdahaScene.draw(), projects with a real off-axis lookAt +
ortho. This script replicates those matrices (the Android
Matrix.setLookAtM / orthoM constructions, and draw()'s centre-solving code
line for line) and projects landmark world points from both monument groups
through the framing PersepolisFraming.forScene computes (also ported here).

It proves, for a matrix of scene sizes:
  * the toe lands exactly on baseFractionFromTop (port sanity),
  * the whole Persepolis facade is inside the frame on phone-shaped scenes,
  * the cut-back platform sits below the horizon and in the left landscape,
  * the tall Persepolis columns rise above the horizon and clear the header,
  * every tall structure inside the readout's protected width — including
    the Mil-e Azhdaha tower, which PersepolisFraming does not need as a
    separate guard — clears the readout under the EXACT projection,
  * the baked, transformed corners of the tower slope's nine fixed sheets
    match PersepolisFraming's slope footprint landmarks,
  * both the forecourt front edge and the nearer slope front fall inside
    the grounding bands painted by SceneGroundRenderer, and
  * how large the Persepolis approximation error actually is (reported in dp).

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
# PersepolisMilAzhdahaScene.draw() viewport math, ported line for line.
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
GREAT_DOORWAY_X_M = -35.3
TALLEST_COLUMN_X_M = 16.5
PLATFORM_LEFT_X_M = -56.98
PLATFORM_RIGHT_X_M = 40.30
FACADE_LEFT_X_M = -52.3
FACADE_RIGHT_X_M = 36.3
MIL_TOWER_CENTER_VIEW_OFFSET_M = 108.14
MIL_TOWER_TOP_VIEW_HEIGHT_M = 16.78
MIL_SLOPE_LEFT_VIEW_OFFSET_M = 82.34
MIL_SLOPE_RIGHT_VIEW_OFFSET_M = 142.08
MIL_SLOPE_TOP_VIEW_HEIGHT_M = 7.14
MIL_SLOPE_FRONT_BELOW_TOE_VIEW_M = 0.97
CENTER_FRACTION_FROM_LEFT = 0.30
DISTANT_TOE_BELOW_HORIZON_FRACTION = 0.098
MAX_TOE_FRACTION = 0.92
READOUT_INSET_DP = 12.0
READOUT_CLEARANCE_DP = 5.0
READOUT_RIGHT_DP = 250.0
HORIZON_FRACTION = 0.70

GUARD_STRUCTURES = [  # combined-scene world x, top above toe, half width (metres)
    (-49.4, 8.45, 2.5), (-44.1, 9.0, 2.7),
    (GREAT_DOORWAY_X_M, GREAT_DOORWAY_TOP_M, 3.1),
    (-15.6, PLATFORM_TOP_M + 7.8, 2.7), (-8.3, PLATFORM_TOP_M + 9.9, 3.0),
    (-0.8, 16.92, 1.4), (TALLEST_COLUMN_X_M, BUILDING_HEIGHT_M, 1.1),
    (19.6, PLATFORM_TOP_M + 15.7, 1.1),
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
        "mil_tower_top_y": toe_y - MIL_TOWER_TOP_VIEW_HEIGHT_M * px_per_meter,
        "model_center_x": model_center_x,
        "platform_left_x": model_center_x + PLATFORM_LEFT_X_M * px_per_meter,
        "platform_right_x": model_center_x + PLATFORM_RIGHT_X_M * px_per_meter,
        "facade_left_x": model_center_x + FACADE_LEFT_X_M * px_per_meter,
        "facade_right_x": model_center_x + FACADE_RIGHT_X_M * px_per_meter,
        "mil_tower_center_x": model_center_x + MIL_TOWER_CENTER_VIEW_OFFSET_M * px_per_meter,
        "mil_slope_left_x": model_center_x + MIL_SLOPE_LEFT_VIEW_OFFSET_M * px_per_meter,
        "mil_slope_right_x": model_center_x + MIL_SLOPE_RIGHT_VIEW_OFFSET_M * px_per_meter,
        "mil_slope_top_y": toe_y - MIL_SLOPE_TOP_VIEW_HEIGHT_M * px_per_meter,
        "mil_slope_front_y": toe_y + MIL_SLOPE_FRONT_BELOW_TOE_VIEW_M * px_per_meter,
        "readout_bottom": readout_bottom,
        "readout_right": readout_right,
        "guard_top_y": toe_y - guard_top_m * px_per_meter,
    }


# --------------------------------------------------------------------------
# Landmarks (combined-scene world metres, from PersepolisMilAzhdaha.kt).
# Persepolis builder-local x values already include PERSEPOLIS_X = -9.7 m.
# --------------------------------------------------------------------------

TOE = (0.0, 0.0, 8.0)
CENTROID = (0.0, 0.0, -2.0)                 # the point centreFractionFromLeft places
FORECOURT_FRONT_TOP = (0.0, 0.5, 11.0)      # forecourt slab: y 0..0.5, z ..11
PLATFORM_FRONT_TOP = (0.0, 4.35, 4.7)       # upper slab front edge
PLATFORM_CORNERS = [                        # cut-back forecourt: x -56.975..40.295, z -13..11
    (-56.975, 0.5, 11.0), (40.295, 0.5, 11.0),
    (-56.975, 0.5, -13.0), (40.295, 0.5, -13.0),
]
TALLEST_COLUMN_TOP = (16.5, 23.55, -2.0)    # 4.35 platform + 19.2 column
SECOND_COLUMN_TOP = (19.6, 20.05, -2.8)     # 4.35 + 15.7
DOORWAY_TOP = (-35.3, 14.75, -3.4)          # great doorway cornice
DOORWAY_TOP_LEFT = (-38.35, 14.75, -3.4)
FAR_LEFT_FRAME_TOP = (-49.4, 8.45, 4.9)     # lowered ledge base 2.85 + 5.6
FAR_LEFT_EDGE = (-51.85, 5.0, 4.9)          # leftmost modelled Persepolis stone
FAR_COLUMN_TOP = (29.6, 12.9, -8.4)         # far isolated broken stack (approx)
STAIR_FACADE_RIGHT = (33.8, 2.6, 7.25)      # east-stair facade end / lion panel

# Mil-e Azhdaha group bake from the combined file.
MIL_GROUP_SCALE = 2.04
MIL_GROUP_ROTY = -0.20
MIL_GROUP_X = 40.7
MIL_HOME_RIGHT_SHIFT_M = 66.0
MIL_GROUP_Y = 1.1
MIL_GROUP_Z = 15.6
MIL_TOWER_CENTER = (MIL_GROUP_X + MIL_HOME_RIGHT_SHIFT_M, MIL_GROUP_Y, MIL_GROUP_Z)
MIL_TOWER_TOP = (MIL_TOWER_CENTER[0], 16.298, MIL_GROUP_Z)  # 1.1 + 2.04 * 7.45 m local ruin top


def mil_transform(p):
    """transformMesh() from the combined file, for one local point."""
    x, y, z = (c * MIL_GROUP_SCALE for c in p)
    c = math.cos(MIL_GROUP_ROTY)
    s = math.sin(MIL_GROUP_ROTY)
    return (
        x * c + z * s + MIL_GROUP_X + MIL_HOME_RIGHT_SHIFT_M,
        y + MIL_GROUP_Y,
        -x * s + z * c + MIL_GROUP_Z,
    )


# The nine fixed sheets in milSlope(): centre xyz + size xyz, Mil-local metres.
MIL_SLOPE_BOXES = [
    (0.0, -0.295, 0.65, 13.6, 0.49, 12.3),
    (8.0, 0.015, 1.65, 2.4, 1.11, 14.3),
    (10.4, 0.325, 1.2, 2.4, 1.73, 13.4),
    (12.8, 0.635, 1.9, 2.4, 2.35, 14.8),
    (15.2, 0.945, 1.4, 2.4, 2.97, 13.8),
    (-8.6, -0.415, 0.65, 3.6, 0.25, 12.3),
    (-11.3, -0.49, 1.5, 2.2, 0.10, 8.0),
    (2.0, -0.415, 8.1, 17.0, 0.25, 2.6),
    (2.0, -0.49, 10.7, 14.0, 0.10, 2.6),
]

MIL_SLOPE_CORNERS = [
    mil_transform((cx + dx, cy + dy, cz + dz))
    for (cx, cy, cz, sx, sy, sz) in MIL_SLOPE_BOXES
    for dx in (-sx / 2.0, sx / 2.0)
    for dy in (-sy / 2.0, sy / 2.0)
    for dz in (-sz / 2.0, sz / 2.0)
]

# Tall silhouettes for the exact readout check: (name, top point). The tower
# is checked here even though PersepolisFraming's guard list omits it: the
# taller Persepolis column to its left should always dominate the toe rule.
READOUT_LANDMARKS = [
    ("far-left frame", FAR_LEFT_FRAME_TOP),
    ("great doorway", DOORWAY_TOP_LEFT),
    ("mid door frame", (-15.6, 12.15, -4.2)),
    ("central niche", (-8.3, 14.25, -3.0)),
    ("centre stack", (-0.8, 16.92, -1.6)),
    ("tallest column", TALLEST_COLUMN_TOP),
    ("second column", SECOND_COLUMN_TOP),
    ("Mil-e Azhdaha tower", MIL_TOWER_TOP),
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

    # Size-independent audit of the transformed fixed slope sheets.
    slope_world_x = (min(p[0] for p in MIL_SLOPE_CORNERS), max(p[0] for p in MIL_SLOPE_CORNERS))
    slope_world_y = (min(p[1] for p in MIL_SLOPE_CORNERS), max(p[1] for p in MIL_SLOPE_CORNERS))
    slope_world_z = (min(p[2] for p in MIL_SLOPE_CORNERS), max(p[2] for p in MIL_SLOPE_CORNERS))
    check(
        abs(slope_world_y[0]) < 0.01 and slope_world_y[1] > 6.0,
        "fixed Mil slope sheets bottom out on world y=0 "
        f"(world x {slope_world_x[0]:.2f}..{slope_world_x[1]:.2f}, "
        f"y {slope_world_y[0]:.3f}..{slope_world_y[1]:.2f}, "
        f"z {slope_world_z[0]:.2f}..{slope_world_z[1]:.2f})",
    )

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
            f"combined-frame anchor on the left-third column (fraction {centre_frac:.2f})",
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
            f"whole Persepolis facade in frame on the left (spans {left_frac:.2f}..{right_frac:.2f} of width)",
        )

        corner_fracs = [(project(c)[0] + 1.0) / 2.0 for c in PLATFORM_CORNERS]
        exact_platform_right = max(corner_fracs) * w
        check(
            max(corner_fracs) <= 0.70,
            f"cut-back platform ends before the open right landscape (right edge at {max(corner_fracs):.2f} of width)",
        )

        # Exact Mil tower/slope placement against PersepolisFraming's fields.
        tower_ndc, tower_row = project(MIL_TOWER_TOP)
        tower_x = (tower_ndc + 1.0) / 2.0 * w
        err_tower_x = abs(tower_x - f["mil_tower_center_x"]) / dp
        err_tower_top = abs(tower_row - f["mil_tower_top_y"]) / dp
        check(
            max(err_tower_x, err_tower_top) < 1.0,
            "Mil tower view-space landmarks match exact projection "
            f"(centre x {tower_x / w:.3f} of width, top {tower_row / h:.3f} of scene; "
            f"errors {err_tower_x:.2f}/{err_tower_top:.2f}dp)",
        )
        check(
            0.0 < tower_x < w and tower_row > 0.36 * h,
            "Mil tower is in frame and clear of the header band",
        )

        slope_projected = [project(c) for c in MIL_SLOPE_CORNERS]
        exact_slope_left = min((ndc + 1.0) / 2.0 * w for ndc, _ in slope_projected)
        exact_slope_right = max((ndc + 1.0) / 2.0 * w for ndc, _ in slope_projected)
        exact_slope_top = min(row for _, row in slope_projected)
        exact_slope_front = max(row for _, row in slope_projected)
        slope_errors = [
            abs(exact_slope_left - f["mil_slope_left_x"]) / dp,
            abs(exact_slope_right - f["mil_slope_right_x"]) / dp,
            abs(exact_slope_top - f["mil_slope_top_y"]) / dp,
            abs(exact_slope_front - f["mil_slope_front_y"]) / dp,
        ]
        check(
            max(slope_errors) < 1.0,
            "Mil slope footprint landmarks match its transformed fixed sheets "
            f"(x {exact_slope_left / w:.3f}..{exact_slope_right / w:.3f} of width, "
            f"rows {exact_slope_top / h:.3f}..{exact_slope_front / h:.3f}; "
            f"max error {max(slope_errors):.2f}dp)",
        )
        phone_window = f["visible_width_m"] <= 180.0
        check(
            tower_x >= 0.58 * w
            and exact_slope_left > exact_platform_right + 0.10 * w
            and (
                not phone_window
                or (tower_x >= 0.89 * w and exact_slope_right >= 1.03 * w)
            ),
            "Mil stands alone at the far right with open plain from Persepolis "
            f"(platform {exact_platform_right / w:.2f}, slope {exact_slope_left / w:.2f}.."
            f"{exact_slope_right / w:.2f}; phone slope is cropped by the edge)"
            if phone_window else
            "Mil stands alone to the right with open plain from Persepolis "
            f"(platform {exact_platform_right / w:.2f}, slope {exact_slope_left / w:.2f}.."
            f"{exact_slope_right / w:.2f})",
        )
        check(
            f["toe_y"] < exact_slope_front <= f["toe_y"] + 0.027 * h,
            "slope front is nearer than the Persepolis toe but inside its contact-shadow band "
            f"({(exact_slope_front - f['toe_y']) / dp:+.1f}dp from toe row)",
        )

        _, fore_row = project(FORECOURT_FRONT_TOP)
        band_lo, band_hi = f["toe_y"] - 0.032 * h, f["toe_y"] + 0.030 * h
        check(
            band_lo <= fore_row <= band_hi,
            f"forecourt front edge inside the painted grounding bands ({(fore_row - f['toe_y']) / dp:+.1f}dp from toe row)",
        )

        # Approximation quality for the Persepolis landmarks (generous bound):
        err_col = abs(col_row - f["column_top_y"]) / dp
        err_door = abs(door_row - f["doorway_top_y"]) / dp
        err_plat = abs(platform_row - f["platform_top_y"]) / dp
        err_edge = abs(exact_platform_right - f["platform_right_x"]) / dp
        check(
            max(err_col, err_door, err_plat, err_edge) < 8.0,
            f"Persepolis isotropic-scale approximation error < 8dp (column {err_col:.1f}, doorway {err_door:.1f}, platform {err_plat:.1f}, platform edge {err_edge:.1f})",
        )

    print("\n" + ("ALL CHECKS PASSED" if not failures else f"{len(failures)} FAILURE(S)"))
    return 1 if failures else 0


if __name__ == "__main__":
    sys.exit(main())
