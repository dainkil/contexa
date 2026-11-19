import { useRef } from 'react'
import { FileText } from 'lucide-react'
import { toast } from 'sonner'
import { Button } from '@/components/ui/button'

/** 브라우저에서 읽어 들일 수 있는 가장 큰 텍스트 파일. */
export const MAX_TEXT_FILE_BYTES = 5 * 1024 * 1024

/**
 * .txt 파일을 브라우저에서 읽어 본문 칸을 채운다. server로 파일을 보내지 않는다.
 * (파일 업로드와 pdf·docx는 나중에 server가 받는다.)
 */
export function TextFileButton({
  onLoad,
}: {
  onLoad: (text: string, fileName: string) => void
}) {
  const input = useRef<HTMLInputElement>(null)

  async function read(file: File) {
    const isText = file.type === 'text/plain' || /\.txt$/i.test(file.name)
    if (!isText) {
      toast.error(
        '텍스트(.txt) 파일만 불러올 수 있습니다. PDF·Word는 아직 지원하지 않습니다.'
      )
      return
    }
    if (file.size > MAX_TEXT_FILE_BYTES) {
      toast.error('5MB보다 큰 파일은 불러올 수 없습니다.')
      return
    }
    onLoad(await file.text(), file.name)
  }

  return (
    <>
      <input
        ref={input}
        type='file'
        accept='.txt,text/plain'
        className='hidden'
        aria-label='텍스트 파일 선택'
        onChange={(e) => {
          const file = e.target.files?.[0]
          e.target.value = ''
          if (file) void read(file)
        }}
      />
      <Button
        type='button'
        variant='outline'
        size='sm'
        onClick={() => input.current?.click()}
      >
        <FileText />
        텍스트 파일 불러오기
      </Button>
    </>
  )
}
