# RESILANE co-pilot fine-tune, run as a Kaggle GPU script (tools/copilot/push_kaggle.sh bundles dataset.py above this).
# Gemma 2B-it + LoRA on generated road-safety examples, then merged and converted to the MediaPipe CPU int4 .bin the
# phone loads. Writes before/after scores on held-out prompts so the improvement is measured, not claimed.
import glob, json, math, os, re, shutil, subprocess, sys, time
subprocess.run([sys.executable, "-m", "pip", "install", "-q", "peft==0.13.2", "mediapipe==0.10.30", "sentencepiece"], check=True)
import torch
from transformers import AutoModelForCausalLM, AutoTokenizer
from peft import LoraConfig, get_peft_model

# Kaggle mounts models under a path that has changed over time; find the Gemma 2B-it checkpoint by its config.
_configs = [p for p in glob.glob("/kaggle/input/**/config.json", recursive=True) if "2b" in p.lower()]
print("model configs found:", _configs, flush=True)
BASE = os.path.dirname(sorted(_configs)[-1])
OUT = "/kaggle/working"; TMP = "/kaggle/tmp"; os.makedirs(TMP, exist_ok=True)
train = make(6000, 7)
held = [x for x in make(400, 1234) if x["prompt"] not in {t["prompt"] for t in train}][:40]
tok = AutoTokenizer.from_pretrained(BASE)
model = AutoModelForCausalLM.from_pretrained(BASE, torch_dtype=torch.float16, device_map={"": 0})

def generate(m, prompts, max_new=64):
    outs = []
    m.eval()
    for p in prompts:
        ids = tok(p, return_tensors="pt", add_special_tokens=True).to(0)
        with torch.no_grad():
            g = m.generate(**ids, max_new_tokens=max_new, do_sample=False)
        outs.append(tok.decode(g[0][ids["input_ids"].shape[1]:], skip_special_tokens=True).strip())
    return outs

def score(rows, outs):
    """Share of answers that open with filler, use a number absent from the facts, call something safe, or run long."""
    s = {"sure": 0, "unsupported_number": 0, "says_safe": 0, "over_40_words": 0}
    for r, o in zip(rows, outs):
        facts = set(re.findall(r"\d+", r["prompt"]))
        if re.match(r"\s*(sure|okay|ok|certainly|of course)\b", o, re.I): s["sure"] += 1
        if any(n not in facts for n in re.findall(r"\d+", o)): s["unsupported_number"] += 1
        if re.search(r"\bsafe to\b|\bit is safe\b", o, re.I): s["says_safe"] += 1
        if len(o.split()) > 40: s["over_40_words"] += 1
    return {k: round(v / len(rows), 3) for k, v in s.items()}

t0 = time.time()
before = generate(model, [r["prompt"] for r in held])
report = {"base": BASE, "train_examples": len(train), "held_out": len(held), "before": score(held, before)}
print("before", report["before"], f"{time.time() - t0:.0f}s", flush=True)

model.gradient_checkpointing_enable(); model.enable_input_require_grads()
model = get_peft_model(model, LoraConfig(r=16, lora_alpha=32, lora_dropout=0.05, task_type="CAUSAL_LM",
                                         target_modules=["q_proj", "k_proj", "v_proj", "o_proj", "gate_proj", "up_proj", "down_proj"]))
for n, p in model.named_parameters():
    if p.requires_grad: p.data = p.data.float()
model.print_trainable_parameters()

def encode(row):
    p = tok(row["prompt"], add_special_tokens=True)["input_ids"]
    a = tok(row["completion"] + "<end_of_turn>", add_special_tokens=False)["input_ids"] + [tok.eos_token_id]
    ids = (p + a)[:384]
    return ids, ([-100] * len(p) + a)[:384]
