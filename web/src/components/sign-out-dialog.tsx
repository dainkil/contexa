import { useQueryClient } from '@tanstack/react-query'
import { useNavigate, useLocation } from '@tanstack/react-router'
import { useAuthStore } from '@/stores/auth-store'
import { ConfirmDialog } from '@/components/confirm-dialog'

interface SignOutDialogProps {
  open: boolean
  onOpenChange: (open: boolean) => void
}

export function SignOutDialog({ open, onOpenChange }: SignOutDialogProps) {
  const navigate = useNavigate()
  const location = useLocation()
  const queryClient = useQueryClient()
  const { auth } = useAuthStore()

  const handleSignOut = () => {
    auth.reset()
    // 다음에 로그인하는 사람이 이전 사람의 프로젝트·검토함을 보지 않게 캐시를 비운다.
    queryClient.clear()
    navigate({
      to: '/sign-in',
      search: { redirect: location.href },
      replace: true,
    })
  }

  return (
    <ConfirmDialog
      open={open}
      onOpenChange={onOpenChange}
      title='로그아웃'
      desc='로그아웃할까요? 다시 쓰려면 로그인해야 합니다.'
      cancelBtnText='취소'
      confirmText='로그아웃'
      destructive
      handleConfirm={handleSignOut}
      className='sm:max-w-sm'
    />
  )
}
