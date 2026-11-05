#!/usr/bin/env python3
"""Executa o testador oficial do commit fixado contra um servidor Java no banco de testes."""
import hashlib
import io
import os
from pathlib import Path
import shutil
import socket
import subprocess
import sys
import time
import urllib.request
import zipfile

root = Path(__file__).resolve().parents[1]
commit = "342bbcf167977e445c9261610d7992c0dbc59ccf"
config = (root / (sys.argv[1] if len(sys.argv) > 1 else "config/mysql-test.properties")).resolve()
keys = dict(line.split("=", 1) for line in config.read_text(encoding="utf-8").splitlines()
            if line and not line.lstrip().startswith(("#", "!")) and "=" in line)
if keys.get("database", "").strip() != "ep1_reserva_salas_teste":
    raise SystemExit("Use somente o banco ep1_reserva_salas_teste; dados da aplicacao protegidos.")
if not shutil.which("node"):
    raise SystemExit("Instale Node.js 18+ para executar o testador oficial.")
destino = root / "build/protocolo-oficial"
spec = root / "docs/asyncapi.yaml"
if not (destino / "asyncapi.yaml").exists() or (destino / "asyncapi.yaml").read_bytes() != spec.read_bytes():
    url = f"https://codeload.github.com/pedrosalvatori/protocolo-reserva-salas/zip/{commit}"
    with urllib.request.urlopen(url, timeout=30) as resposta:
        arquivo = zipfile.ZipFile(io.BytesIO(resposta.read()))
    for nome in arquivo.namelist():
        partes = nome.split("/")[1:]
        if not partes or not partes[-1] or partes[0] not in ("scripts", "assets", "asyncapi.yaml", "package.json"):
            continue
        if ".." in partes:
            raise SystemExit("Caminho invalido no arquivo oficial")
        alvo = destino.joinpath(*partes)
        alvo.parent.mkdir(parents=True, exist_ok=True)
        alvo.write_bytes(arquivo.read(nome))
assert (destino / "asyncapi.yaml").read_bytes() == spec.read_bytes()
assert hashlib.sha256(spec.read_bytes()).hexdigest() == "57964822b0858b3dc8c0222a358f6d884f3ca98ef363ae2353c8f7f797186a94"
with socket.socket() as sock:
    sock.bind(("127.0.0.1", 0))
    porta = sock.getsockname()[1]
log_path = root / "build/servidor-oficial.log"
with log_path.open("w", encoding="utf-8") as log:
    servidor = subprocess.Popen(["java", "-cp", "build/classes" + os.pathsep + "lib/*",
        "br.edu.utfpr.sd.ep1.Servidor", str(porta), str(config)], cwd=root, stdout=log, stderr=subprocess.STDOUT)
    try:
        for tentativa in range(100):
            try:
                with socket.create_connection(("127.0.0.1", porta), timeout=.3):
                    break
            except OSError:
                if servidor.poll() is not None:
                    raise SystemExit(log_path.read_text(encoding="utf-8"))
                time.sleep(.1)
        else:
            raise SystemExit("Servidor nao iniciou em 10 segundos")
        resultado = subprocess.run(["node", str(destino / "scripts/testar-servidor.mjs"),
            "127.0.0.1", str(porta), "--timeout", "10000"], cwd=root)
    finally:
        servidor.terminate()
        try:
            servidor.wait(timeout=5)
        except subprocess.TimeoutExpired:
            servidor.kill()
            servidor.wait()
raise SystemExit(resultado.returncode)
