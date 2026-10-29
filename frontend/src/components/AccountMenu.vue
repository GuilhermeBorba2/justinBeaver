<script setup lang="ts">
import { computed } from 'vue'
import { useApp } from '@/stores/app'
import { useGoogle } from '@/stores/google'
import AppIcon from './AppIcon.vue'
import GoogleG from './GoogleG.vue'
import ToolMenu from './ToolMenu.vue'

/** Top right: "Sign in with Google", or the account with sync status and the app's Quit. */
defineEmits<{ open: [] }>()

const google = useGoogle()
const app = useApp()

const status = computed(() => google.status)
const initial = computed(() => (status.value?.name ?? status.value?.email ?? '?').slice(0, 1).toUpperCase())
const syncText = computed(() => {
  const sync = status.value?.sync
  if (!sync?.enabled) return 'Sync is off'
  if (google.syncing) return 'Syncing…'
  if (sync.lastError) return `Sync problem: ${sync.lastError}`
  return sync.lastSyncAt ? `Synced ${new Date(sync.lastSyncAt).toLocaleTimeString()}` : 'Sync is on'
})
</script>

<template>
  <div class="account-menu">
    <button v-if="!status?.signedIn" class="signin" title="Sign in with Google to sync connections and preferences" @click="$emit('open')">
      <GoogleG /> <span class="label">Sign in with Google</span>
    </button>
    <ToolMenu v-else :title="`${status.name} · ${syncText}`" align-right>
      <template #button>
        <img v-if="status.picture" :src="status.picture" alt="" class="avatar" referrerpolicy="no-referrer" width="22" height="22" />
        <span v-else class="avatar initial">{{ initial }}</span>
        <span v-if="google.syncing" class="spin" aria-hidden="true" />
        <span v-else-if="status.sync.lastError" class="badge bad" title="Sync problem" />
      </template>
      <template #default="{ close }">
        <div class="heading">{{ status.email }}</div>
        <div class="line muted">{{ syncText }}</div>
        <hr />
        <button v-if="status.sync.enabled" class="item" :disabled="google.syncing" @click="close(), google.syncNow()"><AppIcon name="refresh" /> Sync now</button>
        <button class="item" @click="close(), $emit('open')"><AppIcon name="cloud" /> Account &amp; sync…</button>
        <template v-if="app.info?.desktop">
          <hr />
          <button class="item" @click="close(), app.quit()"><AppIcon name="power" /> Quit JustinBeaver</button>
        </template>
      </template>
    </ToolMenu>
    <button v-if="!status?.signedIn && app.info?.desktop" class="quit" title="Quit JustinBeaver" aria-label="Quit JustinBeaver" @click="app.quit()">
      <AppIcon name="power" />
    </button>
  </div>
</template>

<style scoped>
.account-menu {
  display: inline-flex;
  align-items: center;
  gap: 4px;
  margin-left: auto;
  padding-left: 8px;
}

.signin {
  display: inline-flex;
  align-items: center;
  gap: 6px;
  height: 28px;
  padding: 0 10px;
  background: #fff;
  color: #3c4043;
  border-color: #dadce0;
  font-weight: 600;
}

.signin:hover {
  background: #f8f9fa;
}

.quit {
  height: 28px;
  background: transparent;
  border-color: transparent;
  color: var(--muted);
}

.avatar {
  width: 22px;
  height: 22px;
  border-radius: 50%;
  object-fit: cover;
}

.avatar.initial {
  display: grid;
  place-items: center;
  background: var(--accent);
  color: var(--accent-contrast);
  font-size: 12px;
  font-weight: 700;
}

.badge {
  width: 8px;
  height: 8px;
  border-radius: 50%;
}

.badge.bad {
  background: var(--danger);
}

.spin {
  width: 10px;
  height: 10px;
  border: 2px solid var(--border);
  border-top-color: var(--accent);
  border-radius: 50%;
  animation: spin 0.8s linear infinite;
}

@keyframes spin {
  to {
    transform: rotate(360deg);
  }
}

.line {
  padding: 2px 8px 4px;
  font-size: 12px;
  max-width: 280px;
  white-space: normal;
}

@media (max-width: 1180px) {
  .label {
    display: none;
  }
}
</style>
