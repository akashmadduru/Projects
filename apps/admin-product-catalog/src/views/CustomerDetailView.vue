<script setup lang="ts">
import { computed, onMounted, ref, watch } from 'vue';
import { RouterLink } from 'vue-router';

import ConfirmDialog from '@/components/ConfirmDialog.vue';
import ProblemAlert from '@/components/ProblemAlert.vue';
import {
  errorInfo,
  fmtDate,
  getUser,
  listOrdersForUser,
  money,
  setUserRoles,
  setUserStatus,
  statusBadge,
} from '@/lib/adminApi';
import { useAuthStore } from '@/stores/auth';
import { ASSIGNABLE_ROLES, type AdminOrderSummary, type AdminUser } from '@/types/commerce';

/**
 * One account: profile, status, roles, order history. The server enforces
 * every safety rule (no self-change, never zero active admins, sessions
 * revoked on disable/demotion); the UI mirrors them only to avoid offering
 * actions that will be refused.
 */
const props = defineProps<{ id: string }>();

type ErrorInfo = { message: string; correlationId: string | null };

const auth = useAuthStore();
const user = ref<AdminUser | null>(null);
const loading = ref(false);
const error = ref<ErrorInfo | null>(null);
const actionError = ref<ErrorInfo | null>(null);
const notice = ref('');

const orders = ref<readonly AdminOrderSummary[]>([]);
const ordersTotal = ref(0);
const ordersError = ref<ErrorInfo | null>(null);

const roleDraft = ref<string[]>([]);
const savingRoles = ref(false);
const confirmStatus = ref<'ACTIVE' | 'DISABLED' | null>(null);
const savingStatus = ref(false);

const isSelf = computed<boolean>(() => user.value !== null && user.value.id === auth.user?.id);
const rolesDirty = computed<boolean>(() => {
  const a = [...(user.value?.roles ?? [])].sort().join(',');
  const b = [...roleDraft.value].sort().join(',');
  return a !== b;
});

async function load(): Promise<void> {
  loading.value = true;
  error.value = null;
  try {
    user.value = await getUser(props.id);
    roleDraft.value = [...user.value.roles];
  } catch (e: unknown) {
    error.value = errorInfo(e);
    user.value = null;
  } finally {
    loading.value = false;
  }
  try {
    const res = await listOrdersForUser(props.id, 0, 10);
    orders.value = res.content;
    ordersTotal.value = res.page.totalElements;
    ordersError.value = null;
  } catch (e: unknown) {
    ordersError.value = errorInfo(e);
  }
}

async function saveRoles(): Promise<void> {
  if (!user.value || roleDraft.value.length === 0) return;
  savingRoles.value = true;
  actionError.value = null;
  notice.value = '';
  try {
    user.value = await setUserRoles(user.value.id, roleDraft.value);
    roleDraft.value = [...user.value.roles];
    notice.value = 'Roles updated. Removed roles take effect when the user’s current session expires.';
  } catch (e: unknown) {
    actionError.value = errorInfo(e);
  } finally {
    savingRoles.value = false;
  }
}

async function applyStatus(): Promise<void> {
  if (!user.value || !confirmStatus.value) return;
  savingStatus.value = true;
  actionError.value = null;
  notice.value = '';
  try {
    user.value = await setUserStatus(user.value.id, confirmStatus.value);
    notice.value =
      confirmStatus.value === 'DISABLED'
        ? 'Account disabled and all sessions revoked.'
        : 'Account re-enabled.';
  } catch (e: unknown) {
    actionError.value = errorInfo(e);
  } finally {
    savingStatus.value = false;
    confirmStatus.value = null;
  }
}

onMounted(load);
watch(() => props.id, load);
</script>

