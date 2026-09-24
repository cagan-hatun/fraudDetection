import { useNavigate } from 'react-router-dom'
import TableRow from '@mui/material/TableRow'
import TableCell from '@mui/material/TableCell'
import Chip from '@mui/material/Chip'
import CircularProgress from '@mui/material/CircularProgress'
import Stack from '@mui/material/Stack'
import Typography from '@mui/material/Typography'
import { actionToChipColor, formatFraudProbability } from '../utils/format'
import type { components } from '../api/schema'

type TransactionStatusResult = components['schemas']['TransactionStatusResult']

export function TransactionRow({
  transactionId,
  data,
}: {
  transactionId: number
  data: TransactionStatusResult | undefined
}) {
  const navigate = useNavigate()
  const isPending = !data || data.status === 'PENDING'

  return (
    <TableRow hover onClick={() => navigate(`/transactions/${transactionId}`)} sx={{ cursor: 'pointer' }}>
      <TableCell sx={{ fontFamily: 'IBM Plex Mono, monospace' }}>#{transactionId}</TableCell>
      <TableCell>
        {isPending ? (
          <Stack direction="row" spacing={1} sx={{ alignItems: 'center' }}>
            <CircularProgress size={13} thickness={5} />
            <Typography variant="body2" color="text.secondary">
              Kafka üzerinden işleniyor…
            </Typography>
          </Stack>
        ) : (
          <Typography variant="body2" color="text.secondary">
            SCORED
          </Typography>
        )}
      </TableCell>
      <TableCell>
        {data?.action && (
          <Chip
            label={data.action}
            color={actionToChipColor(data.action)}
            size="small"
            sx={{ fontFamily: 'IBM Plex Mono, monospace', fontWeight: 600 }}
          />
        )}
      </TableCell>
      <TableCell sx={{ fontFamily: 'IBM Plex Mono, monospace' }}>
        {data?.fraudProbability != null ? formatFraudProbability(data.fraudProbability) : '—'}
      </TableCell>
    </TableRow>
  )
}
