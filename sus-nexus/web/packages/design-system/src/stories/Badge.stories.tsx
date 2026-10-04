import type { Meta, StoryObj } from '@storybook/react';
import { Badge } from '../components/Badge';

const meta: Meta<typeof Badge> = {
  title: 'Design System/Badge',
  component: Badge,
  args: { children: 'Validado', tone: 'success' },
};
export default meta;

export const Tones: StoryObj<typeof Badge> = {
  render: () => (
    <div className="flex gap-2">
      <Badge tone="neutral">Neutro</Badge>
      <Badge tone="primary">Primário</Badge>
      <Badge tone="success">Sucesso</Badge>
      <Badge tone="warning">Atenção</Badge>
      <Badge tone="danger">Erro</Badge>
      <Badge tone="info">Info</Badge>
    </div>
  ),
};
