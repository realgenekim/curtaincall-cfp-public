#!/usr/bin/env python3
"""Generate the demo headshot pool (bd 9ot): LinkedIn-style profile shots of
people who do not exist, one shared style spec so the wall reads as ONE
conference's speakers.

Key: runtime-loaded from ~/src.local/secrets/openai.edn {:key "..."} — never
an env var, never committed (house rule).

Usage:
  python3 bin/generate_headshots.py --test          # 3 faces, prove the pipe
  python3 bin/generate_headshots.py --pool          # the full 48-face pool
Output: resources/public/images/people/pNN.png (downscale/webp later).
"""
import argparse, base64, concurrent.futures, json, pathlib, re, subprocess, sys

SECRETS = pathlib.Path.home() / "src.local/secrets/openai.edn"
OUT = pathlib.Path(__file__).resolve().parent.parent / "resources/public/images/people"

STYLE = ("Professional LinkedIn-style profile headshot photograph, head and "
         "shoulders, soft natural window lighting, plain warm light-gray studio "
         "background, gentle confident expression, photorealistic, shallow depth "
         "of field, 85mm portrait lens look. The subject is a fictional person "
         "who does not exist: ")

# (filename-suffix, subject description) — varied deliberately across age,
# gender, ethnicity, and wardrobe. Extend to 48 for the full pool.
SUBJECTS = [
    ("01", "a woman in her late 50s with silver-streaked hair, wearing a navy blazer, executive presence"),
    ("02", "a South Asian man in his early 30s with glasses and a gray henley, engineer energy"),
    ("03", "a Black man in his 60s with a trimmed gray beard, charcoal suit, warm smile"),
    ("04", "an East Asian woman in her 40s with shoulder-length hair, forest-green sweater"),
    ("05", "a Latina woman in her late 20s with curly dark hair, denim jacket over white tee"),
    ("06", "a white man in his 50s, bald with stubble, black turtleneck"),
    ("07", "a Black woman in her 30s with braids, rust-colored blouse"),
    ("08", "an older white woman in her 70s with short white hair, pearl earrings, cardigan"),
    ("09", "a Middle Eastern man in his 40s with a short beard, light-blue oxford shirt"),
    ("10", "a young white man in his mid 20s with tousled brown hair, olive crewneck"),
    ("11", "an East Asian man in his 50s with wire-frame glasses, dark gray suit no tie"),
    ("12", "a South Asian woman in her 50s with a streak of gray, deep-teal kurta-style top"),
    ("13", "a Black man in his 30s with short locs, mustard sweater over collared shirt"),
    ("14", "a white woman in her 40s with auburn hair in a bob, cream blouse"),
    ("15", "a Latino man in his 60s with swept-back gray hair, brown blazer"),
    ("16", "a Southeast Asian woman in her early 30s with long straight hair, black blazer"),
    ("17", "a white man in his 70s with a neat white mustache, tweed jacket"),
    ("18", "a Black woman in her 50s with a short natural cut, burgundy jacket"),
    ("19", "a mixed-race man in his 20s with freckles and glasses, heather-gray hoodie"),
    ("20", "an East Asian woman in her 60s with chin-length gray hair, plum sweater"),
    ("21", "a South Asian man in his late 50s, clean-shaven, crisp white shirt"),
    ("22", "a white woman in her early 30s with blonde hair in a low bun, ochre blouse"),
    ("23", "a Middle Eastern woman in her 40s with wavy dark hair, slate-blue blazer"),
    ("24", "a Black man in his 40s, athletic build, quarter-zip pullover"),
    ("25", "a white man in his 30s with red hair and beard, chambray shirt"),
    ("26", "a Latina woman in her 60s with silver hair in a twist, emerald scarf"),
    ("27", "an East Asian man in his 20s with round glasses, navy cardigan over tee"),
    ("28", "a white woman in her 50s with curly gray-blonde hair, denim shirt"),
    ("29", "a South Asian woman in her 20s with a septum ring, black mock-neck"),
    ("30", "a Black woman in her 70s with silver close-crop, camel coat over blouse"),
    ("31", "a white man in his 40s with salt-and-pepper hair, charcoal v-neck"),
    ("32", "a Southeast Asian man in his 50s with a warm grin, batik-print shirt"),
    ("33", "an Indigenous woman in her 40s with long dark hair, turquoise pendant, gray blazer"),
    ("34", "a white nonbinary person in their 30s with a platinum buzzcut, dark green shirt"),
    ("35", "a Black man in his 50s with glasses and a gray goatee, brown cardigan"),
    ("36", "an East Asian woman in her 50s, elegant, cream turtleneck"),
    ("37", "a Latino man in his 30s with a fade haircut, light-gray blazer over tee"),
    ("38", "a white woman in her 60s with reading glasses on a chain, indigo blouse"),
    ("39", "a Middle Eastern man in his 20s with dark curls, olive utility jacket"),
    ("40", "a South Asian man in his 40s with a full beard and turban, steel-blue shirt"),
    ("41", "a Black woman in her 20s with a shaved head and gold hoops, white blouse"),
    ("42", "a white man in his 60s, heavyset with a friendly face, plaid flannel"),
    ("43", "an East Asian man in his 40s with a buzzcut, black band-collar shirt"),
    ("44", "a Latina woman in her 40s with glasses, coral blazer"),
    ("45", "a white woman in her 20s with dark hair and bangs, mustard turtleneck"),
    ("46", "a Black man in his 70s with white hair, houndstooth jacket, bolo tie"),
    ("47", "a mixed-race woman in her 30s with an afro, sage-green jumpsuit"),
    ("48", "a white man in his 50s with long gray hair in a ponytail, black denim jacket"),
]

def api_key():
    m = re.search(r':key\s+"([^"]+)"', SECRETS.read_text())
    if not m:
        sys.exit(f"no :key in {SECRETS}")
    return m.group(1)

def generate(key, model, prompt):
    body = json.dumps({"model": model, "prompt": prompt,
                       "size": "1024x1024", "quality": "low", "n": 1})
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
    ap = argparse.ArgumentParser()
    ap.add_argument("--test", action="store_true", help="first 3 subjects only")
    ap.add_argument("--pool", action="store_true", help="all subjects")
    ap.add_argument("--model", default="gpt-image-1")
    args = ap.parse_args()
    subjects = SUBJECTS[:3] if args.test or not args.pool else SUBJECTS

    OUT.mkdir(parents=True, exist_ok=True)
    key = api_key()

    def one(item):
        suffix, desc = item
        dest = OUT / f"p{suffix}.png"
        if dest.exists():
            return f"p{suffix} exists, skipped"
        try:
            png = generate(key, args.model, STYLE + desc)
        except RuntimeError as e:
            return f"p{suffix} FAILED: {e}"
        dest.write_bytes(png)
        return f"p{suffix} ok ({len(png):,}b)"

    with concurrent.futures.ThreadPoolExecutor(max_workers=6) as ex:
        for res in ex.map(one, subjects):
            print(res, flush=True)

if __name__ == "__main__":
    main()
