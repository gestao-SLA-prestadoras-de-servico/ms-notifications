import json
import os
import subprocess
import uuid

def environment(name):
    data = json.loads(subprocess.check_output(['docker', 'inspect', name], text=True))[0]
    assert data['Name'] == '/' + name
    return dict(item.split('=', 1) for item in data['Config']['Env'])

app = environment('ms-not-13-test-ms-notificacoes-1')
grafana = environment('ms-not-13-test-grafana-1')
env = dict(os.environ)
for key in ['RABBITMQ_USERNAME', 'RABBITMQ_PASSWORD', 'RABBITMQ_VHOST']:
    env[key] = app[key]
env['GRAFANA_USER'] = grafana['GF_SECURITY_ADMIN_USER']
env['GRAFANA_PASSWORD'] = grafana['GF_SECURITY_ADMIN_PASSWORD']
env['TEST_RUN_ID'] = uuid.uuid4().hex

script = r'''
import base64, json, os, re, time, urllib.parse, urllib.request, uuid
from urllib.error import HTTPError
run = os.environ['TEST_RUN_ID']
app = 'http://ms-notificacoes:8081'
auth = base64.b64encode((os.environ['RABBITMQ_USERNAME'] + ':' + os.environ['RABBITMQ_PASSWORD']).encode()).decode()
vhost = urllib.parse.quote(os.environ['RABBITMQ_VHOST'], safe='')

def get(url, headers=None):
    with urllib.request.urlopen(urllib.request.Request(url, headers=headers or {}), timeout=10) as response:
        return response.read().decode()

def publish(channel, event):
    payload = json.dumps(dict(routing_key=channel, payload=json.dumps(event), payload_encoding='string',
                              properties=dict(delivery_mode=2, content_type='application/json'))).encode()
    request = urllib.request.Request('http://rabbitmq:15672/api/exchanges/' + vhost + '/notificacoes/publish',
        data=payload, headers={'Authorization': 'Basic ' + auth, 'Content-Type': 'application/json'})
    with urllib.request.urlopen(request, timeout=10) as response:
        assert json.load(response)['routed']

def eventually(check, timeout=90):
    deadline = time.monotonic() + timeout
    while True:
        try:
            return check()
        except (AssertionError, OSError, ValueError, KeyError):
            if time.monotonic() >= deadline:
                raise
            time.sleep(1)

def samples(text, name, **tags):
    rows = [line for line in text.splitlines() if line.startswith(name + '{') and all(
        key + '="' + value + '"' in line for key, value in tags.items())]
    return sum(float(line.split()[-1]) for line in rows)

def prom(expr):
    result = json.loads(get('http://prometheus:9090/api/v1/query?' + urllib.parse.urlencode({'query': expr})))
    assert result['status'] == 'success'
    return result['data']['result']

grafana_auth = base64.b64encode((os.environ['GRAFANA_USER'] + ':' + os.environ['GRAFANA_PASSWORD']).encode()).decode()
dashboard = json.loads(get('http://grafana:3000/api/dashboards/uid/ms-notificacoes', {'Authorization': 'Basic ' + grafana_auth}))
assert len(dashboard['dashboard']['panels']) == 6
expressions = [target['expr'] for panel in dashboard['dashboard']['panels'] for target in panel.get('targets', [])]
assert any('rabbitmq_queue_messages_delivered_ack_total' in expr for expr in expressions)
assert any('notificacoes_smtp_circuito_habilitado' in expr for expr in expressions)
def initial():
    data = get(app + '/actuator/prometheus')
    assert samples(data, 'notificacoes_eventos_processados_total', canal='interna') >= 1
    assert samples(data, 'notificacoes_entregas_total', canal='interna', resultado='duplicado') >= 1
    assert samples(data, 'notificacoes_falhas_total', canal='interna', categoria='definitiva') >= 1
    assert samples(data, 'notificacoes_smtp_circuito_estado', estado='CLOSED') == 1
    assert run not in data and 'sub-teste' not in data
    assert json.loads(get(app + '/actuator/health/liveness'))['status'] == 'UP'
    for job in ('ms-notificacoes', 'rabbitmq'):
        values = prom('up{job="' + job + '"}')
        assert values and all(float(item['value'][1]) == 1 for item in values)
    values = prom('rabbitmq_queue_messages{queue="notificacoes.interna.dlq"}')
    assert values and float(values[0]['value'][1]) >= 1
    values = prom('rabbitmq_queue_messages_delivered_ack_total{queue="notificacoes.interna"}')
    assert values and float(values[0]['value'][1]) >= 1
    return data
'''

