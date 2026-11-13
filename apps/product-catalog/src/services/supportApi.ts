/** Support API client (support-service via gateway). */

import { apiFetch } from '../lib/http'
import type { ChatReply, CreateTicketRequest, FaqArticle, FaqCategory, Ticket } from '../types'

export const supportApi = {
  articles(params: { category?: FaqCategory; q?: string } = {}): Promise<FaqArticle[]> {
    const query: Record<string, string> = {}
    if (params.category) query['category'] = params.category
    if (params.q) query['q'] = params.q
    return apiFetch<FaqArticle[]>('/api/support/articles', { params: query })
  },

  article(slug: string): Promise<FaqArticle> {
    return apiFetch<FaqArticle>(`/api/support/articles/${encodeURIComponent(slug)}`)
  },

  articleFeedback(slug: string, helpful: boolean): Promise<void> {
    return apiFetch<void>(`/api/support/articles/${encodeURIComponent(slug)}/feedback`, {
      method: 'POST',
      body: { helpful },
    })
  },

  tickets(): Promise<Ticket[]> {
    return apiFetch<Ticket[] | { content: Ticket[] }>('/api/support/tickets').then((r) => (Array.isArray(r) ? r : r.content))
  },

  ticket(id: string): Promise<Ticket> {
    return apiFetch<Ticket>(`/api/support/tickets/${id}`)
  },

  createTicket(request: CreateTicketRequest): Promise<Ticket> {
    return apiFetch<Ticket>('/api/support/tickets', { method: 'POST', body: request })
  },

  reply(id: string, body: string): Promise<Ticket> {
    return apiFetch<Ticket>(`/api/support/tickets/${id}/messages`, { method: 'POST', body: { body } })
  },

  close(id: string): Promise<Ticket> {
    return apiFetch<Ticket>(`/api/support/tickets/${id}/close`, { method: 'POST' })
  },

  csat(id: string, score: number): Promise<void> {
    return apiFetch<void>(`/api/support/tickets/${id}/csat`, { method: 'POST', body: { score } })
  },

  chat(sessionId: string, message: string): Promise<ChatReply> {
    return apiFetch<ChatReply>('/api/support/chat', { method: 'POST', body: { sessionId, message } })
  },
}
