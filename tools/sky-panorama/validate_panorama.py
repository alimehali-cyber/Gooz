#!/usr/bin/env python3
"""
Deterministic asset-level validation for the ZIG runtime sky panorama.

The validator runs four separate checks. Each reports its own result, and the file is accepted
only if all four pass. No check compares images subjectively.

  A. SOURCE INTEGRITY (the EXR)
     * SHA-256 equals --expected-source-sha256 (when given; a mismatch rejects).
     * Header: 4096 x 2048, RGB half-float channels, data window == display window == 0,0,4095,2047.
     * Identity status is recorded, not assumed:
         OFFICIAL_SHA256_MATCHED            only if --official-sha256 is supplied and matches
         USER_SUPPLIED_IDENTITY_UNVERIFIED  otherwise (the default)
       Plausible content and a matching hash of a copy do NOT establish NASA identity.

  B. CONVERSION INTEGRITY (the JPEG)
     * SHA-256 equals --expected-output-sha256 (when given).
     * Real JPEG, exactly 4096 x 2048 (no resize, crop or other geometry change), RGB.
     * Embedded ICC profile whose description is sRGB.
     * 4:4:4 chroma sampling and the quantisation tables of quality 92.
     * With --provenance: declared output hash, dimensions, exposure 0 EV, no highlight roll-off,
       quality 92, 4:4:4, and the converter's own SHA-256 match the file on disk.

  C. ORIENTATION
     Each candidate orientation of the re-derived reference (identity, vertical flip, horizontal
     mirror, 180-degree rotation) is passed through the SAME encoder (encode_panorama_jpeg) and
     decoded. The output is compared with each decoded candidate by mean absolute difference.
     The identity candidate must be the best match, and the best alternative must be worse by at
     least ORIENTATION_MIN_SEPARATION levels. Scores and the separation are reported.
     This shows orientation only. It does not show source authenticity.

  D. CODEC FIDELITY
     * Measured against the pre-JPEG reference: mean |diff|, max |diff|, PSNR, per channel. These
       are reported, not gated on.
     * Gate (reproducibility): the output must equal, within CODEC_REPRO_MAX_ABS levels, the
       independent encode/decode of the reference with the specified settings. The JPEG loss is
       then exactly the loss of the specified encoder, and nothing else has changed the pixels.
       CODEC_REPRO_MAX_ABS = 1 allows for libjpeg IDCT rounding differences between builds.
     Thresholds are NOT weakened for real star-field detail. A lossy JPEG does not reproduce the
     original pixels, so an error budget on the original is not the right acceptance test.

Usage:
  validate_panorama.py app/src/main/assets/sky/panorama/milkyway_2020_4k.jpg \\
      --expected-output-sha256 <sha256> \\
      --source /home/user/milkyway_2020_4k.exr --expected-source-sha256 <sha256> \\
      --provenance tools/sky-panorama/provenance/milkyway_2020_4k.source.json \\
      [--official-sha256 <independent NASA checksum, only if one is published>] \\
      [--report-json report.json]

Exit status: 0 = accepted, 1 = rejected, 2 = usage / unreadable input.
"""

from __future__ import annotations

import argparse
import hashlib
import io
import json
import os
import sys

import numpy as np

HERE = os.path.dirname(os.path.abspath(__file__))
sys.path.insert(0, HERE)

EXPECTED_WIDTH = 4096
EXPECTED_HEIGHT = 2048
EXPECTED_JPEG_QUALITY = 92
EXPECTED_SUBSAMPLING = 0          # PIL/libjpeg: 0 = 4:4:4
# Level separation between the correct orientation and the best alternative (mean |diff| per
# channel, 0-255 scale). The encoder is deterministic, so the correct candidate scores ~0.
# 5 levels is far above the per-candidate codec variance (see CODEC_REPRO_MAX_ABS).
ORIENTATION_MIN_SEPARATION = 5.0
CODEC_REPRO_MAX_ABS = 1


def sha256_bytes(data: bytes) -> str:
    return hashlib.sha256(data).hexdigest()


