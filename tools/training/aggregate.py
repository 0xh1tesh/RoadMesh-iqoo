"""Acquire real driving data and produce separate YOLO tasks + grounded Gemma JSONL.

No pseudo-labels, no random adjacent-frame split, no silent unlabeled-as-negative.
python tools/training/aggregate.py --download --build
"""
from __future__ import annotations
import argparse, base64, csv, hashlib, io, json, math, re, shutil, tarfile, time, zipfile
from collections import Counter, defaultdict
from concurrent.futures import ThreadPoolExecutor
from pathlib import Path
import xml.etree.ElementTree as ET
import requests
from PIL import Image

HERE = Path(__file__).resolve().parent
SOURCES = json.loads((HERE / "sources.json").read_text())
ROOT = HERE / "artifacts"

def dump(path, obj):
    path.parent.mkdir(parents=True, exist_ok=True)
    path.write_text(json.dumps(obj, indent=2, ensure_ascii=False), encoding="utf-8")

def digest(path):
    h = hashlib.sha256()
    with path.open("rb") as f:
        for b in iter(lambda: f.read(1 << 20), b""): h.update(b)
    return h.hexdigest()

def download(url, dest):
    """Atomic download with bounded size and cached checksum; retry interrupted transfers."""
    dest.parent.mkdir(parents=True, exist_ok=True)
    meta = dest.with_suffix(dest.suffix + ".json")
    if dest.exists() and meta.exists():
        m = json.loads(meta.read_text())
        if m.get("sha256") == digest(dest): return m
        raise ValueError(f"Checksum mismatch: {dest}")
    last = None
    for attempt in range(3):
        try:
            with requests.get(url, stream=True, timeout=(20, 90)) as r:
                r.raise_for_status()
                total = int(r.headers.get("Content-Length", 0))
                free = shutil.disk_usage(dest.parent).free
                if total > 5_000_000_000 or free < total + 1_000_000_000:
                    raise ValueError(f"Insufficient download budget: {dest.name}, {total} bytes")
                part = dest.with_suffix(dest.suffix + ".part")
                h = hashlib.sha256(); n = 0; tick = time.monotonic()
                with part.open("wb") as f:
                    for b in r.iter_content(1 << 20):
                        if not b: continue
                        f.write(b); h.update(b); n += len(b)
                        if n > 5_000_000_000: raise ValueError("Download exceeds 5 GB bound")
                        if time.monotonic() - tick > 20:
                            print(f"{dest.name}: {n / 1e6:.0f} MB", flush=True); tick = time.monotonic()
                if total and total != n: raise ValueError("Truncated download")
                part.replace(dest)
                m = dict(url=url, resolved_url=r.url.split("?")[0], bytes=n, sha256=h.hexdigest())
                dump(meta, m); print(f"Downloaded {dest.name}: {n / 1e6:.1f} MB", flush=True)
                return m
        except (requests.RequestException, OSError) as e:
            last = e; time.sleep(attempt + 1)
    raise RuntimeError(f"Download failed: {dest.name}: {last}")

def acquire(root, countries, udacity=False):
    jobs = [(SOURCES['rdd2022']['base_url'] + f'RDD2022_{c}.zip', root / 'raw' / f'RDD2022_{c}.zip') for c in countries]
    if udacity: jobs.append((SOURCES['udacity']['archive_url'], root / 'raw' / 'udacity-crowdai.tar.gz'))
    outcomes = []
    with ThreadPoolExecutor(max_workers=3) as pool:
        futures = [(p, pool.submit(download, u, p)) for u, p in jobs]
        for p, f in futures:
            try: outcomes.append(dict(file=p.name, status='ready', **f.result()))
            except Exception as e:
                outcomes.append(dict(file=p.name, status='failed', error=str(e))); print(str(e), flush=True)
    dump(root / 'acquisition.json', outcomes)
    if udacity:
        # The archive's header is incorrect; use the upstream corrected CSV.
        p = root / 'raw' / 'labels_crowdai.csv'
        if not p.exists():
            r = requests.get(SOURCES['udacity']['labels_api'], headers={'Accept': 'application/vnd.github.raw+json'}, timeout=60)
            r.raise_for_status()
            if r.text.startswith('{'):
                j = r.json()
                if j.get('content'): p.write_bytes(base64.b64decode(j['content']))
                else: download(j['download_url'], p)
            else: p.write_bytes(r.content)

