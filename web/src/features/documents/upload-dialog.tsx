import { useForm, useWatch } from 'react-hook-form'
import { zodResolver } from '@hookform/resolvers/zod'
import { useMutation, useQueryClient } from '@tanstack/react-query'
import { useNavigate } from '@tanstack/react-router'
import { Loader2, Upload } from 'lucide-react'
import { toast } from 'sonner'
import { uploadDocument } from '@/api/documentClient'
import type { DocumentInput } from '@/api/types'
import { Button } from '@/components/ui/button'
import { Checkbox } from '@/components/ui/checkbox'
import {
  Dialog,
  DialogContent,
  DialogDescription,
  DialogFooter,
  DialogHeader,
  DialogTitle,
} from '@/components/ui/dialog'
import {
  Form,
  FormControl,
  FormDescription,
  FormField,
  FormItem,
  FormLabel,
  FormMessage,
} from '@/components/ui/form'
import { Input } from '@/components/ui/input'
import { RadioGroup, RadioGroupItem } from '@/components/ui/radio-group'
import {
  Select,
  SelectContent,
  SelectItem,
  SelectTrigger,
  SelectValue,
} from '@/components/ui/select'
import { Textarea } from '@/components/ui/textarea'
import { documentsKey } from './queries'
import { KIND_LABEL, LANGUAGES, MIXED_LANGUAGE } from './status'
import { TextFileButton } from './text-file-button'
import {
  DEFAULTS,
  type FormValues,
  KINDS,
  formSchema,
  toDocumentInput,
} from './upload-form'

