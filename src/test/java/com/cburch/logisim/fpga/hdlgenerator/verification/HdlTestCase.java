/*
 * Logisim-evolution - digital logic design tool and simulator
 * Copyright by the Logisim-evolution developers
 *
 * https://github.com/logisim-evolution/
 *
 * This is free software released under GNU GPLv3 license
 */

package com.cburch.logisim.fpga.hdlgenerator.verification;

import com.cburch.logisim.data.Attribute;
import com.cburch.logisim.data.AttributeSet;
import com.cburch.logisim.data.BitWidth;
import com.cburch.logisim.fpga.hdlgenerator.HdlGeneratorFactory;
import com.cburch.logisim.instance.InstanceFactory;
import com.cburch.logisim.instance.StdAttr;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import java.util.regex.Pattern;

/**
 * One configuration of a Logisim component whose generated HDL is checked against the Logisim
 * simulation of the same component.
 *
 * <p>Build instances with {@link #of(String, InstanceFactory)}, e.g.
 *
 * <pre>
 * HdlTestCase.of("adder_w8", new Adder()).width(8).floating(Adder.C_IN).build();
 * </pre>
 */
public final class HdlTestCase {

  /** Default number of stimulus bits up to which all input combinations are applied. */
  public static final int DEFAULT_EXHAUSTIVE_BITS = 12;

  /** Default number of random vectors applied in addition to the corner cases. */
  public static final int DEFAULT_RANDOM_VECTORS = 1000;

  // Test case names are used as VHDL library and directory names.
  private static final Pattern NAME_PATTERN = Pattern.compile("[a-z][a-z0-9]*(_[a-z0-9]+)*");

  /** Inclusive range of (unsigned) values applied to an input pin. */
  public record ValueRange(long min, long max) {}

  private final String name;
  private final InstanceFactory factory;
  private final AttributeSet attributes;
  private final HdlGeneratorFactory generator;
  private final Set<Integer> floatingInputs;
  private final Map<Integer, ValueRange> inputRanges;
  private final int exhaustiveBits;
  private final int randomVectors;
  private final Map<String, String> knownIssues;

  private HdlTestCase(Builder builder) {
    name = builder.name;
    factory = builder.factory;
    attributes = builder.attributes;
    generator =
        builder.generator != null ? builder.generator : factory.getHDLGenerator(attributes);
    floatingInputs = Collections.unmodifiableSet(new TreeSet<>(builder.floatingInputs));
    inputRanges = Collections.unmodifiableMap(new HashMap<>(builder.inputRanges));
    exhaustiveBits = builder.exhaustiveBits;
    randomVectors = builder.randomVectors;
    knownIssues = Collections.unmodifiableMap(new HashMap<>(builder.knownIssues));
    if (generator == null) {
      throw new IllegalArgumentException(
          "Component " + factory.getName() + " has no HDL generator for test case " + name);
    }
  }

  /** Starts a test case for a component with its default attributes. */
  public static Builder of(String name, InstanceFactory factory) {
    return new Builder(name, factory);
  }

  public String getName() {
    return name;
  }

  public InstanceFactory getFactory() {
    return factory;
  }

  public AttributeSet getAttributes() {
    return attributes;
  }

  public HdlGeneratorFactory getGenerator() {
    return generator;
  }

  /** Component pins that are left unconnected, so the netlist ties them off. */
  public Set<Integer> getFloatingInputs() {
    return floatingInputs;
  }

  /** Value ranges for component pins; pins without a range receive arbitrary bit patterns. */
  public Map<Integer, ValueRange> getInputRanges() {
    return inputRanges;
  }

  public int getExhaustiveBits() {
    return exhaustiveBits;
  }

  public int getRandomVectors() {
    return randomVectors;
  }

  /**
   * Returns the description of a known deviation of the generated HDL, or {@code null}.
   *
   * @param hdlType {@link HdlGeneratorFactory#VHDL} or {@link HdlGeneratorFactory#VERILOG}
   */
  public String getKnownIssue(String hdlType) {
    return knownIssues.get(hdlType);
  }

