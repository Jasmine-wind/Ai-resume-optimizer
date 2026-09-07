<script setup lang="ts">
import { computed, onMounted, ref } from 'vue'
import { ElMessage } from 'element-plus'
import SettingsLayout from '@/components/settings/SettingsLayout.vue'
import SkeletonBlock from '@/components/common/SkeletonBlock.vue'
import { updateCurrentUserProfile } from '@/api/user'
import { useAuthStore } from '@/stores/auth'

const authStore = useAuthStore()
const loading = ref(!authStore.currentUser)
const saving = ref(false)
const error = ref<string | null>(null)
const saved = ref(false)
const nickname = ref('')
const initialNickname = ref('')

const displayName = computed(() => nickname.value.trim() || authStore.currentUser?.username || '')
const initials = computed(() => {
  const name = displayName.value.trim()
  if (!name) return 'CV'
  const glyphs = Array.from(name.replace(/\s+/gu, ''))
  const first = glyphs[0]
  if (first && (first.codePointAt(0) ?? 0) > 0x7f) return first
  const words = name.split(/[\s._-]+/u).filter(Boolean)
  if (words.length > 1) return words.slice(0, 2).map((word) => Array.from(word)[0] ?? '').join('').toUpperCase()
  return glyphs.slice(0, 2).join('').toUpperCase() || 'CV'
})
const dirty = computed(() => nickname.value.trim() !== initialNickname.value)

const hydrate = async () => {
  if (!authStore.currentUser) {
    loading.value = true
    try {
      await authStore.fetchMe()
    } catch (cause) {
      error.value = cause instanceof Error ? cause.message : '个人资料加载失败'
    } finally {
      loading.value = false
    }
  }
  const value = authStore.currentUser?.nickname?.trim() ?? ''
  nickname.value = value
  initialNickname.value = value
}

const submit = async () => {
  if (!dirty.value || saving.value || !authStore.currentUser) return
  saving.value = true
  error.value = null
  saved.value = false
  try {
    const updated = await updateCurrentUserProfile({ nickname: nickname.value })
    authStore.updateCurrentUser(updated)
    nickname.value = updated.nickname?.trim() ?? ''
    initialNickname.value = nickname.value
    saved.value = true
    ElMessage.success('个人资料已保存')
  } catch (cause) {
    error.value = cause instanceof Error ? cause.message : '个人资料保存失败，请稍后重试'
  } finally {
    saving.value = false
  }
}

onMounted(hydrate)
</script>

<template>
  <SettingsLayout current="profile">
    <section class="settings-page profile-settings">
      <header class="settings-content-heading">
        <p class="settings-section-label">账户信息</p>
        <h2>个人资料</h2>
        <p>显示名称会出现在账号菜单中。头像仅使用 initials，不上传头像文件。</p>
      </header>

      <SkeletonBlock v-if="loading" title :rows="5" />
      <form v-else class="profile-form" @submit.prevent="submit">
        <div class="profile-identity">
          <div class="profile-avatar" aria-hidden="true">{{ initials }}</div>
          <div>
            <strong>{{ displayName || '当前账号' }}</strong>
            <p>当前显示名称</p>
          </div>
        </div>

        <fieldset>
          <legend>显示名称</legend>
          <label for="profile-nickname">显示名称</label>
          <input
            id="profile-nickname"
            v-model="nickname"
            name="nickname"
            type="text"
            maxlength="50"
            autocomplete="nickname"
            aria-describedby="profile-nickname-help"
          />
          <small id="profile-nickname-help">最多 50 个字符；清空后会使用用户名作为显示名称。</small>
        </fieldset>

        <fieldset class="profile-readonly">
          <legend>账户标识</legend>
          <dl>
            <div><dt>用户名</dt><dd>{{ authStore.currentUser?.username }}</dd></div>
            <div><dt>邮箱</dt><dd>{{ authStore.currentUser?.email }}</dd></div>
          </dl>
          <p>用户名和邮箱当前作为账户标识，不在这里修改。</p>
        </fieldset>

        <p v-if="error" class="profile-feedback is-error" role="alert">{{ error }}</p>
        <p v-if="saved" class="profile-feedback is-success" role="status" aria-live="polite">个人资料已保存。</p>
        <button type="submit" class="profile-submit" :disabled="!dirty || saving">
          {{ saving ? '正在保存…' : '保存个人资料' }}
        </button>
      </form>
    </section>
  </SettingsLayout>
