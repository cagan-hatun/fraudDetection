/**
 * JWT'yi tutmak için ince bir katman — şu an localStorage kullanıyor. İleride
 * (örn. XSS riskine karşı) farklı bir saklama stratejisine geçersek, sadece bu
 * dosya değişir; token'ı okuyan/yazan hiçbir yer localStorage'ı bilmiyor.
 */
const STORAGE_KEY = 'fraud-detect-token'

export function getToken(): string | null {
  return localStorage.getItem(STORAGE_KEY)
}

export function setToken(token: string): void {
  localStorage.setItem(STORAGE_KEY, token)
}

export function clearToken(): void {
  localStorage.removeItem(STORAGE_KEY)
}
