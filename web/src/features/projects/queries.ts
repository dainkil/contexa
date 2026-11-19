import { queryOptions } from '@tanstack/react-query'
import { listProjects } from '@/api/authClient'
import type { Role } from '@/api/types'

export const projectsQueryOptions = () =>
  queryOptions({
    queryKey: ['projects'],
    queryFn: listProjects,
  })

export const ROLE_LABEL: Record<Role, string> = {
  OWNER: '소유자',
  REVIEWER: '검토자',
  MEMBER: '구성원',
}
