"""Freeze all matched outcomes without private image payloads or device IDs."""
import hashlib
import json
import statistics
from pathlib import Path
H=Path(__file__).resolve().parent
def load(p):return json.loads(p.read_text('utf-8'))
pilot=load(H/'pad1-pilot-20261025T002124.private/report.json')
plain=load(H/'pad1-plain-20261025T002510.private/report.json')
fullpath=H/'pad1-fullnative-20261025T002908.private/report.json'
full=load(fullpath)
assert not full['active'],'Do not summarize a running experiment as complete'
assert 'failure' not in full,'Keep failed fullNative trial as failure'
summary=[]
for model in ('native2','quality4to2'):
    for case in ('color','line'):
        ps=[p for p in full['pairs'] if p['model']==model and p['case']==case]
        assert len(ps)==3
        gains=[p['totalGainPercent'] for p in ps]
        summary.append(dict(model=model,case=case,totalGainsPercent=gains,
            medianTotalGainPercent=statistics.median(gains),medianInferGainPercent=statistics.median(p['inferGainPercent'] for p in ps),
            gatePassed=statistics.median(gains)>=5 and min(gains)>=-5 and sum(g>0 for g in gains)>=2))
thermal=[]
for run in full['runs']:
    thermal.append(dict(model=run['model'],case=run['case'],round=run['round'],kind=run['kind'],
        before=run['before'],after=run['after'],transition=run['before']['thermal']!=run['after']['thermal']))
report=dict(clientDate='2026-10-25',baselineSource='1dfa768bf6ae7aab08637c5e1d0b81f1c2c4964d',
    pinnedNcnnArchiveSha256='b2145f7b01422f5ee66777d1c2c29de935191ed39865193951e1a7228202a6c1',
    scope='Exact numerical and isolated resident-worker performance candidate. NOT reader/energy/heat acceptance',
    pilot=dict(summary=pilot['summary'],pairs=pilot['pairs'],attribution=pilot['attribution']),
    plain=dict(summary=plain['summary'],controls=plain['controls'],pairs=plain['pairs'],boundaryChecks=plain['boundaries'],gatePassed=plain['expandedGatePassed'],identities=plain['identities'],models=plain['models']),
    fullNative=dict(summary=summary,pairs=full['pairs'],thermal=thermal,identities=full['identities'],rgbaComparisonPairs=60,rgbaComparedOutputs=120,
        gatePassed=all(s['gatePassed'] for s in summary),readerTested=False,energyImprovement='NOT MEASURED'),
    strip=load(H/'strip-verification.json'),
    evidence={p.parent.name:dict(path=str(p.relative_to(H)),sha256=hashlib.sha256(p.read_bytes()).hexdigest()) for p in
        (H/'pad1-pilot-20261025T002124.private/report.json',H/'pad1-plain-20261025T002510.private/report.json',fullpath)},
    failures=[dict(scope='First fullNative harness startup',reason='Worker rejected shortened model directory name (unknown model dir type); stock route, no inference. Corrected to existing asset directory basenames.',path='pad1-fullnative-20261025T002846.private/report.json')])
(H/'PAD_RESULTS_20261025.json').write_text(json.dumps(report,ensure_ascii=False,indent=2)+'\n','utf-8')
print(json.dumps(dict(plainGate=plain['expandedGatePassed'],fullGate=report['fullNative']['gatePassed'],summary=summary)))
