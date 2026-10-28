"""Build labelled visual evidence from synthetic screenshots; never read real vault files."""
from pathlib import Path
from PIL import Image, ImageOps, ImageDraw

root = Path('app/build/reports/visual-walkthrough')
root.mkdir(parents=True, exist_ok=True)
for config in ('portrait', 'large', 'landscape'):
    captures = sorted(root.glob(f'{config}-[0-9][0-9]-*.png'))
    for offset in range(0, len(captures), 4):
        group = captures[offset:offset + 4]
        cell_width, cell_height = (480, 300) if config == 'landscape' else (320, 700)
        sheet = Image.new('RGB', (cell_width * 2, cell_height * 2), '#dddddd')
        draw = ImageDraw.Draw(sheet)
        for i, path in enumerate(group):
            x, y = (i % 2) * cell_width, (i // 2) * cell_height
            shot = Image.open(path).convert('RGB')
            shot.thumbnail((cell_width - 8, cell_height - 30))
            sheet.paste(shot, (x + (cell_width - shot.width) // 2, y + 26))
            draw.text((x + 4, y + 5), path.stem, fill='black')
        sheet.save(root / f'review-{config}-{offset // 4 + 1:02}.png')
        sheet.save(root / f'review-{config}-{offset // 4 + 1:02}.jpg', quality=85)

# An offline review entry point inside the downloaded verification artifact.
import html
sections = []
for config in ('portrait', 'large', 'landscape'):
    cards = []
    for path in sorted(root.glob(f'{config}-[0-9][0-9]-*.png')):
        name = html.escape(path.name, quote=True)
        label = html.escape(path.stem)
        bounds = html.escape(path.with_suffix('.txt').name, quote=True)
        cards.append(f'<figure><a href="{name}"><img loading="lazy" src="{name}" alt="{label}"></a><figcaption>{label} · <a href="{bounds}">layout tree</a></figcaption></figure>')
    video = f'{config}-tour.mp4'
    sections.append(f'<section><h2>{config}</h2><video controls preload="metadata" src="{video}"></video><p><a href="{video}">Download recording</a> · <a href="{config}-failures.txt">Capture failures</a></p><div class="grid">{"".join(cards)}</div></section>')
(root / 'index.html').write_text('''<!doctype html><meta charset="utf-8"><title>SecureNote recorded visual audit</title>
<style>body{font:16px system-ui;margin:24px;background:#f5f1e9;color:#272b28}h1{font-size:28px}video{max-width:100%;max-height:540px;background:#222}.grid{display:grid;grid-template-columns:repeat(auto-fit,minmax(280px,1fr));gap:20px}figure{margin:0;padding:12px;border:1px solid #ccc;background:white}img{width:100%;max-height:700px;object-fit:contain}figcaption{overflow-wrap:anywhere;padding-top:8px}section{margin-top:40px}a{color:#8e3e30}</style>
<h1>Recorded visual audit</h1><p>Synthetic encrypted content only. Normal app screenshot protection remains enabled. The protected video picture may appear black; player controls are captured. Open individual images at full size to assess spacing; layout trees provide node bounds. Capture errors must be resolved before treating coverage as complete.</p>
''' + ''.join(sections), encoding='utf-8')

import json
import subprocess
metadata = {}
for config in ('portrait', 'large', 'landscape'):
    video = root / f'{config}-tour.mp4'
    if not video.exists():
        raise RuntimeError(f'Missing recording: {video}')
    probe = json.loads(subprocess.check_output([
        'ffprobe', '-v', 'error', '-show_entries', 'format=duration:stream=width,height',
        '-of', 'json', str(video),
    ], text=True))
    metadata[config] = probe
    index = root / f'{config}-index.txt'
    last_time = float(index.read_text().strip().splitlines()[-1].split('\t')[-1])
    duration = float(probe['format']['duration'])
    if duration + 3 < last_time:
        raise RuntimeError(f'{config} recording ends at {duration}s but final capture is at {last_time}s')
    print(f'WALKTHROUGH_VIDEO {config}: {duration:.1f}s, final capture {last_time:.1f}s')
(root / 'recording-metadata.json').write_text(json.dumps(metadata, indent=2))
