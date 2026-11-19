import { ConfigDrawer } from '@/components/config-drawer'
import { Header } from '@/components/layout/header'
import { Main } from '@/components/layout/main'
import { ProfileDropdown } from '@/components/profile-dropdown'
import { Search } from '@/components/search'
import { ThemeSwitch } from '@/components/theme-switch'

type ProjectPageProps = {
  title: React.ReactNode
  description: React.ReactNode
  /** 제목 오른쪽에 둘 버튼 */
  actions?: React.ReactNode
  /** 제목 위에 둘 것(돌아가기 링크 등) */
  before?: React.ReactNode
  children?: React.ReactNode
}

/** 프로젝트 안 화면의 공통 틀: 위쪽 막대 + 제목. */
export function ProjectPage({
  title,
  description,
  actions,
  before,
  children,
}: ProjectPageProps) {
  return (
    <>
      <Header fixed>
        <Search />
        <div className='ms-auto flex items-center space-x-4'>
          <ThemeSwitch />
          <ConfigDrawer />
          <ProfileDropdown />
        </div>
      </Header>
      <Main>
        {before}
        <div className='mb-4 flex flex-wrap items-end justify-between gap-2'>
          <div className='min-w-0'>
            <h2 className='text-2xl font-bold tracking-tight break-words'>
              {title}
            </h2>
            <div className='text-muted-foreground'>{description}</div>
          </div>
          {actions && (
            <div className='flex flex-wrap items-center gap-2'>{actions}</div>
          )}
        </div>
        {children}
      </Main>
    </>
  )
}

/** 아직 만들지 않은 화면 자리. */
export function NotYet({ children }: { children: React.ReactNode }) {
  return (
    <div className='rounded-md border border-dashed p-6 text-sm text-muted-foreground'>
      {children}
    </div>
  )
}
