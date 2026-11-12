<script setup lang="ts">
import { computed, onMounted, reactive, ref } from 'vue';

import ProblemAlert from '@/components/ProblemAlert.vue';
import { errorInfo } from '@/lib/adminApi';
import { adminCreateWarehouse, adminListWarehouses, adminUpdateWarehouse } from '@/lib/sellerApi';
import type { Warehouse } from '@/types/seller';

/** Warehouses admin: list, create, edit/activate/deactivate. */
type Err = { message: string; correlationId: string | null };
const warehouses = ref<readonly Warehouse[]>([]);
const loading = ref(false);
const error = ref<Err | null>(null);

async function load(): Promise<void> {
  loading.value = true;
  error.value = null;
  try {
    warehouses.value = await adminListWarehouses();
  } catch (e: unknown) {
    error.value = errorInfo(e);
    warehouses.value = [];
  } finally {
    loading.value = false;
  }
}

const modalOpen = ref(false);
const editing = ref<Warehouse | null>(null);
const submitting = ref(false);
const formError = ref<Err | null>(null);
const touched = ref(false);
const form = reactive({ code: '', name: '', pincode: '', state: '', active: true });

function openModal(w: Warehouse | null): void {
  editing.value = w;
  formError.value = null;
  touched.value = false;
  Object.assign(form, {
    code: w?.code ?? '',
    name: w?.name ?? '',
    pincode: w?.pincode ?? '',
    state: w?.state ?? '',
    active: w?.active ?? true,
  });
  modalOpen.value = true;
}

const validationError = computed<string | null>(() => {
  if (!/^[A-Za-z0-9-]{2,20}$/.test(form.code.trim())) return 'Code must be 2-20 letters, digits or dashes.';
  if (form.name.trim().length === 0) return 'Name is required.';
  if (!/^[0-9]{6}$/.test(form.pincode.trim())) return 'Pincode must be 6 digits.';
  if (form.state.trim().length === 0) return 'State is required.';
  return null;
});

async function submit(): Promise<void> {
  touched.value = true;
  if (validationError.value !== null || submitting.value) return;
  const body = {
    code: form.code.trim().toUpperCase(),
    name: form.name.trim(),
    pincode: form.pincode.trim(),
    state: form.state.trim().toUpperCase(),
    active: form.active,
  };
  submitting.value = true;
  formError.value = null;
  try {
    if (editing.value) await adminUpdateWarehouse(editing.value.id, body);
    else await adminCreateWarehouse(body);
    modalOpen.value = false;
    void load();
  } catch (e: unknown) {
    formError.value = errorInfo(e);
  } finally {
    submitting.value = false;
  }
}

onMounted(() => {
  void load();
});
</script>

<template>
  <section>
    <div class="mb-4 flex items-baseline justify-between">
      <h1 class="text-2xl font-semibold">Warehouses</h1>
      <button class="btn btn-primary btn-sm" type="button" data-test="wh-new" @click="openModal(null)">New warehouse</button>
    </div>

    <div v-if="loading" class="data-card p-8 text-center" role="status">
      <span class="loading loading-spinner loading-lg" aria-hidden="true" />
    </div>
    <div v-else-if="error">
      <ProblemAlert :message="error.message" :correlation-id="error.correlationId" />
      <button class="btn btn-outline mt-4" type="button" @click="load()">Try again</button>
    </div>
    <div v-else-if="warehouses.length === 0" class="data-card p-10 text-center">
      <h2 class="text-lg font-medium">No warehouses</h2>
    </div>
    <div v-else class="data-card p-0">
      <div class="overflow-x-auto">
        <table class="table-minimal table">
          <caption class="sr-only">Warehouses</caption>
          <thead>
            <tr>
              <th scope="col">Code</th><th scope="col">Name</th><th scope="col">Pincode</th><th scope="col">State</th>
              <th scope="col">Owner</th><th scope="col">Status</th><th scope="col" class="text-right">Actions</th>
            </tr>
          </thead>
          <tbody>
            <tr v-for="w in warehouses" :key="w.id" data-test="wh-row">
              <td class="font-mono text-sm font-semibold">{{ w.code }}</td>
              <td class="text-sm">{{ w.name }}</td>
              <td class="tabular-nums text-sm">{{ w.pincode }}</td>
              <td class="text-sm">{{ w.state }}</td>
              <td class="text-xs">{{ w.sellerId ? w.sellerId.slice(0, 8) : 'Platform' }}</td>
              <td><span class="badge badge-sm" :class="w.active ? 'badge-success' : 'badge-ghost'">{{ w.active ? 'Active' : 'Inactive' }}</span></td>
              <td class="text-right"><button class="btn btn-outline btn-xs" type="button" @click="openModal(w)">Edit</button></td>
            </tr>
          </tbody>
        </table>
      </div>
    </div>

    <div v-if="modalOpen" class="modal modal-open" role="dialog" aria-modal="true" data-test="wh-modal">
      <div class="modal-box data-card-elevated">
        <h3 class="text-lg font-semibold">{{ editing ? 'Edit warehouse' : 'New warehouse' }}</h3>
        <ProblemAlert v-if="formError" class="mt-3" :message="formError.message" :correlation-id="formError.correlationId" />
        <form class="mt-3 grid grid-cols-2 gap-3" novalidate @submit.prevent="submit">
          <label class="form-control">
            <span class="label-text">Code</span>
            <input v-model="form.code" class="input input-bordered w-full uppercase" maxlength="20" :disabled="editing !== null" data-test="wh-code" />
          </label>
          <label class="form-control">
            <span class="label-text">Name</span>
            <input v-model="form.name" class="input input-bordered w-full" maxlength="120" />
          </label>
          <label class="form-control">
            <span class="label-text">Pincode</span>
            <input v-model="form.pincode" class="input input-bordered w-full" inputmode="numeric" maxlength="6" />
          </label>
          <label class="form-control">
            <span class="label-text">State (code)</span>
            <input v-model="form.state" class="input input-bordered w-full uppercase" maxlength="40" />
          </label>
          <label class="col-span-2 flex items-center gap-2">
            <input v-model="form.active" type="checkbox" class="checkbox checkbox-sm" />
            <span class="label-text">Active</span>
          </label>
          <p v-if="touched && validationError" class="col-span-2 text-sm text-error" data-test="wh-validation">{{ validationError }}</p>
          <div class="modal-action col-span-2">
            <button class="btn btn-ghost" type="button" :disabled="submitting" @click="modalOpen = false">Cancel</button>
            <button class="btn btn-primary" type="submit" :disabled="submitting" data-test="wh-submit">
              <span v-if="submitting" class="loading loading-spinner loading-sm" aria-hidden="true" />
              Save
            </button>
          </div>
        </form>
      </div>
      <button class="modal-backdrop" type="button" aria-label="Close dialog" @click="modalOpen = false" />
    </div>
  </section>
</template>
