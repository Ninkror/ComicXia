import json

with open('test_rsc.py', 'r') as f: pass # placeholder just to run

import urllib.request, json
req = urllib.request.Request('https://www.comicxia.com/categories?category_id=2', headers={
    'User-Agent':'Mozilla/5.0',
})
res = urllib.request.urlopen(req).read().decode('utf-8')
clean = res.replace('\\"', '"').replace('\\/', '/')

idx = clean.find('妈妈朋友的儿子')
start_idx = clean.rfind('[', 0, idx)
end_idx = clean.find(']', idx)
print(clean[start_idx-20:start_idx])
