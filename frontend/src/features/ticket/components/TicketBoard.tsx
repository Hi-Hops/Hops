import { Link } from 'react-router';
import { TicketStatuses, processing } from '@/features/inquiry';
import { ApiError } from '@/shared/api/client';
import { paths } from '@/shared/config/paths';
import { displayDate } from '@/shared/lib/date';
import { Button } from '@/shared/ui/Button';
import { ErrorState } from '@/shared/ui/ErrorState';
import { LoadingState } from '@/shared/ui/LoadingState';
import { RouteModal } from '@/shared/ui/RouteModal';
import { useTickets } from '../hooks/useTickets';
import { TicketDetail } from './TicketDetail';
import styles from './Tickets.module.css';
export function TicketBoard() {
  const board = useTickets();
  const { data, error } = board.query;
  return (
    <main className={styles.page}>
      <header className={styles.pageHead}>
        <div>
          <h1 id="page-title" tabIndex={-1}>
            {board.user?.roles.includes('BIZ_ADMIN') ? '전체 티켓 현황' : '부서 티켓 현황'}
          </h1>
          <p>
            {board.user?.roles.includes('BIZ_ADMIN')
              ? '업무 관리자 조회 범위'
              : `${board.user?.department.name ?? ''} 부서에 현재 배정된 티켓을 확인하세요.`}
          </p>
        </div>
        <span className={styles.readOnly}>조회 전용</span>
      </header>
      {!board.canRead ? (
        <>
          <ErrorState title="부서 티켓 조회 권한이 없어요" />
          <Link to={paths.home}>IT 문의하기로 돌아가기</Link>
        </>
      ) : (
        <section className={styles.panel} aria-label="부서 티켓 목록">
          <div className={styles.toolbar}>
            <h2>
              티켓 목록 <span>{data?.totalElements ?? '–'}</span>
            </h2>
            <div className={styles.filters}>
              <label htmlFor="processing-filter">처리 상태</label>
              <select
                id="processing-filter"
                value={board.search.get('processingStatus') ?? ''}
                onChange={(event) => {
                  board.change('processingStatus', event.target.value);
                }}
              >
                <option value="">전체 상태</option>
                {Object.entries(processing).map(([value, display]) => (
                  <option value={value} key={value}>
                    {display.label}
                  </option>
                ))}
              </select>
              <Button
                variant="secondary"
                onClick={() => {
                  void board.query.refetch();
                }}
              >
                새로고침
              </Button>
            </div>
          </div>
          {board.query.isPending ? (
            <LoadingState label="티켓 목록을 불러오는 중이에요" />
          ) : error ? (
            <ErrorState
              title={
                error instanceof ApiError && error.status === 403
                  ? '부서 티켓 조회 권한이 없어요'
                  : error instanceof ApiError && error.status === 400
                    ? '조회 조건을 확인해 주세요'
                    : '티켓 목록을 불러오지 못했어요'
              }
              onRetry={() => {
                void board.query.refetch();
              }}
            />
          ) : !data?.items.length ? (
            <div className={styles.empty}>
              <strong>배정된 티켓이 없어요</strong>
              <p>선택한 처리 상태에 해당하는 티켓이 없어요.</p>
            </div>
          ) : (
            <div className={styles.tableWrap}>
              <table>
                <caption>현재 조회 범위의 부서 티켓</caption>
                <thead>
                  <tr>
                    <th scope="col">티켓</th>
                    <th scope="col">처리 부서</th>
                    <th scope="col">처리·배정 상태</th>
                    <th scope="col">생성 시각</th>
                  </tr>
                </thead>
                <tbody>
                  {data.items.map((ticket) => (
                    <tr key={ticket.id}>
                      <td>
                        <Link
                          id={`ticket-${ticket.id}`}
                          to={{
                            pathname: paths.ticket(ticket.id),
                            search: board.search.toString(),
                          }}
                        >
                          <span className={styles.ticketNumber}>{ticket.ticketNumber}</span>
                          <strong>{ticket.title}</strong>
                        </Link>
                      </td>
                      <td>{ticket.department.name}</td>
                      <td>
                        <div className={styles.statuses}>
                          <TicketStatuses ticket={ticket} />
                        </div>
                      </td>
                      <td className={styles.date}>{displayDate(ticket.createdAt)}</td>
                    </tr>
                  ))}
                </tbody>
              </table>
            </div>
          )}
          {data && !error && (
            <nav className={styles.pagination} aria-label="티켓 페이지">
              <span>
                전체 {data.totalElements}건 · {data.page + 1} /{' '}
                {Math.max(1, Math.ceil(data.totalElements / data.size))} 페이지
              </span>
              <div>
                <Button
                  variant="secondary"
                  disabled={data.page === 0}
                  onClick={() => {
                    board.change('page', String(data.page - 1));
                  }}
                >
                  이전
                </Button>
                <Button
                  variant="secondary"
                  disabled={(data.page + 1) * data.size >= data.totalElements}
                  onClick={() => {
                    board.change('page', String(data.page + 1));
                  }}
                >
                  다음
                </Button>
              </div>
            </nav>
          )}
        </section>
      )}
      {board.ticketId && (
        <RouteModal
          title="티켓 상세"
          description="문의 요약과 진단, 처리 부서 및 배정 이력을 확인해요."
          onClose={board.close}
          returnFocusId={`ticket-${board.ticketId}`}
        >
          <TicketDetail key={board.ticketId} id={board.ticketId} onClose={board.close} />
        </RouteModal>
      )}
    </main>
  );
}
