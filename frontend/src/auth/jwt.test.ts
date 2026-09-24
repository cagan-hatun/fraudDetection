import { describe, it, expect } from 'vitest'
import { decodeJwtUsername } from './jwt'

/** Gerçek bir JWT değil, sadece payload kısmını doğru şekilde kodlanmış üretir. */
function fakeJwt(payload: object): string {
  const base64 = btoa(JSON.stringify(payload)).replace(/\+/g, '-').replace(/\//g, '_')
  return `header.${base64}.signature`
}

describe('decodeJwtUsername', () => {
  it('gerçek backend token formatına uygun bir JWT içindeki sub alanını okur', () => {
    // Backend'in gerçek demo hesabı — bkz. JwtService.generateToken
    const token = fakeJwt({ sub: 'analyst', role: 'ANALYST' })
    expect(decodeJwtUsername(token)).toBe('analyst')
  })

  it('sub alanı yoksa null döner', () => {
    const token = fakeJwt({ role: 'ANALYST' })
    expect(decodeJwtUsername(token)).toBeNull()
  })

  it('bozuk bir token için hata fırlatmak yerine null döner', () => {
    expect(decodeJwtUsername('bozuk-token')).toBeNull()
  })
})
