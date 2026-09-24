import { useState } from 'react'
import { useQueries } from '@tanstack/react-query'
import Box from '@mui/material/Box'
import Typography from '@mui/material/Typography'
import Card from '@mui/material/Card'
import Stack from '@mui/material/Stack'
import Button from '@mui/material/Button'
import CircularProgress from '@mui/material/CircularProgress'
import Table from '@mui/material/Table'
import TableHead from '@mui/material/TableHead'
import TableBody from '@mui/material/TableBody'
import TableRow from '@mui/material/TableRow'
import TableCell from '@mui/material/TableCell'
import Alert from '@mui/material/Alert'
import { useScenarios, useReplayMutation, transactionStatusOptions } from '../api/queries'
import { TransactionRow } from './TransactionRow'
import { SummaryDonutChart } from './SummaryDonutChart'

export function DashboardPage() {
  const scenariosQuery = useScenarios()
  const replayMutation = useReplayMutation()

  // Backend'de "bu kullanıcının tetiklediği tüm işlemler" listesini dönen bir
  // uç nokta yok — bu yüzden bu oturumda tetiklediğimiz id'leri kendimiz
  // (sadece bellekte, sayfa yenilenince kaybolacak şekilde) takip ediyoruz.
  const [triggeredIds, setTriggeredIds] = useState<number[]>([])

  // Donut grafiğinin TÜM tetiklenen işlemleri aynı anda görmesi gerektiği
  // için polling'i burada (her satırın kendi içinde değil) tek elden
  // yönetiyoruz — useQueries, dinamik uzunluktaki bir listeyi paralel
  // sorgulamak için React Query'nin kendi deseni (döngüde useQuery çağıramayız).
  const transactionQueries = useQueries({
    queries: triggeredIds.map((id) => transactionStatusOptions(id)),
  })

  function handleTrigger(scenarioId: string) {
    replayMutation.mutate(scenarioId, {
      onSuccess: (data) => {
        if (data?.transactionId != null) {
          setTriggeredIds((ids) => [data.transactionId!, ...ids])
        }
      },
    })
  }

  const counts = { approve: 0, review: 0, block: 0, pending: 0 }
  for (const query of transactionQueries) {
    const data = query.data
    if (!data || data.status === 'PENDING') {
      counts.pending += 1
    } else if (data.action === 'APPROVE') {
      counts.approve += 1
    } else if (data.action === 'REVIEW') {
      counts.review += 1
    } else if (data.action === 'BLOCK') {
      counts.block += 1
    }
  }

  return (
    <Box sx={{ p: 4, display: 'flex', flexDirection: 'column', gap: 4 }}>
      <Typography variant="h4">Dashboard</Typography>

      {triggeredIds.length > 0 && (
        <Card variant="outlined" sx={{ p: 3, alignSelf: 'flex-start' }}>
          <Typography variant="body2" sx={{ fontWeight: 500, mb: 2 }}>
            İşlem durumu dağılımı
          </Typography>
          <SummaryDonutChart counts={counts} />
        </Card>
      )}

      <Box>
        <Stack direction="row" sx={{ justifyContent: 'space-between', alignItems: 'baseline', mb: 1.5 }}>
          <Typography variant="h6">Demo senaryoları</Typography>
          <Typography variant="body2" color="text.secondary">
            IEEE-CIS holdout setinden gerçek işlemler
          </Typography>
        </Stack>

        <Card variant="outlined">
          {scenariosQuery.isLoading && (
            <Box sx={{ p: 3, display: 'flex', justifyContent: 'center' }}>
              <CircularProgress size={22} />
            </Box>
          )}

          {scenariosQuery.isError && (
            <Box sx={{ p: 2 }}>
              <Alert severity="error">Senaryolar yüklenemedi.</Alert>
            </Box>
          )}

          {scenariosQuery.data?.map((scenario, index) => (
            <Stack
              key={scenario.scenarioId}
              direction="row"
              sx={{
                alignItems: 'center',
                justifyContent: 'space-between',
                px: 2.5,
                py: 1.5,
                borderTop: index === 0 ? 'none' : '1px solid',
                borderColor: 'divider',
              }}
            >
              <Typography variant="body2">{scenario.label}</Typography>
              <Button
                size="small"
                variant="contained"
                loading={replayMutation.isPending && replayMutation.variables === scenario.scenarioId}
                onClick={() => handleTrigger(scenario.scenarioId!)}
              >
                Tetikle
              </Button>
            </Stack>
          ))}
        </Card>
      </Box>

      <Box>
        <Typography variant="h6" sx={{ mb: 1.5 }}>
          Bu oturumda tetiklenen işlemler
        </Typography>

        {triggeredIds.length === 0 ? (
          <Typography variant="body2" color="text.secondary">
            Henüz bir senaryo tetiklenmedi.
          </Typography>
        ) : (
          <Card variant="outlined">
            <Table size="small">
              <TableHead>
                <TableRow>
                  <TableCell>İşlem</TableCell>
                  <TableCell>Durum</TableCell>
                  <TableCell>Aksiyon</TableCell>
                  <TableCell>Fraud Olasılığı</TableCell>
                </TableRow>
              </TableHead>
              <TableBody>
                {triggeredIds.map((id, index) => (
                  <TransactionRow key={id} transactionId={id} data={transactionQueries[index]?.data} />
                ))}
              </TableBody>
            </Table>
          </Card>
        )}
      </Box>
    </Box>
  )
}
