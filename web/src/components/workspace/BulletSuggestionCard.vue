<script setup lang="ts">
import { computed, ref } from 'vue'
import { diffText } from '@/utils/diffText'

const props = defineProps<{
  mode: 'composing' | 'requesting' | 'ready' | 'stale' | 'rejected' | 'error'
  originalText: string
  suggestedText?: string | null
  reason?: string | null
  rejectCode?: string | null
  rejectMessage?: string | null
  errorMessage?: string | null
}>()

const emit = defineEmits<{
  apply: []
  reject: []
  regenerate: []
  cancel: []
  submitCustom: [instruction: string]
}>()

const customInstruction = ref('')
const diffSegments = computed(() =>
  props.suggestedText ? diffText(props.originalText, props.suggestedText) : [],
)

const rejectReasonLabel = computed(() => {
  switch (props.rejectCode) {
    case 'NEW_TECHNOLOGY':
    case 'NEW_ENTITY':
      return '引入原文没有的信息'
    case 'NEW_QUANTITATIVE_CLAIM':
      return '改变事实语义'
    case 'NEW_SCOPE_OR_TIME':
      return '删除或改变了关键限定条件'
    case 'AI_REFUSED':
      return '无法在原有事实范围内改写'
    default:
      return '与原始材料不一致'
  }
})

const submitCustom = () => {
  const instruction = customInstruction.value.trim()
  if (!instruction) return
  customInstruction.value = ''
  emit('submitCustom', instruction)
}
</script>

<template>
  <div :class="['bullet-suggestion', `is-${props.mode}`]">
    <template v-if="props.mode === 'composing'">
      <p class="suggestion-title">告诉 AI 这次想怎么改</p>
      <el-input
        v-model="customInstruction"
        type="textarea"
        :autosize="{ minRows: 2, maxRows: 4 }"
        :maxlength="500"
        placeholder="例如：更突出后端职责。AI 不能新增事实。"
        aria-label="自定义改写要求"
      />
      <div class="suggestion-actions">
        <el-button
          size="small"
          type="primary"
          :disabled="!customInstruction.trim()"
          @click="submitCustom"
        >
          生成建议
        </el-button>
        <el-button size="small" @click="emit('cancel')">取消</el-button>
      </div>
    </template>

    <template v-else-if="props.mode === 'requesting'">
      <p class="suggestion-title">正在生成修改建议…</p>
      <p class="suggestion-note">生成期间继续编辑这条内容会使建议失效。</p>
    </template>

    <template v-else-if="props.mode === 'ready' || props.mode === 'stale'">
      <div class="suggestion-copy-block">
        <span class="suggestion-label">原文</span>
        <p>{{ props.originalText }}</p>
      </div>
      <div class="suggestion-copy-block is-proposed">
        <span class="suggestion-label">建议版本</span>
        <p>{{ props.suggestedText }}</p>
      </div>
      <div class="suggestion-copy-block is-diff">
        <span class="suggestion-label">差异</span>
        <p aria-label="原文与建议表达的差异">
          <span
            v-for="(segment, index) in diffSegments"
            :key="`${segment.type}-${index}`"
            :class="`diff-${segment.type}`"
          >{{ segment.text }}</span>
        </p>
      </div>
      <div class="suggestion-copy-block is-reason">
        <span class="suggestion-label">为什么这样改</span>
        <p>{{ props.reason || '保留原有事实，只调整表达。' }}</p>
      </div>
      <p v-if="props.mode === 'stale'" class="suggestion-stale">
        内容或版本已变化，这条建议已失效，不能采纳。可以重新生成或关闭。
      </p>
      <div class="suggestion-actions">
        <el-button
          v-if="props.mode === 'ready'"
          size="small"
          type="primary"
          :disabled="!props.suggestedText"
          @click="emit('apply')"
        >
          采纳
        </el-button>
        <el-button size="small" @click="emit('regenerate')">重新生成</el-button>
        <el-button size="small" text @click="emit('reject')">{{
          props.mode === 'stale' ? '关闭' : '拒绝'
        }}</el-button>
      </div>
    </template>

    <template v-else-if="props.mode === 'rejected'">
      <p class="suggestion-title">这条建议没有通过事实校验</p>
      <p class="suggestion-note">
        AI 改写改变了原始材料的语义，例如把未确认的信息写成了确定事实，因此这条建议没有被采纳。
      </p>
      <span class="suggestion-reason-label">原因：{{ rejectReasonLabel }}</span>
      <p v-if="props.rejectMessage" class="suggestion-detail">校验说明：{{ props.rejectMessage }}</p>
      <div class="suggestion-actions">
        <el-button size="small" type="primary" @click="emit('regenerate')">重新生成建议</el-button>
        <el-button size="small" text @click="emit('reject')">继续手工编辑</el-button>
      </div>
    </template>

    <template v-else>
      <p class="suggestion-title">暂时没有生成建议</p>
      <p class="suggestion-note">{{ props.errorMessage ?? 'AI 服务暂时不可用。你可以稍后再试，也可以继续手工编辑。' }}</p>
      <div class="suggestion-actions">
        <el-button size="small" type="primary" @click="emit('regenerate')">重新生成建议</el-button>
        <el-button size="small" text @click="emit('reject')">继续手工编辑</el-button>
      </div>
    </template>
  </div>
