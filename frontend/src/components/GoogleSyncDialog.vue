<script setup lang="ts">
import { computed, ref } from 'vue'
import { api } from '@/api/client'
import { describeKinds, useDrive } from '@/composables/drive'
import { tr } from '@/lib/menu'
import { useGoogle } from '@/stores/google'
import { useUi } from '@/stores/ui'
import GoogleG from './GoogleG.vue'

/**
 * Sign in on Google's page, then optionally enable encrypted sync.
 * The app's OAuth credentials belong in the app configuration, never in the user's sign-in form.
 */
const google = useGoogle()
const ui = useUi()

const dialog = ref<HTMLDialogElement | null>(null)
const passphrase = ref('')
const repeat = ref('')
const showPassphrase = ref(false)
const cloudCopyExists = ref<boolean | null>(null)
const busy = ref(false)
const error = ref<string | null>(null)
const outcome = ref<string | null>(null)

const drive = useDrive()
/** What the Save / Import buttons move. */
const kinds = ref({ connections: true, scripts: true, preferences: false })
const nothingChosen = computed(() => !kinds.value.connections && !kinds.value.scripts && !kinds.value.preferences)

const saveToDrive = () =>
  run(async () => {
    if (await drive.save({ ...kinds.value })) outcome.value = tr(`Saved to Google Drive: ${describeKinds(kinds.value)}.`, `Salvo no Google Drive: ${describeKinds(kinds.value)}.`)
  })

const importFromDrive = () =>
  run(async () => {
    if (await drive.load({ ...kinds.value })) outcome.value = tr(`Imported from Google Drive: ${describeKinds(kinds.value)}.`, `Importado do Google Drive: ${describeKinds(kinds.value)}.`)
  })

const status = computed(() => google.status)
const step = computed<'login' | 'passphrase' | 'done'>(() => {
  if (!status.value?.signedIn) return 'login'
  return status.value.sync.enabled ? 'done' : 'passphrase'
})

async function open() {
  error.value = null
  outcome.value = null
  passphrase.value = repeat.value = ''
  cloudCopyExists.value = null
  await google.refresh()
  dialog.value?.showModal()
  if (step.value === 'passphrase') await checkCloud()
}

async function checkCloud() {
  cloudCopyExists.value = (await api.syncCloudCopy().catch(() => null))?.exists ?? null
}

async function run(work: () => Promise<void>) {
  busy.value = true
  error.value = null
  try {
    await work()
  } catch (e) {
    error.value = e instanceof Error ? e.message : String(e)
  } finally {
    busy.value = false
  }
}

const signIn = () =>
  run(async () => {
    if (!(await google.login())) return
    if (step.value === 'passphrase') await checkCloud()
  })

const canEnable = computed(
  () => passphrase.value.length >= 8 && (cloudCopyExists.value === true || passphrase.value === repeat.value),
)

const enable = () =>
  run(async () => {
    const result = await google.enableSync(passphrase.value)
    outcome.value = describe(result.connectionsChanged, result.scriptsChanged, result.settingsChanged, result.pushed)
    passphrase.value = repeat.value = ''
  })

const syncNow = () =>
  run(async () => {
    const result = await google.syncNow(true)
    if (result) outcome.value = describe(result.connectionsChanged, result.scriptsChanged, result.settingsChanged, result.pushed)
  })

function describe(connections: number, scripts: number, settings: boolean, pushed: boolean): string {
  const pulled = [
    connections ? `${connections} connection(s)` : '',
    scripts ? `${scripts} script(s)` : '',
    settings ? 'preferences' : '',
  ].filter(Boolean)
  return `${pulled.length ? `Pulled ${pulled.join(', ')}. ` : 'Nothing new to pull. '}${pushed ? 'Your changes were saved to Google Drive.' : 'Google Drive is up to date.'}`
}

const turnOff = () =>
  run(async () => {
    if (!window.confirm('Turn sync off on this computer? You will need the sync passphrase to turn it on again.')) return
    await api.syncDisable()
    await google.refresh()
    outcome.value = null
    await checkCloud()
  })

const deleteCloud = () =>
  run(async () => {
    if (!window.confirm('Delete the copy in Google Drive? The data on your computers stays; sync is turned off here.')) return
    await api.syncDeleteCloud()
    await api.syncDisable()
    await google.refresh()
    outcome.value = 'The cloud copy was deleted.'
    await checkCloud()
  })

const signOut = () =>
  run(async () => {
    if (!window.confirm('Sign out of Google on this computer? Your data here stays.')) return
    await google.logout()
    ui.info('Signed out')
    dialog.value?.close()
  })

const when = (ms: number | null | undefined) => (ms ? new Date(ms).toLocaleString() : 'never')

defineExpose({ open })
</script>

