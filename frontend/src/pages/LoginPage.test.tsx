import { describe, it, expect, vi, beforeEach } from 'vitest'
import { render, screen, waitFor } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { QueryClient, QueryClientProvider } from '@tanstack/react-query'
import { MemoryRouter } from 'react-router-dom'
import { LoginPage } from './LoginPage'
import { useAuth } from '../auth/AuthContext'
import { apiClient } from '../api/client'

vi.mock('../auth/AuthContext', () => ({
  useAuth: vi.fn(),
}))

vi.mock('../api/client', () => ({
  apiClient: { POST: vi.fn() },
}))

const mockUseAuth = vi.mocked(useAuth)
const mockPost = vi.mocked(apiClient.POST)

function renderLoginPage() {
  const queryClient = new QueryClient()
  return render(
    <QueryClientProvider client={queryClient}>
      <MemoryRouter>
        <LoginPage />
      </MemoryRouter>
    </QueryClientProvider>,
  )
}

describe('LoginPage', () => {
  const login = vi.fn()

  beforeEach(() => {
    login.mockClear()
    mockPost.mockReset()
    mockUseAuth.mockReturnValue({ isAuthenticated: false, username: null, login, logout: vi.fn() })
  })

  it('demo hesap bilgileriyle önceden doldurulmuş gelir', () => {
    renderLoginPage()

    expect(screen.getByLabelText('Kullanıcı adı')).toHaveValue('analyst')
    expect(screen.getByLabelText('Şifre')).toHaveValue('ChangeMe123!')
  })

  it('başarılı girişte token AuthContext üzerinden kaydedilir', async () => {
    mockPost.mockResolvedValue({ data: { token: 'fake-jwt' }, error: undefined } as never)
    const user = userEvent.setup()

    renderLoginPage()
    await user.click(screen.getByRole('button', { name: 'Giriş Yap' }))

    await waitFor(() => expect(login).toHaveBeenCalledWith('fake-jwt'))
  })

  it('hatalı girişte kullanıcıyı bilgilendiren bir uyarı gösterir, AuthContext güncellenmez', async () => {
    mockPost.mockResolvedValue({ data: undefined, error: { message: 'Unauthorized' } } as never)
    const user = userEvent.setup()

    renderLoginPage()
    await user.click(screen.getByRole('button', { name: 'Giriş Yap' }))

    expect(await screen.findByText('Kullanıcı adı veya şifre hatalı.')).toBeInTheDocument()
    expect(login).not.toHaveBeenCalled()
  })

  it('kullanıcı adı/şifre alanları düzenlenebilir', async () => {
    const user = userEvent.setup()
    renderLoginPage()

    const usernameField = screen.getByLabelText('Kullanıcı adı')
    await user.clear(usernameField)
    await user.type(usernameField, 'baska-kullanici')

    expect(usernameField).toHaveValue('baska-kullanici')
  })
})
