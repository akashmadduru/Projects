<script setup lang="ts">
import { computed, onMounted, reactive, ref } from 'vue';

import ConfirmDialog from '@/components/ConfirmDialog.vue';
import ProblemAlert from '@/components/ProblemAlert.vue';
import {
  createCoupon,
  deactivateCoupon,
  errorInfo,
  fmtDate,
  listCoupons,
  updateCoupon,
} from '@/lib/adminApi';
import type { Coupon, CouponRequest, CouponType } from '@/types/commerce';

/** Coupon admin: list, create/edit (modal), deactivate. */
const coupons = ref<readonly Coupon[]>([]);
const loading = ref(false);
const error = ref<{ message: string; correlationId: string | null } | null>(null);

async function load(): Promise<void> {
  loading.value = true;
  error.value = null;
  try {
    coupons.value = await listCoupons();
  } catch (e: unknown) {
    error.value = errorInfo(e);
    coupons.value = [];
  } finally {
    loading.value = false;
  }
}

interface FormState {
  code: string;
  description: string;
  type: CouponType;
  value: string;
  maxDiscount: string;
  minOrderAmount: string;
  currency: string;
  startsAt: string;
  endsAt: string;
  totalLimit: string;
  perUserLimit: string;
  firstOrderOnly: boolean;
}

function blank(): FormState {
  return {
    code: '',
    description: '',
    type: 'PERCENT',
    value: '',
    maxDiscount: '',
    minOrderAmount: '',
    currency: '',
    startsAt: '',
    endsAt: '',
    totalLimit: '',
    perUserLimit: '',
    firstOrderOnly: false,
  };
}

/** ISO instant -> value for <input type="datetime-local"> (local time). */
function toLocalInput(iso: string | null): string {
  if (!iso) return '';
  const d = new Date(iso);
  const pad = (n: number): string => String(n).padStart(2, '0');
  return `${d.getFullYear()}-${pad(d.getMonth() + 1)}-${pad(d.getDate())}T${pad(d.getHours())}:${pad(d.getMinutes())}`;
}

const modalOpen = ref(false);
const editing = ref<Coupon | null>(null);
const form = reactive<FormState>(blank());
const submitting = ref(false);
const formError = ref<{ message: string; correlationId: string | null } | null>(null);
const touched = ref(false);

function openCreate(): void {
  editing.value = null;
  Object.assign(form, blank());
  formError.value = null;
  touched.value = false;
  modalOpen.value = true;
}

function openEdit(c: Coupon): void {
  editing.value = c;
  Object.assign(form, {
    code: c.code,
    description: c.description ?? '',
    type: c.type,
    value: String(c.value),
    maxDiscount: c.maxDiscount === null ? '' : String(c.maxDiscount),
    minOrderAmount: c.minOrderAmount === null ? '' : String(c.minOrderAmount),
    currency: c.currency ?? '',
    startsAt: toLocalInput(c.startsAt),
    endsAt: toLocalInput(c.endsAt),
    totalLimit: c.totalLimit === null ? '' : String(c.totalLimit),
    perUserLimit: c.perUserLimit === null ? '' : String(c.perUserLimit),
    firstOrderOnly: c.firstOrderOnly,
  } satisfies FormState);
  formError.value = null;
  touched.value = false;
  modalOpen.value = true;
}

function closeModal(): void {
  if (!submitting.value) modalOpen.value = false;
}

function optNum(v: string): number | undefined {
  const t = v.trim();
  return t.length === 0 ? undefined : Number(t);
}

function optDate(v: string): string | undefined {
  return v ? new Date(v).toISOString() : undefined;
}

const validationError = computed<string | null>(() => {
  if (form.code.trim().length === 0) return 'Code is required.';
  const value = Number(form.value);
  if (form.value.trim().length === 0 || !Number.isFinite(value) || value <= 0) {
    return 'Value must be a positive number.';
  }
  if (form.type === 'PERCENT' && value > 100) return 'Percent value cannot exceed 100.';
  if (form.type === 'FLAT' && form.currency.trim().length !== 3) {
    return 'Flat coupons require a 3-letter currency code.';
  }
  if (form.currency.trim().length > 0 && form.currency.trim().length !== 3) {
    return 'Currency must be a 3-letter code.';
  }
  for (const [label, v] of [
    ['Max discount', form.maxDiscount],
    ['Min order amount', form.minOrderAmount],
    ['Total limit', form.totalLimit],
    ['Per-user limit', form.perUserLimit],
  ] as const) {
    const n = optNum(v);
    if (n !== undefined && (!Number.isFinite(n) || n < 0)) return `${label} must be a non-negative number.`;
  }
  if (form.startsAt && form.endsAt && new Date(form.endsAt) <= new Date(form.startsAt)) {
    return 'End date must be after the start date.';
  }
  return null;
});

