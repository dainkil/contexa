import { useState } from 'react'
import { useMutation, useQueryClient } from '@tanstack/react-query'
import { Link } from '@tanstack/react-router'
import { Loader2, Pencil } from 'lucide-react'
import { toast } from 'sonner'
import { applyReviewAction } from '@/api/reviewClient'
import type {
  Edge,
  NodeType,
  NodeView,
  ReviewItem,
  ReviewStatus,
  Source,
  Uuid,
  Verdict,
} from '@/api/types'
import { cn } from '@/lib/utils'
import { Badge } from '@/components/ui/badge'
import { Button } from '@/components/ui/button'
import { Input } from '@/components/ui/input'
import { Label } from '@/components/ui/label'
import {
  Select,
  SelectContent,
  SelectItem,
  SelectTrigger,
  SelectValue,
} from '@/components/ui/select'
import { Textarea } from '@/components/ui/textarea'
import { ConfirmDialog } from '@/components/confirm-dialog'
import {
  type EdgeChoice,
  VERDICT_LABEL,
  buildAction,
  bundleVerdicts,
  edgeChoices,
  resultMessage,
} from './action'
import {
  type FieldSpec,
  type FieldValues,
  changedFields,
  currentValues,
  fieldsOf,
  missingFields,
} from './fields'
import {
  DECIDING_KINDS,
  EVENT_KIND_LABEL,
  STATUS,
  TYPE_LABEL,
  formatDay,
  relationLabel,
  stateLabel,
} from './labels'

/** 카드 종류마다 머리에 보여 줄 상태 값. */
const SUMMARY: Partial<Record<NodeType, string[]>> = {
  DECISION_ITEM: ['decisionState'],
  DECISION_EVENT: ['standing', 'occurredAt'],
  CONDITION: ['state'],
  TASK: ['workStatus', 'acceptance', 'dueAt'],
  ENTITY: ['kind'],
}

const DATE_STATES: Record<string, string> = {
  occurredAt: '일어난 날',
  dueAt: '기한',
}

const PENDING = new Set<ReviewStatus>(['CANDIDATE', 'HELD'])

type Props = {
  projectId: Uuid
  item: ReviewItem
  /** 소유자·검토자만 판단할 수 있다. 구성원은 보기만 한다. */
  canReview: boolean
  /** 문서 id → 제목. 근거 원문에 문서 이름을 붙인다. */
  documentTitles: Map<Uuid, string>
}

/**
 * 검토 묶음 한 장: 카드, 근거 원문, 딸린 줄, 판단 버튼.
 * 묶음이 바뀌면(버전이 오르면) 부모가 key를 바꿔 고른 것과 고치던 값을 비운다.
 */
