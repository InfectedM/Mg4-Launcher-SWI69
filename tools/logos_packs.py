#!/usr/bin/env python3
"""Builds the radio logo packs per country (loghi/<CC>.zip + loghi/indice.json).

Input: a folder with one subfolder (or one zip) per country, named "CC - Name" (e.g. "IT - Italia"),
containing the PNGs and an indice.csv (columns file, nome, uuid, stream, homepage, generi, logo_originale), like the
ones downloaded from Radio Browser.

For each country:
  1. cleans up the list: empty or broken files, duplicates (same normalized name or same image), technical variants
     (Mobile, 48kbps, AAC, HQ...) and themed sub-channels when the main station is present;
  2. shrinks each logo to at most 256x256 (aspect ratio and transparency kept) as WebP (Android 9 can read it);
  3. renames it with the launcher's normalized name (lowercase, letters and digits only: "RTL 102.5" -> rtl1025);
  4. writes alias.txt (alternative names and short RDS names -> file);
  5. creates loghi/<CC>.zip and updates loghi/indice.json (country name in the launcher's languages, version, size,
     SHA-256, number of logos).
At the end it writes the summary riepilogo.md (logos and size before and after) and the major national FM stations
that are missing.

Usage: python3 tools/logos_packs.py <input_folder> <output_folder> [--countries IT,PL] [--version 1]
       [--alias-extra tools/logos_alias_extra.txt] [--major tools/logos_major.txt] [--preview folder] [--overwrite]
Requires Pillow (and Babel for the country names): pip3 install pillow babel

Note: the generated files (indice.json and its keys, alias.txt, riepilogo.md and its section headings) keep their
Italian names and text: they are a data format read by the launcher and by logos_refine.py.
"""
import argparse
import csv
import hashlib
import io
import json
import os
import re
import sys
import unicodedata
import zipfile

from PIL import Image

LANGS = ["it", "en", "es", "de", "pt", "fr", "tr", "pl"]
MAX = 256
WEBP_QUALITY = 82

# ---------------------------------------------------------------------------------------------------------------
# names


def norm(s):
    """The launcher's normalized name (StationLogos.norm): no accents, lowercase, letters and digits only."""
    s = unicodedata.normalize("NFD", s or "")
    s = "".join(ch for ch in s if unicodedata.category(ch) != "Mn")
    s = s.replace("ł", "l").replace("Ł", "L").replace("ø", "o").replace("Ø", "O").replace("ß", "ss").replace("ı", "i")
    return re.sub(r"[^a-z0-9]", "", s.lower())


# technical variants of the same stream: removed if the main station exists
TECH = re.compile(r"(\[[^\]]*\]|\((?:[^)]*kbps|[^)]*aac|[^)]*mp3|[^)]*hq|[^)]*mobile|[^)]*low|[^)]*high)[^)]*\)|"
                  r"\b(mobile|\d+\s*k(?:bps|b)?|aac\+?|he-?aac|mp3|ogg|opus|hq|hd|lq|low|high quality|hi-?fi|"
                  r"stream|backup|alt(?:ernative)?|ssl|https?|\+1|test)\b)", re.I)
# group prefixes that Radio Browser puts in front of the name (GEDI - Radio Capital)
PREFIX = re.compile(r"^(gedi|rai|mediaset|rtl group|radiomediaset|ens)\s*[-–:|]\s*", re.I)
# words too generic to be a "main station" whose sub-channels get removed
GENERIC = {"radio", "fm", "webradio", "radioweb", "music", "hits", "the", "radiofm", "net", "online", "tv"}


def find_key(name, present, alias=None):
    """Pack file for a name: identical, with or without "radio" before/after, or through the aliases."""
    k = norm(name)
    alias = alias or {}
    for c in (k, "radio" + k, k[5:] if k.startswith("radio") else None, k + "radio", k + "fm"):
        if c and c in present:
            return c
        if c and c in alias:
            return alias[c]
    return None


def clean_name(n):
    n = (n or "").strip()
    n = re.sub(r"^[#_*'\s]+", "", n)
    n = PREFIX.sub("", n)
    n = re.split(r"\s+[|]\s+", n)[0]
    return n.strip()


def base_name(n):
    """Name without the technical parts (bitrate, Mobile, AAC...)."""
    b = TECH.sub(" ", n)
    b = re.sub(r"[-–,;:|]+\s*$", "", re.sub(r"\s+", " ", b)).strip()
    return b