async function submit(): Promise<void> {
  touched.value = true;
  if (validationError.value !== null || submitting.value) return;
  const body: CouponRequest = {
    code: form.code.trim().toUpperCase(),
    description: form.description.trim() || undefined,
    type: form.type,
    value: Number(form.value),
    maxDiscount: optNum(form.maxDiscount),
    minOrderAmount: optNum(form.minOrderAmount),
    currency: form.currency.trim().toUpperCase() || undefined,
    startsAt: optDate(form.startsAt),
    endsAt: optDate(form.endsAt),
    totalLimit: optNum(form.totalLimit),
    perUserLimit: optNum(form.perUserLimit),
    firstOrderOnly: form.firstOrderOnly,
  };
  submitting.value = true;
  formError.value = null;
  try {
    if (editing.value) {
      await updateCoupon(editing.value.id, body);
    } else {
      await createCoupon(body);
    }
    modalOpen.value = false;
    void load();
  } catch (e: unknown) {
    formError.value = errorInfo(e);
  } finally {
    submitting.value = false;
  }
}

const deactivating = ref<Coupon | null>(null);
const deactivateBusy = ref(false);
const deactivateError = ref<{ message: string; correlationId: string | null } | null>(null);

async function confirmDeactivate(): Promise<void> {
  const target = deactivating.value;
  if (!target) return;
  deactivateBusy.value = true;
  deactivateError.value = null;
  try {
    await deactivateCoupon(target.id);
    deactivating.value = null;
    void load();
  } catch (e: unknown) {
    deactivating.value = null;
    deactivateError.value = errorInfo(e);
  } finally {
    deactivateBusy.value = false;
  }
}

function valueLabel(c: Coupon): string {
  return c.type === 'PERCENT' ? `${c.value}%` : `${c.value} ${c.currency ?? ''}`.trim();
}

onMounted(() => {
  void load();
});
</script>

