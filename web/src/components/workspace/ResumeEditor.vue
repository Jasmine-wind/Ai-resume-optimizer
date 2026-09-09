<script setup lang="ts">
import { ElMessage, ElMessageBox } from 'element-plus'
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
  reopenInspector: []
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

const visibleContacts = computed(() => compactContactRows(props.document.basics.contacts ?? []))

const REORDER_MOVE_THRESHOLD = 4

type DragState = {
  type: 'section' | 'entry'
  sourceSectionId: string
  sourceEntryId?: string
  pointerId: number
  startX: number
  startY: number
  clientX: number
  clientY: number
  active: boolean
  captureElement: HTMLElement | null
}

type DropTarget =
  | { type: 'section'; targetSectionId: string; position: 'before' | 'after' }
  | { type: 'entry'; targetSectionId: string; targetEntryId?: string; position: 'before' | 'after' | 'end' }

const dragState = ref<DragState | null>(null)
const dropTarget = ref<DropTarget | null>(null)
const entryDropUnavailable = ref(false)
const reorderAnnouncement = ref('')
let autoScrollFrame: number | null = null
const editingName = ref(false)
const editingContactId = ref<string | null>(null)
const editingSectionTitle = ref<string | null>(null)
const editingEntryKey = ref<string | null>(null)
const expandedSectionIds = ref<Set<string>>(new Set())
const editorRoot = ref<HTMLElement | null>(null)
const basicsDetails = ref<HTMLDetailsElement | null>(null)
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
    ? editorRoot.value?.querySelector<HTMLElement>(`[data-bullet-id="${props.focusedBulletId}"]`)
    : null
  const target = bullet ?? (selectedSection ? sectionElements.get(selectedSection.id) : null)
  if (!target) return
  const scrollContainer = target.closest<HTMLElement>('.resume-stage-scroll')
  if (scrollContainer) {
    const targetTop =
      target.getBoundingClientRect().top - scrollContainer.getBoundingClientRect().top
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

const isStructuredSection = (kind: string) =>
  ['EXPERIENCE', 'PROJECT', 'EDUCATION', 'SKILL'].includes(kind)

const entryTitle = (entry: ResumeDocumentEntry, kind: string) => {
  if (kind === 'EDUCATION') return entry.school || entry.degree || '教育经历'
  if (kind === 'SKILL') return entry.group || '技能组'
  if (kind === 'PROJECT') return entry.organization || entry.role || '项目经历'
  if (kind === 'EXPERIENCE') return entry.organization || entry.role || '工作经历'
  return '内容'
}

const entryMeta = (entry: ResumeDocumentEntry, kind: string) => {
  const values = kind === 'EDUCATION' ? [entry.degree, entry.major] : [entry.role, entry.location]
  const dates =
    entry.startDate && entry.endDate
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

const areEntryKindsCompatible = (sourceKind: string, targetKind: string) =>
  sourceKind === targetKind

const clearDrag = () => {
  const current = dragState.value
  if (
    current?.captureElement &&
    typeof current.captureElement.hasPointerCapture === 'function' &&
    current.captureElement.hasPointerCapture(current.pointerId)
  ) {
    current.captureElement.releasePointerCapture?.(current.pointerId)
  }
  if (autoScrollFrame !== null) {
    window.cancelAnimationFrame(autoScrollFrame)
    autoScrollFrame = null
  }
  dragState.value = null
  dropTarget.value = null
  entryDropUnavailable.value = false
}

const sectionIsDragSource = (sectionId: string) =>
  dragState.value?.active === true &&
  dragState.value.type === 'section' &&
  dragState.value.sourceSectionId === sectionId

const entryIsDragSource = (sectionId: string, entryId: string) =>
  dragState.value?.active === true &&
  dragState.value.type === 'entry' &&
  dragState.value.sourceSectionId === sectionId &&
  dragState.value.sourceEntryId === entryId

const sectionDropClass = (sectionId: string, position: 'before' | 'after') =>
  dropTarget.value?.type === 'section' &&
  dropTarget.value.targetSectionId === sectionId &&
  dropTarget.value.position === position

const entryDropClass = (sectionId: string, entryId: string, position: 'before' | 'after') =>
  dropTarget.value?.type === 'entry' &&
  dropTarget.value.targetSectionId === sectionId &&
  dropTarget.value.targetEntryId === entryId &&
  dropTarget.value.position === position

const entryDropEndClass = (sectionId: string) =>
  dropTarget.value?.type === 'entry' &&
  dropTarget.value.targetSectionId === sectionId &&
  dropTarget.value.position === 'end'

const beginDrag = (state: DragState, event: PointerEvent) => {
  if (event.button !== undefined && event.button !== 0) return
  clearDrag()
  dragState.value = state
  const handle = event.currentTarget instanceof HTMLElement ? event.currentTarget : null
  state.captureElement = handle
  handle?.setPointerCapture?.(event.pointerId)
}

const sectionDropTargetAt = (clientY: number) => {
  const state = dragState.value
  if (!state || state.type !== 'section' || !editorRoot.value) return
  const sections = Array.from(
    editorRoot.value.querySelectorAll<HTMLElement>('.editor-section[data-section-id]'),
  ).filter((element) => element.dataset.sectionId !== state.sourceSectionId)
  if (sections.length === 0) {
    dropTarget.value = null
    return
  }
  let target = sections[sections.length - 1]
  let position: 'before' | 'after' = 'after'
  for (const section of sections) {
    const rect = section.getBoundingClientRect()
    if (clientY < rect.top + rect.height / 2) {
      target = section
      position = 'before'
      break
    }
  }
  const targetSectionId = target?.dataset.sectionId
  dropTarget.value = targetSectionId
    ? { type: 'section', targetSectionId, position }
    : null
}

const findSectionAtPointer = (clientY: number, sourceKind: string) => {
  if (!editorRoot.value) return { section: null, incompatible: false }
  const sectionElements = Array.from(
    editorRoot.value.querySelectorAll<HTMLElement>('.editor-section[data-section-id]'),
  )
  for (const element of sectionElements) {
    const rect = element.getBoundingClientRect()
    if (clientY < rect.top || clientY > rect.bottom) continue
    const section = props.document.sections.find((item) => item.id === element.dataset.sectionId)
    if (!section) continue
    return { section, incompatible: !areEntryKindsCompatible(sourceKind, section.kind) }
  }
  return { section: null, incompatible: false }
}

const entryDropTargetAt = (clientY: number) => {
  const state = dragState.value
  if (!state || state.type !== 'entry' || !editorRoot.value) return
  const sourceSection = props.document.sections.find((item) => item.id === state.sourceSectionId)
  if (!sourceSection) return
  const located = findSectionAtPointer(clientY, sourceSection.kind)
  entryDropUnavailable.value = located.incompatible
  if (!located.section || located.incompatible) {
    dropTarget.value = null
    return
  }

  const sectionElement = Array.from(
    editorRoot.value.querySelectorAll<HTMLElement>('.editor-section[data-section-id]'),
  ).find((element) => element.dataset.sectionId === located.section?.id)
  if (!sectionElement) {
    dropTarget.value = null
    return
  }
  const entries = Array.from(
    sectionElement.querySelectorAll<HTMLElement>('.editor-entry[data-entry-id]'),
  ).filter((element) => element.dataset.entryId !== state.sourceEntryId)
  if (entries.length === 0) {
    dropTarget.value = { type: 'entry', targetSectionId: located.section.id, position: 'end' }
    return
  }

  let target = entries[entries.length - 1]
  let position: 'before' | 'after' = 'after'
  for (const entry of entries) {
    const rect = entry.getBoundingClientRect()
    if (clientY < rect.top + rect.height / 2) {
      target = entry
      position = 'before'
      break
    }
  }
  const targetEntryId = target?.dataset.entryId
  dropTarget.value = targetEntryId
    ? { type: 'entry', targetSectionId: located.section.id, targetEntryId, position }
    : null
}

const updateDropTarget = (clientY: number) => {
  if (dragState.value?.type === 'section') sectionDropTargetAt(clientY)
  else if (dragState.value?.type === 'entry') entryDropTargetAt(clientY)
}

const updateAutoScroll = (clientY: number) => {
  const container = editorRoot.value?.closest<HTMLElement>('.resume-stage-scroll')
  const state = dragState.value
  if (!container || !state?.active) return
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
    const current = dragState.value
    const currentContainer = editorRoot.value?.closest<HTMLElement>('.resume-stage-scroll')
    if (!current?.active || !currentContainer) {
      autoScrollFrame = null
      return
    }
    const currentRect = currentContainer.getBoundingClientRect()
    const currentDirection =
      current.clientY < currentRect.top + edge
        ? -1
        : current.clientY > currentRect.bottom - edge
          ? 1
          : 0
    if (currentDirection === 0) {
      autoScrollFrame = null
      return
    }
    currentContainer.scrollTop += currentDirection * 6
    updateDropTarget(current.clientY)
    autoScrollFrame = window.requestAnimationFrame(scroll)
  }
  autoScrollFrame = window.requestAnimationFrame(scroll)
}

const handleSectionPointerDown = (sectionId: string, event: PointerEvent) => {
  beginDrag(
    {
      type: 'section',
      sourceSectionId: sectionId,
      pointerId: event.pointerId,
      startX: event.clientX,
      startY: event.clientY,
      clientX: event.clientX,
      clientY: event.clientY,
      active: false,
      captureElement: null,
    },
    event,
  )
}

const handleEntryPointerDown = (sectionId: string, entryId: string, event: PointerEvent) => {
  beginDrag(
    {
      type: 'entry',
      sourceSectionId: sectionId,
      sourceEntryId: entryId,
      pointerId: event.pointerId,
      startX: event.clientX,
      startY: event.clientY,
      clientX: event.clientX,
      clientY: event.clientY,
      active: false,
      captureElement: null,
    },
    event,
  )
}

const handlePointerMove = (event: PointerEvent) => {
  const state = dragState.value
  if (!state || event.pointerId !== state.pointerId) return
  state.clientX = event.clientX
  state.clientY = event.clientY
  if (!state.active) {
    if (Math.hypot(state.clientX - state.startX, state.clientY - state.startY) <= REORDER_MOVE_THRESHOLD)
      return
    state.active = true
  }
  event.preventDefault()
  updateDropTarget(state.clientY)
  updateAutoScroll(state.clientY)
}

const finishPointerDrag = () => {
  const state = dragState.value
  const target = dropTarget.value
  if (!state?.active || !target) {
    clearDrag()
    return
  }

  if (state.type === 'section' && target.type === 'section') {
    const sourceIndex = props.document.sections.findIndex((item) => item.id === state.sourceSectionId)
    const targetIndex = props.document.sections.findIndex((item) => item.id === target.targetSectionId)
    const targetAfterRemoval = targetIndex > sourceIndex ? targetIndex - 1 : targetIndex
    const nextIndex = target.position === 'before' ? targetAfterRemoval : targetAfterRemoval + 1
    const source = props.document.sections[sourceIndex]
    if (source && sourceIndex >= 0 && targetIndex >= 0 && nextIndex !== sourceIndex) {
      mutate((doc) => {
        const [section] = doc.sections.splice(sourceIndex, 1)
        if (section) doc.sections.splice(nextIndex, 0, section)
      })
      clearDrag()
      reorderAnnouncement.value = `已将${sectionTitle(source)}移动到第 ${nextIndex + 1} 项`
      return
    }
  }

  if (state.type === 'entry' && target.type === 'entry') {
    const sourceSection = props.document.sections.find((item) => item.id === state.sourceSectionId)
    const targetSection = props.document.sections.find((item) => item.id === target.targetSectionId)
    if (
      sourceSection &&
      targetSection &&
      state.sourceEntryId &&
      areEntryKindsCompatible(sourceSection.kind, targetSection.kind)
    ) {
      const sourceIndex = sourceSection.entries.findIndex((item) => item.id === state.sourceEntryId)
      const targetIndex = target.targetEntryId
        ? targetSection.entries.findIndex((item) => item.id === target.targetEntryId)
        : targetSection.entries.length
      const sameArray = sourceSection.id === targetSection.id
      const targetAfterRemoval = sameArray && targetIndex > sourceIndex ? targetIndex - 1 : targetIndex
      const nextIndex = target.position === 'end'
        ? targetAfterRemoval
        : target.position === 'before'
          ? targetAfterRemoval
          : targetAfterRemoval + 1
      if (sourceIndex >= 0 && targetIndex >= 0 && (!sameArray || nextIndex !== sourceIndex)) {
        const sourceTitle = entryTitle(sourceSection.entries[sourceIndex]!, sourceSection.kind)
        mutate((doc) => {
          const nextSource = doc.sections.find((item) => item.id === state.sourceSectionId)
          const nextTarget = doc.sections.find((item) => item.id === target.targetSectionId)
          if (!nextSource || !nextTarget) return
          const [entry] = nextSource.entries.splice(sourceIndex, 1)
          if (entry) nextTarget.entries.splice(nextIndex, 0, entry)
        })
        clearDrag()
        const positionText = `第 ${nextIndex + 1} 项`
        reorderAnnouncement.value = `已将${sourceTitle}移动到${positionText}`
        return
      }
    }
  }
  clearDrag()
}

const moveEntryWithKeyboard = (sectionId: string, entryId: string, delta: number) => {
  const section = props.document.sections.find((item) => item.id === sectionId)
  if (!section) return
  const index = section.entries.findIndex((item) => item.id === entryId)
  const targetIndex = index + delta
  if (index < 0 || targetIndex < 0 || targetIndex >= section.entries.length) return
  const entry = section.entries[index]
  mutate((doc) => {
    const nextSection = doc.sections.find((item) => item.id === sectionId)
    if (!nextSection) return
    const [moved] = nextSection.entries.splice(index, 1)
    if (moved) nextSection.entries.splice(targetIndex, 0, moved)
  })
  if (entry) reorderAnnouncement.value = `已将${entryTitle(entry, section.kind)}移动到第 ${targetIndex + 1} 项`
}

const handleEntryKeydown = (sectionId: string, entryId: string, event: KeyboardEvent) => {
  if (!event.altKey || event.ctrlKey || event.metaKey) return
  const delta = event.key === 'ArrowUp' ? -1 : event.key === 'ArrowDown' ? 1 : 0
  if (delta === 0) return
  event.preventDefault()
  moveEntryWithKeyboard(sectionId, entryId, delta)
}

const handleSectionKeydown = (sectionId: string, index: number, event: KeyboardEvent) => {
  if (event.target !== event.currentTarget || !event.altKey || event.ctrlKey || event.metaKey) return
  const delta = event.key === 'ArrowUp' ? -1 : event.key === 'ArrowDown' ? 1 : 0
  if (delta === 0 || index + delta < 0 || index + delta >= props.document.sections.length) return
  event.preventDefault()
  const section = props.document.sections.find((candidate) => candidate.id === sectionId)
  moveSection(index, delta)
  if (section)
    reorderAnnouncement.value = `已将${sectionTitle(section)}移动到第 ${index + delta + 1} 项`
}

const handleBasicsOutsidePointerDown = (event: PointerEvent) => {
  if (
    basicsDetails.value?.open &&
    event.target instanceof Node &&
    !basicsDetails.value.contains(event.target)
  ) {
    basicsDetails.value.open = false
  }
}

const handleBasicsEscape = (event: KeyboardEvent) => {
  if (event.key === 'Escape' && basicsDetails.value?.open) {
    basicsDetails.value.open = false
    event.stopPropagation()
  }
}

onMounted(() => {
  document.addEventListener('pointerdown', handleBasicsOutsidePointerDown)
  document.addEventListener('keydown', handleBasicsEscape)
  window.addEventListener('pointermove', handlePointerMove)
  window.addEventListener('pointerup', finishPointerDrag)
  window.addEventListener('pointercancel', clearDrag)
})

onBeforeUnmount(() => {
  clearDrag()
  document.removeEventListener('pointerdown', handleBasicsOutsidePointerDown)
  document.removeEventListener('keydown', handleBasicsEscape)
  window.removeEventListener('pointermove', handlePointerMove)
  window.removeEventListener('pointerup', finishPointerDrag)
  window.removeEventListener('pointercancel', clearDrag)
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
  const structured = isStructuredSection(section.kind)
  editingEntryKey.value = structured ? entryKey(sectionId, entryId) : null
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
      bullets: structured ? [] : [{ id: newId(), text: '' }],
    })
  })
  void focusEditorElement(
    `[data-entry-id="${entryId}"] input, [data-entry-id="${entryId}"] textarea`,
  )
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
      return '添加内容'
  }
}

