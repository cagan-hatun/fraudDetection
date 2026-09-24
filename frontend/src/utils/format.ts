const percentFormatter = new Intl.NumberFormat('tr-TR', {
  style: 'percent',
  minimumFractionDigits: 2,
  maximumFractionDigits: 3,
})

export function formatFraudProbability(value: number): string {
  return percentFormatter.format(value)
}

export type RiskAction = 'APPROVE' | 'REVIEW' | 'BLOCK'

// MUI'nin success/warning/error paletlerine eşliyor — theme/index.ts'te bu
// renkler zaten APPROVE/REVIEW/BLOCK olarak tanımlıydı.
export function actionToChipColor(action: RiskAction): 'success' | 'warning' | 'error' {
  if (action === 'APPROVE') return 'success'
  if (action === 'REVIEW') return 'warning'
  return 'error'
}

export function formatCurrency(amount: number, currency: string): string {
  return new Intl.NumberFormat('tr-TR', { style: 'currency', currency }).format(amount)
}

export function formatDateTime(isoString: string): string {
  return new Intl.DateTimeFormat('tr-TR', {
    day: '2-digit',
    month: '2-digit',
    year: 'numeric',
    hour: '2-digit',
    minute: '2-digit',
  }).format(new Date(isoString))
}

export function formatShapValue(value: number): string {
  const formatted = new Intl.NumberFormat('tr-TR', {
    minimumFractionDigits: 2,
    maximumFractionDigits: 2,
    signDisplay: 'always',
  }).format(value)
  return formatted
}
