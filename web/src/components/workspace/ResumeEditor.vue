<script setup lang="ts">
import { ElMessage } from 'element-plus'
import { computed, nextTick, onBeforeUnmount, onMounted, ref, watch } from 'vue'
import type {
  ResumeDocument,
  ResumeDocumentBasics,
  ResumeDocumentEntry,
  ResumeDocumentSection,
} from '@/types/resume-document'
import type { BulletSuggestIntent } from '@/types/workspace'
import type { BulletSuggestController } from '@/utils/useBulletSuggest'
import {
  getResumeContactPlaceholder,
  getResumeContactTypeLabel,
  RESUME_CONTACT_TYPE_OPTIONS,
} from '@/components/resume/resumeContactPresentation'

const props = defineProps<{
  document: ResumeDocument
  suggest?: BulletSuggestController | null
  suggestEnabled?: boolean
  /** 草稿未保存 / 保存中 / 失败 / 冲突时禁止发起 Suggest。 */
  suggestLocked?: boolean
  selectedSectionId?: string | null
  focusedBulletId?: string | null
  /** 同一目标再次点击时递增，确保仍能重新滚动到上下文。 */
  focusRequestKey?: number
}>()

const emit = defineEmits<{
  change: [document: ResumeDocument]
}>()

const LIMITS = {
  name: 100,
  contact: 200,
  sectionTitle: 100,
  entryField: 200,
  bullet: 4000,
  contacts: 20,
  entriesPerSection: 100,
  bulletsPerEntry: 100,
}

const newId = () => crypto.randomUUID()

const compactContactRows = (contacts: ResumeDocumentBasics['contacts']) => {
  const seen = new Set<string>()
  let emptyContactKept = false
  return contacts.filter((contact) => {
    const value = contact.value?.trim() ?? ''
    if (!value) {
      if (emptyContactKept) return false
      emptyContactKept = true
      return true
    }
    const key = `${contact.type || 'OTHER'}\u0000${value}`
    if (seen.has(key)) return false
    seen.add(key)
    return true
  })
}

const visibleContacts = computed(() =>
  compactContactRows(props.document.basics.contacts ?? []),
)

const SECTION_REORDER_HOLD_DELAY_MS = 220
const SECTION_REORDER_MOVE_THRESHOLD = 6

type SectionReorderIntent = {
  sectionId: string
  pointerId: number
  startX: number
  startY: number
  clientX: number
  clientY: number
  timer: number | null
  active: boolean
}

const sectionReorderIntent = ref<SectionReorderIntent | null>(null)
const draggedSectionId = ref<string | null>(null)
const dropSectionId = ref<string | null>(null)
const dropBefore = ref(false)
const reorderAnnouncement = ref('')
let autoScrollFrame: number | null = null
const editingName = ref(false)
const editingContactId = ref<string | null>(null)
const editingSectionTitle = ref<string | null>(null)
const editingEntryKey = ref<string | null>(null)
const expandedEntryFields = ref<Set<string>>(new Set())
const expandedSectionIds = ref<Set<string>>(new Set())
const editorRoot = ref<HTMLElement | null>(null)
const sectionElements = new Map<string, HTMLElement>()

const focusEditorElement = async (selector: string) => {
  await nextTick()
  editorRoot.value?.querySelector<HTMLElement>(selector)?.focus()
}

const setSectionRef = (sectionId: string, element: unknown) => {
  if (element instanceof HTMLElement) sectionElements.set(sectionId, element)
  else sectionElements.delete(sectionId)
}

const isSectionExpanded = (sectionId: string) => expandedSectionIds.value.has(sectionId)

const toggleSection = (sectionId: string) => {
  const next = new Set(expandedSectionIds.value)
  if (next.has(sectionId)) next.delete(sectionId)
  else next.add(sectionId)
  expandedSectionIds.value = next
}

watch(
  () => props.document.sections.map((section) => section.id),
  (sectionIds, previousSectionIds) => {
    const currentIds = new Set(sectionIds)
    const previousIds = new Set(previousSectionIds ?? [])
    const next = new Set([...expandedSectionIds.value].filter((id) => currentIds.has(id)))

    // The first document establishes the existing "all open" reading state. Later
    // content updates preserve manual choices while genuinely new sections open once.
    for (const sectionId of sectionIds) {
      if (!previousSectionIds || !previousIds.has(sectionId)) next.add(sectionId)
    }
    expandedSectionIds.value = next
  },
  { immediate: true },
)

const syncSelectedSection = async () => {
  const selectedSection = props.selectedSectionId
    ? props.document.sections.find((section) => section.id === props.selectedSectionId)
    : null
  if (selectedSection && !expandedSectionIds.value.has(selectedSection.id)) {
    expandedSectionIds.value = new Set([...expandedSectionIds.value, selectedSection.id])
  }

  // This watch only depends on navigation identity, not the immutable document
  // object. Autosave replacements therefore cannot re-scroll or steal focus.
  await nextTick()
  const bullet = props.focusedBulletId
    ? editorRoot.value?.querySelector<HTMLElement>(
        `[data-bullet-id="${props.focusedBulletId}"]`,
      )
    : null
  const target = bullet ?? (selectedSection ? sectionElements.get(selectedSection.id) : null)
  if (!target) return
  const scrollContainer = target.closest<HTMLElement>('.resume-stage-scroll')
  if (scrollContainer) {
    const targetTop = target.getBoundingClientRect().top - scrollContainer.getBoundingClientRect().top
    const targetOffset = bullet
      ? Math.max(24, (scrollContainer.clientHeight - target.clientHeight) / 2)
      : 24
    scrollContainer.scrollTop += targetTop - targetOffset
  } else if (typeof target.scrollIntoView === 'function') {
    target.scrollIntoView({ behavior: 'auto', block: bullet ? 'center' : 'start' })
  }
}

watch(
  () => [props.selectedSectionId, props.focusedBulletId, props.focusRequestKey] as const,
  () => void syncSelectedSection(),
  { immediate: true },
)

/** 深拷贝后原地修改再整体发出，保证父级始终收到不可变的新文档。
 * 父级传入的 document 是 Vue reactive proxy，structuredClone 无法克隆 Proxy，
 * 与 useWorkspaceEditor 一致使用 JSON 克隆。 */
const mutate = (mutator: (doc: ResumeDocument) => void) => {
  const next = JSON.parse(JSON.stringify(props.document)) as ResumeDocument
  mutator(next)
  next.basics.contacts = compactContactRows(next.basics.contacts ?? [])
  emit('change', next)
}

const updateBasics = (mutator: (basics: ResumeDocumentBasics) => void) => {
  mutate((doc) => mutator(doc.basics))
}

const finishNameEdit = () => {
  editingName.value = false
}

const beginContactEdit = (contactId: string) => {
  editingContactId.value = contactId
}

const finishContactEdit = () => {
  editingContactId.value = null
}

const entryKey = (sectionId: string, entryId: string) => `${sectionId}:${entryId}`
const isEntryEditing = (sectionId: string, entryId: string) =>
  editingEntryKey.value === entryKey(sectionId, entryId)
const beginEntryEdit = (sectionId: string, entryId: string) => {
  editingEntryKey.value = entryKey(sectionId, entryId)
}
const finishEntryEdit = () => {
  editingEntryKey.value = null
}

const entryTitle = (entry: ResumeDocumentEntry, kind: string) => {
  if (kind === 'EDUCATION') return entry.school || entry.degree || '教育经历'
  return entry.organization || entry.role || (kind === 'SKILL' ? entry.group : '经历条目') || '经历条目'
}

const entryMeta = (entry: ResumeDocumentEntry, kind: string) => {
  const values = kind === 'EDUCATION'
    ? [entry.degree, entry.major]
    : [entry.role, entry.location]
  const dates = entry.startDate && entry.endDate
    ? `${entry.startDate} — ${entry.endDate}`
    : entry.startDate || entry.endDate
  return [...values, dates].filter((value): value is string => Boolean(value?.trim())).join(' · ')
}

const updateSection = (sectionId: string, mutator: (section: ResumeDocumentSection) => void) => {
  mutate((doc) => {
    const section = doc.sections.find((item) => item.id === sectionId)
    if (section) mutator(section)
  })
}

const updateEntry = (
  sectionId: string,
  entryId: string,
  mutator: (entry: ResumeDocumentEntry) => void,
) => {
  updateSection(sectionId, (section) => {
    const entry = section.entries.find((item) => item.id === entryId)
    if (entry) mutator(entry)
  })
}

const deleteContact = (contactId: string) => {
  updateBasics((basics) => {
    basics.contacts = basics.contacts.filter((item) => item.id !== contactId)
  })
}

const deleteEntry = (sectionId: string, entryId: string) => {
  updateSection(sectionId, (section) => {
    section.entries = section.entries.filter((item) => item.id !== entryId)
  })
}

const deleteBullet = (sectionId: string, entryId: string, bulletId: string) => {
  updateEntry(sectionId, entryId, (entry) => {
    entry.bullets = entry.bullets.filter((item) => item.id !== bulletId)
  })
}

