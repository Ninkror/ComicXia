import urllib.request, json
def test_api(query):
    url = f'https://www.comicxia.com/api/v1/comics?{query}'
    req = urllib.request.Request(url, headers={'User-Agent':'Mozilla/5.0'})
    try:
        res = urllib.request.urlopen(req).read()
        data = json.loads(res.decode('utf-8'))
        print(f"{query.ljust(30)} => total: {data.get('total')}")
    except Exception as e:
        print(f"{query} failed: {e}")

test_api('limit=2')
test_api('limit=2&category_id=6')
test_api('limit=2&category_id=2')
test_api('limit=2&sort=updated') 
test_api('limit=2&sort=view')
test_api('limit=2&sort=created')
test_api('limit=2&tag_id=2760')
test_api('limit=2&tag_id=916')
test_api('limit=2&word_count_min=1000')
test_api('limit=2&is_end=1')
test_api('limit=2&is_end=0')
test_api('limit=2&status=1')
test_api('limit=2&status=0')
