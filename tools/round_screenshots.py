"""把真机截图裁掉系统状态栏/导航栏并加透明圆角，输出到 docs/screenshots/。

固定参数（与 README 的截图一致）：
- 原图 1080×2280（Samsung Galaxy S10e，状态栏 117px，手势导航胶囊在 y≈2254–2266）
- 上边界 y=117：状态栏下沿，App 顶栏从这里开始
- 下边界 y=2253：手势导航胶囊（y=2255–2265）之上，App 底部工具条/FAB 完整保留
- 圆角半径 48px（短边 1080 的 4.4%），4× 超采样遮罩，导出带 alpha 的 PNG

用法：python tools/round_screenshots.py [目录]
"""
import glob
import os
import sys

from PIL import Image, ImageDraw

TOP = 117
BOTTOM = 2253
RADIUS = 48
SUPERSAMPLE = 4


def process(path: str) -> None:
    im = Image.open(path).convert("RGB")
    w, h = im.size
    if (w, h) != (1080, 2280):
        raise SystemExit(f"{path}: 预期 1080×2280，实际 {w}×{h}")

    cropped = im.crop((0, TOP, w, BOTTOM))
    cw, ch = cropped.size

    # 4× 超采样画圆角矩形再缩回，边缘比直接画 1× 平滑
    mask = Image.new("L", (cw * SUPERSAMPLE, ch * SUPERSAMPLE), 0)
    ImageDraw.Draw(mask).rounded_rectangle(
        (0, 0, cw * SUPERSAMPLE - 1, ch * SUPERSAMPLE - 1),
        radius=RADIUS * SUPERSAMPLE,
        fill=255,
    )
    mask = mask.resize((cw, ch), Image.LANCZOS)

    out = cropped.convert("RGBA")
    out.putalpha(mask)
    out.save(path, "PNG", optimize=True)
    print(f"{os.path.basename(path)}: {cw}×{ch} {os.path.getsize(path) // 1024}KB")


def main() -> None:
    folder = sys.argv[1] if len(sys.argv) > 1 else os.path.join(
        os.path.dirname(os.path.dirname(os.path.abspath(__file__))), "docs", "screenshots"
    )
    for path in sorted(glob.glob(os.path.join(folder, "*.png"))):
        process(path)


if __name__ == "__main__":
    main()
