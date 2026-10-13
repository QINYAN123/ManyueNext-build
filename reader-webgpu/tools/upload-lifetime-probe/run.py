"""Fault-inject the exact Mipmap source without changing its coroutine/upload implementation."""
import argparse
import hashlib
import json
import os
from pathlib import Path
import subprocess

HERE = Path(__file__).resolve().parent
SOURCE = Path('reader-webgpu/src/main/java/ca/mpreg/webgpuviewer/renderer/Mipmap.kt')
p = argparse.ArgumentParser()
p.add_argument('--java', required=True, type=Path)
p.add_argument('--output', required=True, type=Path)
p.add_argument('--baseline', help='Optional git revision that must reproduce at least one leak')
p.add_argument('--repo', type=Path, default=HERE.parents[2])
p.add_argument('--cache', type=Path, default=Path.home()/'.gradle/caches/modules-2/files-2.1')
args = p.parse_args()

def jar(group, name, version):
    files = list((args.cache/group/name/version).glob('*/*.jar'))
    assert len(files) == 1, (name, files)
    return str(files[0])

stdlib = jar('org.jetbrains.kotlin', 'kotlin-stdlib', '2.4.20')
coroutines = jar('org.jetbrains.kotlinx', 'kotlinx-coroutines-core-jvm', '1.11.0')
annotations = jar('org.jetbrains', 'annotations', '23.0.0')
runtime = os.pathsep.join([stdlib, coroutines, annotations])
compiler = os.pathsep.join([jar('org.jetbrains.kotlin', 'kotlin-compiler-embeddable', '2.4.20'),
    jar('org.jetbrains.kotlin', 'kotlin-reflect', '2.4.20'), runtime])
sources = [('candidate', (args.repo/SOURCE).read_bytes())]
if args.baseline:
    sources.insert(0, ('baseline', subprocess.check_output(
        ['git', 'show', f'{args.baseline}:{SOURCE.as_posix()}'], cwd=args.repo)))
results = {}
for label, source in sources:
    out = args.output/label
    out.mkdir(parents=True, exist_ok=True)
    file = out/'Mipmap.kt'
    file.write_bytes(source)
    classes = out/'classes'
    classes.mkdir(exist_ok=True)
    compile_args = [str(args.java), '-cp', compiler, 'org.jetbrains.kotlin.cli.jvm.K2JVMCompiler',
        '-no-stdlib', '-no-reflect', '-classpath', runtime, '-jvm-target', '17', '-d', str(classes),
        str(file)] + [str(path) for path in HERE.glob('*.kt')]
    built = subprocess.run(compile_args, capture_output=True, text=True)
    (out/'compile.log').write_text(built.stdout+built.stderr, encoding='utf8')
    assert built.returncode == 0, (out/'compile.log').read_text()
    run = subprocess.run([str(args.java), '-cp', str(classes)+os.pathsep+runtime, 'ProbeKt'],
        capture_output=True, text=True)
    (out/'run.log').write_text(run.stdout+run.stderr, encoding='utf8')
    assert run.returncode == 0, (out/'run.log').read_text()
    rows = []
    for line in run.stdout.splitlines():
        name, outcome, count, leaks, view_leaks = line.split('|')
        rows.append(dict(case=name, outcome=outcome, created=int(count),
                         textureLeaks=int(leaks), viewLeaks=int(view_leaks)))
    results[label] = dict(sourceSha256=hashlib.sha256(source).hexdigest(), cases=rows,
        passCount=sum(row['textureLeaks'] == row['viewLeaks'] == 0 for row in rows))
(args.output/'results.json').write_text(json.dumps(results, indent=2), encoding='utf8')
print(json.dumps({key: dict(passed=value['passCount'], cases=len(value['cases']))
                  for key, value in results.items()}))
assert results['candidate']['passCount'] == len(results['candidate']['cases']), 'Candidate leaks'
if 'baseline' in results:
    assert results['baseline']['passCount'] < len(results['baseline']['cases']), 'No baseline reproduction'
