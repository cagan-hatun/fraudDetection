import { describe, it, expect } from 'vitest'
import { actionToChipColor, formatCurrency, formatFraudProbability, formatShapValue } from './format'

describe('formatFraudProbability', () => {
  it('gerçek bir işlemden gelen olasılığı Türkçe yüzde formatına çevirir', () => {
    // Bu, caught_fraud senaryosunu canlı test ederken backend'den gelen
    // gerçek değer (bkz. sohbet geçmişi) — uydurma bir sayı değil.
    expect(formatFraudProbability(0.99995)).toBe('%99,995')
  })

  it('düşük bir olasılığı da doğru formatlar', () => {
    expect(formatFraudProbability(0.02042)).toBe('%2,042')
  })
})

describe('actionToChipColor', () => {
  it('APPROVE, REVIEW, BLOCK aksiyonlarını MUI success/warning/error paletine eşler', () => {
    expect(actionToChipColor('APPROVE')).toBe('success')
    expect(actionToChipColor('REVIEW')).toBe('warning')
    expect(actionToChipColor('BLOCK')).toBe('error')
  })
})

describe('formatCurrency', () => {
  it('USD tutarını Türkçe para birimi formatına çevirir', () => {
    // Intl'in tam olarak hangi boşluk/sembol sırasını kullandığı ortama göre
    // ufak farklar gösterebilir — bu yüzden birebir string yerine önemli
    // parçaların (rakamlar, para birimi kodu) varlığını kontrol ediyoruz.
    const result = formatCurrency(300, 'USD')
    expect(result).toContain('300')
    expect(result).toMatch(/\$|USD/)
  })
})

describe('formatShapValue', () => {
  it('pozitif değerlere + işareti ekler', () => {
    expect(formatShapValue(1.86)).toBe('+1,86')
  })

  it('negatif değerlere - işareti ekler', () => {
    expect(formatShapValue(-0.24)).toBe('-0,24')
  })
})
