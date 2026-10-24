"""Isolated production worker A/B; exact lossless output and loading proof."""
import concurrent.futures
import json
import statistics
import struct
import subprocess
import sys
import time
from pathlib import Path
import numpy as np
from PIL import Image

H=Path(__file__).resolve().parent
R=H.parents[2]
sys.path.insert(0,str(H.parent/'native-profile-v93-20261018'))
import run_host as base
ADB=Path('C:/Users/Administrator/Documents/Codex/2026-09-19/ba/work/toolchains/android-sdk/platform-tools/adb.exe')
JNI=R/'work/mihon-source/app/src/main/jniLibs/arm64-v8a'
ASSETS=R/'work/mihon-source/app/src/main/assets/ai'
KEYS=('kind','status','requestId','initUs','decodeUs','inferUs','resizeUs','encodeUs','totalUs','outputBytes','width','height','backend','tileSize','modelLoads')

class Reader:
    def __init__(self,stream):self.stream=stream;self.pending=b''
    def read(self,size):
        result=bytearray()
        while len(result)<size:
            byte=self.pending or self.stream.read(1);self.pending=b''
            if not byte:break
            if byte==b'\r':
                following=self.stream.read(1)
                if following==b'\n':byte=b'\n'
                else:self.pending=following
            result.extend(byte)
        return bytes(result)

def exact(stream,size):
    out=bytearray()
    while len(out)<size:
        chunk=stream.read(size-len(out))
        if not chunk:raise EOFError('Native response ended')
        out.extend(chunk)
    return bytes(out)

def receive(stream):
    header=exact(stream,8);assert header[:4]==b'MNYR' and struct.unpack('<I',header[4:])[0]==92
    return dict(zip(KEYS,struct.unpack('<IiQQQQQQQQIIIII',exact(stream,92))))

def send(stream,job,width,source,dest):
    a=source.encode();b=dest.encode();payload=struct.pack('<QIII',job,width,len(a),len(b))+a+b
    stream.write(b'MNYQ'+struct.pack('<I',len(payload))+payload);stream.flush()

