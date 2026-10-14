#!/usr/bin/env bash
set -euo pipefail
PROJECT_DIR="$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")" && pwd)"
"$PROJECT_DIR/build.sh"
cd -- "$PROJECT_DIR"
mkdir -p build/test-classes
sources=("$PROJECT_DIR"/src/test/java/br/edu/utfpr/sd/ep1/*.java)
javac --release 17 -Xlint:all,-classfile -encoding UTF-8 -cp 'build/classes:lib/*' -d build/test-classes "${sources[@]}"
exec java -cp 'build/classes:build/test-classes:lib/*' br.edu.utfpr.sd.ep1.TesteEp1 "$@"
