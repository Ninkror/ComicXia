import re
import urllib.request
import json

req = urllib.request.Request('https://www.comicxia.com/categories', headers={'User-Agent': 'Mozilla/5.0'})
try:
    content = urllib.request.urlopen(req).read().decode('utf-8')
    content = content.replace('\\\"', '\"').replace('\\/', '/')
    
    cats = re.search(r'\"categories\":\[(.*?)\],\"tags\"', content)
    if cats: print("CATEGORIES:", cats.group(1)[:300])
    
    tags = re.search(r'\"tags\":\[(.*?)\],', content)
    if tags: print("TAGS:", tags.group(1)[:300])
    
    regs = re.search(r'\"regions\":\[(.*?)\],\"status\"', content)
    if regs: print("REGIONS:", regs.group(1)[:300])
    
    status = re.search(r'\"status\":\[(.*?)\],', content)
    if status: print("STATUS:", status.group(1)[:300])

except Exception as e:
    print(e)
