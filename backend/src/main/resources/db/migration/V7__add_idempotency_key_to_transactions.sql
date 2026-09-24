-- POST /api/transactions'a gönderilen bir Idempotency-Key header'ını
-- saklıyor — aynı key ile ikinci bir istek geldiğinde yeni bir transaction
-- YARATILMAZ, var olan döner. NULL bırakılabilir (header opsiyonel; demo
-- replay akışı hiç kullanmıyor).
ALTER TABLE transactions ADD COLUMN idempotency_key VARCHAR(100) UNIQUE;
