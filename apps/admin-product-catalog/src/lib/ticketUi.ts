import type { Ticket, TicketPriority } from '@/types/trust';

export function priorityBadge(p: TicketPriority): string {
  if (p === 'URGENT') return 'badge-error';
  if (p === 'HIGH') return 'badge-warning';
  if (p === 'LOW') return 'badge-ghost';
  return 'badge-info';
}

export type SlaState = 'OK' | 'DUE_SOON' | 'BREACHED' | 'DONE' | 'N/A';

const DUE_SOON_MS = 60 * 60 * 1000;

/** Derived from the next unmet SLA deadline; DONE when the ticket is resolved/closed. */
export function slaState(t: Pick<Ticket, 'status' | 'firstResponseAt' | 'firstResponseDueAt' | 'resolutionDueAt'>, now = Date.now()): SlaState {
  if (t.status === 'RESOLVED' || t.status === 'CLOSED') return 'DONE';
  const due = t.firstResponseAt ? t.resolutionDueAt : (t.firstResponseDueAt ?? t.resolutionDueAt);
  if (!due) return 'N/A';
  const left = new Date(due).getTime() - now;
  if (Number.isNaN(left)) return 'N/A';
  if (left < 0) return 'BREACHED';
  return left < DUE_SOON_MS ? 'DUE_SOON' : 'OK';
}
