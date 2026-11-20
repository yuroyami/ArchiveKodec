#!/usr/bin/env python3
"""Measure the compressed size of every ArchiveKodec encoder on the Silesia Corpus, next to the
reference command-line tools, and check that each tool and ArchiveKodec decode each other's output.

    python3 scripts/compression-ratios.py --work /tmp/ratios

The corpus is fetched into WORK/silesia by scripts/fetch-silesia.sh and checked by SHA-256.
ArchiveKodec runs in the benchmarks-jvm app (BENCHMARK_SUITE=ratios). The report goes to
WORK/ratios.json and WORK/ratios.md. Sizes do not depend on speed; no time is measured.
See docs/benchmarks.md#compression-ratios.
"""
import argparse
import concurrent.futures
from datetime import datetime, timezone
import hashlib
import json
import os
from pathlib import Path
import shutil
import subprocess
import sys

ROOT = Path(__file__).resolve().parents[1]

SILESIA = {
    "dickens": (10192446, "b24c37886142e11d0ee687db6ab06f936207aa7f2ea1fd1d9a36763c7a507e6a"),
    "mozilla": (51220480, "657fc3764b0c75ac9de9623125705831ebbfbe08fed248df73bc2dc66e2a963b"),
    "mr": (9970564, "68637ed52e3e4860174ed2dc0840ac77d5f1a60abbcb13770d5754e3774d53e6"),
    "nci": (33553445, "fc63a31770947b8c2062d3b19ca94c00485a232bb91b502021948fee983e1635"),
    "ooffice": (6152192, "e7ee013880d34dd5208283d0d3d91b07f442e067454276095ded14f322a656eb"),
    "osdb": (10085684, "60f027179302ca3ad87c58ac90b6be72ec23588aaa7a3b7fe8ecc0f11def3fa3"),
    "reymont": (6627202, "0eac0114a3dfe6e2ee1f345a0f79d653cb26c3bc9f0ed79238af4933422b7578"),
    "samba": (21606400, "93ba07bc44d8267789c1d911992f40b089ffa2140b4a160fac11ccae9a40e7b2"),
    "sao": (7251944, "c2d0ea2cc59d4c21b7fe43a71499342a00cbe530a1d5548770e91ecd6214adcc"),
    "webster": (41458703, "6a68f69b26daf09f9dd84f7470368553194a0b294fcfa80f1604efb11143a383"),
    "x-ray": (8474240, "7de9fce1405dc44ae5e6813ed21cd5751e761bd4265655a005d39b9685d1c9ad"),
    "xml": (5345280, "0e82e54e695c1938e4193448022543845b33020c8be6bf3bf3ead2224903e08c"),
}

PY = [sys.executable, "-I", "-c"]
ZLIB_COMPRESS = "import sys,zlib;c=zlib.compressobj(6,zlib.DEFLATED,{bits});d=open(sys.argv[1],'rb').read();sys.stdout.buffer.write(c.compress(d)+c.flush())"
ZLIB_DECOMPRESS = "import sys,zlib;sys.stdout.buffer.write(zlib.decompress(sys.stdin.buffer.read(),{bits}))"


def tool(label, command, kind="stream"):
    """A reference setting: `command` gets the input path appended and writes the stream to stdout.
    kind "zip": the command gets the zip path then the input path, and the size is the member's."""
    return {"label": label, "command": command, "kind": kind}


def seven_zip(label, method):
    return tool(label, ["7z", "a", "-tzip", "-bd", "-bso0", "-bsp0", f"-mm={method}"], "zip")


