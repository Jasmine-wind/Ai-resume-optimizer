<script setup lang="ts">
import { computed, nextTick, onBeforeUnmount, onMounted, ref } from 'vue'
import { useRouter } from 'vue-router'
import ProductFlowDemo from '@/components/brand/ProductFlowDemo.vue'
import { createPresentationGestureController } from '@/utils/useSlideGestureController'
import FinalResumeArtifact from './FinalResumeArtifact.vue'
import LandingResumePaper from './LandingResumePaper.vue'
import ProductMark from '@/components/common/ProductMark.vue'
import { LANDING_DEMO_STAGES, type LandingDemoStageId } from './landingDemoStages'

const router = useRouter()
const sceneViewport = ref<HTMLElement | null>(null)
const activeSceneIndex = ref(0)
const sceneDirection = ref<1 | -1>(1)
const hasInteracted = ref(false)
const liveMessage = ref('第 1 页，共 8 页，Hero')
const controller = createPresentationGestureController({ threshold: 78, quietPeriod: 220 })

const sceneLabels = ['Hero', ...LANDING_DEMO_STAGES.map((stage) => stage.label), '产品原则', '最终 CTA'] as const
const activeDemoIndex = computed(() => Math.min(Math.max(activeSceneIndex.value - 1, 0), LANDING_DEMO_STAGES.length - 1))
const activeStage = computed(() => LANDING_DEMO_STAGES[activeDemoIndex.value]!)
const activeDemoStageIndex = computed(() => activeStage.value.index)
const activeDemoStage = computed<LandingDemoStageId>(() => activeStage.value.id)
const isProductSceneActive = computed(() => activeSceneIndex.value >= 1 && activeSceneIndex.value <= 5)
const productSceneClass = computed(() => ({
  'is-active': isProductSceneActive.value,
  'is-before': activeSceneIndex.value > 5,
  'is-after': activeSceneIndex.value === 0,
  'is-forward': sceneDirection.value === 1,
  'is-backward': sceneDirection.value === -1,
}))

let transitionTimer: ReturnType<typeof setTimeout> | null = null
let originalBodyOverflow = ''
let originalHtmlOverflow = ''
let touchStartX = 0
let touchStartY = 0
let touchDeltaX = 0
let touchDeltaY = 0
let touchCaptured = false
let touchStartedOnInteractive = false

const isReducedMotion = () => window.matchMedia('(prefers-reduced-motion: reduce)').matches

const sceneClass = (index: number) => ({
  'is-active': activeSceneIndex.value === index,
  'is-before': index < activeSceneIndex.value,
  'is-after': index > activeSceneIndex.value,
  'is-forward': sceneDirection.value === 1,
  'is-backward': sceneDirection.value === -1,
})

const finishTransition = () => {
  transitionTimer = null
  controller.finishTransition()
}

const scheduleTransitionFinish = () => {
  if (transitionTimer) clearTimeout(transitionTimer)
  transitionTimer = setTimeout(finishTransition, isReducedMotion() ? 80 : 660)
}

const setActiveScene = (nextIndex: number, fromGesture = false) => {
  const clampedIndex = Math.min(Math.max(nextIndex, 0), sceneLabels.length - 1)
  if (clampedIndex === activeSceneIndex.value) return false
  if (!fromGesture && !controller.beginTransition()) return false

  sceneDirection.value = clampedIndex > activeSceneIndex.value ? 1 : -1
  activeSceneIndex.value = clampedIndex
  hasInteracted.value = true
  liveMessage.value = `第 ${clampedIndex + 1} 页，共 ${sceneLabels.length} 页，${sceneLabels[clampedIndex]}`
  scheduleTransitionFinish()
  return true
}

const moveScene = (direction: 1 | -1) => {
  setActiveScene(activeSceneIndex.value + direction)
}

