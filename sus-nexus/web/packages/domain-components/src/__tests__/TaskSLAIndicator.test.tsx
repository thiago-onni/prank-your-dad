import { render, screen } from '@testing-library/react';
import { computeSlaState, TaskSLAIndicator } from '../components/TaskSLAIndicator';

const now = new Date('2026-10-04T12:00:00-03:00');

describe('TaskSLAIndicator', () => {
  it('calcula estados do SLA', () => {
    expect(
      computeSlaState({ status: 'open', due_at: '2026-10-05T12:00:00-03:00' }, now, 4 * 3600e3)
        .state,
    ).toBe('ok');
    expect(
      computeSlaState({ status: 'open', due_at: '2026-10-04T13:00:00-03:00' }, now, 4 * 3600e3)
        .state,
    ).toBe('warning');
    expect(
      computeSlaState({ status: 'open', due_at: '2026-10-04T10:00:00-03:00' }, now, 4 * 3600e3)
        .state,
    ).toBe('breached');
    expect(
      computeSlaState({ status: 'escalated', due_at: '2026-10-04T10:00:00-03:00' }, now, 4 * 3600e3)
        .state,
    ).toBe('escalated');
    expect(computeSlaState({ status: 'completed' }, now, 4 * 3600e3).state).toBe('closed');
    expect(computeSlaState({ status: 'open' }, now, 4 * 3600e3).state).toBe('none');
  });

  it('renderiza tempo estourado como status', () => {
    render(
      <TaskSLAIndicator
        task={{ status: 'open', due_at: '2026-10-03T12:00:00-03:00', overdue: true }}
        now={now}
      />,
    );
    expect(screen.getByRole('status')).toHaveTextContent('SLA estourado há 1 dia');
  });
});