def sha256_file(path: str) -> str:
    h = hashlib.sha256()
    with open(path, "rb") as f:
        for chunk in iter(lambda: f.read(1 << 20), b""):
            h.update(chunk)
    return h.hexdigest()


def psnr(a: np.ndarray, b: np.ndarray) -> float:
    mse = np.mean((a.astype(np.float64) - b.astype(np.float64)) ** 2)
    if mse == 0:
        return float("inf")
    return float(10.0 * np.log10(255.0 * 255.0 / mse))


# ---------------------------------------------------------------- A. source integrity

def exr_header_info(path: str) -> dict:
    import OpenEXR

    f = OpenEXR.InputFile(path)
    try:
        h = f.header()
        dw = h["dataWindow"]
        disp = h["displayWindow"]
        chans = {n: str(c.type) for n, c in h["channels"].items()}
        return {
            "dataWindow": [dw.min.x, dw.min.y, dw.max.x, dw.max.y],
            "displayWindow": [disp.min.x, disp.min.y, disp.max.x, disp.max.y],
            "width": dw.max.x - dw.min.x + 1,
            "height": dw.max.y - dw.min.y + 1,
            "channels": dict(sorted(chans.items())),
            "compression": str(h.get("compression")),
            "lineOrder": str(h.get("lineOrder")),
            "pixelAspectRatio": h.get("pixelAspectRatio"),
            "attributes": sorted(h.keys()),
        }
    finally:
        f.close()


def check_source(exr_path: str, expected_sha256: str | None, official_sha256: str | None) -> dict:
    reasons: list[str] = []
    actual = sha256_file(exr_path)
    info = {"path": os.path.basename(exr_path), "sha256": actual, "size_bytes": os.path.getsize(exr_path)}

    if expected_sha256 is None:
        info["sha256_check"] = "NOT CHECKED (no --expected-source-sha256 supplied)"
    elif actual != expected_sha256.lower():
        info["sha256_check"] = "MISMATCH"
        reasons.append(f"source SHA-256 {actual} does not match expected {expected_sha256}")
    else:
        info["sha256_check"] = "MATCH"

    try:
        header = exr_header_info(exr_path)
    except Exception as exc:  # noqa: BLE001
        reasons.append(f"source EXR header unreadable: {exc}")
        info["header"] = None
        header = None
    if header is not None:
        info["header"] = header
        if (header["width"], header["height"]) != (EXPECTED_WIDTH, EXPECTED_HEIGHT):
            reasons.append(f"source is {header['width']}x{header['height']}, expected "
                           f"{EXPECTED_WIDTH}x{EXPECTED_HEIGHT}")
        if sorted(header["channels"]) != ["B", "G", "R"]:
            reasons.append(f"source channels are {sorted(header['channels'])}, expected R, G, B")
        elif any(t != "HALF" for t in header["channels"].values()):
            reasons.append(f"source channel types are {header['channels']}, expected HALF")
        if header["dataWindow"] != [0, 0, EXPECTED_WIDTH - 1, EXPECTED_HEIGHT - 1]:
            reasons.append(f"source data window {header['dataWindow']} is not 0,0,4095,2047")

    if official_sha256 is None:
        info["official_sha256"] = None
        info["identity_status"] = "USER_SUPPLIED_IDENTITY_UNVERIFIED"
    elif actual == official_sha256.lower():
        info["official_sha256"] = official_sha256.lower()
        info["identity_status"] = "OFFICIAL_SHA256_MATCHED"
    else:
        info["official_sha256"] = official_sha256.lower()
        info["identity_status"] = "OFFICIAL_SHA256_MISMATCH"
        reasons.append("source SHA-256 does not match the independent official checksum")
    return {"reasons": reasons, "info": info}


# ---------------------------------------------------------------- B. conversion integrity

def encode_reference_jpeg(pixels: np.ndarray) -> bytes:
    from convert_milkyway_exr import encode_panorama_jpeg
    return encode_panorama_jpeg(pixels)


def decode_rgb(jpeg_bytes: bytes) -> np.ndarray:
    from PIL import Image
    return np.asarray(Image.open(io.BytesIO(jpeg_bytes)).convert("RGB"))


