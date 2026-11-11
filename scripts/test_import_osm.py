"""Importer contract tests; synthetic OSM is explicitly not Vietnam coverage."""
import json
from pathlib import Path
import subprocess
import sys
import tempfile
import unittest
from import_osm import ROOT, speed, bearing, inside

class ImportOSMTests(unittest.TestCase):
    def test_explicit_speed_only(self):
        self.assertEqual(speed('50'),50)
        self.assertEqual(speed('30 mph'),48.28)
        for missing in ('VN:urban','none','50;80','signals','walk','0','300',''):
            self.assertIsNone(speed(missing))
    def test_direction_and_path_scope(self):
        self.assertAlmostEqual(bearing((10,106),(10.1,106)),0)
        self.assertAlmostEqual(bearing((10.1,106),(10,106)),180)
        with self.assertRaises(ValueError):inside(ROOT.parent/'outside.ndjson')
    def test_osm_geometry_conditional_and_bidirectional(self):
        (ROOT/'.runtime/managed-data').mkdir(parents=True,exist_ok=True)
        with tempfile.TemporaryDirectory(dir=ROOT/'.runtime/managed-data',prefix='import-contract-') as folder:
            source=Path(folder)/'contract.osm'
            source.write_text('''<?xml version="1.0"?><osm version="0.6" generator="explicit-contract-test">
<node id="1" lat="10.0" lon="106.0" version="1"/>
<node id="2" lat="10.001" lon="106.0" version="1"/>
<node id="3" lat="10.002" lon="106.0" version="1"><tag k="highway" v="speed_camera"/></node>
<way id="10" version="1"><nd ref="1"/><nd ref="2"/><tag k="highway" v="residential"/><tag k="maxspeed" v="50"/></way>
<way id="11" version="1"><nd ref="2"/><nd ref="3"/><tag k="highway" v="residential"/><tag k="maxspeed" v="80"/><tag k="maxspeed:conditional" v="50 @ (wet)"/></way>
</osm>''',encoding='utf-8')
            output=Path(folder)/'contract.ndjson'
            result=subprocess.run([sys.executable,str(ROOT/'scripts/import_osm.py'),'--input',str(source),'--output',str(output)],capture_output=True,text=True,encoding='utf-8')
            self.assertEqual(result.returncode,0,result.stderr)
            rows=[json.loads(line) for line in output.read_text(encoding='utf-8').splitlines()]
            known=[r for r in rows if r.get('osm_way')==10]
            conditional=[r for r in rows if r.get('osm_way')==11]
            self.assertTrue(known)
            self.assertTrue(all(r['max_speed']==50 for r in known))
            self.assertTrue(all(r['max_speed'] is None for r in conditional))
            self.assertEqual({r['bearing'] for r in known},{0,180})
            camera=[r for r in rows if r['kind']=='camera'][0]
            self.assertIsNone(camera['max_speed'])
            self.assertTrue(camera['direction_unknown'])
            self.assertEqual(camera['type'],'701')
            self.assertTrue(all(r['license']=='ODbL-1.0' for r in rows))

if __name__=='__main__':unittest.main()
