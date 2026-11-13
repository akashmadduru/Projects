<script setup lang="ts">
import { onMounted, ref, watch } from 'vue';
import { RouterLink } from 'vue-router';

import ProblemAlert from '@/components/ProblemAlert.vue';
import { errorInfo, fmtDate } from '@/lib/adminApi';
import { assignTicket, getTicket, replyToTicket, setTicketStatus } from '@/lib/trustApi';
import { priorityBadge, slaState } from '@/lib/ticketUi';
import { TICKET_STATUSES, type TicketDetail } from '@/types/trust';

/** One ticket: conversation, reply / internal note, assignment, status. */
const props = defineProps<{ id: string }>();

type ErrorInfo = { message: string; correlationId: string | null };

const ticket = ref<TicketDetail | null>(null);
const loading = ref(false);
const error = ref<ErrorInfo | null>(null);
const actionError = ref<ErrorInfo | null>(null);
const acting = ref(false);
const body = ref('');
const internal = ref(false);
const assigneeInput = ref('');

async function load(): Promise<void> {
  loading.value = true;
  error.value = null;
  try {
    ticket.value = await getTicket(props.id);
  } catch (e: unknown) {
    error.value = errorInfo(e);
    ticket.value = null;
  } finally {
    loading.value = false;
  }
}

async function act(fn: () => Promise<unknown>): Promise<boolean> {
  acting.value = true;
  actionError.value = null;
  try {
    await fn();
    await load();
    return true;
  } catch (e: unknown) {
    actionError.value = errorInfo(e);
    return false;
  } finally {
    acting.value = false;
  }
}

async function send(): Promise<void> {
  const text = body.value.trim();
  if (text.length === 0 || text.length > 5000) return;
  if (await act(() => replyToTicket(props.id, text, internal.value))) {
    body.value = '';
    internal.value = false;
  }
}

function assignToMe(): void {
  void act(() => assignTicket(props.id));
}

function assignToUser(): void {
  const id = assigneeInput.value.trim();
  if (id.length === 0) return;
  void act(() => assignTicket(props.id, id)).then(() => {
    assigneeInput.value = '';
  });
}

function changeStatus(event: Event): void {
  const status = (event.target as HTMLSelectElement).value;
  void act(() => setTicketStatus(props.id, status));
}

onMounted(load);
watch(() => props.id, load);
</script>

<template>
  <section class="flex flex-col gap-4">
    <RouterLink to="/support/tickets" class="link link-hover text-sm opacity-70">← Tickets</RouterLink>

    <div v-if="loading && !ticket" class="data-card p-8 text-center" role="status" aria-live="polite">
      <span class="loading loading-spinner loading-lg" aria-hidden="true" />
    </div>

    <div v-else-if="error">
      <ProblemAlert :message="error.message" :correlation-id="error.correlationId" />
      <button class="btn btn-outline mt-4" type="button" @click="load()">Try again</button>
    </div>

    <template v-else-if="ticket">
      <div class="flex flex-wrap items-start justify-between gap-3">
        <div>
          <h1 class="text-2xl font-semibold">{{ ticket.subject }}</h1>
          <p class="text-sm opacity-70">
            {{ ticket.email ?? 'unknown customer' }} · {{ ticket.category }} · opened {{ fmtDate(ticket.createdAt) }}
          </p>
          <p v-if="ticket.orderId" class="text-sm">
            Order:
            <RouterLink :to="{ path: '/orders', query: { q: ticket.orderId.slice(0, 8) } }" class="link font-mono">
              {{ ticket.orderId.slice(0, 8) }}
            </RouterLink>
          </p>
        </div>
        <div class="flex flex-wrap items-center gap-2">
          <span class="badge" :class="priorityBadge(ticket.priority)">{{ ticket.priority }}</span>
          <span class="badge badge-outline">SLA {{ slaState(ticket) }}</span>
          <span v-if="ticket.csat" class="badge badge-success">CSAT {{ ticket.csat }}/5</span>
        </div>
      </div>

      <ProblemAlert v-if="actionError" :message="actionError.message" :correlation-id="actionError.correlationId" />

      <div class="data-card flex flex-wrap items-end gap-4">
        <div class="form-control">
          <label class="label" for="ticket-status"><span class="label-text">Status</span></label>
          <select
            id="ticket-status"
            class="select select-bordered select-sm"
            :value="ticket.status"
            :disabled="acting"
            data-test="ticket-status"
            @change="changeStatus"
          >
            <option v-for="s in TICKET_STATUSES" :key="s" :value="s">{{ s }}</option>
          </select>
        </div>
        <div class="text-sm">
          Assignee:
          <span class="font-mono">{{ ticket.assigneeUserId ? ticket.assigneeUserId.slice(0, 8) : 'unassigned' }}</span>
        </div>
        <button class="btn btn-outline btn-sm" type="button" :disabled="acting" data-test="assign-me" @click="assignToMe">
          Assign to me
        </button>
        <form class="flex items-center gap-2" @submit.prevent="assignToUser">
          <input
            v-model="assigneeInput"
            class="input input-bordered input-sm w-72 font-mono"
            placeholder="Agent user id"
            aria-label="Assign to user id"
          />
          <button class="btn btn-outline btn-sm" type="submit" :disabled="acting">Assign</button>
        </form>
      </div>

      <div class="flex flex-col gap-3" data-test="ticket-messages">
        <article
          v-for="m in ticket.messages ?? []"
          :key="m.id"
          class="data-card"
          :class="{ 'border border-warning': m.internal }"
        >
          <p class="text-xs opacity-70">
            <span class="font-semibold">{{ m.authorType }}</span>
            <span v-if="m.internal" class="badge badge-warning badge-xs ml-1">internal note</span>
            · {{ fmtDate(m.createdAt) }}
          </p>
          <p class="mt-1 whitespace-pre-line text-sm">{{ m.body }}</p>
        </article>
      </div>

      <form class="data-card flex flex-col gap-2" @submit.prevent="send">
        <label class="label" for="ticket-reply"><span class="label-text">Reply</span></label>
        <textarea
          id="ticket-reply"
          v-model="body"
          class="textarea textarea-bordered h-28"
          maxlength="5000"
          data-test="ticket-reply"
        />
        <div class="flex items-center justify-between">
          <label class="label cursor-pointer gap-2">
            <input v-model="internal" type="checkbox" class="checkbox checkbox-sm" data-test="ticket-internal" />
            <span class="label-text">Internal note (not visible to customer)</span>
          </label>
          <button class="btn btn-primary btn-sm" type="submit" :disabled="acting || body.trim().length === 0">
            <span v-if="acting" class="loading loading-spinner loading-xs" aria-hidden="true" />
            {{ internal ? 'Add note' : 'Send reply' }}
          </button>
        </div>
      </form>
    </template>
  </section>
</template>