export function ReviewCard({
  projectId,
  item,
  canReview,
  documentTitles,
}: Props) {
  const queryClient = useQueryClient()
  const [choices, setChoices] = useState<Record<Uuid, EdgeChoice>>({})
  const [values, setValues] = useState<FieldValues | null>(null)
  const [confirming, setConfirming] = useState<Verdict | null>(null)

  const card = item.card
  const related = new Map(item.related.map((view) => [view.ref.id, view]))
  const sources = new Map(item.sources.map((s) => [s.span.id, s]))
  const verdicts = canReview ? bundleVerdicts(item) : []
  const editable =
    canReview &&
    card !== null &&
    fieldsOf(card.ref.type).length > 0 &&
    (PENDING.has(card.status) || card.status === 'CONFIRMED')
  const picked = Object.values(choices).some((c) => c !== 'FOLLOW')

  const act = useMutation({
    mutationFn: (input: {
      verdict: Verdict | null
      corrections: Record<string, unknown>
    }) => {
      const action = buildAction(
        item,
        input.verdict,
        choices,
        input.corrections
      )
      if (!action) return Promise.reject(new Error('보낼 판단이 없습니다.'))
      return applyReviewAction(projectId, item.id, action)
    },
    onSuccess: (result, input) => {
      setConfirming(null)
      toast.success(
        resultMessage(
          input.verdict,
          Object.keys(input.corrections).length > 0,
          result.impacts
        )
      )
    },
    // 처리했든 409로 막혔든 묶음·맥락을 다시 읽는다
    onSettled: () =>
      queryClient.invalidateQueries({ queryKey: ['projects', projectId] }),
  })

  const decide = (verdict: Verdict) => {
    if (verdict === 'REJECT' || verdict === 'RETRACT') setConfirming(verdict)
    else act.mutate({ verdict, corrections: {} })
  }

  const ownerSource = card ? undefined : sources.get(item.owner.id)
  const titleId = `review-${item.id}`

  return (
    <article
      aria-labelledby={titleId}
      className='grid gap-3 rounded-lg border bg-card p-4'
    >
      <header className='grid gap-1'>
        <div className='flex flex-wrap items-center gap-2'>
          <Badge variant='secondary'>{TYPE_LABEL[item.owner.type]}</Badge>
          {card?.ref.type === 'DECISION_EVENT' && card.states.kind && (
            <Badge
              variant={
                DECIDING_KINDS.has(card.states.kind) ? 'default' : 'outline'
              }
            >
              {EVENT_KIND_LABEL[card.states.kind] ?? card.states.kind}
            </Badge>
          )}
          {card && <StatusBadge status={card.status} />}
        </div>
        <h3
          id={titleId}
          className='text-lg font-semibold break-words break-keep'
        >
          {card ? (
            <bdi dir='auto'>{card.label}</bdi>
          ) : ownerSource ? (
            <q dir='auto'>{ownerSource.text}</q>
          ) : (
            '읽을 수 없는 발언'
          )}
        </h3>
        {card ? (
          <CardStates card={card} />
        ) : (
          <p className='text-sm text-muted-foreground'>
            이 발언에서 누가 말했는지, 누구를 언급했는지 확인합니다.
          </p>
        )}
      </header>

      {item.sources.length > 0 && (
        <section aria-label='근거 원문' className='grid gap-2'>
          <ul className='grid gap-2'>
            {item.sources.map((source) => (
              <SourceQuote
                key={source.span.id}
                projectId={projectId}
                source={source}
                title={documentTitles.get(source.documentId)}
              />
            ))}
          </ul>
        </section>
      )}

      {item.edges.length > 0 && (
        <section aria-label='연결' className='grid gap-1'>
          <ul className='grid gap-1.5'>
            {item.edges.map((edge) => (
              <EdgeRow
                key={edge.id}
                edge={edge}
                ownerId={item.owner.id}
                related={related}
                sources={sources}
                documentTitles={documentTitles}
                choices={verdicts.length > 0 ? edgeChoices(edge) : []}
                followLabel={card ? '카드를 따름' : '버튼대로'}
                choice={choices[edge.id] ?? 'FOLLOW'}
                onChoose={(choice) =>
                  setChoices((prev) => ({ ...prev, [edge.id]: choice }))
                }
              />
            ))}
          </ul>
        </section>
      )}

      {values && card && (
        <CorrectionForm
          idPrefix={item.id}
          card={card}
          values={values}
          onChange={setValues}
          busy={act.isPending}
          canConfirm={PENDING.has(card.status)}
          onSubmit={(verdict) =>
            act.mutate({ verdict, corrections: changedFields(card, values) })
          }
          onCancel={() => setValues(null)}
        />
      )}

      {(verdicts.length > 0 || editable) && !values && (
        <footer className='flex flex-wrap items-center gap-2'>
          {verdicts.map((verdict) => (
            <Button
              key={verdict}
              size='sm'
              variant={
                verdict === 'CONFIRM'
                  ? 'default'
                  : verdict === 'HOLD'
                    ? 'secondary'
                    : 'outline'
              }
              disabled={act.isPending}
              onClick={() => decide(verdict)}
            >
              {act.isPending && act.variables?.verdict === verdict && (
                <Loader2 className='animate-spin' />
              )}
              {verdict === 'CONFIRM' && card?.status === 'CONFIRMED'
                ? '새 줄 확인'
                : VERDICT_LABEL[verdict]}
            </Button>
          ))}
          {picked && (
            <Button
              size='sm'
              variant='outline'
              disabled={act.isPending}
              onClick={() => act.mutate({ verdict: null, corrections: {} })}
            >
              고른 줄만 반영
            </Button>
          )}
          {editable && (
            <Button
              size='sm'
              variant='ghost'
              disabled={act.isPending}
              onClick={() => setValues(currentValues(card))}
            >
              <Pencil />
              고치기
            </Button>
          )}
        </footer>
      )}

      <ConfirmDialog
        open={confirming !== null}
        onOpenChange={(open) => !open && setConfirming(null)}
        title={
          confirming === 'RETRACT'
            ? '확인을 철회할까요?'
            : '이 묶음을 거절할까요?'
        }
        desc='거절·철회는 되돌릴 수 없습니다. 같은 내용이 다시 필요하면 새 후보로 들어와야 합니다. 따로 고르지 않은 줄도 함께 처리됩니다.'
        cancelBtnText='그만두기'
        confirmText={confirming ? VERDICT_LABEL[confirming] : ''}
        destructive
        isLoading={act.isPending}
        handleConfirm={() =>
          confirming && act.mutate({ verdict: confirming, corrections: {} })
        }
      />
    </article>
  )
}

