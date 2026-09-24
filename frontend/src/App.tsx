import { lazy, Suspense } from 'react'
import { Routes, Route } from 'react-router-dom'
import Box from '@mui/material/Box'
import CircularProgress from '@mui/material/CircularProgress'
import { ProtectedRoute } from './auth/ProtectedRoute'
import { AppShell } from './layout/AppShell'

// Route bazlı code-splitting: LoginPage MUI+router dışında hafif, ama
// DashboardPage/TransactionDetailPage Recharts'ı (tek başına büyük bir
// bağımlılık) içeriyor. Bunları ayrı chunk'lara ayırmak, login sayfasını
// ilk açan birinin hiç ihtiyaç duymayacağı grafik kütüphanesini indirmesini
// engelliyor — tek bundle'da her şeyi göndermek yerine.
const LoginPage = lazy(() => import('./pages/LoginPage').then((m) => ({ default: m.LoginPage })))
const DashboardPage = lazy(() => import('./pages/DashboardPage').then((m) => ({ default: m.DashboardPage })))
const TransactionDetailPage = lazy(() =>
  import('./pages/TransactionDetailPage').then((m) => ({ default: m.TransactionDetailPage })),
)

function RouteFallback() {
  return (
    <Box sx={{ display: 'flex', justifyContent: 'center', alignItems: 'center', minHeight: '100vh' }}>
      <CircularProgress />
    </Box>
  )
}

function App() {
  return (
    <Suspense fallback={<RouteFallback />}>
      <Routes>
        <Route path="/login" element={<LoginPage />} />
        <Route
          element={
            <ProtectedRoute>
              <AppShell />
            </ProtectedRoute>
          }
        >
          <Route path="/" element={<DashboardPage />} />
          <Route path="/transactions/:id" element={<TransactionDetailPage />} />
        </Route>
      </Routes>
    </Suspense>
  )
}

export default App
