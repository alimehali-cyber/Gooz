#!/usr/bin/env python3
"""
Offline builder for the AR sky runtime assets (never shipped in the APK; run on a dev machine).

Inputs (download them yourself; the exact upstream blobs are pinned in SOURCES below and verified):
  --bsc     bsc5-all.json          Yale Bright Star Catalogue 5th ed. (Hoffleit & Warren 1991) as JSON,
                                    github.com/brettonw/YaleBrightStarCatalog (MIT), converted from the
                                    Harvard TDC ASCII catalogue http://tdc-www.harvard.edu/catalogs/bsc5.html
  --lines   constellations.lines.json  d3-celestial (BSD-3-Clause), github.com/ofrohn/d3-celestial data/
  --names   constellations.json        d3-celestial (BSD-3-Clause), names (incl. Persian "fa") + label anchors
  --textures DIR                   planet/moon maps (see TEXTURES below for provenance and checksums)

Outputs (under app/src/main/assets/ar/):
  stars_bsc5.bin        big-endian: "BSC5", u16 version=1, i32 count, then per star
                        i16 HR, i32 HD, f32 RA(J2000,deg), f32 Dec(J2000,deg),
                        f32 pmRA* (arcsec/yr, = mu_alpha*cos(delta)), f32 pmDec (arcsec/yr),
                        i16 V*100, i16 (B-V)*1000 (32767 = unknown), u8 spectral class letter
  star_names.txt        "HR|IAU/common name" for the BSC "Common" names
  constellations.txt    "C|code|English|Persian|labelRA|labelDec|rank" and "L|code|i,j;i,j;..." where i, j
                        are 0-based indices into stars_bsc5.bin (vertices snapped to the nearest BSC star)
  textures/*.jpg|png    resized maps (Lanczos), JPEG quality 90, 4:4:4

Usage:
  python3 build_ar_assets.py --bsc bsc5-all.json --lines constellations.lines.json \
      --names constellations.json --textures tex/ --out ../../app/src/main/assets/ar
"""
import argparse, hashlib, json, math, os, struct, sys

V_LIMIT = 6.5
SNAP_LIMIT_DEG = 0.40

TEXTURES = {
    # output name: (source file, target size, upstream description, sha1 of the upstream file)
    "moon.jpg": ("moon.jpg", (1024, 512), "Solar System Scope 2k_moon.jpg, CC BY 4.0", "456ef7331c9230955de3b7c22346af6c613f7d3a"),
    "mercury.jpg": ("mercury.jpg", (1024, 512), "Solar System Scope 2k_mercury.jpg, CC BY 4.0", "6f832f061a9c5689a0bd5bd68ff29f49ffc65d29"),
    "venus.jpg": ("venus_atmosphere.jpg", (1024, 512), "Solar System Scope 2k_venus_atmosphere.jpg, CC BY 4.0", "22cf75fcbbbf65433a8dcc9e04fb04cbf98c4369"),
    "mars.jpg": ("nasa_mars.jpg", (1024, 512), "NASA 3D Resources (github.com/nasa/NASA-3D-Resources) 'Images and Textures/Mars/Mars.jpg', NASA, no copyright", "blob:d60372df53b665e7fb05b4e1757d68b9152e6278"),
    "jupiter.jpg": ("jupiter.jpg", (1024, 512), "Solar System Scope 2k_jupiter.jpg, CC BY 4.0", "11ebb1d50e85505005cd5f13309c0bd6ee16b268"),
    "saturn.jpg": ("saturn.jpg", (1024, 512), "Solar System Scope 2k_saturn.jpg, CC BY 4.0", "26b1ded029fabdd7e98dc607ecc17abcc96dd03e"),
    "uranus.jpg": ("uranus.jpg", (512, 256), "Solar System Scope 2k_uranus.jpg, CC BY 4.0", "39fba155ca70e51da9224b9653cbc598b0c41be1"),
    "neptune.jpg": ("neptune.jpg", (512, 256), "Solar System Scope 2k_neptune.jpg, CC BY 4.0", "4ff333c5cccc045dca033bdf1ae24719b26f053b"),
    "saturn_rings.png": ("saturn_ring_alpha.png", (1024, 4), "Solar System Scope 2k_saturn_ring_alpha.png, CC BY 4.0", "50e43ed88548941eb8638c3ca995a8e7fa77af4d"),
}


