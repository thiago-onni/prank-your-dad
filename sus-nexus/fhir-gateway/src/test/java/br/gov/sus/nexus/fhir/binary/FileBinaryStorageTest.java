package br.gov.sus.nexus.fhir.binary;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class FileBinaryStorageTest {

  @TempDir Path dir;

  @Test
  void putGetAndPathTraversal() throws Exception {
    FileBinaryStorage storage = new FileBinaryStorage(dir);
    String key = storage.put("ibge_1", "ABC", "text/plain", new byte[] {1, 2, 3});
    assertThat(key).isEqualTo("ibge_1/ABC");
    assertThat(Files.exists(dir.resolve(key))).isTrue();
    assertThat(storage.get(key)).contains(new byte[] {1, 2, 3});
    assertThat(storage.get("ibge_1/NOPE")).isEmpty();
    assertThatThrownBy(() -> storage.get("../../etc/passwd"))
        .isInstanceOf(BinaryStorageException.class);
    assertThat(storage.describe()).startsWith("file:");
  }

  @Test
  void s3StorageBuildsWithoutNetwork() {
    S3BinaryStorage s3 =
        S3BinaryStorage.fromConfig(
            "bucket",
            "sa-east-1",
            java.util.Optional.of("http://localhost:9000"),
            true,
            java.util.Optional.of("ak"),
            java.util.Optional.of("sk"));
    assertThat(s3.describe()).isEqualTo("s3:bucket");
  }
}