const entryContentLabel = (kind: string) => {
  if (kind === 'SKILL') return '技能项'
  if (kind === 'EXPERIENCE') return '工作要点'
  if (kind === 'PROJECT') return '项目要点'
  return '内容'
}

const entryDeleteTitle = (kind: string) => {
  switch (kind) {
    case 'EXPERIENCE':
      return '删除这段工作经历？'
    case 'PROJECT':
      return '删除这段项目经历？'
    case 'EDUCATION':
      return '删除这段教育经历？'
    case 'SKILL':
      return '删除这个技能组？'
    default:
      return '删除这段内容？'
  }
}

const confirmDeleteEntry = async (sectionId: string, entryId: string, kind: string) => {
  try {
    await ElMessageBox.confirm(
      `其中的${entryContentLabel(kind)}也会一并删除。`,
      entryDeleteTitle(kind),
      { confirmButtonText: '删除', cancelButtonText: '取消', type: 'warning' },
    )
  } catch {
    return
  }
  deleteEntry(sectionId, entryId)
}

const bulletDeleteLabel = (entry: ResumeDocumentEntry, kind: string, index: number) =>
  `删除${entryTitle(entry, kind)}中的第 ${index + 1} 条${entryContentLabel(kind)}`

const SUGGEST_INTENTS: Array<{ command: BulletSuggestIntent | 'CUSTOM'; label: string }> = [
  { command: 'JOB_TARGETED', label: '岗位定向优化' },
  { command: 'SIMPLIFY', label: '精简' },
  { command: 'TECHNICAL_DEPTH', label: '强化技术深度' },
  { command: 'HIGHLIGHT_OUTCOME', label: '突出成果' },
  { command: 'CUSTOM', label: '自定义要求' },
]

