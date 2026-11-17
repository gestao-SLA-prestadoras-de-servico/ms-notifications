-- Alinha os tipos ao contrato do app-principal (backend/contratos/notificacoes).
-- A reatribuição chega como ATRIBUICAO para o técnico novo e REMOCAO para o anterior.
ALTER TABLE notificacao_interna DROP CONSTRAINT ck_notificacao_tipo_interno;

UPDATE notificacao_interna SET tipo = 'VIOLACAO' WHERE tipo = 'VIOLACAO_SLA';
UPDATE notificacao_interna SET tipo = 'ATRIBUICAO' WHERE tipo = 'REATRIBUICAO';

ALTER TABLE notificacao_interna ADD CONSTRAINT ck_notificacao_tipo_interno CHECK (
    tipo IN ('ATRIBUICAO', 'REMOCAO', 'REABERTURA', 'CONCLUSAO', 'VIOLACAO')
);

-- Relógio de SLA violado; só em VIOLACAO. Linhas antigas ficam nulas.
ALTER TABLE notificacao_interna ADD COLUMN relogio VARCHAR(20)
    CONSTRAINT ck_notificacao_relogio CHECK (
        relogio IS NULL OR (tipo = 'VIOLACAO' AND relogio IN ('RESPOSTA', 'ATENDIMENTO_CAMPO', 'RESOLUCAO'))
    );
