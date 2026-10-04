# Gerador de dados sintéticos

Gera massa brasileira fictícia conforme o contrato `contracts/openapi/core-municipal.yaml` (SEC-006: dev/HML nunca recebem dado real).

```bash
python3 generate.py --citizens 1000 --out ./out --seed 42 --tenant ibge_3143302
```

Saídas:
- `health_units.json` — unidades (CNES fictício), equipes (INE) e microáreas.
- `citizens.jsonl` — um `CitizenRegistration` por linha, com CNS/CPF de dígito verificador válido e duplicidades/conflitos intencionais.
- `appointments.jsonl` — um `AppointmentRegistration` por linha (passados e futuros, com faltas e cancelamentos).
- `ground_truth.json` — grupos de registros que são a mesma pessoa (para medir precisão/recall do MPI) e pares em conflito (`__conflicts__`, que **não** devem ser fundidos automaticamente).

Parâmetros: `--duplicates-pct`, `--conflicts-pct`, `--no-cns-pct`, `--appointments-per-citizen`, `--units`.
