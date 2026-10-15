<script setup lang="ts">
import { ref } from 'vue'
import { api } from '@/api/client'
import { useLoader } from '@/composables'
import LoadState from '@/components/LoadState.vue'
import UiBadge from '@/components/UiBadge.vue'
import UiCard from '@/components/UiCard.vue'

const items = ref<any[]>([])
const { loading, error, reload } = useLoader(async () => { items.value = (await api.get('/modules')).items })
</script>

<template>
  <UiCard title="Modules" caption="Parts of the system switched on for this workspace. Ask your platform administrator to change them.">
    <LoadState :loading="loading" :error="error" :ready="items.length > 0" @retry="reload">
      <div class="mods">
        <div v-for="m in items" :key="m.code" class="mod" :class="{ off: !m.enabled }">
          <div><strong>{{ m.label }}</strong><p class="caption">{{ m.description }}</p></div>
          <UiBadge :tone="m.enabled ? 'success' : 'neutral'">{{ m.enabled ? 'On' : 'Off' }}</UiBadge>
        </div>
      </div>
    </LoadState>
  </UiCard>
</template>

<style scoped>
.mods { display: grid; grid-template-columns: repeat(auto-fill, minmax(260px, 1fr)); gap: 12px; padding: 0 24px 24px; }
.mod { display: flex; gap: 12px; justify-content: space-between; align-items: flex-start; padding: 14px; border: 1px solid var(--border-default); border-radius: var(--radius-md); }
.mod.off { opacity: .6; }
</style>
