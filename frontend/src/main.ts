import { createApp } from 'vue'
import App from './App.vue'
import { router } from './router'
import './styles/tokens.css'
import './styles/base.css'

if (import.meta.env.VITE_ROUTER_MODE === 'memory') router.push('/login')
createApp(App).use(router).mount('#app')
