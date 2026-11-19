import { type SVGProps } from 'react'
import { cn } from '@/lib/utils'

/** Contexa 표시: 문서 한 장과 줄, 그중 한 줄이 근거로 강조된 모양. */
export function Logo({ className, ...props }: SVGProps<SVGSVGElement>) {
  return (
    <svg
      id='contexa-logo'
      viewBox='0 0 24 24'
      xmlns='http://www.w3.org/2000/svg'
      height='24'
      width='24'
      fill='none'
      stroke='currentColor'
      strokeWidth='2'
      strokeLinecap='round'
      strokeLinejoin='round'
      className={cn('size-6', className)}
      {...props}
    >
      <title>Contexa</title>
      <rect x='4' y='3' width='16' height='18' rx='2' />
      <path d='M8 8h8' />
      <path d='M8 12h5' strokeWidth='3.5' />
      <path d='M8 16h8' />
    </svg>
  )
}
