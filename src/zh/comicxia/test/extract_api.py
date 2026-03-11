import re

with open('temp.html', encoding='utf-8') as f:
    html = f.read()

# Look for fetching paths
urls = set(re.findall(r'/api/v1/comics[^\"]*', html))
urls.update(re.findall(r'/api/v1/[\w/]+', html))

for u in urls:
    print(u)
