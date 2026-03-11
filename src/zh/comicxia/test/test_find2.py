import urllib.request, json
req = urllib.request.Request('https://www.comicxia.com/categories?category_id=6', headers={'User-Agent':'Mozilla/5.0'})
res = urllib.request.urlopen(req).read().decode('utf-8')
clean = res.replace('\\"', '"').replace('\\/', '/')

idx = clean.find('"title"')
while idx != -1:
    before = clean[max(0, idx-500):idx]
    if before.rfind('[') != -1:
        print(clean[max(0, idx-100):idx+50])
        break
    idx = clean.find('"title"', idx + 1)