# ---------------------------------------------------------------------------------------------------------------
# images


def load(data):
    im = Image.open(io.BytesIO(data))
    im.load()
    if im.width < 16 or im.height < 16:
        raise ValueError("troppo piccola")
    return im.convert("RGBA")


def ahash(im, size=12):
    """Image fingerprint (average over 12x12, colors and transparency): same fingerprint = same logo."""
    bg = Image.new("RGBA", im.size, (255, 255, 255, 255))
    bg.alpha_composite(im)
    small = bg.convert("L").resize((size, size), Image.BILINEAR)
    px = list(small.tobytes())
    avg = sum(px) / len(px)
    bits = "".join("1" if p > avg else "0" for p in px)
    return "%036x" % int(bits, 2)


def shrink(im):
    """Crops transparent borders, shrinks to at most 256 px and encodes as WebP with transparency."""
    bbox = im.getchannel("A").getbbox()
    if bbox:
        im = im.crop(bbox)
    if max(im.size) > MAX:
        k = MAX / max(im.size)
        im = im.resize((max(1, round(im.width * k)), max(1, round(im.height * k))), Image.LANCZOS)
    out = io.BytesIO()
    im.save(out, "WEBP", quality=WEBP_QUALITY, method=6)
    return out.getvalue(), im.size


# ---------------------------------------------------------------------------------------------------------------
# sources (folders or zips)


class Source:
    """A "CC - Name" folder, or the zip that contains it."""

    def __init__(self, path):
        self.path = path
        self.zip = zipfile.ZipFile(path) if path.endswith(".zip") else None
        if self.zip:
            names = [n for n in self.zip.namelist() if n.endswith("indice.csv")]
            if not names:
                raise ValueError("indice.csv missing in " + path)
            self.prefix = names[0][: -len("indice.csv")]
            folder = self.prefix.rstrip("/").split("/")[-1]
        else:
            self.prefix = ""
            folder = os.path.basename(path.rstrip("/"))
        self.cc = folder[:2].upper()
        self.label = folder[5:] if len(folder) > 5 else folder

    def read(self, name):
        if self.zip:
            try:
                return self.zip.read(self.prefix + name)
            except KeyError:
                return None
        p = os.path.join(self.path, name)
        return open(p, "rb").read() if os.path.exists(p) else None

    def rows(self):
        data = self.read("indice.csv") or b""   # empty folder (e.g. GG, JE): no logos
        return list(csv.DictReader(io.StringIO(data.decode("utf-8-sig"))))


# ---------------------------------------------------------------------------------------------------------------


