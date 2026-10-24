"""Recreate the pad-only candidate from pinned source, without diagnostics."""
import difflib
import hashlib
import json
import shutil
from pathlib import Path

H=Path(__file__).resolve().parent
OLD=H.parent/'native-profile-v93-20261018/pinned-source'
SRC=H/'pad-plain-source'
assert not SRC.exists(), 'Keep existing source/build for reproducibility'
shutil.copytree(OLD,SRC)
rel='src/layer/vulkan/convolution_vulkan.cpp'
s=(SRC/rel).read_text('utf-8')
s=s.replace('#include "convolution_vulkan.h"','#include "convolution_vulkan.h"\n#include <cstdlib>',1)
# Reuse exactly the reviewed host transformation, excluding its one-shot staging
# and timestamp setup. This is trusted local generator text, not external input.
generator=(H/'prepare_pad_host.py').read_text('utf-8')
host=generator[generator.index("start=s.index("):generator.index("(SRC/rel).write_text")]
context=dict(s=s)
exec(host,context)
(SRC/rel).write_text(context['s'],'utf-8',newline='\n')
shader='src/layer/vulkan/shader/convolution_pack4_3x3s1d1_winograd43_transform_input.comp'
shutil.copy2(H/'pad-shader'/Path(shader).name,SRC/shader)
patch=''
for r in (rel,shader):
    a=(OLD/r).read_text('utf-8');b=(SRC/r).read_text('utf-8')
    patch+=''.join(difflib.unified_diff(a.splitlines(True),b.splitlines(True),fromfile='a/'+r,tofile='b/'+r))
(H/'pad1-only.patch').write_text(patch,'utf-8',newline='\n')
assert (OLD/'src/command.cpp').read_bytes()==(SRC/'src/command.cpp').read_bytes()
assert 'MANYUE_STAGE_PROFILE' not in (SRC/rel).read_text('utf-8')
assert 'MANYUE_INPUT64' not in (SRC/rel).read_text('utf-8')
probe=(H.parent/'native-profile-v93-20261018/compute_probe.cpp').read_text('utf-8')
(H/'compute_plain_probe.cpp').write_text(probe,'utf-8',newline='\n')
print(json.dumps(dict(padOnly=True,diagnostic=False,patchSha256=hashlib.sha256(patch.encode()).hexdigest())))
