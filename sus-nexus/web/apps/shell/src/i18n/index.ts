import { ptBR, type Messages } from './pt-BR';

export const messages: Messages = ptBR;

/** Acesso tipado às strings: `t.nav.home`. */
export const t = messages;

/** Interpolação simples: `format('Olá, {name}', { name })`. */
export function format(template: string, params: Record<string, string | number>): string {
  return template.replace(/\{(\w+)\}/g, (_, key: string) => String(params[key] ?? ''));
}
