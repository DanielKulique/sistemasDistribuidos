#!/usr/bin/env bash
set -euo pipefail
PROJECT_DIR="$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")" && pwd)"
"$PROJECT_DIR/build.sh" --classes-only
cd -- "$PROJECT_DIR"
exec java -cp 'build/classes:lib/*' br.edu.utfpr.sd.ep1.Servidor "$@"
