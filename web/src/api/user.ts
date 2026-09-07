import request from '@/api/request'
import type { CurrentUser } from '@/types/auth'

export const getCurrentUser = () => {
  return request.get<CurrentUser>('/api/users/me')
}

export const updateCurrentUserProfile = (payload: { nickname: string }) => {
  return request.patch<CurrentUser>('/api/users/me', payload)
}
