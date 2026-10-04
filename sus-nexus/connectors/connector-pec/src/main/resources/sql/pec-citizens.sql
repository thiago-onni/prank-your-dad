-- Cidadãos alterados desde a marca d'água (incremental). SOMENTE LEITURA.
-- ATENÇÃO: nomes de tabelas/colunas variam entre versões do PEC (4.x/5.x). Ajuste a esta instalação
-- e mantenha o alias de saída (co_seq_cidadao, no_cidadao, ...) igual ao usado em
-- mappings/pec-citizen-1.0.0.yaml. Execute com usuário de banco restrito a SELECT na réplica.
SELECT c.co_seq_cidadao,
       c.no_cidadao,
       c.no_social_cidadao,
       c.nu_cpf,
       c.nu_cns,
       c.dt_nascimento,
       c.no_mae,
       c.no_pai,
       s.no_sexo,
       c.co_raca_cor,
       c.ds_logradouro,
       c.nu_numero,
       c.ds_complemento,
       c.no_bairro,
       l.co_ibge,
       c.ds_cep,
       c.nu_telefone_celular,
       u.nu_cnes,
       e.nu_ine,
       c.nu_micro_area,
       c.st_faleceu,
       c.dt_obito,
       c.dt_atualizado
  FROM tb_cidadao c
  LEFT JOIN tb_sexo s ON s.co_sexo = c.co_sexo
  LEFT JOIN tb_localidade l ON l.co_localidade = c.co_localidade
  LEFT JOIN tb_unidade_saude u ON u.co_seq_unidade_saude = c.co_unidade_saude
  LEFT JOIN tb_equipe e ON e.co_seq_equipe = c.co_equipe
 WHERE c.dt_atualizado > CAST(:#watermark AS timestamp)
 ORDER BY c.dt_atualizado
 LIMIT 5000
