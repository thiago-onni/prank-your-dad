package br.gov.sus.nexus.core.careplan.api;

import br.gov.sus.nexus.core.platform.pagination.Page;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Optional;

/**
 * API pública do módulo careplan: protocolos configuráveis (CUI-009), planos de cuidado
 * (CUI-001/002) e lacunas / busca ativa (CUI-003/004/006). Porta única para REST, consumidores de
 * evidência, job de detecção e outros módulos (hospital, journey).
 */
public interface CarePlanService {

  // --- protocolos ---

  ProtocolDto createProtocolVersion(ProtocolCreate create);

  /**
   * Transição com regras: aprovar exige casos de teste e papel gestor/admin; ativar troca a
   * vigente.
   */
  ProtocolDto transitionProtocol(String protocolId, String version, ProtocolTransition transition);

  List<ProtocolDto> listProtocols(String careLine, String status);

  // --- planos ---

  CarePlanDto create(CarePlanCreate create);

  CarePlanDto get(String carePlanId);

  Page<CarePlanDto> list(
      String citizenId,
      String careLine,
      String status,
      String teamIne,
      String cnes,
      String cursor,
      Integer limit);

  CarePlanDto updateItem(String carePlanId, String itemId, CarePlanItemUpdate update);

  CarePlanDto close(String carePlanId, CarePlanClose close);

  /**
   * Abre plano para a linha de cuidado quando há protocolo vigente elegível e o cidadão ainda não
   * tem plano ativo nela (uso do fluxo pós-alta). Vazio quando nada foi criado.
   */
  Optional<String> openPlanForCareLine(String citizenId, String careLine, CarePlanOrigin origin);

  /** Linhas de cuidado com plano ativo (resumo do cidadão). */
  List<String> activeCareLines(String citizenId);

  // --- lacunas ---

  Page<CareGapDto> listGaps(
      String careLine,
      CareGapKind kind,
      String cnes,
      String teamIne,
      String microarea,
      String status,
      Integer minDaysOverdue,
      String cursor,
      Integer limit);

  CareGapDto resolveGap(String careGapId, CareGapResolve resolve);

  /**
   * Lacuna {@code post_discharge_no_contact} (idempotente por episódio) + tarefa {@code care_gap}.
   */
  Optional<String> openPostDischargeNoContactGap(
      String citizenId,
      String careLine,
      String episodeId,
      OffsetDateTime expectedBy,
      String healthUnitCnes,
      String teamIne,
      String microarea);

  /** Resolve as lacunas abertas vinculadas a uma origem (ex.: episódio hospitalar). */
  int resolveGapsByOrigin(String originRef, String resolution, String note);

  long countOpenGaps(String citizenId);

  // --- evidência automática e detecção ---

  /**
   * Agendamento realizado: marca item compatível como {@code done} (código igual ou tipo ±30 d).
   */
  int applyAppointmentEvidence(
      String citizenId,
      String appointmentId,
      String serviceCode,
      boolean exam,
      OffsetDateTime occurredAt);

  /** Exame realizado/laudado: idem para itens do tipo exame. */
  int applyExamEvidence(
      String citizenId, String examOrderId, String examCode, OffsetDateTime occurredAt);

  /**
   * Varredura do tenant corrente: itens vencidos além de {@code gap_after_days} → lacuna + tarefa;
   * planos sem evento assistencial há mais de {@code lost_to_followup_days} → {@code
   * lost_to_followup}. Idempotente. Retorna quantas lacunas foram abertas.
   */
  int detectGaps();
}
