"""Framework-independent adapter for the tools/copilot prompt/completion training contract."""
import json, random
from pathlib import Path

def load_splits(path):
    root=Path(path)
    result={s:[json.loads(line) for line in (root/(s+'.jsonl')).read_text(encoding='utf-8').splitlines() if line] for s in ('train','val','test')}
    seen=set()
    for split,rows in result.items():
        for r in rows:
            if not isinstance(r.get('prompt'),str) or not isinstance(r.get('completion'),str):
                raise ValueError(f'Invalid prompt/completion in {split}')
            if r['prompt'] in seen: raise ValueError('Duplicate prompt across the aggregate')
            seen.add(r['prompt'])
    return result

def supplement(base_train, grounded, max_extra=6000, seed=42):
    held={r['prompt'] for s in ('val','test') for r in grounded[s]}
    train=[r for r in base_train if r['prompt'] not in held]
    seen={r['prompt'] for r in train}
    extra=[r for r in grounded['train'] if r['prompt'] not in seen]
    random.Random(seed).shuffle(extra)
    return train+extra[:max(0,max_extra)],grounded['val']
