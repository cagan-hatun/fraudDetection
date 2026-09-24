-- DLQ redrive gibi altyapı kurtarma işlemleri için ADMIN rolü artık gerçek
-- anlamda kullanılıyor (bkz. SecurityConfig'teki /api/admin/** kısıtlaması) —
-- bu yüzden demo bir admin hesabı gerekiyor. Kullanıcı adı: admin, şifre:
-- ChangeMeAdmin123! (BCrypt hash'i).
INSERT INTO app_users (username, password_hash, role)
VALUES ('admin', '$2a$10$beDeAhb7CSt14wldGl65xeeVU8sq1FEKYl6J2x57I328MaIQ.y.eO', 'ADMIN');
