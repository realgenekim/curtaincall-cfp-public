#!/usr/bin/env python3
"""Round 2 (Gene, 2026-08-10): treatment 06 was the one he loved — make it
10x better. Keep the editorial ink-wash wit; left side goes Bruegel-dense
(chained mass serving the beige monolith), right side trades the picnic for
the EXCITEMENT of deliberation — a program committee shaping an amazing
conference, papers as scientific discovery. Eight variants, 21-28."""
import sys, pathlib
sys.path.insert(0, str(pathlib.Path(__file__).parent))
import importlib.util
spec = importlib.util.spec_from_file_location("gla", pathlib.Path(__file__).parent / "generate_landing_art.py")
gla = importlib.util.module_from_spec(spec); spec.loader.exec_module(gla)

COMMON = ("No text, no words, no letters, no logos anywhere. Landscape hero "
          "composition, one continuous scene divided naturally into left and "
          "right halves. Gallery-quality, sophisticated, editorial. ")

ROUND2 = [
    ("21-inkwash-v2",
     "Elegant New Yorker style ink and sepia wash illustration. LEFT: a sunken pen where DOZENS of resigned figures in rumpled suits sit chained in cramped rows before a towering beige corporate machine-monolith covered in tiny identical buttons, its long shadow falling over them, papers drooping from their hands — dense crowded Bruegel-like misery. RIGHT: past a broken fence, up a small rise, a warm lamplit open room in full deliberation: people STANDING around a long table mid-argument with delight, one holding a page up to the light, another leaning in pointing, a third at a wall where a grid of cards is taking shape — the thrill of shaping a great conference program together."),
    ("22-selective-color",
     "New Yorker style ink wash where ONLY the right half has color. LEFT, entirely gray monochrome: a dense Bruegel-like crowd of chained clerks hunched in rows beneath a towering beige button-covered software monolith. RIGHT, blooming into warm watercolor golds and greens across a fence line: an electric deliberation room — reviewers standing around a long table, pages held aloft catching lamplight, animated faces mid-debate, a wall of program cards half assembled behind them. The fence is the exact boundary where gray becomes color."),
    ("23-bruegel-oil",
     "Oil painting in the style of Pieter Bruegel the Elder, one continuous landscape: on the left, a teeming gray valley where a hundred small figures drag an enormous beige machine-monolith on log rollers, overseers with clipboards, chains and drudgery everywhere; on the right, a warm hilltop hall open on its side, revealing a long table of scholars in animated deliberation — luminous pages passed hand to hand, arms raised making a point, a great board behind them filling with an emerging program, golden evening light flooding out."),
    ("24-discovery-observatory",
     "Ink and warm wash editorial illustration. LEFT: chained clerks packed shoulder to shoulder in the shadow of a beige monolith of tiny buttons. RIGHT: deliberation as astronomy — a committee around a round table under a high window of stars, each held-up page GLOWING like a star chart, one member at a brass telescope aimed at a wall pinned with luminous abstracts, faces lit from the pages with wonder and argument. Discovery, not leisure."),
    ("25-verdict-energy",
     "Editorial ink and wash with warm accents, right side dominant. LEFT, compressed small in shadow: rows of chained figures before the beige monolith. RIGHT, filling most of the frame: the exact MOMENT a committee finds a great paper — one reader on their feet holding the page high, chair tipped back, others leaning in with lit faces, hands mid-gesture, papers strewn in energetic piles, a jury reaching a joyful verdict. Theatrical light from a single hanging lamp."),
    ("26-program-wall",
     "Elegant ink and warm-wash editorial illustration. LEFT in gray shadow: the chained committee pen beneath the towering beige button monolith. RIGHT in warm light: an enormous wall where a conference program is being SHAPED — a half-assembled grid of cards, two people conferring before it with papers in hand, one placing a card high on the wall from a small ladder, others at a table holding pages up in debate. The wall of the emerging program is the hero of the right half."),
    ("27-cinematic-wide",
     "Very wide minimalist editorial ink-wash with vast paper-white negative space across the sky, built for a headline. Small vignette lower LEFT: chained figures beneath the beige monolith, gray. Lower RIGHT: a warm lamplit deliberation table, figures standing and gesturing over glowing pages, rendered with selective warm color. The two vignettes connected by a thin path. Ninety percent elegance, ten percent detail."),
    ("28-daumier-etching",
     "Etching with ink wash in the style of Honoré Daumier: LEFT, a beige machine-idol on a plinth, rows of bowed clerks chained at its feet, cross-hatched gloom. RIGHT, the same room breaking open into light: a tribunal of reviewers in ecstatic deliberation, light bursting from the pages they hold like evidence in a great trial, one figure standing mid-oration with a page raised. Gravitas, wit, and the joy of judgment."),
]

def main():
    gla.OUT.mkdir(parents=True, exist_ok=True)
    key = gla.api_key()
    import concurrent.futures
    def one(item):
        name, prompt = item
        dest = gla.OUT / f"{name}.png"
        if dest.exists():
            return f"{name} exists, skipped"
        try:
            png = gla.generate(key, prompt)  # gla.generate prepends its COMMON
        except RuntimeError as e:
            return f"{name} FAILED: {e}"
        dest.write_bytes(png)
        return f"{name} ok ({len(png):,}b)"
    with concurrent.futures.ThreadPoolExecutor(max_workers=6) as ex:
        for res in ex.map(one, ROUND2):
            print(res, flush=True)

if __name__ == "__main__":
    main()