<template>
  <dialog ref="dialog" class="dialog" :class="{ 'login-dialog': step === 'login' }" aria-labelledby="gs-title" @cancel="google.cancelLogin()" @close="google.cancelLogin()">
    <div class="form">
      <template v-if="step === 'login'">
        <div class="login-heading">
          <img src="/logo.png" alt="" width="56" height="56" />
          <h2 id="gs-title">Sign in to JustinBeaver</h2>
          <p class="muted">Sign in to sync your connections, saved scripts and preferences across computers.</p>
        </div>
        <button class="google" :disabled="busy || !status?.configured" @click="signIn">
          <GoogleG /> {{ google.waitingForLogin ? 'Waiting for Google…' : 'Continue with Google' }}
        </button>
        <div v-if="google.waitingForLogin" class="login-progress" role="status">
          <p>Choose your account in the Google tab to continue.</p>
          <a v-if="google.loginUrl" :href="google.loginUrl" target="_blank" rel="noopener noreferrer">Open Google sign-in again</a>
          <button class="ghost" @click="google.cancelLogin()">Cancel sign-in</button>
        </div>
        <p v-else-if="!status?.configured" class="setup-notice" role="status">
          Google sign-in is not available in this build yet. You can keep using JustinBeaver locally.
        </p>
        <p v-else class="muted small login-hint">A Google page will open so you can choose your account.</p>
        <p class="muted small privacy-note">
          Sync uses this app's private folder in Google Drive. Your database passwords are encrypted before upload.
        </p>
      </template>

      <template v-else>
        <h2 id="gs-title"><GoogleG /> Google account &amp; sync</h2>
        <div class="account">
          <img v-if="status?.picture" :src="status.picture" alt="" class="avatar" referrerpolicy="no-referrer" width="36" height="36" />
          <span v-else class="avatar initial">{{ (status?.name ?? status?.email ?? '?').slice(0, 1).toUpperCase() }}</span>
          <div>
            <strong>{{ status?.name }}</strong>
            <div class="muted small">{{ status?.email }}</div>
          </div>
        </div>

        <template v-if="step === 'passphrase'">
          <p v-if="cloudCopyExists === true">
            This account already has a copy in Google Drive. Enter the <strong>sync passphrase</strong> you chose on your first
            computer to open it.
          </p>
          <p v-else>
            Choose a <strong>sync passphrase</strong>. It encrypts everything before it goes to Google Drive and you enter it once on
            each computer. <strong>If you forget it, the copy cannot be recovered</strong> (you can delete it and start again).
          </p>
          <label class="field">
            <span>Sync passphrase (8 characters or more)</span>
            <input v-model="passphrase" :type="showPassphrase ? 'text' : 'password'" autocomplete="new-password" />
          </label>
          <label v-if="cloudCopyExists !== true" class="field">
            <span>Repeat the passphrase</span>
            <input v-model="repeat" :type="showPassphrase ? 'text' : 'password'" autocomplete="new-password" />
          </label>
          <label class="check"><input v-model="showPassphrase" type="checkbox" /> Show</label>
          <div class="buttons">
            <button @click="signOut">Sign out</button>
            <span class="spacer" />
            <button class="primary" :disabled="busy || !canEnable" @click="enable">
              {{ cloudCopyExists === true ? 'Open the cloud copy and sync' : 'Turn on sync' }}
            </button>
          </div>
        </template>

        <template v-else>
          <p class="state">
            <span class="dot" :class="status?.sync.lastError ? 'bad' : 'ok'" />
            Sync is on · last sync {{ when(status?.sync.lastSyncAt) }}
          </p>
          <p v-if="status?.sync.lastError" class="err">{{ status.sync.lastError }}</p>
          <fieldset class="transfer">
            <legend>{{ tr('Save to / import from Google Drive', 'Salvar no / importar do Google Drive') }}</legend>
            <div class="kinds">
              <label class="check"><input v-model="kinds.connections" type="checkbox" /> {{ tr('Connections (with passwords)', 'Conexões (com senhas)') }}</label>
              <label class="check"><input v-model="kinds.scripts" type="checkbox" /> {{ tr('SQL scripts', 'Scripts SQL') }}</label>
              <label class="check"><input v-model="kinds.preferences" type="checkbox" /> {{ tr('Preferences (colors, language…)', 'Preferências (cores, idioma…)') }}</label>
            </div>
            <div class="buttons wrap">
              <button class="primary" :disabled="busy || google.syncing || nothingChosen" @click="saveToDrive">{{ tr('Save to Google Drive', 'Salvar no Google Drive') }}</button>
              <button :disabled="busy || google.syncing || nothingChosen" @click="importFromDrive">{{ tr('Import from Google Drive', 'Importar do Google Drive') }}</button>
            </div>
            <p class="muted small">
              {{ tr('Saving replaces the Drive version of the same items; importing replaces the ones here. Nothing is deleted. Everything is encrypted with your sync passphrase.', 'Salvar substitui a versão do Drive dos mesmos itens; importar substitui os daqui. Nada é apagado. Tudo é criptografado com a sua senha de sincronização.') }}
            </p>
          </fieldset>
          <p class="muted small">{{ tr('Changes are also kept in sync automatically.', 'As alterações também são sincronizadas automaticamente.') }}</p>
          <div class="buttons wrap">
            <button :disabled="busy || google.syncing" @click="syncNow">{{ google.syncing ? 'Syncing…' : 'Sync now' }}</button>
            <button :disabled="busy" @click="turnOff">Turn off on this computer</button>
            <button class="danger" :disabled="busy" @click="deleteCloud">Delete the cloud copy</button>
            <span class="spacer" />
            <button :disabled="busy" @click="signOut">Sign out</button>
          </div>
        </template>
      </template>

      <p v-if="error" class="err" role="alert">{{ error }}</p>
      <p v-if="outcome" class="ok">{{ outcome }}</p>
      <div class="buttons">
        <span class="spacer" />
        <button :class="{ ghost: step === 'login' }" @click="dialog?.close()">{{ step === 'login' ? 'Continue without an account' : 'Close' }}</button>
      </div>
    </div>
  </dialog>
