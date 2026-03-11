import urllib.request, json
req = urllib.request.Request('https://www.comicxia.com/categories?category_id=2&sort=view&page=2', headers={
    'User-Agent':'Mozilla/5.0',
    'RSC': '1',
    'Next-Router-State-Tree': '%5B%22%22%2C%7B%22children%22%3A%5B%22categories%22%2C%7B%22children%22%3A%5B%22__PAGE__%22%2C%7B%7D%5D%7D%5D%7D%2Cnull%2Cnull%2Ctrue%5D'
})
try:
    res = urllib.request.urlopen(req).read().decode('utf-8')
    print('Payload size:', len(res))
    idx = res.find('妈妈朋友的儿子')
    # look around it to see formatting
    if idx != -1:
        print('Found comic:', res[idx-20:idx+20])
    else: 
        print(res[:200])
except Exception as e: print(e)
