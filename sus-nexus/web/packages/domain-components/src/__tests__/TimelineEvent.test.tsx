import { render, screen } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { axe } from 'vitest-axe';
import type { TimelineEvent as TimelineEventData } from '@sus-nexus/api-client';
import { TimelineEvent } from '../components/TimelineEvent';

const cause: TimelineEventData = {
  id: 'evt_1',
  citizen_id: 'cit_1',
  domain: 'aps',
  event_type: 'sus.aps.encounter.recorded',
  occurred_at: '2026-09-01T10:00:00-03:00',
  recorded_at: '2026-09-01T10:30:00-03:00',
  source_system: 'PEC',
  status: 'finished',
  summary: 'Consulta na UBS',
};

const effect: TimelineEventData = {
  id: 'evt_2',
  citizen_id: 'cit_1',
  domain: 'regulation',
  event_type: 'sus.regulation.request.created',
  occurred_at: '2026-09-01T11:00:00-03:00',
  recorded_at: '2026-09-05T11:00:00-03:00',
  source_system: 'SISREG',
  cnes: '1234567',
  health_unit_name: 'UBS Centro',
  status: 'pending',
  confidence: 'divergent',
  summary: 'Pedido de cardiologia',
  correlation_chain: ['evt_1'],
};

describe('TimelineEvent', () => {
  it('mostra datas, unidade, confiança e expande a cadeia causa-efeito', async () => {
    const { container } = render(<TimelineEvent event={effect} relatedEvents={[cause]} />);
    expect(screen.getByRole('heading', { name: 'Pedido de cardiologia' })).toBeInTheDocument();
    expect(screen.getByText('Divergente')).toBeInTheDocument();
    expect(screen.getByText(/registro tardio/)).toBeInTheDocument();
    expect(screen.getByText('UBS Centro')).toBeInTheDocument();
    const toggle = screen.getByRole('button', { name: /Cadeia causa-efeito/ });
    expect(toggle).toHaveAttribute('aria-expanded', 'false');
    await userEvent.click(toggle);
    expect(toggle).toHaveAttribute('aria-expanded', 'true');
    expect(screen.getByText('Consulta na UBS')).toBeVisible();
    expect(await axe(container)).toHaveNoViolations();
  });
});
