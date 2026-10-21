#!/usr/bin/env python3
"""Package colored ASCII weather PNGs and their normalized scene geometry.

Input contains geometry.json and wide/ and square/ directories, each with
snow.png, rain.png, cloud.png, sun.png and night.png. Output contains those
RGB PNGs and forest-lake.json for the Android weather renderer. At the default
--cell 1, source RGB pixels are unchanged unless contrast/brightness is set.
Larger cells use full-image BOX downsampling with the exact source aspect ratio;
normalized water polygons and light anchors therefore remain unchanged.
"""

import argparse
import json
import math
from pathlib import Path

from PIL import Image


CONDITIONS = ("snow", "rain", "cloud", "sun", "night")
VARIANTS = ("wide", "square")


def positive_cell(value):
    cell = int(value)
    if cell < 1:
        raise argparse.ArgumentTypeError("cell must be an integer >= 1")
    return cell


def finite_number(value):
    number = float(value)
    if not math.isfinite(number):
        raise argparse.ArgumentTypeError("value must be finite")
    return number


def arguments():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("input", nargs="?", type=Path,
                        default=Path("weather-references/gpt-image"),
                        help="source directory containing geometry.json and PNGs")
    parser.add_argument("--cell", type=positive_cell, default=1,
                        help="minimum BOX downsample factor, preserving exact aspect "
                             "and the complete source image (default: 1)")
    parser.add_argument("--contrast", type=finite_number, default=0,
                        help="channel contrast, -255..255 (default: 0)")
    parser.add_argument("--brightness", type=finite_number, default=0,
                        help="channel brightness, -100..100 percent (default: 0)")
    parser.add_argument("--output", type=Path,
                        default=Path("app/src/main/assets/weather"),
                        help="output directory for forest-lake.json and PNGs")
    args = parser.parse_args()
    if not -255 <= args.contrast <= 255:
        parser.error("--contrast must be between -255 and 255")
    if not -100 <= args.brightness <= 100:
        parser.error("--brightness must be between -100 and 100")
    return args


def normalized_tuple(value, size, label):
    if not isinstance(value, list) or len(value) != size:
        raise ValueError(f"{label} must contain {size} normalized numbers")
    if any(type(number) not in (int, float) or not math.isfinite(number)
           or not 0 <= number <= 1 for number in value):
        raise ValueError(f"{label} coordinates must be finite numbers between 0 and 1")
    return value


def load_geometry(path):
    with path.open(encoding="utf-8") as source:
        geometry = json.load(source)
    if (not isinstance(geometry, dict) or type(geometry.get("version")) is not int
            or geometry["version"] != 1):
        raise ValueError(f"{path}: expected geometry version 1")
    variants = geometry.get("variants")
    if not isinstance(variants, dict) or set(variants) != set(VARIANTS):
        raise ValueError(f"{path}: variants must be wide and square")
    ordered = {}
    for variant in VARIANTS:
        entries = variants[variant]
        if not isinstance(entries, list) or len(entries) != len(CONDITIONS):
            raise ValueError(f"{path}: {variant} must contain all five conditions")
        by_name = {}
        for entry in entries:
            if not isinstance(entry, dict) or entry.get("name") not in CONDITIONS:
                raise ValueError(f"{path}: invalid condition in {variant}")
            name = entry["name"]
            label = f"{path}: {variant}/{name}"
            if name in by_name:
                raise ValueError(f"{label}: duplicate condition")
            water = entry.get("water")
            if not isinstance(water, list) or len(water) < 3:
                raise ValueError(f"{label}: water must be a polygon with at least three points")
            for point in water:
                normalized_tuple(point, 2, f"{label}: water point")
            light = normalized_tuple(entry.get("light"), 3, f"{label}: light")
            if light[2] == 0 and any(light):
                raise ValueError(f"{label}: absent light must be [0, 0, 0]")
            by_name[name] = {"name": name, "water": water, "light": light}
        ordered[variant] = [by_name[name] for name in CONDITIONS]
    return ordered


def sampled_size(width, height, cell, path):
    # Integer multiples of the reduced aspect ratio avoid both stretching and
    # dropping partial edge cells, including when cell does not divide the size.
    common = math.gcd(width, height)
    units = common // cell
    if units == 0:
        raise ValueError(f"--cell {cell} cannot downsample {path} ({width}x{height}) "
                         f"while preserving its exact aspect; maximum is {common}")
    return width // common * units, height // common * units


def manifest_text(variants):
    # One compact record per line keeps polygons readable without expanding
    # every coordinate into a separate multi-line JSON array.
    lines = ['{', '  "version": 1,', '  "variants": {']
    for variant_index, variant in enumerate(VARIANTS):
        lines.append(f'    "{variant}": [')
        for index, entry in enumerate(variants[variant]):
            suffix = "," if index + 1 < len(CONDITIONS) else ""
            lines.append("      " + json.dumps(entry, separators=(",", ":"),
                                                allow_nan=False) + suffix)
        suffix = "," if variant_index + 1 < len(VARIANTS) else ""
        lines.append("    ]" + suffix)
    lines.extend(["  }", "}", ""])
    return "\n".join(lines)


def build(args):
    geometry = load_geometry(args.input / "geometry.json")
    sources = []
    # Decode the complete set and validate every output size before creating
    # any destination files. Missing/corrupt references leave output untouched.
    for variant in VARIANTS:
        for entry in geometry[variant]:
            path = args.input / variant / f'{entry["name"]}.png'
            with Image.open(path) as source:
                if source.format != "PNG":
                    raise ValueError(f"{path}: expected a PNG image")
                image = source.convert("RGB")
            size = sampled_size(image.width, image.height, args.cell, path)
            sources.append((variant, entry, path, image, size))

    channel_lut = None
    if args.contrast != 0 or args.brightness != 0:
        contrast_factor = 259 * (args.contrast + 255) / (255 * (259 - args.contrast))
        brightness_adjustment = args.brightness / 100 * 255
        channel_lut = [max(0, min(255, math.floor(
            contrast_factor * (channel - 128) + 128 + brightness_adjustment + 0.5
        ))) for channel in range(256)]

    manifest = {variant: [] for variant in VARIANTS}
    total_bytes = 0
    for variant, entry, path, image, size in sources:
        source_size = image.size
        if channel_lut is not None:
            image = image.point(channel_lut * 3)
        if image.size != size:
            image = image.resize(size, Image.Resampling.BOX)
        relative_path = f'{variant}/{entry["name"]}.png'
        destination = args.output / relative_path
        destination.parent.mkdir(parents=True, exist_ok=True)
        image.save(destination, format="PNG", optimize=False, compress_level=9)
        total_bytes += destination.stat().st_size
        manifest[variant].append({
            "name": entry["name"],
            "image": f"weather/{relative_path}",
            "width": image.width,
            "height": image.height,
            "water": entry["water"],
            "light": entry["light"],
        })
        print(f"{variant}/{entry['name']}: source={source_size[0]}x{source_size[1]}, "
              f"output={image.width}x{image.height}")
    manifest_path = args.output / "forest-lake.json"
    manifest_path.write_text(manifest_text(manifest), encoding="utf-8", newline="\n")
    total_bytes += manifest_path.stat().st_size
    print(f"source={args.input}, cell={args.cell}, "
          f"contrast={args.contrast:g}, brightness={args.brightness:g}")
    print(f"Wrote {args.output}: {len(sources)} PNGs and forest-lake.json, "
          f"{total_bytes:,} bytes")


if __name__ == "__main__":
    build(arguments())
