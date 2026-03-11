import json

with open('../temp.html', encoding='utf-8') as f:
    text = f.read()

clean = text.replace('\\"', '"').replace('\\/', '/')
target = '"title"'

idx = clean.find(target)
while idx != -1:
    # Check if inside an array
    if clean.rfind('[', max(0, idx-500), idx) != -1:
        # Check if the array is preceded by a key in JSON format
        preceding = clean[max(0, idx-100):idx]
        print(f"Match found:\n...{preceding}{target}...")
        break
    idx = clean.find(target, idx + 1)
