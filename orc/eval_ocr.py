"""
PaddleOCR 准确率评测脚本
========================
单张或批量评测档案材料图片的 OCR 识别效果。

用法:
    ..\paddle2_env\Scripts\python.exe eval_ocr.py <图片路径或目录>

示例:
    python eval_ocr.py 干部履历表.jpg
    python eval_ocr.py C:\档案图片\
"""
import base64
import json
import os
import sys
import time
from pathlib import Path

import requests

OCR_URL = "http://localhost:8866/ocr"


def ocr_one(image_path: str) -> dict:
    """识别单张图片"""
    with open(image_path, "rb") as f:
        b64 = base64.b64encode(f.read()).decode()

    t0 = time.time()
    resp = requests.post(OCR_URL, json={"image": b64}, timeout=120)
    elapsed = time.time() - t0

    if resp.status_code != 200:
        return {"success": False, "error": f"HTTP {resp.status_code}"}

    result = resp.json()
    result["_wall_seconds"] = round(elapsed, 2)
    return result


def print_result(filepath: str, result: dict):
    """打印识别结果"""
    name = os.path.basename(filepath)
    print(f"\n{'─'*64}")
    print(f"📄  {name}")
    print(f"{'─'*64}")

    if not result.get("success"):
        print(f"  ❌ 失败: {result.get('error')}")
        return

    full_text = result.get("full_text", "")
    blocks = result.get("blocks", [])
    server_time = result.get("elapsed_seconds", "?")
    wall_time = result.get("_wall_seconds", "?")

    # 识别文本
    for line in full_text.split("\n"):
        print(f"  │  {line}")

    print(f"  ├── 文字块: {len(blocks)}  字符: {len(full_text)}")
    print(f"  ├── 服务端: {server_time}s  总耗时: {wall_time}s")

    # 低置信度块
    low = [(b["text"], b["confidence"]) for b in blocks if b["confidence"] < 0.8]
    if low:
        print(f"  ├── ⚠️  低置信度 ({len(low)} 块):")
        for text, conf in low:
            print(f"  │    [{conf:.1%}]  {text}")

    # 平均置信度
    if blocks:
        avg_conf = sum(b["confidence"] for b in blocks) / len(blocks)
        print(f"  └── 平均置信度: {avg_conf:.1%}")


def main():
    target = sys.argv[1] if len(sys.argv) > 1 else None
    if not target:
        print(__doc__)
        return

    # 收集图片
    images = []
    p = Path(target)
    if p.is_dir():
        for ext in ("*.jpg", "*.jpeg", "*.png", "*.bmp"):
            images.extend(sorted(p.glob(ext)))
    elif p.is_file():
        images.append(p)
    else:
        print(f"❌ 路径不存在: {target}")
        return

    if not images:
        print("❌ 未找到图片文件")
        return

    print(f"\n{'='*64}")
    print(f"  PaddleOCR 准确率评测 — {len(images)} 张图片")
    print(f"{'='*64}")

    total_chars = 0
    total_blocks = 0
    total_time = 0

    for img in images:
        result = ocr_one(str(img))
        print_result(str(img), result)

        if result.get("success"):
            total_chars += len(result.get("full_text", ""))
            total_blocks += len(result.get("blocks", []))
            total_time += result.get("elapsed_seconds", 0)

    print(f"\n{'='*64}")
    print(f"  总计: {len(images)} 张 | 文字块: {total_blocks} | 总字符: {total_chars}")
    if total_time > 0:
        print(f"  服务端总耗时: {total_time:.1f}s  平均: {total_time/len(images):.1f}s/张")
    print(f"{'='*64}\n")


if __name__ == "__main__":
    main()