<template>
  <section>
    <div class="mb-4 flex items-baseline justify-between">
      <h1 class="text-2xl font-semibold">Coupons</h1>
      <button class="btn btn-primary btn-sm" type="button" data-test="coupon-new" @click="openCreate">
        New coupon
      </button>
    </div>

    <ProblemAlert
      v-if="deactivateError"
      class="mb-4"
      :message="deactivateError.message"
      :correlation-id="deactivateError.correlationId"
    />

    <div v-if="loading" class="data-card p-8 text-center" role="status" aria-live="polite">
      <span class="loading loading-spinner loading-lg" aria-hidden="true" />
      <p class="mt-2">Loading coupons…</p>
    </div>

    <div v-else-if="error">
      <ProblemAlert :message="error.message" :correlation-id="error.correlationId" />
      <button class="btn btn-outline mt-4" type="button" @click="load()">Try again</button>
    </div>

    <div v-else-if="coupons.length === 0" class="data-card p-10 text-center">
      <h2 class="text-lg font-medium">No coupons yet</h2>
    </div>

    <div v-else class="data-card p-0">
      <div class="overflow-x-auto">
        <table class="table-minimal table">
          <caption class="sr-only">Coupons</caption>
          <thead>
            <tr>
              <th scope="col">Code</th>
              <th scope="col">Discount</th>
              <th scope="col">Min order</th>
              <th scope="col">Valid</th>
              <th scope="col">Limits</th>
              <th scope="col">Status</th>
              <th scope="col" class="text-right">Actions</th>
            </tr>
          </thead>
          <tbody>
            <tr v-for="c in coupons" :key="c.id" data-test="coupon-row">
              <td>
                <div class="font-mono text-sm font-semibold">{{ c.code }}</div>
                <div v-if="c.description" class="text-xs opacity-70">{{ c.description }}</div>
              </td>
              <td class="text-sm">
                {{ valueLabel(c) }}
                <div v-if="c.maxDiscount !== null" class="text-xs opacity-70">max {{ c.maxDiscount }}</div>
              </td>
              <td class="text-sm tabular-nums">{{ c.minOrderAmount ?? '-' }}</td>
              <td class="text-xs">{{ fmtDate(c.startsAt) }}<br />→ {{ fmtDate(c.endsAt) }}</td>
              <td class="text-xs">
                total {{ c.totalLimit ?? '∞' }} · per user {{ c.perUserLimit ?? '∞' }}
                <div v-if="c.firstOrderOnly">first order only</div>
              </td>
              <td>
                <span class="badge badge-sm" :class="c.active ? 'badge-success' : 'badge-ghost'">
                  {{ c.active ? 'Active' : 'Inactive' }}
                </span>
              </td>
              <td class="text-right">
                <div class="flex justify-end gap-2">
                  <button class="btn btn-outline btn-xs" type="button" @click="openEdit(c)">Edit</button>
                  <button
                    v-if="c.active"
                    class="btn btn-outline btn-error btn-xs"
                    type="button"
                    data-test="coupon-deactivate"
                    @click="deactivating = c"
                  >
                    Deactivate
                  </button>
                </div>
              </td>
            </tr>
          </tbody>
        </table>
      </div>
    </div>

    <!-- create / edit modal -->
    <div v-if="modalOpen" class="modal modal-open" role="dialog" aria-modal="true" data-test="coupon-modal">
      <div class="modal-box data-card-elevated">
        <h3 class="text-lg font-semibold">{{ editing ? 'Edit coupon' : 'New coupon' }}</h3>
        <ProblemAlert
          v-if="formError"
          class="mt-3"
          :message="formError.message"
          :correlation-id="formError.correlationId"
        />
        <form class="mt-3 grid grid-cols-2 gap-3" novalidate @submit.prevent="submit">
          <label class="form-control col-span-1">
            <span class="label-text">Code</span>
            <input v-model="form.code" class="input input-bordered w-full uppercase" maxlength="40" data-test="coupon-code" />
          </label>
          <label class="form-control col-span-1">
            <span class="label-text">Type</span>
            <select v-model="form.type" class="select select-bordered w-full" data-test="coupon-type">
              <option value="PERCENT">Percent</option>
              <option value="FLAT">Flat</option>
            </select>
          </label>
          <label class="form-control col-span-2">
            <span class="label-text">Description</span>
            <input v-model="form.description" class="input input-bordered w-full" maxlength="255" />
          </label>
          <label class="form-control">
            <span class="label-text">Value {{ form.type === 'PERCENT' ? '(%)' : '(amount)' }}</span>
            <input v-model="form.value" class="input input-bordered w-full" type="number" min="0" step="0.01" data-test="coupon-value" />
          </label>
          <label class="form-control">
            <span class="label-text">Max discount <span class="opacity-60">(optional)</span></span>
            <input v-model="form.maxDiscount" class="input input-bordered w-full" type="number" min="0" step="0.01" />
          </label>
          <label class="form-control">
            <span class="label-text">Min order amount <span class="opacity-60">(optional)</span></span>
            <input v-model="form.minOrderAmount" class="input input-bordered w-full" type="number" min="0" step="0.01" />
          </label>
          <label class="form-control">
            <span class="label-text">
              Currency <span class="opacity-60">({{ form.type === 'FLAT' ? 'required' : 'blank = any' }})</span>
            </span>
            <input v-model="form.currency" class="input input-bordered w-full uppercase" maxlength="3" placeholder="INR" data-test="coupon-currency" />
          </label>
          <label class="form-control">
            <span class="label-text">Starts at</span>
            <input v-model="form.startsAt" class="input input-bordered w-full" type="datetime-local" />
          </label>
          <label class="form-control">
            <span class="label-text">Ends at</span>
            <input v-model="form.endsAt" class="input input-bordered w-full" type="datetime-local" />
          </label>
          <label class="form-control">
            <span class="label-text">Total usage limit</span>
            <input v-model="form.totalLimit" class="input input-bordered w-full" type="number" min="0" step="1" />
          </label>
          <label class="form-control">
            <span class="label-text">Per-user limit</span>
            <input v-model="form.perUserLimit" class="input input-bordered w-full" type="number" min="0" step="1" />
          </label>
          <label class="col-span-2 flex items-center gap-2">
            <input v-model="form.firstOrderOnly" type="checkbox" class="checkbox checkbox-sm" />
            <span class="label-text">First order only</span>
          </label>
          <p v-if="touched && validationError" class="col-span-2 text-sm text-error" data-test="coupon-validation">
            {{ validationError }}
          </p>
          <div class="modal-action col-span-2">
            <button class="btn btn-ghost" type="button" :disabled="submitting" @click="closeModal">Cancel</button>
            <button class="btn btn-primary" type="submit" :disabled="submitting" data-test="coupon-submit">
              <span v-if="submitting" class="loading loading-spinner loading-sm" aria-hidden="true" />
              {{ editing ? 'Save changes' : 'Create coupon' }}
            </button>
          </div>
        </form>
      </div>
      <button class="modal-backdrop" type="button" aria-label="Close dialog" @click="closeModal" />
    </div>

    <ConfirmDialog
      :open="deactivating !== null"
      title="Deactivate coupon"
      :message="deactivating ? `Deactivate ${deactivating.code}? Customers will no longer be able to apply it.` : ''"
      confirm-label="Deactivate"
      :submitting="deactivateBusy"
      @confirm="confirmDeactivate"
      @cancel="deactivating = null"
    />
  </section>
</template>
