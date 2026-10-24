"""Make diagnostic query availability fail closed; preserve arithmetic/submits."""
from pathlib import Path
import difflib
import hashlib
import json

H = Path(__file__).resolve().parent
OLD = H.parent / 'native-profile-v93-20261018/pinned-source'
p = H / 'stage-source/src/command.cpp'
s = p.read_text('utf-8')
anchor = '    if (ret != VK_SUCCESS && ret != VK_NOT_READY)'
assert s.count(anchor) == 1
s = s.replace(anchor, '    // Profile-only: a completed fence does not make an unwritten query valid.\n    if (ret != VK_SUCCESS)', 1)
p.write_text(s, 'utf-8', newline='\n')
p = H / 'compute_stage_probe.cpp'
s = p.read_text('utf-8')
start = s.index('                auto readStages=')
end = s.index('                ncnn::VkMat in_gpu;', start)
s = s[:start] + '''                auto readStages=[&](int layerIndex)->bool{
                    const int q=84+8*net.layers()[layerIndex]->tops[0];
                    if(q<84 || q+8>queryCount) return false;
                    std::vector<uint64_t> values(queryCount,0);
                    // Matching diagnostic library rejects VK_NOT_READY; never WAIT on unwritten slots.
                    if(cmd.get_query_pool_results(q,8,values) || cmd.get_query_pool_results(2*layerIndex,2,values)) return false;
                    const double whole=durationGpu(values,2*layerIndex);
                    StageSample stage={layerIndex,durationGpu(values,q),durationGpu(values,q+2),
                        durationGpu(values,q+4),durationGpu(values,q+6),whole};
                    if(stage.pad<0 || stage.input<0 || stage.gemm<0 || stage.output<0 || whole<=0 || whole>10000) return false;
                    const double tolerance=period*2./1000000.;
                    double previous=0.;
                    for(int j=0;j<8;j++) {
                        const double offset=double((values[q+j]-values[2*layerIndex])&mask)*period/1000000.;
                        if(offset+tolerance<previous || offset>whole+tolerance) return false;
                        previous=offset;
                    }
                    if(stage.pad+stage.input+stage.gemm+stage.output>whole+tolerance) return false;
                    sample.stages.push_back(stage);
                    return true;
                };
''' + s[end:]
s = s.replace('!readStages(2+2*boundaryIndex,cg)', '!readStages(2+2*boundaryIndex)')
s = s.replace('!readStages(34,durationGpu(ticks,68))', '!readStages(34)')
p.write_text(s, 'utf-8', newline='\n')
patch = ''
for rel in ['src/layer/vulkan/convolution_vulkan.cpp', 'src/command.cpp']:
    a=(OLD/rel).read_text('utf-8');b=(H/'stage-source'/rel).read_text('utf-8')
    patch += ''.join(difflib.unified_diff(a.splitlines(True),b.splitlines(True),fromfile='a/'+rel,tofile='b/'+rel))
(H/'stage-timestamps.patch').write_text(patch,'utf-8',newline='\n')
m=json.loads((H/'stage-manifest.json').read_text('utf-8'))
m.update(patchSha256=hashlib.sha256(patch.encode()).hexdigest(),availability='VK_SUCCESS required; no WAIT',
    ordering='relative to whole convolution; two timestamp ticks tolerance')
(H/'stage-manifest.json').write_text(json.dumps(m,indent=2)+'\n','utf-8')
print('Diagnostic availability and interval ordering tightened')
