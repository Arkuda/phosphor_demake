#!/usr/bin/env python3
"""Convert the five weather-reference collage panels to FLA2 square samples.

FLA2 is big-endian: magic and uint32 panel count; then per panel uint32
columns, rows, pixelHeight (= rows), float32 normalized light X, Y, radius,
and row-major (luminance8, red, green, blue, water) bytes. Light radius is
relative to panel width. No source bitmap or prequantized glyph is embedded.
Native glyph density is derived continuously from luminance8 / 255 against
the dense-to-sparse ramp "@#S08Xx+=-;:. ", after screen-space interpolation.
Geometry uses the supplied 1254 x 1254 collage's source-pixel coordinates
and scales with input dimensions. Crop right/bottom bounds are exclusive.
"""

import argparse
import math
from pathlib import Path
import struct

from PIL import Image, ImageDraw


REFERENCE_WIDTH = 1254
REFERENCE_HEIGHT = 1254
# Exclude the thin bright frames and their adjacent empty dark border bands.
# The top pair remains square; the lower triptych retains its portrait shape.
# Lake polygons trace open water conservatively, excluding foreground rocks.
# Optional light tuples are source-pixel (center X, center Y, radius).
PANELS = (
    ("snow", (7, 6, 619, 619), (
        (297, 447), (396, 447), (409, 457), (442, 462), (484, 469),
        (487, 480), (479, 506), (467, 524), (449, 536), (455, 545),
        (461, 555), (456, 576), (443, 587), (413, 593), (394, 588),
        (388, 578), (372, 562), (352, 553), (330, 550), (309, 553),
        (286, 547), (278, 535), (275, 520), (265, 505), (252, 494),
        (244, 485), (256, 480), (277, 473), (290, 464),
    ), None),
    ("rain", (635, 6, 1246, 619), (
        (941, 450), (1045, 450), (1054, 463), (1069, 474),
        (1082, 477), (1086, 491), (1077, 505), (1073, 518),
        (1055, 529), (1037, 540), (1043, 551), (1055, 559),
        (1050, 577), (1037, 591), (1020, 604), (990, 604),
        (983, 591), (973, 580), (958, 573), (940, 570), (918, 567),
        (900, 570), (883, 575), (868, 577), (852, 572), (847, 564),
        (869, 553), (897, 549), (922, 552), (937, 544),
        (941, 526), (940, 501), (939, 478),
    ), None),
    ("cloud", (7, 637, 408, 1246), (
        (239, 1081), (288, 1081), (298, 1091), (294, 1104),
        (300, 1114), (320, 1121), (337, 1129), (348, 1140),
        (348, 1157), (340, 1171), (336, 1185), (346, 1198),
        (345, 1214), (354, 1224), (374, 1234), (387, 1238),
        (387, 1242), (320, 1242), (298, 1232), (290, 1221),
        (283, 1205), (270, 1191), (253, 1181), (234, 1175),
        (217, 1177), (204, 1183), (192, 1194), (181, 1199),
        (166, 1200), (166, 1190), (173, 1179), (182, 1167),
        (182, 1153), (178, 1140), (187, 1126), (192, 1110),
        (206, 1106), (221, 1108), (231, 1101), (235, 1090),
    ), None),
    ("sun", (426, 637, 828, 1246), (
        (638, 1058), (674, 1058), (684, 1068), (698, 1070),
        (702, 1082), (707, 1090), (717, 1096), (732, 1095),
        (733, 1110), (729, 1124), (741, 1132), (763, 1142),
        (775, 1150), (770, 1159), (755, 1166), (733, 1170),
        (717, 1181), (706, 1194), (693, 1200), (672, 1199),
        (666, 1186), (651, 1171), (638, 1165), (616, 1163),
        (602, 1171), (584, 1170), (577, 1160), (578, 1149),
        (573, 1137), (572, 1128), (589, 1128), (598, 1118),
        (605, 1104), (614, 1099), (621, 1088), (625, 1073),
    ), (723, 842, 23)),
    ("night", (847, 637, 1246, 1246), (
        (1054, 1057), (1095, 1057), (1104, 1068), (1114, 1078),
        (1132, 1084), (1155, 1088), (1175, 1095), (1176, 1107),
        (1167, 1116), (1152, 1124), (1137, 1132), (1127, 1146),
        (1132, 1155), (1126, 1165), (1105, 1167), (1087, 1164),
        (1071, 1175), (1063, 1187), (1053, 1196), (1034, 1204),
        (1015, 1210), (1000, 1213), (1000, 1197), (1009, 1184),
        (1012, 1170), (1009, 1152), (1010, 1133), (1007, 1120),
        (1010, 1107), (1028, 1095), (1039, 1084), (1048, 1072),
    ), (1136, 744, 25)),
)


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

def foreground_colors(image, columns, rows, cell):
    """Keep the color of luminous source strokes, not their mixture with black gaps.

    Mean luminance remains an independent 8-bit tone. Luminance-weighted RGB
    selects foreground ink, preserving small blue/gold highlights.
    """
    pixels = image.load()
    if cell == 1:
        yield from image.getdata()
        return
    for row in range(rows):
        for col in range(columns):
            red_sum = green_sum = blue_sum = total = 0
            for y in range(row * cell, (row + 1) * cell):
                for x in range(col * cell, (col + 1) * cell):
                    red, green, blue = pixels[x, y]
                    weight = 299 * red + 587 * green + 114 * blue
                    red_sum += red * weight
                    green_sum += green * weight
                    blue_sum += blue * weight
                    total += weight
            if total:
                yield (round(red_sum / total), round(green_sum / total), round(blue_sum / total))
            else:
                yield (0, 0, 0)


