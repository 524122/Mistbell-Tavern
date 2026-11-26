#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""build_fixtures.py —— 把「会话骨架」(spine) 铺成 300 楼的完整对话夹具。

用法:
    python -I build_fixtures.py [--spines <骨架目录>] [--out <会话输出目录>]
                                [--include-sample]

默认路径按脚本自身位置推导（脚本位于 <仓库根>/.e2e/eval/memory/ 下）:
    骨架目录:  <仓库根>/.e2e/eval/memory/spines
    输出目录:  <仓库根>/app/src/sharedTest/resources/eval/memory/sessions
    manifest:  <仓库根>/app/src/sharedTest/resources/eval/memory/manifest.json
               （固定写在 --out 目录的旁边；sharedTest 同时挂在 test 与
                 androidTest 两个源集上，JVM 回放与设备侧评测读同一份夹具）

默认只处理 s01.json–s12.json；加 --include-sample 时额外处理 _sample.json
（以下划线开头的文件视为样例，不进入正式 12 个会话）。

确定性：不写时间戳；所有随机数来自 random.Random(会话编号整数)，例如 s03 用 3。
同一份骨架输入永远得到逐字节相同的输出。

布局规则（详见 README.md）:
  * 共 300 楼。第 1 楼 greeting(assistant)；第 t 轮(t=1..149) 占两楼：用户在 2t，
    角色在 2t+1；第 300 楼是最后一条探针(user)，无回复。
  * 探针轮固定在 t = 13,25,38,50,63,75,88,100,113,125,138（用户消息在第
    26,50,76,100,126,150,176,200,226,250,276 楼），外加第 300 楼。探针后角色
    一律用 deflections[i] 推托，绝不回答；第 300 楼无回复（deflections[11]
    不出现在消息里）。
  * 30 条原话轮铺在 3..90 轮；重述轮在原话轮 +8 起；更新轮在原话/重述轮 +20 起。
  * 前段 5 个探针（26..126 楼）指向 3 轮前已说出的原话版本，按事实 id 轮流，
    优先选不带更新的事实；中段 3 个（150/176/200 楼）指向「将被更新」的事实
    的原话版本（其更新轮在该探针轮之后）；后段 4 个（226/250/276/300 楼）
    指向已更新且更新轮在 3 轮之前的事实的新版本（update.probe），
    absentFactIds 记同一条事实的原话版本。
  * 恰好 7 条更新被安排在第 74 轮及之前（即第 148 楼之前），另外 3 条更新
    （中段探针指向的那 3 条事实）排在中段探针轮之后。
