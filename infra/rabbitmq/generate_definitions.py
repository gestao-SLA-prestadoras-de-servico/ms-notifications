"""Gera definições com credenciais do ambiente, sem gravar segredos no checkout."""
import base64
import hashlib
import json
import os
from pathlib import Path
import secrets
import math


def main():
    user = os.environ["RABBITMQ_USERNAME"]
    password = os.environ["RABBITMQ_PASSWORD"]
    vhost = os.environ.get("RABBITMQ_VHOST", "/")
    if not user or not password or not vhost:
        raise ValueError("Credenciais e vhost não podem ser vazios")
    definitions = json.loads(Path("/topologia.json").read_text(encoding="utf-8"))
    for section in ("exchanges", "queues", "bindings", "policies"):
        for item in definitions.get(section, []):
            item["vhost"] = vhost
    initial = int(os.environ.get("RETRY_INITIAL_INTERVAL_MS", "1000"))
    multiplier = float(os.environ.get("RETRY_MULTIPLIER", "2"))
    maximum = int(os.environ.get("RETRY_MAX_INTERVAL_MS", "30000"))
    if initial <= 0 or maximum < initial or not math.isfinite(multiplier) or multiplier <= 1:
        raise ValueError("Backoff exige intervalo positivo, multiplicador maior que 1 e máximo >= inicial")
    for policy in definitions["policies"]:
        if policy["name"].startswith("notificacoes-retry-"):
            stage = int(policy["name"].rsplit("-", 1)[1])
            policy["definition"]["message-ttl"] = min(maximum, round(initial * multiplier ** (stage - 1)))
    salt = secrets.token_bytes(4)
    password_hash = base64.b64encode(
        salt + hashlib.sha256(salt + password.encode("utf-8")).digest()
    ).decode("ascii")
    definitions.update(
        users=[dict(name=user, password_hash=password_hash,
                    hashing_algorithm="rabbit_password_hashing_sha256", tags=["administrator"])],
        vhosts=[dict(name=vhost)],
        permissions=[dict(user=user, vhost=vhost, configure=".*", write=".*", read=".*")],
    )
    output = Path("/definitions/definitions.json")
    temporary = output.with_suffix(".tmp")
    temporary.write_text(json.dumps(definitions), encoding="utf-8")
    temporary.chmod(0o644)  # O processo rabbitmq precisa ler o volume gerado pelo init.
    temporary.replace(output)
    print("Definições RabbitMQ geradas")


if __name__ == "__main__":
    main()
