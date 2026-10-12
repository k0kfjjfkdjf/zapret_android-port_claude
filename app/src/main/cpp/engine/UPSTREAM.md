# Engine

A fork of [ByeDPI](https://github.com/hufrea/byedpi) (MIT, see `LICENSE`), imported from
commit `ba532298de7b28cfe854aea83d061369d13ca290` (v0.17.3-38-gba53229). Windows-only files, Docker and packaging files were dropped.

Changes in this fork:

- `engine_reset()` restores global state so that `main()` can run several times in one
  process (the strategy tester starts the engine once per candidate).
