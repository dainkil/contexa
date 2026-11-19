import { BookOpen, FileText, Inbox, ListChecks } from 'lucide-react'
import { type NavGroup } from '../types'

/**
 * 프로젝트 안 메뉴. 순서: 문서 → 검토함 → 맥락과 할 일 → 브리핑.
 * projectId가 없으면(프로젝트 밖) 메뉴도 없다.
 */
export function projectNavGroups(projectId: string | undefined): NavGroup[] {
  if (!projectId) return []
  const base = `/p/${projectId}`
  return [
    {
      title: '프로젝트',
      items: [
        { title: '문서와 전사문', url: `${base}/documents`, icon: FileText },
        { title: '검토함', url: `${base}/reviews`, icon: Inbox },
        { title: '맥락과 할 일', url: `${base}/context`, icon: ListChecks },
        { title: '브리핑', url: `${base}/briefing`, icon: BookOpen },
      ],
    },
  ]
}
