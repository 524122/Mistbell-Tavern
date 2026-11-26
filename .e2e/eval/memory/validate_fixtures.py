#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""validate_fixtures.py —— 校验「会话记忆」评测的骨架与拼装产物。

用法:
    python -I validate_fixtures.py                 # 默认同时校验骨架与拼装产物
    python -I validate_fixtures.py --spines-only   # 只校验骨架
    python -I validate_fixtures.py --fixtures-only # 只校验夹具 + manifest
    python -I validate_fixtures.py --spines <目录> --fixtures <目录>

默认路径按脚本自身位置推导:
    骨架:    <仓库根>/.e2e/eval/memory/spines/*.json
    夹具:    <仓库根>/app/src/sharedTest/resources/eval/memory/sessions/*.json
    manifest: <仓库根>/app/src/sharedTest/resources/eval/memory/manifest.json

任何一项不满足都以非零退出码结束，并把每条违规按「文件 :: 位置 :: 原因」
逐行列出。探针「换说法」的机器判定与 spine-schema-final.md 保持同一套算法：
去人名 → 按固定顺序删豁免词 → 去掉非汉字/数字字符 → 连续两字符集合不相交。
"""

from __future__ import annotations

import argparse
import json
import os
import re
import sys

SCHEMA_VERSION = 1
TOTAL_FLOORS = 300
PROBE_TURNS = [13, 25, 38, 50, 63, 75, 88, 100, 113, 125, 138]
PROBE_FLOORS = [2 * t for t in PROBE_TURNS] + [300]
ALLOWED_SESSION_IDS = {"s%02d" % i for i in range(1, 13)}

EXPECTED_FACT_COUNT = 30
EXPECTED_FACT_IDS = ["f%02d" % i for i in range(1, 31)]
EXPECTED_UPDATE_COUNT = 10
MIN_RESTATE_COUNT = 8
MIN_IMMUTABLE_COUNT = 6
MIN_FILLER_COUNT = 90
EXPECTED_DEFLECTIONS = 12
EXPECTED_ACKS = 16
EXPECTED_LEADINS = 10

MEMORY_TYPES = {"identity", "preference", "relationship", "goal",
                "health", "item", "location", "event", "fact"}
IMMUTABLE_TYPES = {"event", "identity", "fact"}
MAX_HANZI = 40
MIN_FLOOR_GAP_FOR_VERSION = 6   # 探针指向的版本须在探针楼之前至少 6 楼

# 豁免词（顺序与 spine-schema-final.md 完全一致，逐个按子串删除）
EXEMPT_WORDS = [
    "的", "了", "我", "你", "他", "她", "它", "是", "在", "有", "和", "就",
    "都", "也", "还", "吗", "呢", "啊", "吧", "这", "那", "什么", "怎么",
    "哪", "一个", "没有", "不", "人", "说", "会", "把", "被", "给", "到",
    "过", "着", "来", "去", "上", "下", "里", "后", "前", "时候", "现在",
    "之前", "以前", "记得", "知道", "告诉",
]


def repo_root():
    here = os.path.dirname(os.path.abspath(__file__))
    return os.path.dirname(os.path.dirname(os.path.dirname(here)))


def default_spines_dir():
    return os.path.join(os.path.dirname(os.path.abspath(__file__)), "spines")


def default_fixtures_dir():
    return os.path.join(repo_root(), "app", "src", "sharedTest", "resources",
                        "eval", "memory", "sessions")


def default_manifest_path():
    return os.path.join(os.path.dirname(default_fixtures_dir()), "manifest.json")


def load_json(path):
    with open(path, "r", encoding="utf-8-sig") as fh:
        return json.load(fh)


def hanzi_len(text):
    return sum(1 for ch in text if "\u4e00" <= ch <= "\u9fff")


def normalize(text, names):
    for name in names:
        if name:
            text = text.replace(name, "")
    for word in EXEMPT_WORDS:
        text = text.replace(word, "")
    return "".join(ch for ch in text
                   if "\u4e00" <= ch <= "\u9fff" or ch.isdigit())


def bigram_set(text):
    return {text[i:i + 2] for i in range(len(text) - 1)}


def rephrase_ok(probe, other, names):
    """探针换说法判定：两边规范化后连续两字符集合不得相交。"""
    return not (bigram_set(normalize(probe, names))
                & bigram_set(normalize(other, names)))


class Violations:
    def __init__(self):
        self.items = []

    def add(self, path, loc, msg):
        self.items.append((path, loc, msg))

    def __len__(self):
        return len(self.items)


# ---------------------------------------------------------------------------
# 骨架校验
# ---------------------------------------------------------------------------

def check_spine(path, v):
    stem = os.path.splitext(os.path.basename(path))[0]
    try:
        data = load_json(path)
    except (OSError, ValueError) as exc:
        v.add(path, "$", f"JSON 无法解析：{exc}")
        return
    if not isinstance(data, dict):
        v.add(path, "$", "顶层不是 JSON 对象")
        return

    names = []
    for key in ("character", "user"):
        obj = data.get(key)
        if isinstance(obj, dict) and isinstance(obj.get("name"), str) and obj["name"].strip():
            names.append(obj["name"])
        else:
            v.add(path, f"$.{key}", "应为含非空 name 字符串的对象")

    # 1) 顶层字段齐全、类型正确
    for field in ("id", "title", "setting", "greeting"):
        if not isinstance(data.get(field), str):
            v.add(path, f"$.{field}", "缺少或类型错误：应为字符串")
    for field in ("leadIns", "deflections", "acknowledgements"):
        lst = data.get(field)
        if not isinstance(lst, list) or not all(isinstance(x, str) for x in lst):
            v.add(path, f"$.{field}", "缺少或类型错误：应为字符串数组")
    filler = data.get("fillerTurns")
    if not isinstance(filler, list) or not all(
            isinstance(x, dict) and isinstance(x.get("user"), str)
            and isinstance(x.get("assistant"), str) for x in filler):
        v.add(path, "$.fillerTurns", "缺少或类型错误：应为 {user, assistant} 对象数组")

    facts = data.get("facts")
    if not isinstance(facts, list):
        v.add(path, "$.facts", "缺少或类型错误：应为数组")
        return

    # 2) id 与数量
    if data.get("id") != stem:
        v.add(path, "$.id", f"id（{data.get('id')!r}）与文件名（{stem}）不一致")
    if not stem.startswith("_") and data.get("id") not in ALLOWED_SESSION_IDS:
        v.add(path, "$.id", f"id 应为 s01–s12，实际 {data.get('id')!r}")
    if len(facts) != EXPECTED_FACT_COUNT:
        v.add(path, "$.facts",
              f"facts 需要正好 {EXPECTED_FACT_COUNT} 条，实际 {len(facts)} 条")
    ids = [f.get("id") for f in facts if isinstance(f, dict)]
    if ids != EXPECTED_FACT_IDS:
        v.add(path, "$.facts", f"事实 id 应为 f01–f30 连续，实际 {ids}")

    updated = [f for f in facts if isinstance(f, dict) and isinstance(f.get("update"), dict)]
    if len(updated) != EXPECTED_UPDATE_COUNT:
        v.add(path, "$.facts",
              f"带 update 的事实需要正好 {EXPECTED_UPDATE_COUNT} 条，实际 {len(updated)} 条")
    restated = [f for f in facts if isinstance(f, dict) and isinstance(f.get("restatement"), str)]
    if len(restated) < MIN_RESTATE_COUNT:
        v.add(path, "$.facts",
              f"带 restatement 的事实至少 {MIN_RESTATE_COUNT} 条，实际 {len(restated)} 条")
    immutable = [f for f in facts if isinstance(f, dict)
                 and f.get("memoryType") in IMMUTABLE_TYPES]
    if len(immutable) < MIN_IMMUTABLE_COUNT:
        v.add(path, "$.facts",
              f"event/identity/fact 三类合计至少 {MIN_IMMUTABLE_COUNT} 条，实际 {len(immutable)} 条")
    for f in immutable:
        if f.get("update") is not None:
            v.add(path, f.get("id", "?"),
                  f"不可变类型（{f.get('memoryType')}）不得带 update")
    if not isinstance(filler, list) or len(filler) < MIN_FILLER_COUNT:
        n = len(filler) if isinstance(filler, list) else 0
        v.add(path, "$.fillerTurns",
              f"fillerTurns 至少 {MIN_FILLER_COUNT} 组，实际 {n} 组")
    for field, need in (("deflections", EXPECTED_DEFLECTIONS),
                        ("acknowledgements", EXPECTED_ACKS),
                        ("leadIns", EXPECTED_LEADINS)):
        lst = data.get(field)
        lst = lst if isinstance(lst, list) else []
        if len(lst) != need:
            v.add(path, f"$.{field}", f"需要正好 {need} 条，实际 {len(lst)} 条")
        if len(set(lst)) != len(lst):
            v.add(path, f"$.{field}", "存在重复条目")

    # 3/4/5) 逐条事实：长度、换说法、泄露
    probe_texts = []
    for f in facts:
        if not isinstance(f, dict):
            v.add(path, "$.facts", "存在非对象的事实条目")
            continue
        fid = f.get("id", "?")
        if f.get("memoryType") not in MEMORY_TYPES:
            v.add(path, fid, f"memoryType 非法：{f.get('memoryType')!r}")
        if f.get("speaker") not in ("user", "character"):
            v.add(path, fid, f"speaker 应为 user/character，实际 {f.get('speaker')!r}")
        rel = f.get("relation")
        if not isinstance(rel, str) or not re.fullmatch(r"[a-z][a-z0-9_]*", rel or ""):
            v.add(path, fid, f"relation 应为小写下划线串，实际 {rel!r}")
        for field in ("subject", "object", "statement", "probe"):
            if not isinstance(f.get(field), str) or not f[field].strip():
                v.add(path, fid, f"{field} 应为非空字符串")
        statement = f.get("statement") if isinstance(f.get("statement"), str) else ""
        rest = f.get("restatement")
        if rest is not None and not isinstance(rest, str):
            v.add(path, fid, "restatement 应为 null 或字符串")
            rest = None
        upd = f.get("update")
        if upd is not None and (not isinstance(upd, dict)
                                or not isinstance(upd.get("statement"), str)
                                or not isinstance(upd.get("probe"), str)):
            v.add(path, fid, "update 应为 null 或含 statement/probe 字符串的对象")
            upd = None

        if rest == statement and isinstance(rest, str):
            v.add(path, fid, "restatement 与 statement 完全相同")
        if isinstance(upd, dict) and upd.get("statement") == statement:
            v.add(path, fid, "update.statement 与 statement 完全相同")

        pieces = [("statement", statement), ("restatement", rest),
                  ("update.statement", upd.get("statement") if isinstance(upd, dict) else None),
                  ("probe", f.get("probe") if isinstance(f.get("probe"), str) else None),
                  ("update.probe", upd.get("probe") if isinstance(upd, dict) else None)]
        for label, text in pieces:
            if isinstance(text, str) and hanzi_len(text) > MAX_HANZI:
                v.add(path, fid, f"{label} 超过 {MAX_HANZI} 个汉字（实际 {hanzi_len(text)}）")

        probe = f.get("probe") if isinstance(f.get("probe"), str) else None
        if probe is not None:
            if not rephrase_ok(probe, statement, names):
                v.add(path, fid, "probe 与 statement 存在相同的连续两字（换说法不足）")
            if isinstance(rest, str) and not rephrase_ok(probe, rest, names):
                v.add(path, fid, "probe 与 restatement 存在相同的连续两字（换说法不足）")
        if isinstance(upd, dict):
            uprobe, ustmt = upd.get("probe", ""), upd.get("statement", "")
            if not rephrase_ok(uprobe, ustmt, names):
                v.add(path, fid, "update.probe 与 update.statement 存在相同的连续两字")

        objv = f.get("object") if isinstance(f.get("object"), str) else ""
        if objv:
            if probe is not None and objv in probe:
                v.add(path, fid, f"probe 泄露了 object「{objv}」")
            if isinstance(upd, dict) and objv in upd.get("probe", ""):
                v.add(path, fid, f"update.probe 泄露了 object「{objv}」")

        if probe is not None:
            probe_texts.append(probe)
        if isinstance(upd, dict):
            probe_texts.append(upd.get("probe", ""))
    dupes = sorted({t for t in probe_texts if probe_texts.count(t) > 1})
    if dupes:
        v.add(path, "$.facts", f"probe / update.probe 存在重复：{dupes[:3]}")

    # 6) 干扰内容与事实无关
    objects = [(f.get("id", "?"), f.get("object", "")) for f in facts if isinstance(f, dict)]
    objects = [(fid, o) for fid, o in objects if isinstance(o, str) and o]
    distract = []
    if isinstance(data.get("greeting"), str):
        distract.append(("greeting", data["greeting"]))
    for field in ("leadIns", "acknowledgements", "deflections"):
        lst = data.get(field) if isinstance(data.get(field), list) else []
        for i, text in enumerate(lst):
            distract.append((f"{field}[{i}]", text))
    if isinstance(filler, list):
        for i, pair in enumerate(filler):
            if isinstance(pair, dict):
                distract.append((f"fillerTurns[{i}].user", pair.get("user", "")))
                distract.append((f"fillerTurns[{i}].assistant", pair.get("assistant", "")))
    for loc, text in distract:
        for fid, objv in objects:
            if objv in text:
                v.add(path, loc, f"干扰内容包含事实 {fid} 的 object「{objv}」")


# ---------------------------------------------------------------------------
# 夹具校验
# ---------------------------------------------------------------------------

def check_fixture(path, v):
    """校验单个会话夹具，返回用于 manifest 比对的统计 dict（解析失败返回 None）。"""
    stem = os.path.splitext(os.path.basename(path))[0]
    try:
        data = load_json(path)
    except (OSError, ValueError) as exc:
        v.add(path, "$", f"JSON 无法解析：{exc}")
        return None
    if not isinstance(data, dict):
        v.add(path, "$", "顶层不是 JSON 对象")
        return None
    if data.get("schemaVersion") != SCHEMA_VERSION:
        v.add(path, "$.schemaVersion", f"应为 {SCHEMA_VERSION}")
    if data.get("id") != stem:
        v.add(path, "$.id", f"id（{data.get('id')!r}）与文件名（{stem}）不一致")
    if not isinstance(data.get("title"), str):
        v.add(path, "$.title", "应为字符串")
    if not isinstance(data.get("contextTokenLimit"), int) or data.get("contextTokenLimit", 0) <= 0:
        v.add(path, "$.contextTokenLimit", "应为正整数")

    # 7) 楼层结构
    msgs = data.get("messages")
    if not isinstance(msgs, list):
        v.add(path, "$.messages", "缺少或类型错误：应为数组")
        return None
    for m in msgs:
        if not (isinstance(m, dict) and isinstance(m.get("floor"), int)
                and m.get("role") in ("user", "assistant")
                and isinstance(m.get("content"), str)):
            v.add(path, "$.messages", "消息应为 {floor:int, role, content:str}")
            return None
    floors = [m["floor"] for m in msgs]
    if floors != list(range(1, TOTAL_FLOORS + 1)):
        v.add(path, "$.messages",
               f"应为 {TOTAL_FLOORS} 楼且楼层号连续，实际 {len(floors)} 楼")
        return None
    if msgs[0]["role"] != "assistant":
        v.add(path, "floor:1", "第 1 楼应为 assistant（greeting）")
    for m in msgs[1:-1]:
        want = "user" if m["floor"] % 2 == 0 else "assistant"
        if m["role"] != want:
            v.add(path, f"floor:{m['floor']}", f"角色应为 {want}")
    if msgs[-1]["floor"] != TOTAL_FLOORS or msgs[-1]["role"] != "user":
        v.add(path, f"floor:{TOTAL_FLOORS}", "第 300 楼应为 user（末条探针）")

    by_floor = {m["floor"]: m for m in msgs}
    facts = data.get("facts")
    if not isinstance(facts, list):
        v.add(path, "$.facts", "缺少或类型错误：应为数组")
        return None
    probes = data.get("probes")
    if not isinstance(probes, list):
        v.add(path, "$.probes", "缺少或类型错误：应为数组")
        return None

    facts_by_id = {}
    for f in facts:
        if not isinstance(f, dict):
            v.add(path, "$.facts", "存在非对象的事实条目")
            return None
        fid = f.get("id", "?")
        facts_by_id[fid] = f
        for field in ("id", "memoryType", "subject", "relation", "object",
                      "speaker", "statement"):
            if not isinstance(f.get(field), str):
                v.add(path, fid, f"{field} 应为字符串")
        for field in ("statementFloor", "restatementFloor", "updateFloor"):
            if f.get(field) is not None and not isinstance(f.get(field), int):
                v.add(path, fid, f"{field} 应为 int 或 null")
        if f.get("update") is not None and not (
                isinstance(f.get("update"), dict)
                and isinstance(f["update"].get("statement"), str)
                and isinstance(f["update"].get("probe"), str)):
            v.add(path, fid, "update 应为 null 或含 statement/probe 的对象")

    # 8) 逐字出现
    def text_at(fid, label, floor, text, speaker):
        if not isinstance(floor, int) or floor not in by_floor:
            v.add(path, fid, f"{label} 楼层 {floor!r} 不在 1–300 内")
            return
        m = by_floor[floor]
        if text not in m["content"]:
            v.add(path, fid, f"{label} 未在第 {floor} 楼逐字出现")
        want = "user" if speaker == "user" else "assistant"
        if m["role"] != want:
            v.add(path, fid, f"{label} 所在楼（{floor}）角色应为 {want}")

    for f in facts:
        fid = f.get("id", "?")
        speaker = f.get("speaker")
        text_at(fid, "statement", f.get("statementFloor"), f.get("statement", ""), speaker)
        if f.get("restatement") is not None:
            if f.get("restatementFloor") is None:
                v.add(path, fid, "有 restatement 但缺少 restatementFloor")
            else:
                text_at(fid, "restatement", f["restatementFloor"],
                        f["restatement"], speaker)
        if f.get("update") is not None:
            if f.get("updateFloor") is None:
                v.add(path, fid, "有 update 但缺少 updateFloor")
            else:
                text_at(fid, "update.statement", f["updateFloor"],
                        f["update"]["statement"], speaker)

    # 11a) 探针数量与分布
    if len(probes) != len(PROBE_FLOORS):
        v.add(path, "$.probes", f"探针需要正好 {len(PROBE_FLOORS)} 个，实际 {len(probes)} 个")
    probe_floors = [p.get("floor") for p in probes if isinstance(p, dict)]
    if sorted(x for x in probe_floors if isinstance(x, int)) != PROBE_FLOORS:
        v.add(path, "$.probes", f"探针楼层应为 {PROBE_FLOORS}，实际 {probe_floors}")

    seen_targets = set()
    objects = [(f.get("id", "?"), f.get("object", "")) for f in facts]
    objects = [(fid, o) for fid, o in objects if isinstance(o, str) and o]
    for p in probes:
        if not isinstance(p, dict):
            v.add(path, "$.probes", "存在非对象的探针条目")
            continue
        pfloor = p.get("floor")
        tfid = p.get("targetFactId")
        version = p.get("targetVersion")
        loc = f"probe@{pfloor}"
        if not (isinstance(pfloor, int) and isinstance(tfid, str)
                and version in ("original", "updated")
                and isinstance(p.get("text"), str)
                and isinstance(p.get("absentFactIds"), list)):
            v.add(path, loc, "探针应为 {floor:int, targetFactId, targetVersion, absentFactIds, text}")
            continue
        f = facts_by_id.get(tfid)
        if f is None:
            v.add(path, loc, f"targetFactId {tfid} 不存在于 facts")
            continue
        seen_targets.add(tfid)
        if pfloor not in by_floor or by_floor[pfloor]["role"] != "user":
            v.add(path, loc, "探针楼层角色应为 user")
        elif p["text"] not in by_floor[pfloor]["content"]:
            v.add(path, loc, "探针文本未在该楼逐字出现")

        # 9) 版本至少提前 6 楼出现；absent 旧版本须已在此之前被更新
        if version == "original":
            sfloor = f.get("statementFloor")
            if not isinstance(sfloor, int) or pfloor - sfloor < MIN_FLOOR_GAP_FOR_VERSION:
                v.add(path, loc,
                      f"指向的原话出现在第 {sfloor} 楼，距探针不足 {MIN_FLOOR_GAP_FOR_VERSION} 楼")
        else:
            ufloor = f.get("updateFloor")
            if not isinstance(ufloor, int) or pfloor - ufloor < MIN_FLOOR_GAP_FOR_VERSION:
                v.add(path, loc,
                      f"指向的新版本出现在第 {ufloor} 楼，距探针不足 {MIN_FLOOR_GAP_FOR_VERSION} 楼")
            if tfid not in p.get("absentFactIds", []):
                v.add(path, loc, "指向新版本的探针应把同一条事实的原话版本记入 absentFactIds")
        for aid in p.get("absentFactIds", []):
            af = facts_by_id.get(aid)
            if af is None:
                v.add(path, loc, f"absentFactId {aid} 不存在于 facts")
            elif not isinstance(af.get("updateFloor"), int) or af["updateFloor"] >= pfloor:
                v.add(path, loc, f"absentFactId {aid} 未在该探针楼之前被更新")
        # 中段语义：问旧版本时，更新不得已发生在探针之前
        if version == "original" and isinstance(f.get("updateFloor"), int) \
                and f["updateFloor"] < pfloor:
            v.add(path, loc, "指向原话版本，但该事实的更新已发生在探针之前")

        # 10) 探针之后的角色回复不得包含任何事实 object（第 300 楼无回复）
        if pfloor != TOTAL_FLOORS and (pfloor + 1) in by_floor:
            reply = by_floor[pfloor + 1]["content"]
            for fid, objv in objects:
                if objv in reply:
                    v.add(path, f"floor:{pfloor + 1}",
                          f"探针后的角色回复包含事实 {fid} 的 object「{objv}」")
    if len(seen_targets) != len(probes):
        v.add(path, "$.probes", "同一事实被多个探针指向")

    return {
        "id": data.get("id"),
        "floors": len(msgs),
        "facts": len(facts),
        "updatedFacts": sum(1 for f in facts if f.get("update") is not None),
        "restatedFacts": sum(1 for f in facts if f.get("restatement") is not None),
        "probes": len(probes),
        "probesOnUpdated": sum(1 for p in probes
                               if isinstance(p, dict) and p.get("targetVersion") == "updated"),
        "totalChars": sum(len(m.get("content", "")) for m in msgs),
    }


def check_manifest(path, sessions_dir, v, stats_by_path):
    try:
        m = load_json(path)
    except (OSError, ValueError) as exc:
        v.add(path, "$", f"manifest 无法解析：{exc}")
        return
    if not isinstance(m, dict):
        v.add(path, "$", "manifest 顶层不是 JSON 对象")
        return
    if m.get("schemaVersion") != SCHEMA_VERSION:
        v.add(path, "$.schemaVersion", f"应为 {SCHEMA_VERSION}")
    sessions = m.get("sessions")
    if not isinstance(sessions, list):
        v.add(path, "$.sessions", "缺少或类型错误：应为数组")
        return
    entry_ids = [e.get("id") for e in sessions if isinstance(e, dict)]
    if entry_ids != sorted(entry_ids):
        v.add(path, "$.sessions", "sessions 未按 id 排序")
    file_ids = sorted(os.path.splitext(os.path.basename(p))[0] for p in stats_by_path)
    if entry_ids != file_ids:
        v.add(path, "$.sessions",
              f"sessions 列表 {entry_ids} 与夹具文件 {file_ids} 不一致")
    if m.get("sessionCount") != len(sessions):
        v.add(path, "$.sessionCount",
              f"应为 {len(sessions)}，实际 {m.get('sessionCount')}")
    by_id = {e.get("id"): e for e in sessions if isinstance(e, dict)}
    for fpath, stats in stats_by_path.items():
        if stats is None:
            continue
        e = by_id.get(stats["id"])
        if e is None:
            v.add(path, "$.sessions", f"缺少会话 {stats['id']} 的条目")
            continue
        for key in ("floors", "facts", "updatedFacts", "restatedFacts",
                    "probes", "probesOnUpdated", "totalChars"):
            if e.get(key) != stats[key]:
                v.add(path, f"sessions[{stats['id']}].{key}",
                      f"manifest 记录 {e.get(key)}，实际 {stats[key]}")


# ---------------------------------------------------------------------------
# main
# ---------------------------------------------------------------------------

def main(argv=None):
    ap = argparse.ArgumentParser(description="校验会话记忆评测的骨架与拼装产物")
    ap.add_argument("--spines", default=default_spines_dir(), help="骨架目录")
    ap.add_argument("--fixtures", default=default_fixtures_dir(), help="夹具会话目录")
    group = ap.add_mutually_exclusive_group()
    group.add_argument("--spines-only", action="store_true", help="只校验骨架")
    group.add_argument("--fixtures-only", action="store_true", help="只校验夹具与 manifest")
    args = ap.parse_args(argv)

    spines_dir = os.path.abspath(args.spines)
    fixtures_dir = os.path.abspath(args.fixtures)
    manifest_path = os.path.join(os.path.dirname(fixtures_dir), "manifest.json")
    v = Violations()

    def list_json(d):
        if not os.path.isdir(d):
            return []
        return sorted(os.path.join(d, name) for name in os.listdir(d)
                      if name.endswith(".json"))

    checked_spines = checked_fixtures = 0
    if not args.fixtures_only:
        spine_files = list_json(spines_dir)
        for path in spine_files:
            check_spine(path, v)
            checked_spines += 1
    if not args.spines_only:
        stats_by_path = {}
        for path in list_json(fixtures_dir):
            stats_by_path[path] = check_fixture(path, v)
            checked_fixtures += 1
        if stats_by_path or os.path.isfile(manifest_path):
            check_manifest(manifest_path, fixtures_dir, v, stats_by_path)

    kind = "骨架" if args.spines_only else ("夹具" if args.fixtures_only else "骨架+夹具")
    print(f"校验范围：{kind}；骨架 {checked_spines} 个文件，夹具 {checked_fixtures} 个文件。")
    if not args.fixtures_only and checked_spines == 0:
        print("注意：骨架目录为空（写手可能尚未交稿），跳过骨架校验。")
    if not args.spines_only and checked_fixtures == 0:
        print("注意：尚未发现拼装产物（可先运行 build_fixtures.py）。")

    for path, loc, msg in v.items:
        print(f"[违规] {path} :: {loc} :: {msg}")
    if v:
        print(f"共 {len(v)} 项违规，校验失败。")
        return 1
    print("全部通过，0 项违规。")
    return 0


if __name__ == "__main__":
    sys.exit(main())
