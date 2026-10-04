import { render, screen, waitFor } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { z } from 'zod';
import { Form, FormField, useZodForm } from '../Form';
import { inputClassName } from '../Input';
import { Button } from '../Button';

const schema = z.object({
  justification: z.string().min(10, 'Informe ao menos 10 caracteres'),
});

function Harness({ onSubmit }: { onSubmit: (v: z.infer<typeof schema>) => void }) {
  const form = useZodForm(schema, { defaultValues: { justification: '' } });
  return (
    <Form form={form} onSubmit={onSubmit}>
      <FormField name="justification" label="Justificativa" required>
        {({ field, ...aria }) => <input {...field} {...aria} className={inputClassName} />}
      </FormField>
      <Button type="submit">Enviar</Button>
    </Form>
  );
}

describe('Form', () => {
  it('valida com zod e associa erro ao campo', async () => {
    const onSubmit = vi.fn();
    render(<Harness onSubmit={onSubmit} />);
    await userEvent.click(screen.getByRole('button', { name: 'Enviar' }));
    const input = screen.getByLabelText(/Justificativa/);
    await waitFor(() => expect(input).toHaveAttribute('aria-invalid', 'true'));
    expect(screen.getByRole('alert')).toHaveTextContent('Informe ao menos 10 caracteres');
    expect(onSubmit).not.toHaveBeenCalled();

    await userEvent.type(input, 'Paciente confirmou identidade presencialmente');
    await userEvent.click(screen.getByRole('button', { name: 'Enviar' }));
    await waitFor(() => expect(onSubmit).toHaveBeenCalledTimes(1));
  });
});