/** 문서 올리기 창. 올리면 그 문서의 원문 뷰어로 간다. */
export function UploadDialog({
  projectId,
  open,
  onOpenChange,
}: {
  projectId: string
  open: boolean
  onOpenChange: (open: boolean) => void
}) {
  const navigate = useNavigate()
  const queryClient = useQueryClient()
  const form = useForm<FormValues>({
    resolver: zodResolver(formSchema),
    defaultValues: DEFAULTS,
  })
  const isPrivate =
    useWatch({ control: form.control, name: 'visibility' }) === 'PRIVATE'

  const upload = useMutation({
    mutationFn: (input: DocumentInput) => uploadDocument(projectId, input),
    onSuccess: async (started) => {
      toast.success('올렸습니다. 발언으로 자르는 작업을 맡겼습니다.')
      form.reset(DEFAULTS)
      onOpenChange(false)
      await queryClient.invalidateQueries({ queryKey: documentsKey(projectId) })
      navigate({
        to: '/p/$projectId/documents/$documentId',
        params: { projectId, documentId: started.version.documentId },
      })
    },
  })

  return (
    <Dialog open={open} onOpenChange={onOpenChange}>
      <DialogContent className='max-h-[90svh] overflow-y-auto sm:max-w-2xl'>
        <DialogHeader>
          <DialogTitle>문서 올리기</DialogTitle>
          <DialogDescription>
            이미 글로 옮긴 회의록이나 업무 문서를 붙여 넣으세요. 올리면 발언
            단위로 자르고, AI가 결정·할 일 후보를 뽑아 검토함에 넣습니다.
          </DialogDescription>
        </DialogHeader>

        <Form {...form}>
          <form
            id='upload-document'
            className='grid gap-4'
            onSubmit={form.handleSubmit((values) =>
              upload.mutate(toDocumentInput(values))
            )}
          >
            <FormField
              control={form.control}
              name='title'
              render={({ field }) => (
                <FormItem>
                  <FormLabel>제목</FormLabel>
                  <FormControl>
                    <Input
                      dir='auto'
                      placeholder='예: 3차 회의 전사문 (11월 12일)'
                      {...field}
                    />
                  </FormControl>
                  <FormMessage />
                </FormItem>
              )}
            />

            <div className='grid gap-4 sm:grid-cols-2'>
              <FormField
                control={form.control}
                name='kind'
                render={({ field }) => (
                  <FormItem>
                    <FormLabel>종류</FormLabel>
                    <Select value={field.value} onValueChange={field.onChange}>
                      <FormControl>
                        <SelectTrigger className='w-full'>
                          <SelectValue />
                        </SelectTrigger>
                      </FormControl>
                      <SelectContent>
                        {KINDS.map((kind) => (
                          <SelectItem key={kind} value={kind}>
                            {KIND_LABEL[kind]}
                          </SelectItem>
                        ))}
                      </SelectContent>
                    </Select>
                    <FormDescription>
                      회의 전사문은 줄마다 화자를 나눠 자릅니다.
                    </FormDescription>
                  </FormItem>
                )}
              />
              <FormField
                control={form.control}
                name='language'
                render={({ field }) => (
                  <FormItem>
                    <FormLabel>언어</FormLabel>
                    <Select value={field.value} onValueChange={field.onChange}>
                      <FormControl>
                        <SelectTrigger className='w-full'>
                          <SelectValue />
                        </SelectTrigger>
                      </FormControl>
                      <SelectContent>
                        {LANGUAGES.map((language) => (
                          <SelectItem key={language.code} value={language.code}>
                            {language.label}
                          </SelectItem>
                        ))}
                        <SelectItem value={MIXED_LANGUAGE}>
                          여러 언어가 섞임
                        </SelectItem>
                      </SelectContent>
                    </Select>
                  </FormItem>
                )}
              />
            </div>

            <FormField
              control={form.control}
              name='visibility'
              render={({ field }) => (
                <FormItem>
                  <FormLabel>누가 읽나요</FormLabel>
                  <FormControl>
                    <RadioGroup
                      value={field.value}
                      onValueChange={field.onChange}
                      className='grid gap-2 sm:grid-cols-2'
                    >
                      <FormItem className='flex items-center gap-2'>
                        <FormControl>
                          <RadioGroupItem value='PROJECT' />
                        </FormControl>
                        <FormLabel className='font-normal'>
                          프로젝트 구성원 모두
                        </FormLabel>
                      </FormItem>
                      <FormItem className='flex items-center gap-2'>
                        <FormControl>
                          <RadioGroupItem value='PRIVATE' />
                        </FormControl>
                        <FormLabel className='font-normal'>
                          나만 (개인 메모)
                        </FormLabel>
                      </FormItem>
                    </RadioGroup>
                  </FormControl>
                </FormItem>
              )}
            />

            <FormField
              control={form.control}
              name='externalOk'
              render={({ field }) => (
                <FormItem className='flex items-start gap-2'>
                  <FormControl>
                    <Checkbox
                      checked={!isPrivate && field.value}
                      disabled={isPrivate}
                      onCheckedChange={(checked) =>
                        field.onChange(checked === true)
                      }
                    />
                  </FormControl>
                  <div className='grid gap-1'>
                    <FormLabel className='font-normal'>
                      외부로 나가는 답변의 근거로 써도 됩니다
                    </FormLabel>
                    <FormDescription>
                      외부 AI 에이전트와 외부용 답변은 이 표시가 있는 문서만
                      읽습니다. 개인 메모는 외부에 공유할 수 없습니다.
                    </FormDescription>
                  </div>
                </FormItem>
              )}
            />

            <FormField
              control={form.control}
              name='content'
              render={({ field }) => (
                <FormItem>
                  <div className='flex flex-wrap items-center justify-between gap-2'>
                    <FormLabel>본문</FormLabel>
                    <TextFileButton
                      onLoad={(text, fileName) => {
                        form.setValue('content', text, {
                          shouldValidate: true,
                        })
                        if (!form.getValues('title').trim()) {
                          form.setValue(
                            'title',
                            fileName.replace(/\.txt$/i, '')
                          )
                        }
                      }}
                    />
                  </div>
                  <FormControl>
                    <Textarea
                      dir='auto'
                      rows={10}
                      className='max-h-[40svh] font-mono text-sm'
                      placeholder={
                        '민지: 예산 승인 전까지 인쇄는 보류입니다.\nAhmed: OK, then we wait.'
                      }
                      {...field}
                    />
                  </FormControl>
                  <FormMessage />
                </FormItem>
              )}
            />
          </form>
        </Form>

        <DialogFooter>
          <Button
            type='submit'
            form='upload-document'
            disabled={upload.isPending}
          >
            {upload.isPending ? (
              <Loader2 className='animate-spin' />
            ) : (
              <Upload />
            )}
            올리기
          </Button>
        </DialogFooter>
      </DialogContent>
    </Dialog>
  )
}
