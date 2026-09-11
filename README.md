# imaging-nitf NEI adapter

An alternate implementation of Non-Earth Imaging (NEI) and GLAS/GFM extension
support that uses the released, unmodified Codice `imaging-nitf` 0.10 jars.

It is published under its own coordinates, `com.vantor.nitf:imaging-nitf-nei`,
in the `com.vantor.nitf.nei` package. Nothing here occupies an upstream
namespace.

This project deliberately does not patch or replace `codice-imaging-nitf-core`.
The stock parser reads the NITF container and preserves unknown TRE bytes; this
jar decodes those bytes and reads GLAS/GFM DES payloads through the public
`DataExtensionSegment.consume(...)` API.

## Supported extensions

Image TREs:

- `CSEXRB` — Common Sensor Exploitation Reference Data
- `CSRLSB` — Common Sensor Rolling Shutter Terms
- `CSWRPB` — Common Sensor Warping Terms
- `PIXQLA` — Pixel Quality

Data Extension Segment payloads:

- `CSATTB` — Common Sensor Attitude Data
- `CSEPHB` — Common Sensor Ephemeris Data
- `CSSFAB` — Common Sensor Sensor Field Alignment
- `CSCSDB` — Common Sensor Covariance Support Data

The result is an immutable `NeiRecord` containing a flat, insertion-ordered map.
Nested fields use paths such as `EPHEM[0].EPHEM_X`, so applications do not need
to depend on Codice's internal TRE implementation.

## Prerequisites

- JDK 11 or newer
- The included Gradle wrapper (no system Gradle installation required)
- Access to `https://repo.codice.org/repository/maven-releases/` on the first
  build (the repository is already declared in `build.gradle`)

## Build and test

```bash
./gradlew build
./gradlew test
```

The dependency is intentionally pinned to the stock vendor release:

```groovy
dependencies {
    api 'org.codice.imaging.nitf:codice-imaging-nitf-fluent:0.10'
}
```

Gradle's Groovy DSL is the only build. Tests are [Spock](https://spockframework.org/)
specifications under `src/test/groovy`. Groovy stays test-scoped; the published
library and its runtime dependencies are still Java-only.

### Testing against real products

`NeiSampleSpec` scans actual NITF files through the stock jars, exactly as an
application would. Those files are commercial imagery and are **not** committed
here, so the build is pointed at them from outside:

```bash
./gradlew test -PneiSamplesDir=/path/to/nei-samples
NEI_SAMPLES_DIR=/path/to/nei-samples ./gradlew test
```

With neither set, those features skip and the rest of the suite still runs.

The first run beside a sample writes a golden summary next to it
(`product.ntf` produces `product.ntf.nei.txt`) and later runs compare against
it, so a change in what the parser decodes shows up as a diff. The summaries
land in the sample directory, not in this repository, and carry no absolute
paths or timestamps. Rewrite them deliberately with `-PneiRegolden`.

## Command-line tools

Two tools ship with the library: `nei-info` prints what a product says and
`nei-validator` says what is wrong with it. The scripts in `tools/` are thin
wrappers; each runs `./gradlew -q installDist` and then hands off to the
launcher that the Gradle `application` plugin generates for it:

```
tools/nei-info       -> build/install/imaging-nitf-nei/bin/nei-info       (NeiInfo.main)
tools/nei-validator  -> build/install/imaging-nitf-nei/bin/nei-validator  (NeiValidate.main)
```

The main classes are declared in `build.gradle`, not in the scripts. The plugin
only generates one launcher per project, so `nei-validator` gets its own
`CreateStartScripts` task there. Both launchers, plus `lib/` with every runtime
jar, live in one distribution; `./gradlew distZip` packages it for use away
from a checkout, and the wrappers are then unnecessary:

```bash
unzip build/distributions/imaging-nitf-nei-*.zip
imaging-nitf-nei-*/bin/nei-validator /path/to/product.ntf
```

## nei-info

```bash
./tools/nei-info /path/to/product.ntf
./tools/nei-info /path/to/product.ntf --json
```

`nei-info` is the project's `mainClass`, so it is also reachable without the
wrapper:

```bash
./gradlew run --args="/path/to/product.ntf"
```

Options match the relevant subset of the original `nitfinfo` tool:

- `--json` emits machine-readable JSON.
- `--no-tres` omits image TRE records while retaining GLAS/GFM DES records.
- `--no-color` is accepted for compatibility; this tool's text output is
  already uncoloured.

For example:

```bash
./tools/nei-info \
  "$OSSIM_DATA/example-data/1040010097996E00/nitfs/24JUN07235000-P1AS-016429889030_01_P001_chip.ntf"
```

The tool skips image pixels, parses image TRE headers, extracts DES payloads,
and prints each NEI field.

The command-line tools carry `slf4j-nop` so imaging-nitf's logging stays
silent. That binding is on the tools' classpath only (the `cliRuntime`
configuration in `build.gradle`), not the library's: an application that
embeds the library chooses its own SLF4J backend, and sees SLF4J's "no
binding" warning if it chooses none.

