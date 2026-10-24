"""Two-order pilot of one input-transform dispatch candidate; no reader changes."""
import json
import statistics
import subprocess
import sys
import time
from pathlib import Path
import numpy as np

H=Path(__file__).resolve().parent
R=H.parents[2]
OLD=H.parent/'native-profile-v93-20261018'
sys.path.insert(0,str(OLD))
import run_host as base
ADB=Path('C:/Users/Administrator/Documents/Codex/2026-09-19/ba/work/toolchains/android-sdk/platform-tools/adb.exe')
JNI=R/'work/mihon-source/app/src/main/jniLibs/arm64-v8a'


def main():
    def raw(*args,timeout=40):
        try:r=subprocess.run([str(ADB),*map(str,args)],capture_output=True,timeout=timeout)
        except subprocess.TimeoutExpired:raise RuntimeError('ADB timeout; device identifier suppressed') from None
        if r.returncode:raise RuntimeError(f'ADB returned {r.returncode}; identifier suppressed')
        return r.stdout
    online=[x.split()[0] for x in raw('devices').decode().splitlines()[1:] if len(x.split())>=2 and x.split()[1]=='device']
    assert len(online)==1
    def adb(*args,**kwargs):return raw('-s',online[0],*args,**kwargs)
    def idle():
        top=adb('shell','dumpsys','activity','activities').decode(errors='replace')
        resumed=[x for x in top.splitlines() if 'topResumedActivity=' in x]
        assert len(resumed)==1 and ('com.hihonor.android.launcher/' in resumed[0] or ('app.mihon.benchmark/' in resumed[0] and 'MainActivity' in resumed[0])),'No idle foreground'
        names=adb('shell','ps','-A','-o','NAME').decode(errors='replace')
        assert not any(x in names for x in ('libmanyue_realesr','compute_probe','compute_stage_probe')),'Native worker already active'
        return 'launcher' if 'com.hihonor.android.launcher/' in resumed[0] else 'Main'
    foreground=idle()
    def resources():
        t=adb('shell','dumpsys','thermalservice').decode(errors='replace')
        b=adb('shell','dumpsys','battery').decode(errors='replace')
        return dict(thermal=[x.strip() for x in t.splitlines() if 'Thermal Status:' in x],
                    battery=[x.strip() for x in b.splitlines() if any(k in x for k in ('temperature:','powered:','level:'))])
    folder=H/('input64-pilot-20261022T'+time.strftime('%H%M%S')+'.private');folder.mkdir()
    cases,source=base.inputs(folder);case=cases[0]
    configs={'stock':dict(tool=OLD/'compute_probe-arm64',lib=JNI/'libncnn.so'),
             'candidate':dict(tool=H/'compute_stage_probe-arm64',lib=H/'stage-arm64-prefix/lib/libncnn.so')}
    record=dict(active=True,clientDate='2026-10-22',scope='Isolated input-transform pilot; NOT app/reader/thermal/energy acceptance',
        source=source,case=case,foregroundBefore=foreground,appChanged=False,
        identities={k:{n:base.sha(f) for n,f in c.items()} for k,c in configs.items()},
        patchSha256=base.sha(H/'input64.patch'),runs=[],pairs=[])
    def save():base.write(folder/'report.json',record)
    def run(kind,enabled,stage,order):
        assert idle()==foreground
        remote=configs[kind]['remote'];name=f"color-r{order}-{kind}-input{int(enabled)}-stage{int(stage)}"
        cmd=f"cd {remote} && LD_LIBRARY_PATH={remote} OMP_NUM_THREADS=1 OMP_WAIT_POLICY=PASSIVE MANYUE_INPUT64={int(enabled)} MANYUE_STAGE_PROFILE={int(stage)} ./probe x2.param x2.bin color-ccby.f32 400 400 1 4 {name}.f32 -1"
        before=resources()
        try:r=subprocess.run([str(ADB),'-s',online[0],'shell',cmd],capture_output=True,timeout=120)
        except subprocess.TimeoutExpired:raise RuntimeError('Candidate probe timeout; identifier suppressed') from None
        text=(r.stdout+b'\n'+r.stderr).decode(errors='replace');(folder/(name+'.log')).write_text(text,'utf-8')
        assert r.returncode==0,dict(returncode=r.returncode,log=name+'.log')
        assert text.count('MANYUE_INPUT64 active')==(16 if enabled else 0),'Candidate pipeline activation mismatch'
        objects=[json.loads(x) for x in text.splitlines() if x.startswith('{')];assert len(objects)==1
        metrics=objects[0]
        if kind=='candidate':assert metrics['stageEnabled']==stage
        assert not metrics['effectivePack8'] and not metrics['effectiveLocalMemory']
        out=folder/(name+'.f32');adb('pull',remote+'/'+out.name,out)
        values=np.fromfile(out,dtype='<f4');assert values.size==400*400*12 and np.isfinite(values).all()
        row=dict(case=case['name'],round=order,kind=kind,enabled=enabled,stage=stage,metrics=metrics,
            outputFile=out.name,outputSha256=base.sha(out),before=before,after=resources())
        record['runs'].append(row);save();return row
    save()
    try:
        for kind,c in configs.items():
            remote='/data/local/tmp/manyue-v94-input64-'+kind;c['remote']=remote
            adb('shell','mkdir','-p',remote)
            for f,name in [(c['tool'],'probe'),(c['lib'],'libncnn.so'),(JNI/'libomp.so','libomp.so'),(base.MODEL/'x2.param','x2.param'),(base.MODEL/'x2.bin','x2.bin'),(Path(case['input']),'color-ccby.f32')]:
                adb('push',f,remote+'/'+name)
                assert adb('shell','sha256sum',remote+'/'+name).decode().split()[0]==base.sha(f)
            adb('shell','chmod','700',remote+'/probe')
        stock=run('stock',False,False,0)
        rows=[run('candidate',bool(mode),False,i+1) for i,mode in enumerate((0,1,1,0))]
        control=base.pair(stock,rows[0],folder);record['rebuildControl']=control
        record['pairs']=[base.pair(rows[0],rows[1],folder),base.pair(rows[3],rows[2],folder)]
        assert all(p['rgbDifferentValues']==p['floatDifferentValues']==0 for p in [control]+record['pairs']),'Numerical output changed'
        med=lambda rr:statistics.median(s['wallMs'] for r in rr for s in r['metrics']['samples'])
        disabled=med([rows[0],rows[3]]);enabled=med([rows[1],rows[2]])
        drift=abs(med([rows[3]])/med([rows[0]])-1)*100
        gain=(1-enabled/disabled)*100
        record['summary']=dict(disabledMs=disabled,enabledMs=enabled,pooledImprovementPercent=gain,disabledBracketDriftPercent=drift)
        record['pilotGatePassed']=all(p['improvementPercent']>=5 for p in record['pairs']) and gain>drift
        save();print(json.dumps(dict(summary=record['summary'],pairs=record['pairs'],pilotGatePassed=record['pilotGatePassed'])),flush=True)
        if record['pilotGatePassed']:
            attribution=[run('candidate',False,True,5),run('candidate',True,True,6)]
            pair=base.pair(attribution[0],attribution[1],folder)
            assert pair['rgbDifferentValues']==pair['floatDifferentValues']==0
            record['attribution']=dict(pair=pair,stages=[{
                k:statistics.median(sum(st[k] for st in s['stages']) for s in r['metrics']['samples'])
                for k in ('padGpuMs','inputGpuMs','gemmGpuMs','outputGpuMs')} for r in attribution])
        record['foregroundAfter']=idle();assert record['foregroundAfter']==foreground
    except Exception as e:
        record['failure']=dict(type=type(e).__name__,message=str(e));raise
    finally:record['active']=False;save()
    print(json.dumps(dict(folder=str(folder.relative_to(R)),appChanged=False)),flush=True)


if __name__=='__main__':main()
