#!/usr/bin/env python3
"""Regenera o recurso Java a partir do YAML fixado. Requer PyYAML apenas para regenerar."""
import hashlib
import json
from pathlib import Path
import yaml

root = Path(__file__).resolve().parents[1]
raw = (root / "docs/asyncapi.yaml").read_bytes()
assert hashlib.sha256(raw).hexdigest() == "57964822b0858b3dc8c0222a358f6d884f3ca98ef363ae2353c8f7f797186a94"
doc = yaml.safe_load(raw)
artifact = {
    "version": doc["info"]["version"],
    "sha256": hashlib.sha256(raw).hexdigest(),
    "schemas": doc["components"]["schemas"],
    "messages": {key: value["payload"] for key, value in doc["components"]["messages"].items()},
    "access": {key: value.get("x-acesso") for key, value in doc["operations"].items()},
}
(root / "src/main/resources/contrato-2.8.0.json").write_text(
    json.dumps(artifact, ensure_ascii=False, indent=2) + "\n", encoding="utf-8")
