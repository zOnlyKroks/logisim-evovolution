[![Logisim-evolution](img/logisim-evolution-logo.png)](https://github.com/logisim-evolution/logisim-evolution)

---

# HDL verification suite #

* [« Go back](developers.md)
* **HDL verification suite**
  * [Overview](#overview)
  * [Requirements](#requirements)
  * [Running the suite](#running-the-suite)
  * [Reading the results](#reading-the-results)
  * [How a test case is built](#how-a-test-case-is-built)
  * [Adding test cases](#adding-test-cases)
  * [Known issues](#known-issues)
  * [Limitations](#limitations)

---

## Overview ##

The HDL verification suite checks that the VHDL and Verilog which Logisim-evolution generates for a component behave
like the component does in the Logisim simulator. For every test case it

1. generates the entity/module with the component's own HDL generator, exactly as the FPGA flow does,
2. computes test vectors by running the component's simulation model (`InstanceFactory.propagate()`) on the stimulus,
3. simulates the generated HDL in a self-checking test bench that applies the stimulus and compares every response with the
   one computed by the simulation model.

The Logisim simulation model is the reference: the suite does not need a hand-written behavioral HDL model per component,
and it checks exactly the behavior users see in the simulator, including details such as how an unconnected input is
treated.

VHDL is run with [VUnit](https://vunit.github.io/) and any simulator VUnit supports (GHDL, NVC, Questa, Riviera-PRO, ...).
VUnit also ships [OSVVM](https://osvvm.org/), which is available to future test benches, for example for constrained-random
stimulus of clocked components. VUnit does not support Icarus Verilog, so the Verilog part has a small Python runner of its
own.

## Requirements ##

* Python 3 with VUnit: `python -m pip install vunit_hdl`
* For VHDL: a simulator supported by VUnit, e.g. [GHDL](https://github.com/ghdl/ghdl) or [NVC](https://www.nickg.me.uk/nvc/),
  on the `PATH`. VUnit picks the first one it finds; set `VUNIT_SIMULATOR` (e.g. `ghdl` or `nvc`) to choose.
* For Verilog: [Icarus Verilog](https://steveicarus.github.io/iverilog/) (`iverilog` and `vvp`) on the `PATH`.

On Debian or Ubuntu, `sudo apt-get install ghdl iverilog` installs both simulators. The GitHub workflow
[`hdl-verification.yml`](../.github/workflows/hdl-verification.yml) runs the suite on every pull request that touches Java
sources.

## Running the suite ##

```bash
./gradlew hdlVerification          # VHDL and Verilog
./gradlew hdlVerificationVhdl      # only VHDL
./gradlew hdlVerificationVerilog   # only Verilog
```

Optional project properties:

* `-PhdlCases=<regex>` only generates the test cases whose name matches, e.g. `-PhdlCases=^adder`.
* `-PhdlPython=<python>` selects the Python interpreter that has VUnit installed (default `python3`, or `python` on Windows).

The task `generateHdlVerificationSuite` only writes the suite to `build/hdl-verification/suite`, which needs neither
Python nor a simulator. The runners can then be started by hand, which gives access to all VUnit options, e.g. to open
the waveform viewer for one test case:

```bash
python src/test/hdl/run_vunit.py --suite build/hdl-verification/suite/vhdl --gui "adder_w8.*"
python src/test/hdl/run_iverilog.py --suite build/hdl-verification/suite/verilog "adder_*"
```

## Reading the results ##

Each runner ends with a summary such as

```text
VHDL HDL verification: 85 passed, 0 failed, 32 known issues, 0 known issues passing
```

and writes a JUnit XML report to `build/test-results/hdlVerificationVhdl` and `build/test-results/hdlVerificationVerilog`.
For every deviating vector (up to ten per test case) the test bench prints the inputs and the expected and actual outputs,
port by port:

```text
ERROR - Response deviates from the Logisim simulation (vector file line 520)
          inputs   : dataA=00000001, shiftAmount=010
          expected : result=00000000
          actual   : result=11000000
ERROR - Number of vectors (out of 2048) where the generated HDL deviates from the Logisim simulation - Got 896. Expected 0.
```

A test case whose generated VHDL does not compile fails as well, without hiding the results of the other test cases.

The generated files of a test case are in `build/hdl-verification/suite/<vhdl|verilog>/<test case>/`:

* `dut.vhd`/`dut.v`: the entity/module exactly as the HDL generator produced it,
* `harness.vhd`/`harness.v`: the glue described below,
* `vectors.txt`: the stimulus and expected response of every vector; the header names the bits of each port.

## How a test case is built ##

The generic test benches [`tb_logisim_component.vhd`](../src/test/hdl/vhdl/tb_logisim_component.vhd) and
[`tb_logisim_component.v`](../src/test/hdl/verilog/tb_logisim_component.v) are the same for all test cases. They read the
vector file, drive a flat `stimulus` vector, wait, and compare the flat `response` vector with the expected one. Expected
bits the simulation model drives as unknown or error are don't-care bits (`-` in VHDL, `x` in Verilog).

For every test case the suite generates a harness, `logisim_dut_harness`, that instantiates the generated entity/module with
the generic/parameter values the FPGA flow would use and connects its ports to slices of `stimulus` and `response`, in
the alphabetical order of the port declaration. The VHDL harness also contains the package `logisim_dut_info`, which
describes this layout for the error messages.

The ports and their mapping to the component's pins are read from the generator's port table, and each port width is
checked against the width of the Logisim pin, so port mapping errors are reported while generating the suite.

The stimulus is chosen as follows:

* If the inputs have at most 4096 (2^12) combinations, all of them are applied.
* Otherwise all combinations of per-input corner values (0, 1, 2, 3, all ones, the two's complement extremes, alternating
  bit patterns) are applied, followed by 1000 pseudo-random vectors. The random sequence is seeded with the test case name,
  so the vectors are the same in every run.

Inputs can be left unconnected in a test case. The simulation model then sees a floating value, and the harness ties the
HDL port to the constant the netlist uses for unconnected pins. This checks that, for example, an unconnected carry input
means the same thing in the simulator and in the generated HDL.

The Java side lives in the test package [`com.cburch.logisim.fpga.hdlgenerator.verification`][verification-package];
[`HdlVerificationSuiteTest`][suite-test] checks that every test case can be generated and is part of the normal
`./gradlew test` run.

[verification-package]: ../src/test/java/com/cburch/logisim/fpga/hdlgenerator/verification
[suite-test]: ../src/test/java/com/cburch/logisim/fpga/hdlgenerator/verification/HdlVerificationSuiteTest.java

## Adding test cases ##

Test cases are contributed per library by a class in the library's (test) package, so they can use package private
attributes; see [`ArithmeticHdlVerificationCases`][arithmetic-cases]. Register a new class in
[`HdlVerificationCatalog`][catalog].

[arithmetic-cases]: ../src/test/java/com/cburch/logisim/std/arith/ArithmeticHdlVerificationCases.java
[catalog]: ../src/test/java/com/cburch/logisim/fpga/hdlgenerator/verification/HdlVerificationCatalog.java

```java
cases.add(
    HdlTestCase.of("subtractor_open_borrow_w8", new Subtractor()) // unique name: lower case, digits, '_'
        .width(8)                                                   // shortcut for set(StdAttr.WIDTH, ...)
        .floating(Subtractor.B_IN)                                  // leave the borrow input unconnected
        .build());
```

Further builder options:

* `set(attribute, value)` sets any other attribute of the component.
* `inputRange(pin, min, max)` restricts the values applied to an input, e.g. when the simulation model is too slow for
  arbitrary values.
* `exhaustiveUpTo(bits)` and `randomVectors(count)` change how many vectors are applied.
* `generator(factory)` verifies a generator that is not registered with the component yet. This allows a generator to be
  developed against the simulator before HDL support is enabled for users, e.g.
  `HdlTestCase.of("divider_w8", new Divider()).width(8).generator(new DividerHdlGeneratorFactory()).build()`.

Cover the cases where generators usually differ: a width of one (many generators use scalar ports then), small widths that
are verified exhaustively, odd widths, the 64 bit limit of the simulator, every mode attribute, and unconnected optional
inputs.

## Known issues ##

When the suite finds a deviation that is not fixed in the same change, mark the test case with `knownVhdlIssue(...)`,
`knownVerilogIssue(...)` or `knownIssue(...)` (both languages) and a short description of the cause. Such a test case still
runs; its failure is reported as a known issue and does not fail the build. If it passes, the build fails with a request
to remove the marker, so fixed generators are noticed and stay covered.

## Limitations ##

* Only components whose generator produces an entity/module (subclasses of `AbstractHdlGeneratorFactory`) are supported,
  not inlined generators such as the gates.
* Only combinational components are supported so far. Clocked components need test vectors per clock cycle.
* The suite simulates the HDL; it does not synthesize it. A synthesis check (e.g. with GHDL's synthesis or Yosys) and test
  circuits for FPGA boards would complement it.