const moveSection = (index: number, delta: number) => {
  mutate((doc) => {
    const target = index + delta
    if (target < 0 || target >= doc.sections.length) return
    const [section] = doc.sections.splice(index, 1)
    if (section) doc.sections.splice(target, 0, section)
  })
}

const isInteractiveReorderTarget = (target: EventTarget | null) => {
  return target instanceof Element && Boolean(
    target.closest(
      'input, textarea, button, a, select, summary, .el-input, .el-input__wrapper, .el-textarea, [role="textbox"], [contenteditable="true"], [contenteditable=""], [role="button"]',
    ),
  )
}

const clearSectionReorder = () => {
  const intent = sectionReorderIntent.value
  if (intent?.timer !== null && intent?.timer !== undefined) window.clearTimeout(intent.timer)
  if (autoScrollFrame !== null) {
    window.cancelAnimationFrame(autoScrollFrame)
    autoScrollFrame = null
  }
  sectionReorderIntent.value = null
  draggedSectionId.value = null
  dropSectionId.value = null
  dropBefore.value = false
}

const setDropTarget = (clientY: number) => {
  const sourceId = draggedSectionId.value
  if (!sourceId || !editorRoot.value) return
  const sections = Array.from(
    editorRoot.value.querySelectorAll<HTMLElement>('.editor-section[data-section-id]'),
  ).filter((section) => section.dataset.sectionId !== sourceId)
  if (sections.length === 0) {
    dropSectionId.value = null
    return
  }

  let nextTarget = sections[sections.length - 1]
  let insertBefore = false
  for (const section of sections) {
    const rect = section.getBoundingClientRect()
    if (clientY < rect.top + rect.height / 2) {
      nextTarget = section
      insertBefore = true
      break
    }
  }
  dropSectionId.value = nextTarget?.dataset.sectionId ?? null
  dropBefore.value = insertBefore
}

const updateAutoScroll = (clientY: number) => {
  const container = editorRoot.value?.closest<HTMLElement>('.resume-stage-scroll')
  const intent = sectionReorderIntent.value
  if (!container || !intent?.active) return
  const rect = container.getBoundingClientRect()
  const edge = Math.min(80, Math.max(48, rect.height * 0.14))
  const direction = clientY < rect.top + edge ? -1 : clientY > rect.bottom - edge ? 1 : 0
  if (direction === 0) {
    if (autoScrollFrame !== null) {
      window.cancelAnimationFrame(autoScrollFrame)
      autoScrollFrame = null
    }
    return
  }
  if (autoScrollFrame !== null) return

  const scroll = () => {
    const current = sectionReorderIntent.value
    if (!current?.active) {
      autoScrollFrame = null
      return
    }
    const currentContainer = editorRoot.value?.closest<HTMLElement>('.resume-stage-scroll')
    if (!currentContainer) {
      autoScrollFrame = null
      return
    }
    const currentRect = currentContainer.getBoundingClientRect()
    const currentDirection = current.clientY < currentRect.top + edge
      ? -1
      : current.clientY > currentRect.bottom - edge
        ? 1
        : 0
    if (currentDirection === 0) {
      autoScrollFrame = null
      return
    }
    currentContainer.scrollTop += currentDirection * 6
    setDropTarget(current.clientY)
    autoScrollFrame = window.requestAnimationFrame(scroll)
  }
  autoScrollFrame = window.requestAnimationFrame(scroll)
}

const handleSectionPointerDown = (sectionId: string, event: PointerEvent) => {
  if (
    (event.pointerType && event.pointerType !== 'mouse') ||
    (event.button !== undefined && event.button !== 0) ||
    isInteractiveReorderTarget(event.target)
  ) return
  clearSectionReorder()
  const intent: SectionReorderIntent = {
    sectionId,
    pointerId: event.pointerId,
    startX: event.clientX,
    startY: event.clientY,
    clientX: event.clientX,
    clientY: event.clientY,
    timer: null,
    active: false,
  }
  intent.timer = window.setTimeout(() => {
    const current = sectionReorderIntent.value
    if (!current || Math.hypot(current.clientX - current.startX, current.clientY - current.startY) > SECTION_REORDER_MOVE_THRESHOLD) return
    current.active = true
    draggedSectionId.value = current.sectionId
    setDropTarget(current.clientY)
  }, SECTION_REORDER_HOLD_DELAY_MS)
  sectionReorderIntent.value = intent
}

const handleSectionPointerMove = (event: PointerEvent) => {
  const intent = sectionReorderIntent.value
  if (!intent || (event.pointerId && event.pointerId !== intent.pointerId)) return
  intent.clientX = event.clientX
  intent.clientY = event.clientY
  if (!intent.active) {
    if (Math.hypot(intent.clientX - intent.startX, intent.clientY - intent.startY) > SECTION_REORDER_MOVE_THRESHOLD) {
      clearSectionReorder()
    }
    return
  }
  event.preventDefault()
  setDropTarget(intent.clientY)
  updateAutoScroll(intent.clientY)
}

const finishSectionReorder = () => {
  const intent = sectionReorderIntent.value
  if (!intent?.active || !draggedSectionId.value || !dropSectionId.value) {
    clearSectionReorder()
    return
  }

  const sourceId = draggedSectionId.value
  const targetId = dropSectionId.value
  const insertBefore = dropBefore.value
  const source = props.document.sections.find((section) => section.id === sourceId)
  const sourceTitle = source ? sectionTitle(source) : ''
  const sourceIndex = props.document.sections.findIndex((section) => section.id === sourceId)
  const targetIndex = props.document.sections.findIndex((candidate) => candidate.id === targetId)
  const targetIndexAfterRemoval = targetIndex > sourceIndex ? targetIndex - 1 : targetIndex
  const nextIndex = insertBefore ? targetIndexAfterRemoval : targetIndexAfterRemoval + 1
  if (!source || sourceId === targetId || sourceIndex < 0 || targetIndex < 0 || nextIndex === sourceIndex) {
    clearSectionReorder()
    return
  }

  mutate((doc) => {
    const [section] = doc.sections.splice(sourceIndex, 1)
    if (section) doc.sections.splice(nextIndex, 0, section)
  })
  clearSectionReorder()
  reorderAnnouncement.value = `已将${sourceTitle}移动到第 ${nextIndex + 1} 项`
}

const handleSectionKeydown = (sectionId: string, index: number, event: KeyboardEvent) => {
  if (event.target !== event.currentTarget || !event.altKey || event.ctrlKey || event.metaKey) return
  const delta = event.key === 'ArrowUp' ? -1 : event.key === 'ArrowDown' ? 1 : 0
  if (delta === 0 || index + delta < 0 || index + delta >= props.document.sections.length) return
  event.preventDefault()
  const section = props.document.sections.find((candidate) => candidate.id === sectionId)
  moveSection(index, delta)
  if (section) reorderAnnouncement.value = `已将${sectionTitle(section)}移动到第 ${index + delta + 1} 项`
}

onMounted(() => {
  window.addEventListener('pointermove', handleSectionPointerMove)
  window.addEventListener('pointerup', finishSectionReorder)
  window.addEventListener('pointercancel', clearSectionReorder)
})

onBeforeUnmount(() => {
  clearSectionReorder()
  window.removeEventListener('pointermove', handleSectionPointerMove)
  window.removeEventListener('pointerup', finishSectionReorder)
  window.removeEventListener('pointercancel', clearSectionReorder)
})

const setContactType = (contactId: string, type: string) => {
  updateBasics((basics) => {
    const target = basics.contacts.find((item) => item.id === contactId)
    if (target) {
      target.type = type
      target.label = null
    }
  })
}

const addContact = () => {
  if (props.document.basics.contacts.length >= LIMITS.contacts) {
    ElMessage.warning('联系方式数量已达上限')
    return
  }
  if (props.document.basics.contacts.some((contact) => !contact.value?.trim())) {
    ElMessage.warning('请先填写或删除当前空白联系方式')
    return
  }
  updateBasics((basics) => {
    basics.contacts.push({ id: newId(), type: 'OTHER', label: null, value: '' })
  })
}

/** 技能条目以「、」分隔的文本编辑，保存时拆分为结构化列表。 */
const skillItemsText = (entry: ResumeDocumentEntry) => (entry.skillItems ?? []).join('、')

const setSkillItemsText = (sectionId: string, entryId: string, value: string) => {
  updateEntry(sectionId, entryId, (target) => {
    target.skillItems = value
      .split(/[、，,]/)
      .map((item) => item.trim())
      .filter((item) => item.length > 0)
  })
}

