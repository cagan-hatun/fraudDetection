import { queryOptions, useMutation, useQuery, useQueryClient } from '@tanstack/react-query'
import { apiClient } from './client'
import type { components } from './schema'

type SubmitReviewRequest = components['schemas']['SubmitReviewRequest']

export function useScenarios() {
  return useQuery({
    queryKey: ['scenarios'],
    queryFn: async () => {
      const { data, error } = await apiClient.GET('/api/demo/scenarios')
      if (error) throw error
      return data
    },
  })
}

export function useReplayMutation() {
  const queryClient = useQueryClient()
  return useMutation({
    mutationFn: async (scenarioId: string) => {
      const { data, error } = await apiClient.POST('/api/demo/replay/{scenarioId}', {
        params: { path: { scenarioId } },
      })
      if (error) throw error
      return data
    },
    onSuccess: (data) => {
      if (data?.transactionId == null) return
      // Bu transactionId için ilk sorguyu önceden doldurup an be an
      // PENDING gösterebiliriz — ekstra bir istek beklemeden.
      queryClient.setQueryData(['transaction', data.transactionId], data)
    },
  })
}

/**
 * Tek bir işlemin sorgu tanımı — hem tek başına (useQuery) hem de birden
 * fazla işlemi aynı anda izlemek için (useQueries, donut grafiği için)
 * kullanılabilsin diye ayrı bir "query options" fonksiyonu olarak tanımlı.
 * PENDING olduğu sürece 1.5sn'de bir otomatik yeniden çeker (backend Kafka
 * üzerinden asenkron skorluyor); SCORED'a dönünce polling kendiliğinden durur.
 */
export function transactionStatusOptions(transactionId: number) {
  return queryOptions({
    queryKey: ['transaction', transactionId] as const,
    queryFn: async () => {
      const { data, error } = await apiClient.GET('/api/demo/transactions/{id}', {
        params: { path: { id: transactionId } },
      })
      if (error) throw error
      return data
    },
    refetchInterval: (query) => (query.state.data?.status === 'PENDING' ? 1500 : false),
  })
}

export function useTransactionStatus(transactionId: number) {
  return useQuery(transactionStatusOptions(transactionId))
}

/**
 * İşlem Detayı sayfası için — polling uç noktasından AYRI, tek seferlik ama
 * zengin bir çağrı (merchant/tutar/SHAP/Rule Engine dahil). PENDING iken
 * hâlâ 1.5sn'de bir yeniler (kullanıcı sayfayı açık tutarken SCORED'a
 * dönüşünü görsün diye), SCORED'a dönünce durur.
 */
export function useTransactionDetail(transactionId: number) {
  return useQuery({
    queryKey: ['transaction-detail', transactionId],
    queryFn: async () => {
      const { data, error } = await apiClient.GET('/api/demo/transactions/{id}/detail', {
        params: { path: { id: transactionId } },
      })
      if (error) throw error
      return data
    },
    refetchInterval: (query) => (query.state.data?.status === 'PENDING' ? 1500 : false),
  })
}

/**
 * Bir analistin REVIEW durumundaki bir işlem için karar vermesi (onay/red +
 * not). Backend sadece nihai karar REVIEW ise kabul ediyor (409 Conflict
 * döner aksi halde). Başarılı olursa detay sorgusunu geçersiz kılıp
 * sayfanın yeni durumu (analystReview dolu) otomatik göstermesini sağlıyoruz.
 */
export function useSubmitReview(transactionId: number) {
  const queryClient = useQueryClient()
  return useMutation({
    mutationFn: async (request: SubmitReviewRequest) => {
      const { data, error } = await apiClient.POST('/api/demo/transactions/{id}/review', {
        params: { path: { id: transactionId } },
        body: request,
      })
      if (error) throw error
      return data
    },
    onSuccess: () => {
      queryClient.invalidateQueries({ queryKey: ['transaction-detail', transactionId] })
    },
  })
}
