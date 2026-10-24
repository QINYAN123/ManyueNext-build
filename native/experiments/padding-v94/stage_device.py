"""Isolated on-device stage profiling; no APK install or reader interaction."""
import argparse
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
    p=argparse.ArgumentParser()
    p.add_argument('--full',action='store_true')
    a=p.parse_args()
    def raw(*args,timeout=40):
        try:r=subprocess.run([str(ADB),*map(str,args)],capture_output=True,timeout=timeout)
        except subprocess.TimeoutExpired:raise RuntimeError('ADB timeout; device identifier suppressed') from None
        if r.returncode:raise RuntimeError(f'ADB returned {r.returncode}; device identifier suppressed')
        return r.stdout
    online=[s.split()[0] for s in raw('devices').decode().splitlines()[1:] if len(s.split())>=2 and s.split()[1]=='device']
    assert len(online)==1,'Need exactly one authorized phone'
    def adb(*args,**kwargs):return raw('-s',online[0],*args,**kwargs)
    def idle_state():
        activities=adb('shell','dumpsys','activity','activities').decode(errors='replace')
        resumed=[x for x in activities.splitlines() if 'topResumedActivity=' in x]
        assert len(resumed)==1,'Ambiguous foreground'
        if 'com.hihonor.android.launcher/' in resumed[0]:foreground='launcher'
        elif 'app.mihon.benchmark/' in resumed[0] and 'MainActivity' in resumed[0]:foreground='Mihon Main'
        else:raise RuntimeError('Foreground is not idle launcher/Main; no reader input performed')
        names=adb('shell','ps','-A','-o','NAME').decode(errors='replace')
        assert not any(x in names for x in ('libmanyue_realesr','compute_probe','compute_timestamp_probe','compute_stage_probe')),'Native worker already active'
        return foreground
    def resources():
        thermal=adb('shell','dumpsys','thermalservice').decode(errors='replace')
        battery=adb('shell','dumpsys','battery').decode(errors='replace')
        power=adb('shell','dumpsys','power').decode(errors='replace')
        return dict(thermal=[x.strip() for x in thermal.splitlines() if 'Thermal Status:' in x],
            battery=[x.strip() for x in battery.splitlines() if any(y in x for y in ('temperature:','powered:','level:'))],
            power=[x.strip() for x in power.splitlines() if 'mWakefulness=' in x])
    foreground=idle_state()
    folder=H/('stage-'+('full-' if a.full else 'pilot-')+'20261022T'+time.strftime('%H%M%S')+'.private')
    folder.mkdir()
    cases,source=base.inputs(folder)
    cases=cases if a.full else cases[:1]
    configs={'stock':dict(tool=OLD/'compute_probe-arm64',lib=JNI/'libncnn.so'),
             'diagnostic':dict(tool=H/'compute_stage_probe-arm64',lib=H/'stage-arm64-prefix/lib/libncnn.so')}
    record=dict(clientDate='2026-10-22',active=True,scope='Standalone stage diagnostic; not reader/thermal/energy acceptance',
        sourceBaseline='dbd4cf544e7788eaa225d9e505c403d21c5fc85f',applicationBase='1dfa768bf6ae7aab08637c5e1d0b81f1c2c4964d',
        source=source,cases=cases,runs=[],comparisons=[],foregroundBefore=foreground,
        appChanged=False,deviceIdentifierStored=False,identities={k:{n:base.sha(f) for n,f in c.items()} for k,c in configs.items()},
        modelHashes={n:base.sha(base.MODEL/n) for n in ('x2.param','x2.bin')})
    def save():base.write(folder/'report.json',record)
    save()
    try:
        for kind,c in configs.items():
            remote='/data/local/tmp/manyue-v94-stage-'+kind
            c['remote']=remote
            adb('shell','mkdir','-p',remote)
            for src,name in [(c['tool'],'probe'),(c['lib'],'libncnn.so'),(JNI/'libomp.so','libomp.so'),(base.MODEL/'x2.param','x2.param'),(base.MODEL/'x2.bin','x2.bin')]:
                adb('push',src,remote+'/'+name)
                assert adb('shell','sha256sum',remote+'/'+name).decode().split()[0]==base.sha(src)
            adb('shell','chmod','700',remote+'/probe')
            for case in cases:adb('push',case['input'],remote+'/'+Path(case['input']).name)
        for case in cases:
            rows=[]
            for order,(kind,enabled) in enumerate([('stock',False),('diagnostic',False),('diagnostic',True),('diagnostic',False)]):
                assert idle_state()==foreground,'Foreground changed during probe setup'
                remote=configs[kind]['remote'];name=f"{case['name']}-{order}-{kind}-{int(enabled)}"
                before=resources()
                cmd=f"cd {remote} && LD_LIBRARY_PATH={remote} OMP_NUM_THREADS=1 OMP_WAIT_POLICY=PASSIVE MANYUE_STAGE_PROFILE={int(enabled)} ./probe x2.param x2.bin {Path(case['input']).name} 400 400 1 4 {name}.f32 -1"
                try:proc=subprocess.run([str(ADB),'-s',online[0],'shell',cmd],capture_output=True,timeout=120)
                except subprocess.TimeoutExpired:raise RuntimeError('Standalone probe timeout; identifier suppressed') from None
                text=(proc.stdout+b'\n'+proc.stderr).decode(errors='replace')
                (folder/(name+'.log')).write_text(text,'utf-8')
                assert proc.returncode==0,dict(returncode=proc.returncode,log=name+'.log')
                objects=[json.loads(x) for x in text.splitlines() if x.startswith('{')]
                assert len(objects)==1
                metrics=objects[0]
                assert not metrics['effectiveLocalMemory'] and not metrics['effectivePack8']
                if kind=='diagnostic':
                    assert metrics['stageEnabled']==enabled
                    for sample in metrics['samples']:
                        assert len(sample['stages'])==(16 if enabled else 0)
                        if enabled:assert [s['layer'] for s in sample['stages']]==list(range(4,35,2))
                out=folder/(name+'.f32');adb('pull',remote+'/'+out.name,out)
                values=np.fromfile(out,dtype='<f4')
                assert values.size==400*400*12 and np.isfinite(values).all()
                row=dict(case=case['name'],kind=kind,enabled=enabled,order=order,round=order,metrics=metrics,
                    outputFile=out.name,outputSha256=base.sha(out),before=before,after=resources())
                rows.append(row);record['runs'].append(row);save()
            pairs=[base.pair(rows[0],row,folder) for row in rows[1:]]
            assert all(x['rgbDifferentValues']==x['floatDifferentValues']==0 for x in pairs),'Diagnostic output differs'
            samples=rows[2]['metrics']['samples']
            med=lambda rows:statistics.median(s['wallMs'] for r in rows for s in r['metrics']['samples'])
            totals={k:statistics.median(sum(st[k] for st in s['stages']) for s in samples)
                    for k in ('padGpuMs','inputGpuMs','gemmGpuMs','outputGpuMs','wholeConvGpuMs')}
            controls=med([rows[1],rows[3]]);measured=med([rows[2]])
            comparison=dict(case=case['name'],outputComparisons=pairs,
                disabledBeforeMs=med([rows[1]]),enabledMs=measured,disabledAfterMs=med([rows[3]]),
                pooledDisabledMs=controls,observationOverheadPercent=(measured/controls-1)*100,
                middle16StageTotalsMs=totals,
                perLayerMedians=[dict(layer=i,**{k:statistics.median(next(st[k] for st in s['stages'] if st['layer']==i) for s in samples) for k in totals}) for i in range(4,35,2)])
            record['comparisons'].append(comparison);save()
            print(json.dumps({k:v for k,v in comparison.items() if k not in ('outputComparisons','perLayerMedians')}),flush=True)
        record['foregroundAfter']=idle_state()
        assert record['foregroundAfter']==foreground
    except Exception as e:
        record['failure']=dict(type=type(e).__name__,message=str(e));raise
    finally:
        record['active']=False;save()
    print(json.dumps(dict(folder=str(folder.relative_to(R)),appChanged=False,energyAcceptance=False)),flush=True)


if __name__=='__main__':main()
