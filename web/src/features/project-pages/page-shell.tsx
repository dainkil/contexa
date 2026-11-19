import { ConfigDrawer } from '@/components/config-drawer'
import { Header } from '@/components/layout/header'
import { Main } from '@/components/layout/main'
import { ProfileDropdown } from '@/components/profile-dropdown'
import { Search } from '@/components/search'
import { ThemeSwitch } from '@/components/theme-switch'

type ProjectPageProps = {
  title: string
  description: string
  children?: React.ReactNode
}

/** 프로젝트 안 화면의 공통 틀: 위쪽 막대 + 제목. */
export function ProjectPage({
  title,
  description,
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
        <div className='mb-4'>
          <h2 className='text-2xl font-bold tracking-tight'>{title}</h2>
          <p className='text-muted-foreground'>{description}</p>
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
