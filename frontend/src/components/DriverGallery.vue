<script setup lang="ts">
import { computed, nextTick, onMounted, ref } from 'vue'
import type { Driver } from '@/api/types'
import DriverIcon from './DriverIcon.vue'

/** "Select your database": search, categories and sort, like DBeaver's new connection wizard. */
const props = defineProps<{ drivers: Driver[] }>()
const selected = defineModel<string>({ required: true })
const emit = defineEmits<{ choose: [id: string] }>()

const CATEGORIES: { id: string; label: string }[] = [
  { id: 'all', label: 'All' },
  { id: 'popular', label: 'Popular' },
  { id: 'sql', label: 'SQL' },
  { id: 'nosql', label: 'NoSQL' },
  { id: 'analytic', label: 'Analytical' },
  { id: 'file', label: 'Files' },
  { id: 'embedded', label: 'Embedded' },
  { id: 'timeseries', label: 'Timeseries' },
  { id: 'hadoop', label: 'Hadoop / BigData' },
  { id: 'fulltext', label: 'Full-text search' },
  { id: 'graph', label: 'Graph databases' },
  { id: 'cloud', label: 'Cloud' },
]

const search = ref('')
const category = ref('all')
const sort = ref<'title' | 'score'>('score')
const searchInput = ref<HTMLInputElement | null>(null)

const inCategory = (d: Driver, id: string) =>
  id === 'all' || (id === 'popular' ? d.promoted !== null && d.promoted !== undefined : (d.categories ?? []).includes(id))

const categories = computed(() =>
  CATEGORIES.map((c) => ({ ...c, count: props.drivers.filter((d) => inCategory(d, c.id)).length })).filter((c) => c.count > 0),
)

const visible = computed(() => {
  const needle = search.value.trim().toLowerCase()
  const list = props.drivers.filter(
    (d) =>
      inCategory(d, category.value) &&
      (!needle || d.label.toLowerCase().includes(needle) || (d.description ?? '').toLowerCase().includes(needle) ||
        d.className.toLowerCase().includes(needle)),
  )
  return [...list].sort((a, b) => {
    if (sort.value === 'score') {
      const pa = a.promoted ?? 1000
      const pb = b.promoted ?? 1000
      if (pa !== pb) return pa - pb
    }
    return a.label.localeCompare(b.label)
  })
})

function onKey(event: KeyboardEvent) {
  if (event.key === 'Enter' && visible.value.length) {
    event.preventDefault()
    const pick = visible.value.find((d) => d.id === selected.value) ?? visible.value[0]!
    selected.value = pick.id
    emit('choose', pick.id)
  }
}

onMounted(() => void nextTick(() => searchInput.value?.focus()))
</script>

<template>
  <div class="gallery">
    <div class="top">
      <input
        ref="searchInput"
        v-model="search"
        type="search"
        class="search"
        placeholder="Type part of the database or driver name to filter"
        aria-label="Filter drivers"
        @keydown="onKey"
      />
      <span class="muted">Sort by:</span>
      <label class="check"><input v-model="sort" type="radio" value="title" /> Title</label>
      <label class="check"><input v-model="sort" type="radio" value="score" /> Score</label>
    </div>
    <div class="body">
      <nav class="cats" aria-label="Driver categories">
        <button
          v-for="c in categories"
          :key="c.id"
          class="ghost cat"
          :class="{ on: category === c.id }"
          :aria-pressed="category === c.id"
          @click="category = c.id"
        >
          <span>{{ c.label }}</span><span class="muted count">{{ c.count }}</span>
        </button>
      </nav>
      <div class="grid" role="listbox" aria-label="Databases">
        <button
          v-for="d in visible"
          :key="d.id"
          class="item"
          role="option"
          :aria-selected="selected === d.id"
          :class="{ on: selected === d.id }"
          :title="d.description ?? d.label"
          @click="selected = d.id"
          @dblclick="emit('choose', d.id)"
        >
          <DriverIcon :driver-id="d.id" :size="56" />
          <span class="label">{{ d.label }}</span>
        </button>
        <p v-if="visible.length === 0" class="muted empty">No driver matches "{{ search }}".</p>
      </div>
    </div>
  </div>
</template>

<style scoped>
.gallery {
  display: flex;
  flex-direction: column;
  gap: 10px;
  min-height: 0;
}

.top {
  display: flex;
  align-items: center;
  gap: 10px;
  padding-left: 190px;
}

.search {
  flex: 1;
  min-width: 0;
}

.check {
  display: inline-flex;
  align-items: center;
  gap: 4px;
}

.body {
  display: grid;
  grid-template-columns: 180px 1fr;
  gap: 10px;
  min-height: 0;
  height: min(440px, calc(100vh - 260px));
}

.cats {
  display: flex;
  flex-direction: column;
  gap: 1px;
  overflow: auto;
}

.cat {
  justify-content: space-between;
  border-radius: 4px;
  text-align: left;
}

.cat.on {
  background: var(--accent-soft);
  color: var(--text);
}

.count {
  font-size: 11px;
}

.grid {
  display: grid;
  grid-template-columns: repeat(auto-fill, minmax(104px, 1fr));
  align-content: start;
  gap: 4px;
  padding: 6px;
  overflow: auto;
  border: 1px solid var(--border);
  border-radius: 6px;
  background: var(--panel-2);
}

.item {
  display: flex;
  flex-direction: column;
  align-items: center;
  gap: 6px;
  padding: 10px 4px 8px;
  border: 1px solid transparent;
  border-radius: 8px;
  background: transparent;
}

.item:hover {
  background: var(--panel);
}

.item.on {
  border-color: var(--accent);
  background: var(--accent-soft);
}

.label {
  font-size: 12px;
  line-height: 1.25;
  text-align: center;
  overflow-wrap: anywhere;
}

.empty {
  grid-column: 1 / -1;
  padding: 20px;
  text-align: center;
}
</style>