const addEntry = (sectionId: string) => {
  const section = props.document.sections.find((item) => item.id === sectionId)
  if (!section) return
  if (section.entries.length >= LIMITS.entriesPerSection) {
    ElMessage.warning('单个章节的条目数量已达上限')
    return
  }
  const entryId = newId()
  editingEntryKey.value = entryKey(sectionId, entryId)
  updateSection(sectionId, (target) => {
    target.entries.push({
      id: entryId,
      organization: null,
      role: null,
      school: null,
      degree: null,
      major: null,
      startDate: null,
      endDate: null,
      location: null,
      group: null,
      skillItems: section.kind === 'SKILL' ? [] : null,
      bullets: [],
    })
  })
  void focusEditorElement(`[data-entry-id="${entryId}"] input, [data-entry-id="${entryId}"] textarea`)
}

const addBullet = (sectionId: string, entryId: string) => {
  const section = props.document.sections.find((item) => item.id === sectionId)
  const entry = section?.entries.find((item) => item.id === entryId)
  if (!entry) return
  if (entry.bullets.length >= LIMITS.bulletsPerEntry) {
    ElMessage.warning('单个条目的内容数量已达上限')
    return
  }
  const bulletId = newId()
  updateEntry(sectionId, entryId, (target) => {
    target.bullets.push({ id: bulletId, text: '' })
  })
  void focusEditorElement(`[data-bullet-id="${bulletId}"] textarea`)
}

const sectionKindLabel = (kind: string) => {
  switch (kind) {
    case 'EXPERIENCE':
      return '工作经历'
    case 'PROJECT':
      return '项目经历'
    case 'EDUCATION':
      return '教育经历'
    case 'SKILL':
      return '技能'
    default:
      return '自定义内容'
  }
}

const sectionTitle = (section: ResumeDocumentSection) =>
  section.title.trim() || sectionKindLabel(section.kind)
const sectionEntryLabel = (kind: string) => {
  switch (kind) {
    case 'EXPERIENCE':
      return '工作经历'
    case 'PROJECT':
      return '项目经历'
    case 'EDUCATION':
      return '教育经历'
    case 'SKILL':
      return '技能组'
    default:
      return '内容'
  }
}

const bulletPlaceholder = (kind: string) => {
  switch (kind) {
    case 'EXPERIENCE':
      return '写下工作职责、技术实践或成果'
    case 'PROJECT':
      return '写下项目贡献、技术实践或成果'
    default:
      return '写下这段内容'
  }
}

const hasOptionalEntryFields = (entry: ResumeDocumentEntry, kind: string) =>
  kind === 'EXPERIENCE' || kind === 'PROJECT' ? Boolean(entry.location?.trim()) : false

const isEntryFieldsExpanded = (entryId: string) => expandedEntryFields.value.has(entryId)
const toggleEntryFields = (entryId: string) => {
  const next = new Set(expandedEntryFields.value)
  if (next.has(entryId)) next.delete(entryId)
  else next.add(entryId)
  expandedEntryFields.value = next
}

const syncEntryFieldsOpen = (entryId: string, event: Event) => {
  const open = (event.target as HTMLDetailsElement).open
  const next = new Set(expandedEntryFields.value)
  if (open) next.add(entryId)
  else next.delete(entryId)
  expandedEntryFields.value = next
}

const beginSectionTitleEdit = (sectionId: string) => {
  editingSectionTitle.value = sectionId
}

const finishSectionTitleEdit = () => {
  editingSectionTitle.value = null
}

const addEntryLabel = (kind: string) => {
  switch (kind) {
    case 'EXPERIENCE':
      return '添加工作经历'
    case 'PROJECT':
      return '添加项目'
    case 'EDUCATION':
      return '添加教育经历'
    case 'SKILL':
      return '添加技能组'
    default:
      return '添加内容条目'
  }
}

const entryContentLabel = (kind: string) => {
  if (kind === 'SKILL') return '技能项'
  if (kind === 'EXPERIENCE') return '工作要点'
  if (kind === 'PROJECT') return '项目要点'
  return '内容'
}

const SUGGEST_INTENTS: Array<{ command: BulletSuggestIntent | 'CUSTOM'; label: string }> = [
  { command: 'JOB_TARGETED', label: '岗位定向优化' },
  { command: 'SIMPLIFY', label: '精简' },
  { command: 'TECHNICAL_DEPTH', label: '强化技术深度' },
  { command: 'HIGHLIGHT_OUTCOME', label: '突出成果' },
  { command: 'CUSTOM', label: '自定义要求' },
]

const suggestActive = (bulletId: string) =>
  !!props.suggest && props.suggest.activeBulletId.value === bulletId

const handleSuggestCommand = (bulletId: string, command: BulletSuggestIntent | 'CUSTOM') => {
  if (!props.suggest || props.suggest.busy.value) return
  if (props.suggestLocked) {
    ElMessage.warning('请先完成保存，再生成建议')
    return
  }
  if (command === 'CUSTOM') {
    props.suggest.startCustomCompose(bulletId)
    return
  }
  props.suggest.suggest(bulletId, command)
}
</script>

