"""
PaddleOCR API 服务（v2 稳定版）
===============================
PaddleOCR 2.8.1 + PaddlePaddle 2.6.2，PP-OCRv4 模型，CPU 运行。

启动: .\paddle2_env\Scripts\python.exe paddle_server.py

API:
    POST /ocr         单图 base64
    POST /ocr-batch   批量 URL
    GET  /health      健康检查
"""

import base64
import io
import time
import traceback
import numpy as np
import requests as http_requests
from PIL import Image
from flask import Flask, request, jsonify

# ---- PaddleOCR 初始化 ----
print("[init] 正在加载 PaddleOCR PP-OCRv4 模型...")
_start_ts = time.time()

from paddleocr import PaddleOCR

ocr = PaddleOCR(
    use_angle_cls=True,
    lang="ch",
    use_gpu=False,
    show_log=False,
)

_elapsed = time.time() - _start_ts
print(f"[init] 模型加载完成，耗时 {_elapsed:.1f} 秒")


# ---- Flask ----
app = Flask(__name__)


def _do_ocr(pil_image):
    """内部：PIL Image → OCR"""
    max_dim = 3000
    if max(pil_image.width, pil_image.height) > max_dim:
        ratio = max_dim / max(pil_image.width, pil_image.height)
        pil_image = pil_image.resize(
            (int(pil_image.width * ratio), int(pil_image.height * ratio)),
            Image.LANCZOS,
        )

    img_array = np.array(pil_image)
    _start = time.time()
    result = ocr.ocr(img_array, cls=True)
    elapsed = round(time.time() - _start, 2)

    blocks = []
    lines = []

    if result and result[0]:
        for line in result[0]:
            box = line[0]
            text = line[1][0]
            conf = line[1][1]
            blocks.append({
                "text": text,
                "confidence": round(float(conf), 4),
                "box": [[int(p[0]), int(p[1])] for p in box],
            })
            lines.append(text)

    full_text = "\n".join(lines)
    print(f"[ocr] {len(blocks)} 块, 耗时 {elapsed}s")
    return full_text, blocks, elapsed


def _download_image(url, docker_host="http://localhost"):
    """处理 Dify 内部路径，下载图片"""
    if url.startswith("/files/") or url.startswith("http://api"):
        if "/files/" in url:
            path = url[url.index("/files/"):]
            url = f"{docker_host}{path}"
        elif url.startswith("http://api"):
            url = url.replace("http://api", docker_host)

    resp = http_requests.get(url, timeout=30)
    if resp.status_code != 200:
        raise Exception(f"下载失败 HTTP {resp.status_code}")
    return Image.open(io.BytesIO(resp.content)).convert("RGB")


# ---- 路由 ----

@app.route("/health", methods=["GET"])
def health():
    return jsonify({
        "status": "ok",
        "engine": "PaddleOCR",
        "model": "PP-OCRv4",
        "version": "2.8.1",
        "device": "CPU",
    })


@app.route("/ocr", methods=["POST"])
def ocr_single():
    """单图 base64 识别"""
    try:
        data = request.get_json(silent=True)
        if not data:
            return jsonify({"success": False, "error": "请求体必须为 JSON"}), 400

        image_b64 = data.get("image", "")
        if not image_b64:
            return jsonify({"success": False, "error": "缺少 image 字段"}), 400

        if "," in image_b64 and image_b64.startswith("data:"):
            image_b64 = image_b64.split(",", 1)[1]

        image_bytes = base64.b64decode(image_b64)
        image = Image.open(io.BytesIO(image_bytes)).convert("RGB")

        full_text, blocks, elapsed = _do_ocr(image)

        return jsonify({
            "success": True, "full_text": full_text,
            "blocks": blocks, "total_blocks": len(blocks),
            "elapsed_seconds": elapsed,
        })

    except Exception as e:
        traceback.print_exc()
        return jsonify({"success": False, "error": str(e)}), 500


@app.route("/ocr-batch", methods=["POST"])
def ocr_batch():
    """批量 URL 识别"""
    try:
        raw_body = request.get_data(as_text=True)
        print(f"[ocr-batch] 收到请求: {raw_body[:500]}")

        data = request.get_json(silent=True)
        if not data:
            print(f"[ocr-batch] JSON解析失败: {raw_body[:500]}")
            return jsonify({"success": False, "error": "请求体必须为 JSON", "raw": raw_body[:200]}), 400

        images = data.get("images", [])
        docker_host = data.get("docker_host", "http://localhost")

        print(f"[ocr-batch] images 类型: {type(images).__name__}, 数量: {len(images) if isinstance(images, list) else 'N/A'}")

        if not images:
            return jsonify({"success": False, "error": "缺少 images 数组", "body_preview": raw_body[:300]}), 400

        results = []
        _total_start = time.time()

        for img in images:
            img_id = img.get("id", "")
            img_label = img.get("label", "")
            img_url = img.get("url", "")
            item_start = time.time()

            try:
                print(f"[batch] [{img_id}] {img_label}")
                image = _download_image(img_url, docker_host)
                full_text, blocks, elapsed = _do_ocr(image)

                results.append({
                    "id": img_id, "label": img_label,
                    "success": True, "full_text": full_text,
                    "blocks": blocks, "total_blocks": len(blocks),
                    "elapsed_seconds": elapsed,
                })

            except Exception as e:
                results.append({
                    "id": img_id, "label": img_label,
                    "success": False, "error": str(e),
                })

        total_elapsed = round(time.time() - _total_start, 2)
        print(f"[batch] 完成: {len(results)}份, 总耗时 {total_elapsed}s")

        return jsonify({
            "success": True, "results": results,
            "total": len(results), "elapsed_seconds": total_elapsed,
        })

    except Exception as e:
        traceback.print_exc()
        return jsonify({"success": False, "error": str(e)}), 500


# ----
if __name__ == "__main__":
    print("[init] 服务地址: http://0.0.0.0:8866")
    print("[init] 健康检查: http://localhost:8866/health")
    print("-" * 50)
    app.run(host="0.0.0.0", port=8866, debug=False, threaded=False)
