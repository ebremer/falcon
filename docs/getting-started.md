---
title: Getting started
description: Build Falcon, add it to a Maven or Gradle project, and read your first HDF5 file or Zarr store.
---

This page takes you from nothing to a program that reads an HDF5 file and a Zarr store, and to a working
`falcon` command.

## 1. Install the tools

| Tool | Version | Notes |
|---|---|---|
| JDK | **25** or later | Any distribution, such as [Eclipse Temurin](https://adoptium.net/). Falcon's classes are Java 25 class files, so your project must build with Java 25 too. |
| Apache Maven | 3.9 or later | To build Falcon. |
| Git | any | To get the source. |

Check them:

```bash
java -version     # 25 or later
mvn -version      # 3.9 or later, running on Java 25
```

Python 3.11 or later is needed only to run the conformance tests and the reference checks
([Testing and conformance](testing.md)), not to build or use Falcon.

## 2. Get the source and build it

```bash
git clone https://github.com/ebremer/falcon.git
cd falcon
mvn install -DskipTests
```

`mvn install` builds every module and puts its jars into your local Maven repository (`~/.m2/repository`),
where your own projects find them. Drop `-DskipTests` to run the tests as well (a few minutes; see
[Testing and conformance](testing.md)). The build checks its own preconditions, so with the wrong JDK or
Maven it says so and stops.

Each module's jar is in its `target/` folder, beside a sources jar and a Javadoc jar:

```
hdf5/target/hdf5-0.1.0-SNAPSHOT.jar
zarr/target/zarr-0.1.0-SNAPSHOT.jar
ome/target/ome-0.1.0-SNAPSHOT.jar
...
cli/target/falcon.jar            the falcon command, with everything it needs
```

## 3. Add Falcon to your project

Falcon is not on Maven Central yet, so depend on the snapshot you installed. Add the modules you need:

| To work with | Add |
|---|---|
| HDF5 files | `hdf5` |
| Zarr stores | `zarr` |
| OME-Zarr images, plates, scenes | `ome` (it brings `zarr`) |
| Either format in Amazon S3 | `s3`, beside `hdf5` and/or `zarr` |

Each brings `core`, Falcon's codecs, with it.

**Maven:**

```xml
<properties>
    <maven.compiler.release>25</maven.compiler.release>
</properties>

<dependencies>
    <dependency>
        <groupId>com.ebremer</groupId>
        <artifactId>hdf5</artifactId>
        <version>0.1.0-SNAPSHOT</version>
    </dependency>
    <dependency>
        <groupId>com.ebremer</groupId>
        <artifactId>zarr</artifactId>
        <version>0.1.0-SNAPSHOT</version>
    </dependency>
</dependencies>
```

**Gradle** (Kotlin DSL):

```kotlin
repositories {
    mavenLocal()
    mavenCentral()
}

java {
    toolchain { languageVersion.set(JavaLanguageVersion.of(25)) }
}

dependencies {
    implementation("com.ebremer:hdf5:0.1.0-SNAPSHOT")
    implementation("com.ebremer:zarr:0.1.0-SNAPSHOT")
}
```

**Java modules.** Each library is a named module; a modular application requires the ones it uses (a
class-path application needs nothing more):

```java
module my.app {
    requires com.ebremer.falcon.hdf5;
    requires com.ebremer.falcon.zarr;
    requires com.ebremer.falcon.ome;   // if you use OME-Zarr
    requires com.ebremer.falcon.s3;    // if you use S3
}
```

## 4. Read your first file

```java
import com.ebremer.falcon.hdf5.Dataset;
import com.ebremer.falcon.hdf5.Hdf5File;
import com.ebremer.falcon.zarr.Zarr;
import com.ebremer.falcon.zarr.ZarrArray;
import com.ebremer.falcon.zarr.ZarrGroup;
import java.nio.file.Path;
import java.util.Arrays;

public class Hello {
    public static void main(String[] args) throws Exception {
        // HDF5: list the root group, and read a dataset
        try (Hdf5File h5 = Hdf5File.open(Path.of("data.h5"))) {
            System.out.println(h5.root().childNames());
            Dataset ds = h5.root().dataset("run/temperature");
            System.out.println(Arrays.toString(ds.dataspace().dimensions()));
            double[] values = ds.readDoubles();
        }

        // Zarr: open a directory store, and read part of an array
        ZarrGroup root = Zarr.open(Path.of("data.zarr")).asGroup();
        ZarrArray array = root.array("temperature");
        double[] window = array.select(new long[] {0, 0}, new long[] {10, 10}).readDoubles();
    }
}
```

Values come back flattened in row-major (C) order; the shape says how to index them. From here:

- [HDF5 how-to](hdf5.md): selections, attributes, compound data, writing, compression.
- [Zarr how-to](zarr.md): stores, groups, creating arrays, codecs, sharding.
- [OME-Zarr how-to](ome-zarr.md): images, labels, validation, pyramids.
- [S3 and HTTP](cloud.md): remote data.

## 5. Install the falcon command

`mvn install` (or `mvn -pl cli -am package -DskipTests`) builds `cli/target/falcon.jar`, the command and
everything it needs in one jar. Run it with Java 25:

```bash
java -jar cli/target/falcon.jar --help
```

Give it a short name:

```bash
# bash, zsh: in ~/.bashrc or ~/.zshrc
alias falcon='java -jar /path/to/falcon/cli/target/falcon.jar'
```

```powershell
# PowerShell: in your $PROFILE
function falcon { java -jar C:\path\to\falcon\cli\target\falcon.jar @args }
```

Then:

```bash
falcon ls -r data.h5                 # what the file holds
falcon dump data.h5 run/temperature  # its values
falcon convert data.h5 data.zarr     # as a Zarr store
```

See [The falcon command](cli.md) for everything it does.
