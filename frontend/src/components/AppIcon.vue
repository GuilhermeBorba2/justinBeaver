<script setup lang="ts">
export type IconName =
  | 'database'
  | 'plug'
  | 'plug-off'
  | 'schema'
  | 'table'
  | 'view'
  | 'column'
  | 'key'
  | 'play'
  | 'script'
  | 'stop'
  | 'plus'
  | 'refresh'
  | 'trash'
  | 'edit'
  | 'commit'
  | 'rollback'
  | 'download'
  | 'copy'
  | 'chevron'
  | 'close'
  | 'sql'
  | 'prev'
  | 'next'
  | 'activity'
  | 'reconnect'
  | 'history'
  | 'lock'
  | 'unlock'
  | 'gauge'
  | 'diagram'
  | 'search'
  | 'caret'
  | 'tx'
  | 'pin'
  | 'link'
  | 'sigma'
  | 'folder'
  | 'star'
  | 'settings'
  | 'upload'
  | 'cloud'
  | 'power'

withDefaults(defineProps<{ name: IconName; size?: number }>(), { size: 14 })

// 24x24 stroke icons; each entry is the list of path `d` values.
const paths: Record<IconName, string[]> = {
  database: ['M4 6c0-1.7 3.6-3 8-3s8 1.3 8 3-3.6 3-8 3-8-1.3-8-3Z', 'M4 6v6c0 1.7 3.6 3 8 3s8-1.3 8-3V6', 'M4 12v6c0 1.7 3.6 3 8 3s8-1.3 8-3v-6'],
  plug: ['M9 3v5M15 3v5', 'M6 8h12v3a6 6 0 0 1-12 0V8Z', 'M12 17v4'],
  'plug-off': ['M9 3v5M15 3v5', 'M6 8h12v3a6 6 0 0 1-12 0V8Z', 'M3 3l18 18'],
  schema: ['M3 6a1 1 0 0 1 1-1h5l2 2h9a1 1 0 0 1 1 1v10a1 1 0 0 1-1 1H4a1 1 0 0 1-1-1V6Z'],
  table: ['M3 5h18v14H3z', 'M3 10h18M9 5v14'],
  view: ['M2 12s3.6-7 10-7 10 7 10 7-3.6 7-10 7S2 12 2 12Z', 'M12 15a3 3 0 1 0 0-6 3 3 0 0 0 0 6Z'],
  column: ['M8 4v16M16 4v16', 'M4 4h16v16H4z'],
  key: ['M14 10a4 4 0 1 1-3.2 1.6L4 18.5V21h3v-2h2v-2h2l1.6-1.6', 'M16 8h.01'],
  play: ['M7 4.5v15l12-7.5-12-7.5Z'],
  script: ['M5 4h14v16H5z', 'M8 9h8M8 13h8M8 17h5'],
  stop: ['M6 6h12v12H6z'],
  plus: ['M12 5v14M5 12h14'],
  refresh: ['M20 11a8 8 0 1 0-2.3 5.7', 'M20 4v7h-7'],
  trash: ['M4 7h16M10 11v6M14 11v6', 'M6 7l1 13h10l1-13M9 7V4h6v3'],
  edit: ['M4 20h4L19 9l-4-4L4 16v4Z', 'M13 7l4 4'],
  commit: ['M5 12.5l4.5 4.5L19 7'],
  rollback: ['M9 14 4 9l5-5', 'M4 9h10a6 6 0 0 1 0 12h-3'],
  download: ['M12 4v11M7 11l5 5 5-5', 'M5 20h14'],
  copy: ['M9 9h11v11H9z', 'M5 15V4h11'],
  chevron: ['M9 6l6 6-6 6'],
  close: ['M6 6l12 12M18 6 6 18'],
  sql: ['M4 7c0-1.1 3.6-2 8-2s8 .9 8 2-3.6 2-8 2-8-.9-8-2Z', 'M4 7v5c0 1.1 3.6 2 8 2', 'M4 12v5c0 1.1 3.6 2 8 2', 'M15 17l2 2 4-4'],
  prev: ['M15 6l-6 6 6 6'],
  next: ['M9 6l6 6-6 6'],
  activity: ['M3 12h4l3-8 4 16 3-8h4'],
  reconnect: ['M4 12a8 8 0 0 1 13.7-5.7L20 8.6', 'M20 4v4.6h-4.6', 'M20 12a8 8 0 0 1-13.7 5.7L4 15.4', 'M4 20v-4.6h4.6'],
  history: ['M3 12a9 9 0 1 0 2.6-6.4L3 8', 'M3 3v5h5', 'M12 7v5l3 2'],
  lock: ['M5 11h14v10H5z', 'M8 11V7a4 4 0 0 1 8 0v4'],
  unlock: ['M5 11h14v10H5z', 'M8 11V7a4 4 0 0 1 7.7-1.5'],
  gauge: ['M4 18a8 8 0 1 1 16 0', 'M12 18l4-5', 'M12 18h.01'],
  diagram: ['M3 4h7v5H3zM14 15h7v5h-7zM14 4h7v5h-7z', 'M6.5 9v8.5H14', 'M10 6.5h4'],
  search: ['M11 18a7 7 0 1 0 0-14 7 7 0 0 0 0 14Z', 'M16 16l5 5'],
  caret: ['M7 10l5 5 5-5'],
  tx: ['M5 5h14', 'M12 5v14', 'M16 15l3 3 3-3'],
  pin: ['M9 4h6l-1 6 3 3H7l3-3z', 'M12 13v7'],
  link: ['M10 14a4 4 0 0 0 5.7 0l3-3a4 4 0 0 0-5.7-5.7l-1 1', 'M14 10a4 4 0 0 0-5.7 0l-3 3a4 4 0 0 0 5.7 5.7l1-1'],
  sigma: ['M18 5H6l6 7-6 7h12'],
  folder: ['M3 6h6l2 2h10v11H3z'],
  star: ['M12 3l2.7 5.6 6.1.9-4.4 4.3 1 6.1L12 17l-5.4 2.9 1-6.1L3.2 9.5l6.1-.9z'],
  settings: ['M12 15a3 3 0 1 0 0-6 3 3 0 0 0 0 6Z', 'M19 12a7 7 0 0 0-.1-1.2l2-1.6-2-3.4-2.4 1a7 7 0 0 0-2-1.2L14 3h-4l-.5 2.6a7 7 0 0 0-2 1.2l-2.4-1-2 3.4 2 1.6a7 7 0 0 0 0 2.4l-2 1.6 2 3.4 2.4-1a7 7 0 0 0 2 1.2L10 21h4l.5-2.6a7 7 0 0 0 2-1.2l2.4 1 2-3.4-2-1.6c.1-.4.1-.8.1-1.2Z'],
  upload: ['M12 16V4', 'M7 9l5-5 5 5', 'M4 20h16'],
  cloud: ['M7 18a4.5 4.5 0 0 1-.6-8.96A6 6 0 0 1 18 9.5a4 4 0 0 1-.5 8.5Z'],
  power: ['M12 3v9', 'M6.3 6.8a8 8 0 1 0 11.4 0'],
}
</script>

<template>
  <svg
    :width="size"
    :height="size"
    viewBox="0 0 24 24"
    fill="none"
    stroke="currentColor"
    stroke-width="1.8"
    stroke-linecap="round"
    stroke-linejoin="round"
    aria-hidden="true"
    focusable="false"
  >
    <path v-for="d in paths[name]" :key="d" :d="d" />
  </svg>
</template>
