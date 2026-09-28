# Contributing

Thanks for wanting to help keep FACILITY-07 running (or not).

## Building

You need nothing but a JDK 21 or newer on the path; the Gradle wrapper does the rest, and the
toolchain resolver downloads JDK 21 if your default JDK is different.

```bash
./gradlew check          # compile with -Werror, run every test, Checkstyle
./gradlew run            # open the workstation
./gradlew benchmark      # JMH suites (a few minutes)
./gradlew :app:screenshots   # re-render docs/screenshots
```

## Ground rules for the simulation

These are what keep replay exact. Reviews will hold changes to them.

1. **State changes only through events.** Systems decide and `emit`; only `EventApplier`
   mutates `WorldState`. If you need new state, you need a new event (and a sample for it in
   `persistence/src/test/.../SampleEvents.java`; a test fails until you add one).
2. **Systems are stateless.** Anything that influences a future decision lives in world state.
   A pure memo (like the navigator's flow-field cache) is fine. `DeterminismTest` resumes an
   engine mid-run to catch violations.
3. **Randomness comes from `ctx.rng(purpose, subject)`.** Never `Math.random()`, never a
   long-lived `Random`, never iteration over a `HashMap` to make a decision.
4. **Anomalies change state, not pixels.** A new perturbation has to leave something the
   detector can find, or something people in the facility can witness.
5. **Keep the layers honest.** Real JVM numbers come only from the `diagnostics` module and
   appear only in the REAL JVM panel. Nothing in the app may read the user's files, devices or
   network.

If a change alters simulation behaviour, the same seed will tell a different story afterwards.
That is allowed, but say so in `CHANGELOG.md`, because old saved sessions will still replay
their recorded history but will continue differently when resumed.

## Style

Checkstyle enforces the basics. Beyond that: small classes, records for data, sealed
interfaces for closed sets, comments that explain *why*. Match the surrounding code.

## Commits and pull requests

Conventional-ish prefixes (`feat:`, `fix:`, `perf:`, `test:`, `docs:`, `refactor:`, `ci:`),
one logical change per commit, and a PR description that says how you checked it.