"""

from __future__ import annotations

import argparse
import json
import os
import random
import sys

SCHEMA_VERSION = 1

# ---- 楼层/轮次布局常量 ----
TOTAL_FLOORS = 300          # 第 1 楼 greeting；2..299 由第 1..149 轮占用；第 300 楼是末条探针
TOTAL_TURNS = 149           # 常规轮次 1..149（每轮两楼）
PROBE_TURNS = [13, 25, 38, 50, 63, 75, 88, 100, 113, 125, 138]
PROBE_FLOORS = [2 * t for t in PROBE_TURNS] + [300]   # 12 个探针楼层
SESSION_IDS = ["s%02d" % i for i in range(1, 13)]
SAMPLE_FILENAME = "_sample.json"

STMT_TURN_LO, STMT_TURN_HI = 3, 90   # 原话轮范围
REST_MIN_GAP = 8                     # 重述轮在原话轮之后至少 8 轮
UPDATE_MIN_GAP = 20                  # 更新轮在原话/重述轮之后至少 20 轮
PROBE_MIN_GAP_TURNS = 3              # 探针指向的版本须在探针轮 3 轮之前（= 6 楼）

FRONT_DEADLINES = [10, 22, 35, 47, 60]      # 前段 5 个探针(13/25/38/50/63 轮)的原话轮上限
MIDDLE_PROBE_TURNS = [75, 88, 100]          # 中段探针轮
MIDDLE_DEADLINES = [72, 85, 87]             # 中段探针的原话轮上限（probe_turn - 3）
EARLY_UPDATE_LAST_TURN = 74                 # 早更新轮上限（2*74=148 楼 < 150 楼）
EARLY_UPDATE_WINDOW = 12                    # 早更新目标轮在 [74-12, 74] 内抖动
EARLY_REST_LAST_TURN = 54                   # 「早更新且带重述」事实的重述轮上限（+20 后仍 ≤74）
EARLY_STMT_DL_RESTATED = 44                 # 「早更新且带重述」事实的原话轮上限
EARLY_STMT_DL_PLAIN = 48                    # 其余早更新事实的原话轮上限

CONTEXT_TOKEN_LIMIT = 2048

# ---- 骨架硬性数量（与 spine-schema-final.md 一致）----
EXPECTED_FACT_COUNT = 30
EXPECTED_UPDATE_COUNT = 10
MIN_RESTATE_COUNT = 8
MIN_FILLER_COUNT = 90
EXPECTED_DEFLECTIONS = 12
EXPECTED_ACKS = 16
EXPECTED_LEADINS = 10


class LayoutError(Exception):
    """骨架无法按布局规则铺开。"""


def _require(cond, msg):
    if not cond:
        raise LayoutError(msg)


def repo_root():
    """脚本位于 <仓库根>/.e2e/eval/memory/build_fixtures.py。"""
    here = os.path.dirname(os.path.abspath(__file__))
    return os.path.dirname(os.path.dirname(os.path.dirname(here)))


def default_spines_dir():
    return os.path.join(os.path.dirname(os.path.abspath(__file__)), "spines")


def default_out_dir():
    return os.path.join(repo_root(), "app", "src", "sharedTest", "resources",
                        "eval", "memory", "sessions")


def session_seed(session_id):
    """s03 -> 3；不含数字的 id（如 _sample）用 0。"""
    digits = "".join(ch for ch in session_id if ch.isdigit())
    return int(digits) if digits else 0


def load_json(path):
    with open(path, "r", encoding="utf-8-sig") as fh:
        return json.load(fh)


def dump_json(path, obj):
    """UTF-8、LF、ensure_ascii=False、缩进 2、固定键顺序。"""
    text = json.dumps(obj, ensure_ascii=False, indent=2, sort_keys=False) + "\n"
    with open(path, "w", encoding="utf-8", newline="\n") as fh:
        fh.write(text)


# ---------------------------------------------------------------------------
# 空闲轮次查找
# ---------------------------------------------------------------------------

def nearest_free(occupied, start, lo, hi):
    """[lo, hi] 内离 start 最近且未被占用的轮次；同距离先取更小者。"""
    if lo > hi:
        return None
    span = max(start - lo, hi - start)
    for off in range(0, span + 1):
        for cand in ((start - off,) if off == 0 else (start - off, start + off)):
            if lo <= cand <= hi and cand not in occupied:
                return cand
    return None


def downward_free(occupied, start, lo, hi):
    for cand in range(min(start, hi), lo - 1, -1):
        if cand not in occupied:
            return cand
    return None


def upward_free(occupied, start, lo, hi):
    for cand in range(max(start, lo), hi + 1):
        if cand not in occupied:
            return cand
    return None


# ---------------------------------------------------------------------------
# 布局
# ---------------------------------------------------------------------------

def layout_session(spine):
    """返回 (session_dict, manifest_entry_dict)。"""
    sid = str(spine["id"])
    rng = random.Random(session_seed(sid))

    facts = sorted(spine["facts"], key=lambda f: f["id"])
    facts_by_id = {f["id"]: f for f in facts}
    updated = [f for f in facts if f.get("update")]
    plain = [f for f in facts if not f.get("update")]
    restated = [f for f in facts if f.get("restatement")]

    _require(len(facts) == EXPECTED_FACT_COUNT,
             f"{sid}: facts 需要正好 {EXPECTED_FACT_COUNT} 条，实际 {len(facts)} 条")
    _require(len(updated) == EXPECTED_UPDATE_COUNT,
             f"{sid}: 带 update 的事实需要正好 {EXPECTED_UPDATE_COUNT} 条，实际 "
             f"{len(updated)} 条（前 150 楼 ≥7 次更新与中段 3 个探针同时成立需要 10 条）")
    _require(len(restated) >= MIN_RESTATE_COUNT,
             f"{sid}: 带 restatement 的事实至少 {MIN_RESTATE_COUNT} 条，实际 {len(restated)} 条")
    _require(len(plain) >= 5,
             f"{sid}: 不带 update 的事实至少 5 条（前段探针用），实际 {len(plain)} 条")
    _require(len(spine["fillerTurns"]) >= MIN_FILLER_COUNT,
             f"{sid}: fillerTurns 至少 {MIN_FILLER_COUNT} 组，实际 {len(spine['fillerTurns'])} 组")
    _require(len(spine["deflections"]) == EXPECTED_DEFLECTIONS,
             f"{sid}: deflections 需要正好 {EXPECTED_DEFLECTIONS} 条")
    _require(len(spine["acknowledgements"]) == EXPECTED_ACKS,
             f"{sid}: acknowledgements 需要正好 {EXPECTED_ACKS} 条")
    _require(len(spine["leadIns"]) == EXPECTED_LEADINS,
             f"{sid}: leadIns 需要正好 {EXPECTED_LEADINS} 条")

    front_facts = plain[:5]            # 前段 5 个探针（原话版本）
    middle_facts = updated[:3]         # 中段 3 个探针（原话版本，更新在其后）
    back_facts = updated[3:7]          # 后段 4 个探针（新版本）
    early_updated = back_facts + updated[7:]   # 更新必须 ≤ 第 74 轮

    # 每条事实的原话轮 deadline（由探针指向与更新排布反推）
    deadline = {}
    for f, d in zip(front_facts, FRONT_DEADLINES):
        deadline[f["id"]] = d
    for f, d in zip(middle_facts, MIDDLE_DEADLINES):
        deadline[f["id"]] = d
    early_ids = {f["id"] for f in early_updated}
    for f in early_updated:
        deadline[f["id"]] = (EARLY_STMT_DL_RESTATED if f.get("restatement")
                             else EARLY_STMT_DL_PLAIN)

    occupied = set(PROBE_TURNS)        # 探针轮不可挪用
    place = {f["id"]: {"stmt": None, "rest": None, "upd": None} for f in facts}

    def book(fid, field, t):
        occupied.add(t)
        place[fid][field] = t

    def ideal_turn(idx):
        count = len(facts)
        return STMT_TURN_LO + round(idx * (STMT_TURN_HI - STMT_TURN_LO) / (count - 1))

    # ---- 1) 受约束的原话轮（前段/中段/早更新事实），deadline 紧的先排 ----
    unconstrained = []
    order = sorted(facts, key=lambda f: (deadline.get(f["id"], STMT_TURN_HI + 1), f["id"]))
    for f in order:
        fid = f["id"]
        if fid not in deadline:
            unconstrained.append(f)
            continue
        dl = deadline[fid]
        idx = int(fid[1:]) - 1
        start = max(STMT_TURN_LO, min(ideal_turn(idx) + rng.randint(-1, 1), dl))
        t = nearest_free(occupied, start, STMT_TURN_LO, dl)
        _require(t is not None,
                 f"{sid}: 无法为 {fid} 安排原话轮（范围 [{STMT_TURN_LO},{dl}] 内没有空闲轮）")
        book(fid, "stmt", t)

    # ---- 2) 早更新事实的重述轮：窗口 [stmt+8, 50]，窗口最小的先排 ----
    early_rests = sorted((f for f in early_updated if f.get("restatement")),
                         key=lambda f: (-(place[f["id"]]["stmt"] + REST_MIN_GAP), f["id"]))
    for f in early_rests:
        fid = f["id"]
        lo = place[fid]["stmt"] + REST_MIN_GAP
        start = min(lo + rng.randint(0, 3), EARLY_REST_LAST_TURN)
        t = nearest_free(occupied, start, lo, EARLY_REST_LAST_TURN)
        _require(t is not None,
                 f"{sid}: 无法为 {fid} 安排重述轮（须在 {EARLY_REST_LAST_TURN} 轮前，"
                 f"以便其更新轮落在 150 楼之前）")
        book(fid, "rest", t)

    # ---- 3) 早更新轮：[base+20, 74]，窗口最小的先排（保证 ≥7 次更新在 150 楼前）----
    def early_base(fid):
        return place[fid]["rest"] or place[fid]["stmt"]

    upd_order = sorted(early_updated,
                       key=lambda f: (-(early_base(f["id"]) + UPDATE_MIN_GAP), f["id"]))
    for f in upd_order:
        fid = f["id"]
        lo = early_base(fid) + UPDATE_MIN_GAP
        start = max(lo, EARLY_UPDATE_LAST_TURN - rng.randint(0, EARLY_UPDATE_WINDOW))
        t = nearest_free(occupied, start, lo, EARLY_UPDATE_LAST_TURN)
        _require(t is not None,
                 f"{sid}: 无法为 {fid} 在 {EARLY_UPDATE_LAST_TURN} 轮前安排更新轮"
                 f"（需保证 ≥7 次更新落在 150 楼之前）")
        book(fid, "upd", t)

    # ---- 4) 其余原话轮：理想位均匀铺开，撞上已占轮次就近挪 ----
    for f in unconstrained:
        fid = f["id"]
        idx = int(fid[1:]) - 1
        start = max(STMT_TURN_LO, min(ideal_turn(idx) + rng.randint(-1, 1), STMT_TURN_HI))
        t = nearest_free(occupied, start, STMT_TURN_LO, STMT_TURN_HI)
        _require(t is not None,
                 f"{sid}: 无法为 {fid} 安排原话轮（3–90 轮内没有空闲轮）")
        book(fid, "stmt", t)

    # ---- 5) 其余重述轮：原话轮 +8 起，铺到 149 轮内 ----
    for f in facts:
        if f.get("restatement") and place[f["id"]]["rest"] is None:
            fid = f["id"]
            lo = place[fid]["stmt"] + REST_MIN_GAP
            start = lo + rng.randint(0, 3)
            t = nearest_free(occupied, start, lo, TOTAL_TURNS)
            _require(t is not None,
                     f"{sid}: 无法为 {fid} 安排重述轮（原话轮 {place[fid]['stmt']} 之后没有空闲轮）")
            book(fid, "rest", t)

    # ---- 6) 中段事实的更新轮：排在中段探针（75/88/100 轮）之后 ----
    for f, pt in zip(middle_facts, MIDDLE_PROBE_TURNS):
        fid = f["id"]
        base = place[fid]["rest"] or place[fid]["stmt"]
        start = max(pt + 3, base + UPDATE_MIN_GAP) + rng.randint(0, 3)
        t = upward_free(occupied, start, pt + 1, TOTAL_TURNS)
        _require(t is not None,
                 f"{sid}: 无法为 {fid} 在中段探针（第 {pt} 轮）之后安排更新轮")
        book(fid, "upd", t)

    # ---- 4) 探针指向 ----
    probe_targets = []
    for k, f in enumerate(front_facts):
        probe_targets.append({"floor": PROBE_FLOORS[k], "fact": f,
                              "version": "original", "absent": [], "text": f["probe"]})
    for j, f in enumerate(middle_facts):
        probe_targets.append({"floor": PROBE_FLOORS[5 + j], "fact": f,
                              "version": "original", "absent": [], "text": f["probe"]})
    for m, f in enumerate(back_facts):
        probe_targets.append({"floor": PROBE_FLOORS[8 + m], "fact": f,
                              "version": "updated", "absent": [f["id"]],
                              "text": f["update"]["probe"]})

    # ---- 5) 轮次内容表 + filler 填充 ----
    turns = {}
    for i, t in enumerate(PROBE_TURNS):
        turns[t] = ("probe", i)
    for f in facts:
        p = place[f["id"]]
        turns[p["stmt"]] = ("fact", f, "statement")
        if p["rest"] is not None:
            turns[p["rest"]] = ("fact", f, "restatement")
        if p["upd"] is not None:
            turns[p["upd"]] = ("fact", f, "update")
    filler_slots = [t for t in range(1, TOTAL_TURNS + 1) if t not in occupied]
    pool = list(range(len(spine["fillerTurns"])))
    rng.shuffle(pool)
    for k, t in enumerate(filler_slots):
        turns[t] = ("filler", spine["fillerTurns"][pool[k % len(pool)]])

    # ---- 6) 逐楼生成消息 ----
    acks = spine["acknowledgements"]
    leads = spine["leadIns"]
    defls = spine["deflections"]
    messages = [{"floor": 1, "role": "assistant", "content": spine["greeting"]}]
    ack_i = lead_i = 0
    for t in range(1, TOTAL_TURNS + 1):
        kind = turns[t]
        uf, af = 2 * t, 2 * t + 1
        if kind[0] == "probe":
            p = probe_targets[kind[1]]
            messages.append({"floor": uf, "role": "user", "content": p["text"]})
            messages.append({"floor": af, "role": "assistant", "content": defls[kind[1]]})
        elif kind[0] == "fact":
            f, field = kind[1], kind[2]
            text = f["update"]["statement"] if field == "update" else f[field]
            if f["speaker"] == "user":
                messages.append({"floor": uf, "role": "user", "content": text})
                messages.append({"floor": af, "role": "assistant",
                                 "content": acks[ack_i % len(acks)]})
                ack_i += 1
            else:
                messages.append({"floor": uf, "role": "user",
                                 "content": leads[lead_i % len(leads)]})
                messages.append({"floor": af, "role": "assistant", "content": text})
                lead_i += 1
        else:
            pair = kind[1]
            messages.append({"floor": uf, "role": "user", "content": pair["user"]})
            messages.append({"floor": af, "role": "assistant", "content": pair["assistant"]})
    messages.append({"floor": 300, "role": "user", "content": probe_targets[11]["text"]})

    # ---- 7) 事实与探针的机器可读记录 ----
    def floor_of(fid, field):
        t = place[fid][field]
        return 2 * t if facts_by_id[fid]["speaker"] == "user" else 2 * t + 1

    facts_out = []
    for f in facts:
        fid = f["id"]
        upd = f.get("update")
        rest = f.get("restatement")
        facts_out.append({
            "id": f["id"],
            "memoryType": f["memoryType"],
            "subject": f["subject"],
            "relation": f["relation"],
            "object": f["object"],
            "speaker": f["speaker"],
            "statement": f["statement"],
            "statementFloor": floor_of(fid, "stmt"),
            "restatement": rest,
            "restatementFloor": floor_of(fid, "rest") if rest else None,
            "update": ({"statement": upd["statement"], "probe": upd["probe"]}
                       if upd else None),
            "updateFloor": floor_of(fid, "upd") if upd else None,
        })

    probes_out = [{
        "floor": p["floor"],
        "targetFactId": p["fact"]["id"],
        "targetVersion": p["version"],
        "absentFactIds": list(p["absent"]),
        "text": p["text"],
    } for p in probe_targets]

    session = {
        "schemaVersion": SCHEMA_VERSION,
        "id": sid,
        "title": spine.get("title", sid),
        "contextTokenLimit": CONTEXT_TOKEN_LIMIT,
        "messages": messages,
        "facts": facts_out,
        "probes": probes_out,
    }
    entry = {
        "id": sid,
        "floors": len(messages),
        "facts": len(facts_out),
        "updatedFacts": sum(1 for f in facts_out if f["update"] is not None),
        "restatedFacts": sum(1 for f in facts_out if f["restatement"] is not None),
        "probes": len(probes_out),
        "probesOnUpdated": sum(1 for p in probes_out if p["targetVersion"] == "updated"),
        "totalChars": sum(len(m["content"]) for m in messages),
    }
    _verify_session(session)
    return session, entry


def _verify_session(session):
    """拼装后的自检（防御性，正常骨架不会触发）。"""
    sid = session["id"]
    msgs = session["messages"]
    _require(len(msgs) == TOTAL_FLOORS, f"{sid}: 消息数应为 {TOTAL_FLOORS}")
    _require([m["floor"] for m in msgs] == list(range(1, TOTAL_FLOORS + 1)),
             f"{sid}: 楼层号不连续")
    by_floor = {m["floor"]: m for m in msgs}
    _require(by_floor[1]["role"] == "assistant", f"{sid}: 第 1 楼应为 assistant")
    for fl in range(2, TOTAL_FLOORS):
        want = "user" if fl % 2 == 0 else "assistant"
        _require(by_floor[fl]["role"] == want, f"{sid}: 第 {fl} 楼角色应为 {want}")
    _require(by_floor[TOTAL_FLOORS]["role"] == "user", f"{sid}: 第 300 楼应为 user")

    facts_by_id = {f["id"]: f for f in session["facts"]}
    objects = [f["object"] for f in session["facts"] if f.get("object")]

    def check_text(fid, label, floor, text, speaker):
        m = by_floor[floor]
        _require(text in m["content"],
                 f"{sid}: {fid} 的{label}未在第 {floor} 楼逐字出现")
        want = "user" if speaker == "user" else "assistant"
        _require(m["role"] == want, f"{sid}: {fid} 的{label}楼层角色错误")

    for f in session["facts"]:
        check_text(f["id"], "statement", f["statementFloor"], f["statement"], f["speaker"])
        if f["restatement"] is not None:
            check_text(f["id"], "restatement", f["restatementFloor"],
                       f["restatement"], f["speaker"])
        if f["update"] is not None:
            check_text(f["id"], "update.statement", f["updateFloor"],
                       f["update"]["statement"], f["speaker"])

    seen_targets = set()
    for p in session["probes"]:
        m = by_floor[p["floor"]]
        _require(m["role"] == "user", f"{sid}: 探针楼层 {p['floor']} 应为 user")
        _require(p["text"] in m["content"],
                 f"{sid}: 探针文本未在第 {p['floor']} 楼逐字出现")
        f = facts_by_id[p["targetFactId"]]
        seen_targets.add(p["targetFactId"])
        if p["targetVersion"] == "original":
            _require(p["floor"] - f["statementFloor"] >= 2 * PROBE_MIN_GAP_TURNS,
                     f"{sid}: 探针@{p['floor']} 指向的原话距探针不足 6 楼")
        else:
            _require(f["updateFloor"] is not None
                     and p["floor"] - f["updateFloor"] >= 2 * PROBE_MIN_GAP_TURNS,
                     f"{sid}: 探针@{p['floor']} 指向的新版本距探针不足 6 楼")
        for aid in p["absentFactIds"]:
            af = facts_by_id[aid]
            _require(af["updateFloor"] is not None and af["updateFloor"] < p["floor"],
                     f"{sid}: 探针@{p['floor']} 的 absentFactId {aid} 未在此之前被更新")
        if p["floor"] != TOTAL_FLOORS:
            reply = by_floor[p["floor"] + 1]["content"]
            _require(not any(o and o in reply for o in objects),
                     f"{sid}: 探针@{p['floor']} 之后的角色回复包含事实 object")
    _require(sorted(p["floor"] for p in session["probes"]) == PROBE_FLOORS,
             f"{sid}: 探针楼层分布不符合布局规则")
    _require(len(seen_targets) == len(session["probes"]),
             f"{sid}: 同一事实被探针指向多次")


# ---------------------------------------------------------------------------
# main
# ---------------------------------------------------------------------------

def main(argv=None):
    here = os.path.dirname(os.path.abspath(__file__))
    ap = argparse.ArgumentParser(description="把会话骨架铺成 300 楼对话夹具")
    ap.add_argument("--spines", default=default_spines_dir(), help="骨架目录")
    ap.add_argument("--out", default=default_out_dir(), help="会话输出目录（manifest 写在其旁）")
    ap.add_argument("--include-sample", action="store_true",
                    help="额外处理 _sample.json（自测用）")
    args = ap.parse_args(argv)

    spines_dir = os.path.abspath(args.spines)
    out_dir = os.path.abspath(args.out)
    manifest_path = os.path.join(os.path.dirname(out_dir), "manifest.json")

    wanted = [f"{sid}.json" for sid in SESSION_IDS]
    if args.include_sample:
        wanted.append(SAMPLE_FILENAME)
    found, missing = [], []
    for name in wanted:
        path = os.path.join(spines_dir, name)
        (found.append(path) if os.path.isfile(path) else missing.append(name))
    if not found:
        print(f"错误：在 {spines_dir} 下没有找到任何骨架文件（s01–s12"
              f"{'+_sample' if args.include_sample else ''}）。", file=sys.stderr)
        return 2
    if missing:
        print(f"提示：缺少骨架 {', '.join(missing)}，跳过。", file=sys.stderr)

    os.makedirs(out_dir, exist_ok=True)
    entries = []
    for path in found:
        try:
            spine = load_json(path)
            session, entry = layout_session(spine)
        except (LayoutError, KeyError, TypeError, ValueError) as exc:
            print(f"错误：{path}: {exc}", file=sys.stderr)
            return 1
        out_path = os.path.join(out_dir, f"{session['id']}.json")
        dump_json(out_path, session)
        entries.append(entry)
        print(f"[{session['id']}] {entry['floors']} 楼 · {entry['facts']} 事实"
              f"（更新 {entry['updatedFacts']} / 重述 {entry['restatedFacts']}）"
              f" · {entry['probes']} 探针（新版本 {entry['probesOnUpdated']}）"
              f" · {entry['totalChars']} 字符 -> {out_path}")

    entries.sort(key=lambda e: e["id"])
    manifest = {
        "schemaVersion": SCHEMA_VERSION,
        "sessionCount": len(entries),
        "sessions": entries,
    }
    dump_json(manifest_path, manifest)
    print(f"manifest（{len(entries)} 个会话）-> {manifest_path}")
    return 0


if __name__ == "__main__":
    sys.exit(main())
