-- JWT ile giriş yapan analist/admin hesapları — banking müşterilerini temsil
-- eden "users" tablosuyla KARIŞTIRILMAMALI, o yüzden ayrı bir tablo.

CREATE TABLE app_users (
    id BIGSERIAL PRIMARY KEY,
    username VARCHAR(100) NOT NULL UNIQUE,
    password_hash VARCHAR(255) NOT NULL,
    role VARCHAR(20) NOT NULL CHECK (role IN ('ANALYST', 'ADMIN')),
    created_at TIMESTAMPTZ NOT NULL DEFAULT now()
);

-- Demo hesabı — kullanıcı adı: analyst, şifre: ChangeMe123! (BCrypt hash'i).
INSERT INTO app_users (username, password_hash, role)
VALUES ('analyst', '$2a$10$IrMSi9QtdKuHEDDndZG2bOROC0An5/8sQyRrbjgXp8mLqJhpAgdl.', 'ANALYST');
