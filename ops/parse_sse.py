"""解析客服 SSE 输出文件，打印最终回答与工具调用（冒烟测试辅助脚本）。"""
import json
import re
import sys

path = sys.argv[1]
raw = open(path, encoding="utf-8").read()

# 按帧切分（event: xxx \n data: {...} \n\n）
frames = re.split(r"\n\n+", raw)
answer_parts, tools = [], []
for frame in frames:
    m_ev = re.search(r"^event:(\w+)", frame, re.M)
    m_data = re.search(r"^data:(.*)", frame, re.M)
    if not m_ev or not m_data:
        continue
    ev = m_ev.group(1)
    try:
        data = json.loads(m_data.group(1).strip())
    except json.JSONDecodeError:
        continue
    if ev == "delta":
        answer_parts.append(data.get("text", ""))
    elif ev == "tool":
        tools.append(f"{data.get('name')} ok={data.get('ok')}")
    elif ev == "error":
        print("ERROR:", data)

print("工具调用:", tools)
print("最终回答:", "".join(answer_parts)[:400])
