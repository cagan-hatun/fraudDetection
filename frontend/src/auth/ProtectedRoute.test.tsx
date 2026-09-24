import { describe, it, expect, vi } from 'vitest'
import { render, screen } from '@testing-library/react'
import { MemoryRouter, Routes, Route } from 'react-router-dom'
import { ProtectedRoute } from './ProtectedRoute'
import { useAuth } from './AuthContext'

vi.mock('./AuthContext', () => ({
  useAuth: vi.fn(),
}))

const mockUseAuth = vi.mocked(useAuth)

function renderWithRouter() {
  return render(
    <MemoryRouter initialEntries={['/']}>
      <Routes>
        <Route
          path="/"
          element={
            <ProtectedRoute>
              <div>Gizli içerik</div>
            </ProtectedRoute>
          }
        />
        <Route path="/login" element={<div>Giriş sayfası</div>} />
      </Routes>
    </MemoryRouter>,
  )
}

describe('ProtectedRoute', () => {
  it('giriş yapılmışsa çocuk bileşeni gösterir', () => {
    mockUseAuth.mockReturnValue({ isAuthenticated: true, username: 'analyst', login: vi.fn(), logout: vi.fn() })

    renderWithRouter()

    expect(screen.getByText('Gizli içerik')).toBeInTheDocument()
  })

  it("giriş yapılmamışsa /login'e yönlendirir, korumalı içeriği hiç göstermez", () => {
    mockUseAuth.mockReturnValue({ isAuthenticated: false, username: null, login: vi.fn(), logout: vi.fn() })

    renderWithRouter()

    expect(screen.getByText('Giriş sayfası')).toBeInTheDocument()
    expect(screen.queryByText('Gizli içerik')).not.toBeInTheDocument()
  })
})
