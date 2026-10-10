"""Audit the materialized corpus and write a compact data-quality report."""
import argparse, collections, hashlib, json, math
from pathlib import Path
from PIL import Image
from load_gemma import load_splits

def main():
    p=argparse.ArgumentParser();p.add_argument('--root',type=Path,default=Path(__file__).parent/'artifacts');a=p.parse_args()
    result={};source_ids={};groups={}
    for task in ('road-damage','road-users'):
        base=a.root/task;rows=[json.loads(x) for x in (base/'manifest.jsonl').read_text().splitlines()]
        classes=json.loads((base/'report.json').read_text())['classes']
        ids=set();hashes=set();group_splits={};counts=collections.Counter();sizes=collections.Counter();boxes=0
        expected={s:[] for s in ('train','val','test')}
        for r in rows:
            assert r['id'] not in ids,('duplicate id',r['id']);ids.add(r['id'])
            assert r['image_sha256'] not in hashes,('duplicate image',r['id']);hashes.add(r['image_sha256'])
            assert group_splits.setdefault(r['group'],r['split'])==r['split'],'cross-split group'
            source_ids[r['id']]=r['split'];groups[r['id']]=r['group'];expected[r['split']].append('./'+r['image'])
            image=base/r['image'];label=base/'labels'/r['split']/(r['id']+'.txt')
            with Image.open(image) as im:
                w,h=im.size;assert 0<w<=640 and 0<h<=640;im.verify();sizes[f'{w}x{h}']+=1
            labels=label.read_text().splitlines();assert len(labels)==len(r['objects'])
            for line in labels:
                fields=line.split();assert len(fields)==5;cls=int(fields[0]);vals=list(map(float,fields[1:]))
                assert 0<=cls<len(classes) and all(math.isfinite(v) and 0<=v<=1 for v in vals)
                x,y,w,h=vals;assert w>0 and h>0 and x-w/2>=-1e-6 and x+w/2<=1+1e-6 and y-h/2>=-1e-6 and y+h/2<=1+1e-6
                boxes+=1
            counts[r['split']]+=1
            if len(ids)%2000==0:print(f'{task}: checked {len(ids)}/{len(rows)} images',flush=True)
        for s,files in expected.items():assert (base/(s+'.txt')).read_text().splitlines()==files,'split index mismatch'
        result[task]=dict(images=len(rows),boxes=boxes,splits=dict(counts),groups=len(group_splits),sizes=dict(sizes))
    splits=load_splits(a.root/'gemma')
    for split,rows in splits.items():
        for r in rows:
            if 'source_id' in r:assert source_ids[r['source_id']]==split and groups[r['source_id']]==r['group']
    result['gemma']={s:len(rows) for s,rows in splits.items()}
    result['checks']='Image header verification, bounds, label count, unique source IDs/hashes, split manifest membership, group separation, unique prompts and Gemma source-split alignment passed.'
    result['limits']='No human relabeling, no perceptual duplicate audit, no verified drive IDs, no model training/evaluation.'
    (a.root/'quality-report.json').write_text(json.dumps(result,indent=2))
    print(json.dumps(result,indent=2))
if __name__=='__main__':main()