const isInteractiveTarget = (target: EventTarget | null) => {
  if (!(target instanceof HTMLElement)) return false
  return Boolean(
    target.closest('button, a, input, textarea, select, [contenteditable="true"], [role="textbox"]'),
  )
}

const handleWheel = (event: WheelEvent) => {
  if (isInteractiveTarget(event.target)) return

  const decision = controller.handle({
    deltaY: event.deltaY,
    deltaX: event.deltaX,
    deltaMode: event.deltaMode,
    currentIndex: activeSceneIndex.value,
    maxIndex: sceneLabels.length - 1,
    viewportHeight: window.innerHeight,
    ctrlKey: event.ctrlKey,
  })

  if (decision.preventDefault) event.preventDefault()
  if (decision.action === 'next') setActiveScene(activeSceneIndex.value + 1, true)
  if (decision.action === 'previous') setActiveScene(activeSceneIndex.value - 1, true)
}

const handleKeydown = (event: KeyboardEvent) => {
  if (isInteractiveTarget(event.target) || controller.isTransitioning()) return

  let nextIndex: number | null = null
  if (event.key === 'ArrowDown' || event.key === 'PageDown' || (event.key === ' ' && !event.shiftKey)) {
    nextIndex = activeSceneIndex.value + 1
  } else if (event.key === 'ArrowUp' || event.key === 'PageUp' || (event.key === ' ' && event.shiftKey)) {
    nextIndex = activeSceneIndex.value - 1
  } else if (event.key === 'Home') {
    nextIndex = 0
  } else if (event.key === 'End') {
    nextIndex = sceneLabels.length - 1
  }

  if (nextIndex === null || !setActiveScene(nextIndex)) return
  event.preventDefault()
}

const handleTouchStart = (event: TouchEvent) => {
  touchStartedOnInteractive = isInteractiveTarget(event.target)
  if (touchStartedOnInteractive || event.touches.length !== 1 || controller.isTransitioning()) return
  const touch = event.touches[0]!
  touchStartX = touch.clientX
  touchStartY = touch.clientY
  touchDeltaX = 0
  touchDeltaY = 0
  touchCaptured = false
}

const handleTouchMove = (event: TouchEvent) => {
  if (touchStartedOnInteractive || event.touches.length !== 1) return
  const touch = event.touches[0]!
  touchDeltaX = touch.clientX - touchStartX
  touchDeltaY = touch.clientY - touchStartY

  if (Math.abs(touchDeltaY) < 10 || Math.abs(touchDeltaX) >= Math.abs(touchDeltaY) * 1.15) return
  const direction = touchDeltaY < 0 ? 1 : -1
  const atBoundary =
    (direction < 0 && activeSceneIndex.value === 0) ||
    (direction > 0 && activeSceneIndex.value === sceneLabels.length - 1)

  if (!atBoundary) {
    touchCaptured = true
    event.preventDefault()
  }
}

const handleTouchEnd = () => {
  if (
    !touchStartedOnInteractive &&
    touchCaptured &&
    Math.abs(touchDeltaY) >= 56 &&
    Math.abs(touchDeltaY) > Math.abs(touchDeltaX) * 1.15
  ) {
    moveScene(touchDeltaY < 0 ? 1 : -1)
  }
  touchStartedOnInteractive = false
  touchCaptured = false
  touchDeltaX = 0
  touchDeltaY = 0
}

const goToScene = (index: number) => {
  setActiveScene(index)
}

const goToDemoStage = (index: number) => {
  setActiveScene(index + 1)
}

onMounted(async () => {
  await nextTick()
  const viewport = sceneViewport.value
  if (!viewport) return

  originalBodyOverflow = document.body.style.overflow
  originalHtmlOverflow = document.documentElement.style.overflow
  document.body.style.overflow = 'hidden'
  document.documentElement.style.overflow = 'hidden'

  viewport.addEventListener('wheel', handleWheel, { passive: false })
  viewport.addEventListener('touchstart', handleTouchStart, { passive: true })
  viewport.addEventListener('touchmove', handleTouchMove, { passive: false })
  viewport.addEventListener('touchend', handleTouchEnd, { passive: true })
  viewport.addEventListener('touchcancel', handleTouchEnd, { passive: true })
  window.addEventListener('keydown', handleKeydown)
})