function StatusBadge({ status }: { status: ReviewStatus }) {
  const { label, tone } = STATUS[status]
  return (
    <Badge
      variant={
        tone === 'confirmed'
          ? 'default'
          : tone === 'held'
            ? 'secondary'
            : 'outline'
      }
      className={cn(
        tone === 'candidate' && 'border-dashed',
        tone === 'closed' && 'text-muted-foreground'
      )}
    >
      {label}
    </Badge>
  )
}

function CardStates({ card }: { card: NodeView }) {
  const shown = (SUMMARY[card.ref.type] ?? []).flatMap((name) => {
    const value = card.states[name]
    if (name in DATE_STATES) {
      if (value) return [`${DATE_STATES[name]} ${formatDay(value)}`]
      // 기한이 없으면 지어내지 않고 "미정"으로 둔다
      return name === 'dueAt' ? ['기한 미정'] : []
    }
    // 사건은 대체·철회됐을 때만 적는다. 후보에 "유효"라고 쓰면 확정된 것처럼 읽힌다
    if (name === 'standing' && value === 'ACTIVE') return []
    return value ? [stateLabel(name, value)] : []
  })
  if (shown.length === 0) return null
  return <p className='text-sm text-muted-foreground'>{shown.join(' · ')}</p>
}

function SourceQuote({
  projectId,
  source,
  title,
}: {
  projectId: Uuid
  source: Source
  title: string | undefined
}) {
  return (
    <li className='rounded-md border-s-4 border-evidence bg-evidence/25 px-3 py-2'>
      <p
        dir='auto'
        className='text-sm break-words break-keep whitespace-pre-wrap'
      >
        {source.text}
      </p>
      <p className='mt-1 flex flex-wrap items-center gap-x-2 text-xs text-muted-foreground'>
        {source.speaker && <bdi className='font-medium'>{source.speaker}</bdi>}
        <span>
          <bdi>{title ?? '문서'}</bdi> · {source.seq}번 발언
        </span>
        <Link
          to='/p/$projectId/documents/$documentId'
          params={{ projectId, documentId: source.documentId }}
          search={{ span: source.span.id }}
          className='font-medium whitespace-nowrap text-primary hover:underline'
        >
          원문에서 보기
        </Link>
      </p>
    </li>
  )
}

function EdgeRow({
  edge,
  ownerId,
  related,
  sources,
  documentTitles,
  choices,
  followLabel,
  choice,
  onChoose,
}: {
  edge: Edge
  ownerId: Uuid
  related: Map<Uuid, NodeView>
  sources: Map<Uuid, Source>
  documentTitles: Map<Uuid, string>
  choices: EdgeChoice[]
  followLabel: string
  choice: EdgeChoice
  onChoose: (choice: EdgeChoice) => void
}) {
  const ownerIsSrc = edge.src.id === ownerId
  const far = ownerIsSrc ? edge.dst : edge.src
  const relation = relationLabel(edge.rel, ownerIsSrc)

  let name: React.ReactNode
  if (far.type === 'SPAN') {
    const source = sources.get(far.id)
    name = source ? (
      <>
        <bdi>{documentTitles.get(source.documentId) ?? '문서'}</bdi>{' '}
        {source.seq}번 발언
      </>
    ) : (
      <span className='text-muted-foreground'>읽을 수 없는 발언</span>
    )
  } else {
    const view = related.get(far.id)
    name = view ? (
      <>
        {TYPE_LABEL[far.type]}{' '}
        <q>
          <bdi dir='auto'>{view.label}</bdi>
        </q>
      </>
    ) : (
      <span
        className='text-muted-foreground'
        title='근거가 내가 읽을 수 없는 문서에만 있습니다.'
      >
        {TYPE_LABEL[far.type]} (볼 수 없는 카드)
      </span>
    )
  }

  return (
    <li className='flex flex-wrap items-center gap-x-2 gap-y-1 text-sm'>
      <span className='text-muted-foreground'>{relation}</span>
      <span aria-hidden className='text-muted-foreground'>
        →
      </span>
      <span className='min-w-0 break-words'>{name}</span>
      <StatusBadge status={edge.status} />
      {choices.length > 1 && (
        <Select
          value={choice}
          onValueChange={(value) => onChoose(value as EdgeChoice)}
        >
          <SelectTrigger
            size='sm'
            aria-label={`${relation} 줄 판단`}
            className='ms-auto w-auto'
          >
            <SelectValue />
          </SelectTrigger>
          <SelectContent>
            {choices.map((c) => (
              <SelectItem key={c} value={c}>
                {c === 'FOLLOW' ? followLabel : `이 줄만 ${VERDICT_LABEL[c]}`}
              </SelectItem>
            ))}
          </SelectContent>
        </Select>
      )}
    </li>
  )
}

