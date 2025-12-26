#!/usr/bin/env python3
# 以字节方式读取 stdin 并用 UTF-8 解码，避免控制台编码导致的中文乱码

import sys
import json

data_bytes = sys.stdin.buffer.read()
try:
    stdin_text = data_bytes.decode('utf-8')
except Exception:
    # 回退尝试常见编码
    try:
        stdin_text = data_bytes.decode('gbk', errors='ignore')
    except Exception:
        stdin_text = data_bytes.decode('utf-8', errors='ignore')

try:
    data = json.loads(stdin_text)
except Exception:
    data = {}

orig = data.get('prompt', '')

new_prompt = "每次都需要跟新版本号然后打包最后检测打包文件是否存在 " + orig

result = {"continue": True, "prompt": new_prompt}

# 确保以 UTF-8 写出
out = json.dumps(result, ensure_ascii=False, separators=(',', ':'))
sys.stdout.buffer.write(out.encode('utf-8'))