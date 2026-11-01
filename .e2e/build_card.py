# -*- coding: utf-8 -*-
# 组装探测角色卡 JSON（v2 卡，data.extensions.tavern_helper.scripts 携带探测脚本）
# 用法：python build_card.py（修改 probe-script.js 后重跑，再经应用角色导入 UI 导入 probe-card.json）
import json, io, os

here = os.path.dirname(os.path.abspath(__file__))
with io.open(os.path.join(here, "probe-script.js"), "r", encoding="utf-8") as f:
    probe_js = f.read()

card = {
    "spec": "chara_card_v2",
    "spec_version": "2.0",
    "name": "ProbeBot",
    "description": "E2E 探测卡：验证酒馆助手桥消息写 API、生成控制、事件推送与宏替换。",
    "personality": "严谨的测试助手",
    "scenario": "自动化端到端测试",
    "first_mes": "探测卡已加载。请发送一条消息开始测试。",
    "mes_example": "",
    "data": {
        "name": "ProbeBot",
        "description": "E2E 探测卡：验证酒馆助手桥消息写 API、生成控制、事件推送与宏替换。",
        "personality": "严谨的测试助手",
        "scenario": "自动化端到端测试",
        "first_mes": "探测卡已加载。请发送一条消息开始测试。",
        "mes_example": "",
        "extensions": {
            "tavern_helper": {
                "scripts": [
                    {
                        "name": "probe",
                        "enabled": True,
                        "content": probe_js,
                    }
                ]
            }
        },
    },
}

out = os.path.join(here, "probe-card.json")
with io.open(out, "w", encoding="utf-8") as f:
    json.dump(card, f, ensure_ascii=False, indent=1)
print("written", out)
