import type { ReactNode } from 'react'
import { Navigate } from 'react-router-dom'
import { useAuth } from './AuthContext'

/**
 * Giriş yapılmamışsa /login'e yönlendirir. Yetki kontrolü değil (rol bazlı
 * kısıtlama şu an yok) — sadece "geçerli bir token var mı" sorusuna bakıyor,
 * aynı backend'deki JwtAuthenticationFilter'ın yaptığı gibi.
 */
export function ProtectedRoute({ children }: { children: ReactNode }) {
  const { isAuthenticated } = useAuth()

  if (!isAuthenticated) {
    return <Navigate to="/login" replace />
  }

  return children
}
