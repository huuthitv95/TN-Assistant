import hashlib
import json
from datetime import datetime, timezone
from pathlib import Path
import sqlite3
import tempfile
import unittest

from import_osm_production import import_dataset, validate_dataset


def fixture_row(identifier, offset=0.0):
    start_lat = 10.7769 + offset
    end_lat = start_lat + 0.0002
    return {
        "id": identifier,
        "kind": "road",
        "latitude": (start_lat + end_lat) / 2,
        "longitude": 106.7009,
        "bearing": 0.0,
        "start_latitude": start_lat,
        "start_longitude": 106.7009,
        "end_latitude": end_lat,
        "end_longitude": 106.7009,
        "road_group": identifier.rsplit("-", 1)[0],
        "name": "Fixture road",
        "max_speed": 50.0,
        "osm_way": 1,
        "osm_version": 1,
        "osm_tags": {"highway": "residential", "maxspeed": "50"},
        "source": "https://download.geofabrik.de/asia/vietnam-latest.osm.pbf",
        "source_updated_at": datetime.now(timezone.utc).isoformat(timespec="seconds"),
        "license": "ODbL-1.0",
    }


def fixture_point(identifier, kind, type_code, source_updated_at, max_speed=None):
    row = {
        "id": identifier,
        "kind": kind,
        "latitude": 10.7769,
        "longitude": 106.7009,
        "bearing": 0.0,
        "direction_unknown": True,
        "name": "Fixture hazard",
        "type": type_code,
        "osm_node": 1,
        "osm_version": 1,
        "osm_tags": {"highway": "speed_camera"} if kind == "camera" else {"traffic_sign": "VN:130"},
        "source": "https://download.geofabrik.de/asia/vietnam-latest.osm.pbf",
        "source_updated_at": source_updated_at,
        "license": "ODbL-1.0",
    }
    if max_speed is not None:
        row["max_speed"] = max_speed
    return row


