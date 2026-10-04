package br.gov.sus.nexus.core.identity.domain;

import java.util.List;

/** Resultado da comparação probabilística de um par. */
public record MatchScore(PersonFeatures candidate, double score, List<MatchEvidence> evidences) {}