</template>

<style scoped>
.dialog {
  width: min(520px, calc(100vw - 32px));
  max-height: calc(100vh - 32px);
  overflow: auto;
  padding: 0;
  border: 1px solid var(--border);
  border-radius: 10px;
  background: var(--panel);
  color: var(--text);
  box-shadow: 0 20px 60px rgb(0 0 0 / 0.35);
}

.dialog::backdrop {
  background: rgb(0 0 0 / 0.45);
}

.form {
  display: flex;
  flex-direction: column;
  gap: 10px;
  padding: 18px 20px;
}

.login-dialog {
  width: min(420px, calc(100vw - 32px));
  border-radius: 16px;
}

.login-dialog .form {
  padding: 32px;
  gap: 16px;
}

.login-heading {
  display: flex;
  flex-direction: column;
  align-items: center;
  gap: 12px;
  text-align: center;
}

.login-heading h2 {
  display: block;
  font-size: 25px;
  line-height: 1.2;
  letter-spacing: -0.6px;
  margin: 4px 0 0;
}

.login-heading p {
  line-height: 1.6;
}

.login-hint,
.privacy-note,
.setup-notice,
.login-progress {
  text-align: center;
  line-height: 1.6;
}

.privacy-note {
  padding-top: 16px;
  border-top: 1px solid var(--border);
}

.setup-notice {
  padding: 12px;
  background: var(--panel-2);
  border-radius: 8px;
}

.login-progress {
  display: grid;
  justify-items: center;
  gap: 8px;
  color: var(--muted);
  font-size: 12px;
}

.login-progress a {
  color: var(--text);
  text-underline-offset: 3px;
}

.login-dialog > .form > .buttons {
  justify-content: center;
}

.login-dialog .spacer {
  display: none;
}

h2 {
  display: flex;
  align-items: center;
  gap: 8px;
  margin: 0;
  font-size: 16px;
}

p {
  margin: 0;
}

.small {
  font-size: 12px;
}

.field {
  display: flex;
  flex-direction: column;
  gap: 4px;
}

.field > span {
  font-size: 12px;
  color: var(--muted);
}

.check {
  display: flex;
  align-items: center;
  gap: 6px;
}

.account {
  display: flex;
  align-items: center;
  gap: 10px;
}

.avatar {
  width: 36px;
  height: 36px;
  border-radius: 50%;
  object-fit: cover;
}

.avatar.initial {
  display: grid;
  place-items: center;
  background: var(--accent);
  color: var(--accent-contrast);
  font-weight: 700;
}

.state {
  display: flex;
  align-items: center;
  gap: 8px;
}

.dot {
  width: 8px;
  height: 8px;
  border-radius: 50%;
}

.dot.ok {
  background: var(--ok);
}

.dot.bad {
  background: var(--danger);
}

.google {
  display: inline-flex;
  align-items: center;
  gap: 8px;
  background: #fff;
  color: #3c4043;
  border-color: #dadce0;
  font-weight: 600;
  justify-content: center;
  min-height: 44px;
  border-radius: 24px;
}

.google:hover:not(:disabled) {
  background: #f8f9fa;
}

.err {
  color: var(--danger);
}

.ok {
  color: var(--ok);
}

.danger {
  color: var(--danger);
}

.buttons {
  display: flex;
  align-items: center;
  gap: 8px;
}

.buttons.wrap {
  flex-wrap: wrap;
}

.spacer {
  flex: 1;
}
.transfer {
  display: flex;
  flex-direction: column;
  gap: 8px;
  margin: 0;
  padding: 10px 12px;
  border: 1px solid var(--border);
  border-radius: 8px;
}

.transfer legend {
  padding: 0 6px;
  color: var(--muted);
}

.kinds {
  display: flex;
  flex-direction: column;
  gap: 4px;
}
</style>
