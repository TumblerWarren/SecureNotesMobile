# Performance acceptance

Reference workload: physical 4 GB midrange Android phone, 10,000 files/100 GB, individual video up to 20 GB. Record actual model, SoC, OS, free storage, temperature and compilation state. These are acceptance targets, not results already achieved.

| Measurement | Target |
|---|---|
| Cold usable locked screen | p95 <= 1 s |
| Argon2id unlock | Aim 1–3 s, without lowering 64 MiB / 3 passes |
| First 50 metadata items after KDF | p95 <= 200 ms |
| Indexed search | p95 <= 250 ms, excluding 250 ms input debounce |
| Typical note save | p95 <= 150 ms after 300 ms debounce |
| Supported 1080p H.264/AAC start | p95 <= 1 s |
| Seek, fixture with 2-second keyframes | p95 <= 750 ms |
| Large import memory delta | <= 32 MiB above unlocked idle, previews paused |
| Import throughput | >= 70% of equivalent durable plain streaming copy |
| Shared decoded-preview cache | <= 32 MiB |

Implemented controls: 50-row pages/200 retained metadata rows; separate immutable file objects; reusable 64 KiB import buffer; 1 MiB authenticated segments; one import at a time; no transcoding; no repeated password KDF per file; incremental note writes; native media buffer target 24 MiB; one PDF page at a time; visible 512-pixel image tiles with a 2048-pixel fallback; a shared 32 MiB decoded-preview cache; encrypted thumbnail blobs; paged UTF-8 text. Media codec/graphics allocations are additional and must be measured.

The host `:core:largeFileTest` checks that a >4 GiB stream and 64-bit seeks fit under a 128 MiB JVM heap. It prints copy/import timings for that host. It does not prove Android process-memory, battery, 20 GB playback, or 100 GB collection targets.

The benchmark module provides 30-iteration cold-start measurements and startup/vault-journey profile generators. Run `:benchmark:connectedBenchmarkAndroidTest` on a dedicated test device; these tests clear only the separate `.benchmark` application. Use `python3 tools/install_profiles.py --help` to install genuinely generated profiles. No profile is fabricated or committed before generation.

Pending acceptance: physical-device runs (30 iterations for latency cases), generated Baseline Profiles, 3 repeated sustained import measurements, thermal/power measurements, allocation traces and the complete target workload. Use synthetic fixtures only in traces; never log keys, passwords or real filenames/content.

## Recorded host check

On the Linux workspace with JDK 21, the synthetic 4,295,032,833-byte test passed under a 128 MiB maximum JVM heap, including authenticated reads beyond 2 GiB and 4 GiB. One run took 1.42 s for a durable plain copy and 9.03 s for encrypted import (about 454 MiB/s; 15.8% of that host's plain-copy throughput). This is below the 70% relative-throughput target on this host; it is not a physical-phone acceptance result. The run caught and fixed byte-at-a-time forwarding in the output wrapper. Android runtime, codec, thermal and full-collection measurements remain separate gates.
