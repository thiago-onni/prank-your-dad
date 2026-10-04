import { expect, test } from '@playwright/test';

test('home carrega com navegação acessível', async ({ page }) => {
  await page.goto('/');
  await expect(page.getByRole('navigation', { name: 'Navegação principal' })).toBeVisible();
  await expect(page.getByRole('heading', { level: 1 })).toBeVisible();
});

test('console de integrações lista conectores', async ({ page }) => {
  await page.goto('/integracoes');
  await expect(page.getByRole('heading', { name: 'Console de Integrações' })).toBeVisible();
  await expect(page.getByText('e-SUS PEC')).toBeVisible();
});
