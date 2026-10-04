import { render, screen, waitFor } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { axe } from 'vitest-axe';
import { HumanApprovalPanel } from '../components/HumanApprovalPanel';

describe('HumanApprovalPanel', () => {
  it('exige justificativa mínima antes de aprovar/rejeitar', async () => {
    const onApprove = vi.fn();
    const onReject = vi.fn();
    const { container } = render(
      <HumanApprovalPanel
        title="Fundir cadastros"
        onApprove={onApprove}
        onReject={onReject}
        approveLabel="Fundir"
      />,
    );
    await userEvent.click(screen.getByRole('button', { name: 'Fundir' }));
    expect(screen.getByRole('alert')).toHaveTextContent('ao menos 10 caracteres');
    expect(onApprove).not.toHaveBeenCalled();

    await userEvent.type(
      screen.getByLabelText(/Justificativa/),
      'Documentos conferidos presencialmente',
    );
    await userEvent.click(screen.getByRole('button', { name: 'Fundir' }));
    await waitFor(() =>
      expect(onApprove).toHaveBeenCalledWith('Documentos conferidos presencialmente'),
    );
    expect(onReject).not.toHaveBeenCalled();
    expect(await axe(container)).toHaveNoViolations();
  });

  it('rejeita com justificativa', async () => {
    const onReject = vi.fn();
    render(<HumanApprovalPanel title="Caso" onApprove={() => {}} onReject={onReject} />);
    await userEvent.type(screen.getByLabelText(/Justificativa/), 'Datas de nascimento diferentes');
    await userEvent.click(screen.getByRole('button', { name: 'Rejeitar' }));
    await waitFor(() => expect(onReject).toHaveBeenCalledWith('Datas de nascimento diferentes'));
  });
});
