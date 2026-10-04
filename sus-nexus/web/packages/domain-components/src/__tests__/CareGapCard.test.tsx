import { render, screen } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { axe } from 'vitest-axe';
import type { CareGap } from '@sus-nexus/api-client';
import { CareGapCard } from '../components/CareGapCard';

const gap: CareGap = {
  id: 'gap_1',
  citizen_id: 'cit_1',
  citizen_display_name: 'Ana Clara Souza',
  care_line: 'hipertensao',
  gap_kind: 'exam_overdue',
  status: 'open',
  detected_at: '2026-09-01T10:00:00Z',
  expected_by: '2026-08-20T10:00:00Z',
  days_overdue: 45,
  protocol_id: 'prot_has',
  protocol_version: '1.4.0',
  microarea: '02',
  contact_valid: false,
};

describe('CareGapCard', () => {
  it('mostra atraso, linha, protocolo, microárea e contato inválido', async () => {
    const onAct = vi.fn();
    const { container } = render(
      <CareGapCard gap={gap} showCitizen protocolName="Hipertensão (APS)" onAct={onAct} />,
    );
    expect(
      screen.getByRole('heading', { name: 'Exame em atraso — Ana Clara Souza' }),
    ).toBeInTheDocument();
    expect(screen.getByText('45 dias em atraso')).toBeInTheDocument();
    expect(screen.getByText('Hipertensão')).toBeInTheDocument();
    expect(screen.getByText(/Contato inválido/)).toBeInTheDocument();
    await userEvent.click(screen.getByRole('button', { name: 'Registrar desfecho' }));
    expect(onAct).toHaveBeenCalledWith(gap);
    expect(await axe(container)).toHaveNoViolations();
  });

  it('lacuna resolvida não oferece ação', () => {
    render(
      <CareGapCard gap={{ ...gap, status: 'resolved', resolution: 'performed' }} onAct={vi.fn()} />,
    );
    expect(screen.getByText('Resolvida')).toBeInTheDocument();
    expect(screen.getByText('Realizado')).toBeInTheDocument();
    expect(screen.queryByRole('button')).not.toBeInTheDocument();
  });
});