# Every codec with an encoder, in the order of the table. "variants" are ArchiveKodec settings,
# "pairs" match a variant with the reference setting closest in intent, "decode" is the
# reference decoder (stdin to stdout), and "zip" marks streams that only exist inside a ZIP.
CODECS = {
    "gzip": {
        "variants": ["default"] + [f"level={n}" for n in range(1, 10) if n != 6] + ["session"],
        "pairs": [("default", tool("gzip -6 (default)", ["gzip", "-c", "-n", "-6"]))]
                 + [(f"level={n}", tool(f"gzip -{n}", ["gzip", "-c", "-n", f"-{n}"])) for n in range(1, 10) if n != 6]
                 + [("session", "gzip -6 (default)")],
        "decode": ["gzip", "-dc"],
    },
    "zlib": {
        "variants": ["default"],
        "pairs": [("default", tool("zlib library, level 6", PY + [ZLIB_COMPRESS.format(bits=15)]))],
        "decode": PY + [ZLIB_DECOMPRESS.format(bits=15)],
    },
    "deflate": {
        "variants": ["default"],
        "pairs": [("default", tool("zlib library, raw, level 6", PY + [ZLIB_COMPRESS.format(bits=-15)]))],
        "decode": PY + [ZLIB_DECOMPRESS.format(bits=-15)],
    },
    "deflate64": {
        "variants": ["default"],
        "pairs": [("default", seven_zip("7z -tzip -mm=Deflate64 (level 5)", "Deflate64"))],
        "zip": True,
    },
    "bzip2": {
        "variants": ["default", "level=1", "session"],
        "pairs": [("default", tool("bzip2 -9 (default)", ["bzip2", "-c", "-9"])),
                  ("level=1", tool("bzip2 -1", ["bzip2", "-c", "-1"])),
                  ("session", "bzip2 -9 (default)")],
        "decode": ["bzip2", "-dc"],
    },
    "lzma": {
        "variants": ["default"],
        "pairs": [("default", tool("xz --format=lzma -6 (default)", ["xz", "-T1", "--format=lzma", "-c", "-6"])),
                  ("default", tool("xz --format=lzma -9", ["xz", "-T1", "--format=lzma", "-c", "-9"]))],
        "decode": ["xz", "--format=lzma", "-dc"],
    },
    "lzma2": {
        "variants": ["default"],
        "pairs": [("default", tool("xz --format=raw --lzma2=preset=6", ["xz", "-T1", "--format=raw", "--lzma2=preset=6", "-c"])),
                  ("default", tool("xz --format=raw --lzma2=preset=9", ["xz", "-T1", "--format=raw", "--lzma2=preset=9", "-c"]))],
        "decode": ["xz", "--format=raw", "--lzma2=dict=64MiB", "-dc"],
    },
    "xz": {
        "variants": ["default", "session"],
        "pairs": [("default", tool("xz -6 (default)", ["xz", "-T1", "-c", "-6"])),
                  ("default", tool("xz -9", ["xz", "-T1", "-c", "-9"])),
                  ("session", "xz -6 (default)")],
        "decode": ["xz", "-dc"],
    },
    "lzip": {
        "variants": ["default", "session"],
        "pairs": [("default", tool("lzip -6 (default)", ["lzip", "-c", "-6"])),
                  ("default", tool("lzip -9", ["lzip", "-c", "-9"])),
                  ("session", "lzip -6 (default)")],
        "decode": ["lzip", "-dc"],
    },
    "zstd": {
        "variants": ["default", "session"],
        "pairs": [("default", tool("zstd -3 (default)", ["zstd", "-q", "-c", "-3"])),
                  ("default", tool("zstd -1", ["zstd", "-q", "-c", "-1"])),
                  ("default", tool("zstd -19", ["zstd", "-q", "-c", "-19"])),
                  ("session", "zstd -3 (default)")],
        "decode": ["zstd", "-q", "-dc"],
    },
    "lz4": {
        "variants": ["default", "linked", "session"],
        "pairs": [("default", tool("lz4 -1 (default)", ["lz4", "-q", "-c", "-1"])),
                  ("default", tool("lz4 -9", ["lz4", "-q", "-c", "-9"])),
                  ("linked", tool("lz4 -1 -BD", ["lz4", "-q", "-c", "-1", "-BD"])),
                  ("session", "lz4 -1 (default)")],
        "decode": ["lz4", "-q", "-dc"],
    },
    "lzop": {
        "variants": ["default"],
        "pairs": [("default", tool("lzop -3 (default)", ["lzop", "-c", "-3"])),
                  ("default", tool("lzop -1", ["lzop", "-c", "-1"])),
                  ("default", tool("lzop -9", ["lzop", "-c", "-9"]))],
        "decode": ["lzop", "-dc"],
    },
    "compress": {
        "variants": ["default"],
        "pairs": [("default", tool("compress -b16 (default)", ["compress", "-c", "-f", "-b16"]))],
        "decode": ["compress", "-dc"],
    },
    "ppmd": {
        "variants": ["default"],
        "pairs": [("default", seven_zip("7z -tzip -mm=PPMd:o=6:mem=16m", "PPMd:o=6:mem=16m")),
                  ("default", seven_zip("7z -tzip -mm=PPMd (level 5)", "PPMd"))],
        "zip": True,
    },
    "brotli": {"variants": ["default"], "pairs": [("default", None)]},
    "snappy": {"variants": ["default"], "pairs": [("default", None)]},
    "snappy-framed": {"variants": ["default"], "pairs": [("default", None)]},
    "lzw": {"variants": ["default"], "pairs": [("default", None)]},
    "packbits": {"variants": ["default"], "pairs": [("default", None)]},
    "palmdoc": {"variants": ["records=4096"], "pairs": [("records=4096", None)]},
}

