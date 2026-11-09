"""Execute the unmodified PR #2 room pipeline or rebuild a private removal scene."""
from __future__ import annotations
import hashlib
import json
import os
from pathlib import Path
import subprocess
import uuid
import zipfile
import httpx
from .pr2 import convert_export, convert_removal


def download(grant, target):
    expected = grant['asset']
    digest, total = hashlib.sha256(), 0
    with httpx.stream('GET',grant['url'],timeout=180,follow_redirects=False,trust_env=False) as response:
        response.raise_for_status()
        with target.open('wb') as stream:
            for chunk in response.iter_bytes():
                total += len(chunk)
                if total > expected['byteSize']: raise ValueError('Input exceeds declared size')
                digest.update(chunk)
                stream.write(chunk)
    if total != expected['byteSize'] or digest.hexdigest() != expected['sha256']:
        target.unlink(missing_ok=True)
        raise ValueError('Input checksum mismatch')


def extract(archive, target):
    with zipfile.ZipFile(archive) as source:
        entries = source.infolist()
        if len(entries)>7000 or sum(e.file_size for e in entries)>5*1024**3:
            raise ValueError('Capture archive limits exceeded')
        seen=set()
        for entry in entries:
            path = (target/entry.filename).resolve()
            if (not path.is_relative_to(target.resolve()) or '\\' in entry.filename or ':' in entry.filename
                    or entry.flag_bits & 1 or (entry.external_attr >> 16) & 0o170000 == 0o120000
                    or entry.filename in seen):
                raise ValueError('Unsafe capture archive')
            seen.add(entry.filename)
        source.extractall(target)
    candidates = list(target.rglob('meta.json'))
    if len(candidates)!=1: raise ValueError('Exactly one capture metadata file is required')
    return candidates[0].parent


def reconstruct(assignment, output):
    if 'archive' not in assignment['input']:
        raise ValueError('PR #2 requires the original iPhone capture.zip upload')
    root = Path(os.environ.get('BACKEND_CAPTURE_ROOT','/data/indoor-ai-poc/backend-captures'))/assignment['jobId']
    root.mkdir(parents=True,exist_ok=True)
    marker = root/'prepared.json'
    if not marker.exists():
        archive=root/'capture.zip'
        download(assignment['input']['archive'],archive)
        capture=extract(archive,root/'input')
        meta=json.loads((capture/'meta.json').read_text())
        if meta.get('kind')!='room': raise ValueError('Expected a room capture')
        # The pipeline uses the first eight ID characters as its cache key. Isolate it per backend job.
        meta['captureId']=str(uuid.UUID(assignment['jobId']))
        (capture/'meta.json').write_text(json.dumps(meta),encoding='utf-8')
        marker.write_text(json.dumps({'captureDir':str(capture),'sha256':assignment['input']['archive']['asset']['sha256']}))
    prepared=json.loads(marker.read_text())
    if prepared['sha256']!=assignment['input']['archive']['asset']['sha256']:
        raise ValueError('A job cannot reuse another archive')
    capture_id='capture_'+assignment['jobId'][:8]
    repo=Path(os.environ.get('PR2_REPO','/mnt/cdata/dulleobang-worker/ai-poc'))
    env=os.environ.copy()
    env.update(CAPTURE=capture_id,CAPTURE_DIR=prepared['captureDir'],GPU_INDEX='0',EXPORT_POINTS='0')
    subprocess.run(['bash',str(repo/'scripts/plan/run_mesh_room.sh')],cwd=repo,env=env,check=True)
    convert_export(Path('/data/indoor-ai-poc/web/pointmesh')/capture_id/'export',output)


def remove(assignment, output):
    folder=output/'downloads'
    folder.mkdir(exist_ok=True)
    files={}
    for grant in assignment['input'].get('meshDownloads',[]):
        fid=grant['asset']['fileId']
        path=folder/(str(uuid.UUID(fid))+'.glb')
        download(grant,path)
        files[fid]=path
    convert_removal(assignment,output,files)


def main():
    assignment=json.loads(Path(os.environ['DULLEOBANG_ASSIGNMENT']).read_text())
    output=Path(os.environ['DULLEOBANG_OUTPUT']).resolve()
    if assignment['type']=='ROOM_RECONSTRUCTION': reconstruct(assignment,output)
    elif assignment['type']=='ROOM_OBJECT_REMOVAL': remove(assignment,output)
    else: raise ValueError('This worker handles PR #2 rooms and private removal only')

if __name__=='__main__': main()
