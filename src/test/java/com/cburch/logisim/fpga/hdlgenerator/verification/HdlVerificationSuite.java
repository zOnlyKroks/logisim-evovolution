/*
 * Logisim-evolution - digital logic design tool and simulator
 * Copyright by the Logisim-evolution developers
 *
 * https://github.com/logisim-evolution/
 *
 * This is free software released under GNU GPLv3 license
 */

package com.cburch.logisim.fpga.hdlgenerator.verification;

import com.cburch.logisim.fpga.hdlgenerator.AbstractHdlGeneratorFactory;
import com.cburch.logisim.fpga.hdlgenerator.HdlGeneratorFactory;
import com.cburch.logisim.fpga.hdlgenerator.verification.DutInterface.DutPort;
import com.cburch.logisim.fpga.hdlgenerator.verification.DutInterface.Role;
import com.cburch.logisim.prefs.AppPreferences;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

/**
 * Writes the HDL verification suite: for every {@link HdlTestCase} and both HDLs, the generated
 * entity/module, a harness for the generic test bench and the test vectors computed with the
 * Logisim simulation model.
 *
 * <p>Output layout, with one directory per test case:
 *
 * <pre>
 * &lt;output&gt;/vhdl/manifest.json
 * &lt;output&gt;/vhdl/&lt;case&gt;/{dut.vhd, harness.vhd, vectors.txt}
 * &lt;output&gt;/verilog/manifest.json
 * &lt;output&gt;/verilog/&lt;case&gt;/{dut.v, harness.v, vectors.txt}
 * </pre>
 *
 * <p>The test benches in {@code src/test/hdl} run the suite with VUnit (VHDL) and Icarus Verilog.
 * See {@code docs/hdl_verification.md}.
 */
public final class HdlVerificationSuite {

  /** The HDLs the suite is generated for. */
  enum Language {
    VHDL(HdlGeneratorFactory.VHDL, "vhdl", ".vhd", TestVectors.DONT_CARE),
    VERILOG(HdlGeneratorFactory.VERILOG, "verilog", ".v", 'x');

    final String hdlType;
    final String directory;
    final String extension;
    final char dontCare;

    Language(String hdlType, String directory, String extension, char dontCare) {
      this.hdlType = hdlType;
      this.directory = directory;
      this.extension = extension;
      this.dontCare = dontCare;
    }
  }

  private HdlVerificationSuite() {}

  /**
   * Generates the suite.
   *
   * <p>Arguments: {@code <output directory> [<test case name regex>]}
   */
  public static void main(String[] args) throws IOException {
    if (args.length < 1 || args.length > 2) {
      System.err.println("Usage: HdlVerificationSuite <output directory> [<test case regex>]");
      System.exit(2);
    }
    final var output = Path.of(args[0]);
    final var filter = Pattern.compile(args.length > 1 ? args[1] : "");
    final var cases =
        HdlVerificationCatalog.getCases().stream()
            .filter(testCase -> filter.matcher(testCase.getName()).find())
            .toList();
    final var failures = generate(cases, output);
    if (cases.isEmpty()) System.err.println("No test case matches '" + filter.pattern() + "'");
    if (!failures.isEmpty()) {
      System.err.printf("%d of %d test cases could not be generated:%n", failures.size(), cases.size());
      failures.forEach((name, error) -> System.err.printf("  %s: %s%n", name, error));
    }
    // The preferences may have started background threads, so exit explicitly.
    System.exit(failures.isEmpty() ? 0 : 1);
  }

  /**
   * Writes the suite for the given test cases into {@code output}, replacing a previous suite.
   *
   * @return error messages of the test cases that could not be generated, by test case name
   */
  static Map<String, String> generate(List<HdlTestCase> cases, Path output) throws IOException {
    final var failures = new LinkedHashMap<String, String>();
    final var manifests = new LinkedHashMap<Language, List<String>>();
    for (final var language : Language.values()) {
      deleteRecursively(output.resolve(language.directory));
      manifests.put(language, new ArrayList<>());
    }
    final var originalHdlType = AppPreferences.HdlType.get();
    try {
      for (final var testCase : cases) {
        try {
          final var entries = generate(testCase, output);
          entries.forEach((language, entry) -> manifests.get(language).add(entry));
        } catch (RuntimeException e) {
          failures.put(testCase.getName(), e.getClass().getSimpleName() + ": " + e.getMessage());
        }
      }
    } finally {
      AppPreferences.HdlType.set(originalHdlType);
    }
    for (final var language : Language.values()) {
      final var manifest =
          "{\n  \"language\": \"" + language.directory + "\",\n  \"cases\": [\n"
              + String.join(",\n", manifests.get(language))
              + "\n  ]\n}\n";
      write(output.resolve(language.directory).resolve("manifest.json"), manifest);
    }
    return failures;
  }