VERSION_COMMANDS = {
    "gzip": ["gzip", "--version"], "bzip2": ["bzip2", "--help"], "xz": ["xz", "--version"],
    "lzip": ["lzip", "--version"], "zstd": ["zstd", "-V"], "lz4": ["lz4", "-V"],
    "lzop": ["lzop", "--version"], "compress": ["compress", "-V"], "7z": ["7z", "i"],
    "zlib": PY + ["import zlib;print('zlib', zlib.ZLIB_RUNTIME_VERSION)"],
}


def version(name):
    output = subprocess.run(VERSION_COMMANDS[name], capture_output=True, text=True)
    lines = [line.strip() for line in (output.stdout + output.stderr).splitlines() if line.strip()]
    for line in lines:
        if any(word in line for word in ("Version", "version", "v1.", "7-Zip", "compress", "zlib", "lzip", "xz", "gzip")):
            return line
    return lines[0] if lines else "unknown"


def sha256(path):
    digest = hashlib.sha256()
    with open(path, "rb") as stream:
        for block in iter(lambda: stream.read(1 << 20), b""):
            digest.update(block)
    return digest.hexdigest()


def corpus(directory):
    if not all((directory / name).is_file() for name in SILESIA):
        subprocess.run(["sh", str(ROOT / "scripts/fetch-silesia.sh"), str(directory)], check=True)
    for name, (size, digest) in SILESIA.items():
        path = directory / name
        if path.stat().st_size != size or sha256(path) != digest:
            raise ValueError(f"{path} is not the Silesia file {name} (size or SHA-256 differs)")


def zip_member_size(path):
    import zipfile
    with zipfile.ZipFile(path) as archive:
        (info,) = archive.infolist()
        return info.compress_size


def run_tool(setting, source, output, reuse=False):
    if reuse and output.is_file() and output.stat().st_size > 0:
        return zip_member_size(output) if setting["kind"] == "zip" else output.stat().st_size
    output.unlink(missing_ok=True)
    if setting["kind"] == "zip":
        subprocess.run(setting["command"] + [str(output), str(source)], check=True)
        return zip_member_size(output)
    with open(output, "wb") as stream:
        subprocess.run(setting["command"] + [str(source)], stdout=stream, check=True)
    return output.stat().st_size


def decoded_digest(command, compressed):
    digest = hashlib.sha256()
    with open(compressed, "rb") as stream, subprocess.Popen(command, stdin=stream, stdout=subprocess.PIPE,
                                                             stderr=subprocess.PIPE) as process:
        for block in iter(lambda: process.stdout.read(1 << 20), b""):
            digest.update(block)
        error = process.stderr.read().decode(errors="replace").strip()
        if process.wait():
            return None, f"exit {process.returncode}: {error[:200]}"
    return digest.hexdigest(), None