onBeforeUnmount(() => {
  if (transitionTimer) clearTimeout(transitionTimer)
  const viewport = sceneViewport.value
  viewport?.removeEventListener('wheel', handleWheel)
  viewport?.removeEventListener('touchstart', handleTouchStart)
  viewport?.removeEventListener('touchmove', handleTouchMove)
  viewport?.removeEventListener('touchend', handleTouchEnd)
  viewport?.removeEventListener('touchcancel', handleTouchEnd)
  window.removeEventListener('keydown', handleKeydown)
  document.body.style.overflow = originalBodyOverflow
  document.documentElement.style.overflow = originalHtmlOverflow
  controller.reset()
})
</script>

<template>
  <main class="landing-page">
    <!--
      THESIS: 同一份真实简历在固定舞台里被逐步核对，拒绝长滚动和功能陈列。
      OWN-WORLD: 暖灰工作台、暖白纸张、细边界、深色正文与单一砖红动作色，像一份编辑稿。
      STORY: 访客从 Hero 进入要求、证据、建议、确认，再看到原则与最终岗位版本。
      FIRST VIEWPORT: 固定导航下，Hero 用左侧可信承诺与主动作对齐右侧可读简历；场景切换保持稳定构图。
      FORM: 8 个全屏 Presentation 场景由一个状态机驱动；滚轮、触控、键盘和指示器共享同一索引。
      FINISH: unreviewed and undocumented is unfinished; this build ends with the finish review, the verdict, DESIGN.md, and every shipping raster carrying its provenance
    -->
    <header class="landing-nav">
      <RouterLink to="/" class="landing-brand" aria-label="CV Role 首页">
        <ProductMark size="md" />
        <strong>简历优化</strong>
      </RouterLink>
      <nav class="landing-nav-actions" aria-label="账号操作">
        <el-button text @click="router.push('/login')">登录</el-button>
        <el-button type="primary" @click="router.push('/register')">开始岗位定向</el-button>
      </nav>
    </header>

    <div ref="sceneViewport" class="scene-viewport" tabindex="0" aria-label="Landing 页面场景">
      <section
        class="scene-panel hero-scene"
        :class="sceneClass(0)"
        :aria-hidden="activeSceneIndex === 0 ? 'false' : 'true'"
        :inert="activeSceneIndex !== 0"
        aria-labelledby="landing-title"
      >
        <div class="hero-scene-inner">
          <div class="landing-hero-copy">
            <h1 id="landing-title"><span>为每一个岗位，</span><span>认真调整一次简历。</span></h1>
            <p>
              上传简历并粘贴岗位要求。CV Role 会拆解岗位、核对经历证据，只提出有依据的修改；你确认后，再生成这次投递的岗位版本。
            </p>
            <div class="landing-hero-actions">
              <el-button type="primary" size="large" @click="router.push('/register')">
                开始岗位定向
              </el-button>
              <button type="button" class="landing-text-action" @click="goToScene(1)">
                查看示例 <span aria-hidden="true">↓</span>
              </button>
            </div>
            <p class="landing-trust-line">不编经历 · 每条建议有出处 · 确认后才写回</p>
          </div>
          <div class="landing-hero-document" aria-label="原始简历示例">
            <LandingResumePaper stage="initial" />
            <span class="landing-initial-caption">演示材料 · 原始状态</span>
          </div>
        </div>
      </section>

      <section
        class="scene-panel product-scene"
        :class="productSceneClass"
        :aria-hidden="isProductSceneActive ? 'false' : 'true'"
        :inert="!isProductSceneActive"
        aria-labelledby="landing-demo-title"
      >
        <div class="product-scene-inner">
          <div class="product-scene-visual" aria-label="岗位定向简历审阅工作区">
            <ProductFlowDemo landing :landing-stage="activeDemoStage" />
          </div>
          <div class="product-scene-copy">
            <div class="landing-demo-count" aria-hidden="true">
              <strong>{{ activeDemoStageIndex }}</strong>
              <span>/ 05</span>
              <span>{{ activeStage.label }}</span>
            </div>
            <Transition name="landing-copy" mode="out-in">
              <div :key="activeStage.id" class="landing-demo-copy">
                <h2 id="landing-demo-title">{{ activeStage.title }}</h2>
                <p>{{ activeStage.description }}</p>
                <span>{{ activeStage.note }}</span>
              </div>
            </Transition>
            <ol class="landing-progress" aria-label="五步岗位定向演示">
              <li v-for="(stage, index) in LANDING_DEMO_STAGES" :key="stage.id">
                <button
                  type="button"
                  :class="{ 'is-current': index === activeDemoIndex, 'is-complete': index < activeDemoIndex }"
                  :aria-current="index === activeDemoIndex ? 'step' : undefined"
                  :aria-label="`第 ${index + 1} 步：${stage.label}`"
                  @click="goToDemoStage(index)"
                >
                  <span class="landing-progress-node" aria-hidden="true" />
                  <span class="landing-progress-label">{{ stage.label }}</span>
                </button>
              </li>
            </ol>
            <div class="landing-demo-utilities">
              <span class="landing-gesture-hint" :class="{ 'is-hidden': hasInteracted }">滑动切换</span>
              <button type="button" class="landing-skip-button" @click="goToScene(6)">跳过演示</button>
            </div>
          </div>
        </div>
      </section>

      <section
        class="scene-panel principles-scene"
        :class="sceneClass(6)"
        :aria-hidden="activeSceneIndex === 6 ? 'false' : 'true'"
        :inert="activeSceneIndex !== 6"
        aria-labelledby="principles-title"
      >
        <div class="principles-scene-inner">
          <h2 id="principles-title">把真实性，放在每一次修改之前。</h2>
          <div class="principle-list">
            <article>
              <span aria-hidden="true">01</span>
              <h3>不编造经历</h3>
              <p>找不到原文证据的能力，不会自动写进简历。</p>
            </article>
            <article>
              <span aria-hidden="true">02</span>
              <h3>建议有出处</h3>
              <p>每一条调整都能回到岗位要求和简历原文。</p>
            </article>
            <article>
              <span aria-hidden="true">03</span>
              <h3>修改由你确认</h3>
              <p>接受、保留或撤回，再生成这次投递的岗位版本。</p>
            </article>
          </div>
        </div>
      </section>

      <section
        class="scene-panel final-scene"
        :class="sceneClass(7)"
        :aria-hidden="activeSceneIndex === 7 ? 'false' : 'true'"
        :inert="activeSceneIndex !== 7"
        aria-labelledby="landing-final-title"
      >
        <div class="final-scene-inner">
          <div class="landing-final-copy">
            <h2 id="landing-final-title">你的经历没有改变。</h2>
            <p class="landing-final-lead">只是让最值得被看见的部分，更适合这个岗位。</p>
            <el-button type="primary" size="large" @click="router.push('/register')">
              开始岗位定向
            </el-button>
          </div>
          <FinalResumeArtifact />
        </div>
        <footer>CV Role · 从真实材料出发，完成一次岗位定向</footer>
      </section>
    </div>

    <p class="sr-only" aria-live="polite">{{ liveMessage }}</p>
  </main>
