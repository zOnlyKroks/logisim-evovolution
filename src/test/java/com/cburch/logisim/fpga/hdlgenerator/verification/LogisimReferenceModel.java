/*
 * Logisim-evolution - digital logic design tool and simulator
 * Copyright by the Logisim-evolution developers
 *
 * https://github.com/logisim-evolution/
 *
 * This is free software released under GNU GPLv3 license
 */

package com.cburch.logisim.fpga.hdlgenerator.verification;

import com.cburch.logisim.circuit.Circuit;
import com.cburch.logisim.circuit.CircuitState;
import com.cburch.logisim.comp.Component;
import com.cburch.logisim.data.Attribute;
import com.cburch.logisim.data.AttributeSet;
import com.cburch.logisim.data.Location;
import com.cburch.logisim.data.Value;
import com.cburch.logisim.instance.Instance;
import com.cburch.logisim.instance.InstanceData;
import com.cburch.logisim.instance.InstanceFactory;
import com.cburch.logisim.instance.InstanceState;
import com.cburch.logisim.instance.Port;
import com.cburch.logisim.proj.Project;
import java.util.HashMap;
import java.util.Map;
import java.util.Set;

/**
 * Computes the responses of a combinational Logisim component by running its simulation model
 * ({@link InstanceFactory#propagate}) on a plain port-value map, without building a circuit.
 *
 * <p>Floating pins are unconnected: the model sees an unknown value of the pin's width and {@link
 * InstanceState#isPortConnected} returns {@code false} for them.
 */
final class LogisimReferenceModel {

  private final InstanceFactory factory;
  private final Component component;
  private final Instance instance;
  private final Set<Integer> floatingPins;

  LogisimReferenceModel(InstanceFactory factory, AttributeSet attrs, Set<Integer> floatingPins) {
    this.factory = factory;
    this.floatingPins = floatingPins;
    component = factory.createComponent(Location.create(0, 0, false), attrs);
    instance = Instance.getInstanceFor(component);
  }

  /** Width of a component pin in bits. */
  int getPinWidth(int pin) {
    return component.getEnd(pin).getWidth().getWidth();
  }

  /** Number of pins of the component. */
  int getPinCount() {
    return component.getEnds().size();
  }

  /**
   * Propagates the component once and returns the values it drives, indexed by pin. Pins without
   * an input value read as unknown.
   */
  Map<Integer, Value> evaluate(Map<Integer, Value> inputs) {
    final var state = new State(inputs);
    factory.propagate(state);
    return state.outputs;
  }

  private final class State implements InstanceState {
    private final Map<Integer, Value> inputs;
    private final Map<Integer, Value> outputs = new HashMap<>();
    private InstanceData data;

    State(Map<Integer, Value> inputs) {
      this.inputs = inputs;
    }

    @Override
    public void fireInvalidated() {}

    @Override
    public AttributeSet getAttributeSet() {
      return instance.getAttributeSet();
    }

    @Override
    public <E> E getAttributeValue(Attribute<E> attr) {
      return instance.getAttributeValue(attr);
    }

    @Override
    public InstanceData getData() {
      return data;
    }

    @Override
    public InstanceFactory getFactory() {
      return factory;
    }

    @Override
    public Instance getInstance() {
      return instance;
    }

    @Override
    public int getPortIndex(Port port) {
      return instance.getPorts().indexOf(port);
    }

    @Override
    public Value getPortValue(int portIndex) {
      final var value = inputs.get(portIndex);
      return value != null ? value : Value.createUnknown(component.getEnd(portIndex).getWidth());
    }

    @Override
    public Project getProject() {
      return null;
    }

    @Override
    public int getTickCount() {
      return 0;
    }

    @Override
    public boolean isCircuitRoot() {
      return true;
    }

    @Override
    public boolean isPortConnected(int portIndex) {
      return !floatingPins.contains(portIndex);
    }

    @Override
    public CircuitState createCircuitSubstateFor(Circuit circ) {
      throw new UnsupportedOperationException(
          "Subcircuits are not supported by the HDL verification suite");
    }

    @Override
    public void setData(InstanceData value) {
      data = value;
    }

    @Override
    public void setPort(int portIndex, Value value, int delay) {
      outputs.put(portIndex, value);
    }
  }
}
