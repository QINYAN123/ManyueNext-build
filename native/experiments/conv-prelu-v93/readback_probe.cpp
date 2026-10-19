// ncnn20241226 public APIs, adapted from upstream BSD-3-Clause record_clone paths.
// Standalone copy probe, NOT an inference/reader/energy benchmark.
#include <net.h>
#include <gpu.h>
#include <command.h>
#include <chrono>
#include <cstdio>
#include <cstdlib>
#include <cstring>
#include <vector>
#if _WIN32
#define WIN32_LEAN_AND_MEAN
#include <windows.h>
#else
#include <dlfcn.h>
#endif
static double now_ms(){return std::chrono::duration<double,std::milli>(std::chrono::steady_clock::now().time_since_epoch()).count();}
struct Pools {
 const ncnn::VulkanDevice* dev;ncnn::VkAllocator *blob,*stage;
 explicit Pools(const ncnn::VulkanDevice* d):dev(d),blob(d->acquire_blob_allocator()),stage(d->acquire_staging_allocator()){}
 ~Pools(){dev->reclaim_blob_allocator(blob);dev->reclaim_staging_allocator(stage);}
};
int main(int argc,char** argv){
 if(argc!=5){fprintf(stderr,"width height staged(0|1) repeats\n");return 2;}
 const int w=atoi(argv[1]),h=atoi(argv[2]),staged=atoi(argv[3]),n=atoi(argv[4]);
 if(w<1||w>4096||h<1||h>4096||staged<0||staged>1||n<1||n>100)return 3;
 if(ncnn::create_gpu_instance())return 4;
 int gpu=ncnn::get_default_gpu_index();
#if _WIN32
 for(int i=0;i<ncnn::get_gpu_count();i++)if(strstr(ncnn::get_gpu_info(i).device_name(),"NVIDIA"))gpu=i;
#endif
 if(gpu<0)return 5;
 {
 const ncnn::VulkanDevice* dev=ncnn::get_gpu_device(gpu);Pools pools(dev);
 ncnn::Option opt;opt.use_vulkan_compute=true;opt.blob_vkallocator=pools.blob;
 opt.workspace_vkallocator=pools.blob;opt.staging_vkallocator=pools.stage;opt.num_threads=1;
 // RealSR int8-output path: 2-D pixel array, elemsize RGB=3, elempack=1.
 ncnn::Mat input(w,h,(size_t)3u,1);
 const size_t bytes=(size_t)w*h*3;
 for(size_t i=0;i<bytes;i++)((unsigned char*)input.data)[i]=(unsigned char)((i*37+i/127+19)%256);
 ncnn::VkMat source;
 {ncnn::VkCompute cmd(dev);cmd.record_clone(input,source,opt);if(source.empty()||cmd.submit_and_wait())return 6;}
 std::vector<double> total,record,wait;
 for(int i=0;i<n+3;i++){
  double t=now_ms(),r=t,submitted;
  ncnn::Mat output;
  {
   ncnn::VkCompute cmd(dev);ncnn::VkMat staging;
   if(staged){ncnn::Option s=opt;s.blob_vkallocator=pools.stage;cmd.record_clone(source,staging,s);if(staging.empty())return 7;cmd.record_clone(staging,output,opt);}
   else cmd.record_clone(source,output,opt);
   submitted=now_ms();if(cmd.submit_and_wait())return 8;
   if(i>=3){record.push_back(submitted-r);wait.push_back(now_ms()-submitted);}
  }
  double elapsed=now_ms()-t;
  if(output.empty()||output.w!=w||output.h!=h||output.elemsize!=3u||memcmp(input.data,output.data,bytes))return 9;
  if(i>=3)total.push_back(elapsed);
 }
 // ncnn's global function pointer is not exported by the stock Windows DLL.
 // Query through the platform Vulkan loader without changing the ncnn ABI.
#if _WIN32
 HMODULE loader=LoadLibraryW(L"vulkan-1.dll");
 PFN_vkGetPhysicalDeviceMemoryProperties query=loader?(PFN_vkGetPhysicalDeviceMemoryProperties)GetProcAddress(loader,"vkGetPhysicalDeviceMemoryProperties"):0;
#else
 void* loader=dlopen("libvulkan.so",RTLD_NOW|RTLD_LOCAL);
 PFN_vkGetPhysicalDeviceMemoryProperties query=loader?(PFN_vkGetPhysicalDeviceMemoryProperties)dlsym(loader,"vkGetPhysicalDeviceMemoryProperties"):0;
#endif
 if(!query)return 10;
 VkPhysicalDeviceMemoryProperties props;
 query(dev->info.physical_device(),&props);
#if _WIN32
 FreeLibrary(loader);
#else
 dlclose(loader);
#endif
 uint32_t bi=pools.blob->buffer_memory_type_index,si=pools.stage->buffer_memory_type_index;
 uint32_t bf=bi<props.memoryTypeCount?props.memoryTypes[bi].propertyFlags:0;
 uint32_t sf=si<props.memoryTypeCount?props.memoryTypes[si].propertyFlags:0;
 printf("{\"schema\":1,\"scope\":\"isolated byte readback\",\"width\":%d,\"height\":%d,\"bytes\":%llu,\"staged\":%s,\"byteIdentical\":true,\"blobMappable\":%s,\"blobMemoryType\":%u,\"blobFlags\":%u,\"blobCached\":%s,\"stagingMemoryType\":%u,\"stagingFlags\":%u,\"stagingCached\":%s,\"samples\":[",w,h,(unsigned long long)bytes,staged?"true":"false",pools.blob->mappable?"true":"false",bi,bf,(bf&VK_MEMORY_PROPERTY_HOST_CACHED_BIT)?"true":"false",si,sf,(sf&VK_MEMORY_PROPERTY_HOST_CACHED_BIT)?"true":"false");
 for(size_t i=0;i<total.size();i++)printf("%s{\"wallMs\":%.6f,\"recordMs\":%.6f,\"submitWaitMs\":%.6f}",i?",":"",total[i],record[i],wait[i]);
 printf("]}\n");
 }
 ncnn::destroy_gpu_instance();return 0;
}
