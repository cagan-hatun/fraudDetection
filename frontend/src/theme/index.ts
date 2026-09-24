import { createTheme, alpha } from '@mui/material/styles'
import { tokens } from './tokens'

import '@fontsource/space-grotesk/500.css'
import '@fontsource/space-grotesk/600.css'
import '@fontsource/space-grotesk/700.css'
import '@fontsource/ibm-plex-sans/400.css'
import '@fontsource/ibm-plex-sans/500.css'
import '@fontsource/ibm-plex-mono/400.css'
import '@fontsource/ibm-plex-mono/500.css'

const fontHeading = "'Space Grotesk', system-ui, sans-serif"
const fontBody = "'IBM Plex Sans', system-ui, sans-serif"
const fontMono = "'IBM Plex Mono', ui-monospace, monospace"

// MUI'nin standart Palette tipine olmayan alanları (sidebar, mono yazı tipi)
// TypeScript'e tanıtıyoruz ki theme.palette.sidebar.background gibi erişimler
// tip güvenli olsun ve otomatik tamamlama çalışsın.
declare module '@mui/material/styles' {
  interface Palette {
    sidebar: {
      background: string
      text: string
      textMuted: string
    }
  }
  interface PaletteOptions {
    sidebar?: {
      background: string
      text: string
      textMuted: string
    }
  }
  interface TypographyVariants {
    mono: React.CSSProperties
  }
  interface TypographyVariantsOptions {
    mono?: React.CSSProperties
  }
}

declare module '@mui/material/Typography' {
  interface TypographyPropsVariantOverrides {
    mono: true
  }
}

function buildColorScheme(mode: 'light' | 'dark') {
  const t = tokens[mode]
  return {
    palette: {
      mode,
      primary: {
        main: t.primary,
        dark: t.primaryHover,
        contrastText: t.onPrimary,
      },
      success: { main: t.approve },
      warning: { main: t.review },
      error: { main: t.block },
      background: {
        default: t.bg,
        paper: t.surface,
      },
      text: {
        primary: t.text,
        secondary: t.textMuted,
      },
      divider: t.border,
      sidebar: {
        background: t.sidebarBg,
        text: t.sidebarText,
        textMuted: t.sidebarTextMuted,
      },
    },
  }
}

export const theme = createTheme({
  cssVariables: { colorSchemeSelector: 'data' },
  colorSchemes: {
    light: buildColorScheme('light'),
    dark: buildColorScheme('dark'),
  },
  shape: {
    borderRadius: 8,
  },
  typography: {
    fontFamily: fontBody,
    h1: { fontFamily: fontHeading, fontWeight: 600 },
    h2: { fontFamily: fontHeading, fontWeight: 600 },
    h3: { fontFamily: fontHeading, fontWeight: 600 },
    h4: { fontFamily: fontHeading, fontWeight: 600 },
    h5: { fontFamily: fontHeading, fontWeight: 600 },
    h6: { fontFamily: fontHeading, fontWeight: 600 },
    button: { fontFamily: fontHeading, fontWeight: 600, textTransform: 'none' },
    mono: { fontFamily: fontMono },
  },
  components: {
    // "Soluk" rozet/badge arka planlarını (approveBg, reviewBg, blockBg) ayrı
    // hex değerleri olarak SAKLAMIYORUZ — main rengin üstüne alpha() ile
    // saydamlık ekleyerek anlık üretiyoruz. Böylece tek bir renk kaynağı
    // (success.main) her zaman tutarlı kalıyor, iki ayrı token birbirinden
    // sürüklenmiyor (mockup'ta olduğu gibi).
    MuiChip: {
      styleOverrides: {
        root: ({ theme, ownerState }) => {
          const color = ownerState.color
          if (color && color !== 'default' && theme.palette[color]) {
            return {
              backgroundColor: alpha(theme.palette[color].main, 0.14),
              color: theme.palette[color].main,
              fontFamily: fontMono,
              fontWeight: 600,
            }
          }
          return {}
        },
      },
    },
  },
})
