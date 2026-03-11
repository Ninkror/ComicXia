import urllib.request, json, re
req = urllib.request.Request('https://www.comicxia.com/categories?category_id=1', headers={'User-Agent':'Mozilla/5.0'})
res = urllib.request.urlopen(req).read().decode('utf-8')
clean = res.replace('\\"', '"').replace('\\/', '/')
# find all arrays with objects inside the __next_f strings that have "title" or "id"
for m in re.finditer(r'\[(\{.*?\})\]', clean):
    try:
        arr = json.loads('[' + m.group(1) + ']')
        if isinstance(arr, list) and len(arr) > 0 and 'title' in arr[0] and 'id' in arr[0]:
            print('Found array of comics! First title:', arr[0]['title'])
            print('Keys in array item:', arr[0].keys())
            break
    except:
        pass