def split_for(source, stem, frame_index=None):
    # RDD lacks authoritative drive IDs. Numeric blocks keep neighbors together;
    # a guard band around every 100-frame boundary reduces adjacency leakage.
    m = re.search(r'(\d+)$', stem)
    number = frame_index if frame_index is not None else int(m.group(1)) if m else 0
    group = f'{source}:{number // 100}'
    if (m or frame_index is not None) and number % 100 < 5: return 'guard', group
    h = int(hashlib.sha256(group.encode()).hexdigest()[:8], 16) % 100
    return ('train' if h < 80 else 'val' if h < 90 else 'test'), group

class Builder:
    def __init__(self, root, task, names):
        self.path = root / task; self.path.mkdir(parents=True, exist_ok=True)
        self.names = names; self.rows = []; self.seen = {}; self.counts = Counter(); self.reject = Counter()
    def add(self, source, stem, raw, objects, metadata=None, frame_index=None):
        split, group = split_for(source, stem, frame_index)
        if split == 'guard': self.reject['temporal_guard_band'] += 1; return
        sha = hashlib.sha256(raw).hexdigest()
        if sha in self.seen: self.reject['exact_duplicate'] += 1; return
        with Image.open(io.BytesIO(raw)) as im:
            w, h = im.size
            labels = []
            for cls, box in objects:
                x1, y1, x2, y2 = box
                x1, x2 = max(0, x1), min(w, x2); y1, y2 = max(0, y1), min(h, y2)
                if x2 <= x1 or y2 <= y1: self.reject['invalid_box'] += 1; continue
                labels.append((cls, (x1+x2)/(2*w), (y1+y2)/(2*h), (x2-x1)/w, (y2-y1)/h))
            name = source + '_' + stem
            p = self.path / 'images' / split / (name + '.jpg'); p.parent.mkdir(parents=True, exist_ok=True)
            im = im.convert('RGB'); im.thumbnail((640, 640)); im.save(p, quality=90)
        lp = self.path / 'labels' / split / (name + '.txt'); lp.parent.mkdir(parents=True, exist_ok=True)
        lp.write_text('\n'.join(str(c) + ' ' + ' '.join(f'{v:.7f}' for v in b) for c, *b in labels) + ('\n' if labels else ''))
        self.seen[sha] = split; self.counts[split] += 1
        for c, *_ in labels: self.counts[f'{split}/{self.names[c]}'] += 1
        self.rows.append(dict(id=name, source=source, group=group, split=split, image_sha256=sha,
                              image=str(p.relative_to(self.path)).replace('\\','/'), width=w, height=h,
                              objects=[dict(class_name=self.names[c], box_yolo=b) for c,*b in labels], **(metadata or {})))
    def finish(self):
        (self.path / 'manifest.jsonl').write_text(''.join(json.dumps(x) + '\n' for x in self.rows), encoding='utf-8')
        # Relative path works when run from this folder; train_yolo.py resolves it explicitly.
        for split in ['train','val','test']:
            (self.path / (split+'.txt')).write_text(''.join('./'+r['image']+'\n' for r in self.rows if r['split']==split))
        (self.path / 'data.yaml').write_text('path: .\ntrain: train.txt\nval: val.txt\ntest: test.txt\nnames:\n' + ''.join(f'  {i}: {n}\n' for i,n in enumerate(self.names)))
        result = dict(images=len(self.rows), counts=dict(self.counts), excluded=dict(self.reject),
            split_method='SHA256 of source:numeric-frame-block-100, 5-frame guard band; proxy grouping, not verified drive IDs',
            evaluation_limit='Held-out splits are development estimates; verify on separately recorded local drives. No claim of route-disjoint evaluation.',
            classes=self.names)
        dump(self.path / 'report.json', result)
        return result

