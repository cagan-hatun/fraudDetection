import { createContext, useContext, useState, type ReactNode } from 'react'
import { getToken, setToken as persistToken, clearToken } from '../api/token'
import { decodeJwtUsername } from './jwt'

interface AuthContextValue {
  isAuthenticated: boolean
  username: string | null
  login: (token: string) => void
  logout: () => void
}

const AuthContext = createContext<AuthContextValue | null>(null)

/**
 * Token'ın kendisi localStorage'da (api/token.ts) duruyor, ama React'in
 * "giriş yapılmış mı" sorusuna anında (sayfa yenilemeden) tepki verebilmesi
 * için bunu ayrıca bir React state'inde de tutuyoruz. login()/logout()
 * ikisini birden günceller — localStorage tek başına React'i "haberdar
 * etmez", bileşenler yeniden render olmaz.
 */
export function AuthProvider({ children }: { children: ReactNode }) {
  const [token, setTokenState] = useState<string | null>(() => getToken())

  function login(newToken: string) {
    persistToken(newToken)
    setTokenState(newToken)
  }

  function logout() {
    clearToken()
    setTokenState(null)
  }

  return (
    <AuthContext.Provider
      value={{
        isAuthenticated: token !== null,
        username: token ? decodeJwtUsername(token) : null,
        login,
        logout,
      }}
    >
      {children}
    </AuthContext.Provider>
  )
}

export function useAuth(): AuthContextValue {
  const ctx = useContext(AuthContext)
  if (!ctx) {
    throw new Error('useAuth, AuthProvider dışında kullanıldı')
  }
  return ctx
}
