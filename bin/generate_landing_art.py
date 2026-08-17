#!/usr/bin/env python3
"""Landing-page art treatments (Gene, 2026-08-10): the trough vs the table.

Twenty visual treatments of one contrast — the confining gray drudgery of
the conference tools we hate, versus the warm social experience of reading
fascinating proposals with peers. For conference organizers. Provocative,
delightful, sophisticated. Same pipeline as bin/generate_headshots.py.

Output: docs/mockups/landing-art/tNN.png (NOT committed; the HTML review
page is). Key runtime-loaded from ~/src.local/secrets/openai.edn.

Usage: python3 bin/generate_landing_art.py [--test]
"""
import base64, concurrent.futures, json, pathlib, re, subprocess, sys

SECRETS = pathlib.Path.home() / "src.local/secrets/openai.edn"
OUT = pathlib.Path(__file__).resolve().parent.parent / "docs/mockups/landing-art"

COMMON = ("No text, no words, no letters, no logos anywhere in the image. "
          "Landscape composition, suitable as a website hero image. "
          "Sophisticated, gallery-quality execution. ")

TREATMENTS = [
    ("01-bruegel-diptych",
     "A single painting in the style of Pieter Bruegel the Elder, split as a natural diptych: on the left, a gray sunken trench where dozens of hunched clerks shovel endless paper forms under a sickly fluorescent sky; on the right, connected by a small bridge, a luminous garden terrace where people sit around one great wooden table passing handwritten pages to each other, wine and fruit on the table, faces lit with delight and argument."),
    ("02-travel-poster-escape",
     "Flat mid-century travel poster illustration, limited warm palette: a small figure climbing joyfully out of a deep gray ditch filled with beige paper forms and tangled dropdown menus, up onto a sunlit hilltop where a glowing open-air reading room waits, friends waving from a long table."),
    ("03-hopper-2am",
     "Oil painting in the style of Edward Hopper: a lone conference organizer at a desk at 2am, face lit only by a monitor's cold glow, surrounded by towers of paper submissions, an empty coffee cup, the office vast and dark around them. Cinematic melancholy, one warm lamp unlit in the background."),
    ("04-matisse-readers",
     "Henri Matisse cut-out style, bold saturated color on cream: a circle of dancing figures, each holding a bright page like a kite, pages swirling between them like leaves in wind. Pure joy of shared reading. Minimal, iconic, enormous energy."),
    ("05-constructivist-table",
     "Soviet constructivist poster style, red black and cream, dramatic diagonals: heroic figures shoulder to shoulder at a long angled table, passing luminous pages down the line, geometric rays of light from the pages. Monumental, optimistic, collective."),
    ("06-inkwash-monolith",
     "New Yorker style ink and watercolor wash cartoon, wry and elegant: a conference committee shackled by long chains to an enormous beige corporate software monolith with tiny unusable buttons, while just beyond a low fence, another group picnics under a tree happily reading pages aloud to each other."),
    ("07-annunciation-submission",
     "Renaissance annunciation scene reimagined in oil: a conference organizer at a wooden desk turns in wonder as a winged messenger presents a single glowing manuscript page, golden light flooding the room, dust motes suspended. Reverence for the arrival of a great idea."),
    ("08-ukiyoe-wave",
     "Ukiyo-e woodblock print in the style of Hokusai's Great Wave: a towering cresting wave made entirely of gray paper forms and spreadsheet grids about to crash onto a tiny rowboat with one exhausted organizer; in the far distance, serene on the horizon, a warm lantern-lit tea house where figures read together in peace."),
    ("09-magritte-heads",
     "Surrealist oil painting in the style of René Magritte, two figures side by side against a blue cloud-dotted sky: one bowler-hatted figure whose head is a gray locked filing cabinet, the other identical figure whose head is an open glowing illuminated book with pages lifting like wings."),
    ("10-diorama-crack",
     "Isometric miniature diorama photograph, tilt-shift: a vast gray cubicle maze shaped like a spreadsheet grid, cracking open down the middle, and out of the crack grows a warm sunken garden amphitheater where tiny figures gather around tables reading and gesturing, golden light spilling from the crack."),
    ("11-wpa-reading-lodge",
     "WPA national parks poster style, warm dawn palette, majestic scale: a great timber reading lodge at golden hour, wide steps, people streaming toward it carrying pages, long shared tables visible through enormous windows, mountains behind."),
    ("12-noir-avalanche",
     "Film noir photograph, dramatic venetian-blind light: a detective-like figure at a desk buried to the chest in an avalanche of triplicate carbon-copy forms, cigarette smoke curling, one hand reaching desperately from the paper drift toward a warm doorway of light at frame right."),
    ("13-botanical-plates",
     "Antique botanical illustration plate, two specimens side by side on aged cream paper: left, a wilted gray plant whose leaves are tiny withered spreadsheet grids, roots tangled in cable; right, a flourishing plant whose broad healthy leaves are handwritten manuscript pages, blossoms of small golden stars."),
    ("14-bauhaus-resolve",
     "Bauhaus geometric abstraction: on the left, a chaotic tangle of gray and beige rectangles, arrows, and broken grids compressing a small human silhouette; flowing rightward, the shapes resolve into a clean warm composition of golden rectangles passing between open circles arranged around a table form."),
    ("15-caravaggio-candlelight",
     "Oil painting in the style of Caravaggio, deep chiaroscuro: five reviewers leaning into a single candle's light over one manuscript on a dark wooden table, faces vivid with argument and delight, one pointing at a line, another laughing, wine glasses catching the light. The shared table as sacrament."),
    ("16-graphic-novel-breakout",
     "Dynamic graphic novel splash page, bold inks and halftone: a figure mid-leap bursting free of chains made of gray dropdown menus and progress bars that shatter behind them, reaching toward a floating spiral of glowing pages ascending into light."),
    ("17-ghibli-barnraising",
     "Warm pastoral animation film style, golden hour: a community barn-raising scene where dozens of cheerful people together assemble a huge wooden wall of colorful schedule cards in a meadow, ladders and lemonade, children handing cards up, a conference stage being born from communal joy."),
    ("18-brutalist-maze",
     "Aerial photograph of a vast brutalist concrete maze shaped like an enormous spreadsheet grid, one tiny lost figure casting a long shadow in a beige corridor; a thin red thread runs along the floor, leading out of the maze to a green sunken courtyard alive with people reading at long tables."),
    ("19-vanitas-still-life",
     "Dutch Golden Age still life, one canvas split by light: on the shadowed left, a vanitas of dead conference tooling — a dusty CRT monitor, tangled beige cables, wilted sticky notes, an hourglass nearly empty; on the sunlit right, abundance — stacks of crisp manuscripts, ripe fruit, full coffee cups, gold stars scattered like coins."),
    ("20-troughs-cheeky",
     "Whimsical children's book watercolor, cheeky and warm: in the foreground a literal wooden pig trough filled with gray crumpled forms, abandoned; behind it, the whole page glows with a long lamplit banquet table where delighted people feast on nothing but wonderful pages, passing them like dishes."),
]

