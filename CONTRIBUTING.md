# Contributing to ArchiveKodec

Open work is in [GitHub Issues](https://github.com/yuroyami/ArchiveKodec/issues). The library
documentation is in [docs/](docs/index.md).

## Build

The build uses the [Kotlin Toolchain](https://github.com/JetBrains/kotlin-toolchain).
`./kotlin` (or `kotlin.bat` on Windows) downloads the toolchain, the JDKs, Kotlin/Native, Node
and Chrome on first use. `project.yaml` lists the modules, each module has a `module.yaml`,
and the three published modules share `library.module-template.yaml`.

You need the Android SDK. Set `ANDROID_HOME` to it, because the toolchain does not read
`local.properties`.

```bash
./kotlin build                                   # all modules, all platforms this host can build
./kotlin test -p jvm -p android                  # some platforms; -m archivekodec for one module
./kotlin check abi klibAbi version               # the ABI and version checks
python3 integration-tests/web-tests/run.py       # the JS and wasmWasi tests in Node
python3 integration-tests/swift-consumer/run.py  # macOS arm64: compile and run the Swift consumers
```

The local plugins in `build-plugins/` add what the toolchain does not do by itself:

| Plugin | What it adds |
| --- | --- |
| `abi-check` | The `abi` and `klibAbi` checks, and the `updateAbi` and `updateKlibAbi` commands |
| `dokka` | The `dokka` and `dokkaSite` commands, which write the API reference to `build/dokka/html` |
| `jvm-module` | A `module-info.class` in the JVM jars of the core and `archivekodec-io` |
| `version-constant` | The `version` check and the `updateVersion` command for `ArchiveKodec.VERSION` |

Two scripts build what no plugin can make. `scripts/apple_frameworks.py` links the Apple
frameworks and XCFrameworks, and `scripts/js_package.py` assembles the JavaScript module.
[Swift](docs/swift.md) and [JavaScript and TypeScript](docs/javascript.md) show how to use
them.

## Tests

The common tests run on every platform that can run them: the JVM, the Android host, Node.js,
headless Chrome, Linux, Windows, macOS and the iOS, tvOS and watchOS simulators. The Android
tests run on the host JVM against the Android target's compiled code, not on an emulator or a
device.

`./kotlin test -p wasmJs` runs the wasmJs tests in headless Chrome. `./kotlin test` cannot run
the JS and wasmWasi tests, so `integration-tests/web-tests/run.py` links both and runs them in
Node.

The JVM tests also check interoperability with the JDK:

- `ZlibInteropTest` and `WrapperDecodeInteropTest` exchange raw DEFLATE, zlib and gzip with
  `java.util.zip` in both directions.
- `ZipInteropTest` exchanges ZIP files with `java.util.zip`, including a ZIP64 archive of
  70,000 entries.
- `JavaInteropTest` compiles and runs Java source against the public API.

### Corpora

`CorpusSliceTest` runs on every target over slices of four Canterbury Corpus files. Every
encoder must round-trip them, and every encoder except STORE and raw Snappy must make them
smaller. On the JVM, `CorpusRoundTripTest` does the same over the whole Canterbury Corpus,
committed under `archivekodec/testResources@jvm/canterbury` with SHA-256 sums. It also checks
our DEFLATE, zlib and gzip against `java.util.zip` at levels 1, 6 and 9, and holds our size
within 0.5% of zlib's.

The 212 MB Silesia Corpus is too large to commit, so it runs on request:

```bash
scripts/fetch-silesia.sh /tmp/silesia
ARCHIVEKODEC_SILESIA=/tmp/silesia ./kotlin test -m archivekodec -p jvm --include-classes='*CorpusRoundTripTest'
```

### Benchmarks

The unpublished `benchmarks` module measures fixed corpora on the JVM and Node, with JDK
references. Run `python3 benchmarks/run.py --check` for the smoke comparison.
[Benchmarks](docs/benchmarks.md) describes the method and the baseline.

## ABI references

Each published module keeps its ABI references in its own `api/` folder: a JVM dump and a
merged klib dump of every other platform. The Android artifact holds the JVM classes, so it has
no reference of its own. The klib check reads the klibs of all platforms, so run
`./kotlin build` on macOS first.

```bash
./kotlin do updateAbi        # rewrite the JVM dumps; review and commit the diff
./kotlin do updateKlibAbi    # rewrite the klib dumps
```

Update a reference only with an intended API change, and name that change in the commit and
in `CHANGELOG.md`. [api/README.md](api/README.md) describes the references and the frozen 0.1.0
record.

## Version and release

The version is `publishing.version` in `library.module-template.yaml`. The same file repeats
it as the `archivekodec.expectedVersion` test property, which `VersionTest` compares with
`ArchiveKodec.VERSION`. A development build carries a `-SNAPSHOT` suffix.

- `./kotlin do updateVersion` writes `ArchiveKodec.VERSION` from the template.
- `./kotlin check version` fails when the two differ.
- The portable lane fails unless every coordinate in `README.md` and `docs/index.md` names the
  same release.

To release, remove the suffix and publish. Then set the next `-SNAPSHOT` and update the
coordinates in `README.md` and `docs/index.md` in the same commit.

## CI

Every pull request and every push to `main` runs the `CI` workflow. Its `Verification` check
needs all three platform lanes and the benchmark check to pass, so select `Verification` in
branch protection. Each lane runs `integration-tests/ci/run.py`:

- **Linux:** builds every non-Apple platform. Runs the JVM, Android host, wasmJs and `linuxX64`
  tests, and the JS and wasmWasi tests in Node. Runs the JVM ABI check, the version checks and
  the class file checks, and runs the JavaScript and TypeScript consumers.
- **macOS:** builds every platform. Runs the iOS, macOS, tvOS and watchOS simulator tests and
  the klib ABI check. Runs the Swift consumers and the Swift package over the release
  XCFrameworks.
- **Windows:** runs the `mingwX64` tests.

The docs site and the API reference deploy to GitHub Pages only after `Verification` passes on
the same commit of `main`.

Run the same lanes locally, with the Android SDK in `ANDROID_HOME`:

```bash
python3 integration-tests/ci/run.py portable
python3 integration-tests/ci/run.py apple    # macOS arm64 with Xcode and the simulator runtimes
python integration-tests/ci/run.py windows   # Windows x64
```

To check that the gate fails, run `python3 integration-tests/ci/run.py portable --failure-canary`.
It inserts a failing JVM test, expects a nonzero exit with
`CiFailureCanaryTest.deliberateFailure` and removes the test afterwards. Run the normal portable
lane again to restore passing reports. The workflow's manual `failure_canary` input does the
same in CI and skips the Pages deployment.

## Documentation

The docs site builds from `docs/` with MkDocs Material:

```bash
pip install -r docs/requirements.txt
mkdocs build --strict
```

The documentation uses American spelling.
