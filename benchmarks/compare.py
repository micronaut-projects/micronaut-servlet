import re,sys
def parse(f):
    out={}; srv=None; ep=None
    for line in open(f):
        m=re.match(r'=== (\S+)',line)
        if m: srv=m.group(1); continue
        m=re.search(r'--- (\S+ \S+ c=\d+)',line)
        if m: ep=m.group(1); continue
        m=re.search(r'99%\s+([\d.]+)(ms|us|s)',line)
        if m and srv and ep: out.setdefault((srv,ep),{})['p99']=m.group(1)+m.group(2)
        m=re.search(r'Requests/sec:\s+([\d.]+)',line)
        if m and srv and ep: out.setdefault((srv,ep),{})['rps']=float(m.group(1))
    return out
if len(sys.argv)>1 and sys.argv[1]=='--ab':
    d=parse(sys.argv[2]); va,vb=sys.argv[3],sys.argv[4]
    servers=sorted({k[0].rsplit('-',2)[0] for k in d}); rounds=sorted({k[0].rsplit('-',1)[1] for k in d})
    for c in servers:
        for ep in ['GET /plaintext c=64','GET /json c=64','POST /echo c=64']:
            row=f'{c:9}{ep:22}'
            for r in rounds:
                a=d.get((f'{c}-{va}-{r}',ep),{}); b=d.get((f'{c}-{vb}-{r}',ep),{})
                row+=f"  {r}: A {a.get('rps',0):7.0f} {a.get('p99','?'):>8} | B {b.get('rps',0):7.0f} {b.get('p99','?'):>8} ({(b.get('rps',0)/max(a.get('rps',1),1)-1)*100:+4.0f}%)"
            print(row)
    sys.exit(0)
files=sys.argv[1:]
data=[parse(f) for f in files]
keys=[k for k in data[0]]
print(f"{'server':9}{'endpoint':24}"+''.join(f"{f.replace('results-','').replace('.txt',''):>22}" for f in files))
for k in keys:
    row=f"{k[0]:9}{k[1]:24}"
    for d in data:
        v=d.get(k)
        row+= f"{v['rps']:10.0f} {v.get('p99','?'):>10}" if v else f"{'-':>22}"
    print(row)
