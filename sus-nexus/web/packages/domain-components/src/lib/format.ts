const dateTimeFormatter = new Intl.DateTimeFormat('pt-BR', {
  dateStyle: 'short',
  timeStyle: 'short',
  timeZone: 'America/Sao_Paulo',
});
const dateFormatter = new Intl.DateTimeFormat('pt-BR', {
  dateStyle: 'short',
  timeZone: 'America/Sao_Paulo',
});

export function formatDateTime(iso: string | undefined | null): string {
  if (!iso) return '—';
  const d = new Date(iso);
  return Number.isNaN(d.getTime()) ? '—' : dateTimeFormatter.format(d);
}

export function formatDate(iso: string | undefined | null): string {
  if (!iso) return '—';
  // Datas sem hora (YYYY-MM-DD) são interpretadas como locais para evitar deslocamento de fuso.
  const d = /^\d{4}-\d{2}-\d{2}$/.test(iso) ? new Date(`${iso}T12:00:00`) : new Date(iso);
  return Number.isNaN(d.getTime()) ? '—' : dateFormatter.format(d);
}

export function calculateAge(
  birthdate: string | undefined,
  now: Date = new Date(),
): number | undefined {
  if (!birthdate) return undefined;
  const b = new Date(`${birthdate.slice(0, 10)}T12:00:00`);
  if (Number.isNaN(b.getTime())) return undefined;
  let age = now.getFullYear() - b.getFullYear();
  const m = now.getMonth() - b.getMonth();
  if (m < 0 || (m === 0 && now.getDate() < b.getDate())) age -= 1;
  return age;
}

export function formatAge(birthdate: string | undefined, now?: Date): string {
  const age = calculateAge(birthdate, now);
  return age === undefined ? '—' : `${age} ${age === 1 ? 'ano' : 'anos'}`;
}

/** Formata duração relativa (ex.: "2 h 15 min", "3 dias"). */
export function formatDuration(ms: number): string {
  const abs = Math.abs(ms);
  const minutes = Math.floor(abs / 60_000);
  const hours = Math.floor(minutes / 60);
  const days = Math.floor(hours / 24);
  if (days >= 1) return `${days} ${days === 1 ? 'dia' : 'dias'}`;
  if (hours >= 1) return `${hours} h ${minutes % 60} min`;
  return `${minutes} min`;
}

/**
 * Máscara defensiva para CPF: mantém apenas os 2 últimos dígitos (`***.***.***-12`).
 * Usada apenas como fallback — o backend já entrega `value_masked`.
 */
export function maskCpf(value: string): string {
  const digits = value.replace(/\D/g, '');
  return `***.***.***-${digits.slice(-2).padStart(2, '*')}`;
}

/** Máscara defensiva para CNS: mantém os 4 últimos dígitos. */
export function maskCns(value: string): string {
  const digits = value.replace(/\D/g, '');
  return `*** **** **** ${digits.slice(-4).padStart(4, '*')}`;
}

/** Formata CPF em claro (após reveal explícito). */
export function formatCpf(value: string): string {
  const d = value.replace(/\D/g, '').slice(0, 11);
  if (d.length !== 11) return value;
  return `${d.slice(0, 3)}.${d.slice(3, 6)}.${d.slice(6, 9)}-${d.slice(9)}`;
}

/** Formata CNS em claro (após reveal explícito). */
export function formatCns(value: string): string {
  const d = value.replace(/\D/g, '').slice(0, 15);
  if (d.length !== 15) return value;
  return `${d.slice(0, 3)} ${d.slice(3, 7)} ${d.slice(7, 11)} ${d.slice(11)}`;
}

/** Mascara valores de string em objetos JSON por chave (FHIRResourceViewer). */
export const SENSITIVE_KEYS = new Set([
  'cpf',
  'cns',
  'value',
  'identifier',
  'phone',
  'telecom',
  'email',
  'address',
  'line',
  'postalCode',
  'birthDate',
  'name',
  'given',
  'family',
  'text',
  'mother',
]);
