#!/usr/bin/env python3
"""
Tests of the VALIDATOR's behaviour on SYNTHETIC fixtures.

These tests show that validate_panorama.py accepts a correctly oriented conversion with realistic
JPEG loss, and rejects flips, mirrors, 180-degree rotations, wrong dimensions, wrong or missing ICC
profiles, wrong subsampling or quality, perturbed pixels and source-hash mismatches.

They do NOT show that any image is NASA's official asset. Source identity is a separate question,
recorded in provenance/milkyway_2020_4k.source.json, and is not established by these tests.

Run:  python3 -m unittest -v tools/sky-panorama/test_validate_panorama.py
Needs: OpenEXR, numpy, pillow (offline development machine only).
"""

from __future__ import annotations

import io
import os
import sys
import tempfile
import unittest

import numpy as np

HERE = os.path.dirname(os.path.abspath(__file__))
sys.path.insert(0, HERE)

import convert_milkyway_exr as conv  # noqa: E402
import validate_panorama as val  # noqa: E402

W, H = conv.EXPECTED_WIDTH, conv.EXPECTED_HEIGHT


def write_exr(path: str, r: np.ndarray, g: np.ndarray, b: np.ndarray) -> None:
    import Imath
    import OpenEXR

    h, w = r.shape
    header = OpenEXR.Header(w, h)
    header["channels"] = {c: Imath.Channel(Imath.PixelType(Imath.PixelType.HALF)) for c in "RGB"}
    out = OpenEXR.OutputFile(path, header)
    out.writePixels({"R": r.astype(np.float16).tobytes(),
                     "G": g.astype(np.float16).tobytes(),
                     "B": b.astype(np.float16).tobytes()})
    out.close()


def synthetic_panorama(width: int = W, height: int = H, seed: int = 20260417):
    """Asymmetric in BOTH axes, so a flip or a mirror cannot be confused with the original.

    Contents: a gradient that differs left-to-right and top-to-bottom, an off-centre bluish blob,
    two distinct coloured markers (one per axis), and thousands of seeded point 'stars' with a
    Gaussian profile. The stars supply the high-frequency detail that makes JPEG loss realistic.
    Nothing here is NASA imagery.
    """
    rng = np.random.default_rng(seed)
    y, x = np.mgrid[0:height, 0:width].astype(np.float32)
    base = 0.002 + 0.03 * (y / height) + 0.02 * (x / width) ** 2
    r = base.copy()
    g = base * 0.8
    b = base * 1.2
    blob = 0.6 * np.exp(-(((x - 0.72 * width) / (0.03 * width)) ** 2 + ((y - 0.25 * height) / (0.04 * height)) ** 2))
    r += 0.3 * blob
    g += 0.5 * blob
    b += 0.9 * blob

    # Point stars: positions, peak brightness and colour are all seeded.
    n_stars = 4000
    sy = rng.integers(4, height - 4, n_stars)
    sx = rng.integers(4, width - 4, n_stars)
    amp = (rng.pareto(2.0, n_stars) * 0.05 + 0.01).astype(np.float32)
    col = rng.uniform(0.7, 1.0, (n_stars, 3)).astype(np.float32)
    for k in range(n_stars):
        y0, x0 = sy[k], sx[k]
        ys = slice(y0 - 3, y0 + 4)
        xs = slice(x0 - 3, x0 + 4)
        dy, dx = np.mgrid[-3:4, -3:4].astype(np.float32)
        psf = np.exp(-(dx * dx + dy * dy) / 1.2) * amp[k]
        r[ys, xs] += psf * col[k, 0]
        g[ys, xs] += psf * col[k, 1]
        b[ys, xs] += psf * col[k, 2]

    # Markers: a bright red patch near (row 1500, col 500) and a green patch near (row 300, col 3500).
    r[1500:1516, 500:516] = 0.9
    g[1500:1516, 500:516] = 0.1
    b[1500:1516, 500:516] = 0.1
    r[300:316, 3500:3516] = 0.1
    g[300:316, 3500:3516] = 0.9
    b[300:316, 3500:3516] = 0.2
    return r.astype(np.float32), g.astype(np.float32), b.astype(np.float32)


def icc_bytes_for(name: str) -> bytes | None:
    from PIL import ImageCms
    if name == "srgb":
        return conv.srgb_icc_profile()
    if name == "xyz":
        return ImageCms.ImageCmsProfile(ImageCms.createProfile("XYZ")).tobytes()
    return None


