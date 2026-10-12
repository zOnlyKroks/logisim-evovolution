/*
 * Logisim-evolution - digital logic design tool and simulator
 * Copyright by the Logisim-evolution developers
 *
 * https://github.com/logisim-evolution/
 *
 * This is free software released under GNU GPLv3 license
 */

package com.cburch.logisim.std.arith;

import com.cburch.logisim.data.AttributeOption;
import com.cburch.logisim.fpga.hdlgenerator.verification.HdlTestCase;
import java.util.ArrayList;
import java.util.List;

/**
 * HDL verification cases for the arithmetic library.
 *
 * <p>Every component with an HDL generator is checked for a range of widths, covering the scalar
 * (one bit) special cases, small widths that are verified exhaustively, odd widths and the 64 bit
 * limit of the simulator, in every mode. Optional inputs are additionally checked unconnected.
 *
 * <p>Deviations the suite found are marked as known issues; remove a marker once it is fixed.
 */
public final class ArithmeticHdlVerificationCases {

  private static final List<Integer> WIDTHS = List.of(1, 4, 8, 13, 32, 64);

  /** A value of a mode attribute and the short name used in test case names. */
  private record Mode(String name, AttributeOption option) {}

  private static final List<Mode> COMPARATOR_MODES =
      List.of(
          new Mode("unsigned", Comparator.UNSIGNED_OPTION),
          new Mode("signed", Comparator.SIGNED_OPTION));

  private static final List<Mode> MULTIPLIER_MODES =
      List.of(
          new Mode("unsigned", Multiplier.UNSIGNED_OPTION),
          new Mode("signed", Multiplier.SIGNED_OPTION),
          new Mode("mixed", Multiplier.SIGNED_UNSIGNED_OPTION));

  private static final List<Mode> SHIFTER_MODES =
      List.of(
          new Mode("sll", Shifter.SHIFT_LOGICAL_LEFT),
          new Mode("srl", Shifter.SHIFT_LOGICAL_RIGHT),
          new Mode("sra", Shifter.SHIFT_ARITHMETIC_RIGHT),
          new Mode("rol", Shifter.SHIFT_ROLL_LEFT),
          new Mode("ror", Shifter.SHIFT_ROLL_RIGHT));

  private static final List<Mode> BIT_FINDER_TYPES =
      List.of(
          new Mode("low_one", BitFinder.LOW_ONE),
          new Mode("high_one", BitFinder.HIGH_ONE),
          new Mode("low_zero", BitFinder.LOW_ZERO),
          new Mode("high_zero", BitFinder.HIGH_ZERO));

  private static final String MULTIPLIER_MODE_ISSUE =
      "The unsignedMultiplier generic is 0 (unsigned), 1 (mixed) or 2 (signed), but the HDL treats"
          + " 1 as unsigned, 2 as mixed and everything else as signed";
  private static final String MULTIPLIER_VHDL_ISSUE =
      "The VHDL does not compile: the conditional signal assignments contain stray semicolons. "
          + MULTIPLIER_MODE_ISSUE;
  private static final String SUBTRACTOR_VERILOG_ISSUE =
      "dataA + ~(dataB) + n_bIn is evaluated at the width of {s_carry, result}, so ~dataB sets the"
          + " extra bit and the borrow out is inverted";
  private static final String SHIFTER_VERILOG_ISSUE =
      "Stage 0 shifts when shiftAmount != 0 instead of when shiftAmount[0] is set, and its shift-in"
          + " bit is dataA[shifterMode] (rotate left) or 0 (rotate right)";
  private static final String SHIFTER_SRA_VHDL_ISSUE =
      "Arithmetic right shifts fill with bit <stage number> of the stage input instead of the sign"
          + " bit";
  private static final String BIT_COMPARATOR_VERILOG_ISSUE =
      "The one bit Verilog comparator ignores the twosComplement parameter";

  private ArithmeticHdlVerificationCases() {}

  public static List<HdlTestCase> getCases() {
    final var cases = new ArrayList<HdlTestCase>();
    for (final var width : WIDTHS) {
      final var suffix = "_w" + width;
      cases.add(HdlTestCase.of("adder" + suffix, new Adder()).width(width).build());
      cases.add(
          HdlTestCase.of("subtractor" + suffix, new Subtractor())
              .width(width)
              .knownVerilogIssue(SUBTRACTOR_VERILOG_ISSUE)
              .build());
      cases.add(HdlTestCase.of("negator" + suffix, new Negator()).width(width).build());
      for (final var mode : COMPARATOR_MODES) {
        final var testCase =
            HdlTestCase.of("comparator_" + mode.name() + suffix, new Comparator())
                .width(width)
                .set(Comparator.MODE_ATTR, mode.option());
        if (width == 1 && mode.option() == Comparator.SIGNED_OPTION) {
          testCase.knownVerilogIssue(BIT_COMPARATOR_VERILOG_ISSUE);
        }
        cases.add(testCase.build());
      }
      for (final var mode : MULTIPLIER_MODES) {
        cases.add(
            HdlTestCase.of("multiplier_" + mode.name() + suffix, new Multiplier())
                .width(width)
                .set(Multiplier.MODE_ATTR, mode.option())
                .knownVhdlIssue(MULTIPLIER_VHDL_ISSUE)
                .knownVerilogIssue(MULTIPLIER_MODE_ISSUE)
                .build());
      }
      for (final var mode : SHIFTER_MODES) {
        final var testCase =
            HdlTestCase.of("shifter_" + mode.name() + suffix, new Shifter())
                .width(width)
                .set(Shifter.ATTR_SHIFT, mode.option());
        if (width > 1) {
          testCase.knownVerilogIssue(SHIFTER_VERILOG_ISSUE);
          if (mode.option() == Shifter.SHIFT_ARITHMETIC_RIGHT) {
            testCase.knownVhdlIssue(SHIFTER_SRA_VHDL_ISSUE);
          }
        }
        cases.add(testCase.build());
      }
    }
    for (final var width : List.of(1, 2, 3, 8, 13, 64)) {
      for (final var type : BIT_FINDER_TYPES) {
        cases.add(
            HdlTestCase.of("bit_finder_" + type.name() + "_w" + width, new BitFinder())
                .width(width)
                .set(BitFinder.TYPE, type.option())
                .build());
      }
    }

    // Optional inputs left unconnected, which the netlist ties off.
    for (final var width : List.of(1, 8, 64)) {
      final var suffix = "_w" + width;
      cases.add(
          HdlTestCase.of("adder_open_carry" + suffix, new Adder())
              .width(width)
              .floating(Adder.C_IN)
              .build());
      cases.add(
          HdlTestCase.of("subtractor_open_borrow" + suffix, new Subtractor())
              .width(width)
              .floating(Subtractor.B_IN)
              .knownVerilogIssue(SUBTRACTOR_VERILOG_ISSUE)
              .build());
      for (final var mode : MULTIPLIER_MODES) {
        final var testCase =
            HdlTestCase.of("multiplier_" + mode.name() + "_open_carry" + suffix, new Multiplier())
                .width(width)
                .set(Multiplier.MODE_ATTR, mode.option())
                .floating(Multiplier.C_IN)
                .knownVhdlIssue(MULTIPLIER_VHDL_ISSUE);
        // with a zero carry, the signed and unsigned one bit products happen to be equal
        if (width > 1 || mode.option() == Multiplier.SIGNED_UNSIGNED_OPTION) {
          testCase.knownVerilogIssue(MULTIPLIER_MODE_ISSUE);
        }
        cases.add(testCase.build());
      }
    }
    return cases;
  }
}
