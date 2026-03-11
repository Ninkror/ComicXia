import urllib.request, json
req = urllib.request.Request('https://www.comicxia.com/categories?category_id=6', headers={'User-Agent':'Mozilla/5.0'})
res = urllib.request.urlopen(req).read().decode('utf-8')
clean = res.replace('\\"', '"').replace('\\/', '/')

parts = clean.split('"total":')
for i, part in enumerate(parts):
    if i == 0: continue
    print(f"Match {i}")
    # The part preceding "total": might contain the comics list
    prev = parts[i-1]
    # Try finding the array
    idx = prev.rfind('[')
    if idx != -1:
        print("Preceding array ending snippet:", prev[idx:idx+100])
