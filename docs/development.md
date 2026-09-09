# Development

## Prerequisites

- JDK 21 through JDK 25, used as the system JDK. The build rejects JDK 20 and earlier and JDK 26
  and later; those environments need a Gradle and quality-tool upgrade first.
- A shell that can run the Gradle wrapper.

## Build

```bash
./gradlew clean build
```

The artifact is `build/libs/hyperliquid-adapter-1.2.0.jar`. It is a thin JAR: Bookmap supplies the
Layer 0 API, Gson, and Jetty at runtime, so they are `compileOnly` here.

The adapter compiles to Java 8 bytecode. The `verifyJava8Bytecode` task checks that compiled classes
carry major version 52.

## Quality gates

Run these before distributing the JAR:

```bash
./gradlew --no-daemon spotlessCheck
./gradlew --no-daemon checkstyleMain checkstyleTest
./gradlew --no-daemon spotbugsMain
./gradlew --no-daemon test
./gradlew --no-daemon check
./gradlew --no-daemon clean build
```

SpotBugs writes HTML and XML reports under `build/reports/spotbugs`.

## Code layout

All sources live under `src/main/java/com/bookmap/plugins/layer0/hyperliquid`. Class-level
documentation is in the Javadoc; this table is only a map of where to start reading.

| Package | Responsibility |
| --- | --- |
| `hyperliquid` | Layer 0 entry point: provider, connectivity fields, source selection, connection lifecycle |
| `hyperliquid.book` | Holding and publishing the book: folding seeds and deltas, aggregating onto the subscription's tick |
| `hyperliquid.budget` | Process-wide reservations for connection, send, and subscription limits |
| `hyperliquid.concurrent` | Serializing state work and bounding market-data frames |
| `hyperliquid.model` | Immutable value and domain types |
| `hyperliquid.parse` | Turning WebSocket and REST JSON into domain types |
| `hyperliquid.session` | Bridging to Bookmap session output: subscription state, data-health monitoring, and the extra mark-price connection a relay source needs |
| `hyperliquid.trade` | Duplicate suppression for trade notifications |
| `hyperliquid.transport` | Asynchronous WebSocket boundary, implemented on Jetty |

Tests mirror this layout under `src/test/java`.

## Specs and plans

`docs/superpowers/specs/` and `docs/superpowers/plans/` hold the design history: one spec and one
plan per architectural change. Read the spec for a subsystem before changing it.

The workflow that produces those documents is described in [AGENTS.md](../AGENTS.md).
