import { createFileRoute, redirect } from '@tanstack/react-router'

export const Route = createFileRoute('/_authenticated/p/$projectId/')({
  beforeLoad: ({ params }) => {
    throw redirect({
      to: '/p/$projectId/documents',
      params: { projectId: params.projectId },
    })
  },
})