const suggestActive = (bulletId: string) =>
  !!props.suggest && props.suggest.activeBulletId.value === bulletId

const handleSuggestButtonClick = (bulletId: string) => {
  if (props.suggest?.activeBulletId.value === bulletId && !props.suggest.busy.value) {
    emit('reopenInspector')
  }
}

const handleSuggestCommand = (bulletId: string, command: BulletSuggestIntent | 'CUSTOM') => {
  if (!props.suggest || props.suggest.busy.value) return
  if (props.suggest.activeBulletId.value === bulletId) {
    emit('reopenInspector')
    return
  }
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
  <div ref="editorRoot" class="resume-editor" :class="{ 'is-entry-drop-unavailable': entryDropUnavailable }">
    <div class="resume-paper">
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
                @update:model-value="
                  (value: string) => updateBasics((basics) => (basics.name = value))
                "
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
            <p v-if="document.basics.jobIntention" class="identity-target">
              {{ document.basics.jobIntention }}
            </p>
          </div>
          <details ref="basicsDetails" class="basics-details">
            <summary aria-label="编辑补充信息">补充信息</summary>
            <div class="basics-details-menu">
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
                <div
                  v-if="editingContactId === contact.id"
                  class="contact-inline-editor"
                  @keydown.esc="finishContactEdit"
                >
                  <select
                    class="contact-type"
                    :value="contact.type || 'OTHER'"
                    :aria-label="`联系方式类型 · ${contact.value || '未填写'}`"
                    @change="
                      (event: Event) =>
                        setContactType(contact.id, (event.target as HTMLSelectElement).value)
                    "
                  >
                    <option
                      v-for="option in RESUME_CONTACT_TYPE_OPTIONS"
                      :key="option.value"
                      :value="option.value"
                    >
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
                  <button type="button" class="inline-delete" @click="deleteContact(contact.id)">
                    删除
                  </button>
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
          'has-focused-bullet': Boolean(
            props.focusedBulletId &&
            section.entries.some((entry) =>
              entry.bullets.some((bullet) => bullet.id === props.focusedBulletId),
            ),
          ),
          'is-empty': section.entries.length === 0,
          'is-reorder-source': sectionIsDragSource(section.id),
          'is-drop-before': sectionDropClass(section.id, 'before'),
          'is-drop-after': sectionDropClass(section.id, 'after'),
          'is-entry-drop-end': entryDropEndClass(section.id),
        }"
        :ref="(element) => setSectionRef(section.id, element)"
        role="group"
        tabindex="0"
        :aria-label="`${sectionTitle(section)}，第 ${sectionIndex + 1} 项`"
        aria-describedby="resume-reorder-help"
        aria-keyshortcuts="Alt+ArrowUp Alt+ArrowDown"
        @keydown="handleSectionKeydown(section.id, sectionIndex, $event)"
      >
        <header class="editor-block-header">
          <button
            type="button"
            class="section-drag-handle"
            :aria-label="`调整${sectionTitle(section)}顺序`"
            aria-describedby="resume-reorder-help"
            aria-keyshortcuts="Alt+ArrowUp Alt+ArrowDown"
            @pointerdown="handleSectionPointerDown(section.id, $event)"
            @keydown="handleSectionKeydown(section.id, sectionIndex, $event)"
          >
            <span class="drag-grip" aria-hidden="true" />
          </button>
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
        </header>

        <div
          v-if="isSectionExpanded(section.id)"
          :id="`editor-section-${section.id}`"
          class="editor-section-content"
        >
          <p v-if="section.entries.length === 0" class="editor-empty">
            该章节暂时没有内容，可以添加{{ sectionEntryLabel(section.kind) }}。
          </p>

          <article
            v-for="entry in section.entries"
            :key="entry.id"
            class="editor-entry"
            :class="{
              'is-generic': !isStructuredSection(section.kind),
              'is-entry-reorder-source': entryIsDragSource(section.id, entry.id),
              'is-entry-drop-before': entryDropClass(section.id, entry.id, 'before'),
              'is-entry-drop-after': entryDropClass(section.id, entry.id, 'after'),
            }"
            :data-entry-id="entry.id"
          >
            <div v-if="isStructuredSection(section.kind)" class="entry-document-heading">
              <button
                type="button"
                class="entry-drag-handle"
                :aria-label="`调整${entryTitle(entry, section.kind)}顺序`"
                aria-describedby="resume-entry-reorder-help"
                aria-keyshortcuts="Alt+ArrowUp Alt+ArrowDown"
                @pointerdown.stop="handleEntryPointerDown(section.id, entry.id, $event)"
                @keydown="handleEntryKeydown(section.id, entry.id, $event)"
              >
                <span class="drag-grip" aria-hidden="true" />
              </button>
              <template v-if="isEntryEditing(section.id, entry.id)">
                <div
                  v-if="section.kind === 'SKILL'"
                  class="entry-inline-editor"
                  @keydown.esc="finishEntryEdit"
                  @keydown.enter.prevent="finishEntryEdit"
                >
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
                    @update:model-value="
                      (value: string) => setSkillItemsText(section.id, entry.id, value)
                    "
                  />
                  <button type="button" class="inline-done" @click="finishEntryEdit">完成</button>
                </div>
                <div
                  v-else-if="section.kind === 'EDUCATION'"
                  class="entry-inline-editor entry-inline-editor-grid"
                  @keydown.esc="finishEntryEdit"
                  @keydown.enter.prevent="finishEntryEdit"
                >
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
                <div
                  v-else
                  class="entry-inline-editor entry-inline-editor-grid"
                  @keydown.esc="finishEntryEdit"
                  @keydown.enter.prevent="finishEntryEdit"
                >
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
                  <el-input
                    :model-value="entry.location ?? ''"
                    :maxlength="LIMITS.entryField"
                    placeholder="地点（可选）"
                    aria-label="地点"
                    @update:model-value="
                      (value: string) =>
                        updateEntry(section.id, entry.id, (target) => (target.location = value))
                    "
                  />
                  <button type="button" class="inline-done" @click="finishEntryEdit">完成</button>
                </div>
              </template>
              <template v-else-if="section.kind === 'SKILL'">
                <button
                  type="button"
                  class="entry-title-display"
                  @click="beginEntryEdit(section.id, entry.id)"
                >
                  {{ entryTitle(entry, section.kind) }}
                </button>
                <button
                  v-if="skillItemsText(entry)"
                  type="button"
                  class="entry-meta-display"
                  @click="beginEntryEdit(section.id, entry.id)"
                >
                  {{ skillItemsText(entry) }}
                </button>
              </template>
              <template v-else>
                <button
                  type="button"
                  class="entry-title-display"
                  @click="beginEntryEdit(section.id, entry.id)"
                >
                  {{ entryTitle(entry, section.kind) }}
                </button>
                <button
                  v-if="entryMeta(entry, section.kind)"
                  type="button"
                  class="entry-meta-display"
                  @click="beginEntryEdit(section.id, entry.id)"
                >
                  {{ entryMeta(entry, section.kind) }}
                </button>
              </template>
              <button
                v-if="!isEntryEditing(section.id, entry.id)"
                type="button"
                class="entry-delete-action is-heading-delete"
                :aria-label="`删除条目：${entryTitle(entry, section.kind)}`"
                @click="confirmDeleteEntry(section.id, entry.id, section.kind)"
              >
                删除条目
              </button>
            </div>
            <button
              v-if="!isStructuredSection(section.kind)"
              type="button"
              class="entry-drag-handle entry-drag-handle-generic"
              :aria-label="`调整${sectionTitle(section)}内容顺序`"
              aria-describedby="resume-entry-reorder-help"
              aria-keyshortcuts="Alt+ArrowUp Alt+ArrowDown"
              @pointerdown.stop="handleEntryPointerDown(section.id, entry.id, $event)"
              @keydown="handleEntryKeydown(section.id, entry.id, $event)"
            >
              <span class="drag-grip" aria-hidden="true" />
            </button>
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
            </template>

            <div v-if="section.kind !== 'SKILL'" class="entry-bullets-label">
              {{ entryContentLabel(section.kind) }}
            </div>
            <template v-if="section.kind !== 'SKILL'">
              <div
                v-for="(bullet, bulletIndex) in entry.bullets"
                :key="bullet.id"
                class="bullet-block"
                :class="{
                  'is-evidence-focus': props.focusedBulletId === bullet.id,
                  'is-suggest-active': suggestActive(bullet.id),
                }"
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
                            const targetBullet = target.bullets.find(
                              (item) => item.id === bullet.id,
                            )
                            if (targetBullet) targetBullet.text = value
                          })
                      "
                    />
                  </label>
                  <div class="bullet-actions">
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
                        :disabled="suggest.busy.value || !bullet.text.trim()"
                        @click="handleSuggestButtonClick(bullet.id)"
                      >
                        {{ suggestActive(bullet.id) ? '查看 AI 建议' : 'AI 优化' }}
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
                    <button
                      type="button"
                      class="bullet-delete-action"
                      :aria-label="
                        isStructuredSection(section.kind)
                          ? bulletDeleteLabel(entry, section.kind, bulletIndex)
                          : `删除${sectionTitle(section)}中的第 ${bulletIndex + 1} 条内容`
                      "
                      @click="deleteBullet(section.id, entry.id, bullet.id)"
                    >
                      删除
                    </button>
                  </div>
                </div>
              </div>
            </template>

            <div v-if="section.kind !== 'SKILL'" class="entry-actions">
              <el-button size="small" @click="addBullet(section.id, entry.id)">
                添加{{ entryContentLabel(section.kind) }}
              </el-button>
              <button
                v-if="!isStructuredSection(section.kind)"
                type="button"
                class="entry-delete-action is-inline-delete"
                :aria-label="`删除${sectionTitle(section)}中的这段内容`"
                @click="confirmDeleteEntry(section.id, entry.id, section.kind)"
              >
                删除内容
              </button>
            </div>
          </article>

          <div class="section-footer">
            <el-button size="small" @click="addEntry(section.id)">{{
              addEntryLabel(section.kind)
            }}</el-button>
          </div>
        </div>
      </section>
    </div>
    <p id="resume-reorder-help" class="sr-only">拖动章节标题旁的排序按钮，或聚焦章节后使用 Alt + 上/下方向键调整顺序。</p>
    <p id="resume-entry-reorder-help" class="sr-only">拖动条目标题旁的排序按钮，或聚焦排序按钮后使用 Alt + 上/下方向键调整当前章节内条目顺序。</p>
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
  transition:
    border-color 140ms ease,
    background-color 140ms ease,
    transform 140ms ease;
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