def check_conversion(raw: bytes, path: str, expected_output_sha256: str | None,
                     provenance: dict | None) -> dict:
    from PIL import Image, ImageCms
    from PIL.JpegImagePlugin import get_sampling

    reasons: list[str] = []
    info: dict = {"path": path, "sha256": sha256_bytes(raw), "size_bytes": len(raw)}

    if expected_output_sha256 is not None:
        if info["sha256"] != expected_output_sha256.lower():
            reasons.append("output file SHA-256 does not match the expected conversion hash")
            info["sha256_check"] = "MISMATCH"
        else:
            info["sha256_check"] = "MATCH"
    else:
        info["sha256_check"] = "NOT CHECKED"

    try:
        image = Image.open(io.BytesIO(raw))
    except Exception as exc:  # noqa: BLE001
        return {"reasons": reasons + [f"not a readable image: {exc}"], "info": info}

    info["format"] = image.format
    info["width"], info["height"] = image.size
    info["mode"] = image.mode
    if image.format != "JPEG":
        reasons.append(f"format is {image.format}, expected JPEG")
    if image.size != (EXPECTED_WIDTH, EXPECTED_HEIGHT):
        reasons.append(f"dimensions are {image.width}x{image.height}, expected "
                       f"{EXPECTED_WIDTH}x{EXPECTED_HEIGHT} (resized, cropped or wrong projection)")
    if image.mode != "RGB":
        reasons.append(f"mode is {image.mode}, expected RGB")

    sampling = get_sampling(image)
    info["chroma_subsampling"] = {0: "4:4:4", 1: "4:2:2", 2: "4:2:0"}.get(sampling, f"unknown ({sampling})")
    if sampling != EXPECTED_SUBSAMPLING:
        reasons.append(f"chroma subsampling is {info['chroma_subsampling']}, expected 4:4:4")

    # Quantisation tables identify the libjpeg quality setting. Compare with a 16x16 encode at q92.
    probe = io.BytesIO()
    Image.new("RGB", (16, 16)).save(probe, format="JPEG", quality=EXPECTED_JPEG_QUALITY)
    probe.seek(0)
    expected_q = Image.open(probe).quantization
    actual_q = getattr(image, "quantization", None)
    info["quality_tables_match_q92"] = bool(actual_q) and all(
        list(actual_q[k]) == list(expected_q[k]) for k in expected_q)
    if not info["quality_tables_match_q92"]:
        reasons.append("JPEG quantisation tables do not match quality 92")

    icc = image.info.get("icc_profile")
    if not icc:
        reasons.append("no embedded ICC profile: colour space is not tagged as sRGB")
        info["icc_description"] = None
    else:
        try:
            desc = ImageCms.getProfileDescription(ImageCms.ImageCmsProfile(io.BytesIO(icc))).strip()
            info["icc_description"] = desc
            if "srgb" not in desc.lower():
                reasons.append(f"embedded ICC profile is '{desc}', not sRGB")
        except Exception as exc:  # noqa: BLE001
            reasons.append(f"embedded ICC profile is unreadable: {exc}")

    if provenance is not None:
        conv = provenance.get("conversion", {})
        out = provenance.get("output", {})
        declared = {
            "output sha256": (out.get("sha256"), info["sha256"]),
            "output dimensions": (out.get("dimensions"), [info.get("width"), info.get("height")]),
            "exposure_ev": (conv.get("exposure_ev"), 0.0),
            "highlight_roll_off": (conv.get("highlight_roll_off"), "none"),
            "jpeg_quality": (conv.get("jpeg_quality"), EXPECTED_JPEG_QUALITY),
            "chroma_subsampling": (conv.get("chroma_subsampling"), "4:4:4"),
        }
        info["provenance_declared"] = {k: v[0] for k, v in declared.items()}
        for name, (recorded, actual_v) in declared.items():
            if recorded != actual_v:
                reasons.append(f"provenance {name} is {recorded!r}, file/spec is {actual_v!r}")
        conv_path = os.path.join(HERE, "convert_milkyway_exr.py")
        if conv.get("converter_sha256") != sha256_file(conv_path):
            reasons.append("provenance converter_sha256 does not match convert_milkyway_exr.py on disk "
                           "(converter changed after this conversion was recorded)")
    return {"reasons": reasons, "info": info}


