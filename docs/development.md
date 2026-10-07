---
title: Working on Falcon
description: The repository layout, how to build and change Falcon, its conventions, and how this documentation site is published.
---

This page is for working on Falcon itself: where things are, how the build works, and the rules the code
follows.

## The repository

```
falcon/
├── pom.xml              the parent POM: shared versions, plugins, and the dependency rules
├── core/                pure-Java codecs and checksums the formats share (internal)
├── hdf5/                the HDF5 reader and writer
├── zarr/                the Zarr reader and writer, and its stores
├── ome/                 OME-Zarr on the zarr module
├── s3/                  Amazon S3 for both formats, over the AWS SDK
├── cli/                 the falcon command, built into cli/target/falcon.jar
├── tools/
│   ├── fixtures/        scripts that write test fixtures with the reference tools, and check Falcon against them
│   └── conformance/     runners for the conformance suites (Zarr, OME-Zarr, and the HDF5 library's files)
├── docs/                this site
└── .github/workflows/   CI
```

Each module has a `USER_GUIDE.md`; `hdf5` and `zarr` also have a `PLAN.md` (the design and roadmap) and a
`TODO.md` (what is left). `PLAN.md` at the root is the umbrella roadmap.

## Building

```bash
mvn verify                 # everything, with every test
mvn -pl zarr -am test      # one module, and what it needs
mvn install -DskipTests    # into ~/.m2, for other projects
```

The build needs JDK 25 and Maven 3.9; it checks both and stops otherwise. It is **reproducible**: the same
sources build byte-identical jars. Each module also builds a sources jar and a Javadoc jar.

## Rules the code follows

- **Minimal dependencies.** `core`, `hdf5`, `zarr`, and `ome` depend on nothing but `java.base` (and
  Falcon's own modules). Only the optional `s3` module (the AWS SDK) and the `cli` application (JCommander,
  and the SDK through `s3`) have dependencies, at fixed versions. The Maven Enforcer plugin fails the build
  on any other dependency, in any scope (JUnit 5, test scope only, is the exception). A new dependency, a
  build plugin included, needs the maintainer's approval before it is added.
- **No native code.** Compression codecs are written in Java from scratch (szip as CCSDS 121.0, zstd from
  RFC 8878, and so on), never by wrapping native code, and checked against their reference implementations.
- **Java 25, as Java 25.** Records, sealed types, pattern matching, and the Foreign Function & Memory API
  (`MemorySegment`), which memory-maps files of any size.
- **JPMS modules** that export only their public API; implementation packages stay encapsulated.
- **Documented public API.** javac's doclint checks every public member of the exported packages, and a
  missing or malformed comment fails the build (`-Werror`).
- **Typed failures.** Corrupt input fails with the module's exception (`HdfFormatException`,
  `ZarrFormatException`, ...), never a crash, a hang, or an unbounded allocation; robustness tests feed the
  modules corrupt and hostile input.
- **Tests with every change**, against data the reference tools wrote ([Testing and conformance](testing.md)).
  When a structure on disk is not obvious, a comment cites the specification's section.
- **Match the code around you**: its naming, comment density, and idioms.
- **License:** Apache-2.0. New files may carry the standard header: `Copyright <year> Erich Bremer`.

## Adding a test fixture

1. Write it with the reference tool, in a `tools/fixtures/gen_*.py` script (h5py for HDF5, zarr-python for
   Zarr, ome-zarr-py for OME-Zarr), so it can be made again.
2. Put it under the module's `src/test/resources/fixtures/`.
3. Have the test compute the expected values, or read them from a sidecar the script writes.
4. Add the tool, at a fixed version, to `tools/fixtures/requirements.txt` if it is new: a dev-time tool, never
   a Falcon dependency.

## This documentation site

The site is this `docs/` folder, built by GitHub Pages with Jekyll: Markdown pages, one layout
(`_layouts/default.html`), one stylesheet (`assets/css/style.css`), and the sidebar in
`_data/navigation.yml`. It uses no theme or plugin beyond what GitHub Pages provides.

**To publish it** (once): on GitHub, open the repository's **Settings → Pages**, and under *Build and
deployment* choose **Deploy from a branch**, branch **main**, folder **/docs**, and save. GitHub builds the
site on every push to `main`, at <https://ebremer.github.io/falcon/>.

**To add a page:** write `docs/<name>.md` with a front matter `title` (and `description`), and add it to
`_data/navigation.yml`. Link to other pages by their `.md` file (`[Zarr](zarr.md)`); the site turns those
links into links to the pages, and they work on GitHub too.

**To preview it** before pushing, with Ruby installed:

```bash
gem install jekyll jekyll-relative-links kramdown-parser-gfm
jekyll serve --source docs --baseurl ""      # then open http://localhost:4000
```

GitHub Pages builds with its own versions of Jekyll and its plugins, so a local preview can differ in small
ways. Never write two opening braces in a row, or an opening brace and a percent sign, in a page, even in a
code sample: Jekyll reads them as the start of a template tag. Write a Java array of arrays with a space,
`new long[][] { {0, 0}, {1, 1} }`.

## Continuous integration

`.github/workflows/ci.yml` builds and tests every module on Linux and Windows with JDK 25;
`conformance.yml` and `ome-conformance.yml` run the community conformance suites against `falcon.jar`, and
`hdf5-conformance.yml` compares Falcon with h5py on the HDF5 library's test files and reads the CVE files. See
[Testing and conformance](testing.md#continuous-integration).
