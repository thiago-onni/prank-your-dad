package br.gov.sus.nexus.connectors.rnds;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.GeneralSecurityException;
import java.security.KeyStore;
import java.security.cert.X509Certificate;
import java.time.Instant;
import java.util.Collections;
import java.util.Optional;
import javax.net.ssl.KeyManagerFactory;
import javax.net.ssl.SSLContext;
import javax.net.ssl.TrustManagerFactory;

/**
 * Monta o {@link SSLContext} de cliente a partir do keystore PKCS#12 do certificado ICP-Brasil
 * (e-CNPJ) e, opcionalmente, de um truststore com a cadeia dos servidores da RNDS.
 */
public final class MtlsSupport {

  private MtlsSupport() {}

  /** Metadados não sensíveis do certificado (para health/heartbeat). */
  public record CertificateInfo(String subject, Instant notAfter) {}

  public static SSLContext sslContext(RndsConfig.Certificate cfg) {
    String keystorePath =
        cfg.keystorePath()
            .filter(p -> !p.isBlank())
            .orElseThrow(
                () ->
                    new IllegalStateException(
                        "rnds.certificate.keystore-path não configurado (certificado ICP-Brasil)"));
    char[] storePassword = cfg.keystorePassword().orElse("").toCharArray();
    char[] keyPassword = cfg.keyPassword().map(String::toCharArray).orElse(storePassword);
    return sslContext(
        Path.of(keystorePath),
        cfg.keystoreType(),
        storePassword,
        keyPassword,
        cfg.truststorePath().filter(p -> !p.isBlank()).map(Path::of),
        cfg.truststoreType(),
        cfg.truststorePassword().orElse("").toCharArray());
  }

  public static SSLContext sslContext(
      Path keystore,
      String keystoreType,
      char[] storePassword,
      char[] keyPassword,
      Optional<Path> truststore,
      String truststoreType,
      char[] trustPassword) {
    try {
      KeyStore ks = load(keystore, keystoreType, storePassword);
      KeyManagerFactory kmf =
          KeyManagerFactory.getInstance(KeyManagerFactory.getDefaultAlgorithm());
      kmf.init(ks, keyPassword);
      TrustManagerFactory tmf =
          TrustManagerFactory.getInstance(TrustManagerFactory.getDefaultAlgorithm());
      if (truststore.isPresent()) {
        tmf.init(load(truststore.get(), truststoreType, trustPassword));
      } else {
        tmf.init((KeyStore) null);
      }
      SSLContext ctx = SSLContext.getInstance("TLS");
      ctx.init(kmf.getKeyManagers(), tmf.getTrustManagers(), null);
      return ctx;
    } catch (IOException | GeneralSecurityException e) {
      throw new IllegalStateException(
          "falha ao carregar certificado/truststore da RNDS: " + e.getClass().getSimpleName(), e);
    }
  }

  /** Lê o primeiro certificado de chave privada do keystore (sujeito e validade). */
  public static Optional<CertificateInfo> describe(RndsConfig.Certificate cfg) {
    if (cfg.keystorePath().filter(p -> !p.isBlank()).isEmpty()) return Optional.empty();
    try {
      KeyStore ks =
          load(
              Path.of(cfg.keystorePath().get()),
              cfg.keystoreType(),
              cfg.keystorePassword().orElse("").toCharArray());
      for (String alias : Collections.list(ks.aliases())) {
        if (ks.isKeyEntry(alias) && ks.getCertificate(alias) instanceof X509Certificate x) {
          return Optional.of(
              new CertificateInfo(
                  x.getSubjectX500Principal().getName(), x.getNotAfter().toInstant()));
        }
      }
      return Optional.empty();
    } catch (IOException | GeneralSecurityException e) {
      return Optional.empty();
    }
  }

  private static KeyStore load(Path path, String type, char[] password)
      throws IOException, GeneralSecurityException {
    KeyStore ks = KeyStore.getInstance(type);
    try (InputStream in = Files.newInputStream(path)) {
      ks.load(in, password);
    }
    return ks;
  }
}
