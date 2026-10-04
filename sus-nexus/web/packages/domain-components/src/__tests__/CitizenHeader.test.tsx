import { render, screen } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { axe } from 'vitest-axe';
import { TooltipProvider } from '@sus-nexus/design-system';
import type { CitizenDetail } from '@sus-nexus/api-client';
import { CitizenHeader } from '../components/CitizenHeader';

const citizen: CitizenDetail = {
  id: 'cit_01J9ZV4N2G6K8Q7R5T3W1X9Y0A',
  version: 3,
  display_name: 'Ana Beatriz',
  legal_name: 'Antônio Carlos da Silva',
  social_name: 'Ana Beatriz',
  birthdate: '1985-03-15',
  sex: 'female',
  mother_name_masked: 'M*** A*** da S***',
  registration_state: 'validated',
  identity_confidence: 'confirmed',
  identifiers: [
    {
      id: 'cid_1',
      system: 'CPF',
      value_masked: '***.***.***-12',
      status: 'active',
      source_system: 'PEC',
    },
    {
      id: 'cid_2',
      system: 'CNS',
      value_masked: '*** **** **** 0001',
      status: 'active',
      source_system: 'CADSUS',
    },
  ],
  health_unit_cnes: '2222222',
  team_ine: '0001234567',
  microarea: '03',
};

describe('CitizenHeader', () => {
  it('prioriza nome social, mostra idade e identificadores mascarados', async () => {
    const onReveal = vi.fn();
    const { container } = render(
      <TooltipProvider>
        <CitizenHeader
          citizen={citizen}
          healthUnitName="UBS Vila Nova"
          onRevealIdentifier={onReveal}
        />
      </TooltipProvider>,
    );
    expect(screen.getByRole('heading', { level: 1 })).toHaveTextContent('Ana Beatriz');
    expect(screen.getByText(/Nome civil/)).toHaveTextContent('Antônio Carlos da Silva');
    expect(screen.getByText('***.***.***-12')).toBeInTheDocument();
    expect(screen.queryByText(/\d{3}\.\d{3}\.\d{3}-\d{2}/)).not.toBeInTheDocument();
    expect(screen.getByText('UBS Vila Nova')).toBeInTheDocument();
    await userEvent.click(screen.getByRole('button', { name: /Revelar CPF/ }));
    expect(onReveal).toHaveBeenCalledWith(expect.objectContaining({ id: 'cid_1', system: 'CPF' }));
    expect(await axe(container)).toHaveNoViolations();
  });

  it('exibe valor revelado quando fornecido', () => {
    render(
      <TooltipProvider>
        <CitizenHeader
          citizen={citizen}
          revealed={{ cid_1: '123.456.789-12' }}
          onRevealIdentifier={() => {}}
        />
      </TooltipProvider>,
    );
    expect(screen.getByText('123.456.789-12')).toBeInTheDocument();
    expect(screen.queryByRole('button', { name: /Revelar CPF/ })).not.toBeInTheDocument();
    expect(screen.getByRole('button', { name: /Revelar CNS/ })).toBeInTheDocument();
  });
});