initial = r'''
event = dict(eventoId='obs-' + run, tipo='ATRIBUICAO', chamado=dict(id='chamado', numero='#1042'),
             titulo='teste observabilidade', destinatarios=['sub-teste'])
publish('interna', event); publish('interna', event); publish('interna', {})
eventually(initial)
print('OK: Prometheus coleta aplicacao/RabbitMQ; Grafana provisiona seis paineis; metricas e duplicatas sem IDs nos rotulos', flush=True)
'''

indisponivel = r'''
email = dict(eventoId='smtp-' + run, tipo='RESOLUCAO', chamado=dict(id='chamado', numero='#1042'),
             titulo='smtp ' + run, destinatarios=['smoke-' + run + '@example.test'])
publish('email', email)
interno = dict(eventoId='interno-' + run, tipo='ATRIBUICAO', chamado=dict(id='chamado', numero='#1042'),
               titulo='interno', destinatarios=['sub-teste'])
publish('interna', interno)

def bloqueado():
    data = get(app + '/actuator/prometheus')
    assert samples(data, 'notificacoes_smtp_circuito_estado', estado='OPEN') == 1
    assert samples(data, 'notificacoes_smtp_circuito_transicoes_total', de='CLOSED', para='OPEN') >= 1
    assert samples(data, 'notificacoes_falhas_total', canal='email', categoria='indisponibilidade_smtp') >= 1
    assert samples(data, 'notificacoes_eventos_processados_total', canal='interna') >= 2
    assert json.loads(get(app + '/actuator/health/liveness'))['status'] == 'UP'
    queue = json.loads(get('http://rabbitmq:15672/api/queues/' + vhost + '/notificacoes.email', {'Authorization': 'Basic ' + auth}))
    assert queue['consumers'] == 0 and queue['messages'] >= 1
    values = prom('notificacoes_smtp_circuito_estado{estado="OPEN"}')
    assert values and float(values[0]['value'][1]) == 1
eventually(bloqueado)
print('OK: SMTP indisponivel aparece no circuito e nas falhas; liveness UP e canal interno operacional', flush=True)
'''

recuperado = r'''
def pronto():
    data = get(app + '/actuator/prometheus')
    assert samples(data, 'notificacoes_smtp_circuito_estado', estado='CLOSED') == 1
    assert samples(data, 'notificacoes_eventos_processados_total', canal='email') >= 1
    queue = json.loads(get('http://rabbitmq:15672/api/queues/' + vhost + '/notificacoes.email', {'Authorization': 'Basic ' + auth}))
    assert queue['consumers'] > 0 and queue['messages'] == 0
    query = urllib.parse.urlencode({'query': 'subject:' + run})
    assert json.loads(get('http://mailpit:8025/api/v1/search?' + query))['messages_count'] == 1
    values = prom('notificacoes_smtp_circuito_estado{estado="CLOSED"}')
    assert values and float(values[0]['value'][1]) == 1
eventually(pronto)
print('OK: SMTP recuperado; circuito fechado, fila drenada e mensagem capturada', flush=True)
'''

def executar(trecho):
    subprocess.run(['docker', 'run', '--rm', '-i', '--network', 'ms-not-13-test_default',
                    '--env', 'RABBITMQ_USERNAME', '--env', 'RABBITMQ_PASSWORD', '--env', 'RABBITMQ_VHOST',
                    '--env', 'GRAFANA_USER', '--env', 'GRAFANA_PASSWORD', '--env', 'TEST_RUN_ID',
                    'python:3.13-alpine', 'python', '-'], input=script + trecho, encoding='utf-8', env=env, check=True)

executar(initial)
before = json.loads(subprocess.check_output(['docker', 'inspect', 'ms-not-13-test-ms-notificacoes-1'], text=True))[0]
try:
    subprocess.run(['docker', 'stop', 'ms-not-13-test-mailpit-1'], check=True, stdout=subprocess.DEVNULL)
    executar(indisponivel)
finally:
    subprocess.run(['docker', 'start', 'ms-not-13-test-mailpit-1'], check=True, stdout=subprocess.DEVNULL)
executar(recuperado)
after = json.loads(subprocess.check_output(['docker', 'inspect', 'ms-not-13-test-ms-notificacoes-1'], text=True))[0]
assert before['Id'] == after['Id'] and before['RestartCount'] == after['RestartCount']
logs = subprocess.check_output(['docker', 'logs', 'ms-not-13-test-ms-notificacoes-1'], stderr=subprocess.STDOUT, text=True, encoding='utf-8')
import hashlib
assert hashlib.sha256(('smtp-' + env['TEST_RUN_ID']).encode()).hexdigest() in logs
assert 'smoke-' + env['TEST_RUN_ID'] + '@example.test' not in logs
print('OK: processo nao reiniciado; evento correlacionado por hash sem expor destinatario')
