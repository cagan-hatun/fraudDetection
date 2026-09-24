import { useState } from 'react'
import { useParams, Link as RouterLink } from 'react-router-dom'
import Box from '@mui/material/Box'
import Typography from '@mui/material/Typography'
import Chip from '@mui/material/Chip'
import Stack from '@mui/material/Stack'
import Card from '@mui/material/Card'
import Tabs from '@mui/material/Tabs'
import Tab from '@mui/material/Tab'
import Divider from '@mui/material/Divider'
import CircularProgress from '@mui/material/CircularProgress'
import Link from '@mui/material/Link'
import ArrowBackIcon from '@mui/icons-material/ArrowBack'
import { useTransactionDetail } from '../api/queries'
import { actionToChipColor, formatCurrency, formatDateTime, formatFraudProbability } from '../utils/format'
import { ShapChart } from './ShapChart'
import { ReviewPanel } from './ReviewPanel'

type TabKey = 'genel' | 'karar' | 'shap'

export function TransactionDetailPage() {
  const { id } = useParams<{ id: string }>()
  const transactionId = Number(id)
  const [tab, setTab] = useState<TabKey>('genel')

  const { data, isLoading, isError } = useTransactionDetail(transactionId)

  if (isLoading) {
    return (
      <Box sx={{ p: 4, display: 'flex', justifyContent: 'center' }}>
        <CircularProgress />
      </Box>
    )
  }

  if (isError || !data) {
    return (
      <Box sx={{ p: 4 }}>
        <Typography color="error">İşlem bulunamadı.</Typography>
      </Box>
    )
  }

  const isScored = data.status === 'SCORED'

  return (
    <Box sx={{ p: 4, display: 'flex', flexDirection: 'column', gap: 3 }}>
      <Link component={RouterLink} to="/" sx={{ display: 'inline-flex', alignItems: 'center', gap: 0.75, fontSize: 13 }}>
        <ArrowBackIcon sx={{ fontSize: 15 }} />
        Dashboard'a dön
      </Link>

      <Stack direction="row" sx={{ justifyContent: 'space-between', alignItems: 'center' }}>
        <Stack direction="row" spacing={2} sx={{ alignItems: 'center' }}>
          <Typography variant="h5">İşlem #{data.transactionId}</Typography>
          {isScored && data.finalAction && (
            <Chip
              label={data.finalAction}
              color={actionToChipColor(data.finalAction)}
              sx={{ fontFamily: 'IBM Plex Mono, monospace', fontWeight: 600 }}
            />
          )}
        </Stack>
        <Typography variant="mono" sx={{ fontSize: 18, color: 'text.secondary' }}>
          {formatCurrency(data.amount!, data.currency!)}
        </Typography>
      </Stack>

      <Stack direction="row" spacing={3} sx={{ alignItems: 'flex-start' }}>
        {/* sol: sekmeler */}
        <Box sx={{ flex: 1, minWidth: 0 }}>
          <Tabs value={tab} onChange={(_, v) => setTab(v)} sx={{ mb: 2, minHeight: 36 }}>
            <Tab label="Genel Bilgi" value="genel" sx={{ minHeight: 36 }} />
            <Tab label="Karar Detayı" value="karar" disabled={!isScored} sx={{ minHeight: 36 }} />
            <Tab label="SHAP Analizi" value="shap" disabled={!isScored} sx={{ minHeight: 36 }} />
          </Tabs>

          {tab === 'genel' && (
            <Card variant="outlined" sx={{ p: 3, display: 'grid', gridTemplateColumns: '1fr 1fr', gap: 2.5 }}>
              <Field label="Merchant" value={data.merchantName} />
              <Field label="Kategori" value={data.merchantCategory} />
              <Field label="Tutar" value={formatCurrency(data.amount!, data.currency!)} mono />
              <Field label="İşlem zamanı" value={formatDateTime(data.transactionTime!)} mono />
              <Field label="Konum" value={`${data.locationCity}, ${data.locationCountry}`} />
              <Field label="Kullanıcı referansı" value={data.userExternalRef} mono />
              <Box sx={{ gridColumn: '1 / -1' }}>
                <Field label="Cihaz parmak izi" value={data.deviceFingerprint} mono muted />
              </Box>
            </Card>
          )}

          {tab === 'karar' && isScored && (
            <Stack spacing={1.5}>
              <DecisionCard
                title={`ML Modeli — ${data.modelVersion}`}
                description={
                  <>
                    Fraud olasılığını{' '}
                    <Typography component="span" variant="mono" sx={{ color: 'error.main' }}>
                      {formatFraudProbability(data.fraudProbability!)}
                    </Typography>{' '}
                    olarak hesapladı.
                  </>
                }
                action={data.mlAction!}
              />
              <DecisionCard
                title="Rule Engine — ML'den bağımsız değerlendirme"
                description={
                  data.matchedRules
                    ? `Eşleşen kurallar: ${data.matchedRules}`
                    : 'Hiçbir kural eşleşmedi.'
                }
                action={data.ruleAction!}
              />
              <DecisionCard
                title="Nihai karar — escalate-only birleşim"
                description={
                  data.mlAction === data.finalAction
                    ? "ML kararı doğrudan uygulandı; Rule Engine bir yükseltme önermedi."
                    : 'Rule Engine, ML kararını daha sert bir aksiyona yükseltti.'
                }
                action={data.finalAction!}
              />

              {data.finalAction === 'REVIEW' && (
                <ReviewPanel transactionId={transactionId} existingReview={data.analystReview} />
              )}
            </Stack>
          )}

          {tab === 'shap' && isScored && (
            <Card variant="outlined" sx={{ p: 3 }}>
              <ShapChart
                contributions={data.shapContributions ?? []}
                baseValue={data.baseValue ?? 0}
                fraudProbability={data.fraudProbability ?? 0}
              />
            </Card>
          )}

          {!isScored && tab !== 'genel' && (
            <Card variant="outlined" sx={{ p: 3 }}>
              <Typography color="text.secondary">
                İşlem henüz skorlanmadı — bu sekme skorlama tamamlanınca dolacak.
              </Typography>
            </Card>
          )}
        </Box>

        {/* sağ: özet */}
        <Card variant="outlined" sx={{ width: 280, flexShrink: 0, p: 2.5 }}>
          {isScored ? (
            <>
              <Typography variant="body2" color="text.secondary">
                Fraud olasılığı
              </Typography>
              <Typography variant="mono" sx={{ fontSize: 30, fontWeight: 500, color: 'error.main' }}>
                {formatFraudProbability(data.fraudProbability!)}
              </Typography>

              <Divider sx={{ my: 2 }} />

              <Stack spacing={1.25}>
                <SummaryRow label="Nihai karar" action={data.finalAction!} />
                <SummaryRow label="ML kararı" action={data.mlAction!} />
                <SummaryRow label="Rule Engine" action={data.ruleAction!} />
              </Stack>

              {data.analystReview && (
                <>
                  <Divider sx={{ my: 2 }} />
                  <Stack direction="row" sx={{ justifyContent: 'space-between', alignItems: 'center' }}>
                    <Typography variant="body2" color="text.secondary">
                      Analist kararı
                    </Typography>
                    <Chip
                      label={data.analystReview.decision === 'APPROVED' ? 'ONAYLANDI' : 'REDDEDİLDİ'}
                      color={data.analystReview.decision === 'APPROVED' ? 'success' : 'error'}
                      size="small"
                      sx={{ fontFamily: 'IBM Plex Mono, monospace', fontWeight: 600 }}
                    />
                  </Stack>
                </>
              )}

              <Divider sx={{ my: 2 }} />

              <Typography variant="body2" color="text.secondary">
                Model sürümü
              </Typography>
              <Typography variant="mono" sx={{ fontSize: 12.5 }}>
                {data.modelVersion}
              </Typography>
            </>
          ) : (
            <Stack spacing={1.5} sx={{ alignItems: 'center', py: 2 }}>
              <CircularProgress size={22} />
              <Typography variant="body2" color="text.secondary" sx={{ textAlign: 'center' }}>
                Kafka üzerinden işleniyor…
              </Typography>
            </Stack>
          )}
        </Card>
      </Stack>
    </Box>
  )
}