def slug(text):
    return "".join(c if c.isalnum() else "_" for c in text)


def kotlin_jobs(jobs, work, threads, heap, job_name="jobs.tsv"):
    job_file = work / job_name
    job_file.write_text("".join("\t".join(job) + "\n" for job in jobs))
    kotlin = str(ROOT / ("kotlin.bat" if sys.platform == "win32" else "kotlin"))
    environment = dict(os.environ, BENCHMARK_SUITE="ratios", RATIOS_JOBS=str(job_file),
                       RATIOS_THREADS=str(threads), KOTLIN_CLI_NO_WELCOME_BANNER="1")
    subprocess.run([kotlin, "build", "-m", "benchmarks-jvm"], cwd=ROOT, env=environment, check=True)
    results, meta = {}, {}
    with subprocess.Popen([kotlin, "run", "-m", "benchmarks-jvm", f"--jvm-args=-Xmx{heap}"], cwd=ROOT, env=environment,
                          stdout=subprocess.PIPE, stderr=subprocess.STDOUT, text=True, bufsize=1) as process:
        for line in process.stdout:
            if line.startswith("RATIO_RESULT "):
                row = json.loads(line[len("RATIO_RESULT "):])
                results[row["id"]] = row
                print(f"  {row['id']}: {row.get('size', row.get('error'))}", flush=True)
            elif line.startswith("RATIO_META "):
                meta = json.loads(line[len("RATIO_META "):])
            else:
                print(line, end="", flush=True)
        if process.wait():
            raise RuntimeError(f"The ratio jobs failed with exit code {process.returncode}")
    missing = [job[0] for job in jobs if job[0] not in results]
    if missing:
        raise RuntimeError(f"No result for {missing}")
    return results, meta


