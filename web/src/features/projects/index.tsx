import { useState } from 'react'
import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query'
import { Link, useNavigate } from '@tanstack/react-router'
import { ChevronRight, FolderKanban, Loader2, Plus } from 'lucide-react'
import { Logo } from '@/assets/logo'
import { createProject } from '@/api/authClient'
import { serverErrorMessage } from '@/lib/handle-server-error'
import { Badge } from '@/components/ui/badge'
import { Button } from '@/components/ui/button'
import {
  Card,
  CardContent,
  CardDescription,
  CardHeader,
  CardTitle,
} from '@/components/ui/card'
import { Input } from '@/components/ui/input'
import { Label } from '@/components/ui/label'
import { Skeleton } from '@/components/ui/skeleton'
import { ProfileDropdown } from '@/components/profile-dropdown'
import { ROLE_LABEL, projectsQueryOptions } from './queries'

/** 로그인 직후 화면: 내 프로젝트를 고르거나 새로 만든다. */
export function Projects() {
  const navigate = useNavigate()
  const queryClient = useQueryClient()
  const [title, setTitle] = useState('')
  const projects = useQuery(projectsQueryOptions())

  const create = useMutation({
    mutationFn: (value: string) => createProject(value),
    onSuccess: async (id) => {
      setTitle('')
      await queryClient.invalidateQueries({ queryKey: ['projects'] })
      navigate({ to: '/p/$projectId/documents', params: { projectId: id } })
    },
  })

  const trimmed = title.trim()

  return (
    <div className='min-h-svh bg-background'>
      <header className='flex h-16 items-center justify-between border-b px-4'>
        <div className='flex items-center gap-2'>
          <Logo className='text-primary' />
          <span className='text-lg font-medium'>Contexa</span>
        </div>
        <ProfileDropdown />
      </header>

      <main className='mx-auto flex w-full max-w-2xl flex-col gap-6 px-4 py-8'>
        <div>
          <h1 className='text-2xl font-bold tracking-tight'>프로젝트</h1>
          <p className='text-muted-foreground'>
            회의록과 결정사항은 프로젝트 안에서만 보입니다.
          </p>
        </div>

        <Card>
          <CardHeader>
            <CardTitle>내 프로젝트</CardTitle>
            <CardDescription>
              내가 구성원으로 들어가 있는 프로젝트입니다.
            </CardDescription>
          </CardHeader>
          <CardContent className='grid gap-2'>
            {projects.isPending &&
              [0, 1].map((i) => <Skeleton key={i} className='h-14 w-full' />)}

            {projects.isError && (
              <p className='text-sm text-destructive'>
                {serverErrorMessage(projects.error)}
              </p>
            )}

            {projects.isSuccess && projects.data.length === 0 && (
              <p className='text-sm text-muted-foreground'>
                아직 속한 프로젝트가 없습니다. 아래에서 새로 만들거나, 프로젝트
                소유자에게 구성원으로 넣어 달라고 요청하세요.
              </p>
            )}

            {projects.isSuccess &&
              projects.data.map((project) => (
                <Link
                  key={project.id}
                  to='/p/$projectId/documents'
                  params={{ projectId: project.id }}
                  className='flex items-center gap-3 rounded-md border p-3 hover:bg-accent'
                >
                  <FolderKanban className='size-5 shrink-0 text-primary' />
                  <bdi className='truncate font-medium'>{project.title}</bdi>
                  <Badge variant='secondary' className='ms-auto'>
                    {ROLE_LABEL[project.role]}
                  </Badge>
                  <ChevronRight className='size-4 shrink-0 text-muted-foreground rtl:rotate-180' />
                </Link>
              ))}
          </CardContent>
        </Card>

        <Card>
          <CardHeader>
            <CardTitle>새 프로젝트</CardTitle>
            <CardDescription>
              만든 사람이 소유자가 됩니다. 구성원은 나중에 이메일로 넣습니다.
            </CardDescription>
          </CardHeader>
          <CardContent>
            <form
              className='flex flex-col gap-2 sm:flex-row sm:items-end'
              onSubmit={(e) => {
                e.preventDefault()
                if (trimmed) create.mutate(trimmed)
              }}
            >
              <div className='grid flex-1 gap-1.5'>
                <Label htmlFor='project-title'>프로젝트 이름</Label>
                <Input
                  id='project-title'
                  dir='auto'
                  placeholder='예: 2027 국제교류 행사 준비팀'
                  value={title}
                  onChange={(e) => setTitle(e.target.value)}
                />
              </div>
              <Button type='submit' disabled={!trimmed || create.isPending}>
                {create.isPending ? (
                  <Loader2 className='animate-spin' />
                ) : (
                  <Plus />
                )}
                만들기
              </Button>
            </form>
          </CardContent>
        </Card>
      </main>
    </div>
  )
}
