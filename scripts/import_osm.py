"""Convert public OSM Vietnam to sourced managed-road NDJSON; optionally import.

No original-app data or credentials are read. The extract and outputs must stay
under this TN Assistant source tree. Run with pyosmium 4.3.1 in a dedicated
Python environment.
"""
import argparse
from datetime import datetime, timezone
import hashlib
import json
import math
import os
from pathlib import Path
import re
import urllib.request
from urllib.parse import urlparse

ROOT = Path(__file__).resolve().parents[1]
SOURCE = 'https://download.geofabrik.de/asia/vietnam-latest.osm.pbf'
DRIVABLE = {'motorway','motorway_link','trunk','trunk_link','primary','primary_link',
            'secondary','secondary_link','tertiary','tertiary_link','residential',
            'unclassified','service','living_street'}

def inside(path):
    result = Path(path).resolve()
    if not result.is_relative_to(ROOT):
        raise ValueError('All data paths must stay inside the TN Assistant source tree')
    return result

def speed(value):
    match = re.fullmatch(r'\s*(\d+(?:\.\d+)?)\s*(km/h|kmh|kph|mph)?\s*', value or '')
    if not match:
        return None
    number = float(match[1]) * (1.609344 if match[2] == 'mph' else 1)
    return round(number, 2) if 0 < number <= 200 else None

def distance(a, b):
    lat1, lon1, lat2, lon2 = map(math.radians, (*a, *b))
    h = math.sin((lat2-lat1)/2)**2 + math.cos(lat1)*math.cos(lat2)*math.sin((lon2-lon1)/2)**2
    return 12742000*math.asin(min(1, math.sqrt(h)))

def bearing(a, b):
    lat1, lon1, lat2, lon2 = map(math.radians, (*a, *b))
    return (math.degrees(math.atan2(math.sin(lon2-lon1)*math.cos(lat2),
            math.cos(lat1)*math.sin(lat2)-math.sin(lat1)*math.cos(lat2)*math.cos(lon2-lon1)))+360)%360

def download(file):
    file.parent.mkdir(parents=True, exist_ok=True)
    checksum = urllib.request.urlopen(SOURCE+'.md5', timeout=30).read(1024).decode().split()[0]
    if not re.fullmatch('[a-fA-F0-9]{32}', checksum):
        raise ValueError('Invalid published extract checksum')
    partial = file.with_suffix(file.suffix+'.partial')
    digest = hashlib.md5(usedforsecurity=False)
    with urllib.request.urlopen(SOURCE, timeout=30) as response, partial.open('wb') as out:
        while chunk := response.read(2 << 20):
            out.write(chunk)
            digest.update(chunk)
        modified = response.headers.get('Last-Modified')
    if digest.hexdigest() != checksum.lower():
        raise ValueError('Extract changed during download or checksum mismatch; rerun')
    os.replace(partial, file)
    return {'url': SOURCE, 'md5': checksum, 'downloaded_at': datetime.now(timezone.utc).isoformat(), 'last_modified': modified}

