/**
 * Ham renk değerleri — tasarım taslağında (Artifact) kilitlenen Charcoal + Violet
 * paleti. Bu dosya MUI'ye bağımlı değil, sadece hex değerleri tutuyor; MUI'ye
 * özel çeviri (palette, contrastText, alpha ile soluk arka planlar vb.) theme/index.ts'te.
 */

export const tokens = {
  light: {
    primary: '#7C5CFC',
    primaryHover: '#6845E0',
    onPrimary: '#FFFFFF',
    bg: '#FAFAFA',
    surface: '#FFFFFF',
    surface2: '#F1F1F4',
    text: '#1A1A1F',
    textMuted: '#6B6B76',
    border: '#E5E5EA',
    sidebarBg: '#14131A',
    sidebarText: '#F5F5F7',
    sidebarTextMuted: '#8C8C99',
    approve: '#16A34A',
    review: '#D97706',
    block: '#DC2626',
  },
  dark: {
    primary: '#9B82FF',
    primaryHover: '#B29DFF',
    onPrimary: '#14131A',
    bg: '#0E0D12',
    surface: '#17161D',
    surface2: '#1F1E27',
    text: '#F1F1F4',
    textMuted: '#9A99A6',
    border: '#2A2934',
    sidebarBg: '#0A090D',
    sidebarText: '#F1F1F4',
    sidebarTextMuted: '#8B899A',
    approve: '#34D399',
    review: '#F5B84D',
    block: '#F2637A',
  },
} as const

export type ThemeTokens = typeof tokens.light
