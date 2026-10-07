#!/usr/bin/env python3
"""Fetch released 0.1.0 binaries and verify their Maven metadata hashes."""
import argparse
import hashlib
import json
from pathlib import Path
import urllib.parse
import urllib.request

BASE = "https://repo.maven.apache.org/maven2/io/github/yuroyami/"
TARGETS = {
    "kitearchive": {"jvm", "android", "js", "iosarm64", "iossimulatorarm64", "iosx64"},
    "kitearchive-io": {"jvm", "android", "iosarm64", "iossimulatorarm64", "iosx64"},
}


def read_url(url):
    with urllib.request.urlopen(url, timeout=30) as response:
        return response.read()


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument("--destination", type=Path, required=True)
    root = parser.parse_args().destination
    root.mkdir(parents=True, exist_ok=True)
    records = []
    for artifact, targets in TARGETS.items():
        url = f"{BASE}{artifact}/0.1.0/{artifact}-0.1.0.module"
        top = json.loads(read_url(url))
        seen = set()
        for variant in top["variants"]:
            location = variant.get("available-at")
            if not location or location["module"] in seen:
                continue
            name = location["module"]
            seen.add(name)
            target = name.removeprefix(artifact + "-")
            if target not in targets:
                raise RuntimeError(f"Unexpected published target {name}")
            metadata_url = urllib.parse.urljoin(url, location["url"])
            if not metadata_url.startswith(BASE):
                raise RuntimeError(f"Unexpected metadata URL {metadata_url}")
            metadata = json.loads(read_url(metadata_url))
            files = {}
            for published_variant in metadata["variants"]:
                if published_variant["attributes"].get("org.gradle.category") == "library":
                    for file in published_variant.get("files", []):
                        if file["name"].endswith((".jar", ".aar", ".klib")) and "metadata" not in file["name"]:
                            files[file["name"]] = file
            if len(files) != 1:
                raise RuntimeError(f"Expected one binary for {name}, found {list(files)}")
            file = next(iter(files.values()))
            if Path(file["name"]).name != file["name"]:
                raise RuntimeError(f"Unexpected binary filename {file['name']}")
            binary_url = urllib.parse.urljoin(metadata_url, file["url"])
            if not binary_url.startswith(BASE):
                raise RuntimeError(f"Unexpected binary URL {binary_url}")
            data = read_url(binary_url)
            digest = hashlib.sha256(data).hexdigest()
            if digest != file["sha256"] or len(data) != file["size"]:
                raise RuntimeError(f"Published binary hash or size differs for {name}")
            (root / file["name"]).write_bytes(data)
            records.append({
                "module": artifact, "target": target,
                "coordinate": f"io.github.yuroyami:{name}:0.1.0",
                "metadataUrl": metadata_url, "url": binary_url,
                "filename": file["name"], "bytes": len(data), "sha256": digest,
            })
            print(name, file["name"], len(data), digest, flush=True)
    matrix = {module: {row["target"] for row in records if row["module"] == module}
              for module in TARGETS}
    if len(records) != 11 or matrix != TARGETS:
        raise RuntimeError(f"Incomplete published target matrix: {matrix}")
    (root / "manifest.json").write_text(json.dumps({
        "version": "0.1.0", "compiler": "2.4.10", "artifacts": records,
    }, indent=2) + "\n")


if __name__ == "__main__":
    main()
