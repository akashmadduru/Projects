<script setup lang="ts">
import { computed, onBeforeUnmount, onMounted, reactive, ref } from 'vue';

import ConfirmDialog from '@/components/ConfirmDialog.vue';
import ProblemAlert from '@/components/ProblemAlert.vue';
import { createCampaign, errorInfo, fmtDate, listCampaigns, sendCampaign } from '@/lib/adminApi';
import type {
  Campaign,
  CampaignAudience,
  CampaignChannel,
  CampaignRequest,
} from '@/types/commerce';

/** Campaign admin: list, create (modal), preview, send, sent count/status. */
interface ErrInfo {
  message: string;
  correlationId: string | null;
}

const campaigns = ref<readonly Campaign[]>([]);
const loading = ref(false);
const error = ref<ErrInfo | null>(null);
const actionError = ref<ErrInfo | null>(null);

let pollTimer: ReturnType<typeof setTimeout> | null = null;

/** Sending is async server-side: keep refreshing while any campaign is still SENDING. */
function schedulePoll(): void {
  if (pollTimer) clearTimeout(pollTimer);
  pollTimer = campaigns.value.some((c) => c.status === 'SENDING')
    ? setTimeout(() => void load(), 3000)
    : null;
}

onBeforeUnmount(() => {
  if (pollTimer) clearTimeout(pollTimer);
});

async function load(): Promise<void> {
  loading.value = true;
  error.value = null;
  try {
    campaigns.value = await listCampaigns();
    schedulePoll();
  } catch (e: unknown) {
    error.value = errorInfo(e);
    campaigns.value = [];
  } finally {
    loading.value = false;
  }
}

interface FormState {
  name: string;
  channel: CampaignChannel;
  audience: CampaignAudience;
  subject: string;
  body: string;
  link: string;
}

function blank(): FormState {
  return { name: '', channel: 'ALL', audience: 'ALL', subject: '', body: '', link: '' };
}

const modalOpen = ref(false);
const form = reactive<FormState>(blank());
const submitting = ref(false);
const formError = ref<ErrInfo | null>(null);
const touched = ref(false);

function openCreate(): void {
  Object.assign(form, blank());
  formError.value = null;
  touched.value = false;
  modalOpen.value = true;
}

function closeModal(): void {
  if (!submitting.value) modalOpen.value = false;
}

const validationError = computed<string | null>(() => {
  if (form.name.trim().length === 0) return 'Name is required.';
  if (form.subject.trim().length === 0) return 'Subject is required.';
  if (form.body.trim().length === 0) return 'Body is required.';
  const link = form.link.trim();
  if (link.length > 0 && !/^(https?:\/\/|\/)/.test(link)) {
    return 'Link must start with http(s):// or /.';
  }
  return null;
});

async function submit(): Promise<void> {
  touched.value = true;
  if (validationError.value !== null || submitting.value) return;
  const body: CampaignRequest = {
    name: form.name.trim(),
    channel: form.channel,
    audience: form.audience,
    subject: form.subject.trim(),
    body: form.body,
    link: form.link.trim() || undefined,
  };
  submitting.value = true;
  formError.value = null;
  try {
    await createCampaign(body);
    modalOpen.value = false;
    void load();
  } catch (e: unknown) {
    formError.value = errorInfo(e);
  } finally {
    submitting.value = false;
  }
}

/** Preview renders the body as plain text (never HTML). */
const previewing = ref<Campaign | null>(null);

const sending = ref<Campaign | null>(null);
const sendBusy = ref(false);

const sendMessage = computed<string>(() =>
  sending.value
    ? `Send "${sending.value.name}" to ${sending.value.audience} via ${sending.value.channel}? This cannot be undone.`
    : '',
);

async function confirmSend(): Promise<void> {
  const target = sending.value;
  if (!target) return;
  sendBusy.value = true;
  actionError.value = null;
  try {
    await sendCampaign(target.id);
    sending.value = null;
    void load();
  } catch (e: unknown) {
    sending.value = null;
    actionError.value = errorInfo(e);
  } finally {
    sendBusy.value = false;
  }
}

onMounted(() => {
  void load();
});
</script>

