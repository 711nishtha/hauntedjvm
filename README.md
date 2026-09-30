# HAUNTEDJVM

It's 2:40 in the morning at FACILITY-07. You're the night operator: eight security cameras, a
badge tracker, a list of the building's processes, and a recording of everything that has
happened so far.

For a while, nothing is wrong. People do their rounds, drink cold coffee, and chat in the break
room. Then a door opens with nobody near it. Someone swears they saw something in the tape
archive, and the next person they tell remembers it happening in a different room. A process
you killed keeps allocating memory. One of the badges on the floor plan keeps walking, even
though the camera in that room shows it empty.

None of this is scripted. HAUNTEDJVM is a small simulated world written in Java, plus something
that interferes with it. Everything you see comes from how the people inside react. Same seed,
same night. Different seed, different night. And what you do changes it: where you look, which
lights you switch off, whether you rewind.

![A camera feed](docs/screenshots/01-camera.png)

| | |
|---|---|
| ![Badge tracker](docs/screenshots/02-floorplan-inspector.png) | ![Process table](docs/screenshots/03-process-table.png) |
| The tracker shows where badges are. The cameras show what's actually there. | Kill `cam-mux` and every feed turns to static. |

## Running it

You need Java 21 or newer. Gradle takes care of everything else.

```bash
git clone https://github.com/711nishtha/hauntedjvm.git
cd hauntedjvm
./gradlew run
```

Want a particular night? Any word works as a seed:

```bash
./gradlew run --args="--seed archive"
./gradlew run --args="--seed 0xC0FFEE --entities 24 --simulation-speed 2"
```

You can also run it without a window and just read the incident log:

```bash
./gradlew installDist
./app/build/install/hauntedjvm/bin/hauntedjvm --headless --seed archive
```

```
03:32:39  TERMINAL: "WHO IS WATCHING CAM-01" (no sender)
03:45:00  INCIDENT LEVEL 1 (ODD)
03:47:20  [SYSTEM/3] pid 2200 (hvac-ctl) terminated at 03:47:13 and is still allocating
03:50:10  [MEMORY/3] DAGNY remembers "ROOM-12, but the room was longer than it should be" at 03:46:30. No such event was recorded
...
log digest      3d5a870b0b5303d08ce732e0997ac6708e1eca18f622a05e229b6c24856c9ddc
```

That last line is a fingerprint of the whole night. Run the same seed on any machine and you'll
get the same one. CI checks exactly that on Linux, Windows and macOS.

## Playing

Press **F1** in the app for the full list. The ones you'll use most:

| Key | |
|---|---|
| **Space** | pause / resume |
| **← →** | scrub back and forth through the recording |
| **1–8**, **F**, **S** | cameras, floor plan, process table |
| **click** | inspect whoever or whatever you clicked |
| **N** | write a note |
| **R** | while reviewing: rewind the night to this point and carry on from there |

A few things worth knowing:

- Everyone has a personality and a memory. A bad experience in a room will keep someone out of
  it, and the people who trust them will start avoiding it too.
- Rumours spread, and they don't always stay accurate.
- The simulation knows which camera you're watching.
- Rewinding is allowed. Some of the people inside may notice.
- Not every night gets bad. Some stay quiet. Some really don't.

## What's under the hood

Although it's a horror project, the Java side was built seriously:

- **Event-sourced simulation.** Every change is an event in an append-only log, and the log is
  the whole truth. Replay, rewind, saved sessions and the evidence links in the UI all come from it.
- **Fully deterministic.** Seeded, counter-based randomness and a single-threaded tick mean the
  same seed produces the same log, bit for bit.
- **Real time travel.** Checkpoints plus events rebuild any moment in microseconds. Rewinding
  forks the timeline instead of editing it.
- **Modern Java 21.** Records and sealed interfaces model the world, so the compiler rejects an
  event type that replay doesn't handle. Virtual threads handle I/O. The UI and the simulation
  share no locks.
- **Honest telemetry.** The side panel keeps *real* JVM numbers (MXBeans and JFR), *simulated*
  numbers, and *fictional* incident readouts in separate sections, each clearly labelled.
- **No bundled media.** The visuals are drawn in code, the sound is synthesised live, and
  nothing touches your files, camera, microphone or network.

It's split into modules: `core` (the simulation, with no dependencies at all), `persistence`,
`diagnostics`, `audio`, `app` (JavaFX), and `benchmark` (JMH).

For how it all works (the event model, replay, determinism, the anomaly engine, threading,
benchmarks and the design decisions), see **[docs/ARCHITECTURE.md](docs/ARCHITECTURE.md)**.

## Building and testing

```bash
./gradlew check        # all tests + static checks
./gradlew benchmark    # JMH performance suite
```

There are 110+ tests, including property-based tests showing that rebuilding any moment of any
seeded night gives exactly the world the live simulation had.

## Contributing

Ideas for new anomalies are very welcome. [CONTRIBUTING.md](CONTRIBUTING.md) has the few rules
that keep replay exact.

## License

MIT. The bundled fonts (IBM Plex Mono and VT323) are under the SIL Open Font License; see
[THIRD_PARTY_NOTICES.md](THIRD_PARTY_NOTICES.md).
