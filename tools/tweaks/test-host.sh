#!/bin/sh
# No Gradle daemon, emulator, network, or vehicle required.
set -eu
cd "$(dirname "$0")/../.."
exec python3 tools/tweaks/test_host.py
