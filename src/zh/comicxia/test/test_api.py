import urllib.request, json
def test_api(query):
    url = f'https://www.comicxia.com/api/v1/comics?{query}'
    req = urllib.request.Request(url, headers={'User-Agent':'Mozilla/5.0'})
    try:
        res = urllib.request.urlopen(req).read()
        data = json.loads(res.decode('utf-8'))
        count = data.get('total', 0)
        print(f"{query.ljust(15)} => total:{count}")
    except Exception as e:
        print(f"{query} failed: {e}")

test_api('category=2')
test_api('tag=916')