<template>
  <section>
    <div class="mb-4 flex items-baseline justify-between">
      <h1 class="text-2xl font-semibold">Campaigns</h1>
      <div class="flex gap-2">
        <button class="btn btn-outline btn-sm" type="button" :disabled="loading" @click="load()">
          Refresh
        </button>
        <button class="btn btn-primary btn-sm" type="button" data-test="campaign-new" @click="openCreate">
          New campaign
        </button>
      </div>
    </div>

    <ProblemAlert
      v-if="actionError"
      class="mb-4"
      :message="actionError.message"
      :correlation-id="actionError.correlationId"
    />

    <div v-if="loading" class="data-card p-8 text-center" role="status" aria-live="polite">
      <span class="loading loading-spinner loading-lg" aria-hidden="true" />
      <p class="mt-2">Loading campaigns…</p>
    </div>

    <div v-else-if="error">
      <ProblemAlert :message="error.message" :correlation-id="error.correlationId" />
      <button class="btn btn-outline mt-4" type="button" @click="load()">Try again</button>
    </div>

    <div v-else-if="campaigns.length === 0" class="data-card p-10 text-center">
      <h2 class="text-lg font-medium">No campaigns yet</h2>
    </div>

    <div v-else class="data-card p-0">
      <div class="overflow-x-auto">
        <table class="table-minimal table">
          <caption class="sr-only">Campaigns</caption>
          <thead>
            <tr>
              <th scope="col">Name</th>
              <th scope="col">Channel</th>
              <th scope="col">Audience</th>
              <th scope="col">Status</th>
              <th scope="col" class="text-right">Sent</th>
              <th scope="col">Sent at</th>
              <th scope="col" class="text-right">Actions</th>
            </tr>
          </thead>
          <tbody>
            <tr v-for="c in campaigns" :key="c.id" data-test="campaign-row">
              <td>
                <div class="text-sm font-semibold">{{ c.name }}</div>
                <div class="text-xs opacity-70">{{ c.subject }}</div>
              </td>
              <td class="text-sm">{{ c.channel }}</td>
              <td class="text-sm">{{ c.audience }}</td>
              <td>
                <span class="badge badge-sm" :class="c.status === 'SENT' ? 'badge-success' : c.status === 'SENDING' ? 'badge-warning' : 'badge-ghost'">
                  {{ c.status }}
                </span>
              </td>
              <td class="text-right text-sm tabular-nums">{{ c.sentCount }}</td>
              <td class="text-xs">{{ fmtDate(c.sentAt) }}</td>
              <td class="text-right">
                <div class="flex justify-end gap-2">
                  <button class="btn btn-outline btn-xs" type="button" @click="previewing = c">Preview</button>
                  <button
                    v-if="c.status === 'DRAFT'"
                    class="btn btn-outline btn-primary btn-xs"
                    type="button"
                    data-test="campaign-send"
                    @click="sending = c"
                  >
                    Send
                  </button>
                </div>
              </td>
            </tr>
          </tbody>
        </table>
      </div>
    </div>

    <!-- create modal -->
    <div v-if="modalOpen" class="modal modal-open" role="dialog" aria-modal="true" data-test="campaign-modal">
      <div class="modal-box data-card-elevated">
        <h3 class="text-lg font-semibold">New campaign</h3>
        <ProblemAlert
          v-if="formError"
          class="mt-3"
          :message="formError.message"
          :correlation-id="formError.correlationId"
        />
        <form class="mt-3 grid grid-cols-2 gap-3" novalidate @submit.prevent="submit">
          <label class="form-control col-span-2">
            <span class="label-text">Name (internal)</span>
            <input v-model="form.name" class="input input-bordered w-full" maxlength="120" data-test="campaign-name" />
          </label>
          <label class="form-control">
            <span class="label-text">Channel</span>
            <select v-model="form.channel" class="select select-bordered w-full">
              <option value="ALL">All channels</option>
              <option value="EMAIL">Email</option>
              <option value="PUSH">Push</option>
              <option value="INBOX">Inbox</option>
            </select>
          </label>
          <label class="form-control">
            <span class="label-text">Audience</span>
            <select v-model="form.audience" class="select select-bordered w-full">
              <option value="ALL">All customers</option>
              <option value="MEMBERS">Members</option>
              <option value="INACTIVE_30D">Inactive 30 days</option>
            </select>
          </label>
          <label class="form-control col-span-2">
            <span class="label-text">Subject / title</span>
            <input v-model="form.subject" class="input input-bordered w-full" maxlength="200" data-test="campaign-subject" />
          </label>
          <label class="form-control col-span-2">
            <span class="label-text">Body (markdown-lite)</span>
            <textarea v-model="form.body" class="textarea textarea-bordered w-full" rows="6" data-test="campaign-body" />
          </label>
          <label class="form-control col-span-2">
            <span class="label-text">Link <span class="opacity-60">(optional)</span></span>
            <input v-model="form.link" class="input input-bordered w-full" placeholder="https://... or /deals" />
          </label>
          <p v-if="touched && validationError" class="col-span-2 text-sm text-error" data-test="campaign-validation">
            {{ validationError }}
          </p>
          <div class="modal-action col-span-2">
            <button class="btn btn-ghost" type="button" :disabled="submitting" @click="closeModal">Cancel</button>
            <button class="btn btn-primary" type="submit" :disabled="submitting" data-test="campaign-submit">
              <span v-if="submitting" class="loading loading-spinner loading-sm" aria-hidden="true" />
              Save draft
            </button>
          </div>
        </form>
      </div>
      <button class="modal-backdrop" type="button" aria-label="Close dialog" @click="closeModal" />
    </div>

    <!-- preview modal -->
    <div v-if="previewing" class="modal modal-open" role="dialog" aria-modal="true" data-test="campaign-preview">
      <div class="modal-box data-card-elevated">
        <h3 class="text-lg font-semibold">Preview</h3>
        <p class="mt-1 text-xs opacity-70">{{ previewing.channel }} to {{ previewing.audience }}</p>
        <div class="data-card mt-3 p-4">
          <div class="font-semibold">{{ previewing.subject }}</div>
          <p class="mt-2 whitespace-pre-wrap text-sm">{{ previewing.body }}</p>
          <p v-if="previewing.link" class="mt-3 break-all text-xs text-primary">{{ previewing.link }}</p>
        </div>
        <div class="modal-action">
          <button class="btn" type="button" @click="previewing = null">Close</button>
        </div>
      </div>
      <button class="modal-backdrop" type="button" aria-label="Close dialog" @click="previewing = null" />
    </div>

    <ConfirmDialog
      :open="sending !== null"
      title="Send campaign"
      :message="sendMessage"
      confirm-label="Send"
      :submitting="sendBusy"
      @confirm="confirmSend"
      @cancel="sending = null"
    />
  </section>
</template>
