"""Read selected countries from the official RDD ZIP using verified HTTP ranges.

Only required 8 MiB blocks are downloaded; every extracted member is ZIP CRC checked.
Source full archive MD5 is recorded but cannot be verified without downloading it all.
"""
import argparse, io, json, zipfile, time, hashlib
from collections import OrderedDict
from pathlib import Path
import requests

class RangeFile(io.RawIOBase):
    BLOCK=8*1024*1024
    def __init__(self,url,size):
        self.url=url;self.size=size;self.pos=0;self.cache=OrderedDict();self.bytes=0;self.etag=None
        self.session=requests.Session()
    def readable(self):return True
    def seekable(self):return True
    def tell(self):return self.pos
    def seek(self,off,whence=0):
        self.pos=off if whence==0 else self.pos+off if whence==1 else self.size+off
        if self.pos<0:raise ValueError('negative seek')
        return self.pos
    def block(self,idx):
        if idx in self.cache:self.cache.move_to_end(idx);return self.cache[idx]
        start=idx*self.BLOCK;end=min(self.size,start+self.BLOCK)-1
        for attempt in range(4):
            try:
                # A fresh redirect prevents reusing expired signed storage URLs.
                r=self.session.get(self.url,headers={'Range':f'bytes={start}-{end}','Accept-Encoding':'identity'},timeout=(25,120))
                if r.status_code!=206:raise ValueError(f'Range request returned {r.status_code}; refusing full archive')
                if r.headers.get('Content-Range')!=f'bytes {start}-{end}/{self.size}':raise ValueError('Unexpected content range')
                tag=r.headers.get('ETag')
                if self.etag and tag!=self.etag:raise ValueError('Archive changed while reading')
                self.etag=tag
                b=r.content
                if len(b)!=end-start+1:raise ValueError('Incomplete range')
                self.bytes+=len(b);self.cache[idx]=b
                while len(self.cache)>3:self.cache.popitem(last=False)
                return b
            except requests.RequestException:
                if attempt==3:raise
                time.sleep(attempt+1)
    def read(self,n=-1):
        if n<0:n=self.size-self.pos
        n=min(n,self.size-self.pos);out=[]
        while n>0:
            i=self.pos//self.BLOCK;offset=self.pos%self.BLOCK;b=self.block(i);chunk=b[offset:offset+n]
            if not chunk:raise EOFError('Short range')
            out.append(chunk);self.pos+=len(chunk);n-=len(chunk)
        return b''.join(out)

def main():
    p=argparse.ArgumentParser();p.add_argument('--root',type=Path,default=Path(__file__).parent/'artifacts');p.add_argument('--probe',action='store_true');
    p.add_argument('--countries',nargs='+',default=['India','Japan','Czech','United_States','China_MotorBike']);a=p.parse_args()
    r=requests.get('https://api.figshare.com/v2/articles/21431547',timeout=45);r.raise_for_status();meta=r.json()
    a.root.mkdir(parents=True,exist_ok=True);(a.root/'figshare-source.json').write_text(json.dumps(meta,indent=2),encoding='utf-8')
    f=next(x for x in meta['files'] if x['name'].endswith('.zip'));remote=RangeFile(f['download_url'],f['size'])
    with zipfile.ZipFile(remote) as z:
        infos=z.infolist();print(json.dumps({'entries':len(infos),'first':[i.filename for i in infos[:12]],'source_size':f['size']}),flush=True)
        if a.probe:return
        raw=a.root/'raw';raw.mkdir(exist_ok=True)
        for country in a.countries:
            dest=raw/f'RDD2022_{country}.zip';mfile=dest.with_suffix('.zip.json')
            if dest.exists() and mfile.exists():print('Cached '+country,flush=True);continue
            nested=next((i for i in infos if Path(i.filename).name==country+'.zip'),None)
            if nested:
                tmp=dest.with_suffix('.zip.part');h=hashlib.sha256();copied=0;tick=time.monotonic()
                with z.open(nested) as incoming,tmp.open('wb') as outgoing:
                    while True:
                        chunk=incoming.read(1024*1024)
                        if not chunk:break
                        outgoing.write(chunk);h.update(chunk);copied+=len(chunk)
                        if time.monotonic()-tick>20:print(f'{country}: {copied/1e6:.0f}/{nested.file_size/1e6:.0f} MB',flush=True);tick=time.monotonic()
                tmp.replace(dest)
                mfile.write_text(json.dumps(dict(url=f['download_url'],source_file_id=f['id'],source_etag=remote.etag,
                    source_md5=f['computed_md5'],source_md5_verified=False,integrity='Original nested country ZIP; outer member CRC verified',
                    members=1,bytes=copied,sha256=h.hexdigest(),license='CC-BY-SA-4.0 per author repository'),indent=2))
                print('Finished '+country,flush=True);continue
            selected=[i for i in infos if country in Path(i.filename).parts and '/train/' in '/'+i.filename and i.filename.lower().endswith(('.jpg','.jpeg','.png','.xml'))]
            if not selected:raise ValueError('No members for '+country)
            selected.sort(key=lambda i:i.header_offset)
            tmp=dest.with_suffix('.zip.part');count=0
            with zipfile.ZipFile(tmp,'w',compression=zipfile.ZIP_STORED,allowZip64=True) as out:
                for i in selected:
                    out.writestr(i.filename,z.read(i));count+=1
                    if count%1000==0:print(f'{country}: {count}/{len(selected)} members, {remote.bytes/1e6:.0f} MB fetched',flush=True)
            tmp.replace(dest);h=hashlib.sha256()
            with dest.open('rb') as fi:
                for b in iter(lambda:fi.read(1024*1024),b''):h.update(b)
            mfile.write_text(json.dumps(dict(url=f['download_url'],source_file_id=f['id'],source_etag=remote.etag,
                source_md5=f['computed_md5'],source_md5_verified=False,integrity='CRC verified for every selected original ZIP entry',
                members=count,bytes=dest.stat().st_size,sha256=h.hexdigest(),license='CC-BY-SA-4.0 per author repository'),indent=2))
            print('Finished '+country,flush=True)
if __name__=='__main__':main()