<template>
  <div ref="editorRoot" class="resume-editor">
    <div class="resume-paper">
      <div class="resume-page-meta"><span>简历草稿</span><span>点击文字编辑</span></div>
    <section class="editor-block editor-basics">
      <header class="editor-block-header editor-basics-header">
        <div class="resume-identity">
          <template v-if="editingName">
            <el-input
              class="identity-name-input"
              :model-value="document.basics.name ?? ''"
              :maxlength="LIMITS.name"
              placeholder="你的姓名"
              aria-label="姓名"
              autofocus
              @update:model-value="(value: string) => updateBasics((basics) => (basics.name = value))"
              @blur="finishNameEdit"
              @keyup.enter="finishNameEdit"
              @keyup.esc="finishNameEdit"
            />
          </template>
          <button
            v-else
            type="button"
            class="identity-name"
            aria-label="编辑姓名"
            @click="editingName = true"
          >
            {{ document.basics.name || '未命名简历' }}
          </button>
          <p v-if="document.basics.jobIntention" class="identity-target">{{ document.basics.jobIntention }}</p>
        </div>
        <details class="basics-more">
          <summary aria-label="编辑补充信息">···</summary>
          <div class="basics-more-menu">
            <label>
              <span>求职意向</span>
              <el-input
                :model-value="document.basics.jobIntention ?? ''"
                :maxlength="LIMITS.entryField"
                placeholder="例如 Java 后端工程师"
                @update:model-value="
                  (value: string) => updateBasics((basics) => (basics.jobIntention = value))
                "
              />
            </label>
            <label>
              <span>最高学历</span>
              <el-input
                :model-value="document.basics.highestEducation ?? ''"
                :maxlength="LIMITS.entryField"
                placeholder="例如 本科"
                @update:model-value="
                  (value: string) => updateBasics((basics) => (basics.highestEducation = value))
                "
              />
            </label>
          </div>
        </details>
      </header>
      <div class="basics-document">
        <div class="contact-line" aria-label="联系方式">
          <template v-for="(contact, index) in visibleContacts" :key="contact.id">
            <div class="contact-item">
              <span v-if="index > 0" class="contact-divider" aria-hidden="true">·</span>
              <div v-if="editingContactId === contact.id" class="contact-inline-editor" @keydown.esc="finishContactEdit">
                <select
                  class="contact-type"
                  :value="contact.type || 'OTHER'"
                  :aria-label="`联系方式类型 · ${contact.value || '未填写'}`"
                  @change="
                    (event: Event) =>
                      setContactType(contact.id, (event.target as HTMLSelectElement).value)
                  "
                >
                  <option v-for="option in RESUME_CONTACT_TYPE_OPTIONS" :key="option.value" :value="option.value">
                    {{ option.label }}
                  </option>
                </select>
                <el-input
                  :model-value="contact.value"
                  :maxlength="LIMITS.contact"
                  :placeholder="getResumeContactPlaceholder(contact.type)"
                  :aria-label="`${getResumeContactTypeLabel(contact.type)}内容`"
                  @update:model-value="
                    (value: string) =>
                      updateBasics((basics) => {
                        const target = basics.contacts.find((item) => item.id === contact.id)
                        if (target) target.value = value
                      })
                  "
                />
                <button type="button" class="inline-done" @click="finishContactEdit">完成</button>
                <button type="button" class="inline-delete" @click="deleteContact(contact.id)">删除</button>
              </div>
              <button
                v-else
                type="button"
                class="contact-token"
                :class="{ 'is-empty': !contact.value?.trim() }"
                :aria-label="`编辑${getResumeContactTypeLabel(contact.type)}`"
                @click="beginContactEdit(contact.id)"
              >
                {{ contact.value || `添加${getResumeContactTypeLabel(contact.type)}` }}
              </button>
            </div>
          </template>
          <button type="button" class="contact-add" @click="addContact">+ 联系方式</button>
        </div>
      </div>
    </section>

    <section
      v-for="(section, sectionIndex) in document.sections"
      :key="section.id"
      class="editor-block editor-section"
      :data-section-id="section.id"
      :class="{
        'is-collapsed': !isSectionExpanded(section.id),
        'is-focused': props.selectedSectionId === section.id,
        'is-reorder-source': draggedSectionId === section.id,
        'is-drop-before': dropSectionId === section.id && dropBefore,
        'is-drop-after': dropSectionId === section.id && !dropBefore,
      }"
      :ref="(element) => setSectionRef(section.id, element)"
      role="group"
      tabindex="0"
      :aria-label="`${sectionTitle(section)}，第 ${sectionIndex + 1} 项`"
      aria-describedby="resume-reorder-help"
      aria-keyshortcuts="Alt+ArrowUp Alt+ArrowDown"
      @pointerdown="handleSectionPointerDown(section.id, $event)"
      @pointerup="finishSectionReorder"
      @pointercancel="clearSectionReorder"
      @keydown="handleSectionKeydown(section.id, sectionIndex, $event)"
    >
      <header class="editor-block-header">
        <button
          type="button"
          class="section-collapse-toggle"
          :aria-expanded="isSectionExpanded(section.id)"
          :aria-controls="`editor-section-${section.id}`"
          :aria-label="`${isSectionExpanded(section.id) ? '收起' : '展开'}${sectionTitle(section)}`"
          @click="toggleSection(section.id)"
        >
          <span class="section-collapse-icon" aria-hidden="true" />
        </button>
        <div class="section-heading">
          <span v-if="section.title.trim() !== sectionKindLabel(section.kind)" class="section-kind">
            {{ sectionKindLabel(section.kind) }}
          </span>
          <template v-if="editingSectionTitle === section.id">
            <el-input
              class="section-title-input"
              :model-value="section.title"
              :maxlength="LIMITS.sectionTitle"
              aria-label="章节标题"
              @update:model-value="
                (value: string) => updateSection(section.id, (target) => (target.title = value))
              "
              @blur="finishSectionTitleEdit"
              @keyup.enter="finishSectionTitleEdit"
              @keyup.esc="finishSectionTitleEdit"
            />
          </template>
          <button
            v-else
            type="button"
            class="section-title-display"
            :aria-label="`编辑${sectionTitle(section)}标题`"
            @click="beginSectionTitleEdit(section.id)"
          >
            {{ sectionTitle(section) }}
          </button>
        </div>
        <span class="section-entry-count">{{ section.entries.length }} 段</span>
      </header>

      <div v-if="isSectionExpanded(section.id)" :id="`editor-section-${section.id}`" class="editor-section-content">
      <p v-if="section.entries.length === 0" class="editor-empty">
        该章节暂时没有内容，可以添加{{ sectionEntryLabel(section.kind) }}。
      </p>

      <article
        v-for="entry in section.entries"
        :key="entry.id"
        class="editor-entry"
        :data-entry-id="entry.id"
      >
        <div class="entry-document-heading">
          <template v-if="isEntryEditing(section.id, entry.id)">
            <div v-if="section.kind === 'SKILL'" class="entry-inline-editor" @keydown.esc="finishEntryEdit" @keydown.enter.prevent="finishEntryEdit">
              <el-input
                :model-value="entry.group ?? ''"
                :maxlength="LIMITS.entryField"
                placeholder="技能分组"
                aria-label="技能分组"
                @update:model-value="
                  (value: string) =>
                    updateEntry(section.id, entry.id, (target) => (target.group = value))
                "
              />
              <el-input
                :model-value="skillItemsText(entry)"
                :maxlength="LIMITS.bullet"
                placeholder="技能项，用顿号分隔"
                aria-label="技能项"
                @update:model-value="(value: string) => setSkillItemsText(section.id, entry.id, value)"
              />
              <button type="button" class="inline-done" @click="finishEntryEdit">完成</button>
            </div>
            <div v-else-if="section.kind === 'EDUCATION'" class="entry-inline-editor entry-inline-editor-grid" @keydown.esc="finishEntryEdit" @keydown.enter.prevent="finishEntryEdit">
              <el-input
                :model-value="entry.school ?? ''"
                :maxlength="LIMITS.entryField"
                placeholder="学校"
                aria-label="学校"
                @update:model-value="
                  (value: string) =>
                    updateEntry(section.id, entry.id, (target) => (target.school = value))
                "
              />
              <el-input
                :model-value="entry.degree ?? ''"
                :maxlength="LIMITS.entryField"
                placeholder="学历"
                aria-label="学历"
                @update:model-value="
                  (value: string) =>
                    updateEntry(section.id, entry.id, (target) => (target.degree = value))
                "
              />
              <el-input
                :model-value="entry.major ?? ''"
                :maxlength="LIMITS.entryField"
                placeholder="专业"
                aria-label="专业"
                @update:model-value="
                  (value: string) =>
                    updateEntry(section.id, entry.id, (target) => (target.major = value))
                "
              />
              <el-input
                :model-value="entry.startDate ?? ''"
                :maxlength="LIMITS.entryField"
                placeholder="开始时间"
                aria-label="开始时间"
                @update:model-value="
                  (value: string) =>
                    updateEntry(section.id, entry.id, (target) => (target.startDate = value))
                "
              />
              <el-input
                :model-value="entry.endDate ?? ''"
                :maxlength="LIMITS.entryField"
                placeholder="结束时间"
                aria-label="结束时间"
                @update:model-value="
                  (value: string) =>
                    updateEntry(section.id, entry.id, (target) => (target.endDate = value))
                "
              />
              <button type="button" class="inline-done" @click="finishEntryEdit">完成</button>
            </div>
            <div v-else class="entry-inline-editor entry-inline-editor-grid" @keydown.esc="finishEntryEdit" @keydown.enter.prevent="finishEntryEdit">
              <el-input
                :model-value="entry.organization ?? ''"
                :maxlength="LIMITS.entryField"
                :placeholder="section.kind === 'PROJECT' ? '项目名' : '公司'"
                :aria-label="section.kind === 'PROJECT' ? '项目名' : '公司'"
                @update:model-value="
                  (value: string) =>
                    updateEntry(section.id, entry.id, (target) => (target.organization = value))
                "
              />
              <el-input
                :model-value="entry.role ?? ''"
                :maxlength="LIMITS.entryField"
                :placeholder="section.kind === 'PROJECT' ? '角色' : '职位'"
                :aria-label="section.kind === 'PROJECT' ? '角色' : '职位'"
                @update:model-value="
                  (value: string) =>
                    updateEntry(section.id, entry.id, (target) => (target.role = value))
                "
              />
              <el-input
                :model-value="entry.startDate ?? ''"
                :maxlength="LIMITS.entryField"
                placeholder="开始时间"
                aria-label="开始时间"
                @update:model-value="
                  (value: string) =>
                    updateEntry(section.id, entry.id, (target) => (target.startDate = value))
                "
              />
              <el-input
                :model-value="entry.endDate ?? ''"
                :maxlength="LIMITS.entryField"
                placeholder="结束时间"
                aria-label="结束时间"
                @update:model-value="
                  (value: string) =>
                    updateEntry(section.id, entry.id, (target) => (target.endDate = value))
                "
              />
              <button type="button" class="inline-done" @click="finishEntryEdit">完成</button>
            </div>
          </template>
          <template v-else-if="section.kind === 'SKILL'">
            <button type="button" class="entry-title-display" @click="beginEntryEdit(section.id, entry.id)">
              {{ entryTitle(entry, section.kind) }}
            </button>
            <button type="button" class="entry-meta-display" @click="beginEntryEdit(section.id, entry.id)">
              {{ skillItemsText(entry) || '添加技能项' }}
            </button>
          </template>
          <template v-else>
            <button type="button" class="entry-title-display" @click="beginEntryEdit(section.id, entry.id)">
              {{ entryTitle(entry, section.kind) }}
            </button>
            <button type="button" class="entry-meta-display" @click="beginEntryEdit(section.id, entry.id)">
              {{ entryMeta(entry, section.kind) || '添加职位、时间或地点' }}
            </button>
          </template>
        </div>
        <template v-if="section.kind === 'SKILL'">
          <div class="skill-grid">
            <label class="editor-field">
              <span>技能分组</span>
              <el-input
                :model-value="entry.group ?? ''"
                :maxlength="LIMITS.entryField"
                placeholder="例如 后端技术"
                @update:model-value="
                  (value: string) =>
                    updateEntry(section.id, entry.id, (target) => (target.group = value))
                "
              />
            </label>
            <label class="editor-field">
              <span>技能项</span>
              <el-input
                :model-value="skillItemsText(entry)"
                :maxlength="LIMITS.bullet"
                placeholder="用顿号分隔，例如 Java、Spring Boot"
                @update:model-value="
                  (value: string) => setSkillItemsText(section.id, entry.id, value)
                "
              />
            </label>
          </div>
        </template>

        <template v-else-if="section.kind === 'EDUCATION'">
          <div class="entry-grid">
            <label class="editor-field">
              <span>学校</span>
              <el-input
                :model-value="entry.school ?? ''"
                :maxlength="LIMITS.entryField"
                placeholder="学校名称"
                @update:model-value="
                  (value: string) =>
                    updateEntry(section.id, entry.id, (target) => (target.school = value))
                "
              />
            </label>
            <label class="editor-field">
              <span>学历</span>
              <el-input
                :model-value="entry.degree ?? ''"
                :maxlength="LIMITS.entryField"
                placeholder="例如 本科"
                @update:model-value="
                  (value: string) =>
                    updateEntry(section.id, entry.id, (target) => (target.degree = value))
                "
              />
            </label>
            <label class="editor-field">
              <span>专业</span>
              <el-input
                :model-value="entry.major ?? ''"
                :maxlength="LIMITS.entryField"
                placeholder="专业名称"
                @update:model-value="
                  (value: string) =>
                    updateEntry(section.id, entry.id, (target) => (target.major = value))
                "
              />
            </label>
            <div class="date-grid">
              <label class="editor-field">
                <span>开始时间</span>
                <el-input
                  :model-value="entry.startDate ?? ''"
                  :maxlength="LIMITS.entryField"
                  placeholder="例如 2018.09"
                  @update:model-value="
                    (value: string) =>
                      updateEntry(section.id, entry.id, (target) => (target.startDate = value))
                  "
                />
              </label>
              <label class="editor-field">
                <span>结束时间</span>
                <el-input
                  :model-value="entry.endDate ?? ''"
                  :maxlength="LIMITS.entryField"
                  placeholder="例如 2022.06"
                  @update:model-value="
                    (value: string) =>
                      updateEntry(section.id, entry.id, (target) => (target.endDate = value))
                  "
                />
              </label>
            </div>
          </div>
        </template>

        <template v-else-if="section.kind === 'EXPERIENCE' || section.kind === 'PROJECT'">
          <div class="entry-grid">
            <label class="editor-field">
              <span>{{ section.kind === 'PROJECT' ? '项目名' : '公司' }}</span>
              <el-input
                :model-value="entry.organization ?? ''"
                :maxlength="LIMITS.entryField"
                :placeholder="section.kind === 'PROJECT' ? '项目名称' : '公司名称'"
                @update:model-value="
                  (value: string) =>
                    updateEntry(section.id, entry.id, (target) => (target.organization = value))
                "
              />
            </label>
            <label class="editor-field">
              <span>{{ section.kind === 'PROJECT' ? '角色' : '职位' }}</span>
              <el-input
                :model-value="entry.role ?? ''"
                :maxlength="LIMITS.entryField"
                :placeholder="section.kind === 'PROJECT' ? '项目角色（可选）' : '职位名称'"
                @update:model-value="
                  (value: string) =>
                    updateEntry(section.id, entry.id, (target) => (target.role = value))
                "
              />
            </label>
            <div class="date-grid">
              <label class="editor-field">
                <span>开始时间</span>
                <el-input
                  :model-value="entry.startDate ?? ''"
                  :maxlength="LIMITS.entryField"
                  placeholder="例如 2022.07"
                  @update:model-value="
                    (value: string) =>
                      updateEntry(section.id, entry.id, (target) => (target.startDate = value))
                  "
                />
              </label>
              <label class="editor-field">
                <span>结束时间</span>
                <el-input
                  :model-value="entry.endDate ?? ''"
                  :maxlength="LIMITS.entryField"
                  placeholder="例如 至今"
                  @update:model-value="
                    (value: string) =>
                      updateEntry(section.id, entry.id, (target) => (target.endDate = value))
                  "
                />
              </label>
            </div>
          </div>
          <details
            v-if="hasOptionalEntryFields(entry, section.kind) || isEntryFieldsExpanded(entry.id)"
            class="entry-optional-fields"
            :open="isEntryFieldsExpanded(entry.id)"
            @toggle="syncEntryFieldsOpen(entry.id, $event)"
          >
            <summary>更多字段</summary>
            <label class="editor-field">
              <span>地点</span>
              <el-input
                :model-value="entry.location ?? ''"
                :maxlength="LIMITS.entryField"
                placeholder="例如 上海"
                @update:model-value="
                  (value: string) =>
                    updateEntry(section.id, entry.id, (target) => (target.location = value))
                "
              />
            </label>
          </details>
          <button
            v-if="!hasOptionalEntryFields(entry, section.kind) && !isEntryFieldsExpanded(entry.id)"
            type="button"
            class="add-field-control"
            @click="toggleEntryFields(entry.id)"
          >
            添加更多字段
          </button>
        </template>

        <div v-if="section.kind !== 'SKILL'" class="entry-bullets-label">
          {{ entryContentLabel(section.kind) }}
        </div>
        <template v-if="section.kind !== 'SKILL'">
          <div
            v-for="bullet in entry.bullets"
            :key="bullet.id"
            class="bullet-block"
            :class="{ 'is-evidence-focus': props.focusedBulletId === bullet.id }"
            :data-bullet-id="bullet.id"
          >
            <div class="bullet-line">
              <label class="bullet-field">
                <span class="sr-only">{{ entryContentLabel(section.kind) }}</span>
                <el-input
                  type="textarea"
                  :autosize="{ minRows: 1, maxRows: 6 }"
                  :model-value="bullet.text"
                  :maxlength="LIMITS.bullet"
                  :placeholder="bulletPlaceholder(section.kind)"
                  @update:model-value="
                    (value: string) =>
                      updateEntry(section.id, entry.id, (target) => {
                        const targetBullet = target.bullets.find((item) => item.id === bullet.id)
                        if (targetBullet) targetBullet.text = value
                      })
                  "
                />
              </label>
              <div
                class="bullet-actions"
                :class="{ 'has-suggestion-entry': suggestEnabled && suggest }"
              >
                <el-dropdown
                  v-if="suggestEnabled && suggest"
                  trigger="click"
                  @command="
                    (command: unknown) =>
                      handleSuggestCommand(bullet.id, command as BulletSuggestIntent | 'CUSTOM')
                  "
                >
                  <el-button
                    class="bullet-suggest-button"
                    size="small"
                    :disabled="
                      suggest.busy.value || suggestActive(bullet.id) || !bullet.text.trim()
                    "
                  >
                    AI 优化
                  </el-button>
                  <template #dropdown>
                    <el-dropdown-menu>
                      <el-dropdown-item
                        v-for="item in SUGGEST_INTENTS"
                        :key="item.command"
                        :command="item.command"
                      >
                        {{ item.label }}
                      </el-dropdown-item>
                    </el-dropdown-menu>
                  </template>
                </el-dropdown>
                <details class="inline-more bullet-more">
                  <summary aria-label="工作要点操作">···</summary>
                  <div class="inline-more-menu">
                    <button
                      type="button"
                      class="danger-action"
                      :aria-label="`删除${entryTitle(entry, section.kind)}中的${entryContentLabel(section.kind)}`"
                      @click="deleteBullet(section.id, entry.id, bullet.id)"
                    >
                      删除此{{ entryContentLabel(section.kind) }}
                    </button>
                  </div>
                </details>
              </div>
            </div>
          </div>
        </template>

        <div v-if="section.kind !== 'SKILL'" class="entry-actions">
          <el-button size="small" @click="addBullet(section.id, entry.id)">
            添加{{ entryContentLabel(section.kind) }}
          </el-button>
        </div>
        <div class="entry-more-row">
          <details class="entry-more">
            <summary aria-label="条目操作">···</summary>
            <div class="entry-more-menu">
              <button
                type="button"
                class="danger-action"
                :aria-label="`删除条目：${entryTitle(entry, section.kind)}`"
                @click="deleteEntry(section.id, entry.id)"
              >
                删除此条目
              </button>
            </div>
          </details>
        </div>
      </article>

      <div class="section-footer">
        <el-button size="small" @click="addEntry(section.id)">{{
          addEntryLabel(section.kind)
        }}</el-button>
      </div>
      </div>
      <p v-else class="editor-collapsed-summary">
        {{ section.entries.length }} 个条目 · 点击章节标题左侧展开编辑
      </p>
    </section>
    <div class="resume-page-footer"><span>当前版本由你确认</span><span>编辑内容会自动保存</span></div>
    </div>
    <p id="resume-reorder-help" class="sr-only">聚焦章节后使用 Alt 加上、下方向键调整顺序。</p>
    <div class="sr-only" aria-live="polite">{{ reorderAnnouncement }}</div>
  </div>
