#!/usr/bin/env python3
import json,re,sys,zipfile
from pathlib import Path
source=Path(sys.argv[1]);root=Path(__file__).resolve().parents[1]/'docs';root.mkdir(exist_ok=True)
items=[]
with zipfile.ZipFile(source) as z:
 for name in sorted(z.namelist()):
  if not name.endswith('.java'):continue
  for n,line in enumerate(z.read(name).decode('utf-8',errors='replace').splitlines(),1):
   if re.search(r'Pollution\.(addPollution|removePollution|getPollution|hasPollution|migrate)|mutatePollution\(|furnaceAddPollutionOnUpdate\(',line):
    items.append(dict(source=name,line=n,code=line.strip()))
(root/'GT5U_CALLSITES.json').write_text(json.dumps(items,ensure_ascii=False,indent=2))
(root/'GT5U_CALLSITES_RU.md').write_text('# Найденные маршруты GT5U 5.09.54.229\n\nВсе Java-файлы официального sources JAR, включая встроенные аддоны и PollutionHelper. В списке есть объявления и внутренние вызовы. Сторонние JAR полного модпака отдельно не сканировались.\n\n| Исходник : строка | Ссылка / вызов |\n|---|---|\n'+'\n'.join('| `'+i['source']+':'+str(i['line'])+'` | `'+i['code'].replace('|','\\|')+'` |' for i in items)+'\n')
print(len(items),'references')