</template>

<style scoped>
.landing-page {
  position: relative;
  width: 100%;
  height: 100dvh;
  overflow: hidden;
  color: var(--app-text);
  background: var(--app-bg);
}

.landing-nav {
  position: fixed;
  z-index: 30;
  top: 0;
  right: 0;
  left: 0;
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: var(--app-space-6);
  min-height: var(--app-shell-header-height);
  padding: var(--app-space-2) max(var(--app-content-gutter), calc((100% - 1280px) / 2));
  border-bottom: 1px solid color-mix(in srgb, var(--app-border) 76%, transparent);
  background: var(--app-bg);
}

.landing-brand {
  display: inline-flex;
  align-items: center;
  gap: var(--app-space-2);
  color: var(--app-text);
  font-size: 15px;
  font-weight: 750;
}

.landing-nav-actions {
  display: flex;
  align-items: center;
  gap: var(--app-space-2);
}

.scene-viewport {
  position: relative;
  width: 100%;
  height: 100dvh;
  overflow: hidden;
  outline: none;
  touch-action: pan-y;
}

.scene-viewport:focus-visible {
  outline: 2px solid var(--app-primary);
  outline-offset: -3px;
}

.scene-panel {
  position: absolute;
  z-index: 1;
  inset: 0;
  display: grid;
  box-sizing: border-box;
  padding: calc(var(--app-shell-header-height) + 18px) 0 26px;
  opacity: 0;
  pointer-events: none;
  visibility: hidden;
  transform: translateY(40px);
  transition: opacity 640ms cubic-bezier(0.76, 0, 0.24, 1), transform 640ms cubic-bezier(0.76, 0, 0.24, 1), visibility 0s linear 640ms;
}

