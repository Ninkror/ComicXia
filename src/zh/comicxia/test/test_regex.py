import urllib.request, json, re

req = urllib.request.Request('https://www.comicxia.com/categories?category_id=6', headers={'User-Agent':'Mozilla/5.0'})
res = urllib.request.urlopen(req).read().decode('utf-8')
clean = res.replace('\\"', '"').replace('\\/', '/')

m = re.search(r'"([a-zA-Z0-9_]+)":(\[\{"id":\d+,"title":.*?\})', clean)
if m:
    print('FOUND KEY:', m.group(1))
    print('Snippet:', m.group(2)[:200])
else:
    print('Key not found using strict regex. Using loose search.')
    idx = clean.find('"title"')
    print(clean[max(0, idx-100):idx+100])
