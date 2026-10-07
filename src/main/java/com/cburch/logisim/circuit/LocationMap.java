/*
 * Logisim-evolution - digital logic design tool and simulator
 * Copyright by the Logisim-evolution developers
 *
 * https://github.com/logisim-evolution/
 *
 * This is free software released under GNU GPLv3 license
 */

package com.cburch.logisim.circuit;

import com.cburch.logisim.data.Location;

/**
 * Open-addressing map from {@link Location} to a value, for the simulator's per-event lookups.
 * It compares the coordinates directly and stores no per-entry objects, which makes it clearly
 * cheaper than a {@code HashMap<Location, V>} when it is read for every event. Only put and get
 * are supported, and values must not be null.
 */
final class LocationMap<V> {
  private int[] xs;
  private int[] ys;
  private Object[] values;
  private int mask;
  private int size;

  LocationMap() {
    allocate(64);
  }

  private void allocate(int capacity) {
    xs = new int[capacity];
    ys = new int[capacity];
    values = new Object[capacity];
    mask = capacity - 1;
  }

  private static int slot(int x, int y) {
    final var h = x * 0x9E3779B1 ^ y * 0x85EBCA6B;
    return h ^ (h >>> 15);
  }

  @SuppressWarnings("unchecked")
  V get(Location loc) {
    final var x = loc.x;
    final var y = loc.y;
    var i = slot(x, y) & mask;
    Object v;
    while ((v = values[i]) != null) {
      if (xs[i] == x && ys[i] == y) return (V) v;
      i = (i + 1) & mask;
    }
    return null;
  }

  /** Associates the value with the location and returns the previous value, or null. */
  @SuppressWarnings("unchecked")
  V put(Location loc, V value) {
    if (value == null) throw new IllegalArgumentException("null values are not supported");
    if ((size + 1) * 2 > values.length) grow();
    final var x = loc.x;
    final var y = loc.y;
    var i = slot(x, y) & mask;
    while (values[i] != null) {
      if (xs[i] == x && ys[i] == y) {
        final var old = (V) values[i];
        values[i] = value;
        return old;
      }
      i = (i + 1) & mask;
    }
    xs[i] = x;
    ys[i] = y;
    values[i] = value;
    size++;
    return null;
  }

  private void grow() {
    final var oldXs = xs;
    final var oldYs = ys;
    final var oldValues = values;
    allocate(oldValues.length * 2);
    for (var j = 0; j < oldValues.length; j++) {
      if (oldValues[j] == null) continue;
      var i = slot(oldXs[j], oldYs[j]) & mask;
      while (values[i] != null) i = (i + 1) & mask;
      xs[i] = oldXs[j];
      ys[i] = oldYs[j];
      values[i] = oldValues[j];
    }
  }
}