class ImportOsmProductionTest(unittest.TestCase):
    def setUp(self):
        self.temp = tempfile.TemporaryDirectory()
        self.root = Path(self.temp.name)
        self.db = self.root / "tn-assistant.sqlite"
        self.data = self.root / "vietnam-known-speed.ndjson"
        self.summary = self.root / "vietnam-known-speed.ndjson.summary.json"
        self.backups = self.root / "backups"
        self.rows = [fixture_row("osm-way-1-0-0-forward"), fixture_row("osm-way-2-0-0-forward", 0.001)]
        raw = "".join(json.dumps(row, separators=(",", ":")) + "\n" for row in self.rows).encode()
        self.data.write_bytes(raw)
        self.summary.write_text(json.dumps({
            "roads": len(self.rows), "known_speed": len(self.rows), "unknown_speed": 0,
            "camera": 0, "sign": 0,
            "source": self.rows[0]["source"], "source_updated_at": self.rows[0]["source_updated_at"],
            "license": "ODbL-1.0", "attribution": "OpenStreetMap contributors",
            "license_url": "https://www.openstreetmap.org/copyright", "bbox": None,
            "output_bytes": len(raw), "output_sha256": hashlib.sha256(raw).hexdigest(),
        }), encoding="utf-8")
        connection = sqlite3.connect(self.db)
        try:
            connection.executescript("""
                CREATE TABLE records(kind TEXT NOT NULL,id TEXT NOT NULL,data TEXT NOT NULL,PRIMARY KEY(kind,id));
                CREATE TABLE managed_roads(rowid INTEGER PRIMARY KEY,id TEXT NOT NULL UNIQUE,kind TEXT NOT NULL,latitude REAL NOT NULL,longitude REAL NOT NULL,data TEXT NOT NULL);
                CREATE VIRTUAL TABLE managed_road_index USING rtree(id,min_lat,max_lat,min_lon,max_lon);
            """)
            connection.execute("INSERT INTO records VALUES('config','managed_data',?)", (json.dumps({"driving_enabled": False}),))
            pilot = {"id": "pilot-road", "kind": "road", "latitude": 10.0, "longitude": 106.0, "max_speed": 0}
            connection.execute("INSERT INTO managed_roads(id,kind,latitude,longitude,data) VALUES(?,?,?,?,?)",
                               ("pilot-road", "road", 10.0, 106.0, json.dumps(pilot)))
            rowid = connection.execute("SELECT rowid FROM managed_roads WHERE id='pilot-road'").fetchone()[0]
            connection.execute("INSERT INTO managed_road_index VALUES(?,?,?,?,?)", (rowid, 10.0, 10.0, 106.0, 106.0))
            connection.commit()
        finally:
            connection.close()

    def tearDown(self):
        self.temp.cleanup()

    def test_verified_dry_run_does_not_change_database(self):
        summary = validate_dataset(self.data, self.summary)
        result = import_dataset(self.db, self.data, summary, self.backups)
        self.assertFalse(result["applied"])
        self.assertEqual(2, result["source_rows"])
        connection = sqlite3.connect(self.db)
        try:
            self.assertEqual(1, connection.execute("SELECT count(*) FROM managed_roads").fetchone()[0])
            self.assertEqual(0, len(list(self.backups.glob("*.sqlite"))))
        finally:
            connection.close()

    def test_apply_backups_upserts_rows_and_keeps_driving_disabled(self):
        summary = validate_dataset(self.data, self.summary)
        result = import_dataset(self.db, self.data, summary, self.backups, apply=True, batch_size=1)
        self.assertTrue(result["applied"])
        self.assertEqual(2, result["imported"])
        self.assertEqual(3, result["roads_after"])
        self.assertEqual(result["roads_after"], result["index_after"])
        self.assertEqual("ok", result["integrity_check"])
        self.assertTrue(Path(result["backup"]).is_file())
        connection = sqlite3.connect(self.db)
        try:
            self.assertEqual(2, connection.execute("SELECT count(*) FROM managed_roads WHERE json_extract(data,'$.source')=?", (self.rows[0]["source"],)).fetchone()[0])
            config = json.loads(connection.execute("SELECT data FROM records WHERE kind='config' AND id='managed_data'").fetchone()[0])
            self.assertIs(config["driving_enabled"], False)
        finally:
            connection.close()

    def test_supported_hazard_points_import_without_enabling_driving(self):
        source_updated_at = self.rows[0]["source_updated_at"]
        rows = [
            fixture_point("osm-node-3", "camera", "701", source_updated_at),
            fixture_point("osm-node-4", "sign", "130", source_updated_at, 30),
        ]
        raw = "".join(json.dumps(row, separators=(",", ":")) + "\n" for row in rows).encode()
        self.data.write_bytes(raw)
        self.summary.write_text(json.dumps({
            "roads": 0, "camera": 1, "sign": 1, "known_speed": 1, "unknown_speed": 1,
            "source": rows[0]["source"], "source_updated_at": source_updated_at,
            "license": "ODbL-1.0", "attribution": "OpenStreetMap contributors",
            "license_url": "https://www.openstreetmap.org/copyright", "bbox": None,
            "output_bytes": len(raw), "output_sha256": hashlib.sha256(raw).hexdigest(),
        }), encoding="utf-8")

        summary = validate_dataset(self.data, self.summary)
        self.assertEqual("supported_hazards", summary["verified_mode"])
        result = import_dataset(self.db, self.data, summary, self.backups, apply=True)
        self.assertEqual(2, result["imported"])
        self.assertEqual({"camera": 1, "sign": 1}, result["source_kind_counts"])
        self.assertEqual(3, result["roads_after"])
        self.assertEqual(result["roads_after"], result["index_after"])
        self.assertFalse(result["driving_enabled"])

        connection = sqlite3.connect(self.db)
        try:
            stored = [json.loads(row[0]) for row in connection.execute(
                "SELECT data FROM managed_roads WHERE kind IN ('camera','sign') ORDER BY kind")]
            self.assertEqual({"camera", "sign"}, {row["kind"] for row in stored})
            self.assertFalse(json.loads(connection.execute(
                "SELECT data FROM records WHERE kind='config' AND id='managed_data'").fetchone()[0])["driving_enabled"])
        finally:
            connection.close()

    def test_hazard_dataset_rejects_unsupported_sign_types(self):
        source_updated_at = self.rows[0]["source_updated_at"]
        row = fixture_point("osm-node-bad", "sign", "999", source_updated_at)
        raw = (json.dumps(row, separators=(",", ":")) + "\n").encode()
        self.data.write_bytes(raw)
        self.summary.write_text(json.dumps({
            "roads": 0, "camera": 0, "sign": 1, "known_speed": 0, "unknown_speed": 1,
            "source": row["source"], "source_updated_at": source_updated_at,
            "license": "ODbL-1.0", "attribution": "OpenStreetMap contributors",
            "license_url": "https://www.openstreetmap.org/copyright", "bbox": None,
            "output_bytes": len(raw), "output_sha256": hashlib.sha256(raw).hexdigest(),
        }), encoding="utf-8")
        with self.assertRaisesRegex(ValueError, "Unsupported traffic-sign type"):
            validate_dataset(self.data, self.summary)

    def test_enabled_configuration_refuses_before_backup_or_write(self):
        connection = sqlite3.connect(self.db)
        try:
            connection.execute("UPDATE records SET data=? WHERE kind='config' AND id='managed_data'", (json.dumps({"driving_enabled": True}),))
            connection.commit()
        finally:
            connection.close()
        summary = validate_dataset(self.data, self.summary)
        with self.assertRaisesRegex(ValueError, "must remain disabled"):
            import_dataset(self.db, self.data, summary, self.backups, apply=True)
        connection = sqlite3.connect(self.db)
        try:
            self.assertEqual(1, connection.execute("SELECT count(*) FROM managed_roads").fetchone()[0])
            self.assertEqual(0, len(list(self.backups.glob("*.sqlite"))))
        finally:
            connection.close()

    def test_tampered_dataset_is_rejected_before_import(self):
        self.data.write_text(self.data.read_text(encoding="utf-8") + "{}\n", encoding="utf-8")
        with self.assertRaisesRegex(ValueError, "byte length differs"):
            validate_dataset(self.data, self.summary)


if __name__ == "__main__":
    unittest.main()
