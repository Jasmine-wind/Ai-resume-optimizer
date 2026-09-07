import { computed, ref } from 'vue'
import { defineStore } from 'pinia'
import { login as loginApi } from '@/api/auth'
import { getCurrentUser } from '@/api/user'
import type { CurrentUser, LoginRequest } from '@/types/auth'
import { clearAuthToken, readAuthToken, writeAuthToken } from '@/utils/auth-token'

export const useAuthStore = defineStore('auth', () => {
  const token = ref(readAuthToken())
  const currentUser = ref<CurrentUser | null>(null)
  const loading = ref(false)

  const isAuthenticated = computed(() => Boolean(token.value))

  const setToken = (value: string) => {
    token.value = value
    writeAuthToken(value)
  }

  const clearAuth = () => {
    token.value = ''
    currentUser.value = null
    clearAuthToken()
  }

  const login = async (payload: LoginRequest) => {
    loading.value = true

    try {
      const data = await loginApi(payload)

      setToken(data.token)
      currentUser.value = {
        id: data.userId,
        username: data.username,
        email: data.email,
        nickname: data.nickname,
        createdAt: '',
      }

      return data
    } finally {
      loading.value = false
    }
  }

  const fetchMe = async () => {
    if (!token.value) {
      currentUser.value = null
      return null
    }

    loading.value = true

    try {
      const data = await getCurrentUser()

      currentUser.value = data
      return data
    } catch (error) {
      clearAuth()
      throw error
    } finally {
      loading.value = false
    }
  }

  const updateCurrentUser = (user: CurrentUser) => {
    currentUser.value = user
  }

  const logout = () => {
    clearAuth()
  }

  return {
    token,
    currentUser,
    loading,
    isAuthenticated,
    login,
    logout,
    fetchMe,
    updateCurrentUser,
  }
})