def main():
    parser = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    parser.add_argument("--work", type=Path, required=True, help="Directory for the corpus and the compressed files")
    parser.add_argument("--files", nargs="+", choices=sorted(SILESIA), default=sorted(SILESIA), help="A subset of the corpus")
    parser.add_argument("--codecs", nargs="+", choices=list(CODECS), default=list(CODECS), help="A subset of the codecs")
    parser.add_argument("--threads", type=int, default=2, help="Parallel jobs, for the tools and the JVM (default 2)")
    parser.add_argument("--heap", default="2g", help="JVM -Xmx (default 2g)")
    parser.add_argument("--keep", action="store_true", help="Keep the compressed and decoded files")
    parser.add_argument("--reuse", action="store_true",
                        help="Keep reference outputs that an earlier, interrupted run left in WORK/out")
    parser.add_argument("--memory", action="store_true",
                        help="Instead of sizes, find the smallest JVM heap each codec needs for one file")
    parser.add_argument("--memory-file", choices=sorted(SILESIA), default="mozilla", help="The file for --memory (default mozilla)")
    args = parser.parse_args()
    work = args.work.resolve()
    corpus_dir = work / "silesia"
    out = work / ("memory-out" if args.memory else "out")
    out.mkdir(parents=True, exist_ok=True)
    corpus(corpus_dir)
    if args.memory:
        return measure_memory(args, work, corpus_dir, out)
    originals = {name: SILESIA[name][1] for name in args.files}
    tools_needed = {"zlib"} | {setting["command"][0] for codec in args.codecs for _, setting in CODECS[codec]["pairs"]
                               if isinstance(setting, dict) and setting["command"][0] != sys.executable}
    missing_tools = sorted(name for name in tools_needed if name != "zlib" and not shutil.which(name))
    if missing_tools:
        raise RuntimeError(f"Missing reference tools: {missing_tools}")

    # 1. Reference tools compress every file.
    references = {}  # (codec, label, file) -> path
    tasks = []
    for codec in args.codecs:
        for _, setting in CODECS[codec]["pairs"]:
            if isinstance(setting, dict):
                for name in args.files:
                    path = out / f"{codec}.ref.{slug(setting['label'])}.{name}"
                    references[(codec, setting["label"], name)] = path
                    tasks.append((codec, setting, name, path))
    reference_sizes = {}
    print(f"Running {len(tasks)} reference compressions", flush=True)
    with concurrent.futures.ThreadPoolExecutor(args.threads) as pool:
        futures = {pool.submit(run_tool, setting, corpus_dir / name, path, args.reuse): (codec, setting["label"], name)
                   for codec, setting, name, path in tasks}
        for future in concurrent.futures.as_completed(futures):
            reference_sizes[futures[future]] = future.result()

    # 2. ArchiveKodec encodes every file, and decodes every reference output.
    jobs, ak_paths = [], {}
    for codec in args.codecs:
        spec = CODECS[codec]
        for variant in spec["variants"]:
            for name in args.files:
                path = out / f"{codec}.ak.{slug(variant)}.{name}"
                ak_paths[(codec, variant, name)] = path
                job = [f"encode/{codec}/{variant}/{name}", "encode", codec, variant, str(corpus_dir / name), str(path)]
                if spec.get("zip"):
                    job.append(str(path) + ".zip")
                jobs.append(job)
    for (codec, label, name), path in references.items():
        kind = ["unzip", str(path)] if CODECS[codec].get("zip") else ["decode", codec, str(path)]
        jobs.append([f"decode/{codec}/{label}/{name}", *kind, str(path) + ".decoded"])
    print(f"Running {len(jobs)} ArchiveKodec jobs", flush=True)
    results, meta = kotlin_jobs(jobs, work, args.threads, args.heap)

    interop = []  # one row per (direction, codec, setting, file)
    for (codec, label, name), path in references.items():
        row = results[f"decode/{codec}/{label}/{name}"]
        decoded = Path(str(path) + ".decoded")
        ok = row["ok"] and sha256(decoded) == originals[name]
        interop.append({"direction": "ArchiveKodec decodes the tool", "codec": codec, "setting": label, "file": name,
                        "ok": ok, "error": row.get("error") or (None if ok else "output differs")})
        decoded.unlink(missing_ok=True)

    # 3. The reference tools decode every ArchiveKodec output.
    def tool_decode(codec, variant, name, path):
        if CODECS[codec].get("zip"):
            command, source = ["7z", "e", "-so", "-bd", str(path) + ".zip"], None
            with subprocess.Popen(command, stdout=subprocess.PIPE, stderr=subprocess.PIPE) as process:
                digest = hashlib.sha256()
                for block in iter(lambda: process.stdout.read(1 << 20), b""):
                    digest.update(block)
                error = process.stderr.read().decode(errors="replace").strip()
                code = process.wait()
            return (digest.hexdigest(), None) if code == 0 else (None, f"7z exit {code}: {error[:200]}")
        return decoded_digest(CODECS[codec]["decode"], path)

    decode_tasks = [(codec, variant, name, path) for (codec, variant, name), path in ak_paths.items()
                    if results[f"encode/{codec}/{variant}/{name}"]["ok"]
                    and ("decode" in CODECS[codec] or CODECS[codec].get("zip"))]
    with concurrent.futures.ThreadPoolExecutor(args.threads) as pool:
        futures = {pool.submit(tool_decode, *task): task for task in decode_tasks}
        for future in concurrent.futures.as_completed(futures):
            codec, variant, name, _ = futures[future]
            digest, error = future.result()
            ok = digest == originals[name]
            interop.append({"direction": "the tool decodes ArchiveKodec", "codec": codec, "setting": variant, "file": name,
                            "ok": ok, "error": error or (None if ok else "output differs")})

    # 4. Report.
    total_input = sum(SILESIA[name][0] for name in args.files)
    rows = []
    for codec in args.codecs:
        for variant, setting in CODECS[codec]["pairs"]:
            label = setting["label"] if isinstance(setting, dict) else setting
            per_file = {}
            failures = []
            for name in args.files:
                result = results[f"encode/{codec}/{variant}/{name}"]
                if result["ok"]:
                    per_file[name] = {"archivekodec": result["size"]}
                else:
                    failures.append(f"{name}: {result['error']}")
                if label is not None and (codec, label, name) in reference_sizes:
                    per_file.setdefault(name, {})["reference"] = reference_sizes[(codec, label, name)]
            ak_total = None if failures else sum(per_file[name]["archivekodec"] for name in args.files)
            ref_total = sum(per_file[name]["reference"] for name in args.files) if label else None
            rows.append({"codec": codec, "variant": variant, "reference": label, "archivekodec_bytes": ak_total,
                         "reference_bytes": ref_total, "encode_failures": failures, "files": per_file})
    head, dirty = revision()
    report = {
        "recorded_at": datetime.now(timezone.utc).isoformat(), "revision": head, "dirty": dirty,
        "archivekodec": meta,
        "tools": {name: version(name) for name in sorted(tools_needed)},
        "corpus": {"name": "Silesia", "files": {name: {"bytes": SILESIA[name][0], "sha256": SILESIA[name][1]} for name in args.files},
                   "bytes": total_input},
        "rows": rows,
        "interop": sorted(interop, key=lambda row: (row["ok"], row["codec"], row["direction"], row["setting"], row["file"])),
    }
    (work / "ratios.json").write_text(json.dumps(report, indent=2) + "\n")
    markdown = render(report)
    (work / "ratios.md").write_text(markdown)
    print(markdown)
    if not args.keep:
        shutil.rmtree(out)
    failed = [row for row in interop if not row["ok"]]
    if failed or any(row["encode_failures"] for row in rows):
        print(f"{len(failed)} interoperability failures; see {work / 'ratios.json'}", file=sys.stderr)
        sys.exit(1)


