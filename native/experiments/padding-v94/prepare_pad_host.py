"""Prepare a separate pad-input fusion candidate; input64 experiment stays rejected."""
from pathlib import Path
import difflib
import json
import shutil
import subprocess

H=Path(__file__).resolve().parent
OLD=H.parent/'native-profile-v93-20261018/pinned-source'
SRC=H/'stage-source'
rel='src/layer/vulkan/convolution_vulkan.cpp'
# Freeze the completed previous binary before changing only this experiment tree.
assert not (H/'input64-rejected-libncnn.so').exists()
shutil.copy2(H/'stage-arm64-prefix/lib/libncnn.so',H/'input64-rejected-libncnn.so')
for path in [rel,'src/command.cpp']:
    shutil.copy2(OLD/path,SRC/path)
subprocess.run(['git','apply',str(H/'stage-timestamps.patch')],cwd=SRC,check=True)
s=(SRC/rel).read_text('utf-8')
start=s.index('                std::vector<vk_specialization_type> specializations(0 + 7);',s.index('// winograd43'))
end=s.index('                int shader_type_index = -1;',start)
block=s[start:end]
assert 'shape_bordered_packed.w' in block and 'shape_winograd_input_transformed_packed.cstep' in block
new='''                const char* pad1_env = std::getenv("MANYUE_FUSE_PAD1");
                const bool fuse_pad1 = pad1_env && pad1_env[0] == '1' && pad1_env[1] == 0
                    && vkdev->info.type() == 1 && vkdev->info.vendor_id() == 0x5143
                    && num_input == 64 && num_output == 64 && elempack == 4 && out_elempack == 4
                    && pad_left == 1 && pad_right == 1 && pad_top == 1 && pad_bottom == 1 && pad_value == 0.f
                    && !opt.use_image_storage && !opt.use_shader_local_memory
                    && opt.use_fp16_storage && !opt.use_fp16_arithmetic
                    && !vkdev->info.support_cooperative_matrix_16_8_8() && !vkdev->info.support_cooperative_matrix_16_16_16();
                Mat input_unbordered_packed;
                if (shape.dims == 3) input_unbordered_packed = Mat(shape.w, shape.h, num_input / elempack, (void*)0, elemsize, elempack);
                const Mat& input_read_shape = fuse_pad1 ? input_unbordered_packed : shape_bordered_packed;
                std::vector<vk_specialization_type> specializations(0 + 8);
                specializations[0 + 0].i = input_read_shape.w;
                specializations[0 + 1].i = input_read_shape.h;
                specializations[0 + 2].i = input_read_shape.c;
                specializations[0 + 3].i = input_read_shape.cstep;
                specializations[0 + 4].i = shape_winograd_input_transformed_packed.cstep;
                specializations[0 + 5].i = block_x;
                specializations[0 + 6].i = block_y;
                specializations[0 + 7].i = fuse_pad1 ? 1 : 0;
                if (fuse_pad1) NCNN_LOGE("MANYUE_FUSE_PAD1 active 64->64 pack4");

'''
s=s[:start]+new+s[end:]
start=s.index('int Convolution_vulkan::forward(const VkMat&')
end=s.index('int Convolution_vulkan::forward(const VkImageMat&',start)
f=s[start:end]
anchor='    // flattened blob, implement as InnerProduct'
guard='''    // Match the Winograd43 pipeline specialization; small inputs which select
    // Winograd23 still materialize padding and use its unchanged input shader.
    const char* pad1_env = std::getenv("MANYUE_FUSE_PAD1");
    const bool fuse_pad1 = pad1_env && pad1_env[0] == '1' && pad1_env[1] == 0
        && vkdev->info.type() == 1 && vkdev->info.vendor_id() == 0x5143
        && channels * elempack == 64 && num_output == 64 && elempack == 4 && !opt.use_shader_pack8
        && kernel_w == 3 && kernel_h == 3 && stride_w == 1 && stride_h == 1 && dilation_w == 1 && dilation_h == 1
        && pad_left == 1 && pad_right == 1 && pad_top == 1 && pad_bottom == 1 && pad_value == 0.f
        && opt.use_winograd_convolution && opt.use_winograd43_convolution
        && (!opt.use_winograd23_convolution || w + 2 > 12 || h + 2 > 12)
        && !opt.use_image_storage && !opt.use_shader_local_memory
        && opt.use_fp16_storage && !opt.use_fp16_arithmetic
        && !vkdev->info.support_cooperative_matrix_16_8_8() && !vkdev->info.support_cooperative_matrix_16_16_16();

'''
assert f.count(anchor)==1
f=f.replace(anchor,guard+anchor,1)
anchor='    if (pad_left > 0 || pad_right > 0 || pad_top > 0 || pad_bottom > 0)'
assert f.count(anchor)==1
f=f.replace(anchor,'    if (!fuse_pad1 && (pad_left > 0 || pad_right > 0 || pad_top > 0 || pad_bottom > 0))',1)
f=f.replace('    w = bottom_blob_bordered.w;\n    h = bottom_blob_bordered.h;',
    '    // Virtual bordered dimensions preserve output size and algorithm selection.\n    w = bottom_blob_bordered.w + (fuse_pad1 ? 2 : 0);\n    h = bottom_blob_bordered.h + (fuse_pad1 ? 2 : 0);',1)
s=s[:start]+f+s[end:]
(SRC/rel).write_text(s,'utf-8',newline='\n')
# The agent owns only shader generation; do not execute an unfinished file.
assert (H/'pad-shader/convolution_pack4_3x3s1d1_winograd43_transform_input.comp').is_file(),'Need completed audited shader first'
shutil.copy2(H/'pad-shader/convolution_pack4_3x3s1d1_winograd43_transform_input.comp',
    SRC/'src/layer/vulkan/shader/convolution_pack4_3x3s1d1_winograd43_transform_input.comp')
patch=''
for r in [rel,'src/command.cpp','src/layer/vulkan/shader/convolution_pack4_3x3s1d1_winograd43_transform_input.comp']:
    a=(OLD/r).read_text('utf-8');b=(SRC/r).read_text('utf-8')
    patch+=''.join(difflib.unified_diff(a.splitlines(True),b.splitlines(True),fromfile='a/'+r,tofile='b/'+r))
(H/'stage-plus-pad1.patch').write_text(patch,'utf-8',newline='\n')
print(json.dumps(dict(prepared=True,input64Enabled=False,onlyPad1Candidate=True)))
