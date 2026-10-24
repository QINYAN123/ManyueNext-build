"""Prepare isolated diagnostic source from pristine ncnn; never edits app assets."""
from pathlib import Path
import difflib
import hashlib
import json
import shutil

H = Path(__file__).resolve().parent
OLD = H.parent / 'native-profile-v93-20261018'
SRC = H / 'stage-source'
assert not SRC.exists(), 'Preparation is one-shot; inspect existing experiment instead'
shutil.copytree(OLD / 'pinned-source', SRC)
p = SRC / 'src/layer/vulkan/convolution_vulkan.cpp'
original = p.read_text('utf-8')
s = original
s = s.replace('#include "convolution_vulkan.h"', '#include "convolution_vulkan.h"\n#include <cstdlib>', 1)
start = s.index('int Convolution_vulkan::forward(const VkMat&')
end = s.index('int Convolution_vulkan::forward(const VkImageMat&', start)
buffer = s[start:end]
anchor = '    // flattened blob, implement as InnerProduct'
guard = '''#if NCNN_BENCHMARK
    // Diagnostic-only: the fixed 41-layer/42-blob probe reserves eight slots per
    // top blob after upstream queries 0..81 and outer interval 82/83.
    // Distinct slots keep the last middle convolution in the tail unambiguous.
    const char* stage_env = std::getenv("MANYUE_STAGE_PROFILE");
    const bool stage_profile = stage_env && stage_env[0] == '1' && stage_env[1] == 0
        && channels * elempack == 64 && num_output == 64
        && kernel_w == 3 && kernel_h == 3 && stride_w == 1 && stride_h == 1
        && dilation_w == 1 && dilation_h == 1
        && pad_left == 1 && pad_right == 1 && pad_top == 1 && pad_bottom == 1
        && opt.use_winograd_convolution && (opt.use_winograd23_convolution || opt.use_winograd43_convolution)
        && !opt.use_image_storage && tops.size() == 1 && tops[0] >= 0 && tops[0] < 42;
    const uint32_t stage_query = stage_profile ? 84 + 8 * tops[0] : 0;
#endif

'''
assert buffer.count(anchor) == 1
buffer = buffer.replace(anchor, guard + anchor, 1)
anchor = '    VkMat bottom_blob_bordered = bottom_blob;'
buffer = buffer.replace(anchor, '''#if NCNN_BENCHMARK
    if (stage_profile) cmd.record_write_timestamp(stage_query);
#endif
''' + anchor, 1)
anchor = '    w = bottom_blob_bordered.w;'
buffer = buffer.replace(anchor, '''#if NCNN_BENCHMARK
    if (stage_profile) cmd.record_write_timestamp(stage_query + 1);
#endif
''' + anchor, 1)
for variant in ('43', '23'):
    for stage, offset in [('transform_input', 2), ('gemm', 4), ('transform_output', 6)]:
        line = f'                cmd.record_pipeline(pipeline_convolution_3x3s1d1_winograd{variant}_{stage}, bindings, constants, dispatcher);'
        assert buffer.count(line) == 1
        buffer = buffer.replace(line, f'''#if NCNN_BENCHMARK
                if (stage_profile) cmd.record_write_timestamp(stage_query + {offset});
#endif
{line}
#if NCNN_BENCHMARK
                if (stage_profile) cmd.record_write_timestamp(stage_query + {offset + 1});
#endif''', 1)
s = s[:start] + buffer + s[end:]
p.write_text(s, 'utf-8', newline='\n')
patch = ''.join(difflib.unified_diff(original.splitlines(True), s.splitlines(True),
    fromfile='a/src/layer/vulkan/convolution_vulkan.cpp', tofile='b/src/layer/vulkan/convolution_vulkan.cpp'))
(H / 'stage-timestamps.patch').write_text(patch, 'utf-8', newline='\n')

s = (OLD / 'compute_timestamp_probe.cpp').read_text('utf-8')
s = s.replace('struct Sample {', 'struct StageSample { int layer; double pad, input, gemm, output, whole; };\nstruct Sample {', 1)
s = s.replace('double gpu, gpuTail, convGpu, preluGpu;',
              'double gpu, gpuTail, convGpu, preluGpu;\n    std::vector<StageSample> stages;', 1)
