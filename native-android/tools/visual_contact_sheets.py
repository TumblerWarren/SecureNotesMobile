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
