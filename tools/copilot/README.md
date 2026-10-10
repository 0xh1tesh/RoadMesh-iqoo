# Co-pilot fine-tune

    python tools/copilot/dataset.py --out train.jsonl --n 6000     # inspect the data locally
    # Kaggle runs one file: the dataset generator followed by the trainer.
    python -c "d=open('tools/copilot/dataset.py').read().split('if __name__')[0]; open('run.py','w').write(d+open('tools/copilot/finetune_kaggle.py').read())"
    # put run.py next to tools/kaggle/copilot/kernel-metadata.json, then: kaggle kernels push -p <that dir>

The prompt frame in `dataset.py` must stay identical to `Copilot.prompt` in the app.