  /** Generates one test case for all languages and returns the manifest entries. */
  private static Map<Language, String> generate(HdlTestCase testCase, Path output) {
    final var model =
        new LogisimReferenceModel(
            testCase.getFactory(), testCase.getAttributes(), testCase.getFloatingInputs());
    final var entries = new LinkedHashMap<Language, String>();
    final var files = new LinkedHashMap<Path, String>();
    TestVectors vectors = null;
    for (final var language : Language.values()) {
      AppPreferences.HdlType.set(language.hdlType);
      final var dut = DutInterface.create(testCase, model);
      if (vectors == null) vectors = TestVectors.compute(testCase, dut, model);
      final var directory = output.resolve(language.directory).resolve(testCase.getName());
      final var dutFile = "dut" + language.extension;
      final var harnessFile = "harness" + language.extension;
      files.put(directory.resolve(dutFile), generateSource(testCase, dut));
      files.put(
          directory.resolve(harnessFile),
          language == Language.VHDL
              ? VhdlHarnessWriter.write(testCase, dut)
              : VerilogHarnessWriter.write(testCase, dut));
      files.put(directory.resolve("vectors.txt"), formatVectors(testCase, dut, vectors, language));
      entries.put(
          language,
          manifestEntry(testCase, language, dut, vectors, List.of(dutFile, harnessFile)));
    }
    // only write files once all languages could be generated
    files.forEach(HdlVerificationSuite::write);
    return entries;
  }

  /** Returns the generated entity and architecture (VHDL) or module (Verilog). */
  private static String generateSource(HdlTestCase testCase, DutInterface dut) {
    final var generator = (AbstractHdlGeneratorFactory) testCase.getGenerator();
    final var attrs = testCase.getAttributes();
    final var entity = generator.getEntity(DutInterface.NETLIST, attrs, dut.getEntityName());
    final var architecture =
        generator.getArchitecture(DutInterface.NETLIST, attrs, dut.getEntityName());
    if (entity == null || architecture == null) {
      throw new IllegalStateException("The HDL generator reported an error");
    }
    final var source = new ArrayList<>(entity);
    source.add("");
    source.addAll(architecture);
    return String.join("\n", source) + "\n";
  }

  private static String formatVectors(
      HdlTestCase testCase, DutInterface dut, TestVectors vectors, Language language) {
    final var out = new StringBuilder();
    out.append("# Test vectors for ").append(testCase.getDescription()).append('\n')
        .append("# Computed with the Logisim-evolution simulation model; do not edit.\n")
        .append("# Each line holds the stimulus and the expected response, most significant bit\n")
        .append("# first. '").append(language.dontCare).append("' marks don't-care bits.\n")
        .append("# stimulus: ").append(layout(dut.getPorts(Role.STIMULUS))).append('\n')
        .append("# response: ").append(layout(dut.getPorts(Role.RESPONSE))).append('\n');
    for (final var vector : vectors.getVectors()) {
      out.append(vector.stimulus()).append(' ')
          .append(vector.response().replace(TestVectors.DONT_CARE, language.dontCare))
          .append('\n');
    }
    return out.toString();
  }

  private static String layout(List<DutPort> ports) {
    return ports.stream()
        .sorted(Comparator.comparingInt(DutPort::offset).reversed())
        .map(port -> String.format("%s[%d:%d]", port.name(), port.msb(), port.offset()))
        .collect(Collectors.joining(" "));
  }

  private static String manifestEntry(
      HdlTestCase testCase,
      Language language,
      DutInterface dut,
      TestVectors vectors,
      List<String> sources) {
    final var name = testCase.getName();
    final var knownIssue = testCase.getKnownIssue(language.hdlType);
    final var sourceList =
        sources.stream().map(source -> json(name + "/" + source)).collect(Collectors.joining(", "));
    return "    {\n"
        + "      \"name\": " + json(name) + ",\n"
        + "      \"description\": " + json(testCase.getDescription()) + ",\n"
        + "      \"entity\": " + json(dut.getEntityName()) + ",\n"
        + "      \"stimulusBits\": " + dut.getStimulusBits() + ",\n"
        + "      \"responseBits\": " + dut.getResponseBits() + ",\n"
        + "      \"vectorCount\": " + vectors.getVectors().size() + ",\n"
        + "      \"dontCareVectorCount\": " + vectors.getDontCareVectorCount() + ",\n"
        + "      \"vectors\": " + json(name + "/vectors.txt") + ",\n"
        + "      \"sources\": [" + sourceList + "],\n"
        + "      \"knownIssue\": " + (knownIssue == null ? "null" : json(knownIssue)) + "\n"
        + "    }";
  }

  private static String json(String text) {
    return "\"" + text.replace("\\", "\\\\").replace("\"", "\\\"") + "\"";
  }

  private static void write(Path file, String content) {
    try {
      Files.createDirectories(file.getParent());
      Files.writeString(file, content, StandardCharsets.UTF_8);
    } catch (IOException e) {
      throw new UncheckedIOException(e);
    }
  }

  private static void deleteRecursively(Path directory) throws IOException {
    if (!Files.exists(directory)) return;
    try (final var paths = Files.walk(directory)) {
      for (final var path : paths.sorted(Comparator.reverseOrder()).toList()) Files.delete(path);
    }
  }
}
