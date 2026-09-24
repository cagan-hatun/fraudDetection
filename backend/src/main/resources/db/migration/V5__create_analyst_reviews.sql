-- REVIEW durumundaki bir islemi analistin onaylamasi/reddetmesi + not eklemesi
-- icin - risk_scores.final_action'i DOGRUDAN DEGISTIRMIYORUZ, o sistemin
-- kendi (ML+Rule Engine) kararini temsil ediyor ve boyle kalmali (denetim/
-- model-izleme icin). Analist karari ayri, ek bir bilgi katmani.
CREATE TABLE analyst_reviews (
    id BIGSERIAL PRIMARY KEY,
    transaction_id BIGINT NOT NULL UNIQUE REFERENCES transactions(id),
    decision VARCHAR(10) NOT NULL CHECK (decision IN ('APPROVED', 'REJECTED')),
    note TEXT,
    reviewed_by VARCHAR(50) NOT NULL,
    reviewed_at TIMESTAMPTZ NOT NULL DEFAULT now()
);
