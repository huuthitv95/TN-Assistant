"""Migrate TN-owned configuration names without changing secrets or user data."""
import argparse
import copy
import json
import os
from pathlib import Path
import re
import sqlite3


def env_name(name):
    return "TN_" + name[5:] if name.startswith("KIKI_") else name


def migrate_environment(text):
    lines = text.splitlines(keepends=True)
    assignments = {}
    for line in lines:
        if not line.strip() or line.lstrip().startswith("#"):
            continue
        match = re.fullmatch(r"([A-Za-z_][A-Za-z0-9_]*)=(.*?)(?:\r?\n)?", line.strip())
        if not match:
            raise ValueError("Unsupported environment assignment; no files changed")
        key, value = match.groups()
        canonical = env_name(key)
        if canonical in assignments and assignments[canonical] != value:
            raise ValueError("Conflicting values for environment key " + canonical)
        assignments[canonical] = value
    result, emitted, changes = [], set(), 0
    for line in lines:
        if not line.strip() or line.lstrip().startswith("#"):
            result.append(line)
            continue
        key, value = line.strip().split("=", 1)
        canonical = env_name(key)
        if canonical in emitted:
            changes += 1
            continue
        emitted.add(canonical)
        ending = "\r\n" if line.endswith("\r\n") else "\n"
        result.append(canonical + "=" + value + ending)
        changes += canonical != key
    return "".join(result), changes


def migrate_providers(data):
    result = copy.deepcopy(data)
    changes = 0

    def references(value):
        nonlocal changes
        if isinstance(value, dict):
            for key, item in list(value.items()):
                if key.endswith("_env") and isinstance(item, str):
                    replacement = env_name(item)
                    changes += replacement != item
                    value[key] = replacement
                elif key == "header_env" and isinstance(item, dict):
                    for header, reference in list(item.items()):
                        if isinstance(reference, str):
                            replacement = env_name(reference)
                            changes += replacement != reference
                            item[header] = replacement
                else:
                    references(item)
        elif isinstance(value, list):
            for item in value:
                references(item)

    if not isinstance(result, dict):
        raise ValueError("Provider configuration must be an object")
    for provider in result.values():
        if isinstance(provider, dict):
            if provider.get("mode") == "legacy_kiki":
                provider["mode"] = "legacy_tn"
                changes += 1
            references(provider)
    return result, changes


def database_provider_plan(connection):
    row = connection.execute("SELECT data FROM records WHERE kind='config' AND id='providers'").fetchone()
    if row is None:
        return None, None, 0
    updated, changes = migrate_providers(json.loads(row[0]))
    return row[0], json.dumps(updated, ensure_ascii=False), changes


def runtime_initialization_record_delta(connection):
    exists = connection.execute("SELECT 1 FROM records WHERE kind='config' AND id='runtime'").fetchone()
    # The first independent boot adds the runtime config and its audit record.
    return 0 if exists else 2


def migrate_database(connection):
    previous, updated, changes = database_provider_plan(connection)
    if changes:
        with connection:
            cursor = connection.execute("UPDATE records SET data=? WHERE kind='config' AND id='providers' AND data=?", (updated, previous))
            if cursor.rowcount != 1:
                raise RuntimeError("Provider configuration changed concurrently")
    return changes


def atomic_write(path, text, mode=None):
    path = Path(path)
    original = path.stat() if path.exists() else None
    temporary = path.with_name(path.name + ".tn-migration.tmp")
    descriptor = os.open(temporary, os.O_WRONLY | os.O_CREAT | os.O_EXCL, mode or (original.st_mode & 0o777 if original else 0o600))
    try:
        with os.fdopen(descriptor, "w", encoding="utf-8", newline="") as output:
            output.write(text)
            output.flush()
            os.fsync(output.fileno())
        if original and hasattr(os, "chown"):
            os.chown(temporary, original.st_uid, original.st_gid)
        os.replace(temporary, path)
    except BaseException:
        temporary.unlink(missing_ok=True)
        raise


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--env", type=Path, required=True)
    parser.add_argument("--providers", type=Path, required=True)
    parser.add_argument("--db", type=Path, required=True)
    parser.add_argument("--dry-run", action="store_true")
    args = parser.parse_args()
    new_env, env_changes = migrate_environment(args.env.read_text(encoding="utf-8-sig"))
    providers, provider_changes = migrate_providers(json.loads(args.providers.read_text(encoding="utf-8-sig")))
    connection = sqlite3.connect(args.db.resolve().as_uri() + ("?mode=ro" if args.dry_run else "?mode=rw"), uri=True)
    try:
        _, _, db_changes = database_provider_plan(connection)
        if not args.dry_run:
            if env_changes:
                atomic_write(args.env, new_env)
            if provider_changes:
                atomic_write(args.providers, json.dumps(providers, ensure_ascii=False, indent=2) + "\n")
            migrate_database(connection)
    finally:
        connection.close()
    print(json.dumps({"dry_run": args.dry_run, "environment_changes": env_changes, "provider_changes": provider_changes, "database_changes": db_changes}))


if __name__ == "__main__":
    main()
