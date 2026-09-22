-- Rule Engine'in kendi, ML'den BAĞIMSIZ değerlendirmesi. risk_scores.action
-- SAF ML kararı olarak kalır (denetim/model-izleme için); final_action ise
-- escalate-only birleştirmenin (ML ile Rule Engine'den hangisi daha ağırsa)
-- sonucu — ikisi de bitene kadar NULL kalır.

CREATE TABLE rule_evaluations (
    id BIGSERIAL PRIMARY KEY,
    transaction_id BIGINT NOT NULL UNIQUE REFERENCES transactions(id) ON DELETE RESTRICT,
    action VARCHAR(10) NOT NULL CHECK (action IN ('APPROVE', 'REVIEW', 'BLOCK')),
    matched_rules VARCHAR(500),
    evaluated_at TIMESTAMPTZ NOT NULL DEFAULT now()
);

CREATE INDEX idx_rule_evaluations_transaction_id ON rule_evaluations(transaction_id);

ALTER TABLE risk_scores
    ADD COLUMN final_action VARCHAR(10) CHECK (final_action IN ('APPROVE', 'REVIEW', 'BLOCK'));
