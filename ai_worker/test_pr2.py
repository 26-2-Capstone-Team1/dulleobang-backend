import copy
import json
import math
import os
from pathlib import Path
import tempfile
import unittest
import uuid
from unittest.mock import patch
import numpy as np
import trimesh
from .pr2 import convert_export, convert_removal


class Pr2Tests(unittest.TestCase):
    def fixture(self, root):
        root.mkdir()
        obj=str(uuid.uuid4())
        (root/'objects').mkdir()
        room=trimesh.creation.box(extents=[6,3,6]);room.apply_translation([0,1.5,0])
        (root/'room.glb').write_bytes(room.export(file_type='glb'))
        (root/'collision.glb').write_bytes(room.export(file_type='glb'))
        (root/'objects'/f'{obj}.glb').write_bytes(trimesh.creation.box(extents=[1,2,1]).export(file_type='glb'))
        corners=[[-3,-3],[-3,3],[3,3],[3,-3]]
        walls=[{'objectId':str(uuid.uuid4()),'from':p,'to':corners[(n+1)%4], 'dimensions':{'width':6,'height':3,'depth':.1}}
               for n,p in enumerate(corners)]
        source={'capture':'capture_test','frame':'ARKit world, metres, Y up','meshRoom':{'uri':'room.glb'},
                'collisionGeometryUri':'collision.glb','roomGeometry':{'floorHeight':0,'ceilingHeight':3,'walls':walls,'doors':[],'windows':[]},
                'objects':[{'objectId':obj,'category':'chair','position':{'x':1,'y':1,'z':-1},'rotation':{'yaw':math.pi/2},
                            'dimensions':{'width':1,'height':2,'depth':1},'removable':True,'meshUri':f'objects/{obj}.glb'}],
                'glass':[],'selfCheck':{'room':{'ok':True},'objects':{'chair':{'ok':True}}}}
        (root/'space.json').write_text(json.dumps(source))
        return obj

    def points(self, path):
        data=path.read_bytes();offset=data.index(b'end_header\n')+11
        return np.frombuffer(data[offset:],dtype=[('xyz','<f4',(3,)),('rgb','u1',(3,))])['xyz']

    def test_axes_floor_and_true_mesh_removal(self):
        with tempfile.TemporaryDirectory() as temp, patch.dict(os.environ,{'BACKEND_POINT_COUNT':'20000'}):
            root=Path(temp);export=root/'export';oid=self.fixture(export)
            out=root/'converted';doc=convert_export(export,out)
            bundle=doc['result']['bundle'];obj=bundle['objects'][0]
            self.assertEqual(obj['objectId'],oid)
            self.assertAlmostEqual(obj['transform']['position']['y'],0)
            self.assertAlmostEqual(obj['transform']['rotation']['y'],math.sqrt(.5))
            self.assertEqual(bundle['geometry']['coordinateSystem'],'RH_Y_UP_Z_BACK')
            source=bundle['meshSource'];downloaded={}
            for key,filename in [('roomAsset','room.glb'),('roomCollisionAsset','collision.glb')]:
                fid=str(uuid.uuid4());source[key]={'fileId':fid};downloaded[fid]=export/filename
            fid=str(uuid.uuid4());source['objectAssets'][0]['asset']={'fileId':fid};downloaded[fid]=export/'objects'/f'{oid}.glb'
            bundle['objects'][0]['ownershipType']='PERSONAL'
            assignment={'input':{'sourceBundle':bundle,'removedObjectIds':[oid],'roomStateId':str(uuid.uuid4())}}
            removed=root/'removed';removed.mkdir()
            result=convert_removal(assignment,removed,downloaded)
            self.assertEqual(result['bundle']['objects'],[])
            self.assertEqual(result['bundle']['geometry'],bundle['geometry'])
            self.assertIn('INPAINTED_REGION',result['bundle']['quality']['warnings'])
            before,after=self.points(out/'scene.ply'),self.points(removed/'scene.ply')
            def in_object(points): return np.all((points>[.49,.1,-1.51]) & (points<[1.51,1.9,-.49]),axis=1).sum()
            self.assertGreater(in_object(before),100)
            self.assertEqual(in_object(after),0)
            self.assertEqual((removed/'sources/room.glb').read_bytes(),(export/'room.glb').read_bytes())

    def test_rejects_broken_self_check_and_external_paths(self):
        with tempfile.TemporaryDirectory() as temp:
            root=Path(temp);export=root/'export';self.fixture(export)
            data=json.loads((export/'space.json').read_text())
            data['selfCheck']['room']['ok']=False
            (export/'space.json').write_text(json.dumps(data))
            with self.assertRaises(ValueError): convert_export(export,root/'bad')
            data['selfCheck']['room']['ok']=True;data['meshRoom']['uri']='../outside.glb'
            (export/'space.json').write_text(json.dumps(data))
            with self.assertRaises(ValueError): convert_export(export,root/'outside')

if __name__=='__main__': unittest.main()