def sha1(path):
    h = hashlib.sha1()
    with open(path, "rb") as f:
        h.update(f.read())
    return h.hexdigest()


def git_blob_sha1(path):
    with open(path, "rb") as f:
        data = f.read()
    return hashlib.sha1(b"blob %d\0" % len(data) + data).hexdigest()


def sha256(path):
    h = hashlib.sha256()
    with open(path, "rb") as f:
        h.update(f.read())
    return h.hexdigest()


def ra_deg(s):
    return (float(s["RAh"]) + float(s["RAm"]) / 60.0 + float(s["RAs"]) / 3600.0) * 15.0


def dec_deg(s):
    v = float(s["DEd"]) + float(s["DEm"]) / 60.0 + float(s["DEs"]) / 3600.0
    return -v if s["DE-"] == "-" else v


def unit(ra, dec):
    a = math.radians(ra); d = math.radians(dec)
    return (math.cos(d) * math.cos(a), math.cos(d) * math.sin(a), math.sin(d))


def build_stars(bsc_path):
    data = json.load(open(bsc_path))
    stars = []
    for s in data:
        if "RAh" not in s or "Vmag" not in s:
            continue
        v = float(s["Vmag"])
        if v > V_LIMIT:
            continue
        bv = s.get("B-V")
        stars.append({
            "hr": int(s["HR"]),
            "hd": int(s.get("HD", "0") or 0),
            "ra": ra_deg(s),
            "dec": dec_deg(s),
            "pmra": float(s.get("pmRA", "0") or 0.0),
            "pmde": float(s.get("pmDE", "0") or 0.0),
            "v": v,
            "bv": float(bv) if bv not in (None, "") else None,
            "sp": (s.get("SpType") or "?")[:1].upper(),
            "common": s.get("Common"),
        })
    stars.sort(key=lambda x: x["hr"])
    return stars


def write_stars(stars, out_dir):
    path = os.path.join(out_dir, "stars_bsc5.bin")
    with open(path, "wb") as f:
        f.write(b"BSC5")
        f.write(struct.pack(">Hi", 1, len(stars)))
        for s in stars:
            bv = 32767 if s["bv"] is None else int(round(s["bv"] * 1000))
            sp = s["sp"] if s["sp"].isalpha() else "?"
            f.write(struct.pack(">hiffffhhB", s["hr"], s["hd"], s["ra"], s["dec"], s["pmra"], s["pmde"],
                                int(round(s["v"] * 100)), bv, ord(sp)))
    names = os.path.join(out_dir, "star_names.txt")
    with open(names, "w", encoding="utf-8") as f:
        for s in stars:
            if s["common"]:
                f.write("%d|%s\n" % (s["hr"], s["common"].strip()))
    return path, names


def build_constellations(stars, lines_path, names_path, app_fa):
    vecs = [unit(s["ra"], s["dec"]) for s in stars]
    lines = json.load(open(lines_path))
    names = {f["id"]: f for f in json.load(open(names_path))["features"]}
    snapped_worst = 0.0
    out = []
    for feat in lines["features"]:
        code = feat["id"]
        segs = []
        for poly in feat["geometry"]["coordinates"]:
            idx = []
            for lon, lat in poly:
                ra = lon + 360.0 if lon < 0 else lon
                u = unit(ra, lat)
                best = None; bestd = 1e9
                for i, w in enumerate(vecs):
                    c = u[0] * w[0] + u[1] * w[1] + u[2] * w[2]
                    if c < 0.999:
                        continue
                    d = math.degrees(math.acos(min(1.0, c)))
                    # prefer the brighter star among close candidates
                    score = d + 0.02 * max(0.0, stars[i]["v"] - 2.0)
                    if score < bestd:
                        bestd = score; best = (i, d)
                if best is None or best[1] > SNAP_LIMIT_DEG:
                    raise SystemExit("Vertex of %s at (%.3f, %.3f) has no BSC star within %.2f deg" % (code, ra, lat, SNAP_LIMIT_DEG))
                snapped_worst = max(snapped_worst, best[1])
                idx.append(best[0])
            for a, b in zip(idx, idx[1:]):
                if a != b:
                    segs.append((a, b))
        props = names[code]["properties"]
        lon, lat = names[code]["geometry"]["coordinates"]
        out.append({
            "code": code,
            "en": props["name"],
            "fa": app_fa.get(code.upper(), props.get("fa", props["name"])),
            "ra": lon + 360.0 if lon < 0 else lon,
            "dec": lat,
            "rank": int(props.get("rank", "3")),
            "segs": segs,
        })
    return out, snapped_worst