# ---------------------------------------------------------------- C. orientation

def orientation_candidates(reference: np.ndarray) -> dict[str, np.ndarray]:
    return {
        "identity": reference,
        "vertical flip": reference[::-1, :, :].copy(),
        "horizontal mirror": reference[:, ::-1, :].copy(),
        "180-degree rotation": reference[::-1, ::-1, :].copy(),
    }


def check_orientation(output: np.ndarray, reference: np.ndarray) -> dict:
    reasons: list[str] = []
    scores: dict[str, dict] = {}
    for name, cand in orientation_candidates(reference).items():
        # Same encoder, same settings for every candidate: codec error is common to all of them.
        decoded_cand = decode_rgb(encode_reference_jpeg(cand))
        mad = float(np.mean(np.abs(output.astype(np.int16) - decoded_cand.astype(np.int16))))
        scores[name] = {"mean_abs_diff": round(mad, 4), "psnr_db": round(psnr(output, decoded_cand), 2)}

    best = min(scores, key=lambda n: scores[n]["mean_abs_diff"])
    correct = scores["identity"]["mean_abs_diff"]
    alternatives = {n: s["mean_abs_diff"] for n, s in scores.items() if n != "identity"}
    best_alt_name = min(alternatives, key=alternatives.get)
    separation = alternatives[best_alt_name] - correct
    result = {
        "scores_mean_abs_diff": {n: s["mean_abs_diff"] for n, s in scores.items()},
        "scores_psnr_db": {n: s["psnr_db"] for n, s in scores.items()},
        "best_candidate": best,
        "best_alternative": best_alt_name,
        "separation_levels": round(separation, 4),
        "required_separation_levels": ORIENTATION_MIN_SEPARATION,
    }
    if best != "identity":
        reasons.append(f"pixels match a {best} of the source, not the correct orientation")
    elif separation < ORIENTATION_MIN_SEPARATION:
        reasons.append(f"orientation separation {separation:.2f} levels is below the required "
                       f"{ORIENTATION_MIN_SEPARATION} (closest alternative: {best_alt_name})")
    result["reasons"] = reasons
    return result


# ---------------------------------------------------------------- D. codec fidelity

def check_codec(output: np.ndarray, reference: np.ndarray) -> dict:
    reasons: list[str] = []
    diff = np.abs(output.astype(np.int16) - reference.astype(np.int16))
    per_channel_mae = [round(float(diff[..., c].mean()), 4) for c in range(3)]
    measured = {
        "vs_pre_jpeg_reference": {
            "mean_abs_diff": round(float(diff.mean()), 4),
            "max_abs_diff": int(diff.max()),
            "psnr_db": round(psnr(output, reference), 2),
            "per_channel_mean_abs_diff_RGB": per_channel_mae,
            "fraction_pixels_changed": round(float((diff.max(axis=-1) > 0).mean()), 6),
        },
    }
    repro = decode_rgb(encode_reference_jpeg(reference))
    repro_diff = np.abs(output.astype(np.int16) - repro.astype(np.int16))
    measured["reproducibility_vs_independent_encode"] = {
        "max_abs_diff": int(repro_diff.max()),
        "mean_abs_diff": round(float(repro_diff.mean()), 6),
        "allowed_max_abs_diff": CODEC_REPRO_MAX_ABS,
    }
    if int(repro_diff.max()) > CODEC_REPRO_MAX_ABS:
        reasons.append(f"output differs from an independent encode of the reference by up to "
                       f"{int(repro_diff.max())} levels (allowed {CODEC_REPRO_MAX_ABS}); the pixels "
                       f"are not what the specified encoder produces")
    return {"reasons": reasons, "measured": measured}


# ---------------------------------------------------------------- orchestration

