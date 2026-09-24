import { Outlet, NavLink } from 'react-router-dom'
import Box from '@mui/material/Box'
import Stack from '@mui/material/Stack'
import Typography from '@mui/material/Typography'
import IconButton from '@mui/material/IconButton'
import Avatar from '@mui/material/Avatar'
import Button from '@mui/material/Button'
import ShieldOutlinedIcon from '@mui/icons-material/ShieldOutlined'
import DashboardOutlinedIcon from '@mui/icons-material/DashboardOutlined'
import LightModeOutlinedIcon from '@mui/icons-material/LightModeOutlined'
import DarkModeOutlinedIcon from '@mui/icons-material/DarkModeOutlined'
import LogoutOutlinedIcon from '@mui/icons-material/LogoutOutlined'
import { useColorScheme } from '@mui/material/styles'
import { useAuth } from '../auth/AuthContext'

const SIDEBAR_WIDTH = 244

/**
 * Dashboard ve (ileride) İşlem Detayı sayfalarının ortak iskeleti — sidebar +
 * üst bar. React Router'ın nested route / <Outlet/> deseniyle kuruldu: bu
 * bileşen bir route'un "element"i, içindeki sayfa <Outlet/>'in yerine geçiyor.
 * Böylece sidebar/topbar sayfa geçişlerinde yeniden mount olmuyor.
 */
export function AppShell() {
  const { mode, setMode } = useColorScheme()
  const auth = useAuth()

  return (
    <Box sx={{ display: 'flex', minHeight: '100vh' }}>
      <Box
        component="nav"
        sx={{
          width: SIDEBAR_WIDTH,
          flexShrink: 0,
          bgcolor: 'sidebar.background',
          color: 'sidebar.text',
          display: 'flex',
          flexDirection: 'column',
          p: 2,
        }}
      >
        <Stack direction="row" spacing={1.25} sx={{ alignItems: 'center', px: 1, mb: 3.5 }}>
          <ShieldOutlinedIcon sx={{ color: 'primary.main', fontSize: 21 }} />
          <Typography variant="h6" sx={{ fontSize: 15, fontWeight: 700 }}>
            FraudDetect
          </Typography>
        </Stack>

        <NavLink to="/" end style={{ textDecoration: 'none' }}>
          {({ isActive }) => (
            <Stack
              direction="row"
              spacing={1.25}
              sx={{
                alignItems: 'center',
                px: 1.5,
                py: 1.1,
                borderRadius: 1,
                bgcolor: isActive ? 'primary.main' : 'transparent',
                color: isActive ? 'primary.contrastText' : 'sidebar.textMuted',
              }}
            >
              <DashboardOutlinedIcon sx={{ fontSize: 17 }} />
              <Typography sx={{ fontSize: 13.5, fontWeight: 500 }}>Dashboard</Typography>
            </Stack>
          )}
        </NavLink>

        <Box sx={{ flex: 1 }} />

        <Stack
          direction="row"
          spacing={1.25}
          sx={{ alignItems: 'center', p: 1, borderRadius: 1, bgcolor: 'rgba(255,255,255,0.03)' }}
        >
          <Avatar sx={{ width: 28, height: 28, fontSize: 11, bgcolor: 'rgba(255,255,255,0.08)' }}>
            {(auth.username ?? '?').slice(0, 2).toUpperCase()}
          </Avatar>
          <Typography sx={{ fontSize: 13, flex: 1 }}>{auth.username}</Typography>
          <IconButton
            aria-label="Çıkış yap"
            size="small"
            onClick={auth.logout}
            sx={{ color: 'sidebar.textMuted' }}
          >
            <LogoutOutlinedIcon sx={{ fontSize: 18 }} />
          </IconButton>
        </Stack>
      </Box>

      <Box sx={{ flex: 1, display: 'flex', flexDirection: 'column', minWidth: 0 }}>
        <Box
          component="header"
          sx={{
            height: 60,
            flexShrink: 0,
            display: 'flex',
            alignItems: 'center',
            justifyContent: 'flex-end',
            px: 3.5,
            borderBottom: '1px solid',
            borderColor: 'divider',
          }}
        >
          <Button
            size="small"
            startIcon={mode === 'light' ? <DarkModeOutlinedIcon /> : <LightModeOutlinedIcon />}
            onClick={() => setMode(mode === 'light' ? 'dark' : 'light')}
            color="inherit"
          >
            {mode === 'light' ? 'Koyu mod' : 'Açık mod'}
          </Button>
        </Box>

        <Box component="main" sx={{ flex: 1, overflow: 'auto' }}>
          <Outlet />
        </Box>
      </Box>
    </Box>
  )
}
