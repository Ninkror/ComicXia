import urllib.request, json, re
def test_page(query):
    req = urllib.request.Request(f'https://www.comicxia.com/categories?{query}', headers={'User-Agent':'Mozilla/5.0'})
    try:
        res = urllib.request.urlopen(req).read().decode('utf-8')
        m = re.search(r'"comics":(\[.*?\]),"total":(\d+)', res.replace('\\"', '"').replace('\\/', '/'))
        if m:
            comics = json.loads(m.group(1))
            first = comics[0]['title'] if comics else 'None'
            print(f"{query.ljust(30)} => total:{m.group(2)} first:{first}")
        else:
            print(f"No comics found for {query}")
    except Exception as e: print(e)

test_page('category_id=6')
test_page('category_id=2')
test_page('category_id=2&status=1')
test_page('status=2')
test_page('tag_id=916')
test_page('sort=updated')
