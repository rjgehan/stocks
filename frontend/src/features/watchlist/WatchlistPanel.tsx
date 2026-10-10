import { useMemo, useState } from 'react'
import { usePositions } from '@/lib/api/hooks/account'
import { describeError, errorBody } from '@/lib/api/errors'
import {
  useAddToWatchlist,
  useCreateWatchlist,
  useDeleteWatchlist,
  useRemoveFromWatchlist,
  useUpdateWatchlist,
  useWatchlists,
} from '@/lib/api/hooks/watchlists'
import { useQuotesSeed } from '@/lib/realtime/useQuote'
import { readLocal, writeLocal } from '@/lib/prefs/local'
import { Button, IconButton } from '@/components/ui/Button'
import { ConfirmDialog } from '@/components/ui/ConfirmDialog'
import { Dialog } from '@/components/ui/Dialog'
import { EmptyState } from '@/components/ui/EmptyState'
import { TextField } from '@/components/ui/Field'
import { MenuItem, Popover } from '@/components/ui/Popover'
import { SkeletonRows } from '@/components/ui/Skeleton'
import { IconEdit, IconMore, IconPlus, IconStar, IconTrash } from '@/components/ui/icons'
import { cx } from '@/components/ui/cx'
import { IndexStrip } from '@/features/market/IndexStrip'
import { SymbolSearch } from '@/features/market/SymbolSearch'
import { toast } from '@/features/notifications/toastStore'
import { type PillMode, WatchlistRow } from './WatchlistRow'
import { type ColumnId, type WatchlistSort, readColumns, readSort, writeColumns, writeSort } from './columns'
import { ColumnChooser, WatchlistTable } from './WatchlistTable'

export const POPULAR = ['AAPL', 'NVDA', 'MSFT', 'TSLA', 'AMZN'] as const
const HOLDINGS = '__holdings__'

function move<T>(list: readonly T[], from: number, to: number): T[] {
  const next = [...list]
  const [item] = next.splice(from, 1)
  if (item !== undefined) next.splice(to, 0, item)
  return next
}

const fail = (title: string) => (e: unknown) => {
  const d = describeError(e)
  toast({ tone: 'error', title, body: errorBody(d) ?? d.title })
}

/**
 * The watchlist (§22.4): list tabs ("My List", an automatic "Holdings" list, custom lists), the index strip,
 * streaming rows, add / remove / reorder (drag or ↑ ↓), list create / rename / delete, and the "Add 5 popular stocks"
 * empty state. Used as the desktop left panel, the tablet drawer and the phone Watchlist tab.
 */
