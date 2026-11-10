import { createApp } from 'vue';
import { createPinia } from 'pinia';

import App from '@/App.vue';
import { createAppRouter } from '@/router';
import '@/style.css';

const app = createApp(App);

// Pinia before the router: the navigation guard resolves the auth store on the
// very first navigation, which happens during router installation.
app.use(createPinia());
app.use(createAppRouter());

app.mount('#app');