function Field({ label, value, mono, muted }: { label: string; value?: string | null; mono?: boolean; muted?: boolean }) {
  return (
    <Box>
      <Typography variant="caption" sx={{ textTransform: 'uppercase', letterSpacing: 0.4, color: 'text.secondary' }}>
        {label}
      </Typography>
      <Typography
        variant={mono ? 'mono' : 'body2'}
        sx={{ color: muted ? 'text.secondary' : 'text.primary', fontSize: mono ? 13 : 14 }}
      >
        {value ?? '—'}
      </Typography>
    </Box>
  )
}

function DecisionCard({
  title,
  description,
  action,
}: {
  title: string
  description: React.ReactNode
  action: 'APPROVE' | 'REVIEW' | 'BLOCK'
}) {
  return (
    <Card variant="outlined" sx={{ p: 2.5, display: 'flex', alignItems: 'center', gap: 2 }}>
      <Box sx={{ flex: 1 }}>
        <Typography variant="body2" color="text.secondary" sx={{ mb: 0.5 }}>
          {title}
        </Typography>
        <Typography variant="body2">{description}</Typography>
      </Box>
      <Chip label={action} color={actionToChipColor(action)} sx={{ fontFamily: 'IBM Plex Mono, monospace', fontWeight: 600 }} />
    </Card>
  )
}

function SummaryRow({ label, action }: { label: string; action: 'APPROVE' | 'REVIEW' | 'BLOCK' }) {
  return (
    <Stack direction="row" sx={{ justifyContent: 'space-between', alignItems: 'center' }}>
      <Typography variant="body2" color="text.secondary">
        {label}
      </Typography>
      <Chip
        label={action}
        color={actionToChipColor(action)}
        size="small"
        sx={{ fontFamily: 'IBM Plex Mono, monospace', fontWeight: 600 }}
      />
    </Stack>
  )
}
