# One-time legacy navigation-patch adoption

This explicit maintenance tool adopts only the exact known handle-only
RightScreenHome ODEX into Tilepad's existing stock-backup/journal format. It is
not bundled in either APK and does not make general foreign-file adoption possible.

It requires the owner's matching stock capture. The candidate must match the
installed recipe's SHA-256; the paired APK must match too; and applying only the
known navigation patch to that stock must reproduce the entire current ODEX.
Any other optimized code is refused. A pending transaction or incompatible journal
is refused. The tool uses the same config lock as the runtime and normal commands.

Adoption records the original configuration and legacy ODEX, installs a verified
stock backup and ownership journal, and commits only `nav=true`. All other stored
settings remain in place. `popover` must already be off and stays off. No system
remount or write is allowed: a read-only adapter rejects any unexpected attempt.
Repeating a completed or partially staged adoption is safe for the supported state.

The device entry is `com.tilepad.migration.MigrateNav`; the existing installed
Tilepad APK supplies its core classes. Compile only `LegacyNavMigration.java` and
`MigrateNav.java` to a DEX 035 container against those classes and Android API 19.
Run as root with that container plus the installed APK on `CLASSPATH`, and pass
the verified stock candidate path as the sole argument. Use a private root-owned
`ANDROID_DATA` with a `dalvik-cache` subdirectory: NEMESIS's reduced capabilities
cannot create fresh files in the system-owned global cache. The maintenance
operator must verify hardware, back up state/package files and confirm the exact
target before running it. It never chooses an ADB device automatically.

## Host verification with a local capture

```sh
sh tools/tweaks/test-host.sh
mkdir -p build/nav-migration/classes
javac --release 8 -cp build/tweaks-host-tests -d build/nav-migration/classes \
  tools/tweaks/migration/LegacyNavMigration.java \
  tools/tweaks/migration/MigrationCheck.java \
  tools/tweaks/migration/HashCheck.java
# Use a NEW scratch directory for each run; the check intentionally retains state.
java -cp build/nav-migration/classes:build/tweaks-host-tests \
  com.tilepad.migration.MigrationCheck /path/to/captured/system \
  build/nav-migration/new-check tune-service/tweaks/patches
java -Xmx64m -cp build/nav-migration/classes:build/tweaks-host-tests \
  com.tilepad.migration.HashCheck /path/to/captured/system/app/RightScreenHome.apk \
  d1af15e34c71f06b8689c8bfc2e422d017a5ab12ffd9ac478dc1fc4dde5dd391
```

Checks cover wrong stock/ODEX/APK refusal, repeat adoption, preserved preferences,
unchanged active bytes, both panel options composing afterward, disabling the
handle while keeping the pop-over suppression, and restoring exact stock.
The 154 MiB APK digest is also verified with only a 64 MiB Java heap to prevent
regression to whole-file hashing.

## Owner's car, November 4, 2026

The supported old patch was adopted on `192.168.0.23:5555`, confirmed as actual
`tcc803x` hardware. Original tweak state and package were copied to
`/data/nemesis/backup/tilepad-tweaks-before-nav-migration-20261104` and
`/data/nemesis/backup/tilepad-before-nav-cleanup-20261104`. The migration audit is
under `/data/nemesis/backup/tilepad-tweaks/legacy-nav-migration`.

The old ODEX MD5 remains `e8eac4e6a0f33dc2e4cca904298a8682`; the managed stock
backup is `a46717495f957dab68b96cf6c9a01d4a`. The helper did not rewrite either
the panel APK or its ODEX. DST, heater options, consent and track settings were
preserved. The pop-over option was unlocked, not enabled.
