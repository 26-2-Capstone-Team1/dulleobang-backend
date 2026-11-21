"""Run the PR #2 room pipeline, a private removal, or a furniture engine adapter."""
from __future__ import annotations
import hashlib
import json
import os
from pathlib import Path
import subprocess
from tempfile import TemporaryDirectory
import traceback
import uuid
import zipfile
import httpx
from .failures import (
    ENGINE_DEPENDENCY_EXIT,
    INVALID_ENGINE_DATA_EXIT,
    problem_code,
    retryable_status,
)
INPUT_SUFFIXES = {
    "application/json": ".json",
    "application/zip": ".zip",
    "image/jpeg": ".jpg",
    "image/png": ".png",
    "model/vnd.usdz+zip": ".usdz",
}


def download(grant, target, check=None):
    expected = grant['asset']
    digest, total = hashlib.sha256(), 0
    with httpx.stream('GET',grant['url'],timeout=180,follow_redirects=False,trust_env=False) as response:
        response.raise_for_status()
        with target.open('wb') as stream:
            for chunk in response.iter_bytes():
                if check is not None:
                    check()
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
    from .pr2 import convert_export

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
    from .pr2 import convert_removal

    folder=output/'downloads'
    folder.mkdir(exist_ok=True)
    files={}
    for grant in assignment['input'].get('meshDownloads',[]):
        fid=grant['asset']['fileId']
        path=folder/(str(uuid.UUID(fid))+'.glb')
        download(grant,path)
        files[fid]=path
    convert_removal(assignment,output,files)


def local_input(grant, folder):
    asset = grant["asset"]
    file_id = str(uuid.UUID(asset["fileId"]))
    target = folder / (file_id + INPUT_SUFFIXES.get(asset["contentType"], ""))
    if not target.exists():
        download(grant, target)
    return {
        "path": str(target),
        "asset": {key: asset[key] for key in ("fileId", "format", "contentType", "sha256", "byteSize")},
    }


def reconstruct_furniture(assignment, output):
    engine = os.environ["DULLEOBANG_FURNITURE_ENGINE"]
    inputs = assignment["input"]
    with TemporaryDirectory(prefix="furniture-input-", dir=output.parent) as directory:
        manifest_path = prepare_furniture_input(inputs, Path(directory))
        environment = os.environ.copy()
        environment.pop("DULLEOBANG_ASSIGNMENT", None)
        process = subprocess.run(
            [engine, str(manifest_path), str(output)], env=environment, check=False
        )
        if process.returncode == ENGINE_DEPENDENCY_EXIT:
            raise SystemExit(ENGINE_DEPENDENCY_EXIT)
        if process.returncode == INVALID_ENGINE_DATA_EXIT:
            raise ValueError("Furniture engine rejected the capture")
        process.check_returncode()


def prepare_furniture_input(inputs, folder):
    furniture = inputs.get("furniture")
    category = furniture.get("category") if isinstance(furniture, dict) else inputs.get("category")
    if not isinstance(category, str) or not category.strip():
        raise ValueError("Furniture capture requires a category")
    manifest = {
        "captureId": inputs["captureId"],
        "category": category,
        "measuredDimensions": inputs.get("measuredDimensions"),
    }
    if furniture is not None:
        manifest["furniture"] = furniture
    for key in ("archive", "manifest", "roomplan", "roomplanStructure"):
        grant = inputs.get(key)
        if grant is not None:
            manifest[key] = local_input(grant, folder)
    for key in ("images", "depths", "confidences"):
        grants = inputs.get(key, []) if "archive" not in manifest else []
        manifest[key] = [local_input(grant, folder) for grant in grants]
    if "archive" not in manifest and ("manifest" not in manifest or not manifest["images"]):
        raise ValueError("Expected a furniture capture archive or a manifest and images")
    manifest_path = folder / "input.json"
    manifest_path.write_text(json.dumps(manifest), encoding="utf-8")
    return manifest_path


def main():
    assignment=json.loads(Path(os.environ['DULLEOBANG_ASSIGNMENT']).read_text())
    output=Path(os.environ['DULLEOBANG_OUTPUT']).resolve()
    if assignment['type']=='ROOM_RECONSTRUCTION': reconstruct(assignment,output)
    elif assignment['type']=='ROOM_OBJECT_REMOVAL': remove(assignment,output)
    elif assignment['type']=='FURNITURE_RECONSTRUCTION':
        if os.environ.get("FURNITURE_REPO"):
            from .furniture import run_pipeline

            run_pipeline(output)
        else:
            reconstruct_furniture(assignment, output)
    else: raise ValueError('Unsupported AI job type')

if __name__ == "__main__":
    try:
        main()
    except (ValueError, KeyError, TypeError, zipfile.BadZipFile):
        traceback.print_exc()
        raise SystemExit(INVALID_ENGINE_DATA_EXIT)
    except httpx.HTTPStatusError as error:
        status = error.response.status_code
        print(f"Engine input download failed (HTTP {status})", flush=True)
        transient = retryable_status(status) or problem_code(error.response) == "INVALID_STORAGE_GRANT"
        raise SystemExit(ENGINE_DEPENDENCY_EXIT if transient else INVALID_ENGINE_DATA_EXIT)
    except httpx.RequestError:
        print("Engine input service unavailable", flush=True)
        raise SystemExit(ENGINE_DEPENDENCY_EXIT)