# Codecs whose decoders read a ByteSource and whose encoders are sessions of their own,
# rather than the default that buffers the whole input.
STREAM_DECODERS = ["deflate", "zlib", "gzip", "bzip2", "lzma", "xz", "lzip", "zstd", "lz4", "compress", "lzop"]
STREAM_ENCODERS = ["deflate", "zlib", "gzip", "bzip2", "xz", "zstd", "lz4", "lzip"]


def revision():
    head = subprocess.check_output(["git", "rev-parse", "HEAD"], cwd=ROOT, text=True).strip()
    dirty = bool(subprocess.check_output(["git", "status", "--porcelain"], cwd=ROOT, text=True))
    return head, dirty


def measure_memory(args, work, corpus_dir, out):
    """The smallest -Xmx, in MiB from a fixed ladder, at which one call succeeds in a child JVM."""
    name = args.memory_file
    source = corpus_dir / name
    codecs = [codec for codec in args.codecs if CODECS[codec]["variants"][0] == "default"]  # not PalmDOC's records
    # Inputs: ArchiveKodec's own default output, and the reference tool's default output.
    jobs, own, reference = [], {}, {}
    for codec in codecs:
        own[codec] = out / f"{codec}.ak.{name}"
        jobs.append([f"encode/{codec}", "encode", codec, "default", str(source), str(own[codec])])
    for codec in STREAM_DECODERS:
        if codec in codecs:
            reference[codec] = out / f"{codec}.ref.{name}"
            run_tool(CODECS[codec]["pairs"][0][1], source, reference[codec])
    prepared, _ = kotlin_jobs(jobs, work, args.threads, args.heap, "memory-inputs.tsv")
    failed = [key for key, row in prepared.items() if not row["ok"]]
    if failed:
        raise RuntimeError(f"Could not prepare {failed}")
    searches = []
    for codec in codecs:
        searches.append([f"array-encode/{codec}", "heap", "arrayencode", codec, str(source)])
        searches.append([f"array-decode/{codec}", "heap", "arraydecode", codec, str(own[codec])])
    for codec in STREAM_ENCODERS:
        if codec in codecs:
            searches.append([f"session-encode/{codec}", "heap", "streamencode", codec, str(source)])
    for codec in STREAM_DECODERS:
        if codec in codecs:
            searches.append([f"stream-decode-tool/{codec}", "heap", "streamdecode", codec, str(reference[codec])])
            searches.append([f"stream-decode-own/{codec}", "heap", "streamdecode", codec, str(own[codec])])
    print(f"Running {len(searches)} heap searches on {name}", flush=True)
    results, meta = kotlin_jobs(searches, work, args.threads, "256m", "memory.tsv")
    head, dirty = revision()
    report = {
        "recorded_at": datetime.now(timezone.utc).isoformat(), "revision": head, "dirty": dirty,
        "archivekodec": meta, "file": name, "bytes": SILESIA[name][0],
        "compressed": {codec: path.stat().st_size for codec, path in own.items()},
        "reference_compressed": {codec: path.stat().st_size for codec, path in reference.items()},
        "heap_mib": {key: (row["size"] if row["ok"] else None) for key, row in results.items()},
        "errors": {key: row["error"] for key, row in results.items() if not row["ok"]},
    }
    (work / "memory.json").write_text(json.dumps(report, indent=2) + "\n")

    def cell(key):
        if key not in report["heap_mib"]:
            return ""
        value = report["heap_mib"][key]
        return "failed" if value is None else ("over 1,536" if value < 0 else f"{value:,}")

    lines = [f"Smallest -Xmx in MiB for one call on {name} ({SILESIA[name][0]:,} bytes), "
             f"ArchiveKodec {meta.get('version')} at {head[:12]}{' (with local changes)' if dirty else ''}, "
             f"{meta.get('runtime')}, {report['recorded_at'][:10]}.", "",
             "| Codec | compress | decompress | encoder session | decompress(source, sink) of the tool's stream "
             "| decompress(source, sink) of its own stream |",
             "| --- | ---: | ---: | ---: | ---: | ---: |"]
    for codec in codecs:
        lines.append(f"| {codec} | {cell('array-encode/' + codec)} | {cell('array-decode/' + codec)} | "
                     f"{cell('session-encode/' + codec)} | {cell('stream-decode-tool/' + codec)} | "
                     f"{cell('stream-decode-own/' + codec)} |")
    markdown = "\n".join(lines) + "\n"
    (work / "memory.md").write_text(markdown)
    print(markdown)
    if report["errors"]:
        print(f"Errors: {report['errors']}", file=sys.stderr)
    if not args.keep:
        shutil.rmtree(out)


