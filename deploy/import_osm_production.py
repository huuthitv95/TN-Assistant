#!/usr/bin/env python3
"""Safely import a verified, known-speed OSM extract into the production DB.

The import is deliberately data-only: it refuses to run while managed driving
is enabled, never changes runtime configuration, and takes a consistent SQLite
backup before the first write. Batches are idempotent so an interrupted run can
resume from the start.
"""
import argparse
from datetime import datetime, timedelta, timezone
import hashlib
import json
import math
from pathlib import Path
import shutil
import sqlite3
import sys

SOURCE = "https://download.geofabrik.de/asia/vietnam-latest.osm.pbf"
LICENSE = "ODbL-1.0"
LICENSE_URL = "https://www.openstreetmap.org/copyright"
MAX_SOURCE_AGE = timedelta(days=30)
BATCH_SIZE = 1000
SEGMENT_FIELDS = ("start_latitude", "start_longitude", "end_latitude", "end_longitude")


def number(row, key, low, high):
    value = row.get(key)
    if isinstance(value, bool) or not isinstance(value, (int, float)):
        raise ValueError(f"{key} must be numeric")
    value = float(value)
    if not math.isfinite(value) or not low <= value <= high:
        raise ValueError(f"{key} is out of range")
    return value


def distance(a_lat, a_lon, b_lat, b_lon):
    rad = math.pi / 180
    dlat = (b_lat - a_lat) * rad
    dlon = (b_lon - a_lon) * rad
    a = math.sin(dlat / 2) ** 2 + math.cos(a_lat * rad) * math.cos(b_lat * rad) * math.sin(dlon / 2) ** 2
    return 6371000 * 2 * math.atan2(math.sqrt(a), math.sqrt(max(0, 1 - a)))


def validate_row(row, provenance):
    if not isinstance(row, dict) or row.get("kind") != "road":
        raise ValueError("Only managed road records may be imported")
    lat = number(row, "latitude", -90, 90)
    lon = number(row, "longitude", -180, 180)
    bearing = number(row, "bearing", 0, 359.999999)
    speed = number(row, "max_speed", 1, 200)
    identifier = row.get("id")
    if not isinstance(identifier, str) or not identifier or len(identifier) > 160 or any(c in identifier for c in "/\\"):
        raise ValueError("Invalid road id")
    if row.get("source") != provenance["source"] or row.get("license") != provenance["license"] or row.get("source_updated_at") != provenance["source_updated_at"]:
        raise ValueError("Record provenance does not match the verified summary")
    source_date = datetime.fromisoformat(row["source_updated_at"].replace("Z", "+00:00"))
    if source_date.tzinfo is None:
        raise ValueError("Source timestamp must include a timezone")
    if row.get("license") != LICENSE or row.get("source") != SOURCE:
        raise ValueError("Unexpected dataset source or license")
    road_group = row.get("road_group")
    if not isinstance(road_group, str) or not road_group or len(road_group) > 160:
        raise ValueError("Segment road_group is required")
    points = [number(row, key, -180 if index % 2 else -90, 180 if index % 2 else 90) for index, key in enumerate(SEGMENT_FIELDS)]
    a_lat, a_lon, b_lat, b_lon = points
    length = distance(a_lat, a_lon, b_lat, b_lon)
    if length < 0.1 or length > 100 or abs(b_lon - a_lon) > 180:
        raise ValueError("Road segment length is invalid")
    if distance(lat, lon, (a_lat + b_lat) / 2, (a_lon + b_lon) / 2) > 1:
        raise ValueError("Road segment midpoint does not match its index coordinate")
    rad = math.pi / 180
    heading = math.degrees(math.atan2(
        math.sin((b_lon - a_lon) * rad) * math.cos(b_lat * rad),
        math.cos(a_lat * rad) * math.sin(b_lat * rad)
        - math.sin(a_lat * rad) * math.cos(b_lat * rad) * math.cos((b_lon - a_lon) * rad),
    )) % 360
    diff = abs(bearing - heading)
    if min(diff, 360 - diff) > 5:
        raise ValueError("Road segment bearing does not match directed geometry")
    if not math.isfinite(speed):
        raise ValueError("Invalid speed limit")
    return row