</template>

<style scoped>
.resume-editor {
  display: grid;
  gap: 26px;
  min-width: 0;
}

.editor-block {
  display: grid;
  gap: 16px;
  min-width: 0;
  padding-bottom: 26px;
  border-bottom: 1px solid var(--app-border);
}

.editor-block:last-child {
  border-bottom: 0;
}

.editor-block-header {
  display: flex;
  align-items: start;
  justify-content: space-between;
  gap: 16px;
}

.editor-block-header h2 {
  margin: 0;
  color: var(--app-text);
  font-size: 18px;
  line-height: 1.4;
}

.editor-block-header p {
  margin: 4px 0 0;
  color: var(--app-text-secondary);
  font-size: 12px;
  line-height: 1.6;
}

.editor-section .editor-block-header {
  align-items: center;
}

.section-collapse-toggle {
  display: inline-grid;
  flex: 0 0 auto;
  place-items: center;
  width: 28px;
  height: 28px;
  margin: -4px 0;
  border: 1px solid var(--app-border);
  border-radius: 50%;
  color: var(--app-primary);
  background: var(--app-surface-soft);
  cursor: pointer;
  transition: border-color 140ms ease, background-color 140ms ease, transform 140ms ease;
}

.section-collapse-toggle:hover,
.section-collapse-toggle:focus-visible {
  border-color: var(--app-primary);
  background: var(--app-primary-soft);
}

