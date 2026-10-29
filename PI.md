# Pi Zero qualification

This is the final hardware/runtime gate for Experiment 010.

The Raspberry Pi image is maintained separately in:

`brainboxemb/2026-010-03.raspberry-pi-test-image`

That repository builds the generic **Brainbox Zero** image. It deliberately does not contain
this benchmark or consumer application code.

## Boundary

The Experiment 010 Pi bundle contains:

- Java 8-compatible experiment bytecode;
- the JMH benchmark jar;
- one shell script that captures environment/thermal state and runs the measurements.

It does **not**:

- modify the Brainbox Zero image;
- install Java;
- contain Wi-Fi/user credentials;
- require Maven or a compiler on the Pi.

A compatible Java runtime must already be present on the target. The script records
`java -version` and stops clearly when `java` is unavailable.

The first physical target is the original Raspberry Pi Zero W (ARMv6, 512 MB) running the
32-bit Brainbox Zero image.

## Java runtime candidate

ARMv6 is the limiting factor, not the Experiment 010 bytecode. The experiment is compiled for
Java 8 and can run on a newer compatible JVM, but many modern ARM32 JVM builds target ARMv7+.

The first runtime candidate for the physical Zero W is **Azul Zulu 11 for Linux
aarch32 hard-float**.

Why:

- Pi4J documents Zulu 11 as an ARMv6-compatible JVM for the original Pi/Zero;
- Azul still publishes current Zulu 11 `linux_aarch32hf` tarballs;
- Azul's platform matrix explicitly says Zulu 17+ ARM32 hard-float is ARMv7/ARMv8 only.

Do not bake that runtime into Brainbox Zero or auto-download it from the qualification script
yet. First validate it manually on the current Trixie image. After that, pin the exact runtime
archive + SHA-256 in whichever repository ultimately owns the runtime installation.

Current references:

- https://www.pi4j.com/documentation/java-for-arm/
- https://docs.azul.com/core/supported-platforms
- https://cdn.azul.com/zulu/bin/

## Build/download the bundle

GitHub Actions workflow:

```text
Pi Zero qualification bundle
```

publishes artifact:

```text
experiment-010-pi-bundle
```

The artifact contains:

```text
experiment-010.jar
experiment-010-jmh.jar
run-pi-qualification.sh
BUNDLE-SHA256SUMS
```

Copy those files to one directory on the Pi.

## Run

On the Pi:

```sh
chmod +x run-pi-qualification.sh
./run-pi-qualification.sh
```

Results are written below:

```text
pi-results/
```

The script first runs the small deterministic diagnostics for population 500, then a selected
JMH comparison of only:

- `SORTED_ARRAY`;
- `POOLED_AVL`.

The selected JMH paths are:

- pure rank lookup;
- replace/prepare/rank/restore;
- per-registration synchronization scan;
- 500-row snapshot;
- start-change + synchronization roundtrip.

The Pi JMH run is deliberately shorter than the desktop evidence:

- 2 forks;
- 3 warmup iterations;
- 5 measurement iterations;
- 500 ms per iteration;
- GC/allocation profiler.

This is enough to check whether the ARMv6 target reverses the desktop architecture conclusion
without spending excessive time on baseline candidates that are already uncompetitive.

## Runtime settings

The default benchmark JVM settings are:

```text
-Xms32m
-Xmx192m
-XX:+UseSerialGC
```

Override them when necessary:

```sh
JAVA_OPTS="-Xms32m -Xmx128m -XX:+UseSerialGC" ./run-pi-qualification.sh
```

To skip JMH and run only the fast diagnostics:

```sh
RUN_JMH=0 ./run-pi-qualification.sh
```

## Environment evidence

The script records before/after information including:

- UTC timestamp;
- `uname` / architecture / word size;
- OS release;
- Brainbox Zero image identity when present;
- Java version;
- memory;
- uptime/load;
- CPU model;
- Raspberry Pi firmware throttle/temperature information when `vcgencmd` is available.

A result should not be treated as clean performance evidence if the Pi reports active
undervoltage or thermal throttling.

## Acceptance question

The Pi run answers:

> At the real ARMv6 target, does SortedArrayRanking remain a sensible simpler choice around
> 500 registrations, or does PooledAvlRanking gain a material enough advantage to justify its
> extra code and retained memory?

The desktop decision remains SortedArray unless the physical target provides contrary evidence.

## Returning the evidence

Retain the complete `pi-results/` directory together with:

- the Brainbox Zero image identity;
- the Experiment 010 bundle SHA-256;
- target model;
- Java runtime/version.

Those results can then be committed under `evidence/` and referenced from `DECISION.md`.
