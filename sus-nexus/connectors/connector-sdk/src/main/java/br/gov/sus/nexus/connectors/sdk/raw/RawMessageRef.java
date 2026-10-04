package br.gov.sus.nexus.connectors.sdk.raw;

import java.time.Instant;

/** Referência imutável ao bruto gravado na raw zone. */
public record RawMessageRef(String uri, String sha256, long sizeBytes, Instant storedAt) {}
