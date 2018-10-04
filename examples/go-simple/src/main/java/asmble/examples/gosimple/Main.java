package asmble.examples.gosimple;

import asmble.examples.goutil.Executor;
import asmble.generated.GoSimple;

public class Main {
  public static void main(String[] args) {
    Integer exitCode = new Executor<>(GoSimple::new).run(GoSimple::run, "test-app");
    if (exitCode == null) throw new IllegalStateException("Did not get exit code");
    if (exitCode != 0) throw new IllegalStateException("Expected exit code 0, got: " + exitCode);
  }
}
