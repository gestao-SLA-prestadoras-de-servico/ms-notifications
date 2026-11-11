"""Validação destrutiva SOMENTE da stack descartável ms-not-03-test.

Requer: docker compose -p ms-not-03-test up -d --build --wait
Não executa purge nem remove volumes; recusa filas que já contenham mensagens.
"""
import base64
import json
from pathlib import Path
import smtplib
import subprocess
import time
import urllib.parse
import urllib.request
import uuid

ROOT = Path(__file__).resolve().parents[1]
COMPOSE = ["docker", "compose", "-p", "ms-not-03-test"]


def compose(*args):
    return subprocess.check_output(COMPOSE + list(args), cwd=ROOT, text=True).strip()


def address(service, port, index=1):
    return compose("port", "--index", str(index), service, str(port)).splitlines()[0]


def eventually(check, timeout=120):
    deadline = time.monotonic() + timeout
    while True:
        try:
            return check()
        except (OSError, AssertionError, ValueError):
            if time.monotonic() >= deadline:
                raise
            time.sleep(2)


def main():
    config = json.loads(compose("config", "--format", "json"))
    env = config["services"]["ms-notificacoes"]["environment"]
    credentials = base64.b64encode(
        (env["RABBITMQ_USERNAME"] + ":" + env["RABBITMQ_PASSWORD"]).encode()
    ).decode()
    base = "http://" + address("rabbitmq", 15672) + "/api"
    vhost = urllib.parse.quote(env["RABBITMQ_VHOST"], safe="")

    def api(path, data=None):
        request = urllib.request.Request(base + path,
            data=None if data is None else json.dumps(data).encode(),
            headers={"Authorization": "Basic " + credentials, "Content-Type": "application/json"})
        with urllib.request.urlopen(request, timeout=10) as response:
            return json.load(response)

    def queue(name):
        return api(f"/queues/{vhost}/{name}")

    def get(name, ackmode):
        return api(f"/queues/{vhost}/{name}/get",
                   dict(count=1, ackmode=ackmode, encoding="auto"))

    def publish(channel, payload):
        response = api(f"/exchanges/{vhost}/notificacoes/publish", dict(
            routing_key=channel, payload=payload, payload_encoding="string",
            properties=dict(delivery_mode=2, content_type="application/json")))
        assert response["routed"] is True, "Mensagem não roteada"

    topology = json.loads((ROOT / "docs/topologia.json").read_text(encoding="utf-8"))
    for exchange in topology["exchanges"]:
        actual = api(f'/exchanges/{vhost}/{exchange["name"]}')
        assert actual["durable"] and actual["type"] == "direct"
    for expected in topology["queues"]:
        actual = queue(expected["name"])
        assert actual["durable"] and not actual["auto_delete"]
        assert actual["arguments"] == expected["arguments"]
        assert actual.get("messages", 0) == 0, "Use uma stack de teste com filas vazias"
    print("OK: exchanges, quatro filas duráveis, DLX por canal e TTL 604800000 ms", flush=True)

    run_id = uuid.uuid4().hex
    payloads = {}
    for channel, fixture in [("email", "resolucao"), ("interna", "atribuicao")]:
        event = json.loads((ROOT / f"docs/contratos/exemplos/validos/{fixture}.json").read_text(encoding="utf-8"))
        event["eventoId"] = f"smoke-{run_id}-{channel}"
        payloads[channel] = json.dumps(event)
        publish(channel, payloads[channel])
        messages = get("notificacoes." + channel, "ack_requeue_true")
        assert len(messages) == 1 and messages[0]["payload"] == payloads[channel]
        assert messages[0]["properties"]["delivery_mode"] == 2
    print("OK: payloads persistentes roteados e isolados por canal", flush=True)

    def verify_preserved():
        for channel, payload in payloads.items():
            messages = get("notificacoes." + channel, "ack_requeue_true")
            assert len(messages) == 1 and messages[0]["payload"] == payload

    compose("restart", "rabbitmq")
    eventually(verify_preserved)
    print("OK: mensagens preservadas após restart do RabbitMQ", flush=True)
    compose("up", "-d", "--no-deps", "--force-recreate", "--wait", "--wait-timeout", "180", "rabbitmq")
    eventually(verify_preserved)
    print("OK: mensagens preservadas após recriação do container com o mesmo volume", flush=True)

    for channel, payload in payloads.items():
        source = "notificacoes." + channel
        messages = get(source, "reject_requeue_false")
        assert len(messages) == 1

        def verify_dlq():
            messages = get(source + ".dlq", "ack_requeue_true")
            assert len(messages) == 1 and messages[0]["payload"] == payload
            assert messages[0]["properties"]["headers"]["x-death"][0]["reason"] == "rejected"

        eventually(verify_dlq)
        assert len(get(source + ".dlq", "ack_requeue_false")) == 1
        assert get(source, "ack_requeue_true") == []
    print("OK: rejeição sem requeue encaminhada à DLQ correspondente", flush=True)

    query = compose("exec", "-T", "postgres", "sh", "-c",
                    'psql -U "$POSTGRES_USER" -d "$POSTGRES_DB" -Atc "select current_database()"')
    assert query == "ms_notificacoes"
    smtp_host, smtp_port = address("mailpit", 1025).rsplit(":", 1)
    with smtplib.SMTP(smtp_host, int(smtp_port), timeout=10) as smtp:
        smtp.sendmail("smoke@example.com", ["cliente@example.com"],
                      "Subject: MS-NOT-03 smoke\r\n\r\nSMTP disponivel\r\n")
    with urllib.request.urlopen("http://" + address("mailpit", 8025) + "/api/v1/messages", timeout=10) as response:
        assert json.load(response)["total"] >= 1
    print("OK: PostgreSQL exclusivo e SMTP capturado no Mailpit", flush=True)

    compose("up", "-d", "--scale", "ms-notificacoes=4", "--wait", "--wait-timeout", "240")
    for index in range(1, 5):
        url = "http://" + address("ms-notificacoes", 8081, index) + "/actuator/health"
        with urllib.request.urlopen(url, timeout=10) as response:
            assert json.load(response)["status"] == "UP"
    print("OK: quatro instâncias saudáveis com portas distintas", flush=True)


if __name__ == "__main__":
    main()
