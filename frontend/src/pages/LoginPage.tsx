import { useState, type FormEvent } from 'react'
import { useNavigate } from 'react-router-dom'
import { useMutation } from '@tanstack/react-query'
import Box from '@mui/material/Box'
import Typography from '@mui/material/Typography'
import TextField from '@mui/material/TextField'
import Button from '@mui/material/Button'
import IconButton from '@mui/material/IconButton'
import InputAdornment from '@mui/material/InputAdornment'
import Alert from '@mui/material/Alert'
import Divider from '@mui/material/Divider'
import Stack from '@mui/material/Stack'
import ShieldOutlinedIcon from '@mui/icons-material/ShieldOutlined'
import Visibility from '@mui/icons-material/Visibility'
import VisibilityOff from '@mui/icons-material/VisibilityOff'
import { apiClient } from '../api/client'
import { useAuth } from '../auth/AuthContext'

export function LoginPage() {
  const auth = useAuth()
  const navigate = useNavigate()

  // Demo projesi: alanlar gerçek demo hesabıyla önceden dolduruluyor ki
  // portfolyoya bakan biri hiçbir şey yazmadan "Giriş Yap"a basabilsin.
  const [username, setUsername] = useState('analyst')
  const [password, setPassword] = useState('ChangeMe123!')
  const [showPassword, setShowPassword] = useState(false)

  const loginMutation = useMutation({
    mutationFn: async () => {
      const { data, error } = await apiClient.POST('/api/auth/login', {
        body: { username, password },
      })
      if (error) throw error
      return data
    },
    onSuccess: (data) => {
      if (!data?.token) return
      auth.login(data.token)
      navigate('/', { replace: true })
    },
  })

  function handleSubmit(event: FormEvent) {
    event.preventDefault()
    loginMutation.mutate()
  }

  return (
    <Box sx={{ display: 'flex', minHeight: '100vh' }}>
      {/* Sol: marka paneli — her zaman koyu, temadan bağımsız */}
      <Box
        sx={{
          display: { xs: 'none', md: 'flex' },
          width: '58%',
          bgcolor: 'sidebar.background',
          color: 'sidebar.text',
          flexDirection: 'column',
          justifyContent: 'space-between',
          p: 8,
        }}
      >
        <Stack direction="row" spacing={1.5} sx={{ alignItems: 'center' }}>
          <ShieldOutlinedIcon sx={{ color: 'primary.main' }} />
          <Typography variant="h6" sx={{ fontWeight: 700 }}>
            FraudDetect
          </Typography>
        </Stack>

        <Box sx={{ maxWidth: 480 }}>
          <Typography variant="h3" sx={{ mb: 2.5 }}>
            İşlemler saniyeler içinde skorlanır.
          </Typography>
          <Typography variant="body1" sx={{ color: 'sidebar.textMuted' }}>
            LightGBM tabanlı model ve kural motoru, her işlemi bağımsız olarak
            değerlendirip SHAP ile gerekçelendirir — analist tek panelden karar
            verir.
          </Typography>

          <Stack direction="row" spacing={4} sx={{ mt: 5 }}>
            <Box>
              <Typography variant="mono" sx={{ fontSize: 22, color: 'primary.main' }}>
                %83,5
              </Typography>
              <Typography variant="body2" sx={{ color: 'sidebar.textMuted' }}>
                Yakalama oranı (recall)
              </Typography>
            </Box>
            <Divider orientation="vertical" flexItem sx={{ borderColor: 'rgba(255,255,255,0.1)' }} />
            <Box>
              <Typography variant="mono" sx={{ fontSize: 22, color: 'primary.main' }}>
                3
              </Typography>
              <Typography variant="body2" sx={{ color: 'sidebar.textMuted' }}>
                Bağımsız karar kaynağı
              </Typography>
            </Box>
          </Stack>
        </Box>

        <Typography variant="body2" sx={{ color: 'sidebar.textMuted' }}>
          © 2026 FraudDetect — portfolyo projesi
        </Typography>
      </Box>

      {/* Sağ: giriş formu */}
      <Box
        sx={{
          flex: 1,
          display: 'flex',
          alignItems: 'center',
          justifyContent: 'center',
          p: 4,
        }}
      >
        <Box component="form" onSubmit={handleSubmit} sx={{ width: '100%', maxWidth: 360 }}>
          <Typography variant="h5" sx={{ mb: 1 }}>
            Hesabına giriş yap
          </Typography>
          <Typography variant="body2" color="text.secondary" sx={{ mb: 4 }}>
            Devam etmek için kullanıcı adı ve şifreni gir.
          </Typography>

          <Stack spacing={2.5}>
            <TextField
              label="Kullanıcı adı"
              value={username}
              onChange={(e) => setUsername(e.target.value)}
              autoComplete="username"
              fullWidth
              slotProps={{ input: { sx: { fontFamily: 'IBM Plex Mono, monospace' } } }}
            />

            <TextField
              label="Şifre"
              type={showPassword ? 'text' : 'password'}
              value={password}
              onChange={(e) => setPassword(e.target.value)}
              autoComplete="current-password"
              fullWidth
              slotProps={{
                input: {
                  sx: { fontFamily: 'IBM Plex Mono, monospace' },
                  endAdornment: (
                    <InputAdornment position="end">
                      <IconButton
                        aria-label={showPassword ? 'Şifreyi gizle' : 'Şifreyi göster'}
                        onClick={() => setShowPassword((v) => !v)}
                        edge="end"
                      >
                        {showPassword ? <VisibilityOff /> : <Visibility />}
                      </IconButton>
                    </InputAdornment>
                  ),
                },
              }}
            />

            {loginMutation.isError && (
              <Alert severity="error">Kullanıcı adı veya şifre hatalı.</Alert>
            )}

            <Button
              type="submit"
              variant="contained"
              size="large"
              fullWidth
              loading={loginMutation.isPending}
            >
              Giriş Yap
            </Button>
          </Stack>

          <Box
            sx={{
              mt: 3.5,
              p: 2,
              borderRadius: 1,
              bgcolor: 'action.hover',
              border: '1px solid',
              borderColor: 'divider',
            }}
          >
            <Typography variant="body2" color="text.secondary">
              Demo hesap bilgileri alanlara önceden dolduruldu — bu bir
              portfolyo projesidir, gerçek müşteri verisi içermez.
            </Typography>
          </Box>
        </Box>
      </Box>
    </Box>
  )
}
