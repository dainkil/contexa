import { z } from 'zod'
import type { DocumentInput, DocumentKind } from '@/api/types'
import { KIND_LABEL, MIXED_LANGUAGE } from './status'

/** 문서 올리기 폼의 값과 검사. server가 받는 DocumentInput으로 바꾸는 규칙도 여기 둔다. */

export const KINDS = Object.keys(KIND_LABEL) as [
  DocumentKind,
  ...DocumentKind[],
]

export const formSchema = z.object({
  title: z.string().trim().min(1, '제목을 입력하세요.'),
  kind: z.enum(KINDS),
  language: z.string(),
  visibility: z.enum(['PROJECT', 'PRIVATE']),
  externalOk: z.boolean(),
  content: z
    .string()
    .refine(
      (v) => v.trim().length > 0,
      '본문을 붙여 넣거나 파일을 불러오세요.'
    ),
})

export type FormValues = z.infer<typeof formSchema>

export const DEFAULTS: FormValues = {
  title: '',
  kind: 'TRANSCRIPT',
  language: 'ko',
  visibility: 'PROJECT',
  externalOk: false,
  content: '',
}

/** 폼 값 → server가 받는 문서. 섞인 언어는 null, 개인 메모는 외부 공유를 끈다. */
export function toDocumentInput(values: FormValues): DocumentInput {
  return {
    title: values.title.trim(),
    kind: values.kind,
    language: values.language === MIXED_LANGUAGE ? null : values.language,
    visibility: values.visibility,
    externalOk: values.visibility === 'PROJECT' && values.externalOk,
    content: values.content,
  }
}
