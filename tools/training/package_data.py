"""Package only manifest-referenced files; no duplicate source archive copies."""
import argparse, hashlib, json, zipfile
from pathlib import Path

def main():
    here=Path(__file__).parent
    p=argparse.ArgumentParser();p.add_argument('--root',type=Path,default=here/'artifacts');a=p.parse_args()
    root=a.root;out=root/'packages';out.mkdir(exist_ok=True);reports=[]
    for task in ('road-damage','road-users','gemma'):
        base=root/task;paths=[x for x in base.iterdir() if x.is_file()]
        if task!='gemma':
            for line in (base/'manifest.jsonl').read_text().splitlines():
                row=json.loads(line);paths.append(base/row['image']);paths.append(base/'labels'/row['split']/(row['id']+'.txt'))
        dest=out/(task+'.zip');part=dest.with_suffix('.zip.part')
        with zipfile.ZipFile(part,'w',zipfile.ZIP_DEFLATED,compresslevel=1) as z:
            for f in paths:z.write(f,f.relative_to(root),compress_type=zipfile.ZIP_STORED if f.suffix=='.jpg' else zipfile.ZIP_DEFLATED)
            for name in ('sources.json','aggregate-report.json','quality-report.json'):
                if (root/name).is_file():z.write(root/name,name)
            for name in ('README.md','train_yolo.py','load_gemma.py'):z.write(here/name,name)
        part.replace(dest)
        sha=hashlib.sha256()
        with dest.open('rb') as f:
            for b in iter(lambda:f.read(1<<20),b''):sha.update(b)
        reports.append(dict(file=dest.name,bytes=dest.stat().st_size,sha256=sha.hexdigest()))
        print(f'Packaged {task}: {dest.stat().st_size/1e6:.1f} MB',flush=True)
    (out/'packages.json').write_text(json.dumps(reports,indent=2))
if __name__=='__main__':main()
