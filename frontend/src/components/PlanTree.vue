<script setup lang="ts">
import { ref } from 'vue'
import type { PlanNode } from '@/lib/plan'

defineOptions({ name: 'PlanTree' })

/** One node of an execution plan and its children; the bar shows the node's share of the total cost. */
const props = withDefaults(defineProps<{ node: PlanNode; depth?: number }>(), { depth: 0 })
const open = ref(true)

const fmt = (n: number | null, digits = 2) => (n === null ? '—' : n.toLocaleString(undefined, { maximumFractionDigits: digits }))
const heat = () => (props.node.share > 0.5 ? 'hot' : props.node.share > 0.2 ? 'warm' : '')
</script>

<template>
  <div class="plan-node" role="treeitem" :aria-expanded="node.children.length ? open : undefined">
    <div class="line" :style="{ paddingLeft: depth * 18 + 6 + 'px' }">
      <button v-if="node.children.length" class="ghost toggle" :aria-label="open ? 'Collapse' : 'Expand'" @click="open = !open">
        {{ open ? '▾' : '▸' }}
      </button>
      <span v-else class="toggle" />
      <strong class="type" :class="heat()">{{ node.type }}</strong>
      <span class="detail">{{ node.detail }}</span>
      <span class="spacer" />
      <span class="metric" title="Estimated cost (startup..total)">cost {{ fmt(node.startupCost) }}..{{ fmt(node.totalCost) }}</span>
      <span class="metric" title="Estimated rows">rows {{ fmt(node.rows, 0) }}</span>
      <span v-if="node.actualMs !== null" class="metric actual" title="Actual time (ms) × loops, actual rows">
        {{ fmt(node.actualMs, 3) }} ms × {{ node.loops }} · {{ fmt(node.actualRows, 0) }} rows
      </span>
      <span class="bar" :title="`${Math.round(node.share * 100)}% of the total cost`"><span :class="heat()" :style="{ width: node.share * 100 + '%' }" /></span>
    </div>
    <div v-for="c in node.conditions" :key="c" class="cond mono" :style="{ paddingLeft: depth * 18 + 34 + 'px' }">{{ c }}</div>
    <div v-if="open" role="group">
      <PlanTree v-for="(child, i) in node.children" :key="i" :node="child" :depth="depth + 1" />
    </div>
  </div>
</template>

<style scoped>
.line {
  display: flex;
  align-items: center;
  gap: 8px;
  min-height: 24px;
  padding-right: 8px;
  border-bottom: 1px solid var(--border);
  white-space: nowrap;
}

.line:hover {
  background: var(--panel-2);
}

.toggle {
  width: 16px;
  padding: 0;
  border: 0;
  flex: none;
}

.type.warm {
  color: var(--warn);
}

.type.hot {
  color: var(--danger);
}

.detail {
  color: var(--muted);
  overflow: hidden;
  text-overflow: ellipsis;
}

.spacer {
  flex: 1;
}

.metric {
  font-size: 11px;
  font-family: var(--font-mono);
  color: var(--muted);
}

.metric.actual {
  color: var(--text);
}

.bar {
  flex: none;
  width: 80px;
  height: 6px;
  border-radius: 3px;
  background: var(--panel-2);
  overflow: hidden;
}

.bar span {
  display: block;
  height: 100%;
  background: var(--accent);
}

.bar span.warm {
  background: var(--warn);
}

.bar span.hot {
  background: var(--danger);
}

.cond {
  font-size: 11px;
  color: var(--muted);
  padding: 1px 0;
  white-space: pre-wrap;
}
</style>
