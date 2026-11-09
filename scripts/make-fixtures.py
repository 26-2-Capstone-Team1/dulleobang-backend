"""Rebuild public synthetic cross-language fixtures from the frozen legacy copy."""
from pathlib import Path
from io import BytesIO
import sys, json, hashlib, struct

ROOT = Path(__file__).resolve().parents[1]
sys.path.insert(0, str(ROOT / 'legacy-baseline'))
sys.path.insert(0, str(ROOT / 'legacy-baseline/tests'))
import jwt
import rfc8785
from argon2 import PasswordHasher
from PIL import Image
from conftest import glb, ply
from test_capture_zip import capture_files, archive

OUT = ROOT / 'src/test/resources/fixtures'
OUT.mkdir(parents=True, exist_ok=True)
secrets = {'jwt': 'public-user-fixture-key-00000000000001',
           'worker': 'public-worker-fixture-key-000000000001',
           'storage': 'public-storage-fixture-key-00000000001'}
at = 1800000000
user = '11111111-1111-4111-8111-111111111111'
sid = '22222222-2222-4222-8222-222222222222'
claims = {'iss':'dulleobang','aud':'dulleobang-mobile','sub':user,'sid':sid,'iat':at,'exp':at+900}
storage = {'iss':'dulleobang-storage','aud':'storage','file':user,'method':'GET','sha':'a'*64,'exp':at+900}
worker = {'iss':'dulleobang-worker','aud':'dulleobang-internal','sub':'fixture-worker','scope':'AI_WORKER','iat':at,'exp':at+900}
cursor = {'aud':'cursor','exp':at+1800,'binding':'b'*64,'ids':sid,'offset':1}
tokens = {'user':jwt.encode(claims,secrets['jwt'],algorithm='HS256'),
          'storage':jwt.encode(storage,secrets['storage'],algorithm='HS256'),
          'worker':jwt.encode(worker,secrets['worker'],algorithm='HS256'),
          'cursor':jwt.encode(cursor,secrets['storage'],algorithm='HS256')}
cases = [{'b':2,'a':1}, {'number':1e-7,'big':1e20,'small':0.000001},
         {'한글':'둘러방','escaped':'\n\"\\','array':[True,None,1.5]}, {'z':-0.0,'x':333333333.3333333}]
fixture = {'publicTestOnly':True,'epoch':at,'secrets':secrets,'userId':user,'sessionId':sid,
           'password':'PublicFixturePassword!123',
           'passwordHash':PasswordHasher().hash('PublicFixturePassword!123',salt=b'fixture-salt-1234'),
           'tokens':tokens,'canonical':[{'input':json.dumps(v,ensure_ascii=False),'output':rfc8785.dumps(v).decode(),'sha256':hashlib.sha256(rfc8785.dumps(v)).hexdigest()} for v in cases]}
(OUT/'compatibility.json').write_text(json.dumps(fixture,ensure_ascii=False,indent=2),encoding='utf-8')
(OUT/'scene.ply').write_bytes(ply())
(OUT/'model.glb').write_bytes(glb())
(OUT/'capture.zip').write_bytes(archive(capture_files()))
depth = Image.new('I;16',(2,2)); depth.putdata([0,1200,5000,65535]); depth.save(OUT/'depth.png')
confidence = Image.new('L',(2,2)); confidence.putdata([0,1,2,2]); confidence.save(OUT/'confidence.png')
rgb = Image.new('RGB',(8,6),'red'); exif = Image.Exif(); exif[315]='synthetic fixture artist'
rgb.save(OUT/'photo.jpg',exif=exif)
manifest = {p.name:hashlib.sha256(p.read_bytes()).hexdigest() for p in OUT.iterdir() if p.is_file() and p.name != 'sha256.json'}
(OUT/'sha256.json').write_text(json.dumps(manifest,indent=2),encoding='utf-8')
print('Generated synthetic fixtures:',len(manifest))
