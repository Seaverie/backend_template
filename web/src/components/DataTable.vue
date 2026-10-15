<script setup lang="ts">
// A table on wide screens; a list of cards on phones (each column becomes a "label: value" line).
// Cell content comes from `#cell-<key>` slots, falling back to the raw value.
export interface Column { key: string; label: string; width?: string; align?: 'left' | 'right'; primary?: boolean; hideOnMobile?: boolean }
// Rows are API objects; slots receive them untyped (`any`) so views can read any field.
type Row = any
defineProps<{ columns: Column[]; rows: Row[]; rowKey: string; selected?: unknown; empty?: string; clickable?: boolean }>()
defineEmits<{ select: [row: Row] }>()
defineSlots<Record<string, (p: { row: Row }) => any>>()
</script>

<template>
  <div class="table-wrap">
    <table class="table">
      <thead>
        <tr><th v-for="c in columns" :key="c.key" :style="{ width: c.width, textAlign: c.align ?? 'left' }" scope="col">{{ c.label }}</th></tr>
      </thead>
      <tbody>
        <tr v-for="row in rows" :key="String(row[rowKey])" :class="{ selected: selected === row[rowKey], clickable }"
            :tabindex="clickable ? 0 : undefined" @click="clickable && $emit('select', row)" @keydown.enter="clickable && $emit('select', row)">
          <td v-for="c in columns" :key="c.key" :style="{ textAlign: c.align ?? 'left' }" :data-label="c.label"
              :class="{ primary: c.primary, 'hide-mobile': c.hideOnMobile }">
            <div class="cell"><slot :name="`cell-${c.key}`" :row="row">{{ row[c.key] }}</slot></div>
          </td>
        </tr>
        <tr v-if="!rows.length"><td :colspan="columns.length" class="empty">{{ empty ?? 'Nothing here yet.' }}</td></tr>
      </tbody>
    </table>
  </div>
</template>

<style scoped>
.table-wrap { width: 100%; overflow-x: auto; }
.table { width: 100%; border-collapse: collapse; }
th { background: var(--bg-subtle); color: var(--text-secondary); font-size: 12px; font-weight: 500; padding: 12px 16px; white-space: nowrap; }
td { padding: 12px 16px; border-top: 1px solid var(--border-default); vertical-align: middle; }
tr.clickable { cursor: pointer; }
tr.clickable:hover td { background: var(--bg-canvas); }
tr.selected td, tr.selected:hover td { background: var(--brand-soft); }
.empty { text-align: center; color: var(--text-secondary); padding: 32px 16px; }

@media (max-width: 767px) {
  thead { position: absolute; width: 1px; height: 1px; overflow: hidden; clip: rect(0 0 0 0); }
  table, tbody, tr, td { display: block; width: 100%; }
  tr { padding: 12px 16px; border-top: 1px solid var(--border-default); }
  td { border: 0; padding: 3px 0; display: flex; justify-content: space-between; align-items: center; gap: 12px; text-align: right !important; }
  td::before { content: attr(data-label); color: var(--text-secondary); font-size: 12px; text-align: left; flex: 0 0 auto; }
  td .cell { display: flex; flex-direction: column; align-items: flex-end; gap: 2px; min-width: 0; overflow-wrap: anywhere; }
  td[data-label=''] { justify-content: flex-end; }
  td.primary { display: block; text-align: left !important; padding-bottom: 6px; }
  td.primary .cell { align-items: stretch; }
  td.primary::before, td:empty::before, td[data-label='']::before { content: none; }
  td.hide-mobile { display: none; }
  tr.selected { background: var(--brand-soft); }
}
</style>
