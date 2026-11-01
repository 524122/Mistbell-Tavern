# -*- coding: utf-8 -*-
# 组装 P2 探测角色卡 ProbeBot2：
# - first_mes 带 ```html 围栏块，内联 <script> 在文档解析期执行，打点 typeof TavernHelper/__tavernEmit
# - tavern_helper.scripts 带 v3 探测脚本（双通道订阅 + ENDED-PEEK + 守卫按钮）
import json, io

with io.open(r"C:\Users\20109\Desktop\chat\mistbell-tavern-android-main\.e2e\probe-script-v3.js", "r", encoding="utf-8") as f:
    probe_js = f.read()

PARSE_TIME_BLOCK = (
    "```html\n"
    "<!DOCTYPE html>\n<html>\n<head><meta charset=\"utf-8\"></head>\n<body>\n"
    "<p id=\"p2line\">P2 解析期探测</p>\n"
    "<script>\n"
    "window.TavernBridge.log('P2 parse-time: TavernHelper=' + typeof window.TavernHelper +\n"
    "  ' __tavernEmit=' + typeof window.__tavernEmit + ' bridge=' + typeof window.TavernBridge);\n"
    "document.getElementById('p2line').textContent =\n"
    "  'parse-time TavernHelper=' + typeof window.TavernHelper + ' __tavernEmit=' + typeof window.__tavernEmit;\n"
    "</script>\n"
    "</body>\n</html>\n"
    "```\n"
)

DESC = "P2 探测卡：解析期门面可见性 + 内置包启停 + 提供者互斥 + regenerate 事件配对。"

card = {
    "spec": "chara_card_v2",
    "spec_version": "2.0",
    "name": "ProbeBot2",
    "description": DESC,
    "personality": "严谨的测试助手",
    "scenario": "P2 端到端测试",
    "first_mes": "P2 探测卡已加载。\n" + PARSE_TIME_BLOCK,
    "mes_example": "",
    "data": {
        "name": "ProbeBot2",
        "description": DESC,
        "personality": "严谨的测试助手",
        "scenario": "P2 端到端测试",
        "first_mes": "P2 探测卡已加载。\n" + PARSE_TIME_BLOCK,
        "mes_example": "",
        "extensions": {
            "tavern_helper": {
                "scripts": [
                    {"name": "probe3", "enabled": True, "content": probe_js},
                ]
            }
        },
    },
}

out = r"C:\Users\20109\Desktop\chat\mistbell-tavern-android-main\.e2e\probe-card2.json"
with io.open(out, "w", encoding="utf-8") as f:
    json.dump(card, f, ensure_ascii=False, indent=1)
print("written", out)