def validate_dataset(data_path, summary_path, now=None):
    data_path, summary_path = Path(data_path), Path(summary_path)
    if data_path.is_symlink() or summary_path.is_symlink():
        raise ValueError("Dataset and summary must not be symlinks")
    summary = json.loads(summary_path.read_text(encoding="utf-8"))
    if summary.get("source") != SOURCE or summary.get("license") != LICENSE or summary.get("license_url") != LICENSE_URL:
        raise ValueError("Summary must identify the official Geofabrik OSM source and ODbL license")
    if "OpenStreetMap contributors" not in summary.get("attribution", ""):
        raise ValueError("OpenStreetMap attribution is required")
    if any(summary.get(key) != value for key, value in {
        "camera": 0, "sign": 0, "unknown_speed": 0, "roads": summary.get("known_speed"),
    }.items()):
        raise ValueError("Only the known-speed road extract is accepted")
    source_date = datetime.fromisoformat(summary["source_updated_at"].replace("Z", "+00:00"))
    now = now or datetime.now(timezone.utc)
    if source_date.tzinfo is None or source_date > now or now - source_date > MAX_SOURCE_AGE:
        raise ValueError("OSM source timestamp must be recent, timezone-aware and not in the future")
    if summary.get("bbox") is not None:
        raise ValueError("Expected the whole-country extract")
    if data_path.stat().st_size != summary.get("output_bytes"):
        raise ValueError("Dataset byte length differs from its summary")
    digest = hashlib.sha256()
    seen = set()
    count = 0
    with data_path.open("rb") as stream:
        for line_number, line in enumerate(stream, 1):
            digest.update(line)
            try:
                row = json.loads(line)
                validate_row(row, summary)
            except Exception as exc:
                raise ValueError(f"Invalid dataset row {line_number}: {exc}") from exc
            if row["id"] in seen:
                raise ValueError(f"Duplicate road id at row {line_number}")
            seen.add(row["id"])
            count += 1
    if count != summary.get("roads") or count != summary.get("known_speed"):
        raise ValueError("Dataset row count differs from its summary")
    if digest.hexdigest() != summary.get("output_sha256"):
        raise ValueError("Dataset SHA-256 differs from its summary")
    summary["verified_rows"] = count
    summary["verified_sha256"] = digest.hexdigest()
    return summary


def database_state(connection):
    tables = {row[0] for row in connection.execute("SELECT name FROM sqlite_master WHERE type='table'")}
    required = {"records", "managed_roads", "managed_road_index"}
    if not required.issubset(tables):
        raise ValueError("Production managed-road schema is incomplete")
    row = connection.execute("SELECT data FROM records WHERE kind='config' AND id='managed_data'").fetchone()
    raw_config = row[0] if row else None
    config = json.loads(raw_config) if raw_config else {}
    if config.get("driving_enabled", False) is not False:
        raise ValueError("Managed driving must remain disabled during import")
    roads = connection.execute("SELECT count(*) FROM managed_roads").fetchone()[0]
    index = connection.execute("SELECT count(*) FROM managed_road_index").fetchone()[0]
    if roads != index:
        raise ValueError("Road table and spatial index are inconsistent")
    return raw_config, roads, index


def make_backup(connection, database, backup_dir):
    raw_database, raw_backup_dir = Path(database), Path(backup_dir)
    if raw_database.is_symlink() or raw_backup_dir.is_symlink():
        raise ValueError("Database and backup directory must not be symlinks")
    database, backup_dir = raw_database.resolve(), raw_backup_dir.resolve()
    if backup_dir != database.parent / "backups":
        raise ValueError("Backup directory must be the protected backups child of the database directory")
    backup_dir.mkdir(mode=0o700, parents=True, exist_ok=True)
    backup_dir.chmod(0o700)
    name = "tn-assistant-before-vietnam-known-speed-" + datetime.now(timezone.utc).strftime("%Y%m%dT%H%M%S%fZ") + ".sqlite"
    destination = backup_dir / name
    if destination.exists() or destination.is_symlink():
        raise FileExistsError("Refusing to overwrite an existing production backup")
    backup = sqlite3.connect(str(destination))
    try:
        connection.backup(backup)
        backup.commit()
        check = backup.execute("PRAGMA integrity_check").fetchone()[0]
        if check != "ok":
            raise ValueError("Consistent database backup failed integrity_check")
    finally:
        backup.close()
    destination.chmod(0o600)
    return destination