def water_coverage(mask, columns, rows, cell):
    """Require every source pixel of a square sample to be inside the lake."""
    if cell == 1:
        yield from (value == 255 for value in mask.getdata())
        return
    pixels = mask.load()
    for row in range(rows):
        for col in range(columns):
            yield all(pixels[x, y] == 255
                      for y in range(row * cell, (row + 1) * cell)
                      for x in range(col * cell, (col + 1) * cell))


def arguments():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("input", nargs="?", type=Path, default=Path("weather-square-ref.png"))
    parser.add_argument("--cell", type=positive_cell, default=1,
                        help="square source cell width and height (default: 1)")
    parser.add_argument("--contrast", type=finite_number, default=0,
                        help="channel contrast, -255..255 (default: 0)")
    parser.add_argument("--brightness", type=finite_number, default=0,
                        help="channel brightness, -100..100 percent (default: 0)")
    parser.add_argument("--output", type=Path,
                        default=Path("app/src/main/assets/weather/forest-lake.bin"))
    args = parser.parse_args()
    if not -255 <= args.contrast <= 255:
        parser.error("--contrast must be between -255 and 255")
    if not -100 <= args.brightness <= 100:
        parser.error("--brightness must be between -100 and 100")
    return args


def build(args):
    with Image.open(args.input) as source:
        image = source.convert("RGB")
    scale_x = image.width / REFERENCE_WIDTH
    scale_y = image.height / REFERENCE_HEIGHT
    # Validate all independent panel dimensions before opening the destination.
    crops = []
    for name, bounds, polygon, light in PANELS:
        left, top, right, bottom = bounds
        left, right = round(left * scale_x), round(right * scale_x)
        top, bottom = round(top * scale_y), round(bottom * scale_y)
        columns = (right - left) // args.cell
        rows = (bottom - top) // args.cell
        if columns == 0 or rows == 0:
            raise ValueError(f"--cell {args.cell} leaves no cells in {name}")
        # Drop partial edge cells rather than stretching the source composition.
        crop = (left, top, left + columns * args.cell, top + rows * args.cell)
        points = [(round(x * scale_x) - left, round(y * scale_y) - top)
                  for x, y in polygon]
        if light is None:
            anchor = (0.0, 0.0, 0.0)
        else:
            x, y, radius = light
            anchor = ((x * scale_x - left) / (columns * args.cell),
                      (y * scale_y - top) / (rows * args.cell),
                      radius * scale_x / (columns * args.cell))
        crops.append((name, crop, columns, rows, points, anchor))

    contrast_factor = 259 * (args.contrast + 255) / (255 * (259 - args.contrast))
    brightness_adjustment = args.brightness / 100 * 255
    channel_lut = [max(0, min(255, math.floor(
        contrast_factor * (channel - 128) + 128 + brightness_adjustment + 0.5
    ))) for channel in range(256)]
    args.output.parent.mkdir(parents=True, exist_ok=True)
    total_cells = 0
    with args.output.open("wb") as output:
        output.write(struct.pack(">4sI", b"FLA2", len(PANELS)))
        for name, crop, columns, rows, polygon, anchor in crops:
            cropped = image.crop(crop).point(channel_lut * 3)
            sampled = cropped.resize((columns, rows), Image.Resampling.BOX)
            colors = list(sampled.getdata())
            luminances = [0.299 * red + 0.587 * green + 0.114 * blue
                          for red, green, blue in colors]
            low, high = min(luminances), max(luminances)
            span = high - low

            lake = Image.new("L", (crop[2] - crop[0], crop[3] - crop[1]), 0)
            ImageDraw.Draw(lake).polygon(polygon, fill=255)
            coverage = water_coverage(lake, columns, rows, args.cell)
            cells = bytearray(columns * rows * 5)
            water_cells = 0
            foreground = foreground_colors(cropped, columns, rows, args.cell)
            for index, (luminance, covered, ink) in enumerate(zip(luminances, coverage, foreground)):
                normalized = (luminance - low) / span if span > 0 else 0.5
                tone = math.floor(normalized * 255 + 0.5)
                # No glyph quantization here: interpolate all 256 tones natively.
                water = int(covered)
                water_cells += water
                red, green, blue = ink
                offset = index * 5
                cells[offset:offset + 5] = bytes((tone, red, green, blue, water))
            output.write(struct.pack(">IIIfff", columns, rows, rows, *anchor))
            output.write(cells)
            total_cells += columns * rows
            print(f"{name}: crop={crop}, cells={columns}x{rows} "
                  f"({columns * rows:,}), water={water_cells:,}, pixelHeight={rows}, "
                  f"light=({anchor[0]:.9f}, {anchor[1]:.9f}, {anchor[2]:.9f})")
    size = args.output.stat().st_size
    print(f"source={args.input} ({image.width}x{image.height}), cell={args.cell}x{args.cell}, "
          f"contrast={args.contrast:g}, brightness={args.brightness:g}")
    print(f"Wrote {args.output}: {total_cells:,} cells, {size:,} bytes")


if __name__ == "__main__":
    build(arguments())
