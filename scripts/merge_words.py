#!/usr/bin/env python3
"""
merge_words.py — 将 words.json 的双镜像条目（EN_TO_ZH + ZH_TO_EN 各一条）
合并为单条配对（一个英文词一条：word=英文, translation=中文, zhDistractors + enDistractors）。

配对规则（消费式匹配）:
  对每个 EN 条目 e，在 ZH 池里找满足 m.translation == e.word 且 m.word == e.translation 的条目，
  找到即从池中消费。同一中文释义被多个英文词共用时（如 everyone/everybody 共用"每个人，人人"），
  各自消费自己互指的那条镜像，互不干扰。

用法:
  python3 scripts/merge_words.py --check          # 只校验，不写文件
  python3 scripts/merge_words.py                  # 合并写回（备份原文件为 words.json.bak）

校验项（--check 模式下任一失败即退出码 1）:
  1. EN 侧与 ZH 侧条数一致
  2. 每个 EN 条目都能找到互指的 ZH 镜像（无孤儿）
  3. 写回后自检：所有 EN 侧 (word, translation, page, unit) + distractors 完整保留
"""
import json
import sys
import shutil
from pathlib import Path

WORDS = Path(__file__).resolve().parent.parent / "app" / "src" / "main" / "assets" / "words.json"


def main() -> int:
    check_only = "--check" in sys.argv
    data = json.loads(WORDS.read_text(encoding="utf-8"))
    en = [w for w in data if w.get("level") == "EN_TO_ZH"]
    zh = [w for w in data if w.get("level") == "ZH_TO_EN"]

    errors = []
    if len(en) != len(zh):
        errors.append(f"条数不一致: EN={len(en)} ZH={len(zh)}")

    zh_pool = list(zh)  # 消费式匹配池
    merged = []
    for e in en:
        idx = next(
            (i for i, m in enumerate(zh_pool)
             if m["word"] == e["translation"] and m["translation"] == e["word"]),
            None,
        )
        if idx is None:
            # 无互指镜像：以 EN 侧为准，enDistractors 置空（补位时直接 TBD）
            print(f"  [WARN] 无 ZH 镜像（以 EN 侧为准, enDistractors 置空）: {e['word']!r} -> {e['translation']!r}")
            if not check_only:
                merged.append({
                    "word": e["word"],
                    "translation": e["translation"],
                    "zhDistractors": e.get("distractors", []),
                    "enDistractors": [],
                    "page": e.get("page", 0),
                    "unit": e.get("unit", ""),
                })
            continue
        m = zh_pool.pop(idx)
        if (m.get("page"), m.get("unit")) != (e.get("page"), e.get("unit")):
            print(f"  [WARN] page/unit 不一致（取 EN 侧）: {e['word']!r} EN=({e.get('page')},{e.get('unit')}) ZH=({m.get('page')},{m.get('unit')})")
        if not check_only:
            merged.append({
                "word": e["word"],
                "translation": e["translation"],
                "zhDistractors": e.get("distractors", []),
                "enDistractors": m.get("distractors", []),
                "page": e.get("page", 0),
                "unit": e.get("unit", ""),
            })

    if zh_pool:
        for w in zh_pool:
            errors.append(f"孤儿 ZH 条目（无 EN 镜像）: {w['word']!r} -> {w['translation']!r}")

    if check_only:
        if errors:
            print("校验失败:")
            for e2 in errors:
                print("  -", e2)
            return 1
        print(f"校验通过: {len(en)} 个词可合并（EN/ZH 各 {len(en)}/{len(zh)} 条，无孤儿）")
        return 0

    if errors:
        print("校验失败，未写文件:")
        for e2 in errors:
            print("  -", e2)
        return 1

    # 写回（备份 + 保持 2 空格缩进、ensure_ascii=False，与原文件风格一致）
    shutil.copy(WORDS, WORDS.with_suffix(".json.bak"))
    WORDS.write_text(
        json.dumps(merged, ensure_ascii=False, indent=2) + "\n",
        encoding="utf-8",
    )
    print(f"合并完成: {len(data)} 条 -> {len(merged)} 条（备份: {WORDS.with_suffix('.json.bak').name}）")

    # 自检：重新读取，验证内容完整性
    out = json.loads(WORDS.read_text(encoding="utf-8"))
    assert len(out) == len(en), f"写回后条数异常: {len(out)} != {len(en)}"
    for e in en:
        m = next(o for o in out if o["word"] == e["word"])
        assert m["translation"] == e["translation"]
        assert m["zhDistractors"] == e.get("distractors", [])
        assert m["page"] == e.get("page", 0) and m["unit"] == e.get("unit", "")
    print("自检通过: 所有 EN 侧字段完整保留")
    return 0


if __name__ == "__main__":
    sys.exit(main())
