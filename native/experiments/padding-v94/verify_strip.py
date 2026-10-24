"""Prove llvm-strip retained every loadable segment's code/data bytes."""
import hashlib
import json
import struct
from pathlib import Path
H=Path(__file__).resolve().parent
a=(H/'pad-plain-arm64-prefix/lib/libncnn.so').read_bytes()
b=(H/'pad1-candidate-libncnn.so').read_bytes()
def segments(data):
    assert data[:6]==b'\x7fELF\x02\x01'
    off=struct.unpack_from('<Q',data,32)[0];size,n=struct.unpack_from('<HH',data,54)
    out=[]
    for i in range(n):
        kind,flags,offset,vaddr,paddr,filesz,memsz,align=struct.unpack_from('<IIQQQQQQ',data,off+i*size)
        if kind!=1:continue
        payload=bytearray(data[offset:offset+filesz])
        if offset==0:
            # Section-table location/count/name-index are not loaded program semantics.
            payload[40:48]=b'\0'*8;payload[58:64]=b'\0'*6
        out.append(dict(flags=flags,offset=offset,vaddr=vaddr,paddr=paddr,filesz=filesz,memsz=memsz,align=align,
            sha256=hashlib.sha256(payload).hexdigest()))
    return out
sa,sb=segments(a),segments(b)
assert sa==sb,'llvm-strip changed loadable segment code/data/layout'
report=dict(passed=True,sourceSha256=hashlib.sha256(a).hexdigest(),strippedSha256=hashlib.sha256(b).hexdigest(),
    sourceBytes=len(a),strippedBytes=len(b),normalizedElfHeaderBytes=['40:48 section offset','58:64 section table entry/count/name index'],loadSegments=sa)
(H/'strip-verification.json').write_text(json.dumps(report,indent=2)+'\n','utf-8')
print(json.dumps(dict(passed=True,loadSegments=len(sa),strippedBytes=len(b))))
