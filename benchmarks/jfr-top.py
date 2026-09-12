import sys,subprocess,collections,re
f=sys.argv[1]; event=sys.argv[2] if len(sys.argv)>2 else 'jdk.ExecutionSample'; depth=int(sys.argv[3]) if len(sys.argv)>3 else 1
out=subprocess.run(['jfr','print','--events',event,'--stack-depth','40',f],capture_output=True,text=True).stdout
top=collections.Counter(); leaf=collections.Counter(); n=0
for block in out.split('\n\n'):
    if 'stackTrace = [' not in block: continue
    frames=[l.strip() for l in block.split('stackTrace = [')[1].split(']')[0].split('\n') if l.strip() and not l.strip().startswith('...')]
    if not frames: continue
    n+=1
    leaf[re.sub(r'\s+line:.*','',frames[0])]+=1
    key=' <- '.join(re.sub(r'\s+line:.*','',x) for x in frames[:depth])
    top[key]+=1
print('samples',n)
for k,v in (top if depth>1 else leaf).most_common(int(sys.argv[4]) if len(sys.argv)>4 else 25): print(f'{v:6} {100*v/n:5.1f}%  {k}')
