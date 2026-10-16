import sys,xml.etree.ElementTree as ET
t=ET.parse(sys.argv[1]).getroot()
print(t.attrib['tests'],'tests',t.attrib['failures'],'fail',t.attrib['errors'],'err',t.attrib['skipped'],'skip')
for c in t.findall('testcase'):
    f=c.find('failure')
    if f is None: f=c.find('error')
    if f is not None:
        m=(f.attrib.get('message') or '').replace('\n',' ')[:int(sys.argv[2]) if len(sys.argv)>2 else 230]
        print('FAIL',c.attrib['name'],'::',m)
