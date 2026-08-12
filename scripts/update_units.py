#!/usr/bin/env python3
"""Parse diclib.txt and add 'unit' field to words.json."""
import json
import re

# --- Parse diclib.txt ---
diclib = open('/data/wordbattle/docs/diclib.txt', encoding='utf-8').read()
words_json = json.load(open('/data/wordbattle/app/src/main/assets/words.json', encoding='utf-8'))

# Build mapping: entry_text -> unit
entry_unit = {}
current_unit = None

for line in diclib.splitlines():
    line = line.strip()
    if not line:
        continue
    m = re.match(r'^###\s+(.+)$', line)
    if m:
        current_unit = m.group(1).strip()
        continue
    # Extract page number (last token, purely numeric or with slash)
    page_match = re.search(r'\s+(\d+(?:/\d+)?)\s*$', line)
    if not page_match:
        continue
    page_str = page_match.group(1)
    if '/' in page_str:
        page = int(page_str.split('/')[0])
    else:
        page = int(page_str)
    # Entry text is everything before the phonetic/pronunciation part
    # e.g. "ready /ˈredi/ adj. 准备好（做某事）的 3"
    #      "of course 当然 5"
    # Match: text before first / or last space+number
    text = re.sub(r'\s+(\d+(?:/\d+)?)\s*$', '', line)
    # For single-word entries, extract the word before phonetic
    phonetic_match = re.match(r'^([^\s/]+)\s+/.*$', text)
    if phonetic_match:
        entry_text = phonetic_match.group(1)
    else:
        entry_text = text.strip()
    if current_unit and entry_text:
        entry_unit[entry_text] = current_unit

# --- Map words.json entries to units ---
def match_unit(word_entry):
    """Find unit for a word entry from words.json."""
    direction = word_entry.get('level', '')
    word = word_entry.get('word', '')
    translation = word_entry.get('translation', '')
    page = word_entry.get('page', 0)

    # For EN_TO_ZH: word is English -> match against dict entry
    # For ZH_TO_EN: translation is English -> match against dict entry
    if direction == 'EN_TO_ZH':
        # Try matching English word
        if word in entry_unit:
            return entry_unit[word]
        # Try matching Chinese translation
        if translation in entry_unit:
            return entry_unit[translation]
    elif direction == 'ZH_TO_EN':
        # Try matching Chinese word
        if word in entry_unit:
            return entry_unit[word]
        # Try matching English translation
        if translation in entry_unit:
            return entry_unit[translation]
    return None

matched = 0
unmatched = []

for w in words_json:
    unit = match_unit(w)
    if unit:
        w['unit'] = unit
        matched += 1
    else:
        unmatched.append(f"{w['word']} -> {w['translation']} (page={w.get('page')}, dir={w.get('level')})")

# Stats
from collections import Counter
unit_counts = Counter(w.get('unit', 'NONE') for w in words_json)

print(f"Total words: {len(words_json)}")
print(f"Matched: {matched}")
print(f"Unmatched: {len(unmatched)}")
print(f"\nUnit distribution:")
for unit, count in sorted(unit_counts.items()):
    print(f"  {unit}: {count}")

if unmatched:
    print(f"\nUnmatched entries:")
    for u in unmatched[:20]:
        print(f"  {u}")

# Write back
with open('/data/wordbattle/app/src/main/assets/words.json', 'w', encoding='utf-8') as f:
    json.dump(words_json, f, ensure_ascii=False, indent=2)

print(f"\nDone. Updated words.json with {matched} unit assignments.")