class ValidatorTests(unittest.TestCase):
    @classmethod
    def setUpClass(cls) -> None:
        from PIL import Image
        cls.Image = Image
        cls.tmp = tempfile.TemporaryDirectory()
        r, g, b = synthetic_panorama()
        cls.exr = os.path.join(cls.tmp.name, "synthetic_4k.exr")
        write_exr(cls.exr, r, g, b)
        cls.exr_sha = conv.sha256_file(cls.exr)
        cls.reference = conv.convert_to_uint8(cls.exr, 0.0)
        cls.good_bytes = conv.encode_panorama_jpeg(cls.reference)
        cls.good = os.path.join(cls.tmp.name, "good.jpg")
        with open(cls.good, "wb") as fh:
            fh.write(cls.good_bytes)
        cls.good_sha = val.sha256_bytes(cls.good_bytes)

    @classmethod
    def tearDownClass(cls) -> None:
        cls.tmp.cleanup()

    def _write(self, name: str, pixels: np.ndarray | None = None, raw: bytes | None = None) -> str:
        path = os.path.join(self.tmp.name, name)
        data = raw if raw is not None else conv.encode_panorama_jpeg(pixels)
        with open(path, "wb") as fh:
            fh.write(data)
        return path

    def _validate(self, jpeg: str, **kwargs) -> dict:
        return val.validate(jpeg, source_exr=self.exr, expected_source_sha256=self.exr_sha, **kwargs)

    # --- accept

    def test_accepts_correct_orientation_with_real_jpeg_loss(self) -> None:
        report = self._validate(self.good, expected_output_sha256=self.good_sha)
        self.assertTrue(report["accepted"], report["reasons"])
        orient = report["C_orientation"]
        self.assertEqual(orient["best_candidate"], "identity")
        self.assertGreaterEqual(orient["separation_levels"], val.ORIENTATION_MIN_SEPARATION)
        codec = report["D_codec_fidelity"]["vs_pre_jpeg_reference"]
        # The test must exercise real loss, not a lossless fixture.
        self.assertGreater(codec["mean_abs_diff"], 0.5)
        self.assertLess(codec["psnr_db"], 60.0)
        self.assertEqual(report["D_codec_fidelity"]["reproducibility_vs_independent_encode"]["max_abs_diff"], 0)

    def test_identity_status_is_unverified_unless_official_checksum_given(self) -> None:
        report = self._validate(self.good)
        self.assertEqual(report["A_source_integrity"]["identity_status"], "USER_SUPPLIED_IDENTITY_UNVERIFIED")
        report = self._validate(self.good, official_sha256=self.exr_sha)
        self.assertEqual(report["A_source_integrity"]["identity_status"], "OFFICIAL_SHA256_MATCHED")
        report = self._validate(self.good, official_sha256="0" * 64)
        self.assertFalse(report["accepted"])
        self.assertEqual(report["A_source_integrity"]["identity_status"], "OFFICIAL_SHA256_MISMATCH")

    # --- orientation: each wrong orientation must be named and rejected

    def test_rejects_vertically_flipped_panorama(self) -> None:
        report = self._validate(self._write("flip.jpg", self.reference[::-1, :, :].copy()))
        self.assertFalse(report["accepted"])
        self.assertEqual(report["C_orientation"]["best_candidate"], "vertical flip")
        self.assertTrue(any("vertical flip" in r for r in report["reasons"]), report["reasons"])

    def test_rejects_horizontally_mirrored_panorama(self) -> None:
        report = self._validate(self._write("mirror.jpg", self.reference[:, ::-1, :].copy()))
        self.assertFalse(report["accepted"])
        self.assertEqual(report["C_orientation"]["best_candidate"], "horizontal mirror")
        self.assertTrue(any("horizontal mirror" in r for r in report["reasons"]), report["reasons"])

    def test_rejects_180_degree_rotated_panorama(self) -> None:
        report = self._validate(self._write("rot180.jpg", self.reference[::-1, ::-1, :].copy()))
        self.assertFalse(report["accepted"])
        self.assertEqual(report["C_orientation"]["best_candidate"], "180-degree rotation")
        self.assertTrue(any("180-degree rotation" in r for r in report["reasons"]), report["reasons"])

    def test_orientation_margin_is_reported_for_every_candidate(self) -> None:
        report = self._validate(self.good)
        scores = report["C_orientation"]["scores_mean_abs_diff"]
        self.assertEqual(set(scores), {"identity", "vertical flip", "horizontal mirror", "180-degree rotation"})
        alternatives = [v for k, v in scores.items() if k != "identity"]
        self.assertGreater(min(alternatives) - scores["identity"], val.ORIENTATION_MIN_SEPARATION)

    # --- conversion integrity

    def test_rejects_wrong_expected_output_hash(self) -> None:
        report = self._validate(self.good, expected_output_sha256="0" * 64)
        self.assertFalse(report["accepted"])
        self.assertTrue(any("file SHA-256" in r for r in report["reasons"]), report["reasons"])

    def test_rejects_source_hash_mismatch(self) -> None:
        report = val.validate(self.good, source_exr=self.exr, expected_source_sha256="0" * 64)
        self.assertFalse(report["accepted"])
        self.assertTrue(any("source SHA-256" in r for r in report["reasons"]), report["reasons"])
        self.assertEqual(report["C_orientation"], "NOT RUN (source or conversion checks failed)")

    def test_rejects_resized_panorama(self) -> None:
        small = np.asarray(self.Image.fromarray(self.reference).resize((2048, 1024), self.Image.LANCZOS))
        report = self._validate(self._write("small.jpg", small))
        self.assertFalse(report["accepted"])
        self.assertTrue(any("dimensions are 2048x1024" in r for r in report["reasons"]), report["reasons"])

    def test_rejects_wrong_source_dimensions(self) -> None:
        path = os.path.join(self.tmp.name, "small_source.exr")
        small = np.zeros((1024, 2048), dtype=np.float32) + 0.1
        write_exr(path, small, small, small)
        report = val.validate(self.good, source_exr=path, expected_source_sha256=conv.sha256_file(path))
        self.assertFalse(report["accepted"])
        self.assertTrue(any("source is 2048x1024" in r for r in report["reasons"]), report["reasons"])

    def test_rejects_missing_icc_profile(self) -> None:
        report = self._validate(self._write("untagged.jpg", self.reference, raw=self._bytes_without_icc()))
        self.assertFalse(report["accepted"])
        self.assertTrue(any("no embedded ICC" in r for r in report["reasons"]), report["reasons"])

    def test_rejects_non_srgb_icc_profile(self) -> None:
        raw = self._bytes_with_icc(icc_bytes_for("xyz"))
        report = self._validate(self._write("xyz.jpg", self.reference, raw=raw))
        self.assertFalse(report["accepted"])
        self.assertTrue(any("not sRGB" in r for r in report["reasons"]), report["reasons"])

    def test_rejects_chroma_subsampling_other_than_444(self) -> None:
        buf = io.BytesIO()
        self.Image.fromarray(self.reference).save(buf, format="JPEG", quality=92, subsampling=2,
                                                  icc_profile=conv.srgb_icc_profile())
        report = self._validate(self._write("420.jpg", raw=buf.getvalue()))
        self.assertFalse(report["accepted"])
        self.assertTrue(any("4:2:0" in r for r in report["reasons"]), report["reasons"])

    def test_rejects_quality_other_than_92(self) -> None:
        buf = io.BytesIO()
        self.Image.fromarray(self.reference).save(buf, format="JPEG", quality=85, subsampling=0,
                                                  icc_profile=conv.srgb_icc_profile())
        report = self._validate(self._write("q85.jpg", raw=buf.getvalue()))
        self.assertFalse(report["accepted"])
        self.assertTrue(any("quality 92" in r for r in report["reasons"]), report["reasons"])

    def test_rejects_perturbed_pixels_at_correct_orientation(self) -> None:
        # Negative control for the codec gate: a tiny exposure change (+2 %) is a real pixel
        # change. It must fail the reproducibility gate even though the orientation is correct.
        perturbed = np.clip(self.reference.astype(np.float32) * 1.02, 0, 255).round().astype(np.uint8)
        report = self._validate(self._write("perturbed.jpg", perturbed))
        self.assertFalse(report["accepted"])
        self.assertEqual(report["C_orientation"]["best_candidate"], "identity")
        self.assertTrue(any("independent encode" in r for r in report["reasons"]), report["reasons"])

    def test_missing_source_is_reported_not_accepted(self) -> None:
        report = val.validate(self.good, expected_output_sha256=self.good_sha)
        self.assertFalse(report["accepted"])
        self.assertTrue(any("no --source" in r for r in report["reasons"]), report["reasons"])

    # --- helpers

    def _bytes_with_icc(self, icc: bytes | None) -> bytes:
        buf = io.BytesIO()
        kwargs = {"icc_profile": icc} if icc else {}
        self.Image.fromarray(self.reference).save(buf, format="JPEG", quality=92, subsampling=0,
                                                  optimize=True, **kwargs)
        return buf.getvalue()

    def _bytes_without_icc(self) -> bytes:
        return self._bytes_with_icc(None)


if __name__ == "__main__":
    unittest.main()