.editor-field {
  display: grid;
  min-width: 0;
  gap: 6px;
  padding: 5px 7px 7px;
  border: 1px solid transparent;
  transition:
    border-color 140ms ease,
    background-color 140ms ease;
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

.section-heading {
  display: grid;
  gap: 2px;
  min-width: 0;
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
  transition:
    border-color 140ms ease,
    background-color 140ms ease;
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
  transition:
    border-color 140ms ease,
    background-color 140ms ease;
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

.bullet-suggest-button {
  color: var(--app-primary);
}

.entry-actions,
.section-footer {
  display: flex;
}

.entry-actions {
  align-items: center;
  gap: 12px;
  padding-top: 2px;
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
  font-size: 12px;
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
  font-size: 13px;
}

:deep(.el-textarea__inner) {
  min-height: 31px;
  border: 0;
  border-radius: 0;
  padding: 6px 7px;
  color: var(--app-text);
  font-size: 14px;
  line-height: 1.6;
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
  color: var(--app-ai);
}

.entry-actions,
.section-footer {
  padding-top: 3px;
}

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

.basics-details {
  position: relative;
  flex: 0 0 auto;
}

.basics-details summary {
  border: 0;
  padding: 2px 0;
  color: var(--app-text-secondary);
  font-size: 12px;
  line-height: 1.4;
  cursor: pointer;
  list-style: none;
}

.basics-details summary::-webkit-details-marker {
  display: none;
}

.basics-details summary:hover,
.basics-details summary:focus-visible,
.basics-details[open] summary {
  color: var(--app-primary-active);
  text-decoration: underline;
  text-underline-offset: 3px;
}

.basics-details-menu {
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

.basics-details-menu label {
  display: grid;
  gap: 4px;
  color: var(--app-text-secondary);
  font-size: 11px;
  font-weight: 700;
}

.basics-details-menu label :deep(.el-input__wrapper) {
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
  grid-template-columns: auto minmax(0, 1fr);
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

.editor-section.is-focused > .editor-block-header {
  padding: 4px 6px;
  background: color-mix(in srgb, var(--app-focus-soft) 24%, transparent);
  box-shadow: inset 2px 0 0 var(--app-focus);
}

.editor-section.is-focused.has-focused-bullet > .editor-block-header {
  background: color-mix(in srgb, var(--app-focus-soft) 10%, transparent);
  box-shadow: inset 2px 0 0 color-mix(in srgb, var(--app-focus) 58%, transparent);
}

.editor-section.is-focused {
  scroll-margin-top: 20px;
}

.editor-section {
  position: relative;
  cursor: grab;
  transition:
    background-color 160ms ease,
    box-shadow 160ms ease;
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
  grid-template-columns: minmax(0, 1fr) minmax(0, 38%) auto;
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
  font-size: 15px;
  font-weight: 750;
}

.entry-meta-display {
  color: var(--app-text-secondary);
  font-size: 12px;
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

.editor-entry > .skill-grid,
.editor-entry > .entry-grid,
.editor-entry > .entry-bullets-label {
  display: none;
}

.editor-entry > .entry-actions {
  height: 0;
  min-height: 0;
  overflow: hidden;
  padding: 0;
  opacity: 0;
  transition: opacity 140ms ease;
}

.editor-section > .editor-section-content > .section-footer {
  min-height: 24px;
  padding-top: 5px;
  opacity: 1;
  pointer-events: auto;
  transition: opacity 140ms ease;
}

.editor-entry.is-generic {
  padding-top: 4px;
  border-top-color: transparent;
}

.editor-entry.is-generic > .entry-actions {
  gap: 12px;
}

.editor-entry:hover > .entry-actions,
.editor-entry:focus-within > .entry-actions {
  height: auto;
  min-height: 24px;
  opacity: 1;
}

@media (hover: hover) and (pointer: fine) {
  .editor-section:not(.is-empty) > .editor-section-content > .section-footer {
    opacity: 0;
    pointer-events: none;
  }

  .editor-section:not(.is-empty):hover > .editor-section-content > .section-footer,
  .editor-section:not(.is-empty):focus-within > .editor-section-content > .section-footer {
    opacity: 1;
    pointer-events: auto;
  }
}

.bullet-block {
  gap: 3px;
}

.bullet-block.is-evidence-focus {
  margin: -4px 0 -4px -10px;
  padding: 5px 8px 5px 10px;
  border: 1px solid color-mix(in srgb, var(--app-focus) 32%, var(--app-border));
  border-radius: var(--app-radius-sm);
  background: color-mix(in srgb, var(--app-focus-soft) 48%, var(--app-surface));
  box-shadow: inset 3px 0 0 var(--app-focus);
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
  font-size: 14px;
  line-height: 1.6;
  word-break: break-word;
  resize: none;
  box-shadow: none !important;
}

:deep(.bullet-line .el-textarea__inner:hover),
:deep(.bullet-line .el-textarea__inner:focus) {
  box-shadow: none !important;
}

.bullet-actions {
  display: flex;
  min-height: 27px;
  align-items: center;
  gap: 8px;
  opacity: 0;
  transition: opacity 140ms ease;
}

.bullet-block:hover .bullet-actions,
.bullet-block:focus-within .bullet-actions,
.bullet-block.is-evidence-focus .bullet-actions,
.bullet-block.is-suggest-active .bullet-actions,
.bullet-actions:focus-within {
  opacity: 1;
}

.bullet-line:hover .bullet-suggest-button,
.bullet-suggest-button:focus-visible {
  border-color: var(--app-ai) !important;
  color: var(--app-ai) !important;
  background: var(--app-ai-soft) !important;
}

.bullet-delete-action,
.entry-delete-action {
  border: 0;
  padding: 0;
  color: var(--app-danger);
  font: inherit;
  font-size: 12px;
  line-height: 1.4;
  background: transparent;
  cursor: pointer;
}

.bullet-delete-action {
  color: color-mix(in srgb, var(--app-danger) 78%, var(--app-text-secondary));
}

.bullet-delete-action:hover,
.bullet-delete-action:focus-visible,
.entry-delete-action:hover,
.entry-delete-action:focus-visible {
  color: var(--app-danger);
  text-decoration: underline;
  text-underline-offset: 3px;
}

.entry-delete-action {
  display: inline-flex;
  min-height: 24px;
  align-items: center;
  align-self: center;
  line-height: 1.4;
  white-space: nowrap;
}

.entry-delete-action.is-heading-delete {
  grid-column: 3;
  grid-row: 1 / span 2;
  align-self: start;
  justify-self: end;
  opacity: 0;
  pointer-events: none;
  transition: opacity 140ms ease;
}

.editor-entry:hover .entry-delete-action.is-heading-delete,
.editor-entry:focus-within .entry-delete-action.is-heading-delete {
  opacity: 1;
  pointer-events: auto;
}

.entry-actions :deep(.el-button),
.section-footer :deep(.el-button) {
  min-height: 24px;
  border: 0;
  padding: 0;
  color: var(--app-text-muted);
  font-size: 12px;
  background: transparent;
}

.section-footer :deep(.el-button)::before {
  margin-right: 5px;
  content: '+';
  font-size: 14px;
}

.entry-actions :deep(.el-button:hover),
.section-footer :deep(.el-button:hover) {
  color: var(--app-primary-active);
}

@keyframes resume-focus-pulse {
  0% {
    box-shadow: 0 0 0 4px color-mix(in srgb, var(--app-focus) 18%, transparent);
  }
  100% {
    box-shadow: 0 0 0 0 transparent;
  }
}

@media (prefers-reduced-motion: reduce) {
  .bullet-block.is-evidence-focus {
    animation: none;
  }
}

@media (hover: none), (pointer: coarse) {
  .bullet-actions {
    opacity: 1;
  }

  .entry-delete-action.is-heading-delete {
    opacity: 0.72;
    pointer-events: auto;
  }

  .editor-entry > .entry-actions {
    height: auto;
    min-height: 24px;
    opacity: 0.55;
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
    grid-template-columns: 22px minmax(0, 1fr) auto;
    gap: 3px 7px;
  }

  .entry-title-display,
  .entry-meta-display {
    grid-column: 2;
  }

  .entry-delete-action.is-heading-delete {
    grid-column: 3;
    grid-row: 1 / span 2;
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

  .entry-delete-action.is-heading-delete {
    opacity: 0.72;
    pointer-events: auto;
  }

  .editor-entry > .entry-actions {
    height: auto;
    min-height: 24px;
    opacity: 0.55;
  }
}

/* Reordering is explicit: only the small contextual grip owns pointer dragging. */
.editor-section {
  cursor: default;
}

.editor-section.is-reorder-source,
.editor-section.is-reorder-source * {
  cursor: grabbing !important;
}

.section-drag-handle,
.entry-drag-handle {
  display: inline-grid;
  flex: 0 0 auto;
  place-items: center;
  width: 24px;
  height: 24px;
  border: 0;
  border-radius: 4px;
  padding: 0;
  color: var(--app-text-muted);
  background: transparent;
  cursor: grab;
  touch-action: none;
  transition:
    color 140ms ease,
    background-color 140ms ease,
    opacity 140ms ease;
}

.section-drag-handle {
  opacity: 0.5;
}

.editor-section:hover .section-drag-handle,
.editor-section:focus-within .section-drag-handle,
.section-drag-handle:focus-visible,
.entry-drag-handle:focus-visible,
.editor-entry:hover .entry-drag-handle,
.editor-entry:focus-within .entry-drag-handle {
  color: var(--app-primary-active);
  background: var(--app-primary-soft);
  opacity: 1;
}

.section-drag-handle:focus-visible,
.entry-drag-handle:focus-visible {
  outline: 2px solid var(--app-primary);
  outline-offset: 2px;
}

.drag-grip {
  width: 12px;
  height: 14px;
  background: radial-gradient(circle, currentColor 1.35px, transparent 1.6px) 0 0 / 6px 6px;
}

.editor-section .editor-block-header {
  grid-template-columns: 24px 20px minmax(0, 1fr);
  justify-content: initial;
  gap: 7px;
}

.editor-section.is-reorder-source .section-drag-handle,
.editor-entry.is-entry-reorder-source .entry-drag-handle {
  color: var(--app-primary-active);
  background: var(--app-primary-soft);
  cursor: grabbing;
  opacity: 1;
}

.entry-document-heading {
  grid-template-columns: 24px minmax(0, 1fr) minmax(0, 38%) auto;
}

.entry-delete-action.is-heading-delete {
  grid-column: 4;
}

.editor-entry {
  position: relative;
  cursor: default;
}

.entry-drag-handle-generic {
  position: absolute;
  top: 3px;
  left: -2px;
  opacity: 0.35;
}

.editor-entry.is-generic {
  padding-left: 28px;
}

.editor-entry.is-entry-reorder-source {
  user-select: none;
  background: color-mix(in srgb, var(--app-primary-soft) 44%, transparent);
  box-shadow: 0 5px 14px color-mix(in srgb, var(--app-text) 10%, transparent);
}

.editor-entry.is-entry-reorder-source,
.editor-entry.is-entry-reorder-source * {
  cursor: grabbing !important;
}

.editor-entry.is-entry-drop-before::before,
.editor-entry.is-entry-drop-after::after {
  position: absolute;
  z-index: 2;
  right: 0;
  left: 22px;
  height: 2px;
  border-radius: 2px;
  background: var(--app-primary);
  box-shadow: 0 0 0 2px color-mix(in srgb, var(--app-primary) 12%, transparent);
  content: '';
  pointer-events: none;
}

.editor-entry.is-entry-drop-before::before {
  top: -1px;
}

.editor-entry.is-entry-drop-after::after {
  bottom: -1px;
}

.editor-section.is-entry-drop-end::after {
  position: absolute;
  right: 0;
  bottom: -1px;
  left: 22px;
  height: 2px;
  border-radius: 2px;
  background: var(--app-primary);
  box-shadow: 0 0 0 2px color-mix(in srgb, var(--app-primary) 12%, transparent);
  content: '';
  pointer-events: none;
}

.resume-editor.is-entry-drop-unavailable .editor-entry:not(.is-entry-reorder-source) .entry-drag-handle {
  color: var(--app-text-muted);
  cursor: not-allowed;
}

@media (max-width: 760px) {
  .editor-section .editor-block-header {
    grid-template-columns: 24px 20px minmax(0, 1fr);
    gap: 5px;
  }

  .entry-document-heading {
    grid-template-columns: 24px minmax(0, 1fr) auto;
  }

  .entry-title-display,
  .entry-meta-display {
    grid-column: 2;
  }

  .entry-delete-action.is-heading-delete {
    grid-column: 3;
  }

  .editor-entry.is-generic {
    padding-left: 28px;
  }
}
</style>
