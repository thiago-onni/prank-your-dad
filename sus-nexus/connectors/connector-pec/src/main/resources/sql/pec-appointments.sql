-- Agendamentos alterados desde a marca d'água (incremental). SOMENTE LEITURA.
-- Ajuste à versão do PEC; mantenha os aliases usados em mappings/pec-appointment-1.0.0.yaml.
SELECT a.co_seq_agendado,
       a.co_cidadao,
       c.nu_cpf AS nu_cpf_cidadao,
       c.nu_cns AS nu_cns_cidadao,
       a.dt_agendado,
       a.co_situacao_agendado,
       u.nu_cnes,
       a.co_prof,
       a.ds_motivo_cancelamento,
       a.dt_atualizado
  FROM tb_agendado a
  JOIN tb_cidadao c ON c.co_seq_cidadao = a.co_cidadao
  LEFT JOIN tb_unidade_saude u ON u.co_seq_unidade_saude = a.co_unidade_saude
 WHERE a.dt_atualizado > CAST(:#watermark AS timestamp)
 ORDER BY a.dt_atualizado
 LIMIT 5000
