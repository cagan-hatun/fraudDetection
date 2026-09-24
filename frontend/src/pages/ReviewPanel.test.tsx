import { describe, it, expect, vi, beforeEach } from 'vitest'
import { render, screen } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { ReviewPanel } from './ReviewPanel'
import { useSubmitReview } from '../api/queries'
import type { components } from '../api/schema'

type AnalystReviewSummary = components['schemas']['AnalystReviewSummary']
type UseSubmitReviewResult = ReturnType<typeof useSubmitReview>

vi.mock('../api/queries', () => ({
  useSubmitReview: vi.fn(),
}))

const mockUseSubmitReview = vi.mocked(useSubmitReview)

function mockMutation(overrides: Partial<UseSubmitReviewResult> = {}): UseSubmitReviewResult {
  return {
    mutate: vi.fn(),
    isPending: false,
    isError: false,
    variables: undefined,
    ...overrides,
  } as UseSubmitReviewResult
}

describe('ReviewPanel', () => {
  beforeEach(() => {
    mockUseSubmitReview.mockReset()
  })

  it('mevcut bir karar yoksa onay/red formunu gösterir', () => {
    mockUseSubmitReview.mockReturnValue(mockMutation())

    render(<ReviewPanel transactionId={1} existingReview={null} />)

    expect(screen.getByText('Bu işlem inceleme bekliyor — bir karar ver')).toBeInTheDocument()
    expect(screen.getByRole('button', { name: 'Onayla' })).toBeInTheDocument()
    expect(screen.getByRole('button', { name: 'Reddet' })).toBeInTheDocument()
  })

  it("Onayla butonuna basınca APPROVED kararını (girilen notla birlikte) gönderir", async () => {
    const mutate = vi.fn()
    mockUseSubmitReview.mockReturnValue(mockMutation({ mutate }))
    const user = userEvent.setup()

    render(<ReviewPanel transactionId={1} existingReview={null} />)
    await user.type(screen.getByLabelText('Not (opsiyonel)'), 'şüpheli lokasyon')
    await user.click(screen.getByRole('button', { name: 'Onayla' }))

    expect(mutate).toHaveBeenCalledWith(
      { decision: 'APPROVED', note: 'şüpheli lokasyon' },
      expect.objectContaining({ onSuccess: expect.any(Function) }),
    )
  })

  it("Reddet butonuna basınca REJECTED kararını (not boşsa undefined ile) gönderir", async () => {
    const mutate = vi.fn()
    mockUseSubmitReview.mockReturnValue(mockMutation({ mutate }))
    const user = userEvent.setup()

    render(<ReviewPanel transactionId={1} existingReview={null} />)
    await user.click(screen.getByRole('button', { name: 'Reddet' }))

    expect(mutate).toHaveBeenCalledWith(
      { decision: 'REJECTED', note: undefined },
      expect.objectContaining({ onSuccess: expect.any(Function) }),
    )
  })

  it('gönderim başarısız olursa hata uyarısı gösterir', () => {
    mockUseSubmitReview.mockReturnValue(mockMutation({ isError: true }))

    render(<ReviewPanel transactionId={1} existingReview={null} />)

    expect(screen.getByText('Karar kaydedilemedi.')).toBeInTheDocument()
  })

  it('mevcut bir karar varsa formu değil, kararı (durum + not + kim/ne zaman) gösterir', () => {
    mockUseSubmitReview.mockReturnValue(mockMutation())
    const existingReview: AnalystReviewSummary = {
      decision: 'APPROVED',
      note: 'gerçek işlem, onaylandı',
      reviewedBy: 'analyst',
      reviewedAt: '2026-01-15T10:30:00Z',
    }

    render(<ReviewPanel transactionId={1} existingReview={existingReview} />)

    expect(screen.getByText('ONAYLANDI')).toBeInTheDocument()
    expect(screen.getByText('"gerçek işlem, onaylandı"')).toBeInTheDocument()
    expect(screen.getByText(/analyst/)).toBeInTheDocument()
    expect(screen.queryByRole('button', { name: 'Onayla' })).not.toBeInTheDocument()
  })

  it("\"Kararı değiştir\"e basınca formu tekrar gösterir", async () => {
    mockUseSubmitReview.mockReturnValue(mockMutation())
    const existingReview: AnalystReviewSummary = {
      decision: 'REJECTED',
      note: undefined,
      reviewedBy: 'analyst',
      reviewedAt: '2026-01-15T10:30:00Z',
    }
    const user = userEvent.setup()

    render(<ReviewPanel transactionId={1} existingReview={existingReview} />)
    await user.click(screen.getByRole('button', { name: 'Kararı değiştir' }))

    expect(screen.getByText('Bu işlem inceleme bekliyor — bir karar ver')).toBeInTheDocument()
  })
})
