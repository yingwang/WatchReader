# Write the listings in store-assets/listings-extra.json to Play in one edit, each with the
# en-US promo video and a copy of every en-US image (icon, feature graphic, phone and wear
# screenshots), since a new listing language starts with no images. Dry run by default;
# pass --commit to publish. Uses the helpers in ~/claude/telewise/tools/play_publish.py.
import json, os, sys, urllib.parse, urllib.request
sys.path.insert(0, os.path.expanduser('~/claude/telewise/tools'))
from play_publish import KEY_PATH, access_token, call, API, UPLOAD
PKG = 'com.watchreader'
listings = json.load(open(os.path.expanduser('~/claude/WatchReader/store-assets/listings-extra.json'), encoding='utf-8'))
commit = '--commit' in sys.argv
token = access_token(json.load(open(KEY_PATH)))
edit = call(token, 'POST', '%s/%s/edits' % (API, PKG), payload={})['id']
base = '%s/%s/edits/%s' % (API, PKG, edit)
src = call(token, 'GET', base + '/listings/en-US')
video = src.get('video')
print('edit', edit, 'video', video)
for l in listings:
    for f, cap in (('title', 30), ('shortDescription', 80), ('fullDescription', 4000)):
        n = len(l[f]); assert n <= cap, (l['language'], f, n)
    body = dict(l); body['video'] = video
    call(token, 'PUT', base + '/listings/' + urllib.parse.quote(l['language']), payload=body)
    print('%-7s title %2d short %2d full %4d' % (l['language'], len(l['title']), len(l['shortDescription']), len(l['fullDescription'])))
cache = {}
for t in ('icon', 'featureGraphic', 'phoneScreenshots', 'wearScreenshots'):
    imgs = call(token, 'GET', '%s/listings/en-US/%s' % (base, t)).get('images', [])
    cache[t] = [urllib.request.urlopen(i['url'] + '=s0').read() for i in imgs]
    print(t, len(cache[t]), 'images from en-US')
for l in listings:
    lang = urllib.parse.quote(l['language'])
    for t, blobs in cache.items():
        call(token, 'DELETE', '%s/listings/%s/%s' % (base, lang, t))
        for b in blobs:
            ctype = 'image/png' if b[:4] == b'\x89PNG' else 'image/jpeg'
            call(token, 'POST', '%s/%s/edits/%s/listings/%s/%s?uploadType=media' % (UPLOAD, PKG, edit, lang, t), body=b, content_type=ctype, length=len(b))
    print(l['language'], 'images done')
call(token, 'POST', base + ':validate')
print('validated')
if commit:
    try:
        call(token, 'POST', base + ':commit'); print('committed')
    except SystemExit as e:
        if 'changesNotSentForReview' not in str(e): raise
        call(token, 'POST', base + ':commit?changesNotSentForReview=true'); print('committed without auto-review; send for review in Console')
else:
    call(token, 'DELETE', base); print('dry run: edit discarded')
