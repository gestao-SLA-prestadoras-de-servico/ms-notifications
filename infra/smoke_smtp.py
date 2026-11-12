"""Interrompe e recupera o SMTP SOMENTE na stack isolada ms-not-09-test.

Requer a aplicacao atualizada e saudavel nessa stack, com consumidores/circuito
habilitados e filas notificacoes.email/notificacoes.email.dlq vazias.
Nao apaga mensagens ou volumes; preserva os dados gerados para inspecao.
"""
import base64
import json
from pathlib import Path
import subprocess
import time
import urllib.parse
import urllib.request
import uuid

ROOT = Path(__file__).resolve().parents[1]
COMMAND = ['docker', 'compose', '-p', 'ms-not-09-test']


def compose(*args, stdin=None):
    return subprocess.run(COMMAND + list(args), cwd=ROOT, input=stdin, text=True,
                          stdout=subprocess.PIPE, stderr=subprocess.PIPE, check=True).stdout.strip()


def eventually(check, timeout=90):
    deadline = time.monotonic() + timeout
    while True:
        try:
            return check()
        except (AssertionError, OSError, ValueError):
            if time.monotonic() > deadline:
                raise
            time.sleep(1)


config = json.loads(compose('config', '--format', 'json'))
assert config['name'] == 'ms-not-09-test'
env = config['services']['ms-notificacoes']['environment']
for flag in ('CONSUMIDOR_EMAIL_ENABLED', 'CONSUMIDOR_INTERNO_ENABLED', 'SMTP_CIRCUIT_BREAKER_ENABLED'):
    assert str(env[flag]).lower() == 'true', 'Habilite consumidores e circuito na stack de teste'
assert str(env['SMTP_CIRCUIT_WINDOW_SIZE']) == '1' and str(env['SMTP_CIRCUIT_MIN_CALLS']) == '1'
assert env['NOTIFICACOES_FILA_EMAIL'] == 'notificacoes.email'
assert env['NOTIFICACOES_FILA_INTERNA'] == 'notificacoes.interna'
credentials = base64.b64encode((env['RABBITMQ_USERNAME'] + ':' + env['RABBITMQ_PASSWORD']).encode()).decode()
base = 'http://' + compose('port', 'rabbitmq', '15672') + '/api'
vhost = urllib.parse.quote(env['RABBITMQ_VHOST'], safe='')
run = uuid.uuid4().hex


def api(path, data=None):
    request = urllib.request.Request(base + path, data=None if data is None else json.dumps(data).encode(),
                                    headers={'Authorization': 'Basic ' + credentials, 'Content-Type': 'application/json'})
    with urllib.request.urlopen(request, timeout=10) as response:
        return json.load(response)


def queue(channel):
    return api('/queues/' + vhost + '/notificacoes.' + channel)


def publish(channel, index, attempt):
    fixture = 'resolucao' if channel == 'email' else 'atribuicao'
    event = json.loads((ROOT / ('docs/contratos/exemplos/validos/' + fixture + '.json')).read_text(encoding='utf-8'))
    event['eventoId'] = 'runtime-' + run + '-' + str(index)
    event['titulo'] = 'Circuito ' + run
    event['destinatarios'] = ['runtime-' + run + '@example.com' if channel == 'email' else 'gestor-' + run]
    result = api('/exchanges/' + vhost + '/notificacoes/publish', dict(routing_key=channel,
        payload=json.dumps(event), payload_encoding='string', properties=dict(delivery_mode=2,
        content_type='application/json', headers={'x-notificacoes-tentativa': attempt})))
    assert result['routed']


def count(canal):
    sql = "SELECT count(*) FROM evento_processado WHERE evento_id LIKE 'runtime-" + run + "-%' AND canal='" + canal + "';"
    return int(compose('exec', '-T', 'postgres', 'sh', '-c',
                      'psql -U "$POSTGRES_USER" -d "$POSTGRES_DB" -At', stdin=sql))


assert queue('email')['messages'] == 0
assert queue('email.dlq')['messages'] == 0
try:
    compose('stop', 'mailpit')
    publish('email', 0, 4)

    def paused():
        q = queue('email')
        assert q['consumers'] == 0 and q['messages_ready'] == 1
        logs = compose('logs', '--no-color', 'ms-notificacoes')
        assert 'estado=OPEN' in logs and 'tentativas preservadas' in logs

    eventually(paused)
    for i in range(1, 4):
        publish('email', i, 3)
    publish('interna', 4, 1)

    def isolated():
        assert count('INTERNA') == 1
        assert count('EMAIL') == 0
        q = queue('email')
        assert q['consumers'] == 0 and q['messages_ready'] == 4
        assert queue('email.dlq')['messages'] == 0

    eventually(isolated)
    messages = api('/queues/' + vhost + '/notificacoes.email/get',
                   dict(count=4, ackmode='ack_requeue_true', encoding='auto'))
    assert len(messages) == 4
    assert sorted(m['properties']['headers']['x-notificacoes-tentativa'] for m in messages) == [3, 3, 3, 4]
    print('OK: Mailpit stopped, circuit OPEN, consumer paused, headers 3/3/3/4 preserved, internal committed, DLQ empty', flush=True)
    compose('start', 'mailpit')

    def recovered():
        assert count('EMAIL') == 4
        assert queue('email')['messages'] == 0
        assert queue('email')['consumers'] > 0
        assert queue('email.dlq')['messages'] == 0
        logs = compose('logs', '--no-color', 'ms-notificacoes')
        assert 'estado=HALF_OPEN' in logs and 'estado=CLOSED' in logs
        url = 'http://' + compose('port', 'mailpit', '8025') + '/api/v1/search?query=subject:' + run
        with urllib.request.urlopen(url, timeout=10) as response:
            assert json.load(response)['messages_count'] == 4

    eventually(recovered)
    print('OK: Mailpit restarted, scheduled probe HALF_OPEN -> CLOSED, queue drained, four emails captured, four success markers', flush=True)
finally:
    compose('start', 'mailpit')
