#!/usr/bin/env python3
"""Export the existing Bot whale artwork for Android; requires Pillow, not an APK dependency."""
import json
import re
from pathlib import Path
from PIL import Image

root = Path(__file__).resolve().parents[1]
source = root / "android/app/src/main/assets/www"
crop = json.loads(re.search(r"FISH_CROPS=Object\.freeze\((\{.*?\})\)",
                           (source / "js/bot-fish.js").read_text()).group(1))["whale"]
atlas = Image.open(source / "img/fish-atlas.png").convert("RGB")
# The same sRGB ink filter and 418px cell clip used by fishSvg().
alpha = Image.new("L", atlas.size)
alpha.putdata([round(max(0, min(1, 2 - 3 * (r * .3333 + g * .3333 + b * .3334) / 255)) * 255)
               if x < 418 and y < 418 else 0
               for y in range(atlas.height) for x in range(atlas.width)
               for r, g, b in [atlas.getpixel((x, y))]])
x, y, width, height = crop
ink = Image.new("RGBA", atlas.size, "white")
ink.putalpha(alpha)
ink.transform((96, 96), Image.Transform.EXTENT,
              (x, y, x + width, y + height), Image.Resampling.BICUBIC).save(
                  root / "android/app/src/main/res/drawable-nodpi/notification_whale.png", optimize=True)
# Adaptive icons expose the central 72 of 108 units. Match the Bot's 74% glyph size
# inside that visible area, rather than letting the launcher crop enlarge it.
whale = ink.transform((216, 216), Image.Transform.EXTENT,
                      (x, y, x + width, y + height), Image.Resampling.BICUBIC)
foreground = Image.new("RGBA", (432, 432))
foreground.paste(whale, (108, 108))
foreground.save(root / "android/app/src/main/res/drawable-nodpi/launcher_whale.png", optimize=True)
