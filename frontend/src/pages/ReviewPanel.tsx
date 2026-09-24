import { useState } from 'react'
import Box from '@mui/material/Box'
import Card from '@mui/material/Card'
import Typography from '@mui/material/Typography'
import Stack from '@mui/material/Stack'
import Chip from '@mui/material/Chip'
import Button from '@mui/material/Button'
import TextField from '@mui/material/TextField'
import Alert from '@mui/material/Alert'
import CheckCircleOutlinedIcon from '@mui/icons-material/CheckCircleOutlined'
import CancelOutlinedIcon from '@mui/icons-material/CancelOutlined'
import { useSubmitReview } from '../api/queries'
import { formatDateTime } from '../utils/format'
import type { components } from '../api/schema'

type AnalystReviewSummary = components['schemas']['AnalystReviewSummary']

export function ReviewPanel({
  transactionId,
  existingReview,
}: {
  transactionId: number
  existingReview: AnalystReviewSummary | null | undefined
}) {
  const [note, setNote] = useState('')
  const [showForm, setShowForm] = useState(!existingReview)
  const submitReview = useSubmitReview(transactionId)

  function handleSubmit(decision: 'APPROVED' | 'REJECTED') {
    submitReview.mutate(
      { decision, note: note.trim() || undefined },
      { onSuccess: () => setShowForm(false) },
    )
  }

  const review = showForm ? undefined : existingReview

  if (review) {
    return (
      <Card variant="outlined" sx={{ p: 2.5 }}>
        <Stack direction="row" sx={{ justifyContent: 'space-between', alignItems: 'center', mb: review.note ? 1.5 : 0 }}>
          <Stack direction="row" spacing={1.5} sx={{ alignItems: 'center' }}>
            <Typography variant="body2" color="text.secondary">
              Analist kararı
            </Typography>
            <Chip
              label={review.decision === 'APPROVED' ? 'ONAYLANDI' : 'REDDEDİLDİ'}
              color={review.decision === 'APPROVED' ? 'success' : 'error'}
              size="small"
              sx={{ fontFamily: 'IBM Plex Mono, monospace', fontWeight: 600 }}
            />
          </Stack>
          <Button size="small" onClick={() => setShowForm(true)}>
            Kararı değiştir
          </Button>
        </Stack>
        {review.note && (
          <Typography variant="body2" sx={{ mb: 1.5 }}>
            "{review.note}"
          </Typography>
        )}
        <Typography variant="caption" color="text.secondary">
          {review.reviewedBy} · {formatDateTime(review.reviewedAt!)}
        </Typography>
      </Card>
    )
  }

  return (
    <Card variant="outlined" sx={{ p: 2.5 }}>
      <Typography variant="body2" sx={{ mb: 1.5, fontWeight: 500 }}>
        Bu işlem inceleme bekliyor — bir karar ver
      </Typography>

      <TextField
        label="Not (opsiyonel)"
        multiline
        minRows={2}
        fullWidth
        value={note}
        onChange={(e) => setNote(e.target.value)}
        sx={{ mb: 2 }}
      />

      {submitReview.isError && <Alert severity="error" sx={{ mb: 2 }}>Karar kaydedilemedi.</Alert>}

      <Stack direction="row" spacing={1.5}>
        <Button
          variant="contained"
          color="success"
          startIcon={<CheckCircleOutlinedIcon />}
          loading={submitReview.isPending && submitReview.variables?.decision === 'APPROVED'}
          onClick={() => handleSubmit('APPROVED')}
        >
          Onayla
        </Button>
        <Button
          variant="contained"
          color="error"
          startIcon={<CancelOutlinedIcon />}
          loading={submitReview.isPending && submitReview.variables?.decision === 'REJECTED'}
          onClick={() => handleSubmit('REJECTED')}
        >
          Reddet
        </Button>
        {existingReview && (
          <Box sx={{ flex: 1, display: 'flex', justifyContent: 'flex-end' }}>
            <Button size="small" color="inherit" onClick={() => setShowForm(false)}>
              Vazgeç
            </Button>
          </Box>
        )}
      </Stack>
    </Card>
  )
}
