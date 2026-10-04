import type {
  ActionClass,
  ActionStatus,
  AppointmentStatus,
  ApprovalStatus,
  AutonomyLevel,
  CareGapKind,
  CareGapResolution,
  CarePlanItemKind,
  CarePlanItemStatus,
  CarePlanOriginKind,
  CarePlanStatus,
  ConnectorHealth,
  DischargeFollowupOutcome,
  FollowupStatus,
  HospitalEpisodeClass,
  HospitalEpisodeStatus,
  HospitalRiskLevel,
  Domain,
  ExamIssue,
  ExamOrderStatus,
  ExamResultStatus,
  IdentityConfidence,
  IntegrationMessageStatus,
  MergeCaseStatus,
  ProtocolItemPriority,
  ProtocolStatus,
  ProtocolTransitionAction,
  Purpose,
  RegistrationState,
  RegulationIssueFilter,
  RegulationIssueKind,
  RegulationKind,
  RegulationPriority,
  RegulationQueueGroupBy,
  RegulationStatus,
  RunStatus,
  TaskPriority,
  TaskStatus,
  TimelineConfidence,
  ToolCallRecord,
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

// ---------- regulação ----------

export const regulationStatusLabels: Record<RegulationStatus, { label: string; tone: BadgeTone }> =
  {
    requested: { label: 'Solicitado', tone: 'neutral' },
    pending_documents: { label: 'Aguardando documentos', tone: 'warning' },
    returned: { label: 'Devolvido à origem', tone: 'warning' },
    under_review: { label: 'Em análise', tone: 'info' },
    authorized: { label: 'Autorizado', tone: 'success' },
    denied: { label: 'Negado', tone: 'danger' },
    scheduled: { label: 'Agendado', tone: 'primary' },
    cancelled: { label: 'Cancelado', tone: 'neutral' },
    no_show: { label: 'Faltou', tone: 'danger' },
    performed: { label: 'Realizado', tone: 'success' },
    expired: { label: 'Expirado', tone: 'danger' },
  };

export const regulationPriorityLabels: Record<
  RegulationPriority,
  { label: string; tone: BadgeTone; rank: number }
> = {
  emergency: { label: 'Emergência', tone: 'danger', rank: 0 },
  urgent: { label: 'Urgente', tone: 'danger', rank: 1 },
  priority: { label: 'Prioritário', tone: 'warning', rank: 2 },
  elective: { label: 'Eletivo', tone: 'neutral', rank: 3 },
};

export const regulationKindLabels: Record<RegulationKind, string> = {
  consultation: 'Consulta',
  exam: 'Exame',
  procedure: 'Procedimento',
  surgery: 'Cirurgia',
  admission: 'Internação',
};

export const regulationIssueKindLabels: Record<RegulationIssueKind, string> = {
  missing_document: 'Documento ausente',
  missing_field: 'Campo obrigatório ausente',
  clinical_justification: 'Justificativa clínica insuficiente',
  duplicate: 'Possível duplicidade',
  other: 'Outra',
  sla_breached: 'SLA estourado',
  no_capacity: 'Sem capacidade no prestador',
  expired: 'Solicitação expirada',
};

export const regulationIssueFilterLabels: Record<RegulationIssueFilter, string> = {
  incomplete: 'Incompletas (documento/campo)',
  returned: 'Devolvidas à origem',
  expired: 'Expiradas',
  duplicate: 'Duplicadas',
  no_capacity: 'Sem capacidade',
  sla_breached: 'SLA estourado',
};

export const regulationQueueGroupByLabels: Record<RegulationQueueGroupBy, string> = {
  specialty: 'Especialidade',
  service_code: 'Procedimento',
  provider_cnes: 'Prestador',
  requesting_cnes: 'Unidade solicitante',
  priority: 'Prioridade',
};

// ---------- exames ----------

export const examStatusLabels: Record<ExamOrderStatus, { label: string; tone: BadgeTone }> = {
  requested: { label: 'Solicitado', tone: 'neutral' },
  authorized: { label: 'Autorizado', tone: 'info' },
  scheduled: { label: 'Agendado', tone: 'primary' },
  collected: { label: 'Coletado', tone: 'primary' },
  performed: { label: 'Realizado', tone: 'info' },
  reported: { label: 'Laudado', tone: 'success' },
  cancelled: { label: 'Cancelado', tone: 'neutral' },
  not_performed: { label: 'Não realizado', tone: 'danger' },
};

/** Ordem canônica do ciclo do exame (stepper). */
export const EXAM_CYCLE: ExamOrderStatus[] = [
  'requested',
  'authorized',
  'scheduled',
  'collected',
  'performed',
  'reported',
];

export const examIssueLabels: Record<ExamIssue, { label: string; tone: BadgeTone }> = {
  not_scheduled: { label: 'Não agendado', tone: 'warning' },
  result_pending: { label: 'Laudo pendente', tone: 'warning' },
  no_result_followup: { label: 'Sem retorno', tone: 'warning' },
  integration_failure: { label: 'Falha de integração', tone: 'danger' },
  inconclusive: { label: 'Inconclusivo', tone: 'neutral' },
  critical: { label: 'Crítico', tone: 'danger' },
};

export const examResultStatusLabels: Record<ExamResultStatus, { label: string; tone: BadgeTone }> =
  {
    final: { label: 'Final', tone: 'success' },
    preliminary: { label: 'Preliminar', tone: 'info' },
    amended: { label: 'Retificado', tone: 'warning' },
    inconclusive: { label: 'Inconclusivo', tone: 'neutral' },
    cancelled: { label: 'Cancelado', tone: 'neutral' },
  };

// ---------- cuidado ----------

/** Tipos de tarefa do Workbench de Cuidado (origem: regras/agentes/workflows). */
export const CARE_TASK_TYPES = [
  'no_show_recovery',
  'exam_not_scheduled',
  'exam_result_followup',
  'regulation_pending_document',
  'mpi_review',
  'generic',
] as const;
export type CareTaskType = (typeof CARE_TASK_TYPES)[number];

export const careTaskTypeLabels: Record<CareTaskType, { label: string; description: string }> = {
  no_show_recovery: {
    label: 'Recuperação de falta',
    description: 'Cidadão faltou a consulta/exame; reagendar e orientar.',
  },
  exam_not_scheduled: {
    label: 'Exame não agendado',
    description: 'Pedido de exame sem agendamento dentro do prazo.',
  },
  exam_result_followup: {
    label: 'Retorno de exame',
    description: 'Laudo disponível sem consulta de retorno registrada.',
  },
  regulation_pending_document: {
    label: 'Pendência de regulação',
    description: 'Solicitação devolvida ou aguardando documento da unidade.',
  },
  mpi_review: {
    label: 'Revisão de cadastro',
    description: 'Possível duplicidade ou divergência de identidade.',
  },
  generic: { label: 'Outras', description: 'Tarefas sem classificação específica.' },
};

export function careTaskType(taskType: string): CareTaskType {
  return (CARE_TASK_TYPES as readonly string[]).includes(taskType)
    ? (taskType as CareTaskType)
    : 'generic';
}

// ---------- agentes ----------

export const runStatusLabels: Record<RunStatus, { label: string; tone: BadgeTone }> = {
  running: { label: 'Em execução', tone: 'info' },
  completed: { label: 'Concluída', tone: 'success' },
  invalid_output: { label: 'Saída inválida', tone: 'warning' },
  failed: { label: 'Falhou', tone: 'danger' },
  denied: { label: 'Negada (política)', tone: 'danger' },
};

export const actionStatusLabels: Record<ActionStatus, { label: string; tone: BadgeTone }> = {
  executed: { label: 'Executada', tone: 'success' },
  pending_approval: { label: 'Aguardando aprovação', tone: 'warning' },
  approved: { label: 'Aprovada', tone: 'success' },
  rejected: { label: 'Rejeitada', tone: 'neutral' },
  blocked: { label: 'Bloqueada', tone: 'danger' },
  denied: { label: 'Negada', tone: 'danger' },
  failed: { label: 'Falhou', tone: 'danger' },
};

export const actionClassLabels: Record<ActionClass, { label: string; tone: BadgeTone }> = {
  auto: { label: 'Automática', tone: 'success' },
  requires_approval: { label: 'Requer aprovação', tone: 'warning' },
  forbidden: { label: 'Proibida', tone: 'danger' },
};

export const approvalStatusLabels: Record<ApprovalStatus, { label: string; tone: BadgeTone }> = {
  pending: { label: 'Pendente', tone: 'warning' },
  approved: { label: 'Aprovada', tone: 'success' },
  rejected: { label: 'Rejeitada', tone: 'neutral' },
};

export const autonomyLabels: Record<AutonomyLevel, { label: string; tone: BadgeTone }> = {
  suggest_only: { label: 'Somente sugestão', tone: 'neutral' },
  approval_required: { label: 'Ação com aprovação humana', tone: 'warning' },
  autonomous: { label: 'Ações automáticas (baixo risco)', tone: 'info' },
};

export const toolCallStatusLabels: Record<
  ToolCallRecord['status'],
  { label: string; tone: BadgeTone }
> = {
  executed: { label: 'Executada', tone: 'success' },
  denied: { label: 'Negada pelo OPA', tone: 'danger' },
  requires_approval: { label: 'Aguardando aprovação', tone: 'warning' },
  error: { label: 'Erro', tone: 'danger' },
};

// ---------- hospital (visão da APS) ----------

export const hospitalEpisodeStatusLabels: Record<
  HospitalEpisodeStatus,
  { label: string; tone: BadgeTone }
> = {
  admitted: { label: 'Internado', tone: 'info' },
  in_progress: { label: 'Em atendimento', tone: 'info' },
  transferred: { label: 'Transferido', tone: 'warning' },
  discharged: { label: 'Alta', tone: 'success' },
  deceased: { label: 'Óbito', tone: 'neutral' },
  cancelled: { label: 'Cancelado', tone: 'neutral' },
};

export const hospitalEpisodeClassLabels: Record<HospitalEpisodeClass, string> = {
  inpatient: 'Internação',
  emergency: 'Urgência/emergência',
  observation: 'Observação',
  day_hospital: 'Hospital-dia',
};

export const hospitalRiskLabels: Record<HospitalRiskLevel, { label: string; tone: BadgeTone }> = {
  high: { label: 'Risco alto', tone: 'danger' },
  medium: { label: 'Risco médio', tone: 'warning' },
  low: { label: 'Risco baixo', tone: 'success' },
};

export const followupStatusLabels: Record<FollowupStatus, { label: string; tone: BadgeTone }> = {
  pending: { label: 'Contato pendente', tone: 'warning' },
  contacted: { label: 'Contato realizado', tone: 'success' },
  scheduled: { label: 'Consulta agendada', tone: 'success' },
  closed: { label: 'Encerrado', tone: 'neutral' },
  escalated: { label: 'Escalonado', tone: 'danger' },
};

export const dischargeFollowupOutcomeLabels: Record<DischargeFollowupOutcome, string> = {
  contact_made: 'Contato realizado',
  appointment_scheduled: 'Consulta agendada na UBS',
  deceased: 'Óbito',
  moved: 'Mudou-se do território',
  refused: 'Recusou acompanhamento',
  not_found: 'Não localizado',
};

/** Destino na alta (`disposition`) — texto livre no contrato; rótulos dos valores conhecidos. */
export const dischargeDispositionLabels: Record<string, string> = {
  home: 'Domicílio',
  home_with_care: 'Domicílio com cuidados',
  transfer: 'Transferência',
  against_advice: 'Alta a pedido',
  deceased: 'Óbito',
  other: 'Outro',
};

export const hospitalMovementLabels: Record<string, string> = {
  admit: 'Admissão',
  transfer: 'Transferência',
  bed_change: 'Mudança de leito',
  discharge: 'Alta',
  death: 'Óbito',
  cancel: 'Cancelamento',
};

export const admissionSourceLabels: Record<string, string> = {
  emergency: 'Urgência',
  regulation: 'Regulação',
  transfer: 'Transferência',
  elective: 'Eletiva',
  other: 'Outra',
};

// ---------- plano de cuidado / lacunas / protocolos ----------

/** Rótulos das linhas de cuidado conhecidas; demais valores são exibidos como vieram. */
export const careLineLabels: Record<string, string> = {
  pre_natal: 'Pré-natal',
  hipertensao: 'Hipertensão',
  diabetes: 'Diabetes',
  saude_mental: 'Saúde mental',
  pos_alta: 'Pós-alta',
  puericultura: 'Puericultura',
};

export function careLineLabel(careLine: string): string {
  return careLineLabels[careLine] ?? careLine;
}

export const carePlanStatusLabels: Record<CarePlanStatus, { label: string; tone: BadgeTone }> = {
  active: { label: 'Ativo', tone: 'primary' },
  on_hold: { label: 'Suspenso', tone: 'warning' },
  completed: { label: 'Concluído', tone: 'success' },
  cancelled: { label: 'Cancelado', tone: 'neutral' },
};

export const carePlanItemStatusLabels: Record<
  CarePlanItemStatus,
  { label: string; tone: BadgeTone }
> = {
  planned: { label: 'Previsto', tone: 'neutral' },
  scheduled: { label: 'Agendado', tone: 'info' },
  done: { label: 'Realizado', tone: 'success' },
  missed: { label: 'Não realizado', tone: 'danger' },
  cancelled: { label: 'Cancelado', tone: 'neutral' },
};

export const carePlanItemKindLabels: Record<CarePlanItemKind, string> = {
  consultation: 'Consulta',
  exam: 'Exame',
  vaccine: 'Vacina',
  return: 'Retorno',
  home_visit: 'Visita domiciliar',
  procedure: 'Procedimento',
  education: 'Ação educativa',
  other: 'Outro',
};

export const carePlanOriginLabels: Record<CarePlanOriginKind, string> = {
  professional: 'Profissional',
  rule: 'Regra',
  workflow: 'Fluxo',
  hospital_discharge: 'Alta hospitalar',
};

export const careGapKindLabels: Record<CareGapKind, string> = {
  consultation_overdue: 'Consulta em atraso',
  exam_overdue: 'Exame em atraso',
  vaccine_overdue: 'Vacina em atraso',
  return_overdue: 'Retorno em atraso',
  no_contact: 'Sem contato',
  lost_to_followup: 'Perda de seguimento',
  post_discharge_no_contact: 'Pós-alta sem contato',
};

export const careGapResolutionLabels: Record<CareGapResolution, string> = {
  performed: 'Realizado',
  scheduled: 'Agendado',
  contact_made: 'Contato realizado',
  refused: 'Recusou',
  moved: 'Mudou-se',
  deceased: 'Óbito',
  not_found: 'Não localizado',
  cancelled: 'Cancelado (não se aplica)',
};

export const protocolStatusLabels: Record<ProtocolStatus, { label: string; tone: BadgeTone }> = {
  draft: { label: 'Rascunho', tone: 'neutral' },
  in_review: { label: 'Em revisão', tone: 'warning' },
  approved: { label: 'Aprovada', tone: 'info' },
  active: { label: 'Vigente', tone: 'success' },
  revoked: { label: 'Revogada', tone: 'neutral' },
};

export const protocolItemPriorityLabels: Record<
  ProtocolItemPriority,
  { label: string; tone: BadgeTone }
> = {
  low: { label: 'Baixa', tone: 'neutral' },
  medium: { label: 'Média', tone: 'info' },
  high: { label: 'Alta', tone: 'warning' },
  urgent: { label: 'Urgente', tone: 'danger' },
};

export const protocolTransitionLabels: Record<ProtocolTransitionAction, string> = {
  submit: 'Enviar para revisão',
  approve: 'Aprovar',
  activate: 'Ativar',
  revoke: 'Revogar',
};

/** Próxima ação do ciclo de aprovação a partir do status atual (ou `undefined`). */
export const PROTOCOL_NEXT_ACTION: Partial<Record<ProtocolStatus, ProtocolTransitionAction>> = {
  draft: 'submit',
  in_review: 'approve',
  approved: 'activate',
  active: 'revoke',
};
