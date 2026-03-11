import urllib.request, json, re

try:
    url = 'https://www.comicxia.com/categories'
    req = urllib.request.Request(url, headers={'User-Agent': 'Mozilla/5.0'})
    res = urllib.request.urlopen(req).read().decode('utf-8')
    
    blocks = re.findall(r'self\.__next_f\.push\(\[1,\"(.*?)\"\]\)', res, re.DOTALL)
    out = {}
    for b in blocks:
        clean = b.replace('\\"', '"').replace('\\/', '/')
        for key in ['categories', 'tags', 'regions', 'status']:
            m = re.search(f'"{key}":(\[{{.*?}}\])', clean)
            if m:
                try:
                    out[key] = json.loads(m.group(1))
                except: pass

    with open('filters.json', 'w', encoding='utf-8') as f:
        json.dump(out, f, ensure_ascii=False, indent=2)
except Exception as e:
    print(e)
