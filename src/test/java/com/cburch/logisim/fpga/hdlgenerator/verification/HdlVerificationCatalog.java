/*
 * Logisim-evolution - digital logic design tool and simulator
 * Copyright by the Logisim-evolution developers
 *
 * https://github.com/logisim-evolution/
 *
 * This is free software released under GNU GPLv3 license
 */

package com.cburch.logisim.fpga.hdlgenerator.verification;

import com.cburch.logisim.std.arith.ArithmeticHdlVerificationCases;
import java.util.ArrayList;
import java.util.List;

/**
 * All test cases of the HDL verification suite.
 *
 * <p>Each library contributes its cases from a class in its own (test) package, so the cases can
 * use package private attributes. Register new contributions here.
 */
public final class HdlVerificationCatalog {

  private HdlVerificationCatalog() {}

  public static List<HdlTestCase> getCases() {
    final var cases = new ArrayList<HdlTestCase>();
    cases.addAll(ArithmeticHdlVerificationCases.getCases());
    return cases;
  }
}
