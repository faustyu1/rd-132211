#!/bin/bash
# Second client for testing multiplayer locally: same build and jars as run.sh, but
# launched from a scratch directory so this instance keeps its own settings.properties,
# servers.properties and saves/ instead of fighting the first client over them.
set -e
DIR="$(cd "$(dirname "$0")" && pwd)"
RD_RUN_DIR="${RD_RUN_DIR:-/tmp/rubydung-client2}"
mkdir -p "$RD_RUN_DIR"
export RD_RUN_DIR
exec "$DIR/run.sh"