export function WatchlistPanel({ activeSymbol, variant = 'panel' }: { activeSymbol?: string; variant?: 'panel' | 'screen' }) {
  const lists = useWatchlists()
  const positions = usePositions()
  const [selected, setSelected] = useState(() => readLocal('stocks.watchlist.tab', ''))
  const [pillMode, setPillMode] = useState<PillMode>(() => (readLocal('stocks.watchlist.pill', 'pct') === 'change' ? 'change' : 'pct'))
  const [editing, setEditing] = useState(false)
  const [adding, setAdding] = useState(false)
  const [dragSymbol, setDragSymbol] = useState<string | null>(null)
  const [dragOrder, setDragOrder] = useState<string[] | null>(null)
  const [nameDialog, setNameDialog] = useState<null | { mode: 'create' | 'rename'; name: string }>(null)
  const [confirmDelete, setConfirmDelete] = useState(false)
  // §22.10 desktop columns and sorting (the panel variant is the desktop left column)
  const table = variant === 'panel'
  const [columns, setColumns] = useState<ColumnId[]>(() => readColumns())
  const [sort, setSort] = useState<WatchlistSort | null>(() => readSort())
  const addSymbol = useAddToWatchlist()
  const removeSymbol = useRemoveFromWatchlist()
  const update = useUpdateWatchlist()
  const create = useCreateWatchlist()
  const remove = useDeleteWatchlist()

  const tabs = useMemo(() => {
    const l = [...(lists.data ?? [])].sort((a, b) => a.sortOrder - b.sortOrder).filter((w): w is typeof w & { id: string } => Boolean(w.id))
    const [first, ...rest] = l
    return [
      ...(first ? [{ id: first.id, name: first.name ?? 'My List' }] : []),
      { id: HOLDINGS, name: 'Holdings' },
      ...rest.map((w) => ({ id: w.id, name: w.name ?? 'List' })),
    ]
  }, [lists.data])

  const currentId = tabs.some((t) => t.id === selected) ? selected : (tabs[0]?.id ?? '')
  const current = lists.data?.find((w) => w.id === currentId)
  const isHoldings = currentId === HOLDINGS
  const symbols = useMemo(() => {
    if (isHoldings) return (positions.data ?? []).map((p) => p.symbol).filter((s): s is string => Boolean(s))
    return current?.symbols ?? []
  }, [isHoldings, current, positions.data])

  useQuotesSeed(symbols)

  const cyclePill = () => {
    const next: PillMode = pillMode === 'pct' ? 'change' : 'pct'
    setPillMode(next)
    writeLocal('stocks.watchlist.pill', next)
  }
  const select = (id: string) => {
    setSelected(id)
    setEditing(false)
    setAdding(false)
    writeLocal('stocks.watchlist.tab', id)
  }
  const reorder = (from: number, to: number) => {
    if (!current?.id || to < 0 || to >= symbols.length || from === to) return
    update.mutate({ id: current.id, name: current.name ?? 'My List', symbols: move(symbols, from, to) }, { onError: fail('Could not reorder the list') })
  }
  const addPopular = async () => {
    if (!current?.id) return
    try {
      for (const symbol of POPULAR) if (!symbols.includes(symbol)) await addSymbol.mutateAsync({ id: current.id, symbol })
    } catch (e) {
      fail('Could not add the symbols')(e)
    }
  }
  const saveName = () => {
    const name = nameDialog?.name.trim()
    if (!nameDialog || !name) return
    if (nameDialog.mode === 'create') {
      create.mutate(
        { name },
        {
          onSuccess: (list) => {
            if (list.id) select(list.id)
            setNameDialog(null)
          },
          onError: fail('Could not create the list'),
        },
      )
    } else if (current?.id) {
      update.mutate({ id: current.id, name, symbols }, { onSuccess: () => setNameDialog(null), onError: fail('Could not rename the list') })
    }
  }

  const loading = lists.isPending || (isHoldings && positions.isPending)
  const canEdit = !isHoldings && Boolean(current)
  const isFirstList = current?.id === tabs[0]?.id
  const touch = variant === 'screen'
  return (
    <section aria-label="Watchlist" className="flex h-full min-h-0 flex-col bg-bg-1">
      <div className={cx('flex shrink-0 items-center justify-between gap-1 pr-1.5 pl-3', variant === 'panel' ? 'h-11 pr-10' : 'h-12')}>
        {variant === 'panel' ? <h2 className="text-section font-semibold">Watchlist</h2> : <span className="text-caption font-medium text-text-3">{current?.name ?? (isHoldings ? 'Holdings' : '')}</span>}
        <div className="flex items-center">
          {table ? (
            <ColumnChooser
              columns={columns}
              onChange={(c) => {
                setColumns(c)
                writeColumns(c)
              }}
            />
          ) : null}
          {canEdit ? (
            <IconButton label={adding ? 'Close add symbol' : 'Add symbol'} size={touch ? 'touch' : 'sm'} active={adding} onClick={() => setAdding((a) => !a)}>
              <IconPlus size={18} />
            </IconButton>
          ) : null}
          <Popover
            label="List actions"
            renderTrigger={(props) => (
              <IconButton label="List actions" size={touch ? 'touch' : 'sm'} {...props}>
                <IconMore size={18} />
              </IconButton>
            )}
          >
            {(close) => (
              <>
                <MenuItem onClick={() => (close(), setNameDialog({ mode: 'create', name: '' }))}>
                  <IconPlus size={16} /> New list
                </MenuItem>
                <MenuItem disabled={!canEdit || symbols.length === 0} onClick={() => (close(), setEditing(true), setAdding(false))}>
                  <IconEdit size={16} /> Edit symbols
                </MenuItem>
                <MenuItem disabled={!canEdit} onClick={() => (close(), setNameDialog({ mode: 'rename', name: current?.name ?? '' }))}>
                  <IconEdit size={16} /> Rename list
                </MenuItem>
                <MenuItem tone="danger" disabled={!canEdit || isFirstList} onClick={() => (close(), setConfirmDelete(true))}>
                  <IconTrash size={16} /> Delete list
                </MenuItem>
              </>
            )}
          </Popover>
        </div>
      </div>
      <div className="shrink-0 border-y border-divider">
        <IndexStrip />
      </div>
      <div role="tablist" aria-label="Lists" className="scrollbar-none flex shrink-0 gap-1 overflow-x-auto border-b border-divider px-2 py-1.5">
        {tabs.map((t) => (
          <button
            key={t.id}
            role="tab"
            type="button"
            aria-selected={t.id === currentId}
            onClick={() => select(t.id)}
            className={cx(
              'h-11 shrink-0 rounded-sm px-2.5 text-caption font-semibold transition-colors tablet:h-8',
              t.id === currentId ? 'bg-bg-3 text-text-1' : 'text-text-2 hover:bg-bg-2 hover:text-text-1',
            )}
          >
            {t.name}
          </button>
        ))}
      </div>
      {adding && current?.id ? (
        <div className="shrink-0 border-b border-divider p-2">
          <SymbolSearch
            variant="inline"
            focusOnMount
            placeholder="Add a symbol"
            exclude={symbols}
            onSelect={(symbol) =>
              addSymbol.mutate(
                { id: current.id!, symbol },
                { onSuccess: () => toast({ tone: 'neutral', title: `Added ${symbol} to ${current.name ?? 'your list'}` }), onError: fail(`Could not add ${symbol}`) },
              )
            }
          />
        </div>
      ) : null}
      {editing ? (
        <div className="flex h-10 shrink-0 items-center justify-between bg-accent-bg px-3 text-caption">
          <span className="font-medium text-text-1">Drag or use the arrows to reorder</span>
          <Button size="sm" variant="primary" onClick={() => setEditing(false)}>
            Done
          </Button>
        </div>
      ) : table ? null : (
        <div className="flex h-7 shrink-0 items-center justify-between px-3 text-micro font-medium tracking-wide text-text-3 uppercase">
          <span>Symbol</span>
          <span className="flex items-center gap-6">
            <span>Last</span>
            <button type="button" onClick={cyclePill} className="-my-3.5 h-11 w-[76px] text-right uppercase hover:text-text-1 tablet:my-0 tablet:h-auto">
              {pillMode === 'pct' ? 'Chg %' : 'Chg $'}
            </button>
          </span>
        </div>
      )}
      {/* the desktop table scrolls itself (both ways, with a sticky header and symbol column) */}
      <div className={cx('min-h-0 flex-1', table && !editing && symbols.length > 0 && !loading ? 'flex flex-col' : 'scrollbar-thin overflow-y-auto')}>
        {loading ? (
          <div className="px-3">
            <SkeletonRows rows={7} cols={3} />
          </div>
        ) : symbols.length === 0 ? (
          isHoldings ? (
            <EmptyState title="No holdings yet" body="Positions you buy appear here automatically." />
          ) : (
            <EmptyState
              icon={<IconStar size={28} />}
              title="Your list is empty"
              body="Start with a few of the most-watched names. You can remove them any time."
              action={
                <Button variant="primary" size="sm" onClick={() => void addPopular()} disabled={addSymbol.isPending || !current}>
                  Add 5 popular stocks
                </Button>
              }
            />
          )
        ) : table && !editing ? (
          <WatchlistTable
            symbols={symbols}
            activeSymbol={activeSymbol}
            columns={columns}
            sort={sort}
            onSort={(next) => {
              setSort(next)
              writeSort(next)
            }}
            onReorder={
              canEdit && current?.id
                ? (order) => update.mutate({ id: current.id!, name: current.name ?? 'My List', symbols: order }, { onError: fail('Could not reorder the list') })
                : null
            }
          />
        ) : (
          <div className="divide-y divide-divider" onDragOver={(e) => e.preventDefault()}>
            {(dragOrder ?? symbols).map((s, i, shown) => (
              <WatchlistRow
                key={s}
                symbol={s}
                active={s === activeSymbol}
                pillMode={pillMode}
                onCyclePill={cyclePill}
                dense={variant === 'panel'}
                editing={
                  editing && current?.id
                    ? {
                        index: i,
                        count: shown.length,
                        onMove: reorder,
                        onRemove: () => removeSymbol.mutate({ id: current.id!, symbol: s }, { onError: fail(`Could not remove ${s}`) }),
                        dragging: dragSymbol === s,
                        onDragStart: () => {
                          setDragSymbol(s)
                          setDragOrder(symbols)
                        },
                        // reorder locally while dragging; one PUT when the drag ends
                        onDragOver: () => {
                          if (!dragSymbol || dragSymbol === s) return
                          setDragOrder((order) => {
                            const base = order ?? symbols
                            return move(base, base.indexOf(dragSymbol), base.indexOf(s))
                          })
                        },
                        onDragEnd: () => {
                          const order = dragOrder
                          setDragSymbol(null)
                          setDragOrder(null)
                          if (order && order.join() !== symbols.join() && current?.id) {
                            update.mutate({ id: current.id, name: current.name ?? 'My List', symbols: order }, { onError: fail('Could not reorder the list') })
                          }
                        },
                      }
                    : undefined
                }
              />
            ))}
          </div>
        )}
      </div>

      <Dialog open={nameDialog !== null} onClose={() => setNameDialog(null)} title={nameDialog?.mode === 'create' ? 'New watchlist' : 'Rename watchlist'}>
        <form
          className="space-y-3 p-4"
          onSubmit={(e) => {
            e.preventDefault()
            saveName()
          }}
        >
          <TextField
            label="Name"
            value={nameDialog?.name ?? ''}
            maxLength={40}
            onChange={(e) => setNameDialog((d) => (d ? { ...d, name: e.target.value } : d))}
          />
          <Button type="submit" variant="primary" size="lg" block disabled={!nameDialog?.name.trim() || create.isPending || update.isPending}>
            {nameDialog?.mode === 'create' ? 'Create list' : 'Save'}
          </Button>
        </form>
      </Dialog>
      <ConfirmDialog
        open={confirmDelete}
        onClose={() => setConfirmDelete(false)}
        title={`Delete “${current?.name ?? 'list'}”?`}
        confirmLabel="Delete list"
        tone="danger"
        pending={remove.isPending}
        onConfirm={() =>
          current?.id &&
          remove.mutate(current.id, {
            onSuccess: () => {
              setConfirmDelete(false)
              select(tabs[0]?.id ?? '')
            },
            onError: fail('Could not delete the list'),
          })
        }
      >
        The symbols stay in your other lists. This cannot be undone.
      </ConfirmDialog>
    </section>
  )
}
