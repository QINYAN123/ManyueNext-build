#include <gpu.h>
#include <cstdio>
int main(){
 if(ncnn::create_gpu_instance())return 1;
 int i=ncnn::get_default_gpu_index();if(i<0)return 2;
 const ncnn::GpuInfo& g=ncnn::get_gpu_info(i);
 printf("{\"type\":%d,\"subgroupSize\":%u,\"maxWorkgroupInvocations\":%u,\"maxWorkgroupX\":%u,\"maxWorkgroupY\":%u,\"maxWorkgroupZ\":%u,\"maxImage3D\":%u,\"maxImage2D\":%u,\"storageBufferNoL1\":%s,\"imageLoadZeroBug\":%s,\"cooperativeMatrix\":%s}\n",g.type(),g.subgroup_size(),g.max_workgroup_invocations(),g.max_workgroup_size_x(),g.max_workgroup_size_y(),g.max_workgroup_size_z(),g.max_image_dimension_3d(),g.max_image_dimension_2d(),g.bug_storage_buffer_no_l1()?"true":"false",g.bug_buffer_image_load_zero()?"true":"false",g.support_cooperative_matrix()?"true":"false");
 ncnn::destroy_gpu_instance();return 0;
}
