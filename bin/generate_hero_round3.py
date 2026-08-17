#!/usr/bin/env python3
"""Round 3 (Gene): 22 was fantastic — 4x better. LEFT: cubicles, each person
alone with a trough-like inbox of papers, lonely, walls all around. RIGHT:
even more dynamic — laughing at brilliant submissions, aha moments,
reviewers getting smarter, pride in crafting a great program. Keep the
selective-color device: gray left, color blooming right."""
import pathlib, importlib.util, concurrent.futures
spec = importlib.util.spec_from_file_location("gla", pathlib.Path(__file__).parent / "generate_landing_art.py")
gla = importlib.util.module_from_spec(spec); spec.loader.exec_module(gla)

DEVICE = ("Editorial New Yorker style ink and watercolor illustration where ONLY "
          "the RIGHT half of the image has color — the LEFT half is entirely gray "
          "monochrome, and warm watercolor golds and greens bloom exactly at the "
          "midline boundary. ")

ROUND3 = [
    ("31-cubicles-v3",
     DEVICE + "LEFT, gray: rows of small office cubicles seen in perspective, each one walling in a single hunched reviewer, alone, facing an inbox tray overflowing with papers like a feeding trough bolted to the desk — no one can see anyone else, only partition walls and the stack in front of them, profound loneliness. RIGHT, color: a long table where reviewers LAUGH together over a brilliant submission — one reading aloud with delight, another leaning back mid-laugh, a third struck by an aha moment with a hand to their head, pages glowing warmly, a program wall of cards taking shape behind them."),
    ("32-trough-overhead",
     DEVICE + "LEFT, gray, seen from slightly above: a grid of identical cubicle pens stretching back like livestock stalls, one lonely figure per pen bent over a trough-shaped inbox brimming with papers, partition walls high enough that nobody sees a neighbor. RIGHT, color, at warm eye level: the same room broken open — reviewers crowded around one table, laughing at a wonderful page held up, one pounding the table in delight, another wiping a tear of laughter, cards of an emerging program pinned behind."),
    ("33-one-cube-closeup",
     DEVICE + "LEFT, gray, intimate close-up: ONE reviewer alone inside a cubicle, walls close on three sides, a single overflowing inbox tray of papers before them, shoulders slumped, the loneliness palpable. RIGHT, color, wide: the communal opposite — a big animated table of reviewers mid-laughter and argument over glowing submissions, one standing to read a passage aloud, faces lit with discovery, pride visible in every posture."),
    ("34-laughing-aloud",
     DEVICE + "LEFT, gray: cubicle rows, each isolated figure buried behind an inbox trough of papers. RIGHT, color: the joy of review — one reviewer reads a brilliant passage ALOUD while the table erupts in warm laughter, heads thrown back, one applauding, delight not mockery, the shared thrill of finding something wonderful in the pile."),
    ("35-aha-halo",
     DEVICE + "LEFT, gray: the lonely cubicle pens with their paper troughs. RIGHT, color: the AHA moment — one reviewer holds a page that seems to radiate light, eyes wide with discovery, colleagues crowding in to read over their shoulders, one already striding to pin a card onto the glowing half-built program wall. Insight made visible."),
    ("36-pride-of-craft",
     DEVICE + "LEFT, gray: cubicle isolation, papers heaped in trough-like inboxes, bowed heads. RIGHT, color: the pride of the craft — a finished program wall of warmly glowing cards, the committee standing back to admire it together, one adjusting a final card from a ladder, two colleagues clinking coffee mugs in quiet triumph, satisfaction on every face."),
    ("37-getting-smarter",
     DEVICE + "LEFT, gray: the cubicle trough farm, one hunched figure per stall. RIGHT, color: pages passing hand to hand down a long animated table, each handoff leaving a faint trail of warm light, faces progressively brighter along the table's length as ideas accumulate — reviewers visibly getting smarter together, gesturing, laughing, scribbling notes."),
    ("38-wide-headline",
     DEVICE + "A very wide composition with generous pale negative space across the upper half for a headline. Lower LEFT, gray vignette: the cubicle pens with overflowing paper troughs, tiny and lonely. Lower RIGHT, color vignette: the laughing table with its glowing pages and emerging program wall. A thin path connects them across the bottom."),
]

def main():
    gla.OUT.mkdir(parents=True, exist_ok=True)
    key = gla.api_key()
    def one(item):
        name, prompt = item
        dest = gla.OUT / f"{name}.png"
        if dest.exists():
            return f"{name} exists, skipped"
        try:
            png = gla.generate(key, prompt)
        except RuntimeError as e:
            return f"{name} FAILED: {e}"
        dest.write_bytes(png)
        return f"{name} ok ({len(png):,}b)"
    with concurrent.futures.ThreadPoolExecutor(max_workers=6) as ex:
        for res in ex.map(one, ROUND3):
            print(res, flush=True)

if __name__ == "__main__":
    main()
