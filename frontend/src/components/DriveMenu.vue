<script setup lang="ts">
import { computed } from 'vue'
import { ALL_KINDS, CONNECTIONS, SCRIPTS, useDrive } from '@/composables/drive'
import { useAppActions } from '@/composables/appActions'
import { tr } from '@/lib/menu'
import { useGoogle } from '@/stores/google'
import AppIcon from './AppIcon.vue'
import ToolMenu from './ToolMenu.vue'

/** A small Google Drive drop-down: save to / import from Drive for connections, scripts or everything. */
const props = defineProps<{ scope: 'connections' | 'scripts' | 'all'; alignRight?: boolean }>()

const drive = useDrive()
const google = useGoogle()
const shell = useAppActions()

const kinds = computed(() => (props.scope === 'connections' ? CONNECTIONS : props.scope === 'scripts' ? SCRIPTS : ALL_KINDS))
const what = computed(() =>
  props.scope === 'connections' ? tr('connections', 'conexões') : props.scope === 'scripts' ? tr('scripts', 'scripts') : tr('everything', 'tudo'),
)
const status = computed(() => {
  const s = google.status
  if (!s?.signedIn) return tr('Not signed in', 'Sem login')
  if (!s.sync.enabled) return tr('Sync is off', 'Sincronização desligada')
  if (s.sync.lastError) return `${tr('Problem', 'Problema')}: ${s.sync.lastError}`
  return s.sync.lastSyncAt ? tr(`Last sync ${new Date(s.sync.lastSyncAt).toLocaleTimeString()}`, `Última sincronização ${new Date(s.sync.lastSyncAt).toLocaleTimeString()}`) : tr('Sync is on', 'Sincronização ligada')
})
const title = computed(() => tr(`Google Drive: save or import ${what.value}`, `Google Drive: salvar ou importar ${what.value}`))
</script>

<template>
  <ToolMenu :title="title" :align-right="alignRight">
    <template #button><AppIcon name="cloud" :size="14" /></template>
    <template #default="{ close }">
      <div class="heading">Google Drive · {{ status }}</div>
      <button class="item" :disabled="drive.busy.value" @click="close(), drive.save(kinds)">
        <AppIcon name="upload" /> {{ tr(`Save ${what} to Google Drive`, `Salvar ${what} no Google Drive`) }}
      </button>
      <button class="item" :disabled="drive.busy.value" @click="close(), drive.load(kinds)">
        <AppIcon name="download" /> {{ tr(`Import ${what} from Google Drive`, `Importar ${what} do Google Drive`) }}
      </button>
      <hr />
      <button class="item" @click="close(), shell.googleSync()"><AppIcon name="settings" /> {{ tr('Account & sync…', 'Conta e sincronização…') }}</button>
    </template>
  </ToolMenu>
</template>
