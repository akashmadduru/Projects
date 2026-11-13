/** Privacy (user-service) and exchange-offer (promotion-service) API clients. */

import { apiDownload, apiFetch } from '../lib/http'
import type { DeletionStatus, DeviceCondition, ExchangeProgram, ExchangeQuote, ExportJob } from '../types'

export const privacyApi = {
  requestExport(): Promise<ExportJob> {
    return apiFetch<ExportJob>('/api/users/me/export', { method: 'POST' })
  },

  exportStatus(jobId: string): Promise<ExportJob> {
    return apiFetch<ExportJob>(`/api/users/me/export/${jobId}`)
  },

  downloadExport(job: ExportJob): Promise<Blob> {
    return apiDownload(job.downloadUrl ?? `/api/users/me/export/${job.jobId}/download`)
  },

  requestDeletion(password: string): Promise<DeletionStatus> {
    return apiFetch<DeletionStatus>('/api/users/me/delete', { method: 'POST', body: { password } })
  },

  deletionStatus(): Promise<DeletionStatus> {
    return apiFetch<DeletionStatus>('/api/users/me/delete')
  },

  cancelDeletion(): Promise<void> {
    return apiFetch<void>('/api/users/me/delete/cancel', { method: 'POST' })
  },
}

export const exchangeApi = {
  programs(productId: string): Promise<ExchangeProgram[]> {
    return apiFetch<ExchangeProgram[]>('/api/promotions/exchange/programs', { params: { productId } })
  },

  quote(request: {
    programId: string
    brand: string
    model: string
    condition: DeviceCondition
    ageYears: number
  }): Promise<ExchangeQuote> {
    return apiFetch<ExchangeQuote>('/api/promotions/exchange/quote', { method: 'POST', body: request })
  },
}
