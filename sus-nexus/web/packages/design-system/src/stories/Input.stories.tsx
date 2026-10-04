import type { Meta, StoryObj } from '@storybook/react';
import { Input } from '../components/Input';

const meta: Meta<typeof Input> = {
  title: 'Design System/Input',
  component: Input,
  args: { label: 'Nome', placeholder: 'Digite o nome' },
};
export default meta;

export const Default: StoryObj<typeof Input> = {};
export const WithError: StoryObj<typeof Input> = {
  args: { error: 'Campo obrigatório', required: true },
};
