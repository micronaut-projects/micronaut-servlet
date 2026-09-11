import sys,subprocess,collections,re
f=sys.argv[1]; depth=int(sys.argv[2]) if len(sys.argv)>2 else 2; n=int(sys.argv[3]) if len(sys.argv)>3 else 25
out=subprocess.run(['jfr','print','--events','jdk.ObjectAllocationSample','--stack-depth','30',f],capture_output=True,text=True).stdout
by=collections.Counter(); total=0
for block in out.split('\n\n'):
    if 'stackTrace = [' not in block: continue
    m=re.search(r'weight = ([\d.]+) (\w+)',block); 
    if not m: continue
    w=float(m.group(1)); unit=m.group(2); w*= {'bytes':1,'kB':1e3,'MB':1e6,'GB':1e9}.get(unit,1)
    cls=re.search(r'objectClass = (\S+)',block).group(1)
    frames=[re.sub(r'\s+line:.*','',l.strip()) for l in block.split('stackTrace = [')[1].split(']')[0].split('\n') if l.strip() and not l.strip().startswith('...')]
    key=cls+' @ '+' <- '.join(re.sub(r'\(.*','',x) for x in frames[:depth])
    by[key]+=w; total+=w
print(f'total sampled {total/1e6:.0f} MB')
for k,v in by.most_common(n): print(f'{v/1e6:8.0f} MB {100*v/total:5.1f}%  {k}')