def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--download', action='store_true')
    parser.add_argument('--input', default=str(ROOT/'.runtime/managed-data/vietnam-latest.osm.pbf'))
    parser.add_argument('--output', default=str(ROOT/'.runtime/managed-data/vietnam-roads.ndjson'))
    parser.add_argument('--bbox', help='min_lon,min_lat,max_lon,max_lat; optional real-region subset')
    parser.add_argument('--api', help='Explicit backend origin to import; omitted means conversion only')
    parser.add_argument('--token-file', help='Ignored runtime file containing only an admin access token')
    args = parser.parse_args()
    source, output = inside(args.input), inside(args.output)
    metadata_path = source.with_suffix(source.suffix+'.source.json')
    if args.download:
        metadata_path.write_text(json.dumps(download(source), indent=2), encoding='utf-8')
    if not source.is_file():
        parser.error('Input not found; use --download once')
    import osmium
    reader = osmium.io.Reader(str(source))
    header = reader.header()
    reader.close()
    provenance = json.loads(metadata_path.read_text(encoding='utf-8')) if metadata_path.exists() else {}
    extract_source = provenance.get('url', SOURCE)
    stamp = header.get('osmosis_replication_timestamp') or datetime.fromtimestamp(source.stat().st_mtime, timezone.utc).isoformat()
    bbox = tuple(map(float,args.bbox.split(','))) if args.bbox else None
    if bbox and (len(bbox)!=4 or bbox[0]>bbox[2] or bbox[1]>bbox[3]):
        parser.error('Invalid bbox')
    output.parent.mkdir(parents=True, exist_ok=True)
    stats = {'roads':0,'camera':0,'sign':0,'known_speed':0,'unknown_speed':0,
             'source':extract_source,'source_updated_at':stamp,'license':'ODbL-1.0',
             'attribution':'© OpenStreetMap contributors', 'license_url':'https://www.openstreetmap.org/copyright', 'bbox':bbox}
    api_token = None
    if args.api:
        origin = urlparse(args.api)
        if origin.username or origin.password or origin.query or origin.fragment or origin.path not in ('','/') or not (
                origin.scheme == 'https' or origin.scheme == 'http' and origin.hostname in {'localhost','127.0.0.1','::1'}):
            parser.error('--api must be HTTPS or loopback HTTP origin')
        if args.token_file:
            token_file=inside(args.token_file)
            if not token_file.is_relative_to(ROOT/'.runtime'):
                parser.error('Admin token file must stay under the ignored .runtime directory')
            api_token=token_file.read_text(encoding='utf-8').strip()
    if args.api and not api_token:
        parser.error('--api requires --token-file; token is never printed')
    batch = []
    class NoRedirect(urllib.request.HTTPRedirectHandler):
        def redirect_request(self, req, fp, code, msg, headers, newurl):
            raise RuntimeError('Backend import redirects are disabled to protect the admin token')
    api_client=urllib.request.build_opener(NoRedirect())
    def flush():
        if args.api and batch:
            raw=json.dumps({'items':batch},ensure_ascii=False).encode()
            request=urllib.request.Request(args.api.rstrip('/')+'/v1/admin/managed/roads/import',raw,
                {'Authorization':'Bearer '+api_token,'Content-Type':'application/json'},method='POST')
            with api_client.open(request,timeout=60) as response:
                reply=json.load(response)
            if reply.get('imported') != len(batch):
                raise RuntimeError('Server did not acknowledge entire import batch')
        batch.clear()
    with output.open('w',encoding='utf-8',newline='\n') as out:
        def emit(row):
            lat,lon=row['latitude'],row['longitude']
            if bbox and not (bbox[0]<=lon<=bbox[2] and bbox[1]<=lat<=bbox[3]):
                return
            row.update(source=extract_source,source_updated_at=stamp,license='ODbL-1.0')
            out.write(json.dumps(row,ensure_ascii=False,separators=(',',':'))+'\n')
            stats['roads' if row['kind']=='road' else row['kind']]+=1
            stats['unknown_speed' if row['max_speed'] is None else 'known_speed']+=1
            if args.api:
                batch.append(row)
                if len(batch)>=1000: flush()
        class Handler(osmium.SimpleHandler):
            def node(self,node):
                if not node.tags or not node.location.valid():return
                if bbox and not (bbox[0]<=node.location.lon<=bbox[2] and bbox[1]<=node.location.lat<=bbox[3]):return
                tags=dict(node.tags)
                kind='camera' if tags.get('highway')=='speed_camera' else 'sign' if tags.get('traffic_sign') else None
                if not kind:return
                direction=tags.get('direction','')
                valid_direction=bool(re.fullmatch(r'\d+(?:\.\d+)?',direction)) and 0<=float(direction)<360
                raw_type=tags.get('traffic_sign','')
                vn_type=raw_type.removeprefix('VN:')
                # Keep unsupported sign codes as metadata instead of pretending
                # they are supported VN driving-alert types.
                if kind=='sign' and vn_type not in {'102','123a','123b','124a','124b','124ar','124c','124d','124e','124f','130','131a','131b','131c'}:return
                emit(dict(id=f'osm-node-{node.id}',kind=kind,latitude=node.location.lat,
                    longitude=node.location.lon,bearing=float(direction) if valid_direction else 0,
                    direction_unknown=not valid_direction,name=tags.get('name',''),
                    max_speed=speed(tags.get('maxspeed')),type='701' if kind=='camera' else vn_type,
                    osm_tags=tags,osm_version=node.version))
            def way(self,way):
                tags=dict(way.tags)
                if tags.get('highway') not in DRIVABLE or tags.get('access')=='no' or tags.get('motor_vehicle')=='no':return
                points=[(n.lat,n.lon) for n in way.nodes if n.location.valid()]
                if len(points)<2:return
                if bbox and (max(p[1] for p in points)<bbox[0] or min(p[1] for p in points)>bbox[2]
                        or max(p[0] for p in points)<bbox[1] or min(p[0] for p in points)>bbox[3]):return
                reverse_only=tags.get('oneway')=='-1'
                one_way=tags.get('oneway') in {'yes','1','true','-1'} or tags.get('junction')=='roundabout'
                if reverse_only:points.reverse()
                for segment,(a,b) in enumerate(zip(points,points[1:])):
                    if bbox and (max(a[1],b[1])<bbox[0] or min(a[1],b[1])>bbox[2]
                            or max(a[0],b[0])<bbox[1] or min(a[0],b[0])>bbox[3]):continue
                    length=distance(a,b)
                    if length<0.1:continue
                    steps=max(1,math.ceil(length/50))
                    for offset in range(steps+1):
                        t=offset/steps;lat=a[0]+(b[0]-a[0])*t;lon=a[1]+(b[1]-a[1])*t
                        directions=[('forward',bearing(a,b))] if one_way else [('forward',bearing(a,b)),('backward',bearing(b,a))]
                        for direction,course in directions:
                            original_direction='backward' if reverse_only else direction
                            value=tags.get('maxspeed:'+original_direction,tags.get('maxspeed'))
                            limit=None if any(k.startswith('maxspeed') and ':conditional' in k for k in tags) else speed(value)
                            emit(dict(id=f'osm-way-{way.id}-{segment}-{offset}-{direction}',kind='road',latitude=lat,
                                longitude=lon,bearing=course,name=tags.get('name',tags.get('ref','')),
                                max_speed=limit,osm_way=way.id,osm_version=way.version,
                                osm_tags={k:v for k,v in tags.items() if k in {'highway','oneway','junction'} or k.startswith('maxspeed')}))
        Handler().apply_file(str(source),locations=True,idx='flex_mem')
        flush()
    stats['output_bytes']=output.stat().st_size
    with output.open('rb') as generated:
        stats['output_sha256']=hashlib.file_digest(generated,'sha256').hexdigest()
    output.with_suffix(output.suffix+'.summary.json').write_text(json.dumps(stats,ensure_ascii=False,indent=2),encoding='utf-8')
    print(json.dumps(stats,ensure_ascii=True))

if __name__=='__main__':
    main()
