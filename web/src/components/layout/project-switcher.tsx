import { useQuery } from '@tanstack/react-query'
import { useLocation, useNavigate, useParams } from '@tanstack/react-router'
import { ChevronsUpDown, FolderKanban, Plus } from 'lucide-react'
import {
  DropdownMenu,
  DropdownMenuContent,
  DropdownMenuItem,
  DropdownMenuLabel,
  DropdownMenuSeparator,
  DropdownMenuTrigger,
} from '@/components/ui/dropdown-menu'
import {
  SidebarMenu,
  SidebarMenuButton,
  SidebarMenuItem,
  useSidebar,
} from '@/components/ui/sidebar'
import { ROLE_LABEL, projectsQueryOptions } from '@/features/projects/queries'

/** 사이드바 맨 위: 지금 프로젝트와 내 역할, 다른 프로젝트로 옮겨 가기. */
export function ProjectSwitcher() {
  const { isMobile } = useSidebar()
  const navigate = useNavigate()
  const pathname = useLocation({ select: (l) => l.pathname })
  const { projectId } = useParams({ strict: false })
  const { data: projects = [] } = useQuery(projectsQueryOptions())

  const current = projects.find((p) => p.id === projectId)

  // 같은 화면(문서·검토함 등)을 유지한 채 프로젝트만 바꾼다.
  const switchTo = (nextId: string) => {
    const section =
      projectId && pathname.startsWith(`/p/${projectId}/`)
        ? pathname.slice(`/p/${projectId}`.length)
        : '/documents'
    navigate({ to: `/p/${nextId}${section}` })
  }

  return (
    <SidebarMenu>
      <SidebarMenuItem>
        <DropdownMenu>
          <DropdownMenuTrigger asChild>
            <SidebarMenuButton
              size='lg'
              className='data-[state=open]:bg-sidebar-accent data-[state=open]:text-sidebar-accent-foreground'
            >
              <div className='flex aspect-square size-8 items-center justify-center rounded-lg bg-sidebar-primary text-sidebar-primary-foreground'>
                <FolderKanban className='size-4' />
              </div>
              <div className='grid flex-1 text-start text-sm leading-tight'>
                <span className='truncate font-semibold' dir='auto'>
                  {current?.title ?? '프로젝트'}
                </span>
                <span className='truncate text-xs'>
                  {current ? ROLE_LABEL[current.role] : ''}
                </span>
              </div>
              <ChevronsUpDown className='ms-auto' />
            </SidebarMenuButton>
          </DropdownMenuTrigger>
          <DropdownMenuContent
            className='w-(--radix-dropdown-menu-trigger-width) min-w-56 rounded-lg'
            align='start'
            side={isMobile ? 'bottom' : 'right'}
            sideOffset={4}
          >
            <DropdownMenuLabel className='text-xs text-muted-foreground'>
              내 프로젝트
            </DropdownMenuLabel>
            {projects.map((project) => (
              <DropdownMenuItem
                key={project.id}
                onClick={() => switchTo(project.id)}
                className='gap-2 p-2'
              >
                <div className='flex size-6 items-center justify-center rounded-sm border'>
                  <FolderKanban className='size-4 shrink-0' />
                </div>
                <bdi className='truncate'>{project.title}</bdi>
                <span className='ms-auto text-xs text-muted-foreground'>
                  {ROLE_LABEL[project.role]}
                </span>
              </DropdownMenuItem>
            ))}
            <DropdownMenuSeparator />
            <DropdownMenuItem
              className='gap-2 p-2'
              onClick={() => navigate({ to: '/projects' })}
            >
              <div className='flex size-6 items-center justify-center rounded-md border bg-background'>
                <Plus className='size-4' />
              </div>
              <div className='font-medium text-muted-foreground'>
                새 프로젝트 · 전체 목록
              </div>
            </DropdownMenuItem>
          </DropdownMenuContent>
        </DropdownMenu>
      </SidebarMenuItem>
    </SidebarMenu>
  )
}
