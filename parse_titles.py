import json

with open("issues.json", "r") as f:
    issues = json.load(f)

with open("issue_titles.txt", "w") as f:
    for i in issues:
        f.write(f"{i['number']}: {i['title']}\n")
