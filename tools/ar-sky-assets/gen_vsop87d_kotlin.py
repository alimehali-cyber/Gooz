#!/usr/bin/env python3
"""Generates app/src/main/java/com/alijafari/red/astronomy/astro_engine/Vsop87dData.kt.

Input: vsop87d.csv from https://github.com/gmiller123456/vsop87-multilang (Languages/CSV/vsop87d.csv),
the VSOP87D series (Bretagnon & Francou 1988, Bureau des Longitudes): heliocentric ecliptic spherical
coordinates L, B, R referred to the ecliptic and equinox of date. The conversion is public domain
(see THIRD_PARTY_LICENSES/vsop87-multilang-LICENSE.txt). The file's git blob SHA-1 is verified.

Truncation: a term A·cos(B + C·τ) of power α in τ (Julian millennia TDB from J2000.0) is kept when
|A|·0.3^α ≥ 2·10⁻⁷. Over 1800–2200 the truncated series differs from the full series by ≤ 0.31″ in L and B
and ≤ 0.4″ (relative) in R for every planet.

Usage:
  python3 gen_vsop87d_kotlin.py --csv vsop87d.csv \
      --out ../../app/src/main/java/com/alijafari/red/astronomy/astro_engine/Vsop87dData.kt
"""
import argparse
import collections
import csv
import hashlib
import sys

PINNED_BLOB = "f928345f5f1b5cf2d27263fca252f4b318d8d5c0"
THRESHOLD = 2e-7
PLANETS = ["mercury", "venus", "earth", "mars", "jupiter", "saturn", "uranus", "neptune"]

HEADER = '''package com.alijafari.red.astronomy.astro_engine

// GENERATED FILE — do not edit by hand. Produced by tools/ar-sky-assets/gen_vsop87d_kotlin.py from the
// VSOP87D series (Bretagnon & Francou 1988, Bureau des Longitudes; heliocentric ecliptic & equinox of date,
// spherical L/B/R) as distributed in github.com/gmiller123456/vsop87-multilang (Languages/CSV/vsop87d.csv,
// git blob f928345f5f1b5cf2d27263fca252f4b318d8d5c0, public domain). Terms are kept when
// |A|·0.3^α ≥ 2·10⁻⁷ (α = power of τ); over 1800–2200 the truncation differs from the full series by
// ≤ 0.31″ in L and B and ≤ 0.4″ (relative) in R for every planet (validated against the full CSV).
// Each term is (A, B, C): A·cos(B + C·τ), τ = Julian millennia (TDB) from J2000.0.
'''


def git_blob_sha1(path):
    data = open(path, "rb").read()
    return hashlib.sha1(b"blob %d\0" % len(data) + data).hexdigest()


def main():
    ap = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    ap.add_argument("--csv", required=True, help="vsop87d.csv from gmiller123456/vsop87-multilang")
    ap.add_argument("--out", required=True, help="output Kotlin file (Vsop87dData.kt)")
    a = ap.parse_args()

    blob = git_blob_sha1(a.csv)
    if blob != PINNED_BLOB:
        sys.exit("%s: git blob %s does not match the pinned upstream %s" % (a.csv, blob, PINNED_BLOB))

    rows = collections.defaultdict(list)
    with open(a.csv) as f:
        r = csv.reader(f)
        next(r)
        for row in r:
            planet = row[1].strip()
            var = row[2].strip()
            exp = int(row[3])
            rows[(planet, var, exp)].append((row[4].strip(), row[5].strip(), row[6].strip()))

    out = [HEADER]
    total = 0
    for p in PLANETS:
        out.append("internal object %s {" % ("Vsop87d" + p.capitalize()))
        for var in "lbr":
            arrs = []
            for e in range(6):
                ts = [t for t in rows.get((p, var, e), []) if abs(float(t[0])) * (0.3 ** e) >= THRESHOLD]
                total += len(ts)
                arrs.append("        doubleArrayOf(%s)" % ", ".join("%s, %s, %s" % t for t in ts))
            out.append("    val %s: Array<DoubleArray> = arrayOf(\n%s\n    )" % (var.upper(), ",\n".join(arrs)))
        out.append("}\n")
    with open(a.out, "w") as f:
        f.write("\n".join(out))
    print("terms", total)


if __name__ == "__main__":
    main()
