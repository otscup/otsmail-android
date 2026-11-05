"""把 docs/screenshots 下的单图拼成两张 2×2 预览图，供 README 引用。

规格（与单图的裁切参数一致，tile 由 round_screenshots.py 产出）：
- tile 宽 540（单图 1080×2136 等比缩到 540×1068）
- tile 间距 28px，四周外留白 24px，白底 #FFFFFF，无投影/边框/描边
- 单图本身已带 48px 透明圆角，这里不重裁；圆角处先合到白底再缩放，
  避免 RGBA 直接缩放出灰边
- 输出 preview-a.png / preview-b.png（带 alpha 的 PNG，alpha 全不透明）
- 每张目标 ≤400KB，超过时依次降级：8-bit 调色板 → 白底 JPEG q85

用法：python tools/make_preview_grids.py [目录]
"""
import os
import re
import sys

from PIL import Image

TILE_W = 540
GAP = 28
PAD = 24
BG = (255, 255, 255)
MAX_KB = 400

GROUPS = {
    "preview-a.png": ["00-login.png", "01-inbox.png", "02-drawer.png", "03-detail.png"],
    "preview-b.png": ["04-compose.png", "05-profile.png", "06-system.png", "07-inbox-dark.png"],
}


def tile(path: str) -> Image.Image:
    im = Image.open(path).convert("RGBA")
    w, h = im.size
    # 先合到白底：圆角处的半透明像素直接取白，缩放后不会出现灰色描边
    flat = Image.new("RGBA", (w, h), BG + (255,))
    flat.alpha_composite(im)
    return flat.convert("RGB").resize((TILE_W, TILE_W * h // w), Image.LANCZOS)


def compose(tiles: list[Image.Image], size: tuple[int, int]) -> Image.Image:
    tw, th = tiles[0].size
    canvas = Image.new("RGBA", size, BG + (255,))
    for i, t in enumerate(tiles):
        canvas.paste(t, (PAD + (tw + GAP) * (i % 2), PAD + (th + GAP) * (i // 2)))
    return canvas


def build(folder: str, out_name: str, names: list[str]) -> None:
    tiles = [tile(os.path.join(folder, n)) for n in names]
    tw, th = tiles[0].size
    for t in tiles:
        if t.size != (tw, th):
            raise SystemExit(f"tile 尺寸不一致：{t.size} vs {(tw, th)}")

    width = PAD * 2 + tw * 2 + GAP
    height = PAD * 2 + th * 2 + GAP
    out = os.path.join(folder, out_name)
    canvas = compose(tiles, (width, height))
    canvas.save(out, "PNG", optimize=True)
    size = os.path.getsize(out) // 1024
    if size <= MAX_KB:
        print(f"{out_name}: {width}×{height} {size}KB PNG")
        return

    # 降级 1：tile 各自降到 8-bit 调色板再贴回纯白画布。
    # 只量化 tile、不量化画布，外留白和 tile 圆角外仍是精确的 #FFFFFF。
    quantized = [
        t.quantize(colors=256, method=Image.FASTOCTREE).convert("RGB") for t in tiles
    ]
    canvas = compose(quantized, (width, height))
    canvas.save(out, "PNG", optimize=True)
    size = os.path.getsize(out) // 1024
    if size <= MAX_KB:
        print(f"{out_name}: {width}×{height} {size}KB PNG(8bit tile)")
        return

    # 降级 2：白底 JPEG（8-bit 仍超 400KB 时才会走到这里）
    jpg = out[:-4] + ".jpg"
    canvas.convert("RGB").save(jpg, "JPEG", quality=85, optimize=True, subsampling=0)
    os.remove(out)
    print(f"{out_name[:-4]}.jpg: {width}×{height} {os.path.getsize(jpg) // 1024}KB JPEG")


def main() -> None:
    folder = sys.argv[1] if len(sys.argv) > 1 else os.path.join(
        os.path.dirname(os.path.dirname(os.path.abspath(__file__))), "docs", "screenshots"
    )
    for out_name, names in GROUPS.items():
        missing = [n for n in names if not os.path.exists(os.path.join(folder, n))]
        if missing:
            raise SystemExit(f"缺少单图：{', '.join(missing)}")
        build(folder, out_name, names)
    # 拼好后顺手检查 README 里引用的截图是否都存在（防止改名后留死链）
    readme = os.path.join(folder, "..", "..", "README.md")
    if os.path.exists(readme):
        with open(readme, encoding="utf-8") as f:
            text = f.read()
        root = os.path.dirname(os.path.abspath(readme))
        for ref in sorted(set(re.findall(r"docs/screenshots/[A-Za-z0-9._-]+", text))):
            if not os.path.exists(os.path.join(root, ref)):
                print(f"README 死链：{ref}")


if __name__ == "__main__":
    main()