def build_country(src, extra_alias, preview=None):
    rows = src.rows()
    before_n, before_bytes = 0, 0
    # categories and notes of removed logos end up in riepilogo.md, so they stay in Italian like the rest of it
    dropped = {"rotti": [], "varianti": [], "sottocanali": [], "doppioni": []}
    items = []
    for r in rows:
        data = src.read(r["file"])
        if data:
            before_n += 1
            before_bytes += len(data)
        name = clean_name(r.get("nome") or r["file"].rsplit("_", 1)[0])
        if len(norm(name)) > 40:   # very long spam names: no real station is called like that
            dropped["rotti"].append(name[:40] + "… (nome troppo lungo)")
            continue
        if not data:
            dropped["rotti"].append(name + " (file mancante)")
            continue
        try:
            im = load(data)
        except Exception as e:
            dropped["rotti"].append("%s (%s)" % (name, e))
            continue
        items.append({"name": name, "base": base_name(name), "img": im, "hash": ahash(im), "orig": r.get("nome", "")})

    # 1) technical variants: same base name as another entry without technical parts
    plain = {norm(i["name"]) for i in items if i["base"] == i["name"]}
    keep = []
    for i in items:
        if i["base"] != i["name"] and norm(i["base"]) in plain:
            dropped["varianti"].append(i["name"])
        else:
            if i["base"] != i["name"]:
                i["name"] = i["base"]  # only the variant exists: keep the cleaned name
            keep.append(i)
    items = keep

    # 2) duplicates by normalized name: keep the largest image, the other names become aliases
    byname = {}
    for i in items:
        k = norm(i["name"])
        if not k:
            dropped["rotti"].append(i["name"] + " (nome vuoto)")
            continue
        cur = byname.get(k)
        if cur is None:
            byname[k] = i
            i["aliases"] = set()
        else:
            dropped["doppioni"].append(i["name"])
            if i["img"].width * i["img"].height > cur["img"].width * cur["img"].height:
                i["aliases"] = cur["aliases"]
                byname[k] = i
    items = list(byname.values())

    # 3) themed sub-channels: name starting with another station's name (e.g. "Virgin Radio Hard Rock")
    keys = {norm(i["name"]) for i in items}
    mains = sorted((k for k in keys if len(k) >= 4 and k not in GENERIC), key=len, reverse=True)
    keep = []
    for i in items:
        k = norm(i["name"])
        parent = next((m for m in mains if m != k and k.startswith(m)), None)
        if parent:
            dropped["sottocanali"].append(i["name"] + " → " + parent)
        else:
            keep.append(i)
    items = keep

    # 4) duplicates by image: same logo under different names → one file, the other names become aliases
    byhash = {}
    for i in items:
        h = i["hash"]
        if h in byhash:
            main = byhash[h]
            if len(norm(i["name"])) < len(norm(main["name"])):
                i["aliases"] |= main["aliases"] | {main["name"]}
                byhash[h] = i
            else:
                main["aliases"] |= i["aliases"] | {i["name"]}
            dropped["doppioni"].append(i["name"] + " (stessa immagine)")
        else:
            byhash[h] = i
    items = sorted(byhash.values(), key=lambda x: norm(x["name"]))

    # 5) shrinking and aliases
    files = {}
    alias = {}
    for i in items:
        data, size = shrink(i["img"])
        key = norm(i["name"])
        files[key + ".webp"] = data
        for a in i["aliases"]:
            if norm(a) and norm(a) != key:
                alias[norm(a)] = key
        if preview is not None:
            preview[key] = data
    present = set(k[:-5] for k in files)
    for a, target in extra_alias.get(src.cc, {}).items():
        t = find_key(target, present, alias)
        if t and norm(a) and norm(a) != t:
            alias[norm(a)] = t
    return files, alias, dropped, before_n, before_bytes


def country_names(cc, fallback):
    names = {}
    try:
        from babel import Locale
        for lang in LANGS:
            n = Locale.parse(lang).territories.get(cc)
            names[lang] = n[0].upper() + n[1:] if n else fallback
    except Exception:
        names = {lang: fallback for lang in LANGS}
    return names


def read_extra_alias(path):
    """File of "CC | broadcast name = file" lines (empty lines and # lines are ignored)."""
    out = {}
    if not path or not os.path.exists(path):
        return out
    for line in open(path, encoding="utf-8"):
        line = line.strip()
        if not line or line.startswith("#") or "|" not in line or "=" not in line:
            continue
        cc, rest = line.split("|", 1)
        a, t = rest.split("=", 1)
        out.setdefault(cc.strip().upper(), {})[a.strip()] = t.strip()
    return out


def read_major(path):
    """File of "CC | station name" lines for the major national FM stations (to list the missing ones)."""
    out = {}
    if not path or not os.path.exists(path):
        return out
    for line in open(path, encoding="utf-8"):
        line = line.strip()
        if not line or line.startswith("#") or "|" not in line:
            continue
        cc, n = line.split("|", 1)
        out.setdefault(cc.strip().upper(), []).append(n.strip())
    return out


