# Working manual-control checkpoint — 0.4.0

The operator confirmed that this version works on **14 November 2026 (Asia/Dubai)**. Preserve this tag as the recovery point before future changes.

- Git tag: `manual-control-v0.4.0-working`
- Android: `org.maxbot.app`, version `0.4.0-manual`, version code `7`
- Firmware source identity: `19b9632ee8b0b3dcee73b448b6b6621dbc869adee551eae9322386bf3d9321ba`
- Actuator calibration identity: `210b9c6c3489ffac1a75e66182cf207d60b6042937fac9dc7cd534210accd687`
- Manual input freshness: 300 ms; phone-to-ESP watchdog: 500 ms; clock uncertainty bound: 75 ms.
- Brief manual gaps neutralize output without forcing Reset. Fresh input resumes while manually armed. Manual arming skips the extra zero-output takeover acknowledgement. Explicit Stop/disarm, emergency stop, authenticated packets, measured steering limits and reverse pause remain.
- WASD drive ramp: 100 %/second by default; steering ramp: 250 %/second. Both are editable in the dashboard.
- Logical PWM ±255 maps to physical duty ±170. Battery monitoring is disabled; no physical stop switch is configured.

The private local recovery bundle is `dist/checkpoints/manual-control-v0.4.0-working/`. It preserves the exact APK, firmware images, original deployment manifest, private runtime configuration and host launcher files, with SHA-256 hashes. It is ignored by Git because the binaries/configuration contain pairing and Wi-Fi credentials. GitHub stores this source checkpoint and public documentation; no credential-bearing release binaries are published.

## Artifact hashes

| Artifact | SHA-256 |
| --- | --- |
| `maxbot-0.4.0-manual.apk` | `68a3cbe12dc88ba9486702f7b532cd2f90c8d44dca81c3da488121428b7b3cf6` |
| `maxbot.ino.bin` | `0cfc5626f9f7b942ce16d4e39112a72df62125a53bc125e99c3d6e1a354ad0f4` |
| `maxbot.ino.bootloader.bin` | `17a546b0b936fce668e35c8428b1806f9cb9e09f41f7eb86613cff68dfab8f2e` |
| `maxbot.ino.partitions.bin` | `148b959cbff1c38aa8e1d5c0ba9d612c54997b945e56a63f41223eef650653a1` |
| `maxbot.ino.merged.bin` | `133db4b0f99e676dfd092113f09e4b5d3837425a70876a231d0c546f4061cdbc` |

## Validation and recovery

The implementation passed 19 Android unit tests, 2,961 core checks, 27 Node tests, firmware calibration/deadline/lifecycle checks and isolated desktop/mobile browser checks. Flash hashes and 150 signed, disarmed zero-output telemetry samples were verified. The subsequent operator confirmation establishes that manual control works on the car; no measured latency, stopping-distance or autonomous-course certification is claimed.

Recover source using the tag in a separate checkout/worktree, then follow [manual-control.md](../manual-control.md). Restore the private configuration and host files using the local bundle's `RESTORE.md` and `checkpoint.json`. The saved APK and firmware are the exact recovery artifacts; keep that directory private. Autonomous operation remains uncommissioned.
