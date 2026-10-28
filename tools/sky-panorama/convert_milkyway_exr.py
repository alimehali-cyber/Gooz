#!/usr/bin/env python3
"""
Offline converter: NASA SVS "Deep Star Maps 2020" Milky Way EXR -> ZIG runtime panorama (JPEG).

Source (not fetched by this script; download it yourself and pass the path):
  https://svs.gsfc.nasa.gov/4851/
  https://svs.gsfc.nasa.gov/vis/a000000/a004800/a004851/milkyway_2020_4k.exr
  Credit (from the NASA page): "NASA/Goddard Space Flight Center Scientific Visualization Studio.
  Gaia DR2: ESA/Gaia/DPAC." Visualization by Ernie Wright (USRA).

Pipeline (documented so that it can be re-run and audited):
  1. Read the EXR as half-float linear RGB and verify the 4096 x 2048 data window.
  2. Apply an optional exposure gain in stops (default 0 EV, i.e. no change).
  3. Clip to [0, 1]. No highlight shoulder or other tone curve is applied.
  4. Encode with the sRGB transfer function (IEC 61966-2-1). The linear
     values are NOT reinterpreted directly as sRGB.
  5. Quantize to 8 bits with a deterministic triangular (TPDF) dither of +/- 1 LSB, which
     prevents posterization of the dark gradients.
  6. Encode a 4:4:4 baseline JPEG (quality 92) and write a JSON provenance sidecar.

The source file is opened read-only and never modified. Nothing is baked in: no stars, flares,
gradients or nebulae are added. The conversion is only a colour-space and bit-depth change plus
no tone curve.

Requirements (offline development machine only, never shipped in the APK):
  pip install OpenEXR numpy pillow

Usage:
  python3 convert_milkyway_exr.py milkyway_2020_4k.exr \
      --out ../../app/src/main/assets/sky/panorama/milkyway_2020_4k.jpg \
      --provenance milkyway_2020_4k.provenance.json
"""

from __future__ import annotations

import argparse
import datetime as _dt
import hashlib
import json
import os
import sys

import numpy as np

EXPECTED_WIDTH = 4096
EXPECTED_HEIGHT = 2048
JPEG_QUALITY = 92
SOURCE_URL = "https://svs.gsfc.nasa.gov/vis/a000000/a004800/a004851/milkyway_2020_4k.exr"
SOURCE_PAGE = "https://svs.gsfc.nasa.gov/4851/"
SOURCE_TITLE = "NASA SVS | Deep Star Maps 2020"
CREDIT = ("NASA/Goddard Space Flight Center Scientific Visualization Studio. "
          "Gaia DR2: ESA/Gaia/DPAC.")


def sha256_file(path: str) -> str:
    h = hashlib.sha256()
    with open(path, "rb") as f:
        for chunk in iter(lambda: f.read(1 << 20), b""):
            h.update(chunk)
    return h.hexdigest()


def exr_header_summary(path: str) -> dict:
    """Non-pixel metadata from the EXR header, recorded for provenance. Never used for decisions."""
    import OpenEXR

    exr = OpenEXR.InputFile(path)
    try:
        header = exr.header()
        dw = header["dataWindow"]
        dsp = header.get("displayWindow")
        summary = {
            "dataWindow": [dw.min.x, dw.min.y, dw.max.x, dw.max.y],
            "displayWindow": None if dsp is None else [dsp.min.x, dsp.min.y, dsp.max.x, dsp.max.y],
            "channels": sorted(header["channels"].keys()),
            "attributes": sorted(k for k in header.keys()),
        }
        return summary
    finally:
        exr.close()


def read_exr_rgb(path: str) -> np.ndarray:
    """Returns float32 array (H, W, 3) of linear RGB. Raises if the layout is unexpected."""
    import Imath
    import OpenEXR

    exr = OpenEXR.InputFile(path)
    try:
        header = exr.header()
        dw = header["dataWindow"]
        width = dw.max.x - dw.min.x + 1
        height = dw.max.y - dw.min.y + 1
        if (width, height) != (EXPECTED_WIDTH, EXPECTED_HEIGHT):
            raise SystemExit(
                f"Unexpected EXR size {width}x{height}; expected {EXPECTED_WIDTH}x{EXPECTED_HEIGHT}. "
                "Refusing to convert a different source without an explicit decision.")
        channels = set(header["channels"].keys())
        if not {"R", "G", "B"}.issubset(channels):
            raise SystemExit(f"EXR has channels {sorted(channels)}; expected R, G, B.")
        half = Imath.PixelType(Imath.PixelType.HALF)
        planes = []
        for name in ("R", "G", "B"):
            raw = exr.channel(name, half)
            arr = np.frombuffer(raw, dtype=np.float16).reshape(height, width)
            planes.append(arr.astype(np.float32))
        return np.stack(planes, axis=-1)
    finally:
        exr.close()


def tone_map_highlights(linear: np.ndarray, exposure_ev: float) -> np.ndarray:
    """Exposure gain then clip to [0, 1]. No highlight shoulder, no tone curve.

    With the default exposure_ev of 0.0 this is a pure clip: the NASA linear values are kept
    exactly, and the only remaining step is the sRGB transfer function.
    """
    x = np.clip(linear, 0.0, None) * (2.0 ** exposure_ev)
    return np.clip(x, 0.0, 1.0)


