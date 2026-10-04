import type {
  AppointmentStatus,
  ConnectorHealth,
  Domain,
  IdentityConfidence,
  IntegrationMessageStatus,
  MergeCaseStatus,
  Purpose,
  RegistrationState,
  TaskPriority,
  TaskStatus,
  TimelineConfidence,
} from '@sus-nexus/api-client';
import type { BadgeTone } from '@sus-nexus/design-system';

export const purposeLabels: Record<Purpose, string> = {
  care_coordination: 'Coordenação do cuidado',
  regulation: 'Regulação',
  scheduling: 'Agendamento',
  identity_management: 'Gestão de identidade',
  production_audit: 'Auditoria de produção',
  public_health_surveillance: 'Vigilância em saúde',
  management_analytics: 'Gestão e indicadores',
  integration_operations: 'Operação de integrações',
  security_audit: 'Auditoria de segurança',
};

export const domainLabels: Record<Domain, string> = {
  identity: 'Identidade',
  aps: 'Atenção Primária',
  schedule: 'Agenda',
  regulation: 'Regulação',
  exam: 'Exames',
  hospital: 'Hospitalar',
  careplan: 'Plano de cuidado',
  task: 'Tarefas',
  production: 'Produção',
  communication: 'Comunicação',
};

export const registrationStateLabels: Record<
  RegistrationState,
  { label: string; tone: BadgeTone }
> = {
  validated: { label: 'Validado', tone: 'success' },
  divergent: { label: 'Divergente', tone: 'warning' },
  incomplete: { label: 'Incompleto', tone: 'warning' },
  duplicate: { label: 'Duplicidade', tone: 'danger' },
  pending: { label: 'Pendente', tone: 'neutral' },
};

export const identityConfidenceLabels: Record<
  IdentityConfidence,
  { label: string; tone: BadgeTone; description: string }
> = {
  confirmed: {
    label: 'Confirmado',
    tone: 'success',
    description: 'Identidade confirmada por documento ou regra determinística.',
  },
  probable: {
    label: 'Provável',
    tone: 'info',
    description: 'Correspondência probabilística acima do limiar; sem conflito.',
  },
  pending: {
    label: 'Pendente',
    tone: 'warning',
    description: 'Aguardando revisão humana (caso aberto na fila MPI).',
  },
  divergent: {
    label: 'Divergente',
    tone: 'danger',
    description: 'Há conflito em identificador ou data de nascimento.',
  },
};

export const timelineConfidenceLabels: Record<
  TimelineConfidence,
  { label: string; tone: BadgeTone }
> = {
  confirmed: { label: 'Confirmado', tone: 'success' },
  pending: { label: 'Pendente', tone: 'warning' },
  divergent: { label: 'Divergente', tone: 'danger' },
  unsynced: { label: 'Não sincronizado', tone: 'neutral' },
};

export const appointmentStatusLabels: Record<
  AppointmentStatus,
  { label: string; tone: BadgeTone }
> = {
  proposed: { label: 'Proposto', tone: 'neutral' },
  booked: { label: 'Agendado', tone: 'info' },
  confirmed: { label: 'Confirmado', tone: 'primary' },
  arrived: { label: 'Chegou', tone: 'primary' },
  fulfilled: { label: 'Realizado', tone: 'success' },
  cancelled: { label: 'Cancelado', tone: 'neutral' },
  noshow: { label: 'Faltou', tone: 'danger' },
  waitlist: { label: 'Lista de espera', tone: 'warning' },
};

export const taskStatusLabels: Record<TaskStatus, { label: string; tone: BadgeTone }> = {
  open: { label: 'Aberta', tone: 'neutral' },
  assigned: { label: 'Atribuída', tone: 'info' },
  in_progress: { label: 'Em andamento', tone: 'primary' },
  completed: { label: 'Concluída', tone: 'success' },
  cancelled: { label: 'Cancelada', tone: 'neutral' },
  escalated: { label: 'Escalonada', tone: 'danger' },
};

export const taskPriorityLabels: Record<TaskPriority, { label: string; tone: BadgeTone }> = {
  low: { label: 'Baixa', tone: 'neutral' },
  medium: { label: 'Média', tone: 'info' },
  high: { label: 'Alta', tone: 'warning' },
  urgent: { label: 'Urgente', tone: 'danger' },
};

export const connectorHealthLabels: Record<ConnectorHealth, { label: string; tone: BadgeTone }> = {
  healthy: { label: 'Saudável', tone: 'success' },
  degraded: { label: 'Degradado', tone: 'warning' },
  down: { label: 'Parado', tone: 'danger' },
  unknown: { label: 'Desconhecido', tone: 'neutral' },
};

export const integrationMessageStatusLabels: Record<
  IntegrationMessageStatus,
  { label: string; tone: BadgeTone }
> = {
  received: { label: 'Recebida', tone: 'neutral' },
  transformed: { label: 'Transformada', tone: 'info' },
  validated: { label: 'Validada', tone: 'info' },
  published: { label: 'Publicada', tone: 'primary' },
  processed: { label: 'Processada', tone: 'success' },
  failed: { label: 'Falhou', tone: 'danger' },
  dead_lettered: { label: 'DLQ', tone: 'danger' },
  reprocessing: { label: 'Reprocessando', tone: 'warning' },
};

export const mergeCaseStatusLabels: Record<MergeCaseStatus, { label: string; tone: BadgeTone }> = {
  open: { label: 'Aberto', tone: 'warning' },
  in_review: { label: 'Em revisão', tone: 'info' },
  merged: { label: 'Fundido', tone: 'success' },
  rejected: { label: 'Rejeitado', tone: 'neutral' },
  unmerged: { label: 'Fusão revertida', tone: 'danger' },
};

export const identifierSystemLabels: Record<string, string> = {
  CNS: 'CNS',
  CPF: 'CPF',
  PEC: 'e-SUS PEC',
  SISREG: 'SISREG',
  ESUS_REGULACAO: 'e-SUS Regulação',
  HIS: 'HIS',
  AIH: 'AIH',
  APAC: 'APAC',
  LOCAL: 'Local',
};

export const sexLabels: Record<string, string> = {
  female: 'Feminino',
  male: 'Masculino',
  unknown: 'Não informado',
};