</template>

<style scoped>
.profile-settings { gap: var(--app-space-6); }
.settings-content-heading { display: grid; gap: var(--app-space-2); padding-bottom: var(--app-space-5); border-bottom: 1px solid var(--app-border-strong); }
.settings-content-heading h2 { margin: 0; color: var(--app-text); font-size: 22px; }
.settings-content-heading > p:last-child { margin: 0; color: var(--app-text-secondary); font-size: var(--app-font-size-sm); line-height: 1.6; }
.settings-section-label { margin: 0; color: var(--app-text-muted); font-family: var(--app-font-mono); font-size: 10px; font-weight: 700; letter-spacing: .06em; }
.profile-form { display: grid; gap: var(--app-space-6); }
.profile-identity { display: flex; align-items: center; gap: var(--app-space-4); }
.profile-avatar { display: grid; width: 64px; height: 64px; place-items: center; border: 1px solid var(--app-primary-subtle); border-radius: 50%; color: var(--app-primary-active); font-weight: 750; background: var(--app-primary-soft); }
.profile-identity strong { color: var(--app-text); font-size: 18px; }
.profile-identity p, fieldset small, .profile-readonly p { margin: var(--app-space-1) 0 0; color: var(--app-text-secondary); font-size: var(--app-font-size-sm); line-height: 1.55; }
fieldset { display: grid; gap: var(--app-space-2); border: 0; border-top: 1px solid var(--app-border-strong); padding: var(--app-space-5) 0 0; }
legend { padding: 0; color: var(--app-text); font-size: 16px; font-weight: 700; }
label { color: var(--app-text-secondary); font-size: var(--app-font-size-sm); font-weight: 650; }
input { width: 100%; max-width: 520px; box-sizing: border-box; min-height: 40px; border: 1px solid var(--app-border-strong); border-radius: var(--app-radius-sm); padding: 0 var(--app-space-3); color: var(--app-text); font: inherit; background: var(--app-surface); }
input:focus-visible { outline: 2px solid var(--app-primary); outline-offset: 2px; }
.profile-readonly dl { display: grid; gap: var(--app-space-3); margin: 0; }
.profile-readonly dl > div { display: grid; grid-template-columns: 90px minmax(0, 1fr); gap: var(--app-space-4); }
.profile-readonly dt { color: var(--app-text-muted); font-size: var(--app-font-size-sm); }
.profile-readonly dd { margin: 0; color: var(--app-text); font-size: var(--app-font-size-sm); overflow-wrap: anywhere; }
.profile-submit { justify-self: start; min-height: 40px; border: 1px solid var(--app-primary); border-radius: var(--app-radius-sm); padding: 0 var(--app-space-5); color: var(--app-surface); font: inherit; font-weight: 700; background: var(--app-primary); cursor: pointer; }
.profile-submit:hover:not(:disabled), .profile-submit:focus-visible:not(:disabled) { background: var(--app-primary-active); }
.profile-submit:focus-visible { outline: 2px solid var(--app-primary); outline-offset: 3px; }
.profile-submit:disabled { cursor: not-allowed; opacity: .45; }
.profile-feedback { margin: 0; font-size: var(--app-font-size-sm); line-height: 1.5; }
.profile-feedback.is-error { color: var(--app-danger); }
.profile-feedback.is-success { color: var(--app-success); }
@media (max-width: 520px) { .profile-submit { width: 100%; } }
</style>
