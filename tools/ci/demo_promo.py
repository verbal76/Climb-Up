"""Demo workflow (docs/DEMO.md): optional release messaging for one build. Replaces the promo screen's second and/or third line in
assets/demo/recipe.json on the CI checkout (empty argument = keep the recipe's line). Course data is untouched, so the courses' hashes still hold."""
import json, sys
p = 'assets/demo/recipe.json'
r = json.load(open(p, encoding='utf-8'))
line1, line2 = (sys.argv[1:] + ['', ''])[:2]
for key, val in (('line1', line1), ('line2', line2)):
    val = val.strip()
    if val:
        if len(val) > 60: sys.exit('promo ' + key + ' is longer than 60 characters')
        r['promo'][key] = val.upper()
json.dump(r, open(p, 'w', encoding='utf-8'), indent=2)
print('promo:', r['promo']['title'], '/', r['promo']['line1'], '/', r['promo']['line2'])
