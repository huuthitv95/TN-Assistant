"""Importer contract tests; synthetic OSM is explicitly not Vietnam coverage."""
import json
from pathlib import Path
import subprocess
import sys
import tempfile
import unittest
from import_osm import ROOT, speed, bearing, inside, road_segments, distance, source_timestamp

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
            source.with_suffix('.osm.source.json').write_text(json.dumps({
                'url':'https://example.invalid/synthetic-osm-fixture',
                'last_modified':'Thu, 01 Jan 2026 00:00:00 GMT'}),encoding='utf-8')
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

class RoadSegmentsTest(unittest.TestCase):
    def test_source_timestamp_never_uses_copy_time(self):
        with self.assertRaises(ValueError): source_timestamp('',{})
        with self.assertRaises(ValueError): source_timestamp('2999-01-01T00:00:00Z',{})
        with self.assertRaises(ValueError): source_timestamp('2026-01-01T00:00:00',{})
        self.assertEqual('2026-01-01T00:00:00+00:00',source_timestamp('',{'last_modified':'Thu, 01 Jan 2026 00:00:00 GMT'}))
        self.assertEqual('2026-01-01T00:00:00+00:00',source_timestamp('2026-01-01T07:00:00+07:00',{}))

    def test_splits_long_roads_into_directed_midpoint_segments(self):
        rows = list(road_segments([(10, 106), (10.002, 106)], {'maxspeed': '50'}, 12, 1))
        self.assertEqual(10, len(rows))
        self.assertEqual(10, len({r['id'] for r in rows}))
        for r in rows:
            start = r['start_latitude'], r['start_longitude']
            end = r['end_latitude'], r['end_longitude']
            self.assertLessEqual(distance(start, end), 50.01)
            self.assertAlmostEqual((start[0]+end[0])/2, r['latitude'])
            self.assertEqual(50, r['max_speed'])

    def test_reverse_oneway_keeps_backward_speed_and_geometry(self):
        rows = list(road_segments([(10, 106), (10.0002, 106)],
                                 {'oneway': '-1', 'maxspeed:backward': '40', 'maxspeed:forward': '90'}, 3, 1))
        self.assertEqual(1, len(rows))
        self.assertEqual(40, rows[0]['max_speed'])
        self.assertAlmostEqual(180, rows[0]['bearing'])
        self.assertGreater(rows[0]['start_latitude'], rows[0]['end_latitude'])

    def test_missing_nodes_are_not_bridged(self):
        self.assertEqual([], list(road_segments([(10, 106), None, (10.0002, 106)], {}, 1, 1)))

    def test_conditional_or_unknown_limits_stay_unknown(self):
        for tags in ({}, {'maxspeed': 'signals'}, {'maxspeed': '50', 'maxspeed:conditional': '30 @ wet'}):
            self.assertTrue(all(r['max_speed'] is None for r in road_segments([(10,106),(10.0002,106)], tags, 1, 1)))



if __name__=='__main__':unittest.main()
