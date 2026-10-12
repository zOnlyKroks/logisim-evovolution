/*
 * Logisim-evolution - digital logic design tool and simulator
 * Copyright by the Logisim-evolution developers
 *
 * https://github.com/logisim-evolution/
 *
 * This is free software released under GNU GPLv3 license
 */

package com.cburch.logisim.fpga.hdlgenerator.verification;

import static org.junit.jupiter.api.Assertions.assertAll;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.cburch.logisim.data.AttributeSet;
import com.cburch.logisim.data.BitWidth;
import com.cburch.logisim.data.Value;
import com.cburch.logisim.fpga.hdlgenerator.AbstractHdlGeneratorFactory;
import com.cburch.logisim.fpga.hdlgenerator.HdlGeneratorFactory;
import com.cburch.logisim.fpga.hdlgenerator.verification.DutInterface.Role;
import com.cburch.logisim.instance.Port;
import com.cburch.logisim.instance.StdAttr;
import com.cburch.logisim.prefs.AppPreferences;
import com.cburch.logisim.std.arith.Adder;
import com.cburch.logisim.std.arith.Subtractor;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class HdlVerificationSuiteTest {

  private final String originalHdlType = AppPreferences.HdlType.get();

  @AfterEach
  void restoreHdlType() {
    AppPreferences.HdlType.set(originalHdlType);
  }

  @Test
  void everyCatalogCaseCanBeGenerated(@TempDir Path output) throws Exception {
    final var cases = HdlVerificationCatalog.getCases();

    final var failures = HdlVerificationSuite.generate(cases, output);

    assertEquals(Map.of(), failures);
    assertEquals(originalHdlType, AppPreferences.HdlType.get());
    for (final var language : List.of("vhdl", "verilog")) {
      final var manifest = Files.readString(output.resolve(language).resolve("manifest.json"));
      for (final var testCase : cases) {
        assertTrue(manifest.contains("\"name\": \"" + testCase.getName() + "\""), testCase.getName());
        assertTrue(Files.exists(output.resolve(language).resolve(testCase.getName()).resolve("vectors.txt")));
      }
    }
  }

  @Test
  void catalogNamesAreUnique() {
    final var names = new HashSet<String>();
    for (final var testCase : HdlVerificationCatalog.getCases()) {
      assertTrue(names.add(testCase.getName()), "duplicate test case " + testCase.getName());
    }
  }

  @Test
  void referenceModelPropagatesTheComponent() {
    final var model = new LogisimReferenceModel(new Adder(), attributes(8), Set.of());

    final var outputs =
        model.evaluate(
            Map.of(
                Adder.IN0, Value.createKnown(8, 200),
                Adder.IN1, Value.createKnown(8, 100),
                Adder.C_IN, Value.TRUE));

    assertEquals(Value.createKnown(8, 45), outputs.get(Adder.OUT));
    assertEquals(Value.TRUE, outputs.get(Adder.C_OUT));
  }

  @Test
  void packsPortsInDeclarationOrder() {
    final var testCase = HdlTestCase.of("adder_w8", new Adder()).width(8).build();
    AppPreferences.HdlType.set(HdlGeneratorFactory.VHDL);

    final var dut = DutInterface.create(testCase, model(testCase));

    assertAll(
        () -> assertEquals("Adder", dut.getEntityName()),
        () -> assertEquals(17, dut.getStimulusBits()),
        () -> assertEquals(9, dut.getResponseBits()),
        () ->
            assertEquals(
                List.of("carryIn", "dataA", "dataB"),
                dut.getPorts(Role.STIMULUS).stream().map(DutInterface.DutPort::name).toList()),
        () -> assertEquals(1, dut.getPorts(Role.STIMULUS).get(1).offset()),
        () -> assertTrue(dut.getPorts(Role.STIMULUS).get(0).scalar()),
        () -> assertEquals("8", dut.getParameters().get("nrOfBits")));
  }

  @Test
  void harnessesConnectScalarsAndSlices() {
    final var testCase = HdlTestCase.of("adder_w8", new Adder()).width(8).build();
    final var model = model(testCase);
    AppPreferences.HdlType.set(HdlGeneratorFactory.VHDL);
    final var vhdl = VhdlHarnessWriter.write(testCase, DutInterface.create(testCase, model));
    AppPreferences.HdlType.set(HdlGeneratorFactory.VERILOG);
    final var verilog = VerilogHarnessWriter.write(testCase, DutInterface.create(testCase, model));

    assertAll(
        () -> assertTrue(vhdl.contains("dut : entity work.Adder")),
        () -> assertTrue(vhdl.contains("carryIn  => stimulus(0)")),
        () -> assertTrue(vhdl.contains("dataA    => stimulus(8 downto 1)")),
        () -> assertTrue(vhdl.contains("result   => response(8 downto 1)")),
        () -> assertTrue(vhdl.contains("nrOfBits     => 8")),
        () -> assertTrue(verilog.contains(".carryIn(stimulus[0])")),
        () -> assertTrue(verilog.contains(".dataA(stimulus[8:1])")),
        () -> assertTrue(verilog.contains(".nrOfBits(8)")));
  }

  @Test
  void floatingInputsAreTiedOffAndFloatInTheModel() {
    final var testCase =
        HdlTestCase.of("subtractor_open_borrow", new Subtractor())
            .width(8)
            .floating(Subtractor.B_IN)
            .build();
    AppPreferences.HdlType.set(HdlGeneratorFactory.VHDL);
    final var model = model(testCase);
    final var dut = DutInterface.create(testCase, model);

    final var tied = dut.getPorts(Role.TIED);
    final var vectors = TestVectors.compute(testCase, dut, model).getVectors();

    assertEquals(1, tied.size());
    assertEquals("borrowIn", tied.get(0).name());
    assertFalse(tied.get(0).tieHigh());
    assertTrue(VhdlHarnessWriter.write(testCase, dut).contains("borrowIn  => '0'"));
    assertEquals(16, dut.getStimulusBits());
    // 0 - 0 without a borrow in
    assertEquals(new TestVectors.Vector("0".repeat(16), "0".repeat(9)), vectors.get(0));
  }

  @Test
  void smallInputSpacesAreAppliedExhaustively() {
    final var testCase = HdlTestCase.of("adder_w4", new Adder()).width(4).build();
    AppPreferences.HdlType.set(HdlGeneratorFactory.VHDL);
    final var model = model(testCase);

    final var vectors = TestVectors.compute(testCase, DutInterface.create(testCase, model), model);

    final var stimuli = vectors.getVectors().stream().map(TestVectors.Vector::stimulus).toList();
    assertEquals(512, stimuli.size());
    assertEquals(512, new HashSet<>(stimuli).size());
    assertEquals(0, vectors.getDontCareVectorCount());
  }

  @Test
  void largeInputSpacesUseReproducibleCornersAndRandomValues() {
    final var testCase = HdlTestCase.of("adder_w32", new Adder()).width(32).randomVectors(100).build();
    AppPreferences.HdlType.set(HdlGeneratorFactory.VHDL);
    final var model = model(testCase);
    final var dut = DutInterface.create(testCase, model);

    final var first = TestVectors.compute(testCase, dut, model).getVectors();
    final var second = TestVectors.compute(testCase, dut, model).getVectors();

    // 11 corner values for each data input, 2 for the carry
    assertEquals(11 * 11 * 2 + 100, first.size());
    assertEquals(first, second);
  }

  @Test
  void inputRangesLimitTheAppliedValues() {
    final var testCase =
        HdlTestCase.of("adder_w32", new Adder()).width(32).inputRange(Adder.IN0, 0, 96).build();
    AppPreferences.HdlType.set(HdlGeneratorFactory.VHDL);
    final var model = model(testCase);
    final var dut = DutInterface.create(testCase, model);
    final var dataA =
        dut.getPorts(Role.STIMULUS).stream()
            .filter(port -> port.componentPin() == Adder.IN0)
            .findFirst()
            .orElseThrow();

    for (final var values : StimulusGenerator.generate(testCase, dut.getPorts(Role.STIMULUS))) {
      final var value = values[dut.getPorts(Role.STIMULUS).indexOf(dataA)];
      assertTrue(value >= 0 && value <= 96, "dataA " + value);
    }
  }

  @Test
  void portsMustMatchTheComponentPins() {
    final var generator =
        new AbstractHdlGeneratorFactory() {
          {
            myPorts
                .add(Port.INPUT, "dataA", 4, Adder.IN0)
                .add(Port.OUTPUT, "result", 8, Adder.OUT);
          }
        };
    final var testCase = HdlTestCase.of("adder_w8", new Adder()).width(8).generator(generator).build();

    final var error =
        assertThrows(IllegalStateException.class, () -> DutInterface.create(testCase, model(testCase)));

    assertEquals(
        "HDL port dataA has 4 bits, but the Logisim pin 0 it is mapped to has 8 bits",
        error.getMessage());
  }

  @Test
  void floatingPinsMustBeHdlInputs() {
    final var testCase = HdlTestCase.of("adder_w8", new Adder()).width(8).floating(Adder.OUT).build();

    assertThrows(IllegalArgumentException.class, () -> DutInterface.create(testCase, model(testCase)));
  }

  @Test
  void testCaseNamesMustBeLibraryNames() {
    assertAll(
        () -> assertThrows(IllegalArgumentException.class, () -> HdlTestCase.of("Adder", new Adder())),
        () -> assertThrows(IllegalArgumentException.class, () -> HdlTestCase.of("adder__8", new Adder())),
        () -> assertThrows(IllegalArgumentException.class, () -> HdlTestCase.of("adder_", new Adder())));
  }

  @Test
  void knownIssuesArePerLanguage() {
    final var testCase =
        HdlTestCase.of("adder_w8", new Adder()).width(8).knownVerilogIssue("broken").build();

    assertEquals("broken", testCase.getKnownIssue(HdlGeneratorFactory.VERILOG));
    assertEquals(null, testCase.getKnownIssue(HdlGeneratorFactory.VHDL));
  }

  private static LogisimReferenceModel model(HdlTestCase testCase) {
    return new LogisimReferenceModel(
        testCase.getFactory(), testCase.getAttributes(), testCase.getFloatingInputs());
  }

  private static AttributeSet attributes(int width) {
    final var attrs = new Adder().createAttributeSet();
    attrs.setValue(StdAttr.WIDTH, BitWidth.create(width));
    return attrs;
  }
}
