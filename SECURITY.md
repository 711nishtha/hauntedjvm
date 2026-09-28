# Security and privacy

## What the application does and does not touch

HAUNTEDJVM is designed to be entirely offline and self-contained.

- **Network:** none at runtime. The application opens no sockets and contacts no server.
  (Building it downloads dependencies through Gradle, as any Gradle project does.)
- **Files:** it writes only
  - session files and reports where you choose in a save dialog (default `~/.hauntedjvm/sessions`),
  - its own log at `~/.hauntedjvm/logs/hauntedjvm.log`.
  It reads only files you open from a dialog or pass with `--load`.
- **Devices:** it opens an audio *output* line if one exists. It never opens a microphone,
  camera or any input device.
- **System information:** the REAL JVM panel reads this JVM's own MXBeans and a JFR event
  stream kept in memory (never written to disk). Nothing else about your machine is read.
- **The story:** messages inside the simulation may address "the operator", but they are built
  only from simulation state and the actions you take inside the application.

## Reporting a vulnerability

Please report security issues privately to nishthasharma70311@gmail.com rather than in a public
issue. Include the version or commit and steps to reproduce. You should hear back within a week.

Session files are parsed with Jackson with unknown properties rejected and polymorphic types
restricted to the simulation's own sealed hierarchies; a malformed or edited file is refused
rather than partially loaded. If you find a way around that, that is exactly the kind of report
this policy is for.