data = [encode(r) for r in train]
EPOCHS, BATCH, ACCUM, LR = 2, 2, 8, 2e-4  # Gemma's 256k vocabulary makes logits large; small batches fit 16 GB
opt = torch.optim.AdamW([p for p in model.parameters() if p.requires_grad], lr=LR, weight_decay=0.0)
steps = EPOCHS * math.ceil(len(data) / (BATCH * ACCUM))
sched = torch.optim.lr_scheduler.LambdaLR(opt, lambda s: min(1.0, (s + 1) / 30) * max(0.05, 0.5 * (1 + math.cos(math.pi * s / steps))))
scaler = torch.cuda.amp.GradScaler()
model.train(); step = 0; g = torch.Generator().manual_seed(0)
for epoch in range(EPOCHS):
    order = torch.randperm(len(data), generator=g).tolist()
    for i in range(0, len(order), BATCH):
        batch = [data[j] for j in order[i:i + BATCH]]
        L = max(len(x[0]) for x in batch)
        ids = torch.tensor([x[0] + [tok.pad_token_id] * (L - len(x[0])) for x in batch]).to(0)
        lab = torch.tensor([x[1] + [-100] * (L - len(x[1])) for x in batch]).to(0)
        att = (torch.arange(L)[None, :] < torch.tensor([len(x[0]) for x in batch])[:, None]).long().to(0)
        with torch.autocast("cuda", dtype=torch.float16):
            loss = model(input_ids=ids, attention_mask=att, labels=lab).loss / ACCUM
        scaler.scale(loss).backward()
        if (i // BATCH + 1) % ACCUM == 0:
            scaler.unscale_(opt); torch.nn.utils.clip_grad_norm_(model.parameters(), 1.0)
            scaler.step(opt); scaler.update(); opt.zero_grad(set_to_none=True); sched.step(); step += 1
            if step % 25 == 0: print(f"epoch {epoch} step {step}/{steps} loss {loss.item() * ACCUM:.4f} {time.time() - t0:.0f}s", flush=True)

model.gradient_checkpointing_disable()
after = generate(model, [r["prompt"] for r in held])
report["after"] = score(held, after)
report["samples"] = [{"facts": r["prompt"].split("Facts: ")[1].split("<end_of_turn>")[0], "base": b, "tuned": a, "reference": r["completion"]}
                     for r, b, a in list(zip(held, before, after))[:15]]
print("after", report["after"], flush=True)
model.save_pretrained(f"{OUT}/lora")
json.dump(report, open(f"{OUT}/copilot_eval.json", "w"), indent=1)

merged = model.merge_and_unload().half()
mdir = f"{TMP}/merged"; merged.save_pretrained(mdir, safe_serialization=True, max_shard_size="3GB"); tok.save_pretrained(mdir)
for f in glob.glob(f"{BASE}/tokenizer.model"): shutil.copy(f, mdir)
del merged, model; torch.cuda.empty_cache()

from mediapipe.tasks.python.genai import converter
target = f"{OUT}/gemma-2b-it-resilane-cpu-int4.bin"
cfg = converter.ConversionConfig(input_ckpt=mdir, ckpt_format="safetensors", model_type="GEMMA_2B", backend="cpu",
                                 output_dir=f"{TMP}/conv", combine_file_only=False, vocab_model_file=f"{mdir}/tokenizer.model",
                                 output_tflite_file=target, attention_quant_bits=8, feedforward_quant_bits=4, embedding_quant_bits=8)
try:
    converter.convert_checkpoint(cfg)
    report["bin_bytes"] = os.path.getsize(target)
except Exception as e:  # keep the evaluation and the adapter even if conversion fails
    report["convert_error"] = repr(e)[:2000]
report["minutes"] = round((time.time() - t0) / 60, 1)
json.dump(report, open(f"{OUT}/copilot_eval.json", "w"), indent=1)
print(json.dumps({k: v for k, v in report.items() if k != "samples"}, indent=1))
shutil.rmtree(TMP, ignore_errors=True)