</template>

<style scoped>
.bullet-suggestion {
  display: grid;
  gap: 10px;
  margin-top: 2px;
  border: 1px solid var(--app-primary-subtle);
  border-radius: var(--app-radius-md);
  padding: 13px;
  background: var(--app-primary-soft);
}

.bullet-suggestion.is-rejected,
.bullet-suggestion.is-error {
  border-color: var(--el-color-warning-light-7);
  background: var(--app-warning-soft);
}

.suggestion-title,
.suggestion-note,
.suggestion-stale,
.suggestion-copy-block p {
  margin: 0;
  line-height: 1.65;
}

.suggestion-title {
  color: var(--app-text);
  font-size: 13px;
  font-weight: 700;
}

.suggestion-note,
.suggestion-stale,
.suggestion-detail {
  color: var(--app-text-secondary);
  font-size: 12px;
}

.suggestion-reason-label {
  color: var(--app-warning);
  font-size: 11px;
  font-weight: 700;
}

.suggestion-detail {
  margin: -3px 0 0;
  color: var(--app-text-muted);
  line-height: 1.55;
}

.suggestion-stale {
  color: var(--el-color-warning-dark-2);
  font-weight: 600;
}

.suggestion-copy-block {
  display: grid;
  gap: 5px;
  padding: 9px 10px;
  border: 1px solid var(--app-border-soft);
  border-radius: var(--app-radius-sm);
  background: var(--app-surface);
}

.suggestion-copy-block.is-proposed {
  border-color: var(--el-color-success-light-7);
  background: var(--app-success-soft);
}

.suggestion-copy-block.is-diff {
  border-color: var(--app-border);
  background: var(--app-document);
}

.suggestion-copy-block.is-reason {
  background: transparent;
}

.diff-added {
  color: var(--app-success);
  background: var(--app-success-soft);
  text-decoration: underline;
  text-decoration-thickness: 1px;
  text-underline-offset: 3px;
}

.diff-removed {
  color: var(--app-primary-active);
  background: var(--app-primary-soft);
  text-decoration: line-through;
}

.diff-equal {
  color: var(--app-text-secondary);
}

.suggestion-label {
  color: var(--app-text-muted);
  font-size: 11px;
  font-weight: 700;
}

.suggestion-copy-block p {
  color: var(--app-text);
  font-size: 13px;
  white-space: pre-wrap;
  word-break: break-word;
}

.suggestion-actions {
  display: flex;
  flex-wrap: wrap;
  gap: 8px;
  align-items: center;
}
</style>
