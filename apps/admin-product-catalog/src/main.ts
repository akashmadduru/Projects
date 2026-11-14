import { createApp } from 'vue';
import { createPinia } from 'pinia';

import App from '@/App.vue';
import { bootstrapBranding } from '@/composables/useBranding';
import { createAppRouter } from '@/router';
import '@/style.css';

const app = createApp(App);

// Pinia before the router: the navigation guard resolves the auth store on the
// very first navigation, which happens during router installation.
app.use(createPinia());
app.use(createAppRouter());

// Paint cached branding before mount; the network reconcile continues in the background.
void bootstrapBranding();

app.mount('#app');
