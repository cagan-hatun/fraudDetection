/**
 * JWT'nin payload kısmını çözüp "sub" (kullanıcı adı) alanını okur — sadece
 * EKRANDA GÖSTERMEK için. İmza doğrulaması yapmıyoruz (zaten JWT payload'ı
 * şifreli değil, herkes base64 çözüp okuyabilir) — yetkilendirme kararı hâlâ
 * ve sadece backend'de veriliyor. Token'ı ayrıca bir "username" alanı olarak
 * saklamamak için bunu login sırasında değil, ihtiyaç anında token'dan türetiyoruz.
 */
export function decodeJwtUsername(token: string): string | null {
  try {
    const payload = token.split('.')[1]
    const json = atob(payload.replace(/-/g, '+').replace(/_/g, '/'))
    const parsed = JSON.parse(json) as { sub?: string }
    return parsed.sub ?? null
  } catch {
    return null
  }
}