<template>
  <section class="flex flex-col gap-4">
    <RouterLink to="/customers" class="link link-hover text-sm opacity-70">← Customers</RouterLink>

    <div v-if="loading" class="data-card p-8 text-center" role="status" aria-live="polite">
      <span class="loading loading-spinner loading-lg" aria-hidden="true" />
    </div>

    <div v-else-if="error">
      <ProblemAlert :message="error.message" :correlation-id="error.correlationId" />
      <button class="btn btn-outline mt-4" type="button" @click="load()">Try again</button>
    </div>

    <template v-else-if="user">
      <div class="flex flex-wrap items-baseline justify-between gap-3">
        <div>
          <h1 class="text-2xl font-semibold">{{ user.displayName || user.email }}</h1>
          <p class="text-sm opacity-70">{{ user.email }}</p>
        </div>
        <span class="badge" :class="statusBadge(user.status)" data-test="customer-status">{{ user.status }}</span>
      </div>

      <ProblemAlert v-if="actionError" :message="actionError.message" :correlation-id="actionError.correlationId" />
      <div v-if="notice" class="alert alert-success text-sm" role="status">{{ notice }}</div>
      <div v-if="isSelf" class="alert alert-info text-sm">
        This is your own account. Status and roles can only be changed by another administrator.
      </div>

      <div class="grid grid-cols-1 gap-4 lg:grid-cols-3">
        <div class="data-card">
          <h2 class="mb-3 text-sm font-medium uppercase tracking-wide opacity-60">Profile</h2>
          <dl class="flex flex-col gap-2 text-sm">
            <div class="flex justify-between gap-4"><dt class="opacity-70">Email verified</dt><dd>{{ user.emailVerified ? 'Yes' : 'No' }}</dd></div>
            <div class="flex justify-between gap-4">
              <dt class="opacity-70">Phone</dt>
              <dd>{{ user.phone ?? '—' }}<span v-if="user.phone && !user.phoneVerified" class="opacity-60"> (unverified)</span></dd>
            </div>
            <div class="flex justify-between gap-4"><dt class="opacity-70">Joined</dt><dd>{{ fmtDate(user.createdAt) }}</dd></div>
            <div class="flex justify-between gap-4"><dt class="opacity-70">Updated</dt><dd>{{ fmtDate(user.updatedAt) }}</dd></div>
            <div class="flex justify-between gap-4"><dt class="opacity-70">ID</dt><dd class="font-mono text-xs">{{ user.id }}</dd></div>
          </dl>
        </div>

        <div class="data-card">
          <h2 class="mb-3 text-sm font-medium uppercase tracking-wide opacity-60">Roles</h2>
          <div class="flex flex-col gap-2">
            <label v-for="r in ASSIGNABLE_ROLES" :key="r" class="label cursor-pointer justify-start gap-3">
              <input
                v-model="roleDraft"
                type="checkbox"
                class="checkbox checkbox-sm"
                :value="r"
                :disabled="isSelf || savingRoles"
                :data-test="`role-${r}`"
              />
              <span class="label-text">{{ r }}</span>
            </label>
          </div>
          <p v-if="roleDraft.length === 0" class="mt-2 text-xs text-error">At least one role is required.</p>
          <button
            class="btn btn-primary btn-sm mt-3"
            type="button"
            data-test="roles-save"
            :disabled="isSelf || !rolesDirty || roleDraft.length === 0 || savingRoles"
            @click="saveRoles"
          >
            <span v-if="savingRoles" class="loading loading-spinner loading-xs" aria-hidden="true" />
            Save roles
          </button>
        </div>

        <div class="data-card">
          <h2 class="mb-3 text-sm font-medium uppercase tracking-wide opacity-60">Access</h2>
          <p class="text-sm opacity-80">
            Disabling blocks sign-in and revokes every refresh token. Tokens already issued stop working
            within the access-token lifetime.
          </p>
          <button
            v-if="user.status === 'ACTIVE'"
            class="btn btn-outline btn-error btn-sm mt-3"
            type="button"
            data-test="customer-disable"
            :disabled="isSelf || savingStatus"
            @click="confirmStatus = 'DISABLED'"
          >
            Disable account
          </button>
          <button
            v-else
            class="btn btn-primary btn-sm mt-3"
            type="button"
            data-test="customer-enable"
            :disabled="isSelf || savingStatus"
            @click="confirmStatus = 'ACTIVE'"
          >
            {{ user.status === 'LOCKED' ? 'Unlock account' : 'Enable account' }}
          </button>
        </div>
      </div>

      <div class="data-card p-0">
        <div class="flex items-baseline justify-between p-4 pb-2">
          <h2 class="text-sm font-medium uppercase tracking-wide opacity-60">Orders ({{ ordersTotal }})</h2>
        </div>
        <div v-if="ordersError" class="p-4">
          <ProblemAlert :message="ordersError.message" :correlation-id="ordersError.correlationId" />
        </div>
        <p v-else-if="orders.length === 0" class="p-4 text-sm opacity-70">No orders.</p>
        <table v-else class="table-minimal table table-sm">
          <tbody>
            <tr v-for="o in orders" :key="o.id">
              <td class="font-mono text-xs">{{ o.id.slice(0, 8) }}</td>
              <td><span class="badge badge-sm" :class="statusBadge(o.status)">{{ o.status }}</span></td>
              <td class="text-xs">{{ o.paymentMethod }} · {{ o.paymentStatus }}</td>
              <td class="text-right tabular-nums">{{ money(o.totalAmount, o.currency) }}</td>
              <td class="text-xs opacity-70">{{ fmtDate(o.placedAt) }}</td>
            </tr>
          </tbody>
        </table>
      </div>
    </template>

    <ConfirmDialog
      :open="confirmStatus !== null"
      :title="confirmStatus === 'DISABLED' ? 'Disable this account?' : 'Re-enable this account?'"
      :message="
        confirmStatus === 'DISABLED'
          ? `${user?.email ?? ''} will be signed out everywhere and unable to sign in.`
          : `${user?.email ?? ''} will be able to sign in again.`
      "
      :confirm-label="confirmStatus === 'DISABLED' ? 'Disable' : 'Enable'"
      :destructive="confirmStatus === 'DISABLED'"
      :submitting="savingStatus"
      @confirm="applyStatus"
      @cancel="confirmStatus = null"
    />
  </section>
</template>
