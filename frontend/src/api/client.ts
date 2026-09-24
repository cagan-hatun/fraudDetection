import createClient from 'openapi-fetch'
import type { paths } from './schema'
import { getToken, clearToken } from './token'

/**
 * Tipli API client — tipler backend'in gerçek OpenAPI şemasından
 * (`npx openapi-typescript ...` ile) üretiliyor, elle yazılmıyor. Böylece
 * backend'de bir alan/uç nokta değişirse şemayı yeniden üretip derleme
 * zamanında (tip hatası olarak) haberimiz olur — sessizce kırılmaz.
 */
export const apiClient = createClient<paths>({
  baseUrl: import.meta.env.VITE_API_BASE_URL,
})

// Her isteğe, varsa JWT'yi otomatik ekler — tek tek her API çağrısında
// "Authorization" header'ı yazmamıza gerek kalmıyor.
apiClient.use({
  onRequest({ request }) {
    const token = getToken()
    if (token) {
      request.headers.set('Authorization', `Bearer ${token}`)
    }
    return request
  },
  // Token süresi dolmuş/geçersizse backend her istekte 401/403 döner ama
  // AuthContext'in React state'i bunu kendiliğinden öğrenemez (localStorage
  // değişikliği React'i "haberdar etmez") — sidebar "giriş yapılmış"
  // görünmeye devam eder, oysa her API çağrısı sessizce başarısız olur.
  // Login denemesinin kendi 401'ini (yanlış şifre) hariç tutuyoruz — o,
  // LoginPage'in kendi hata mesajıyla zaten ele alınıyor.
  onResponse({ response, request }) {
    const isAuthEndpoint = request.url.includes('/api/auth/')
    if (!isAuthEndpoint && (response.status === 401 || response.status === 403)) {
      clearToken()
      if (window.location.pathname !== '/login') {
        window.location.assign('/login')
      }
    }
    return response
  },
})
