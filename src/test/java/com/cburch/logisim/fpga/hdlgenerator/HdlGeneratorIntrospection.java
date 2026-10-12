/*
 * Logisim-evolution - digital logic design tool and simulator
 * Copyright by the Logisim-evolution developers
 *
 * https://github.com/logisim-evolution/
 *
 * This is free software released under GNU GPLv3 license
 */

package com.cburch.logisim.fpga.hdlgenerator;

import com.cburch.logisim.data.AttributeSet;
import com.cburch.logisim.fpga.designrulecheck.Netlist;
import com.cburch.logisim.instance.Port;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.TreeSet;

/**
 * Read-only view on the port and parameter tables of an {@link AbstractHdlGeneratorFactory}.
 *
 * <p>The HDL verification suite uses it to instantiate a generated entity/module in a test harness
 * without building a netlist around the component. It lives in this package because the tables
 * are protected members of the generator.
 */
public final class HdlGeneratorIntrospection {

  /**
   * One port of a generated entity/module.
   *
   * @param name HDL name of the port
   * @param direction one of {@code Port.INPUT}, {@code Port.OUTPUT} or {@code Port.INOUT}
   * @param declaredBits the raw width as stored by the generator: {@code 1} is a scalar, {@code 0}
   *     a one-bit vector, a positive value a fixed width vector and a negative value the id of the
   *     generic/parameter holding the width
   * @param widthParameter name of the generic/parameter holding the width, or {@code null}
   * @param componentPinId index of the Logisim component pin, or {@code -1} for fixed mapped ports
   * @param fixedMap the fixed mapping for ports without a component pin, or {@code null}
   * @param pullDownOnFloat whether the netlist ties the port to zero (instead of one) when its pin
   *     is not connected
   * @param clock whether the port is a clock port
   */
  public record PortDeclaration(
      String name,
      String direction,
      int declaredBits,
      String widthParameter,
      int componentPinId,
      String fixedMap,
      boolean pullDownOnFloat,
      boolean clock) {}

  private HdlGeneratorIntrospection() {}

  /** Returns the ports of the generated entity/module, sorted by name as in the declaration. */
  public static List<PortDeclaration> getPorts(
      AbstractHdlGeneratorFactory generator, Netlist nets, AttributeSet attrs) {
    if (generator.getWiresPortsDuringHDLWriting) {
      generator.myWires.removeWires();
      generator.myTypedWires.clear();
      generator.myPorts.removePorts();
      generator.getGenerationTimeWiresPorts(nets, attrs);
    }
    final var ports = generator.myPorts;
    final var result = new ArrayList<PortDeclaration>();
    for (final var name : new TreeSet<>(ports.keySet())) {
      final var declaredBits = ports.get(name, attrs);
      final var widthParameter =
          declaredBits < 0 ? generator.myParametersList.get(declaredBits, attrs) : null;
      final var fixedMapped = ports.isFixedMapped(name);
      result.add(
          new PortDeclaration(
              name,
              direction(ports, name),
              declaredBits,
              widthParameter,
              fixedMapped ? -1 : ports.getComponentPortId(name),
              fixedMapped ? ports.getFixedMap(name) : null,
              ports.doPullDownOnFloat(name),
              ports.isClock(name)));
    }
    return result;
  }

  /** Returns the generic/parameter values the generator uses for a component instance. */
  public static Map<String, String> getParameterMap(
      AbstractHdlGeneratorFactory generator, AttributeSet attrs) {
    return generator.getParameterMap(attrs);
  }

  private static String direction(HdlPorts ports, String name) {
    for (final var type : List.of(Port.INPUT, Port.OUTPUT, Port.INOUT)) {
      if (ports.keySet(type).contains(name)) return type;
    }
    throw new IllegalStateException("Port " + name + " has no direction");
  }
}