  /** Human readable description of the component, its width and its non-default attributes. */
  public String getDescription() {
    final var defaults = factory.createAttributeSet();
    final var settings = new ArrayList<String>();
    for (final var attr : attributes.getAttributes()) {
      final var value = attributes.getValue(attr);
      if (value == null || attr == StdAttr.LABEL) continue;
      final var relevant =
          attr == StdAttr.WIDTH
              || (attr.isToSave() && !attr.isHidden() && !value.equals(defaults.getValue(attr)));
      if (!relevant) continue;
      @SuppressWarnings("unchecked")
      final var typed = (Attribute<Object>) attr;
      settings.add(attr.getName() + "=" + typed.toStandardString(value));
    }
    final var description = new StringBuilder(factory.getName());
    if (!settings.isEmpty()) description.append(" (").append(String.join(", ", settings)).append(')');
    if (!floatingInputs.isEmpty()) {
      description.append(" with unconnected pins ").append(floatingInputs);
    }
    return description.toString();
  }

  @Override
  public String toString() {
    return name;
  }

  /** Builder for {@link HdlTestCase}. */
  public static final class Builder {
    private final String name;
    private final InstanceFactory factory;
    private final AttributeSet attributes;
    private HdlGeneratorFactory generator;
    private final Set<Integer> floatingInputs = new TreeSet<>();
    private final Map<Integer, ValueRange> inputRanges = new HashMap<>();
    private int exhaustiveBits = DEFAULT_EXHAUSTIVE_BITS;
    private int randomVectors = DEFAULT_RANDOM_VECTORS;
    private final Map<String, String> knownIssues = new HashMap<>();

    private Builder(String name, InstanceFactory factory) {
      if (!NAME_PATTERN.matcher(name).matches()) {
        throw new IllegalArgumentException(
            "Test case name '" + name + "' must match " + NAME_PATTERN.pattern());
      }
      this.name = name;
      this.factory = factory;
      this.attributes = factory.createAttributeSet();
    }

    /** Sets an attribute of the component. */
    public <E> Builder set(Attribute<E> attr, E value) {
      attributes.setValue(attr, value);
      return this;
    }

    /** Sets the {@link StdAttr#WIDTH} attribute of the component. */
    public Builder width(int bits) {
      return set(StdAttr.WIDTH, BitWidth.create(bits));
    }

    /**
     * Uses the given generator instead of the one registered with the component, e.g. to verify a
     * generator before it is enabled.
     */
    public Builder generator(HdlGeneratorFactory generator) {
      this.generator = generator;
      return this;
    }

    /**
     * Leaves the given component pins unconnected. The Logisim model sees a floating value and the
     * harness ties the HDL port off the same way the netlist does for an unconnected pin.
     */
    public Builder floating(int... componentPins) {
      for (final var pin : componentPins) floatingInputs.add(pin);
      return this;
    }

    /** Restricts the (unsigned) values applied to a component pin to {@code [min, max]}. */
    public Builder inputRange(int componentPin, long min, long max) {
      if (Long.compareUnsigned(min, max) > 0) {
        throw new IllegalArgumentException("Empty input range for pin " + componentPin);
      }
      inputRanges.put(componentPin, new ValueRange(min, max));
      return this;
    }

    /** Applies all input combinations if the stimulus has at most this many bits. */
    public Builder exhaustiveUpTo(int bits) {
      exhaustiveBits = bits;
      return this;
    }

    /** Number of random vectors applied in addition to the corner cases. */
    public Builder randomVectors(int count) {
      randomVectors = count;
      return this;
    }

    /**
     * Marks the generated VHDL and Verilog as known to deviate from the simulation, see {@link
     * #knownVhdlIssue}.
     */
    public Builder knownIssue(String description) {
      return knownVhdlIssue(description).knownVerilogIssue(description);
    }

    /**
     * Marks the generated VHDL as known to deviate from the simulation. The test case still runs,
     * but its failure is reported as expected; it fails if it passes, so fixed issues get noticed.
     */
    public Builder knownVhdlIssue(String description) {
      knownIssues.put(HdlGeneratorFactory.VHDL, description);
      return this;
    }

    /** Marks the generated Verilog as known to deviate from the simulation. */
    public Builder knownVerilogIssue(String description) {
      knownIssues.put(HdlGeneratorFactory.VERILOG, description);
      return this;
    }

    public HdlTestCase build() {
      return new HdlTestCase(this);
    }
  }
}
