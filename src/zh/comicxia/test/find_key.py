import urllib.request, json, re
req = urllib.request.Request('https://www.comicxia.com/categories?category_id=1', headers={'User-Agent':'Mozilla/5.0'})
res = urllib.request.urlopen(req).read().decode('utf-8')
clean = res.replace('\\"', '"').replace('\\/', '/')

for m in re.finditer(r'\"([a-zA-Z0-9_]+)\":(\[\{.*?\}\])', clean):
    try:
        arr = json.loads(m.group(2))
        if isinstance(arr, list) and len(arr) > 0 and 'title' in arr[0] and 'id' in arr[0]:
            print('The key is:', m.group(1))
            break
    except: pass