.section-collapse-toggle:focus-visible {
  outline: 2px solid var(--app-primary);
  outline-offset: 2px;
}

.section-collapse-icon {
  width: 7px;
  height: 7px;
  border-right: 1.5px solid currentColor;
  border-bottom: 1.5px solid currentColor;
  transform: rotate(45deg) translateY(-2px);
  transition: transform 140ms ease;
}

.editor-section:not(.is-collapsed) .section-collapse-icon {
  transform: rotate(225deg) translate(-1px, -1px);
}

.editor-section.is-focused {
  scroll-margin-top: 24px;
}

.editor-section.is-focused > .editor-block-header {
  background: var(--app-primary-soft);
}

.editor-section-content {
  display: grid;
  gap: 16px;
  min-width: 0;
}

.editor-collapsed-summary {
  margin: -5px 0 0 44px;
  color: var(--app-text-muted);
  font-size: 12px;
}

.editor-field {
  display: grid;
  min-width: 0;
  gap: 6px;
  padding: 5px 7px 7px;
  border: 1px solid transparent;
  transition: border-color 140ms ease, background-color 140ms ease;
}

.editor-field:hover,
.editor-field:focus-within {
  border-color: var(--app-border-strong);
  background: var(--app-surface-soft);
}

.editor-field:focus-within {
  border-color: var(--app-primary);
  background: var(--app-primary-soft);
}

.editor-field > span,
.entry-bullets-label {
  color: var(--app-text-secondary);
  font-size: 12px;
  font-weight: 700;
}

.entry-optional-fields {
  max-width: 760px;
  border-top: 1px solid var(--app-border-soft);
  padding-top: 12px;
}

.entry-optional-fields summary,
.inline-more summary,
.entry-more summary {
  width: fit-content;
  color: var(--app-primary);
  font-size: 12px;
  font-weight: 700;
  cursor: pointer;
}

.section-heading {
  display: grid;
  gap: 2px;
  min-width: 0;
}

.section-kind {
  color: var(--app-text-muted);
  font-size: 11px;
  font-weight: 700;
}

.section-title-display {
  max-width: 560px;
  overflow: hidden;
  border: 0;
  padding: 0;
  color: var(--app-text);
  font: inherit;
  font-size: 18px;
  font-weight: 750;
  line-height: 1.4;
  text-align: left;
  text-overflow: ellipsis;
  white-space: nowrap;
  background: transparent;
  cursor: text;
}

.section-title-display:hover {
  color: var(--app-primary);
}

.section-title-input {
  width: min(560px, 100%);
}

.entry-more,
.inline-more {
  position: relative;
  flex: 0 0 auto;
}

.entry-more summary,
.inline-more summary {
  list-style: none;
}

.entry-more summary::-webkit-details-marker,
.inline-more summary::-webkit-details-marker {
  display: none;
}

.entry-more-menu,
.inline-more-menu {
  position: absolute;
  z-index: 5;
  top: calc(100% + 7px);
  right: 0;
  display: grid;
  min-width: 142px;
  gap: 2px;
  padding: 5px;
  border: 1px solid var(--app-border);
  border-radius: var(--app-radius-md);
  background: var(--app-surface);
  box-shadow: var(--app-shadow-soft);
}

.entry-more-menu button,
.inline-more-menu button {
  border: 0;
  padding: 8px 9px;
  color: var(--app-text-secondary);
  font: inherit;
  font-size: 12px;
  text-align: left;
  background: transparent;
  cursor: pointer;
}

.entry-more-menu button:hover,
.entry-more-menu button:focus-visible,
.inline-more-menu button:hover,
.inline-more-menu button:focus-visible {
  color: var(--app-text);
  background: var(--app-surface-soft);
}

.editor-empty {
  margin: 0;
  color: var(--app-text-muted);
  font-size: 13px;
}

.editor-entry {
  display: grid;
  gap: 14px;
  min-width: 0;
  margin: 0 -8px;
  padding: 18px 8px 0;
  border: 1px solid transparent;
  border-top-color: var(--app-border-soft);
  transition: border-color 140ms ease, background-color 140ms ease;
}

.editor-entry:hover,
.editor-entry:focus-within {
  border-color: var(--app-border-strong);
  background: color-mix(in srgb, var(--app-surface-soft) 70%, transparent);
}

.entry-grid,
.skill-grid {
  display: grid;
  gap: 14px;
  grid-template-columns: repeat(2, minmax(0, 1fr));
}

.date-grid {
  display: grid;
  grid-template-columns: repeat(2, minmax(0, 1fr));
  grid-column: 1 / -1;
  gap: 10px;
}

.entry-optional-fields {
  display: grid;
  gap: 10px;
}

.entry-optional-fields .editor-field {
  max-width: 360px;
}

.add-field-control {
  justify-self: start;
  border: 0;
  padding: 0;
  color: var(--app-primary);
  font: inherit;
  font-size: 12px;
  cursor: pointer;
  background: transparent;
}

.entry-bullets-label {
  margin-top: 2px;
}

.bullet-block {
  display: grid;
  gap: 9px;
  min-width: 0;
}

.bullet-block.is-evidence-focus {
  margin: -5px;
  padding: 5px;
  border: 1px solid var(--app-accent);
  background: var(--app-accent-soft);
}

.bullet-line {
  display: grid;
  grid-template-columns: minmax(0, 1fr) auto;
  gap: 10px;
  align-items: start;
  border: 1px solid transparent;
  transition: border-color 140ms ease, background-color 140ms ease;
}

.bullet-line:hover,
.bullet-line:focus-within {
  border-color: var(--app-border-strong);
  background: var(--app-primary-soft);
}

.bullet-field {
  display: block;
  min-width: 0;
}

.bullet-actions {
  display: flex;
  align-items: center;
  gap: 10px;
  min-height: 32px;
  opacity: 0;
  transition: opacity 0.15s ease;
}

.bullet-actions.has-suggestion-entry,
.bullet-line:hover .bullet-actions,
.bullet-line:focus-within .bullet-actions,
.bullet-actions:focus-within {
  opacity: 1;
}

.bullet-suggest-button {
  color: var(--app-primary);
}

.entry-actions,
.section-footer {
  display: flex;
}

.entry-actions {
  padding-top: 2px;
}

.entry-more-row {
  display: flex;
  justify-content: flex-end;
}

.danger-action {
  color: var(--app-danger) !important;
}

/* The editor keeps every structured field and action, but presents them as one document of record. */
.resume-editor {
  display: block;
  min-width: 0;
  padding: 14px 12px 38px;
  background: var(--app-stage);
}

.resume-paper {
  position: relative;
  width: min(760px, 100%);
  min-height: 850px;
  margin: 0 auto;
  padding: 30px 49px 58px;
  background: var(--app-document);
  border: 1px solid var(--app-border);
  box-shadow: var(--app-shadow-page);
}

.resume-page-meta,
.resume-page-footer {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 18px;
  color: var(--app-text-muted);
  font-family: 'IBM Plex Mono', 'SFMono-Regular', Consolas, monospace;
  font-size: 8px;
  font-weight: 650;
  letter-spacing: 0.07em;
  text-transform: uppercase;
}

.resume-page-meta {
  padding-bottom: 20px;
  border-bottom: 1px solid var(--app-text);
}

.resume-page-footer {
  position: absolute;
  right: 49px;
  bottom: 20px;
  left: 49px;
  padding-top: 8px;
  border-top: 1px solid var(--app-border);
}

.editor-block {
  gap: 14px;
  padding-bottom: 24px;
  border-bottom-color: var(--app-border);
}

.editor-basics {
  padding-top: 24px;
}

.editor-block-header h2 {
  color: var(--app-text);
}

.editor-basics .editor-block-header {
  align-items: flex-end;
}

.editor-basics .editor-block-header h2 {
  margin-bottom: 6px;
  font-family: Georgia, 'Songti SC', serif;
  font-size: 34px;
  font-weight: 650;
  line-height: 1;
  letter-spacing: -0.05em;
}

.editor-basics .editor-block-header p {
  color: var(--app-accent);
  font-size: 11px;
}

.editor-field > span,
.entry-bullets-label {
  color: var(--app-text-muted);
  font-family: 'IBM Plex Mono', 'SFMono-Regular', Consolas, monospace;
  font-size: 9px;
  font-weight: 700;
  letter-spacing: 0.04em;
  text-transform: uppercase;
}

