// Isolated ncnn20241226 investigation, adapted from the existing v83 storage_probe.
// Uses upstream ncnn BSD-3-Clause APIs; no model or arithmetic modifications.
#include <net.h>
#include <gpu.h>
#include <command.h>
#include <chrono>
#include <cstdio>
#include <cstdlib>
#include <ctime>
#include <vector>
#include <cstring>
#include <dlfcn.h>
#if _WIN32
#define WIN32_LEAN_AND_MEAN
#include <windows.h>
#endif

static const char* boundaries[] = {"56", "59", "62", "65", "68", "71", "74", "77",
    "80", "83", "86", "89", "92", "95", "98", "101"};
static double wall_ms() {
    return std::chrono::duration<double, std::milli>(std::chrono::steady_clock::now().time_since_epoch()).count();
}
static double cpu_ms() {
#if _WIN32
    FILETIME create,exit,kernel,user;
    if (!GetThreadTimes(GetCurrentThread(),&create,&exit,&kernel,&user)) return -1;
    ULARGE_INTEGER k,u;
    k.LowPart=kernel.dwLowDateTime; k.HighPart=kernel.dwHighDateTime;
    u.LowPart=user.dwLowDateTime; u.HighPart=user.dwHighDateTime;
    return (k.QuadPart+u.QuadPart)/10000.;
#else
    struct timespec ts;
    if (clock_gettime(CLOCK_THREAD_CPUTIME_ID, &ts)) return -1;
    return ts.tv_sec * 1000. + ts.tv_nsec / 1000000.;
#endif
}
struct Sample {
    double wall, cpu, record, wait, reset, tail;
    double gpu, gpuTail, convGpu, preluGpu;
};
struct Pools {
    const ncnn::VulkanDevice* device;
    ncnn::VkAllocator* blob;
    ncnn::VkAllocator* staging;
    explicit Pools(const ncnn::VulkanDevice* d):device(d),blob(d->acquire_blob_allocator()),staging(d->acquire_staging_allocator()) {}
    ~Pools() { device->reclaim_blob_allocator(blob); device->reclaim_staging_allocator(staging); }
};
int main(int argc, char** argv) {
    if (argc != 10) {
        fprintf(stderr, "param bin input.f32 width height variant(0=noLocal,1=baseline,2=pack8) repeats output.f32 gpu\n");
        return 2;
    }
    const int w=atoi(argv[4]), h=atoi(argv[5]), local=atoi(argv[6]), repeats=atoi(argv[7]);
    int gpu=atoi(argv[9]);
    if (w<1 || h<1 || w>1024 || h>1024 || repeats<1 || repeats>30 || local<0 || local>2) return 3;
    ncnn::create_gpu_instance();
    if (gpu==-1) {
        gpu=ncnn::get_default_gpu_index();
#if _WIN32
        for (int i=0;i<ncnn::get_gpu_count();i++)
            if (std::strstr(ncnn::get_gpu_info(i).device_name(),"NVIDIA")) gpu=i;
#endif
    }
    if (gpu<0 || gpu>=ncnn::get_gpu_count()) return 4;
    const ncnn::GpuInfo& gi=ncnn::get_gpu_info(gpu);
    void* vkloader=dlopen("libvulkan.so",RTLD_NOW|RTLD_LOCAL);
    if(!vkloader)return 20;
    PFN_vkGetPhysicalDeviceProperties propsFn=(PFN_vkGetPhysicalDeviceProperties)dlsym(vkloader,"vkGetPhysicalDeviceProperties");
    PFN_vkGetPhysicalDeviceQueueFamilyProperties queuesFn=(PFN_vkGetPhysicalDeviceQueueFamilyProperties)dlsym(vkloader,"vkGetPhysicalDeviceQueueFamilyProperties");
    if(!propsFn||!queuesFn)return 21;
    VkPhysicalDeviceProperties props;propsFn(gi.physical_device(),&props);
    uint32_t count=0;queuesFn(gi.physical_device(),&count,0);
    std::vector<VkQueueFamilyProperties> queues(count);queuesFn(gi.physical_device(),&count,queues.data());
    if(gi.compute_queue_family_index()>=count)return 22;
    const uint32_t validBits=queues[gi.compute_queue_family_index()].timestampValidBits;
    const double period=gi.timestamp_period();
    if(!props.limits.timestampComputeAndGraphics||!validBits||validBits>64||!(period>0))return 23;
    const uint64_t mask=validBits==64?~uint64_t(0):(uint64_t(1)<<validBits)-1;
    auto durationGpu=[&](const std::vector<uint64_t>& q,int first)->double{
        if(q[first]==0||q[first+1]==0)return -1.;
        return double((q[first+1]-q[first])&mask)*period/1000000.;
    };
    dlclose(vkloader);
    fprintf(stderr,"probe phase=net_create\n");
    {
        ncnn::Net net;
        net.opt.use_vulkan_compute=true;
        net.opt.num_threads=1;
        net.opt.use_fp16_packed=true;
        net.opt.use_fp16_storage=true;
        net.opt.use_fp16_arithmetic=false;
        net.opt.use_int8_storage=true;
        net.opt.use_image_storage=false;
        net.opt.use_shader_local_memory=local!=0;
        net.opt.use_shader_pack8=local==2;
#if _WIN32
        // Same host guard used by v83; Adreno target capabilities differ from NVIDIA.
        net.opt.use_cooperative_matrix=false;
#endif
        net.set_vulkan_device(gpu);
        fprintf(stderr,"probe phase=load_param\n");
        double load=wall_ms();
        if (net.load_param(argv[1])) return 5;
        fprintf(stderr,"probe phase=load_model\n");
        if (net.load_model(argv[2])) return 5;
        fprintf(stderr,"probe phase=input\n");
        load=wall_ms()-load;
        // Direct VkMat APIs require the caller-owned pools (as RealSR::process does).
        // Unlike Mat extraction, they do not implicitly acquire staging/blob allocators.
        Pools pools(net.vulkan_device());
        net.opt.blob_vkallocator=pools.blob;
        net.opt.workspace_vkallocator=pools.blob;
        net.opt.staging_vkallocator=pools.staging;
        ncnn::Mat input(w,h,3);
        FILE* f=fopen(argv[3],"rb");
        if (!f) return 6;
        bool valid=true;
        for (int c=0;c<3;c++) if (fread(input.channel(c),sizeof(float),size_t(w)*h,f)!=size_t(w)*h) valid=false;
        if (fgetc(f)!=EOF) valid=false;
        fclose(f);
        if (!valid) return 7;
        std::vector<Sample> samples;
        ncnn::Mat output;
        for (int iteration=0;iteration<repeats+2;iteration++) {
            fprintf(stderr,"probe phase=iteration index=%d\n",iteration);
            Sample sample={};
            double start=wall_ms(), cpuStart=cpu_ms();
            {
                ncnn::Extractor ex=net.create_extractor();
                ncnn::VkCompute cmd(net.vulkan_device());
                if(cmd.create_query_pool(84))return 24;
                ncnn::VkMat in_gpu;
                cmd.record_upload(input,in_gpu,net.opt);
                if (ex.input("data",in_gpu)) return 8;
                int boundaryIndex=0;
                for (const char* boundaryName:boundaries) {
                    cmd.record_write_timestamp(82);
                    ncnn::VkMat boundary;
                    double t=wall_ms();
                    int ret=ex.extract(boundaryName,boundary,cmd);
                    cmd.record_write_timestamp(83);
                    sample.record+=wall_ms()-t;
                    if (ret || boundary.empty()) return 9;
                    t=wall_ms();
                    ret=cmd.submit_and_wait();
                    sample.wait+=wall_ms()-t;
                    if (ret) return 10;
                    std::vector<uint64_t> ticks(84,0);
                    if(cmd.get_query_pool_results(82,2,ticks)||cmd.get_query_pool_results(4+4*boundaryIndex,4,ticks))return 25;
                    double g=durationGpu(ticks,82),cg=durationGpu(ticks,4+4*boundaryIndex),pg=durationGpu(ticks,6+4*boundaryIndex);
                    if(g<0||cg<0||pg<0)return 26;
                    sample.gpu+=g;sample.convGpu+=cg;sample.preluGpu+=pg;
                    boundaryIndex++;
                    t=wall_ms();
                    ret=cmd.reset();
                    sample.reset+=wall_ms()-t;
                    if (ret) return 11;
                }
                double tailStart=wall_ms();
                ncnn::VkMat out_gpu;
                cmd.record_write_timestamp(82);
                if (ex.extract("output",out_gpu,cmd)) return 12;
                cmd.record_write_timestamp(83);
                cmd.record_download(out_gpu,output,net.opt);
                if (cmd.submit_and_wait()) return 13;
                sample.tail=wall_ms()-tailStart;
                std::vector<uint64_t> ticks(84,0);
                if(cmd.get_query_pool_results(82,2,ticks))return 27;
                sample.gpuTail=durationGpu(ticks,82);if(sample.gpuTail<0)return 28;
            }
            sample.wall=wall_ms()-start;
            sample.cpu=cpu_ms()-cpuStart;
            if (iteration>=2) samples.push_back(sample);
        }
        if (output.w!=w*2 || output.h!=h*2 || output.c!=3 || output.elempack!=1 || output.elemsize!=4) return 14;
        f=fopen(argv[8],"wb"); if (!f) return 15;
        bool written=true;
        for (int c=0;c<3;c++) if (fwrite(output.channel(c),sizeof(float),size_t(output.w)*output.h,f)!=size_t(output.w)*output.h) written=false;
        if (fclose(f)) written=false;
        if (!written) return 16;
        printf("{\"schema\":1,\"localMemory\":%s,\"imageStorage\":false,\"fp16Arithmetic\":false,\"width\":%d,\"height\":%d,\"loadMs\":%.6f,\"effectiveLocalMemory\":%s,\"effectivePack8\":%s,\"samples\":[",
            local?"true":"false",w,h,load,net.opt.use_shader_local_memory?"true":"false",net.opt.use_shader_pack8?"true":"false");
        for (size_t i=0;i<samples.size();i++) {
            const Sample& s=samples[i];
            printf("%s{\"wallMs\":%.6f,\"threadCpuMs\":%.6f,\"recordMs\":%.6f,\"submitWaitMs\":%.6f,\"resetMs\":%.6f,\"tailMs\":%.6f,\"gpuChunksMs\":%.6f,\"gpuTailNetworkMs\":%.6f,\"convGpuChunksMs\":%.6f,\"preluGpuChunksMs\":%.6f}",
                i?",":"",s.wall,s.cpu,s.record,s.wait,s.reset,s.tail,s.gpu,s.gpuTail,s.convGpu,s.preluGpu);
        }
        printf("],\"gpuTimestampPeriodNs\":%.6f,\"gpuTimestampValidBits\":%u,\"profileOnly\":true}\n",period,validBits);
    }
    ncnn::destroy_gpu_instance();
    return 0;
}