.scene-panel.is-before {
  transform: translateY(-40px);
}

.scene-panel.is-active {
  z-index: 2;
  opacity: 1;
  pointer-events: auto;
  visibility: visible;
  transform: translateY(0);
  transition-delay: 0s;
}

.hero-scene,
.product-scene,
.principles-scene,
.final-scene {
  background: var(--app-bg);
}

.hero-scene-inner,
.product-scene-inner,
.principles-scene-inner,
.final-scene-inner {
  width: min(calc(100% - 96px), 1360px);
  margin: auto;
}

.hero-scene-inner {
  display: grid;
  grid-template-columns: minmax(390px, 0.86fr) minmax(580px, 1.14fr);
  gap: clamp(52px, 7vw, 116px);
  align-items: center;
}

.landing-hero-copy {
  max-width: 520px;
}

.landing-hero-copy h1 {
  margin: 0;
  color: var(--app-text);
  font-size: clamp(40px, 4vw, 60px);
  line-height: 1.08;
  letter-spacing: -0.04em;
}

.landing-hero-copy h1 span {
  display: block;
  white-space: nowrap;
}

.landing-hero-copy > p:first-of-type {
  max-width: 42ch;
  margin: 26px 0 0;
  color: var(--app-text-secondary);
  font-size: 16px;
  line-height: 1.85;
}

.landing-hero-actions {
  display: flex;
  flex-wrap: wrap;
  align-items: center;
  gap: 22px;
  margin-top: 30px;
}

.landing-text-action,
.landing-skip-button {
  display: inline-flex;
  align-items: center;
  gap: 9px;
  min-height: 40px;
  border: 0;
  padding: 0;
  color: var(--app-text-secondary);
  background: transparent;
  cursor: pointer;
  text-decoration: underline;
  text-decoration-color: var(--app-border-strong);
  text-underline-offset: 5px;
  transition: color 160ms ease, text-decoration-color 160ms ease;
}

.landing-text-action:hover,
.landing-text-action:focus-visible,
.landing-skip-button:hover,
.landing-skip-button:focus-visible {
  color: var(--app-primary-active);
  text-decoration-color: var(--app-primary);
}

.landing-text-action span { color: var(--app-primary); }

.landing-trust-line {
  margin: 20px 0 0 !important;
  color: var(--app-text-muted) !important;
  font-size: 12px !important;
  letter-spacing: 0.02em;
}