:deep(.el-input__wrapper) {
  min-height: 30px;
  border-radius: 0;
  padding: 0;
  background: transparent;
  box-shadow: 0 1px 0 var(--app-border) !important;
}

:deep(.el-input__wrapper:hover),
:deep(.el-input__wrapper.is-focus) {
  box-shadow: 0 2px 0 var(--app-primary) !important;
}

:deep(.el-input__inner) {
  color: var(--app-text);
  font-size: 12px;
}

:deep(.el-textarea__inner) {
  min-height: 31px;
  border: 0;
  border-radius: 0;
  padding: 6px 7px;
  color: var(--app-text);
  font-size: 12px;
  line-height: 1.55;
  background: transparent;
  box-shadow: 0 1px 0 var(--app-border) !important;
  resize: vertical;
}

:deep(.el-textarea__inner:hover),
:deep(.el-textarea__inner:focus) {
  box-shadow: 0 2px 0 var(--app-primary) !important;
}

.contact-type {
  height: 30px;
  border-color: var(--app-border);
  border-radius: 0;
  padding: 0 7px;
  color: var(--app-text);
  font-size: 11px;
  background: transparent;
}

.section-title-display {
  color: var(--app-text);
  font-family: 'IBM Plex Mono', 'SFMono-Regular', Consolas, monospace;
  font-size: 10px;
  font-weight: 750;
  letter-spacing: 0.08em;
  text-transform: uppercase;
}

.section-title-display:hover {
  color: var(--app-primary-active);
}

.section-kind {
  color: var(--app-text-muted);
  font-family: 'IBM Plex Mono', 'SFMono-Regular', Consolas, monospace;
  font-size: 9px;
  letter-spacing: 0.05em;
  text-transform: uppercase;
}

.editor-entry {
  gap: 12px;
  padding-top: 17px;
  border-top-color: var(--app-border-soft);
}

.entry-grid,
.skill-grid {
  column-gap: 18px;
  row-gap: 11px;
}

.entry-bullets-label {
  margin-top: 4px;
}

.bullet-line {
  gap: 8px;
}

.bullet-field {
  position: relative;
  padding-left: 14px;
}

.bullet-field::before {
  position: absolute;
  top: 10px;
  left: 3px;
  width: 4px;
  height: 4px;
  border-radius: 50%;
  background: var(--app-text-muted);
  content: '';
}

.bullet-actions {
  min-height: 30px;
}

.bullet-suggest-button {
  color: var(--app-primary-active);
}

.entry-actions,
.section-footer {
  padding-top: 3px;
}

.add-field-control,
.section-footer :deep(.el-button),
.entry-actions :deep(.el-button) {
  color: var(--app-text-secondary);
}

/* Document-first surface: fields remain available, but only appear in a contextual edit state. */
.resume-editor .sr-only {
  position: absolute;
  width: 1px;
  height: 1px;
  overflow: hidden;
  clip: rect(0, 0, 0, 0);
  clip-path: inset(50%);
  white-space: nowrap;
}

.resume-editor {
  display: block;
  padding: 10px 14px 30px;
  background: var(--app-stage);
}

.resume-paper {
  width: min(820px, 100%);
  min-height: 0;
  margin: 0 auto;
  padding: 26px 40px 32px;
  border-color: var(--app-border-strong);
  box-shadow: 0 5px 18px color-mix(in srgb, var(--app-text) 8%, transparent);
}

.resume-page-meta {
  padding-bottom: 13px;
  border-bottom-color: var(--app-border);
  color: var(--app-text-muted);
}

.resume-page-footer {
  position: static;
  margin-top: 22px;
  padding-top: 8px;
}

.editor-block {
  gap: 13px;
  padding-bottom: 20px;
}

.editor-basics {
  padding-top: 20px;
}

.editor-basics-header {
  align-items: flex-start !important;
}

.resume-identity {
  display: grid;
  min-width: 0;
  gap: 5px;
}

.identity-name,
.identity-name-input {
  max-width: 100%;
}

.identity-name {
  width: fit-content;
  max-width: 100%;
  overflow-wrap: anywhere;
  border: 0;
  padding: 0;
  color: var(--app-text);
  font-family: Georgia, 'Songti SC', serif;
  font-size: 32px;
  font-weight: 650;
  line-height: 1.05;
  letter-spacing: -0.05em;
  text-align: left;
  background: transparent;
  cursor: text;
}

.identity-name:hover,
.identity-name:focus-visible {
  color: var(--app-primary-active);
}

.identity-name:focus-visible {
  outline: 2px solid var(--app-primary);
  outline-offset: 4px;
}

.identity-target {
  margin: 0;
  color: var(--app-text-secondary);
  font-size: 12px;
}

.identity-name-input :deep(.el-input__wrapper) {
  min-height: 39px;
  box-shadow: 0 1px 0 var(--app-primary) !important;
}

.identity-name-input :deep(.el-input__inner) {
  font-family: Georgia, 'Songti SC', serif;
  font-size: 30px;
}

.basics-more {
  position: relative;
  flex: 0 0 auto;
}

.basics-more summary {
  border: 0;
  padding: 2px 4px;
  color: var(--app-text-muted);
  font-size: 18px;
  line-height: 1;
  cursor: pointer;
  list-style: none;
}

.basics-more summary::-webkit-details-marker {
  display: none;
}

.basics-more summary:hover,
.basics-more summary:focus-visible,
.basics-more[open] summary {
  color: var(--app-primary-active);
  background: var(--app-primary-soft);
}

.basics-more-menu {
  position: absolute;
  z-index: 5;
  top: calc(100% + 7px);
  right: 0;
  display: grid;
  width: min(300px, calc(100vw - 64px));
  gap: 10px;
  padding: 12px;
  border: 1px solid var(--app-border);
  border-radius: var(--app-radius-md);
  background: var(--app-surface);
  box-shadow: var(--app-shadow-soft);
}

.basics-more-menu label {
  display: grid;
  gap: 4px;
  color: var(--app-text-secondary);
  font-size: 11px;
  font-weight: 700;
}

.basics-more-menu label :deep(.el-input__wrapper) {
  min-height: 29px;
  box-shadow: 0 1px 0 var(--app-border) !important;
}

.basics-document {
  min-width: 0;
}

.contact-line {
  display: flex;
  min-width: 0;
  align-items: center;
  gap: 6px 13px;
  flex-wrap: wrap;
  color: var(--app-text-secondary);
  font-size: 12px;
}

.contact-token,
.contact-add,
.inline-done {
  border: 0;
  padding: 0;
  color: var(--app-text-secondary);
  font: inherit;
  font-size: 12px;
  background: transparent;
  cursor: pointer;
}

.contact-token {
  min-width: 0;
  max-width: 100%;
  overflow-wrap: anywhere;
  line-height: 1.55;
  text-align: left;
}

.contact-item {
  display: flex;
  min-width: 0;
  max-width: 100%;
  align-items: baseline;
  gap: 6px;
}

.contact-divider {
  flex: 0 0 auto;
  color: var(--app-border-strong);
}

.contact-token:hover,
.contact-token:focus-visible,
.contact-add:hover,
.contact-add:focus-visible,
.inline-done:hover,
.inline-done:focus-visible {
  color: var(--app-primary-active);
}

.contact-token.is-empty {
  color: var(--app-text-muted);
  font-style: italic;
}

.contact-inline-editor {
  display: flex;
  width: min(430px, 100%);
  min-width: 0;
  align-items: center;
  gap: 7px;
  flex-wrap: wrap;
  padding-bottom: 4px;
  border-bottom: 1px solid var(--app-primary);
}

.contact-inline-editor .contact-type {
  width: 74px;
  height: 27px;
  border-color: var(--app-border);
  border-radius: 0;
  font-size: 11px;
}

.contact-inline-editor :deep(.el-input) {
  min-width: 150px;
  flex: 1 1 180px;
}

.contact-inline-editor :deep(.el-input__wrapper) {
  min-height: 27px;
  box-shadow: none !important;
}

.inline-done,
.inline-delete {
  color: var(--app-primary-active);
  font-weight: 700;
  white-space: nowrap;
}

.inline-delete {
  color: var(--app-danger);
}

.editor-section .editor-block-header {
  display: grid;
  grid-template-columns: auto minmax(0, 1fr) auto auto;
  align-items: center;
  gap: 8px;
}

.section-collapse-toggle {
  width: 20px;
  height: 20px;
  margin: 0;
  border: 0;
  background: transparent;
}

.section-collapse-toggle:hover,
.section-collapse-toggle:focus-visible {
  background: var(--app-primary-soft);
}

.section-heading {
  min-width: 0;
}

.section-kind {
  font-size: 8px;
}