def render(report):
    total = report["corpus"]["bytes"]

    def size(value):
        return "failed" if value is None else f"{value:,}"

    def ratio(value):
        return "" if value is None else f"{total / value:.3f}"

    lines = [f"Silesia Corpus, {len(report['corpus']['files'])} files, {total:,} bytes. "
             f"ArchiveKodec {report['archivekodec'].get('version')} at {report['revision'][:12]}"
             f"{' (with local changes)' if report['dirty'] else ''}, {report['recorded_at'][:10]}.", "",
             "| Codec | ArchiveKodec setting | ArchiveKodec bytes | Ratio | Reference setting | Reference bytes | Ratio | ArchiveKodec / reference |",
             "| --- | --- | ---: | ---: | --- | ---: | ---: | ---: |"]
    for row in report["rows"]:
        ak, ref = row["archivekodec_bytes"], row["reference_bytes"]
        relative = f"{ak / ref:.3f}" if ak and ref else ""
        lines.append(f"| {row['codec']} | {row['variant']} | {size(ak)} | {ratio(ak)} | {row['reference'] or 'none'} | "
                     f"{'' if ref is None else size(ref)} | {ratio(ref)} | {relative} |")
    checks = report["interop"]
    failed = [row for row in checks if not row["ok"]]
    lines += ["", f"Interoperability: {len(checks) - len(failed)} of {len(checks)} decodes byte-exact."]
    for row in failed:
        lines.append(f"- FAILED: {row['direction']}, {row['codec']} {row['setting']}, {row['file']}: {row['error']}")
    lines += ["", "Tools: " + "; ".join(f"{value}" for value in report["tools"].values()) + "."]
    return "\n".join(lines) + "\n"


if __name__ == "__main__":
    try:
        main()
    except (OSError, ValueError, RuntimeError, subprocess.SubprocessError) as error:
        print(str(error), file=sys.stderr)
        sys.exit(1)
