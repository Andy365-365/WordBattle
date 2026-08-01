#!/usr/bin/env python3
import json, random, re

words = []
with open("/data/wordbattle/pic/diclib.txt", encoding="utf-8") as f:
    for line in f:
        line = line.strip()
        if not line or line.startswith("###"):
            continue
        # 提取页码（行尾数字）
        m = re.search(r'\s(\d+)\s*$', line)
        if not m:
            continue
        page = int(m.group(1))
        rest = line[:m.start()]
        # 提取单词（第一个token）
        word = rest.split()[0]
        # 过滤短语（含空格）和带连字符的单词保留
        if ' ' in word:
            continue
        # 提取中文释义：跳过 /xxx/ 音标和 词性. 前缀
        # 找到最后一个 词性. 后面的中文
        parts = rest.split()
        chinese = []
        found_pos = False
        for p in parts:
            if p.startswith('/'):
                continue
            if not found_pos and re.match(r'^[a-z]+\.$', p):
                found_pos = True
                continue
            if found_pos:
                chinese.append(p)
        translation = "".join(chinese)
        if not translation:
            continue
        words.append({"word": word, "translation": translation, "page": page})

print(f"解析到 {len(words)} 个单词")

# 生成题库
all_en = [w["word"] for w in words]
all_zh = [w["translation"] for w in words]
questions = []
for w in words:
    # EN -> ZH
    wrong_zh = random.sample([t for t in all_zh if t != w["translation"]], 3)
    opts_zh = [w["translation"]] + wrong_zh
    random.shuffle(opts_zh)
    correct_zh = opts_zh.index(w["translation"])
    questions.append({
        "word": w["word"],
        "translation": w["translation"],
        "distractors": wrong_zh,
        "level": "EN_TO_ZH",
        "page": w["page"]
    })
    # ZH -> EN
    wrong_en = random.sample([t for t in all_en if t != w["word"]], 3)
    opts_en = [w["word"]] + wrong_en
    random.shuffle(opts_en)
    correct_en = opts_en.index(w["word"])
    questions.append({
        "word": w["translation"],
        "translation": w["word"],
        "distractors": wrong_en,
        "level": "ZH_TO_EN",
        "page": w["page"]
    })

with open("/data/wordbattle/app/src/main/assets/words.json", "w", encoding="utf-8") as f:
    json.dump(questions, f, ensure_ascii=False, indent=2)

print(f"生成 {len(questions)} 题（英中 {len(words)} + 中英 {len(words)}）")