.landing-hero-document {
  position: relative;
  display: grid;
  justify-items: center;
  min-width: 0;
}

.landing-hero-document :deep(.resume-paper) {
  width: min(100%, 680px);
  min-height: 590px;
}

.landing-initial-caption {
  position: absolute;
  right: 0;
  bottom: -22px;
  color: var(--app-text-muted);
  font-size: 11px;
}

.product-scene {
  background: var(--app-bg-soft);
}

.product-scene-inner {
  display: grid;
  grid-template-columns: minmax(0, 1.58fr) minmax(350px, 0.9fr);
  gap: clamp(40px, 5vw, 72px);
  align-items: center;
}

.product-scene-visual {
  min-width: 0;
}

.product-scene-copy {
  display: grid;
  align-content: center;
  min-height: 540px;
}

.landing-demo-count {
  display: flex;
  align-items: baseline;
  gap: 8px;
  color: var(--app-text-muted);
  font-variant-numeric: tabular-nums;
}

.landing-demo-count strong {
  color: var(--app-primary);
  font-size: 18px;
}

.landing-demo-count span { font-size: 12px; }
.landing-demo-count span:last-child { margin-left: 8px; color: var(--app-text-secondary); font-weight: 700; }

.landing-demo-copy { min-height: 270px; }

.landing-demo-copy h2 {
  max-width: 100%;
  margin: 26px 0 0;
  color: var(--app-text);
  font-size: clamp(32px, 2.8vw, 42px);
  line-height: 1.18;
  letter-spacing: -0.035em;
}

.landing-demo-copy p {
  max-width: 34ch;
  margin: 22px 0 0;
  color: var(--app-text-secondary);
  font-size: 15px;
  line-height: 1.85;
}

.landing-demo-copy > span {
  display: block;
  max-width: 34ch;
  margin-top: 24px;
  padding-top: 12px;
  border-top: 1px solid var(--app-border-strong);
  color: var(--app-text-muted);
  font-size: 11px;
  line-height: 1.6;
}

.landing-progress {
  display: grid;
  grid-template-columns: repeat(5, minmax(0, 1fr));
  margin: 12px 0 0;
  padding: 0;
  list-style: none;
}

.landing-progress li { position: relative; min-width: 0; }
.landing-progress li:not(:last-child)::after {
  position: absolute;
  z-index: 0;
  top: 9px;
  right: 0;
  left: 18px;
  height: 1px;
  background: var(--app-border-strong);
  content: '';
}

.landing-progress button {
  position: relative;
  z-index: 1;
  display: grid;
  width: 100%;
  gap: 8px;
  justify-items: start;
  border: 0;
  padding: 0 6px 4px 0;
  color: var(--app-text-muted);
  background: transparent;
  cursor: pointer;
  text-align: left;
}

.landing-progress-node {
  display: block;
  width: 19px;
  height: 19px;
  box-sizing: border-box;
  border: 1px solid var(--app-border-strong);
  border-radius: 50%;
  background: var(--app-bg-soft);
  transition: border-color 180ms ease, background-color 180ms ease, transform 180ms ease;
}

.landing-progress-label { overflow: hidden; font-size: 10px; line-height: 1.35; text-overflow: ellipsis; white-space: nowrap; }
.landing-progress button.is-complete .landing-progress-node { border-color: var(--app-primary); background: var(--app-primary); }
.landing-progress button.is-current { color: var(--app-primary-active); font-weight: 750; }
.landing-progress button.is-current .landing-progress-node { border: 5px solid var(--app-primary); background: var(--app-surface); transform: scale(1.08); }
.landing-progress button:hover .landing-progress-node,
.landing-progress button:focus-visible .landing-progress-node { border-color: var(--app-primary); transform: scale(1.12); }
.landing-progress button:focus-visible { border-radius: var(--app-radius-sm); outline-offset: 4px; }

