import Box from '@mui/material/Box'
import Stack from '@mui/material/Stack'
import Typography from '@mui/material/Typography'
import { useTheme } from '@mui/material/styles'
import { PieChart, Pie, Sector } from 'recharts'
import type { SectorProps } from 'recharts'

interface Counts {
  approve: number
  review: number
  block: number
  pending: number
}

/**
 * Recharts, renkleri MUI'nin sx token'ları ("success.main" gibi) olarak değil,
 * gerçek hex string olarak istiyor — theme.palette'ten okuyup elle geçiriyoruz.
 */
export function SummaryDonutChart({ counts }: { counts: Counts }) {
  const theme = useTheme()
  const total = counts.approve + counts.review + counts.block + counts.pending

  const segments = [
    { key: 'block', label: 'BLOCK', value: counts.block, color: theme.palette.error.main },
    { key: 'approve', label: 'APPROVE', value: counts.approve, color: theme.palette.success.main },
    { key: 'review', label: 'REVIEW', value: counts.review, color: theme.palette.warning.main },
    { key: 'pending', label: 'PENDING', value: counts.pending, color: theme.palette.text.disabled },
  ]

  return (
    <Stack direction="row" spacing={3} sx={{ alignItems: 'center' }}>
      <Box sx={{ position: 'relative', width: 128, height: 128, flexShrink: 0 }}>
        <PieChart width={128} height={128}>
          <Pie
            data={segments}
            dataKey="value"
            nameKey="label"
            innerRadius={42}
            outerRadius={62}
            paddingAngle={total > 0 ? 2 : 0}
            stroke="none"
            shape={(props: SectorProps & { payload?: (typeof segments)[number] }) => (
              <Sector {...props} fill={props.payload?.color} />
            )}
          />
        </PieChart>
        <Box
          sx={{
            position: 'absolute',
            inset: 0,
            display: 'flex',
            flexDirection: 'column',
            alignItems: 'center',
            justifyContent: 'center',
            pointerEvents: 'none',
          }}
        >
          <Typography variant="mono" sx={{ fontSize: 20, fontWeight: 500 }}>
            {total}
          </Typography>
          <Typography sx={{ fontSize: 10, color: 'text.secondary' }}>toplam</Typography>
        </Box>
      </Box>

      <Stack spacing={1.25}>
        {segments.map((segment) => (
          <Stack key={segment.key} direction="row" spacing={1} sx={{ alignItems: 'center' }}>
            <Box sx={{ width: 8, height: 8, borderRadius: 0.5, bgcolor: segment.color }} />
            <Typography variant="body2" color="text.secondary" sx={{ minWidth: 68 }}>
              {segment.label}
            </Typography>
            <Typography variant="mono" sx={{ fontSize: 13, fontWeight: 500 }}>
              {segment.value}
            </Typography>
          </Stack>
        ))}
      </Stack>
    </Stack>
  )
}
