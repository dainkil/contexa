import { createFileRoute, notFound } from '@tanstack/react-router'
import { AuthenticatedLayout } from '@/components/layout/authenticated-layout'
import { ProjectNotFound } from '@/features/projects/project-not-found'
import { projectsQueryOptions } from '@/features/projects/queries'

/**
 * 프로젝트 안 화면들. 내 프로젝트 목록(server가 준 것)에 없는 id면 열지 않는다.
 * 없는 프로젝트와 볼 수 없는 프로젝트는 server처럼 구분하지 않는다.
 * 이 검사는 화면 편의일 뿐이고, 실제 차단은 server가 요청마다 한다.
 */
export const Route = createFileRoute('/_authenticated/p/$projectId')({
  loader: async ({ context, params }) => {
    const projects = await context.queryClient.ensureQueryData(
      projectsQueryOptions()
    )
    const project = projects.find((p) => p.id === params.projectId)
    if (!project) throw notFound()
    return { project }
  },
  component: AuthenticatedLayout,
  notFoundComponent: ProjectNotFound,
})
