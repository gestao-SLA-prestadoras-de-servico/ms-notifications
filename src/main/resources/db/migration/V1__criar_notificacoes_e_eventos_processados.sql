CREATE TABLE notificacao_interna (
    id UUID PRIMARY KEY,
    destinatario_sub TEXT NOT NULL CHECK (btrim(destinatario_sub) <> ''),
    evento_id TEXT NOT NULL CHECK (btrim(evento_id) <> ''),
    tipo VARCHAR(40) NOT NULL,
    chamado_id TEXT NOT NULL CHECK (btrim(chamado_id) <> ''),
    numero_chamado TEXT NOT NULL CHECK (btrim(numero_chamado) <> ''),
    titulo TEXT NOT NULL CHECK (btrim(titulo) <> ''),
    criado_em TIMESTAMP WITH TIME ZONE NOT NULL,
    lida_em TIMESTAMP WITH TIME ZONE,
    CONSTRAINT uk_notificacao_evento_destinatario UNIQUE (evento_id, destinatario_sub),
    CONSTRAINT ck_notificacao_tipo_interno CHECK (
        tipo IN ('ATRIBUICAO', 'REATRIBUICAO', 'REMOCAO', 'REABERTURA', 'CONCLUSAO', 'VIOLACAO_SLA')
    ),
    CONSTRAINT ck_notificacao_data_leitura CHECK (lida_em IS NULL OR lida_em >= criado_em)
);

CREATE INDEX idx_notificacao_destinatario_criacao
    ON notificacao_interna (destinatario_sub, criado_em DESC, id DESC);

CREATE INDEX idx_notificacao_nao_lida
    ON notificacao_interna (destinatario_sub) WHERE lida_em IS NULL;

CREATE INDEX idx_notificacao_criacao ON notificacao_interna (criado_em);

-- Independente das notificações: o expurgo não deve apagar a memória de processamento.
-- A inserção representa sucesso do evento inteiro no canal, não uma reserva de execução.
CREATE TABLE evento_processado (
    id UUID PRIMARY KEY,
    evento_id TEXT NOT NULL CHECK (btrim(evento_id) <> ''),
    canal VARCHAR(10) NOT NULL,
    processado_em TIMESTAMP WITH TIME ZONE NOT NULL,
    CONSTRAINT uk_evento_processado_evento_canal UNIQUE (evento_id, canal),
    CONSTRAINT ck_evento_processado_canal CHECK (canal IN ('EMAIL', 'INTERNA'))
);