function CorrectionForm({
  idPrefix,
  card,
  values,
  onChange,
  busy,
  canConfirm,
  onSubmit,
  onCancel,
}: {
  idPrefix: string
  card: NodeView
  values: FieldValues
  onChange: (values: FieldValues) => void
  busy: boolean
  canConfirm: boolean
  onSubmit: (verdict: Verdict | null) => void
  onCancel: () => void
}) {
  const missing = missingFields(card, values)
  const changed = Object.keys(changedFields(card, values)).length > 0
  const set = (name: string, value: string) =>
    onChange({ ...values, [name]: value })

  return (
    <form
      aria-label='카드 고치기'
      className='grid gap-3 rounded-md border bg-muted/30 p-3'
      onSubmit={(e) => {
        e.preventDefault()
        if (missing.length === 0) onSubmit(canConfirm ? 'CONFIRM' : null)
      }}
    >
      {fieldsOf(card.ref.type).map((spec) => (
        <FieldInput
          key={spec.name}
          id={`${idPrefix}-${spec.name}`}
          spec={spec}
          value={values[spec.name] ?? ''}
          onChange={(value) => set(spec.name, value)}
        />
      ))}
      {missing.length > 0 && (
        <p className='text-sm text-destructive'>
          비울 수 없는 칸: {missing.join(', ')}
        </p>
      )}
      <div className='flex flex-wrap gap-2'>
        {canConfirm && (
          <Button type='submit' size='sm' disabled={busy || missing.length > 0}>
            {busy && <Loader2 className='animate-spin' />}
            고쳐서 확인
          </Button>
        )}
        <Button
          type='button'
          size='sm'
          variant={canConfirm ? 'outline' : 'default'}
          disabled={busy || missing.length > 0 || !changed}
          onClick={() => onSubmit(null)}
        >
          고친 내용만 저장
        </Button>
        <Button
          type='button'
          size='sm'
          variant='ghost'
          disabled={busy}
          onClick={onCancel}
        >
          취소
        </Button>
      </div>
    </form>
  )
}

function FieldInput({
  id,
  spec,
  value,
  onChange,
}: {
  id: string
  spec: FieldSpec
  value: string
  onChange: (value: string) => void
}) {
  return (
    <div className='grid gap-1.5'>
      <Label htmlFor={id}>{spec.label}</Label>
      {spec.kind === 'textarea' ? (
        <Textarea
          id={id}
          dir='auto'
          rows={2}
          value={value}
          onChange={(e) => onChange(e.target.value)}
        />
      ) : spec.kind === 'select' ? (
        <Select value={value} onValueChange={onChange}>
          <SelectTrigger id={id} className='w-full sm:w-60'>
            <SelectValue />
          </SelectTrigger>
          <SelectContent>
            {spec.options?.map((option) => (
              <SelectItem key={option.value} value={option.value}>
                {option.label}
              </SelectItem>
            ))}
          </SelectContent>
        </Select>
      ) : (
        <Input
          id={id}
          type={spec.kind === 'date' ? 'date' : 'text'}
          dir={spec.kind === 'date' ? undefined : 'auto'}
          className={spec.kind === 'date' ? 'w-full sm:w-60' : undefined}
          value={value}
          onChange={(e) => onChange(e.target.value)}
        />
      )}
    </div>
  )
}
