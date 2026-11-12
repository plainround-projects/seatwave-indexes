package io.plainround.seatwave.indexes.study;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class StudySettingsTest {

  private static final String PASSWORD = "lab-password-for-the-test";

  @TempDir Path folder;

  @Test
  void readsEveryKeyAndThePasswordFromItsOwnFile() throws IOException {
    Path settingsFile = write(valid());

    StudySettings settings = StudySettings.load(settingsFile);

    assertThat(settings)
        .isEqualTo(
            new StudySettings(
                "jdbc:postgresql://127.0.0.1:21400/seatwave",
                "seatwave_owner",
                folder.resolve("owner.password"),
                625,
                625,
                200,
                60,
                64,
                1_000_000));
    assertThat(settings.password()).isEqualTo(PASSWORD);
  }

  @Test
  void reportsEveryProblemOfTheFileInOneMessage() throws IOException {
    String broken =
        valid()
            .replace("db.user=seatwave_owner\n", "")
            .replace("bench.writerThreads=64", "bench.writerThreads=500")
            .replace("plan.repetitions=200", "plan.repetitions=many");
    Path settingsFile = write(broken);

    assertThatThrownBy(() -> StudySettings.load(settingsFile))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("db.user is missing")
        .hasMessageContaining("bench.writerThreads must be from 1 to 128, was 500")
        .hasMessageContaining("plan.repetitions must be a whole number, was 'many'");
  }

  @Test
  void refusesADatabaseThatIsNotOnTheLoopbackAddress() throws IOException {
    Path settingsFile = write(valid().replace("127.0.0.1:21400", "db.example.org:5432"));

    assertThatThrownBy(() -> StudySettings.load(settingsFile))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining(
            "db.url must point at the lab database on 127.0.0.1, was jdbc:postgresql://db.example.org:5432/seatwave");
  }

  @Test
  void refusesMoreEventsThanTheFullVolume() throws IOException {
    Path settingsFile = write(valid().replace("study.arenaEvents=625", "study.arenaEvents=2501"));

    assertThatThrownBy(() -> StudySettings.load(settingsFile))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("study.arenaEvents must be from 1 to 2500, was 2501");
  }

  @Test
  void namesThePasswordFileItCannotRead() throws IOException {
    Path missing = folder.resolve("absent.password");
    Path settingsFile =
        write(valid().replace(folder.resolve("owner.password").toString(), missing.toString()));

    StudySettings settings = StudySettings.load(settingsFile);

    assertThatThrownBy(settings::password)
        .isInstanceOf(UncheckedIOException.class)
        .hasMessageContaining("cannot read the password file " + missing);
  }

  @Test
  void namesTheSettingsFileItCannotRead() {
    Path missing = folder.resolve("absent.properties");

    assertThatThrownBy(() -> StudySettings.load(missing))
        .isInstanceOf(UncheckedIOException.class)
        .hasMessageContaining("cannot read settings file " + missing);
  }

  @Test
  void neverHoldsThePasswordInItsText() throws IOException {
    StudySettings settings = StudySettings.load(write(valid()));

    assertThat(settings.toString()).doesNotContain(PASSWORD);
  }

  private String valid() throws IOException {
    Path passwordFile = folder.resolve("owner.password");
    Files.writeString(passwordFile, PASSWORD + "\n", StandardCharsets.UTF_8);
    return """
    db.url=jdbc:postgresql://127.0.0.1:21400/seatwave
    db.user=seatwave_owner
    db.passwordFile=%s
    study.arenaEvents=625
    study.clubEvents=625
    plan.repetitions=200
    bench.seconds=60
    bench.writerThreads=64
    bench.prefillRows=1000000
    """
        .formatted(passwordFile);
  }

  private Path write(String settingsText) throws IOException {
    Path settingsFile = folder.resolve("study.properties");
    Files.writeString(settingsFile, settingsText, StandardCharsets.UTF_8);
    return settingsFile;
  }
}
