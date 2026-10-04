package br.gov.sus.nexus.core.identity.api;

import br.gov.sus.nexus.core.platform.pagination.Page;

/** API pública do MPI para a fila de revisão (merge/unmerge/reject). */
public interface MergeCaseService {

  Page<MergeCaseDto> list(MergeCaseStatus status, String cursor, Integer limit);

  MergeCaseDto get(String caseId);

  MergeCaseDto merge(String caseId, Requests.Merge request);

  MergeCaseDto reject(String caseId, Requests.Reason request);

  MergeCaseDto unmerge(String mergeId, Requests.Reason request);
}
