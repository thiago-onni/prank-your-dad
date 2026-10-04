import { render, screen } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { axe } from 'vitest-axe';
import { Dialog, DialogContent, DialogTrigger } from '../Dialog';
import { Button } from '../Button';

describe('Dialog', () => {
  it('abre, prende o foco e fecha com Esc', async () => {
    render(
      <Dialog>
        <DialogTrigger asChild>
          <Button>Abrir</Button>
        </DialogTrigger>
        <DialogContent title="Confirmar fusão" description="Esta ação é auditada.">
          <p>Conteúdo</p>
        </DialogContent>
      </Dialog>,
    );
    await userEvent.click(screen.getByRole('button', { name: 'Abrir' }));
    const dialog = await screen.findByRole('dialog', { name: 'Confirmar fusão' });
    expect(dialog).toHaveAccessibleDescription('Esta ação é auditada.');
    expect(await axe(document.body)).toHaveNoViolations();
    await userEvent.keyboard('{Escape}');
    expect(screen.queryByRole('dialog')).not.toBeInTheDocument();
  });
});