def build_rdd(root, countries):
    classes = SOURCES['rdd2022']['native_classes']; b = Builder(root, 'road-damage', list(classes.values()))
    for country in countries:
        p = root / 'raw' / f'RDD2022_{country}.zip'
        if not p.exists(): continue
        with zipfile.ZipFile(p) as z:
            names = z.namelist(); images = {Path(n).stem:n for n in names if n.lower().endswith(('.jpg','.jpeg','.png')) and '/train/' in '/' + n}
            xmls = [n for n in names if n.endswith('.xml') and '/train/' in '/' + n]
            for n in sorted(xmls):
                stem = Path(n).stem
                if stem not in images: b.reject['missing_image'] += 1; continue
                x = ET.fromstring(z.read(n)); objects=[]; unknown=False
                for obj in x.findall('object'):
                    cls = obj.findtext('name'); box = obj.find('bndbox')
                    if cls not in classes: unknown=True; continue
                    vals = tuple(float(box.findtext(k)) for k in ['xmin','ymin','xmax','ymax'])
                    objects.append((list(classes).index(cls), vals))
                if unknown: b.reject['unmapped_annotation_image'] += 1; continue
                b.add('rdd2022-'+country, stem, z.read(images[stem]), objects,
                      {'annotation':n,'license':'CC-BY-SA-4.0','label_origin':'human_dataset_annotation'})
        print(f'Converted {country}: {len(b.rows)} cumulative images', flush=True)
    if not b.rows: raise RuntimeError('No labeled RDD images; acquisition must complete first')
    return b, b.finish()

def build_udacity(root):
    archive=root/'raw'/'udacity-crowdai.tar.gz'; labels=root/'raw'/'labels_crowdai.csv'
    if not archive.exists() or not labels.exists(): return None
    classes=SOURCES['udacity']['native_classes']; rows=defaultdict(list)
    with labels.open(newline='') as f:
        for r in csv.DictReader(f):
            if r['Label'] in classes:
                rows[Path(r['Frame']).name].append((list(classes).index(r['Label']),tuple(float(r[k]) for k in ['xmin','ymin','xmax','ymax'])))
    b=Builder(root,'road-users',list(classes.values()))
    order={name:i for i,name in enumerate(sorted(rows))}
    with tarfile.open(archive,'r|*') as t:
        for m in t:
            if m.isfile() and Path(m.name).name in rows:
                name=Path(m.name).name
                b.add('udacity-crowdai',Path(name).stem,t.extractfile(m).read(),rows[name],
                      {'license':'educational-only','label_origin':'upstream_corrected_annotation'},frame_index=order[name])
    return b,b.finish()

