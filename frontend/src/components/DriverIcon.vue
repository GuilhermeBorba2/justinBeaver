<script setup lang="ts">
import { computed, ref, watch } from 'vue'
import logos from '@/lib/driverLogos.json'
import AppIcon from './AppIcon.vue'

const props = withDefaults(defineProps<{ driverId: string; size?: number }>(), { size: 48 })
const failed = ref(false)
const logo = computed(() => (logos as Record<string, string>)[props.driverId])
watch(() => props.driverId, () => { failed.value = false })
</script>

<template>
  <span class="tile" :style="{ width: size + 'px', height: size + 'px', padding: size * 0.125 + 'px', borderRadius: size * 0.2 + 'px' }" aria-hidden="true">
    <img v-if="logo && !failed" :src="logo" alt="" draggable="false" @error="failed = true" />
    <AppIcon v-else name="database" :size="size * 0.6" />
  </span>
</template>

<style scoped>
.tile {
  display: inline-grid;
  place-items: center;
  box-sizing: border-box;
  border: 1px solid rgb(0 0 0 / 0.08);
  background: #fff;
  color: #6b7280;
  box-shadow: 0 2px 5px rgb(0 0 0 / 0.12);
  flex: none;
}

.tile img {
  display: block;
  width: 100%;
  height: 100%;
  object-fit: contain;
}
</style>
