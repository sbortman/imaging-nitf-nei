# imaging-nitf NEI adapter

An alternate implementation of Non-Earth Imaging (NEI) and GLAS/GFM extension
support that uses the released, unmodified Codice `imaging-nitf` 0.10 jars.

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

The build uses Gradle's Groovy DSL. Maven remains available as a compatibility
build, so `mvn test` exercises the same source and test trees.

## Inspect a file

```bash
./tools/neiinfo /path/to/product.ntf
./tools/neiinfo /path/to/product.ntf --json
```

The equivalent direct Gradle command is:

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
./tools/neiinfo \
  "$OSSIM_DATA/example-data/1040010097996E00/nitfs/24JUN07235000-P1AS-016429889030_01_P001_chip.ntf"
```

The tool skips image pixels, parses image TRE headers, extracts DES payloads,
and prints each NEI field. An SLF4J “no binding” warning is harmless unless the
calling application wants logging; this project does not force a logging backend.

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