def validate(jpeg_path: str, expected_output_sha256: str | None = None,
             source_exr: str | None = None, expected_source_sha256: str | None = None,
             official_sha256: str | None = None, provenance_path: str | None = None,
             exposure_ev: float = 0.0) -> dict:
    report: dict = {"reasons": [], "accepted": False}

    provenance = None
    if provenance_path is not None:
        with open(provenance_path, "r", encoding="utf-8") as fh:
            provenance = json.load(fh)

    with open(jpeg_path, "rb") as fh:
        raw = fh.read()

    conv = check_conversion(raw, jpeg_path, expected_output_sha256, provenance)
    report["B_conversion_integrity"] = conv["info"]
    report["reasons"] += conv["reasons"]

    if source_exr is not None:
        src = check_source(source_exr, expected_source_sha256, official_sha256)
        report["A_source_integrity"] = src["info"]
        report["reasons"] += src["reasons"]
        if src["info"].get("header") is not None and not src["reasons"]:
            from convert_milkyway_exr import convert_to_uint8
            reference = convert_to_uint8(source_exr, exposure_ev)
            from PIL import Image
            with Image.open(io.BytesIO(raw)) as im:
                decodable = im.size == (EXPECTED_WIDTH, EXPECTED_HEIGHT)
            if decodable:
                output = decode_rgb(raw)
                orient = check_orientation(output, reference)
                report["C_orientation"] = {k: v for k, v in orient.items() if k != "reasons"}
                report["reasons"] += orient["reasons"]
                codec = check_codec(output, reference)
                report["D_codec_fidelity"] = codec["measured"]
                report["reasons"] += codec["reasons"]
        else:
            report["C_orientation"] = "NOT RUN (source or conversion checks failed)"
            report["D_codec_fidelity"] = "NOT RUN (source or conversion checks failed)"
    else:
        report["A_source_integrity"] = "NOT RUN (no --source supplied)"
        report["C_orientation"] = "NOT RUN (no --source supplied)"
        report["D_codec_fidelity"] = "NOT RUN (no --source supplied)"

    report["accepted"] = not report["reasons"] and source_exr is not None
    if source_exr is None:
        report["reasons"].append("no --source supplied: orientation and codec checks were not run")
    return report


def print_report(report: dict) -> None:
    print("ACCEPTED" if report["accepted"] else "REJECTED")
    for key in ("A_source_integrity", "B_conversion_integrity", "C_orientation", "D_codec_fidelity"):
        val = report.get(key)
        if isinstance(val, str):
            print(f"{key}: {val}")
        elif val is not None:
            print(f"{key}:")
            print(json.dumps(val, indent=2, sort_keys=True))
    for r in report["reasons"]:
        print(" - " + r)


def main(argv: list[str]) -> int:
    parser = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    parser.add_argument("jpeg")
    parser.add_argument("--expected-output-sha256", help="Expected SHA-256 of the JPEG file")
    parser.add_argument("--source", help="Original EXR (for the header, orientation and codec checks)")
    parser.add_argument("--expected-source-sha256", help="Expected SHA-256 of the EXR file")
    parser.add_argument("--official-sha256", help="Independent official NASA checksum, only if NASA publishes one")
    parser.add_argument("--provenance", help="Provenance JSON whose declared settings must match the file")
    parser.add_argument("--exposure-ev", type=float, default=0.0)
    parser.add_argument("--report-json", help="Write the full machine-readable report here")
    args = parser.parse_args(argv)
    if not os.path.isfile(args.jpeg):
        print(f"missing file: {args.jpeg}", file=sys.stderr)
        return 2
    if args.source and not os.path.isfile(args.source):
        print(f"missing source: {args.source}", file=sys.stderr)
        return 2
    report = validate(args.jpeg, args.expected_output_sha256, args.source, args.expected_source_sha256,
                      args.official_sha256, args.provenance, args.exposure_ev)
    print_report(report)
    if args.report_json:
        with open(args.report_json, "w", encoding="utf-8") as fh:
            json.dump(report, fh, indent=2, sort_keys=True)
    return 0 if report["accepted"] else 1


if __name__ == "__main__":
    sys.exit(main(sys.argv[1:]))