.section-title-display {
  max-width: 100%;
  overflow: visible;
  overflow-wrap: anywhere;
  font-family: Georgia, 'Songti SC', serif;
  font-size: 18px;
  font-weight: 700;
  letter-spacing: -0.02em;
  line-height: 1.35;
  text-align: left;
  text-overflow: clip;
  text-transform: none;
  white-space: normal;
}

.section-entry-count {
  color: var(--app-text-muted);
  font-family: var(--app-font-mono);
  font-size: 9px;
  white-space: nowrap;
}

.entry-more summary,
.inline-more summary {
  min-width: 20px;
  padding: 2px 4px;
  color: var(--app-text-muted);
  font-size: 16px;
  line-height: 1;
  text-align: center;
}

.entry-more summary:hover,
.entry-more summary:focus-visible,
.inline-more summary:hover,
.inline-more summary:focus-visible {
  color: var(--app-primary-active);
  background: var(--app-primary-soft);
}

.editor-section.is-focused > .editor-block-header {
  background: color-mix(in srgb, var(--app-focus-soft) 42%, transparent);
  box-shadow: inset 3px 0 0 var(--app-focus);
  padding: 5px 7px;
}

.editor-section.is-focused {
  scroll-margin-top: 20px;
}

.editor-section {
  position: relative;
  cursor: grab;
  transition: background-color 160ms ease, box-shadow 160ms ease;
}

.editor-section :deep(input),
.editor-section :deep(textarea),
.editor-section :deep(.el-input),
.editor-section :deep(.el-input__wrapper),
.editor-section :deep(.el-textarea),
.editor-section [role='textbox'],
.editor-section button,
.editor-section a,
.editor-section select,
.editor-section summary,
.editor-section [contenteditable='true'] {
  cursor: text;
}

.editor-section button,
.editor-section a,
.editor-section select,
.editor-section summary {
  cursor: pointer;
}

.editor-section.is-reorder-source,
.editor-section.is-reorder-source * {
  cursor: grabbing !important;
}

.editor-section.is-reorder-source {
  user-select: none;
  background: color-mix(in srgb, var(--app-primary-soft) 44%, transparent);
  box-shadow: 0 7px 18px color-mix(in srgb, var(--app-text) 10%, transparent);
}

.editor-section.is-drop-before::before,
.editor-section.is-drop-after::after {
  position: absolute;
  z-index: 2;
  right: 0;
  left: 0;
  height: 2px;
  border-radius: 2px;
  background: var(--app-primary);
  box-shadow: 0 0 0 2px color-mix(in srgb, var(--app-primary) 14%, transparent);
  content: '';
  pointer-events: none;
}

.editor-section.is-drop-before::before {
  top: -1px;
}

.editor-section.is-drop-after::after {
  bottom: -1px;
}

.editor-section-content {
  gap: 12px;
}

.editor-entry {
  gap: 9px;
  margin: 0;
  padding: 14px 0 0;
  border-top-color: var(--app-border-soft);
  background: transparent;
}

.editor-entry:hover,
.editor-entry:focus-within {
  border-color: transparent;
  background: transparent;
}

.entry-document-heading {
  display: grid;
  min-width: 0;
  grid-template-columns: minmax(0, 1fr) minmax(0, 38%);
  gap: 4px 12px;
  align-items: start;
}

.entry-title-display,
.entry-meta-display {
  min-width: 0;
  overflow-wrap: anywhere;
  border: 0;
  padding: 0;
  text-align: left;
  white-space: normal;
  background: transparent;
  cursor: text;
}

.entry-title-display {
  color: var(--app-text);
  font-size: 14px;
  font-weight: 750;
}

.entry-meta-display {
  color: var(--app-text-secondary);
  font-size: 11px;
  line-height: 1.45;
}

.entry-title-display:hover,
.entry-title-display:focus-visible,
.entry-meta-display:hover,
.entry-meta-display:focus-visible {
  color: var(--app-primary-active);
}

.entry-title-display:focus-visible,
.entry-meta-display:focus-visible {
  outline: 2px solid var(--app-primary);
  outline-offset: 3px;
}

.entry-inline-editor {
  display: grid;
  min-width: 0;
  grid-column: 1 / -1;
  grid-template-columns: repeat(2, minmax(0, 1fr));
  gap: 7px;
  align-items: center;
  padding: 5px 0 7px;
  border-bottom: 1px solid var(--app-primary);
}

.entry-inline-editor :deep(.el-input__wrapper) {
  min-height: 28px;
  box-shadow: 0 1px 0 var(--app-border) !important;
}

.entry-inline-editor .inline-done {
  justify-self: start;
  grid-column: 1 / -1;
}

.entry-more-row {
  margin-top: -5px;
  opacity: 0;
  transition: opacity 140ms ease;
}

.editor-entry:hover .entry-more-row,
.editor-entry:focus-within .entry-more-row {
  opacity: 1;
}

.editor-entry > .skill-grid,
.editor-entry > .entry-grid,
.editor-entry > .entry-optional-fields,
.editor-entry > .add-field-control,
.editor-entry > .entry-bullets-label {
  display: none;
}

.editor-entry > .entry-actions,
.editor-section > .section-footer {
  height: 0;
  min-height: 0;
  overflow: hidden;
  padding: 0;
  opacity: 0;
  transition: opacity 140ms ease;
}

.editor-entry:hover > .entry-actions,
.editor-entry:focus-within > .entry-actions,
.editor-section:hover > .section-footer,
.editor-section:focus-within > .section-footer {
  height: auto;
  min-height: 24px;
  opacity: 1;
}

.bullet-block {
  gap: 3px;
}

.bullet-block.is-evidence-focus {
  margin: -4px 0 -4px -10px;
  padding: 5px 8px 5px 10px;
  border: 1px solid color-mix(in srgb, var(--app-focus) 38%, var(--app-border));
  border-left: 3px solid var(--app-focus);
  border-radius: var(--app-radius-sm);
  background: color-mix(in srgb, var(--app-focus-soft) 58%, var(--app-surface));
  animation: resume-focus-pulse 1.6s ease-out both;
}

.bullet-line {
  grid-template-columns: minmax(0, 1fr) auto;
  gap: 9px;
  border: 0;
  background: transparent;
}

.bullet-line:hover,
.bullet-line:focus-within {
  border: 0;
  background: transparent;
}

.bullet-field {
  padding-left: 15px;
}

.bullet-field::before {
  top: 10px;
  left: 2px;
  width: 4px;
  height: 4px;
  background: var(--app-text-muted);
}

:deep(.bullet-line .el-textarea__inner) {
  min-height: 24px;
  max-width: 100%;
  overflow-x: hidden;
  overflow-wrap: anywhere;
  padding: 1px 0;
  color: var(--app-text);
  font-size: 12px;
  line-height: 1.55;
  word-break: break-word;
  resize: none;
  box-shadow: none !important;
}

:deep(.bullet-line .el-textarea__inner:hover),
:deep(.bullet-line .el-textarea__inner:focus) {
  box-shadow: none !important;
}

.bullet-actions,
.bullet-actions.has-suggestion-entry {
  min-height: 27px;
  opacity: 0;
}

.bullet-line:hover .bullet-actions,
.bullet-line:focus-within .bullet-actions,
.bullet-actions:focus-within {
  opacity: 1 !important;
}

.bullet-line:hover .bullet-suggest-button,
.bullet-suggest-button:focus-visible {
  border-color: var(--app-primary) !important;
  color: var(--app-primary-active) !important;
  background: var(--app-surface) !important;
}

.entry-actions :deep(.el-button),
.section-footer :deep(.el-button) {
  min-height: 24px;
  border: 0;
  padding: 0;
  color: var(--app-text-muted);
  font-size: 10px;
  background: transparent;
}

.entry-actions :deep(.el-button:hover),
.section-footer :deep(.el-button:hover) {
  color: var(--app-primary-active);
}

@keyframes resume-focus-pulse {
  0% { box-shadow: 0 0 0 4px color-mix(in srgb, var(--app-focus) 18%, transparent); }
  100% { box-shadow: 0 0 0 0 transparent; }
}

@media (prefers-reduced-motion: reduce) {
  .bullet-block.is-evidence-focus {
    animation: none;
  }
}

@media (max-width: 760px) {
  .resume-editor {
    padding: 8px 8px 24px;
  }

  .resume-paper {
    padding: 20px 18px 26px;
  }

  .identity-name {
    font-size: 28px;
  }

  .identity-name-input :deep(.el-input__inner) {
    font-size: 26px;
  }

  .contact-line {
    gap: 5px 10px;
  }

  .entry-document-heading {
    grid-template-columns: minmax(0, 1fr);
    gap: 3px;
  }

  .entry-meta-display {
    max-width: 100%;
  }

  .entry-inline-editor,
  .entry-inline-editor-grid {
    grid-template-columns: minmax(0, 1fr);
  }

  .entry-inline-editor .inline-done {
    grid-column: auto;
  }

  .bullet-actions {
    gap: 5px;
    opacity: 1;
  }

  .entry-more-row {
    opacity: 1;
  }
}

</style>