def main():
    ap = argparse.ArgumentParser(description="Builds the radio logo packs per country (loghi/<CC>.zip + indice.json).")
    ap.add_argument("input", help="folder with one \"CC - Name\" subfolder or zip per country")
    ap.add_argument("output", help="output folder (e.g. loghi)")
    ap.add_argument("--countries", default="", help="comma-separated country codes to build (default: all)")
    ap.add_argument("--version", type=int, default=1, help="pack version written to indice.json")
    here = os.path.dirname(os.path.abspath(__file__))
    ap.add_argument("--alias-extra", default=os.path.join(here, "logos_alias_extra.txt"), help="extra RDS/DAB aliases file")
    ap.add_argument("--major", default=os.path.join(here, "logos_major.txt"), help="major national FM stations file")
    ap.add_argument("--preview", default="", help="folder where to also write every shrunk logo, for a visual check")
    ap.add_argument("--overwrite", action="store_true", help="also rebuild the countries already in the output folder")
    args = ap.parse_args()

    wanted = {p.strip().upper() for p in args.countries.split(",") if p.strip()}
    extra = read_extra_alias(args.alias_extra)
    major = read_major(args.major)
    os.makedirs(args.output, exist_ok=True)
    idx_path = os.path.join(args.output, "indice.json")
    index = json.load(open(idx_path)) if os.path.exists(idx_path) else {"versione": args.version, "paesi": []}
    index["versione"] = args.version
    report = []

    entries = sorted(os.listdir(args.input))
    for e in entries:
        p = os.path.join(args.input, e)
        if not (os.path.isdir(p) or e.endswith(".zip")) or e.startswith("."):
            continue
        try:
            src = Source(p)
        except Exception as ex:
            print("skipping", e, ex)
            continue
        if wanted and src.cc not in wanted:
            continue
        if os.path.exists(os.path.join(args.output, src.cc + ".zip")) and not args.overwrite:
            print("skipping", src.cc, "(pack already present; use --overwrite to rebuild it)")
            continue
        preview = {} if args.preview else None
        files, alias, dropped, bn, bb = build_country(src, extra, preview)
        if not files:
            report.append((src.cc, src.label, bn, bb, 0, 0, dropped, []))
            continue
        # alias.txt header kept in Italian: it is part of the pack read by the launcher
        alias_txt = "# Loghi radio %s (%s), pacchetto %d\n# nome trasmesso = file (senza estensione)\n" % (src.cc, src.label, args.version)
        alias_txt += "".join("%s = %s\n" % (a, t) for a, t in sorted(alias.items()))
        zpath = os.path.join(args.output, src.cc + ".zip")
        with zipfile.ZipFile(zpath, "w", zipfile.ZIP_STORED) as z:   # WebP is already compressed
            for n, d in sorted(files.items()):
                z.writestr(n, d)
            z.writestr("alias.txt", alias_txt, compress_type=zipfile.ZIP_DEFLATED)
        size = os.path.getsize(zpath)
        sha = hashlib.sha256(open(zpath, "rb").read()).hexdigest()
        index["paesi"] = [x for x in index["paesi"] if x["codice"] != src.cc] + [{
            "codice": src.cc, "nomi": country_names(src.cc, src.label), "file": src.cc + ".zip",
            "versione": args.version, "dimensione": size, "sha256": sha, "loghi": len(files), "alias": len(alias)}]
        present = {k[:-5] for k in files}
        missing = [m for m in major.get(src.cc, []) if not find_key(m, present, alias)]
        report.append((src.cc, src.label, bn, bb, len(files), size, dropped, missing))
        if preview is not None:
            os.makedirs(args.preview, exist_ok=True)
            for k, d in preview.items():
                open(os.path.join(args.preview, src.cc + "_" + k + ".webp"), "wb").write(d)
        print("%s %-24s %4d → %4d logos   %6.1f MB → %5.2f MB" % (src.cc, src.label, bn, len(files), bb / 1e6, size / 1e6))
        sys.stdout.flush()

    index["paesi"].sort(key=lambda x: x["codice"])
    json.dump(index, open(idx_path, "w"), ensure_ascii=False, indent=1)
    rpath = os.path.join(args.output, "riepilogo.md")
    # riepilogo.md text kept in Italian: logos_refine.py looks for its section headings
    with open(rpath, "w", encoding="utf-8") as f:
        f.write("# Pacchetti loghi radio (versione %d)\n\n| Paese | Loghi prima | MB prima | Loghi dopo | MB dopo | Tolti |\n|---|---:|---:|---:|---:|---|\n" % args.version)
        for cc, label, bn, bb, an, ab, dr, _ in report:
            f.write("| %s %s | %d | %.1f | %d | %.2f | %s |\n" % (cc, label, bn, bb / 1e6, an, ab / 1e6,
                    ", ".join("%s %d" % (k, len(v)) for k, v in dr.items() if v)))
        f.write("\n## Stazioni FM nazionali che mancano\n\n")
        for cc, label, *_, missing in report:
            if missing:
                f.write("- **%s %s**: %s\n" % (cc, label, ", ".join(missing)))
        f.write("\n## Dettaglio di cosa è stato tolto\n")
        for cc, label, bn, bb, an, ab, dr, _ in report:
            f.write("\n### %s %s\n" % (cc, label))
            for k, v in dr.items():
                if v:
                    f.write("- %s: %s\n" % (k, "; ".join(v)))
    print("summary:", rpath)


if __name__ == "__main__":
    main()