def api_key():
    m = re.search(r':key\s+"([^"]+)"', SECRETS.read_text())
    if not m:
        sys.exit(f"no :key in {SECRETS}")
    return m.group(1)

def generate(key, prompt):
    body = json.dumps({"model": "gpt-image-1", "prompt": COMMON + prompt,
                       "size": "1536x1024", "quality": "low", "n": 1})
    r = subprocess.run(
        ["curl", "-sS", "--fail-with-body", "-m", "300",
         "https://api.openai.com/v1/images/generations",
         "-H", f"Authorization: Bearer {key}",
         "-H", "Content-Type: application/json",
         "-d", body],
        capture_output=True, text=True)
    if r.returncode != 0:
        raise RuntimeError(f"curl {r.returncode}: {r.stdout[:300]}{r.stderr[:200]}")
    return base64.b64decode(json.loads(r.stdout)["data"][0]["b64_json"])

def main():
    test = "--test" in sys.argv
    items = TREATMENTS[:3] if test else TREATMENTS
    OUT.mkdir(parents=True, exist_ok=True)
    key = api_key()

    def one(item):
        name, prompt = item
        dest = OUT / f"{name}.png"
        if dest.exists():
            return f"{name} exists, skipped"
        try:
            png = generate(key, prompt)
        except RuntimeError as e:
            return f"{name} FAILED: {e}"
        dest.write_bytes(png)
        return f"{name} ok ({len(png):,}b)"

    with concurrent.futures.ThreadPoolExecutor(max_workers=6) as ex:
        for res in ex.map(one, items):
            print(res, flush=True)

if __name__ == "__main__":
    main()
