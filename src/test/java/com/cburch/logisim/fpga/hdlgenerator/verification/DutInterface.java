/*
 * Logisim-evolution - digital logic design tool and simulator
 * Copyright by the Logisim-evolution developers
 *
 * https://github.com/logisim-evolution/
 *
 * This is free software released under GNU GPLv3 license
 */

package com.cburch.logisim.fpga.hdlgenerator.verification;

import com.cburch.logisim.fpga.designrulecheck.Netlist;
import com.cburch.logisim.fpga.hdlgenerator.AbstractHdlGeneratorFactory;
import com.cburch.logisim.fpga.hdlgenerator.HdlGeneratorIntrospection;
import com.cburch.logisim.fpga.hdlgenerator.HdlGeneratorIntrospection.PortDeclaration;
import com.cburch.logisim.fpga.hdlgenerator.HdlPorts;
import com.cburch.logisim.instance.Port;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Map;

/**
 * The interface of a generated entity/module as seen by the test harness.
 *
 * <p>All driven input ports are packed into one stimulus vector and all output ports into one
 * response vector, in the (alphabetical) order of the port declaration, starting at bit 0. Inputs
 * that are unconnected in the test case, or fixed mapped by the generator, are tied off in the
 * harness instead.
 */
final class DutInterface {

  /** Netlist handed to the generators; they only use it for the project name in file headers. */
  static final Netlist NETLIST =
      new Netlist(null) {
        @Override
        public String projName() {
          return "hdl_verification";
        }
      };

  enum Role {
    /** Input driven from the stimulus vector. */
    STIMULUS,
    /** Input tied to a constant (an unconnected pin or a fixed mapped port). */
    TIED,
    /** Output captured in the response vector. */
    RESPONSE
  }

  /**
   * A port of the generated entity/module.
   *
   * @param scalar whether the port is declared as a single bit (std_logic) instead of a vector
   * @param componentPin the Logisim pin, or {@code -1} for fixed mapped ports
   * @param offset position of the port's bit 0 in the stimulus or response vector
   * @param tieHigh for {@link Role#TIED} ports, whether all bits are tied to one instead of zero
   */
  record DutPort(
      String name,
      Role role,
      int width,
      boolean scalar,
      int componentPin,
      int offset,
      boolean tieHigh) {

    int msb() {
      return offset + width - 1;
    }

    /** The tie-off value as bit string, MSB first. */
    String tieOffBits() {
      return (tieHigh ? "1" : "0").repeat(width);
    }
  }

  private final String entityName;
  private final Map<String, String> parameters;
  private final List<DutPort> ports;
  private final int stimulusBits;
  private final int responseBits;

  private DutInterface(
      String entityName,
      Map<String, String> parameters,
      List<DutPort> ports,
      int stimulusBits,
      int responseBits) {
    this.entityName = entityName;
    this.parameters = parameters;
    this.ports = ports;
    this.stimulusBits = stimulusBits;
    this.responseBits = responseBits;
  }

  /**
   * Derives the harness interface of a test case for the currently selected HDL.
   *
   * @throws UnsupportedOperationException if the generator or its ports cannot be tested yet
   * @throws IllegalStateException if the generated ports do not match the Logisim component
   */
  static DutInterface create(HdlTestCase testCase, LogisimReferenceModel model) {
    if (!(testCase.getGenerator() instanceof AbstractHdlGeneratorFactory generator)
        || generator.isOnlyInlined()) {
      throw new UnsupportedOperationException(
          "Only generators producing an entity/module are supported, not "
              + testCase.getGenerator().getClass().getSimpleName());
    }
    final var attrs = testCase.getAttributes();
    final var parameters = HdlGeneratorIntrospection.getParameterMap(generator, attrs);
    final var ports = new ArrayList<DutPort>();
    final var unusedFloatingPins = new HashSet<>(testCase.getFloatingInputs());
    var stimulusBits = 0;
    var responseBits = 0;
    for (final var declaration : HdlGeneratorIntrospection.getPorts(generator, NETLIST, attrs)) {
      final var name = declaration.name();
      if (declaration.clock()) {
        throw new UnsupportedOperationException("Clocked components are not supported yet (" + name + ")");
      }
      final var width = getWidth(declaration, parameters);
      final var scalar = declaration.declaredBits() == 1;
      final var pin = declaration.componentPinId();
      if (pin >= 0 && model.getPinWidth(pin) != width) {
        throw new IllegalStateException(
            String.format(
                "HDL port %s has %d bits, but the Logisim pin %d it is mapped to has %d bits",
                name, width, pin, model.getPinWidth(pin)));
      }
      switch (declaration.direction()) {
        case Port.INPUT -> {
          if (pin < 0) {
            ports.add(new DutPort(name, Role.TIED, width, scalar, pin, 0, isPullUp(declaration)));
          } else if (unusedFloatingPins.remove(pin)) {
            final var tieHigh = !declaration.pullDownOnFloat();
            ports.add(new DutPort(name, Role.TIED, width, scalar, pin, 0, tieHigh));
          } else {
            ports.add(new DutPort(name, Role.STIMULUS, width, scalar, pin, stimulusBits, false));
            stimulusBits += width;
          }
        }
        case Port.OUTPUT -> {
          if (pin < 0) {
            throw new UnsupportedOperationException("Output " + name + " has no component pin");
          }
          ports.add(new DutPort(name, Role.RESPONSE, width, scalar, pin, responseBits, false));
          responseBits += width;
        }
        default -> throw new UnsupportedOperationException("Inout ports are not supported (" + name + ")");
      }
    }
    if (!unusedFloatingPins.isEmpty()) {
      throw new IllegalArgumentException(
          "Floating pins " + unusedFloatingPins + " are not inputs of the generated HDL");
    }
    if (stimulusBits == 0 || responseBits == 0) {
      throw new UnsupportedOperationException("Components without inputs or outputs are not supported");
    }
    final var entityName = testCase.getFactory().getHDLName(attrs);
    return new DutInterface(
        entityName, parameters, Collections.unmodifiableList(ports), stimulusBits, responseBits);
  }

  private static int getWidth(PortDeclaration declaration, Map<String, String> parameters) {
    final var declaredBits = declaration.declaredBits();
    if (declaredBits >= 0) return Math.max(declaredBits, 1);
    final var value = parameters.get(declaration.widthParameter());
    try {
      return Integer.parseInt(value.trim());
    } catch (NullPointerException | NumberFormatException e) {
      throw new IllegalStateException(
          String.format(
              "Width of port %s refers to generic %s, which has no integer value (%s)",
              declaration.name(), declaration.widthParameter(), value),
          e);
    }
  }

  private static boolean isPullUp(PortDeclaration declaration) {
    return switch (declaration.fixedMap()) {
      case HdlPorts.PULL_UP -> true;
      case HdlPorts.PULL_DOWN -> false;
      default -> throw new UnsupportedOperationException(
          "Fixed map " + declaration.fixedMap() + " of port " + declaration.name() + " is not supported");
    };
  }

  /** Name of the generated entity/module. */
  String getEntityName() {
    return entityName;
  }

  /** Generic/parameter values of the instance, as HDL literals of the selected HDL. */
  Map<String, String> getParameters() {
    return parameters;
  }

  /** All ports in declaration order. */
  List<DutPort> getPorts() {
    return ports;
  }

  List<DutPort> getPorts(Role role) {
    return ports.stream().filter(port -> port.role() == role).toList();
  }

  int getStimulusBits() {
    return stimulusBits;
  }

  int getResponseBits() {
    return responseBits;
  }
}