## nei-validator

```bash
./tools/nei-validator /path/to/product.ntf
./tools/nei-validator /path/to/*.ntf --errors-only
./tools/nei-validator /path/to/product.ntf --json
./tools/nei-validator --help
```

It is deliberately **self-contained**: no level1a XML, no focused sidecar, no
vendor reconstruction. That is what lets it run on any product, including one
whose sources are long gone. The trade-off is what it can therefore check:

- **conformance** -- fields a standard requires but the file leaves blank
  (`OSTAID`, `FSCLAS`/`ISCLAS`/`DESCLAS`, `FDT`);
- **NEI profile** -- legal under MIL-STD-2500C but wrong for an NEI product:
  `IDATIM` filled with hyphens while the ephemeris DES carry a real epoch, and a
  blank `FSCLSY`/`ISCLSY` on an unclassified segment (WARN; ERROR only when the
  segment is classified);
- **internal consistency** -- two places in the same file that must agree
  (`CSEXRB.NUM_LINES`/`NUM_SAMPLES` against `NROWS`/`NCOLS`, `BANDSB.COUNT`
  and `CSSFAB.N_BANDS` against the image's own band count, the block grid
  against the declared size, `ICORDS` declaring a system while `IGEOLO` is
  blank);
- **layout** -- a TRE or DES whose parse stops short of its payload.

It cannot check whether a *populated* value is the *correct* value. Comparing
against sources is a different job, and `nei-test-suite`'s
`verify_nitf_metadata.py` already does it.

Options:

- `--json` emits one machine-readable document for all files given.
- `--errors-only` suppresses WARN and INFO, in both the findings and the by-code summary.
- `--quiet` prints only the per-file counts and the by-code summary.
- `--no-color` is accepted for compatibility; output is already uncoloured.
- `--help` prints usage and the exit-status key, then exits `0`.

Exit status is `0` when no ERROR was raised, `1` when one was, `2` on a usage
problem and `3` when a file could not be read. WARN and INFO never fail a run,
so this is usable in a build without having to pin every legal-but-odd field.

Findings carry a stable code (`CSEXRB-NUM-LINES`, `IMG-ICORDS-WITHOUT-IGEOLO`),
so a rule can be tracked or suppressed downstream without matching on prose.
Anything reported as a defect always states both what was found and what was
expected; a check that cannot state both is emitted as INFO rather than
asserting something it cannot substantiate.

## Library use

Scan a complete NITF with stock imaging-nitf:

```java
File file = new File("product.ntf");
NeiScanReport report = new NeiNitfAdapter().scanReport(file);

for (NeiOccurrence occurrence : report.getRecords()) {
    NeiRecord record = occurrence.getRecord();
    System.out.println(record.getType() + ": " + record.getFields());
}
for (NeiParseFailure failure : report.getFailures()) {
    System.err.println(failure.getType() + ": " + failure.getMessage());
}
```

Or decode payload bytes directly, with no NITF parser involved:

```java
NeiExtensionParser parser = new NeiExtensionParser();
NeiRecord ephemeris = parser.parse("CSEPHB", desVersion, payloadBytes);
String date = ephemeris.get("DATE_EPHEM");
```

`NeiNitfAdapter.parse(Tre)` handles the raw bytes retained for unknown image
TREs. `NeiNitfAdapter.parse(DataExtensionSegment)` handles supported DESs, as
long as the Codice parse strategy included `SlottedParseStrategy.DES_DATA`.

## Why this works without core changes

Stock imaging-nitf already provides the two extension seams needed here:

1. Unknown TREs survive parsing as `Tre.getRawData()`.
2. Arbitrary DES payloads are available from `DataExtensionSegment.consume(...)`.

The adapter uses its own bounds-checked fixed-width reader for the constructs
that the 0.10 XML engine cannot describe generically, notably `order + 1`
polynomial loops, symmetric covariance counts `n(n+1)/2`, and conditions that
depend on DES subheader version.

## Blank ENCRYP compatibility

Some Maxar products write a blank NITF 2.1 `ENCRYP` field even though the
standard requires `0`. Stock imaging-nitf 0.10 rejects the file before an
extension adapter can run.

`NeiNitfAdapter.scanReport(...)` therefore wraps the input in a read-only shim.
For NITF 2.1 only, it derives the file, image, graphic, and text subheader
positions from the file header and presents a blank byte as `0` only at those
known `ENCRYP` offsets. It does not alter the file and does not accept any other
unexpected value.

One observed Maxar `CSSFAB` payload also has a 173-byte producer preamble before
the registered body. The adapter retains that data as `VENDOR_PREAMBLE` and then
parses the standard body. This behavior is isolated here rather than added to
the general-purpose vendor library.

## Current scope

- Parsing only; serialization is not implemented.
- NITF 2.1 is the tested container version.
- Direct payload parsing supports CSATTB/CSEPHB DES version-aware Earth
  orientation fields; available samples currently exercise DES version 1.
- Association is exposed through `CSEXRB` UUID fields but this project does not
  impose an application-specific object graph.
