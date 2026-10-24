"""Matched pad-only plain SDK verification. Idle screen; no app data mutation."""
import json
import random
import statistics
import subprocess
import sys
import time
from pathlib import Path
import numpy as np
from PIL import Image

H=Path(__file__).resolve().parent
R=H.parents[2]
OLD=H.parent/'native-profile-v93-20261018'
sys.path.insert(0,str(OLD))
import run_host as base
ADB=Path('C:/Users/Administrator/Documents/Codex/2026-09-19/ba/work/toolchains/android-sdk/platform-tools/adb.exe')
JNI=R/'work/mihon-source/app/src/main/jniLibs/arm64-v8a'
ASSETS=R/'work/mihon-source/app/src/main/assets/ai'

def main():
    def raw(*args,timeout=40):
        try:p=subprocess.run([str(ADB),*map(str,args)],capture_output=True,timeout=timeout)
        except subprocess.TimeoutExpired:raise RuntimeError('ADB timeout; identifier suppressed') from None
        if p.returncode:raise RuntimeError(f'ADB failed code={p.returncode}; identifier suppressed')
        return p.stdout
    online=[x.split()[0] for x in raw('devices').decode().splitlines()[1:] if len(x.split())>=2 and x.split()[1]=='device']
    assert len(online)==1,'Require exactly one authorized device'
    def adb(*args,**kw):return raw('-s',online[0],*args,**kw)
    def idle():
        names=adb('shell','ps','-A','-o','NAME').decode(errors='replace')
        assert not any(x in names for x in ('libmanyue_realesr','compute_probe','compute_stage_probe')),'Native worker already active'
        lines=adb('shell','dumpsys','activity','activities').decode(errors='replace').splitlines()
        top=[x for x in lines if 'topResumedActivity=' in x]
        assert len(top)==1 and ('com.hihonor.android.launcher/' in top[0] or ('app.mihon.benchmark/' in top[0] and 'MainActivity' in top[0])),'Require idle Main or launcher'
        return 'launcher' if 'com.hihonor.android.launcher/' in top[0] else 'Main'
    def resources():
        t=adb('shell','dumpsys','thermalservice').decode(errors='replace')
        b=adb('shell','dumpsys','battery').decode(errors='replace')
        status=[x.strip() for x in t.splitlines() if 'Thermal Status:' in x]
        return dict(thermal=status,battery=[x.strip() for x in b.splitlines() if any(k in x for k in ('temperature:','powered:','level:'))])
    foreground=idle()
    folder=H/('pad1-plain-20261025T'+time.strftime('%H%M%S')+'.private');folder.mkdir()
    original,source=base.inputs(folder)
    cases=[]
    for case,(w,h) in zip(original,((400,400),(420,420),(399,401))):
        im=Image.open(folder/(case['name']+'.png')).convert('RGB').resize((w,h),Image.Resampling.LANCZOS)
        path=folder/(case['name']+f'-{w}x{h}.f32')
        np.ascontiguousarray(np.asarray(im,dtype=np.float32).transpose(2,0,1)/np.float32(255)).tofile(path)
        cases.append(dict(name=case['name'],width=w,height=h,input=str(path),inputSha256=base.sha(path)))
    models={'native2':ASSETS/'models-Real-ESRGANv3-anime-native2','quality4to2':ASSETS/'models-Real-ESRGANv3-anime'}
    configs={'stock':dict(tool=OLD/'compute_probe-arm64',lib=H/'stock-libncnn.so'),
        'plain':dict(tool=H/'compute_plain_probe-arm64',lib=H/'pad-plain-arm64-prefix/lib/libncnn.so')}
    assert base.sha(configs['stock']['lib'])=='87d150e735157b09aa20f26f5e57f72468c548e7ce98ce407ec50ee7e14a52dd'
    record=dict(active=True,clientDate='2026-10-25',scope='Standalone plain SDK networks, not fullNative/reader/watts acceptance',
        source=source,cases=cases,seed=94,appChanged=False,foregroundBefore=foreground,
        patchSha256=base.sha(H/'pad1-only.patch'),runs=[],pairs=[],controls=[],boundaries=[],
        identities={k:{n:base.sha(f) for n,f in c.items()} for k,c in configs.items()},
        models={k:{n:base.sha(p/n) for n in ('x2.param','x2.bin')} for k,p in models.items()})
    def save():base.write(folder/'report.json',record)
    def run(model,case,kind,enabled,tag):
        assert idle()==foreground,'Foreground changed'
        name=f'{model}-{case["width"]}x{case["height"]}-{case["name"]}-{tag}-{kind}-{int(enabled)}'
        remote=configs[kind]['remote']
        before=resources()
        cmd=f'cd {remote} && LD_LIBRARY_PATH={remote} OMP_NUM_THREADS=1 OMP_WAIT_POLICY=PASSIVE MANYUE_FUSE_PAD1={int(enabled)} ./probe {model}.param {model}.bin {Path(case["input"]).name} {case["width"]} {case["height"]} 1 4 {name}.f32 -1'
        try:p=subprocess.run([str(ADB),'-s',online[0],'shell',cmd],capture_output=True,timeout=120)
        except subprocess.TimeoutExpired:raise RuntimeError('Plain probe timeout; identifier suppressed') from None
        log=(p.stdout+b'\n'+p.stderr).decode(errors='replace');(folder/(name+'.log')).write_text(log,'utf-8')
        assert p.returncode==0,dict(code=p.returncode,log=name+'.log')
        assert log.count('MANYUE_FUSE_PAD1 active')==(16 if enabled else 0),'Pipeline activation mismatch'
        m=[json.loads(x) for x in log.splitlines() if x.startswith('{')];assert len(m)==1
        m=m[0];assert not m['effectivePack8'] and not m['effectiveLocalMemory']
        out=folder/(name+'.f32');adb('pull',remote+'/'+out.name,out)
        a=np.fromfile(out,dtype='<f4');assert a.size==case['width']*case['height']*12 and np.isfinite(a).all()
        row=dict(model=model,case=case['name'],round=tag,kind=kind,enabled=enabled,metrics=m,outputFile=out.name,
            outputSha256=base.sha(out),before=before,after=resources())
        record['runs'].append(row);save();return row
    def pair(a,b):
        result=base.pair(a,b,folder);result['model']=a['model']
        assert result['floatDifferentValues']==result['rgbDifferentValues']==0,'Output differs'
        return result
    def pushcase(case):
        for cfg in configs.values():adb('push',case['input'],cfg['remote']+'/'+Path(case['input']).name)
    save()
    try:
        for kind,cfg in configs.items():
            remote='/data/local/tmp/manyue-v94-padplain-'+kind;cfg['remote']=remote;adb('shell','mkdir','-p',remote)
            files=[(cfg['tool'],'probe'),(cfg['lib'],'libncnn.so'),(JNI/'libomp.so','libomp.so')]
            for model,path in models.items():files.extend([(path/'x2.param',model+'.param'),(path/'x2.bin',model+'.bin')])
            for f,name in files:
                adb('push',f,remote+'/'+name)
                assert adb('shell','sha256sum',remote+'/'+name).decode().split()[0]==base.sha(f)
            adb('shell','chmod','700',remote+'/probe')
        for case in cases:pushcase(case)
        for model in models:
            stock=run(model,cases[0],'stock',False,'control')
            off=run(model,cases[0],'plain',False,'control')
            record['controls'].append(pair(stock,off));save()
        rng=random.Random(94)
        for roundid in range(1,4):
            workloads=[(model,case) for model in models for case in cases];rng.shuffle(workloads)
            for index,(model,case) in enumerate(workloads):
                rows={}
                for enabled in ((False,True) if (roundid+index)%2 else (True,False)):
                    rows[enabled]=run(model,case,'plain',enabled,'r'+str(roundid))
                result=pair(rows[False],rows[True]);record['pairs'].append(result);save();print(json.dumps(result),flush=True)
        for w,h in ((1,1),(10,10),(13,17),(65,129),(420,420)):
            im=Image.open(folder/'color-ccby.png').convert('RGB').resize((w,h),Image.Resampling.LANCZOS)
            path=folder/f'boundary-{w}x{h}.f32';np.ascontiguousarray(np.asarray(im,dtype=np.float32).transpose(2,0,1)/np.float32(255)).tofile(path)
            case=dict(name='boundary',width=w,height=h,input=str(path),inputSha256=base.sha(path));pushcase(case)
            for model in models:
                a=run(model,case,'plain',False,'boundary');b=run(model,case,'plain',True,'boundary')
                record['boundaries'].append(pair(a,b));save()
        record['summary']=[]
        for model in models:
            for case in cases:
                ps=[p for p in record['pairs'] if p['model']==model and p['case']==case['name']]
                gains=[p['improvementPercent'] for p in ps]
                record['summary'].append(dict(model=model,case=case['name'],gainsPercent=gains,medianPercent=statistics.median(gains),
                    gatePassed=statistics.median(gains)>=5 and min(gains)>=-5 and sum(g>0 for g in gains)>=2))
        record['expandedGatePassed']=all(x['gatePassed'] for x in record['summary'])
        record['foregroundAfter']=idle();assert record['foregroundAfter']==foreground
    except Exception as e:record['failure']=dict(type=type(e).__name__,message=str(e));raise
    finally:record['active']=False;save()
    print(json.dumps(dict(folder=str(folder.relative_to(R)),expandedGatePassed=record.get('expandedGatePassed'),appChanged=False)),flush=True)

if __name__=='__main__':main()