def write_constellations(cons, out_dir):
    path = os.path.join(out_dir, "constellations.txt")
    with open(path, "w", encoding="utf-8") as f:
        for c in cons:
            f.write("C|%s|%s|%s|%.4f|%.4f|%d\n" % (c["code"], c["en"], c["fa"], c["ra"], c["dec"], c["rank"]))
            f.write("L|%s|%s\n" % (c["code"], ";".join("%d,%d" % s for s in c["segs"])))
    return path


def build_textures(tex_dir, out_dir):
    from PIL import Image
    os.makedirs(os.path.join(out_dir, "textures"), exist_ok=True)
    report = {}
    for out_name, (src, size, desc, expect_sha1) in TEXTURES.items():
        src_path = os.path.join(tex_dir, src)
        got = sha1(src_path)
        if expect_sha1 and expect_sha1.startswith("blob:"):
            blob = git_blob_sha1(src_path)
            if blob != expect_sha1[5:]:
                raise SystemExit("%s: git blob %s does not match the pinned upstream %s" % (src, blob, expect_sha1))
        elif expect_sha1 and got != expect_sha1:
            raise SystemExit("%s: sha1 %s does not match the pinned upstream %s" % (src, got, expect_sha1))
        im = Image.open(src_path)
        dst = os.path.join(out_dir, "textures", out_name)
        if out_name == "moon.jpg":
            # Not shipped: the AR/Sky Canvas Moon uses moon_plate.jpg (build_moon_plate_map.py), whose
            # libration zones and far side come from this map.
            dst = os.path.join(os.path.dirname(os.path.abspath(__file__)), "sources", "moon_sss_1024x512.jpg")
        if out_name.endswith(".png"):
            im = im.convert("RGBA")
            # Radial ring profile: average the rows, keep a 4-row strip.
            import numpy as np
            a = np.array(im).astype("float64").mean(axis=0, keepdims=True)
            strip = Image.fromarray(np.repeat(a, 4, axis=0).round().clip(0, 255).astype("uint8"), "RGBA")
            strip = strip.resize(size, Image.LANCZOS)
            strip.save(dst, optimize=True)
        else:
            im = im.convert("RGB").resize(size, Image.LANCZOS)
            im.save(dst, quality=90, subsampling=0, optimize=True)
        report[out_name] = {"source": desc, "source_sha1": got, "size": list(size), "output_sha256": sha256(dst)}
    return report


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--bsc", required=True)
    ap.add_argument("--lines", required=True)
    ap.add_argument("--names", required=True)
    ap.add_argument("--textures", required=True)
    ap.add_argument("--app-fa", default=None, help="optional code|Persian name overrides (from the app catalogue)")
    ap.add_argument("--out", required=True)
    a = ap.parse_args()
    os.makedirs(a.out, exist_ok=True)
    app_fa = {}
    if a.app_fa:
        for line in open(a.app_fa, encoding="utf-8"):
            line = line.strip()
            if "|" in line:
                k, v = line.split("|", 1)
                app_fa[k.strip().upper()] = v.strip()
    stars = build_stars(a.bsc)
    sp, np_ = write_stars(stars, a.out)
    cons, worst = build_constellations(stars, a.lines, a.names, app_fa)
    cp = write_constellations(cons, a.out)
    tex = build_textures(a.textures, a.out)
    prov = {
        "stars": {"count": len(stars), "v_limit": V_LIMIT, "file_sha256": sha256(sp), "source_sha1": sha1(a.bsc)},
        "star_names": {"count": sum(1 for s in stars if s["common"]), "file_sha256": sha256(np_)},
        "constellations": {"count": len(cons), "segments": sum(len(c["segs"]) for c in cons),
                           "worst_vertex_snap_deg": round(worst, 4), "file_sha256": sha256(cp),
                           "lines_source_sha1": sha1(a.lines), "names_source_sha1": sha1(a.names)},
        "textures": tex,
    }
    json.dump(prov, open(os.path.join(os.path.dirname(os.path.abspath(__file__)), "provenance.json"), "w"), indent=2, ensure_ascii=False)
    print(json.dumps(prov, indent=2, ensure_ascii=False))


if __name__ == "__main__":
    main()
