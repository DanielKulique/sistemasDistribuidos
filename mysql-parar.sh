#!/usr/bin/env bash
set -euo pipefail
PROJECT_DIR="$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")" && pwd)"
cd -- "$PROJECT_DIR"
test -f config/mysql.env || { echo 'MySQL ainda nao configurado.' >&2; exit 1; }
docker compose --env-file config/mysql.env stop mysql
echo 'MySQL encerrado. Os dados permanecem no volume Docker do projeto.'
