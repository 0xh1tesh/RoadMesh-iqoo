"""Kaggle/local entry point. Attach artifacts; install ultralytics separately.
python train_yolo.py --data /kaggle/input/roadmesh-data/road-damage --epochs 30
The damage and road-user label spaces are deliberately separate tasks.
"""
import argparse, json
from pathlib import Path

def main():
    p=argparse.ArgumentParser();p.add_argument('--data',type=Path,required=True);p.add_argument('--epochs',type=int,default=30)
    p.add_argument('--model',default='yolo11n.pt');p.add_argument('--batch',type=int,default=16);p.add_argument('--device',default='0')
    p.add_argument('--output',type=Path,default=Path('/kaggle/working/roadmesh-yolo'));a=p.parse_args()
    import yaml
    from ultralytics import YOLO
    root=a.data.resolve();config=yaml.safe_load((root/'data.yaml').read_text());config['path']=str(root)
    a.output.mkdir(parents=True,exist_ok=True);resolved=a.output/'data-resolved.yaml';resolved.write_text(yaml.safe_dump(config))
    model=YOLO(a.model);model.train(data=str(resolved),epochs=a.epochs,imgsz=640,batch=a.batch,device=a.device,seed=42,
        project=str(a.output),name='train',exist_ok=True,workers=2)
    best=YOLO(str(a.output/'train/weights/best.pt'));metrics=best.val(data=str(resolved),split='test',device=a.device)
    exported=best.export(format='onnx',imgsz=[384,640],dynamic=False,simplify=False,opset=17)
    (a.output/'deployment.json').write_text(json.dumps(dict(classes=config['names'],image_hw=[384,640],onnx=str(exported),
        metrics=metrics.results_dict,notice='Map exported class IDs explicitly in the phone detector. Do not replace a COCO model with this head without updating class mapping.'),indent=2))
if __name__=='__main__':main()
