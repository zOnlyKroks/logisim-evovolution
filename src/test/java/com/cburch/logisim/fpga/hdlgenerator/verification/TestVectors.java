/*
 * Logisim-evolution - digital logic design tool and simulator
 * Copyright by the Logisim-evolution developers
 *
 * https://github.com/logisim-evolution/
 *
 * This is free software released under GNU GPLv3 license
 */

package com.cburch.logisim.fpga.hdlgenerator.verification;

import com.cburch.logisim.data.Value;
import com.cburch.logisim.fpga.hdlgenerator.verification.DutInterface.DutPort;
import com.cburch.logisim.fpga.hdlgenerator.verification.DutInterface.Role;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;

/**
 * Stimulus vectors together with the response the Logisim simulation model gives for them.
 *
 * <p>Both are bit strings, most significant bit first, laid out as described by {@link
 * DutInterface}. Response bits the model drives with an unknown or error value are don't-care bits,
 * written as {@link #DONT_CARE}.
 */
final class TestVectors {

  static final char DONT_CARE = '-';

  /** One stimulus vector and the expected response. */
  record Vector(String stimulus, String response) {}

  private final List<Vector> vectors;

  private TestVectors(List<Vector> vectors) {
    this.vectors = vectors;
  }

  static TestVectors compute(HdlTestCase testCase, DutInterface dut, LogisimReferenceModel model) {
    final var stimulusPorts = dut.getPorts(Role.STIMULUS);
    final var responsePorts = dut.getPorts(Role.RESPONSE);
    final var vectors = new ArrayList<Vector>();
    for (final var values : StimulusGenerator.generate(testCase, stimulusPorts)) {
      final var stimulus = new char[dut.getStimulusBits()];
      final var inputs = new HashMap<Integer, Value>();
      for (var index = 0; index < values.length; index++) {
        final var port = stimulusPorts.get(index);
        inputs.put(port.componentPin(), Value.createKnown(port.width(), values[index]));
        for (var bit = 0; bit < port.width(); bit++) {
          stimulus[position(stimulus, port, bit)] = ((values[index] >>> bit) & 1) != 0 ? '1' : '0';
        }
      }
      final var outputs = model.evaluate(inputs);
      final var response = new char[dut.getResponseBits()];
      for (final var port : responsePorts) {
        final var value = outputs.get(port.componentPin());
        for (var bit = 0; bit < port.width(); bit++) {
          response[position(response, port, bit)] = toChar(value, bit);
        }
      }
      vectors.add(new Vector(new String(stimulus), new String(response)));
    }
    return new TestVectors(Collections.unmodifiableList(vectors));
  }

  private static int position(char[] vector, DutPort port, int bit) {
    return vector.length - 1 - port.offset() - bit;
  }

  private static char toChar(Value value, int bit) {
    if (value == null || bit >= value.getWidth()) return DONT_CARE;
    final var bitValue = value.get(bit);
    if (bitValue == Value.TRUE) return '1';
    if (bitValue == Value.FALSE) return '0';
    return DONT_CARE;
  }

  List<Vector> getVectors() {
    return vectors;
  }

  /** Number of vectors with at least one don't-care response bit. */
  int getDontCareVectorCount() {
    return (int)
        vectors.stream().filter(vector -> vector.response().indexOf(DONT_CARE) >= 0).count();
  }
}