def import_dataset(database, data_path, summary, backup_dir, apply=False, batch_size=BATCH_SIZE):
    if not 1 <= batch_size <= BATCH_SIZE:
        raise ValueError(f"Import batch size must be between 1 and {BATCH_SIZE}")
    if Path(database).is_symlink() or Path(data_path).is_symlink():
        raise ValueError("Database and import data must not be symlinks")
    required_free = Path(database).stat().st_size + summary["output_bytes"] * 4
    available_free = shutil.disk_usage(Path(database).parent).free
    if apply and available_free < required_free:
        raise ValueError("Insufficient free space for a database backup and OSM import")
    connection = sqlite3.connect(str(database), timeout=60)
    connection.execute("PRAGMA busy_timeout=60000")
    connection.execute("PRAGMA foreign_keys=ON")
    try:
        config_before, roads_before, index_before = database_state(connection)
        if not apply:
            return {"applied": False, "source_rows": summary["verified_rows"], "roads_before": roads_before, "index_before": index_before, "driving_enabled": False}
        backup = make_backup(connection, database, backup_dir)
        processed = 0
        batch = []
        def flush():
            nonlocal processed
            if not batch:
                return
            connection.execute("BEGIN IMMEDIATE")
            try:
                current_config, _, _ = database_state(connection)
                if current_config != config_before:
                    raise ValueError("Managed-data config changed during import; stopping without altering it")
                for row in batch:
                    row["updated_at"] = datetime.now(timezone.utc).isoformat(timespec="microseconds").replace("+00:00", "Z")
                    data = json.dumps(row, ensure_ascii=False, separators=(",", ":"))
                    connection.execute(
                        "INSERT INTO managed_roads(id,kind,latitude,longitude,data) VALUES(?,?,?,?,?) "
                        "ON CONFLICT(id) DO UPDATE SET kind=excluded.kind,latitude=excluded.latitude,longitude=excluded.longitude,data=excluded.data",
                        (row["id"], "road", row["latitude"], row["longitude"], data),
                    )
                    connection.execute(
                        "INSERT OR REPLACE INTO managed_road_index "
                        "SELECT rowid,latitude,latitude,longitude,longitude FROM managed_roads WHERE id=?",
                        (row["id"],),
                    )
                connection.commit()
            except Exception:
                connection.rollback()
                raise
            processed += len(batch)
            batch.clear()
        with Path(data_path).open("rb") as stream:
            for line_number, line in enumerate(stream, 1):
                row = json.loads(line)
                validate_row(row, summary)
                batch.append(row)
                if len(batch) >= batch_size:
                    flush()
        flush()
        config_after, roads_after, index_after = database_state(connection)
        if config_after != config_before or index_after != roads_after:
            raise ValueError("Post-import state changed configuration or left the spatial index incomplete")
        count = connection.execute(
            "SELECT count(*) FROM managed_roads WHERE json_extract(data,'$.source')=? "
            "AND json_extract(data,'$.source_updated_at')=? AND json_extract(data,'$.license')=?",
            (summary["source"], summary["source_updated_at"], summary["license"]),
        ).fetchone()[0]
        if processed != summary["verified_rows"] or count != processed:
            raise ValueError("Database does not contain every verified input row")
        integrity = connection.execute("PRAGMA integrity_check").fetchone()[0]
        if integrity != "ok":
            raise ValueError("Post-import database integrity_check failed")
        return {
            "applied": True,
            "imported": processed,
            "source_rows_in_db": count,
            "roads_before": roads_before,
            "roads_after": roads_after,
            "index_after": index_after,
            "driving_enabled": False,
            "backup": str(backup),
            "integrity_check": integrity,
        }
    finally:
        connection.close()


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--database", required=True, help="Existing SQLite database")
    parser.add_argument("--data", required=True, help="Verified OSM known-speed NDJSON")
    parser.add_argument("--summary", required=True, help="Adjacent generator summary JSON")
    parser.add_argument("--backup-dir", required=True, help="Protected backups directory beside the database")
    parser.add_argument("--apply", action="store_true", help="Create a backup, then apply data-only import")
    args = parser.parse_args()
    summary = validate_dataset(args.data, args.summary)
    result = import_dataset(args.database, args.data, summary, args.backup_dir, apply=args.apply)
    print(json.dumps(result, ensure_ascii=True, sort_keys=True))


if __name__ == "__main__":
    try:
        main()
    except Exception as exc:
        print(json.dumps({"error": str(exc)}, ensure_ascii=True), file=sys.stderr)
        raise SystemExit(1)
