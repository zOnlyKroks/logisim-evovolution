/*
 * Logisim-evolution - digital logic design tool and simulator
 * Copyright by the Logisim-evolution developers
 *
 * https://github.com/logisim-evolution/
 *
 * This is free software released under GNU GPLv3 license
 */

package com.cburch.logisim.fpga.hdlgenerator.verification;

import com.cburch.logisim.fpga.hdlgenerator.verification.DutInterface.DutPort;
import com.cburch.logisim.fpga.hdlgenerator.verification.HdlTestCase.ValueRange;
import java.math.BigInteger;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Random;

/**
 * Generates the input values applied to a component.
 *
 * <p>If the inputs have few enough combinations, all of them are applied. Otherwise the cartesian
 * product of per-input corner values (zero, one, all ones, the signed extremes, alternating bit
 * patterns, ...) is applied, followed by pseudo-random values. The random sequence is seeded with
 * the test case name, so the vectors are reproducible.
 */
final class StimulusGenerator {

  /** Maximum number of corner value combinations; larger products are sampled. */
  static final int MAX_CORNER_VECTORS = 4096;

  private StimulusGenerator() {}

  /**
   * Returns the stimulus vectors; each entry holds one value per port, in the order of {@code
   * ports}.
   */
  static List<long[]> generate(HdlTestCase testCase, List<DutPort> ports) {
    final var domains = new ArrayList<ValueRange>();
    var combinations = BigInteger.ONE;
    for (final var port : ports) {
      final var range = testCase.getInputRanges().get(port.componentPin());
      final var mask = mask(port.width());
      final var domain =
          range == null
              ? new ValueRange(0, mask)
              : new ValueRange(range.min() & mask, range.max() & mask);
      domains.add(domain);
      combinations = combinations.multiply(size(domain));
    }
    final var random = new Random(testCase.getName().hashCode());
    if (combinations.compareTo(BigInteger.ONE.shiftLeft(testCase.getExhaustiveBits())) <= 0) {
      return exhaustive(domains, combinations.intValueExact());
    }
    final var vectors = corners(ports, domains, random);
    for (var count = 0; count < testCase.getRandomVectors(); count++) {
      final var vector = new long[domains.size()];
      for (var index = 0; index < vector.length; index++) {
        vector[index] = randomValue(domains.get(index), random);
      }
      vectors.add(vector);
    }
    return vectors;
  }

  private static List<long[]> exhaustive(List<ValueRange> domains, int count) {
    final var vectors = new ArrayList<long[]>(count);
    final var vector = new long[domains.size()];
    for (var index = 0; index < vector.length; index++) vector[index] = domains.get(index).min();
    for (var number = 0; number < count; number++) {
      vectors.add(vector.clone());
      // increment the mixed radix counter, lowest port first
      for (var index = 0; index < vector.length; index++) {
        final var domain = domains.get(index);
        if (vector[index] != domain.max()) {
          vector[index]++;
          break;
        }
        vector[index] = domain.min();
      }
    }
    return vectors;
  }

  private static List<long[]> corners(List<DutPort> ports, List<ValueRange> domains, Random random) {
    final var cornerValues = new ArrayList<long[]>();
    var combinations = 1L;
    for (var index = 0; index < ports.size(); index++) {
      final var values = cornerValues(ports.get(index).width(), domains.get(index));
      cornerValues.add(values);
      combinations = Math.min((long) MAX_CORNER_VECTORS + 1, combinations * values.length);
    }
    final var vectors = new ArrayList<long[]>();
    if (combinations <= MAX_CORNER_VECTORS) {
      final var choice = new int[ports.size()];
      for (var number = 0; number < combinations; number++) {
        final var vector = new long[choice.length];
        for (var index = 0; index < choice.length; index++) {
          vector[index] = cornerValues.get(index)[choice[index]];
        }
        vectors.add(vector);
        for (var index = 0; index < choice.length; index++) {
          if (++choice[index] < cornerValues.get(index).length) break;
          choice[index] = 0;
        }
      }
    } else {
      for (var number = 0; number < MAX_CORNER_VECTORS; number++) {
        final var vector = new long[cornerValues.size()];
        for (var index = 0; index < vector.length; index++) {
          final var values = cornerValues.get(index);
          vector[index] = values[random.nextInt(values.length)];
        }
        vectors.add(vector);
      }
    }
    return vectors;
  }

  private static long[] cornerValues(int width, ValueRange domain) {
    final var mask = mask(width);
    final var candidates = new LinkedHashSet<Long>();
    if (domain.min() == 0 && domain.max() == mask) {
      final var signBit = 1L << (width - 1);
      for (final var value :
          new long[] {
            0, 1, 2, 3, mask, mask - 1, signBit, signBit - 1, signBit + 1,
            0x5555555555555555L, 0xAAAAAAAAAAAAAAAAL
          }) {
        candidates.add(value & mask);
      }
    } else {
      final var min = domain.min();
      final var max = domain.max();
      final var middle = min + ((max - min) >>> 1);
      for (final var value : new long[] {min, min + 1, middle, max - 1, max}) {
        if (Long.compareUnsigned(value, min) >= 0 && Long.compareUnsigned(value, max) <= 0) {
          candidates.add(value);
        }
      }
    }
    return candidates.stream().mapToLong(Long::longValue).toArray();
  }

  private static long randomValue(ValueRange domain, Random random) {
    final var span = domain.max() - domain.min() + 1;
    // a span of zero means the domain covers all 64 bit values
    return span == 0 ? random.nextLong() : domain.min() + Long.remainderUnsigned(random.nextLong(), span);
  }

  private static BigInteger size(ValueRange domain) {
    return new BigInteger(Long.toUnsignedString(domain.max() - domain.min())).add(BigInteger.ONE);
  }

  static long mask(int width) {
    return width >= 64 ? -1L : (1L << width) - 1;
  }
}
