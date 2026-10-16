#!/usr/bin/env python3
# Conservative token-level check after removing strings/comments. switch case is not a body keyword.
from pathlib import Path
import re
root=Path(__file__).resolve().parents[1]
errors=[]
for p in (root/'src').rglob('*.java'):
 if '/com/myname/' in str(p): continue
 text=p.read_text()
 text=re.sub(r'"(?:\\.|[^"\\])*"|\'(?:\\.|[^\'\\])*\'|//[^\n]*|/\*.*?\*/',' ',text,flags=re.S)
 tokens=re.findall(r'[A-Za-z_$][\w$]*|[^\s]',text)
 for i,t in enumerate(tokens):
  if t not in ('if','for','while','else','do'):continue
  j=i+1
  if t in ('if','for','while'):
   if j>=len(tokens) or tokens[j]!='(':continue
   level=1;j+=1
   while j<len(tokens) and level:
    level+=(tokens[j]=='(')-(tokens[j]==')');j+=1
  if j<len(tokens) and tokens[j]!='{' and not(t=='else' and tokens[j]=='if') and not(t=='while' and tokens[j]==';'):
   errors.append(str(p.relative_to(root))+': '+t+' missing braces')
if errors:raise SystemExit('\n'.join(errors))
print('SMOG_BRACES_PASS')
