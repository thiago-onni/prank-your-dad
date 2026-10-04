import type { Meta, StoryObj } from '@storybook/react';
import { Button } from '../components/Button';

const meta: Meta<typeof Button> = {
  title: 'Design System/Button',
  component: Button,
  args: { children: 'Ação primária' },
  argTypes: {
    variant: {
      control: 'select',
      options: ['primary', 'secondary', 'tertiary', 'danger', 'ghost'],
    },
    size: { control: 'select', options: ['sm', 'md', 'lg', 'icon'] },
  },
};
export default meta;

type Story = StoryObj<typeof Button>;

export const Primary: Story = {};
export const Secondary: Story = { args: { variant: 'secondary' } };
export const Danger: Story = { args: { variant: 'danger', children: 'Rejeitar' } };
export const Loading: Story = { args: { loading: true, children: 'Enviando' } };
