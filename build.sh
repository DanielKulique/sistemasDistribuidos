#!/usr/bin/env bash
set -euo pipefail
PROJECT_DIR="$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")" && pwd)"
GSON_JAR="$PROJECT_DIR/lib/gson-2.14.0.jar"
MONGO_JAR="$PROJECT_DIR/lib/mongodb-driver-sync-5.2.1.jar"
command -v javac >/dev/null || { echo 'Instale um JDK 17 ou superior.' >&2; exit 1; }
test -f "$GSON_JAR" || { echo "Dependencia ausente: $GSON_JAR" >&2; exit 1; }
test -f "$MONGO_JAR" || { echo "Dependencia ausente: $MONGO_JAR" >&2; exit 1; }
mkdir -p "$PROJECT_DIR/build"
rm -rf -- "$PROJECT_DIR/build/classes"
mkdir -p "$PROJECT_DIR/build/classes"
sources=("$PROJECT_DIR"/src/main/java/br/edu/utfpr/sd/ep1/*.java)
javac --release 17 -Xlint:all,-classfile -encoding UTF-8 -cp "$PROJECT_DIR/lib/*" -d "$PROJECT_DIR/build/classes" "${sources[@]}"
if [[ "${1:-}" == "--classes-only" ]]; then
    echo 'Compilacao concluida: build/classes'
    exit 0
fi
jar --create --file "$PROJECT_DIR/build/ep1-novo.jar" -C "$PROJECT_DIR/build/classes" .
mv -- "$PROJECT_DIR/build/ep1-novo.jar" "$PROJECT_DIR/build/ep1.jar"
echo 'Compilacao concluida: build/ep1.jar'
