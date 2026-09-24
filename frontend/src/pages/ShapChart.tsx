import Box from '@mui/material/Box'
import Stack from '@mui/material/Stack'
import Typography from '@mui/material/Typography'
import { useTheme } from '@mui/material/styles'
import { BarChart, Bar, XAxis, YAxis, Rectangle, ReferenceLine, ResponsiveContainer, Tooltip } from 'recharts'
import type { RectangleProps } from 'recharts'
import { formatFraudProbability, formatShapValue } from '../utils/format'
import type { components } from '../api/schema'

type ShapContribution = components['schemas']['ShapContribution']

const TOP_N = 8

export function ShapChart({
  contributions,
  baseValue,
  fraudProbability,
}: {
  contributions: ShapContribution[]
  baseValue: number
  fraudProbability: number
}) {
  const theme = useTheme()

  // Backend zaten |shap_value|'ya göre büyükten küçüğe sıralı gönderiyor —
  // ilk TOP_N'i tek tek gösterip gerisini "diğer" olarak topluyoruz (bazı
  // işlemlerde 100+ özellik olabiliyor, hepsini listelemek okunmaz olurdu).
  const top = contributions.slice(0, TOP_N)
  const rest = contributions.slice(TOP_N)
  const restSum = rest.reduce((sum, c) => sum + (c.shapValue ?? 0), 0)

  const chartData = [
    ...top.map((c) => ({ name: c.featureName ?? '?', value: c.shapValue ?? 0 })),
    ...(rest.length > 0 ? [{ name: `diğer (${rest.length} özellik)`, value: restSum }] : []),
  ]

  return (
    <Box>
      <Stack direction="row" sx={{ justifyContent: 'space-between', mb: 2 }}>
        <Typography variant="body2">Fraud olasılığına özellik katkıları (log-odds)</Typography>
        <Typography variant="body2" color="text.secondary">
          taban değerden tahmine
        </Typography>
      </Stack>

      <ResponsiveContainer width="100%" height={chartData.length * 34 + 20}>
        <BarChart data={chartData} layout="vertical" margin={{ left: 8, right: 24 }}>
          <XAxis type="number" hide />
          <YAxis
            type="category"
            dataKey="name"
            width={150}
            tick={{ fontFamily: 'IBM Plex Mono, monospace', fontSize: 12, fill: theme.palette.text.secondary }}
            axisLine={false}
            tickLine={false}
          />
          <ReferenceLine x={0} stroke={theme.palette.divider} />
          <Tooltip
            formatter={(value) => formatShapValue(Number(value))}
            contentStyle={{
              fontFamily: 'IBM Plex Mono, monospace',
              fontSize: 12,
              background: theme.palette.background.paper,
              border: `1px solid ${theme.palette.divider}`,
            }}
          />
          <Bar
            dataKey="value"
            radius={3}
            shape={(props: RectangleProps & { payload?: (typeof chartData)[number] }) => (
              <Rectangle
                {...props}
                fill={
                  (props.payload?.value ?? 0) >= 0 ? theme.palette.error.main : theme.palette.success.main
                }
              />
            )}
          />
        </BarChart>
      </ResponsiveContainer>

      <Stack
        direction="row"
        sx={{ justifyContent: 'space-between', mt: 2, pt: 2, borderTop: '1px solid', borderColor: 'divider' }}
      >
        <Typography variant="body2" color="text.secondary">
          Taban değer{' '}
          <Typography component="span" variant="mono" sx={{ color: 'text.primary' }}>
            {formatShapValue(baseValue)}
          </Typography>
        </Typography>
        <Typography variant="body2" color="text.secondary">
          Tahmin →{' '}
          <Typography component="span" variant="mono" sx={{ color: 'error.main' }}>
            {formatFraudProbability(fraudProbability)}
          </Typography>
        </Typography>
      </Stack>
    </Box>
  )
}