.landing-demo-utilities {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 18px;
  margin-top: 22px;
  color: var(--app-text-muted);
  font-size: 11px;
}

.landing-gesture-hint { transition: opacity 280ms ease; }
.landing-gesture-hint.is-hidden { opacity: 0; }

.principles-scene { background: var(--app-bg); }
.principles-scene-inner { align-self: center; }
.principles-scene-inner > h2 {
  max-width: 16ch;
  margin: 0;
  color: var(--app-text);
  font-size: clamp(38px, 4.6vw, 66px);
  line-height: 1.1;
  letter-spacing: -0.04em;
}

.principle-list {
  display: grid;
  grid-template-columns: repeat(3, minmax(0, 1fr));
  margin-top: 76px;
  border-top: 1px solid var(--app-border-strong);
}

.principle-list article { min-height: 220px; padding: 24px 38px 12px 0; }
.principle-list article + article { border-left: 1px solid var(--app-border-strong); padding-left: 38px; }
.principle-list article > span { color: var(--app-primary); font-size: 12px; }
.principle-list h3 { margin: 58px 0 0; color: var(--app-text); font-size: 22px; }
.principle-list p { max-width: 28ch; margin: 14px 0 0; color: var(--app-text-secondary); font-size: 14px; line-height: 1.75; }

.final-scene { color: var(--app-surface); background: var(--app-text); }
.final-scene-inner {
  display: grid;
  grid-template-columns: minmax(0, 0.86fr) minmax(560px, 1.14fr);
  gap: clamp(52px, 7vw, 110px);
  align-items: center;
}

