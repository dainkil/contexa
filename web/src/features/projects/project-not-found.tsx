import { Link } from '@tanstack/react-router'
import { Button } from '@/components/ui/button'

/** server처럼 "없음"과 "볼 권한 없음"을 구분하지 않는다. */
export function ProjectNotFound() {
  return (
    <div className='flex h-svh flex-col items-center justify-center gap-2 px-4 text-center'>
      <h1 className='text-2xl font-bold'>이 프로젝트를 열 수 없습니다</h1>
      <p className='text-muted-foreground'>
        없는 프로젝트이거나, 내가 구성원이 아닌 프로젝트입니다.
      </p>
      <Button asChild className='mt-4'>
        <Link to='/projects'>프로젝트 목록으로</Link>
      </Button>
    </div>
  )
}