anchor = '        fprintf(stderr,"probe phase=input\\n");'
insert = '''        if(net.layers().size()!=41 || net.blobs().size()!=42) return 30;
        for(int i=0;i<17;i++) {
            const ncnn::Layer* layer=net.layers()[2+2*i];
            if(layer->type!="Convolution" || layer->tops.size()!=1 || layer->tops[0]<0 || layer->tops[0]>=42) return 31;
        }
        const char* stageEnv=std::getenv("MANYUE_STAGE_PROFILE");
        const bool stageEnabled=stageEnv && std::strcmp(stageEnv,"1")==0;
        const int queryCount=84+8*net.blobs().size();
'''
assert s.count(anchor) == 1
s = s.replace(anchor, insert + anchor, 1)
s = s.replace('if(cmd.create_query_pool(84))return 24;', 'if(cmd.create_query_pool(queryCount))return 24;', 1)
s = s.replace('std::vector<uint64_t> ticks(84,0);', 'std::vector<uint64_t> ticks(queryCount,0);')
anchor = '                ncnn::VkMat in_gpu;'
insert = '''                auto readStages=[&](int layerIndex,double whole)->bool{
                    const int q=84+8*net.layers()[layerIndex]->tops[0];
                    if(q<84 || q+8>queryCount) return false;
                    std::vector<uint64_t> values(queryCount,0);
                    if(cmd.get_query_pool_results(q,8,values)) return false;
                    StageSample stage={layerIndex,durationGpu(values,q),durationGpu(values,q+2),
                        durationGpu(values,q+4),durationGpu(values,q+6),whole};
                    if(stage.pad<0 || stage.input<0 || stage.gemm<0 || stage.output<0 || whole<=0) return false;
                    if(stage.pad+stage.input+stage.gemm+stage.output>whole*1.05) return false;
                    sample.stages.push_back(stage);
                    return true;
                };
'''
assert s.count(anchor) == 1
s = s.replace(anchor, insert + anchor, 1)
s = s.replace('                    boundaryIndex++;',
    '                    if(stageEnabled && boundaryIndex>0 && !readStages(2+2*boundaryIndex,cg)) return 32;\n                    boundaryIndex++;', 1)
anchor = '                sample.gpuTail=durationGpu(ticks,82);if(sample.gpuTail<0)return 28;'
s = s.replace(anchor, anchor + '''
                if(stageEnabled) {
                    if(cmd.get_query_pool_results(68,2,ticks)) return 33;
                    if(!readStages(34,durationGpu(ticks,68))) return 34;
                    if(sample.stages.size()!=16) return 35;
                }''', 1)
anchor = 's.wall,s.cpu,s.record,s.wait,s.reset,s.tail,s.gpu,s.gpuTail,s.convGpu,s.preluGpu);'
assert s.count(anchor) == 1
# Existing sample JSON closes after preluGpu; reopen its last brace for stages.
s = s.replace('\\"preluGpuChunksMs\\":%.6f}', '\\"preluGpuChunksMs\\":%.6f', 1)
s = s.replace(anchor, anchor + '''
            printf(",\\"stages\\":[");
            for(size_t k=0;k<s.stages.size();k++) {
                const StageSample& v=s.stages[k];
                printf("%s{\\"layer\\":%d,\\"padGpuMs\\":%.6f,\\"inputGpuMs\\":%.6f,\\"gemmGpuMs\\":%.6f,\\"outputGpuMs\\":%.6f,\\"wholeConvGpuMs\\":%.6f}",
                    k?",":"",v.layer,v.pad,v.input,v.gemm,v.output,v.whole);
            }
            printf("]}");''', 1)
s = s.replace('\\"profileOnly\\":true}\\n",period,validBits);',
    '\\"profileOnly\\":true,\\"stageEnabled\\":%s}\\n",period,validBits,stageEnabled?"true":"false");', 1)
(H / 'compute_stage_probe.cpp').write_text(s, 'utf-8', newline='\n')
(H / 'stage-manifest.json').write_text(json.dumps(dict(
    defaultEnabled=False, requiresEnv='MANYUE_STAGE_PROFILE=1', queryCount=420,
    patchSha256=hashlib.sha256(patch.encode()).hexdigest(),
    sourceArchiveSha256='b2145f7b01422f5ee66777d1c2c29de935191ed39865193951e1a7228202a6c1',
    runtimeChanged=False), indent=2)+'\n', 'utf-8')
print('Prepared isolated stage timestamps; no shader or model change')