def gemma(root, builders):
    # Use the app's exact prompt framing, with targets derived solely from real labels.
    from importlib.util import spec_from_file_location, module_from_spec
    spec=spec_from_file_location('roadmesh_copilot_dataset', HERE.parent/'copilot'/'dataset.py')
    module=module_from_spec(spec);spec.loader.exec_module(module)
    path=root/'gemma';path.mkdir(exist_ok=True)
    files={s:(path/(s+'.jsonl')).open('w',encoding='utf-8') for s in ['train','val','test']}
    counts=Counter();seen=set()
    try:
        for b in builders:
            for row in b.rows:
                if not row['objects']: continue
                counts_by=Counter(x['class_name'].replace('_',' ') for x in row['objects'])
                # Pixel regions are grounded in annotations; no imagined real-world distances.
                located=[]
                for obj in row['objects'][:3]:
                    x,y,w,h=obj['box_yolo']; region=('left' if x<.33 else 'right' if x>.67 else 'center')+' '+('upper' if y<.33 else 'lower' if y>.67 else 'middle')
                    located.append(f"{obj['class_name'].replace('_',' ')} at {round(x*100)}% image width, {round(y*100)}% image height ({region})")
                facts='Annotated road image contains '+', '.join(f'{v} {k}' for k,v in sorted(counts_by.items()))+'. Image locations: '+'; '.join(located)+'. No measured distance, speed, motion or lane position is available.'
                completion='The annotations show '+', '.join(f'{v} {k}' for k,v in sorted(counts_by.items()))+'. Motion and distance cannot be determined from these labels.'
                prompt=module.prompt('Describe only the supplied object annotations in at most two short sentences.',facts)
                key=hashlib.sha256(prompt.encode()).hexdigest()
                if key in seen: continue
                seen.add(key)
                sample=dict(prompt=prompt,completion=completion,source_id=row['id'],source=row['source'],split=row['split'],group=row['group'],
                    label_origin='template_from_real_annotations',license=row['license'],review_status='automatic_grounded_template')
                files[row['split']].write(json.dumps(sample,ensure_ascii=False)+'\n');counts[row['split']]+=1
        # Unknown-motion questions must not fabricate braking/overtaking from a still image.
        for i, q in enumerate(['Is that car braking?','Can I overtake?','How far is the pothole?','Is the road clear?']):
            p=module.prompt(module.task_question(q),'Only a still-image object annotation is available. No speed, distance, movement, current lane or mesh receipt is measured.')
            files['train'].write(json.dumps(dict(prompt=p,completion="I cannot tell from these annotations; current motion and road conditions are not measured.",source='roadmesh-curated-unknown',label_origin='authored_template',split='train'))+'\n');counts['train']+=1
    finally:
        for f in files.values():f.close()
    dump(path/'report.json',dict(counts=counts,description='Unique annotation-grounded text pairs; not human driving explanations. Image source splits retained. Repeated prompts removed globally.',prompt_contract='tools/copilot/dataset.py:prompt'))
    return dict(counts)

def cached_builder(root, task):
    """Reuse an already completed conversion; only manifests define membership."""
    report=json.loads((root/task/'report.json').read_text())
    b=Builder(root,task,report['classes'])
    b.rows=[json.loads(line) for line in (root/task/'manifest.jsonl').read_text().splitlines()]
    if len(b.rows)!=report['images']: raise ValueError('Incomplete cached manifest')
    if any(not (b.path/r['image']).is_file() for r in b.rows): raise ValueError('Missing cached image')
    return b,report

def main():
    p=argparse.ArgumentParser();p.add_argument('--download',action='store_true');p.add_argument('--build',action='store_true');p.add_argument('--udacity',action='store_true')
    p.add_argument('--root',type=Path,default=ROOT);p.add_argument('--countries',nargs='+',default=SOURCES['rdd2022']['countries'])
    p.add_argument('--reuse-road-users',action='store_true',help='Reuse a completed road-users manifest without decoding images again')
    p.add_argument('--reuse-road-damage',action='store_true',help='Reuse a completed road-damage manifest and its recorded source membership')
    a=p.parse_args()
    a.root.mkdir(parents=True,exist_ok=True)
    if any(c not in SOURCES['rdd2022']['countries'] for c in a.countries):p.error('Unknown country')
    dump(a.root/'sources.json',SOURCES)
    if a.download:acquire(a.root,a.countries,a.udacity)
    if a.build:
        b,r=cached_builder(a.root,'road-damage') if a.reuse_road_damage else build_rdd(a.root,a.countries)
        builders=[b];reports={'road-damage':r}
        other=cached_builder(a.root,'road-users') if a.reuse_road_users else build_udacity(a.root)
        if other:builders.append(other[0]);reports['road-users']=other[1]
        reports['gemma']=gemma(a.root,builders);dump(a.root/'aggregate-report.json',reports)
        print(json.dumps(reports,indent=2))
if __name__=='__main__':main()
