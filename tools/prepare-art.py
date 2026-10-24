"""Prepare right-sized copies of the repository artwork, with no invented assets."""
from pathlib import Path
import json
import re
import xml.etree.ElementTree as ET
from PIL import Image
root = Path(__file__).resolve().parents[1]
out = root / 'app/src/main/assets/art'
out.mkdir(parents=True, exist_ok=True)
sizes = {'blackrock': 1920, 'rocks': 1412, 'clare': 1920, 'wave-crest': 723, 'hooker': 64,
         'hot-chocolate': 56, 'umbrella': 180, 'pinwheel-handle': 50, 'pinwheel-spinner': 50,
         'sea-texture': 612, 'watercolor-texture': 960}
for name, width in sizes.items():
    source = root / 'artwork' / (name + ('.jpg' if 'texture' in name else '.png'))
    im = Image.open(source)
    im.thumbnail((width, round(im.height * width / im.width)), Image.Resampling.LANCZOS)
    im.save(out / f'{name}.webp', lossless=True, method=6)
def shaded_cloud(source, stops):
    """Bake the CSS cloud colour ramp into its alpha mask, once offline."""
    source = source.convert('RGBA')
    gradient = Image.new('RGBA', source.size)
    rows = []
    for y in range(source.height):
        t = y / max(1, source.height - 1)
        left, right = stops[0], stops[-1]
        for low, high in zip(stops, stops[1:]):
            if low[0] <= t <= high[0]:
                left, right = low, high
                break
        f = max(0, min(1, (t - left[0]) / (right[0] - left[0])))
        rgb = tuple(round(a + (b - a) * f) for a, b in zip(left[1], right[1]))
        rows.extend([(*rgb, 255)] * source.width)
    gradient.putdata(rows)
    gradient.putalpha(source.getchannel('A'))
    return gradient

# Keep the web palette's hold points: neutral cloud tops, coloured undersides.
GREY_CLOUD_STOPS = [(0, (194, 194, 194)), (.5, (194, 194, 194)),
                    (.75, (223, 223, 223)), (1, (238, 238, 238))]
SUNSET_CLOUD_STOPS = [(0, (195, 195, 195)), (.7, (195, 195, 195)),
                      (1, (255, 93, 143))]

for n in range(1, 10):
    im = Image.open(root / f'artwork/clouds/{n}.png').convert('RGBA')
    im.thumbnail((500, 500), Image.Resampling.LANCZOS)
    im.save(out / f'cloud-{n}.webp', lossless=True, method=6)
    shaded_cloud(im, GREY_CLOUD_STOPS).save(out / f'cloud-grey-{n}.webp', lossless=True, method=6)
    shaded_cloud(im, SUNSET_CLOUD_STOPS).save(out / f'cloud-sunset-{n}.webp', lossless=True, method=6)
svg = ET.parse(root / 'artwork/front-wave.svg')
paths = [{'path': p.attrib['d'], 'alpha': float(p.attrib.get('opacity', 1))}
         for p in svg.iter() if p.tag.endswith('}path')]
(out / 'wave-paths.json').write_text(json.dumps(paths, separators=(',', ':')))
foam = re.split(r'c4\.5,8\.984', paths[-1]['path'])[0]
assert len(foam) < len(paths[-1]['path'])
(out / 'wave-foam.json').write_text(json.dumps({'path': foam}))
print(f'Prepared {len(sizes) + 28} artwork files in {out}')