def linear_to_srgb(v: np.ndarray) -> np.ndarray:
    """IEC 61966-2-1 sRGB OETF for values in [0, 1]."""
    return np.where(v <= 0.0031308, 12.92 * v, 1.055 * np.power(v, 1.0 / 2.4) - 0.055)


def quantize_with_tpdf_dither(srgb01: np.ndarray, seed: int = 0x5A1C) -> np.ndarray:
    """Deterministic TPDF dither of +/- 1 LSB, then round to uint8."""
    rng = np.random.default_rng(seed & 0xFFFFFFFF)
    noise = rng.random(srgb01.shape, dtype=np.float32) - rng.random(srgb01.shape, dtype=np.float32)
    scaled = srgb01.astype(np.float32) * 255.0 + noise
    return np.clip(np.rint(scaled), 0, 255).astype(np.uint8)


def convert_to_uint8(source: str, exposure_ev: float = 0.0) -> np.ndarray:
    """The complete, deterministic pixel pipeline. Used by main() and by validate_panorama.py."""
    linear = read_exr_rgb(source)
    mapped = tone_map_highlights(linear, exposure_ev)
    encoded = linear_to_srgb(mapped)
    return quantize_with_tpdf_dither(encoded)


def srgb_icc_profile() -> bytes:
    """Built-in sRGB profile from LittleCMS, with its ICC creation date pinned.

    createProfile() writes the current time into ICC header bytes 24-35, so without this the
    output differs between runs in those two bytes. The pinned date is 2000-01-01 00:00:00. The
    colour data is unchanged, and the entropy-coded image data is identical either way.
    """
    import struct
    from PIL import ImageCms

    raw = bytearray(ImageCms.ImageCmsProfile(ImageCms.createProfile("sRGB")).tobytes())
    raw[24:36] = struct.pack(">6H", 2000, 1, 1, 0, 0, 0)
    return bytes(raw)


def encode_panorama_jpeg(pixels: np.ndarray) -> bytes:
    """The single definition of the runtime JPEG encoding: baseline, quality 92, 4:4:4, optimised
    Huffman tables, embedded built-in sRGB ICC profile. The validator re-uses this function so
    that its reference encodes use exactly the same settings as the converter."""
    import io
    from PIL import Image

    image = Image.fromarray(pixels, mode="RGB")
    buf = io.BytesIO()
    image.save(buf, format="JPEG", quality=JPEG_QUALITY, subsampling=0, optimize=True,
               icc_profile=srgb_icc_profile())
    return buf.getvalue()


def main(argv: list[str]) -> int:
    parser = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    parser.add_argument("source", help="Path to milkyway_2020_4k.exr (downloaded from the NASA source)")
    parser.add_argument("--out", required=True, help="Output JPEG path")
    parser.add_argument("--provenance", help="Optional JSON sidecar path")
    parser.add_argument("--exposure-ev", type=float, default=0.0,
                        help="Exposure in stops applied before tone mapping (default 0). "
                             "Must stay 0 unless a technical HDR-to-LDR reason is documented.")
    parser.add_argument("--expected-sha256",
                        help="Refuse to convert unless the source EXR has exactly this SHA-256. "
                             "Use the hash recorded in provenance/milkyway_2020_4k.source.json.")
    args = parser.parse_args(argv)

    source_sha = sha256_file(args.source)
    if args.expected_sha256 and source_sha != args.expected_sha256.lower():
        print(f"REFUSED: source SHA-256 {source_sha} does not match expected {args.expected_sha256}",
              file=sys.stderr)
        return 2
    pixels = convert_to_uint8(args.source, args.exposure_ev)

    os.makedirs(os.path.dirname(os.path.abspath(args.out)), exist_ok=True)
    with open(args.out, "wb") as f:
        f.write(encode_panorama_jpeg(pixels))

    out_size = os.path.getsize(args.out)
    out_sha = sha256_file(args.out)
    record = {
        "source_url": SOURCE_URL,
        "source_page": SOURCE_PAGE,
        "source_title": SOURCE_TITLE,
        "source_sha256": source_sha,
        "source_size_bytes": os.path.getsize(args.source),
        "source_dimensions": [EXPECTED_WIDTH, EXPECTED_HEIGHT],
        "credit": CREDIT,
        "coordinates": "celestial (ICRF/J2000 RA/Dec), RA increasing to the left, centred on RA 0h",
        "output_file": os.path.basename(args.out),
        "output_dimensions": [pixels.shape[1], pixels.shape[0]],
        "output_format": "JPEG baseline, quality %d, 4:4:4" % JPEG_QUALITY,
        "output_size_bytes": out_size,
        "output_sha256": out_sha,
        "exposure_ev": args.exposure_ev,
        "highlight_roll_off": "none",
        "transfer": "clip to [0,1] then sRGB OETF (IEC 61966-2-1); no tone curve",
        "dither": "deterministic TPDF, +/-1 LSB",
        "converted_utc": _dt.datetime.now(_dt.timezone.utc).isoformat(timespec="seconds"),
        "converter": os.path.basename(__file__),
        "converter_sha256": sha256_file(os.path.abspath(__file__)),
        "embedded_icc_profile": "sRGB built-in (PIL ImageCms.createProfile('sRGB'))",
        "exr_header": exr_header_summary(args.source),
    }
    print(json.dumps(record, indent=2))
    if args.provenance:
        with open(args.provenance, "w", encoding="utf-8") as f:
            json.dump(record, f, indent=2)
            f.write("\n")
    return 0


if __name__ == "__main__":
    sys.exit(main(sys.argv[1:]))