def main():
    def raw(*args,timeout=60):
        try:p=subprocess.run([str(ADB),*map(str,args)],capture_output=True,timeout=timeout)
        except subprocess.TimeoutExpired:raise RuntimeError('ADB timeout; identifier suppressed') from None
        if p.returncode:raise RuntimeError(f'ADB failed {p.returncode}; identifier suppressed')
        return p.stdout
    online=[x.split()[0] for x in raw('devices').decode().splitlines()[1:] if len(x.split())>=2 and x.split()[1]=='device'];assert len(online)==1
    def adb(*args,**kw):return raw('-s',online[0],*args,**kw)
    def idle():
        top=[x for x in adb('shell','dumpsys','activity','activities').decode(errors='replace').splitlines() if 'topResumedActivity=' in x]
        assert len(top)==1 and ('com.hihonor.android.launcher/' in top[0] or ('app.mihon.benchmark/' in top[0] and 'MainActivity' in top[0])),'Screen must be idle'
        names=adb('shell','ps','-A','-o','NAME').decode(errors='replace')
        assert not any(x in names for x in ('libmanyue_realesr','compute_probe','compute_stage_probe')),'Worker already active'
        return 'launcher' if 'com.hihonor.android.launcher/' in top[0] else 'Main'
    def resource():
        t=adb('shell','dumpsys','thermalservice').decode(errors='replace');b=adb('shell','dumpsys','battery').decode(errors='replace')
        return dict(thermal=[x.strip() for x in t.splitlines() if 'Thermal Status:' in x],battery=[x.strip() for x in b.splitlines() if any(k in x for k in ('temperature:','powered:','level:'))])
    foreground=idle()
    folder=H/('pad1-fullnative-20261025T'+time.strftime('%H%M%S')+'.private');folder.mkdir()
    seed=folder/'seed';seed.mkdir();basecases,source=base.inputs(seed)
    cases=[]
    sourceimage=R/source['colorSource']
    with Image.open(sourceimage) as im:
        im.convert('RGB').resize((690,round(im.height*690/im.width)),Image.Resampling.LANCZOS).crop((0,0,690,901)).save(folder/'color.png')
    with Image.open(seed/'line-text-synthetic.png') as im:im.resize((635,831),Image.Resampling.LANCZOS).save(folder/'line.png')
    for name,w,h in (('color',690,901),('line',635,831)):
        p=folder/(name+'.png');cases.append(dict(name=name,width=w,height=h,path=str(p),sha256=base.sha(p)))
    models={'native2':ASSETS/'models-Real-ESRGANv3-anime-native2','quality4to2':ASSETS/'models-Real-ESRGANv3-anime'}
    configs={'stock':H/'stock-libncnn.so','pad1':H/'pad1-candidate-libncnn.so'}
    assert base.sha(configs['stock'])=='87d150e735157b09aa20f26f5e57f72468c548e7ce98ce407ec50ee7e14a52dd'
    record=dict(active=True,clientDate='2026-10-25',scope='Full production resident worker isolated from app: six tiles, lossless PNG, no reader/energy acceptance',
        cases=cases,source=source,foregroundBefore=foreground,appChanged=False,runs=[],pairs=[],
        identities=dict(worker=base.sha(JNI/'libmanyue_realesr.so'),omp=base.sha(JNI/'libomp.so'),**{k:base.sha(v) for k,v in configs.items()}))
    def save():base.write(folder/'report.json',record)
    remote='/data/local/tmp/manyue-v94-padworker'
    def run(kind,model,case,roundid):
        assert idle()==foreground
        route=remote+'/'+kind;label=f'r{roundid}-{model}-{case["name"]}-{kind}'
        pidfile=route+'/worker.pid'
        inner=f'echo $$ > {pidfile}; export MANYUE_GPU_CHUNK=1 MANYUE_FUSE_PAD1={int(kind=="pad1")} OMP_NUM_THREADS=1 OMP_WAIT_POLICY=PASSIVE LD_LIBRARY_PATH={route}; exec {route}/libmanyue_realesr.so -S -s 2 -m {route}/{models[model].name} -g 0 -t 400 -j 1:1:1'
        before=resource();log=(folder/(label+'.log')).open('wb')
        process=subprocess.Popen([str(ADB),'-s',online[0],'shell','-T',"sh -c '"+inner+"'"],stdin=subprocess.PIPE,stdout=subprocess.PIPE,stderr=log)
        pool=concurrent.futures.ThreadPoolExecutor(max_workers=1);reader=Reader(process.stdout);samples=[];proof={};pid=None
        try:
            ready=pool.submit(receive,reader).result(timeout=90)
            assert ready['kind']==0 and ready['status']==0 and ready['modelLoads']==1,ready
            pid=adb('shell','cat',pidfile).decode().strip();assert pid.isdigit()
            maps=adb('shell','cat',f'/proc/{pid}/maps').decode(errors='replace')
            proof=dict(workerMapped=route+'/libmanyue_realesr.so' in maps,ncnnMapped=route+'/libncnn.so' in maps)
            assert all(proof.values()),'Library load proof failed'
            for request in range(1,6):
                dest=route+'/'+label+f'-{request}.png'
                start=time.monotonic_ns();send(process.stdin,request,case['width']*2,route+'/'+case['name']+'.png',dest)
                response=pool.submit(receive,reader).result(timeout=180)
                wall=(time.monotonic_ns()-start)/1e6
                assert response['kind']==1 and response['status']==0 and response['requestId']==request,response
                assert response['backend']==1 and response['tileSize']==400 and response['modelLoads']==0,response
                assert (response['width'],response['height'])==(case['width']*2,case['height']*2),response
                path=folder/(label+f'-{request}.png');adb('pull',dest,path)
                with Image.open(path) as im:
                    assert im.format=='PNG';a=np.asarray(im.convert('RGBA'));assert im.size==(case['width']*2,case['height']*2)
                samples.append(dict(request=request,warmup=request<=2,wallMs=wall,response=response,outputFile=path.name,pixelSha256=__import__('hashlib').sha256(a.tobytes()).hexdigest(),fileSha256=base.sha(path)))
            process.stdin.close();assert process.wait(timeout=30)==0
        finally:
            if process.poll() is None:
                if pid and pid.isdigit():
                    # Kill only the exact worker launched by this route, never app processes.
                    raw('-s',online[0],'shell',f'if [ "$(readlink /proc/{pid}/exe)" = "{route}/libmanyue_realesr.so" ]; then kill {pid}; fi')
                process.kill();process.wait(timeout=10)
            pool.shutdown(wait=False,cancel_futures=True);log.close()
        row=dict(kind=kind,model=model,case=case['name'],round=roundid,ready=ready,loadProof=proof,before=before,after=resource(),samples=samples)
        record['runs'].append(row);save();return row
    save()
    try:
        for kind,ncnn in configs.items():
            route=remote+'/'+kind;adb('shell','mkdir','-p',route)
            files=[(JNI/'libmanyue_realesr.so','libmanyue_realesr.so'),(JNI/'libomp.so','libomp.so'),(ncnn,'libncnn.so')]
            for model,path in models.items():
                adb('shell','mkdir','-p',route+'/'+path.name)
                files.extend((path/name,path.name+'/'+name) for name in ('x2.param','x2.bin'))
            files.extend((Path(c['path']),c['name']+'.png') for c in cases)
            for p,name in files:
                adb('push',p,route+'/'+name);assert adb('shell','sha256sum',route+'/'+name).decode().split()[0]==base.sha(p)
            adb('shell','chmod','700',route+'/libmanyue_realesr.so')
        for roundid in range(1,4):
            for model in models:
                for case in cases:
                    rows={}
                    for kind in (('stock','pad1') if roundid%2 else ('pad1','stock')):rows[kind]=run(kind,model,case,roundid)
                    a,b=rows['stock'],rows['pad1']
                    equal=all(x['pixelSha256']==y['pixelSha256'] for x,y in zip(a['samples'],b['samples']))
                    assert equal,'FullNative PNG pixels differ'
                    median=lambda r,k:statistics.median(s['response'][k]/1000 for s in r['samples'] if not s['warmup'])
                    inferA,inferB=median(a,'inferUs'),median(b,'inferUs');totalA,totalB=median(a,'totalUs'),median(b,'totalUs')
                    result=dict(model=model,case=case['name'],round=roundid,exactRgba=True,stockInferMs=inferA,padInferMs=inferB,
                        inferGainPercent=(1-inferB/inferA)*100,stockTotalMs=totalA,padTotalMs=totalB,totalGainPercent=(1-totalB/totalA)*100)
                    record['pairs'].append(result);save();print(json.dumps(result),flush=True)
        record['foregroundAfter']=idle();assert record['foregroundAfter']==foreground
    except Exception as e:record['failure']=dict(type=type(e).__name__,message=str(e));raise
    finally:record['active']=False;save()
    print(json.dumps(dict(folder=str(folder.relative_to(R)),pairs=len(record['pairs']),appChanged=False)),flush=True)

if __name__=='__main__':main()