.landing-final-copy h2 { margin: 0; color: var(--app-surface); font-size: clamp(42px, 5vw, 70px); line-height: 1.04; letter-spacing: -0.04em; }
.landing-final-lead { max-width: 17ch; margin: 18px 0 34px; color: #d7d1c7; font-size: clamp(24px, 2.6vw, 38px); line-height: 1.32; letter-spacing: -0.025em; }
.final-scene footer { position: absolute; right: 0; bottom: 26px; left: 0; color: #8f8981; font-size: 11px; text-align: center; }

@media (max-width: 1200px) {
  .hero-scene-inner,
  .product-scene-inner,
  .principles-scene-inner,
  .final-scene-inner { width: min(calc(100% - 88px), 1120px); }
  .hero-scene-inner { grid-template-columns: minmax(330px, 0.8fr) minmax(500px, 1.2fr); gap: 42px; }
  .landing-hero-copy h1 { font-size: clamp(38px, 4vw, 50px); }
  .landing-hero-document :deep(.resume-paper) { min-height: 550px; }
  .product-scene-inner { grid-template-columns: minmax(0, 1.5fr) minmax(320px, 0.8fr); gap: 42px; }
  .final-scene-inner { grid-template-columns: minmax(0, 0.75fr) minmax(500px, 1.25fr); gap: 42px; }
}

@media (max-width: 900px) {
  .landing-nav { padding-right: 16px; padding-left: 16px; }
  .landing-nav-actions .el-button:first-child { display: none; }
  .landing-nav-actions .el-button:last-child { padding-right: 10px; padding-left: 10px; }
  .scene-panel { padding-top: calc(var(--app-shell-header-height) + 20px); padding-bottom: 20px; overflow-y: auto; }
  .hero-scene-inner,
  .product-scene-inner,
  .principles-scene-inner,
  .final-scene-inner { width: min(calc(100% - 32px), 680px); }
  .hero-scene-inner,
  .product-scene-inner,
  .final-scene-inner { display: flex; flex-direction: column; align-items: stretch; gap: 22px; }
  .hero-scene-inner { justify-content: center; }
  .landing-hero-copy h1 { font-size: clamp(34px, 8vw, 48px); }
  .landing-hero-copy > p:first-of-type { margin-top: 18px; font-size: 15px; }
  .landing-hero-actions { align-items: stretch; flex-direction: column; gap: 10px; margin-top: 22px; }
  .landing-hero-actions .el-button { width: 100%; }
  .landing-text-action { justify-content: center; }
  .landing-hero-document { height: 270px; overflow: hidden; }
  .landing-hero-document :deep(.resume-paper) { width: 100%; min-height: 500px; }
  .landing-initial-caption { right: 10px; bottom: 8px; padding: 4px 7px; background: var(--app-bg); }
  .product-scene-inner { justify-content: center; }
  .product-scene-copy { display: contents; min-height: 0; }
  .landing-demo-count { order: 1; }
  .landing-demo-copy { order: 2; min-height: 150px; }
  .landing-demo-copy h2 { max-width: 18ch; margin-top: 12px; font-size: clamp(29px, 7vw, 40px); }
  .landing-demo-copy p { max-width: 54ch; margin-top: 12px; font-size: 14px; }
  .landing-demo-copy > span { margin-top: 12px; padding-top: 8px; }
  .product-scene-visual { order: 3; }
  .landing-progress { order: 4; margin-top: 0; }
  .landing-demo-utilities { order: 5; justify-content: flex-start; margin-top: 3px; }
  .landing-progress-label { display: none; }
  .landing-progress button { min-height: 28px; }
  .principles-scene-inner { align-self: center; }
  .principles-scene-inner > h2 { font-size: clamp(36px, 8vw, 52px); }
  .principle-list { grid-template-columns: 1fr; margin-top: 46px; }
  .principle-list article { display: grid; grid-template-columns: 42px minmax(0, 1fr); min-height: 0; padding: 22px 0; border-bottom: 1px solid var(--app-border-strong); }
  .principle-list article + article { border-left: 0; padding-left: 0; }
  .principle-list h3 { margin: 0; }
  .principle-list p { grid-column: 2; }
  .final-scene-inner { justify-content: center; }
  .landing-final-copy h2 { font-size: clamp(42px, 10vw, 60px); }
  .landing-final-lead { margin-bottom: 22px; font-size: clamp(24px, 6vw, 34px); }
  .final-scene footer { bottom: 14px; }
}

@media (max-width: 520px) {
  .scene-panel { padding-top: calc(var(--app-shell-header-height) + 14px); padding-bottom: 16px; }
  .hero-scene-inner,
  .product-scene-inner,
  .principles-scene-inner,
  .final-scene-inner { width: calc(100% - 32px); }
  .landing-hero-copy h1 { font-size: clamp(32px, 9vw, 41px); }
  .landing-hero-document { height: 224px; }
  .landing-hero-document :deep(.resume-paper) { min-height: 470px; }
  .landing-demo-copy { min-height: 164px; }
  .landing-demo-copy h2 { font-size: clamp(28px, 8vw, 35px); }
  .product-scene-visual :deep(.landing-demo-paper) { max-height: 255px; }
  .product-scene-visual :deep(.landing-demo-annotation) { max-height: 145px; }
  .landing-demo-utilities { font-size: 10px; }
  .principles-scene-inner > h2 { font-size: 37px; }
  .final-scene-inner { gap: 12px; }
  .landing-final-copy h2 { font-size: 44px; }
  .landing-final-lead { font-size: 24px; }
}

@media (prefers-reduced-motion: reduce) {
  .scene-panel { transition-duration: 80ms; }
  .landing-copy-enter-active,
  .landing-copy-leave-active { transition: opacity 80ms linear; }
  .landing-copy-enter-from,
  .landing-copy-leave-to { transform: none; }
  .landing-gesture-hint,
  .landing-progress-node { transition-duration: 0.01ms; }
}

.sr-only { position: absolute; width: 1px; height: 1px; overflow: hidden; clip: rect(0, 0, 0, 0); clip-path: inset(50%); white-space: nowrap; }
</style>
