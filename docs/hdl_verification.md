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

Only Python 3 (`python3`, or `python` on Windows) needs to be installed. On Windows and Linux (x86-64), the task
`setUpHdlVerification`, which the other tasks depend on, sets up everything else in `.gradle/hdl-verification`, without
touching the system:

* a Python environment with [VUnit](https://vunit.github.io/), see [`requirements.txt`](../src/test/hdl/requirements.txt),
* [GHDL](https://github.com/ghdl/ghdl) for VHDL, from the GHDL release archives,
* [Icarus Verilog](https://steveicarus.github.io/iverilog/) for Verilog, from conda-forge (installed with micromamba).

The downloads are pinned in [`setup_environment.py`](../src/test/hdl/setup_environment.py) and their checksums are
verified. They are downloaded once; later runs reuse them, also after `./gradlew clean`.

On Linux the downloaded GHDL needs glibc 2.38 or newer (e.g. Ubuntu 24.04, Debian 13, Fedora 39). Where it does not run,
or on other systems, install the simulators yourself and use them with `-PhdlSimulators=system` (see below):

* Debian or Ubuntu: `sudo apt-get install ghdl iverilog`
* Windows: `winget install ghdl.ghdl.ucrt64.mcode` and `winget install Icarus.Verilog`

VUnit can also use other simulators, e.g. [NVC](https://www.nickg.me.uk/nvc/); set `VUNIT_SIMULATOR` (e.g. `nvc`) to
choose one.

No local installation is needed to run the suite on GitHub: the workflow
[`hdl-verification.yml`](../.github/workflows/hdl-verification.yml) runs it on every pull request that touches Java
sources, and it can be started manually in the repository's *Actions* tab ("HDL verification", *Run workflow*) or with
`gh workflow run hdl-verification.yml -f language=vhdl -f cases='^adder'`. The manual run can be restricted to one HDL
and to the test cases matching a regular expression. The results appear in the job summary, and the JUnit reports and
the generated suite are attached to the run as an artifact. GitHub only offers manual runs for workflows on the
repository's default branch.

## Running the suite ##

```bash
./gradlew hdlVerification          # VHDL and Verilog
./gradlew hdlVerificationVhdl      # only VHDL
./gradlew hdlVerificationVerilog   # only Verilog
```

Optional project properties, which can also be set once per machine in `~/.gradle/gradle.properties` (e.g.
`hdlPath=C:\\ghdl\\bin;C:\\iverilog\\bin`):

* `-PhdlCases=<regex>` only generates the test cases whose name matches, e.g. `-PhdlCases=^adder`.
* `-PhdlSimulators=system` uses the simulators on the `PATH` instead of downloading them.
* `-PhdlPath=<directories>` searches these directories for the simulators first. Separate them with the platform's path
  separator (`;` on Windows, `:` elsewhere).
* `-PhdlPython=<python>` uses this Python interpreter, which must have VUnit installed, instead of the Python environment
  set up by `setUpHdlVerification`.

Restart the IDE and stop the Gradle daemon (`./gradlew --stop`) after changing the `PATH`, as both keep the old one.

The task `generateHdlVerificationSuite` only writes the suite to `build/hdl-verification/suite`, which needs neither
Python nor a simulator. The runners can then be started by hand, which gives access to all VUnit options, e.g. to open
the waveform viewer for one test case:

```bash
python src/test/hdl/run_vunit.py --suite build/hdl-verification/suite/vhdl --gui "adder_w8.*"
python src/test/hdl/run_iverilog.py --suite build/hdl-verification/suite/verilog "adder_*"
```

## Reading the results ##

If everything works, each runner prints a single line:

```text
VHDL: all 117 test cases match the Logisim simulation
Verilog: all 117 test cases match the Logisim simulation (60 known issues)
```

Only failing test cases are reported in detail: the first deviating vectors with the inputs and the expected and actual
outputs, port by port, the number of deviating vectors, or the compiler errors:

```text
VHDL: 1 of 117 test cases FAILED

  shifter_sra_w8
    Response deviates from the Logisim simulation (vector file line 520)
      inputs   : dataA=00000001, shiftAmount=010
      expected : result=00000000
      actual   : result=11000000
    ...
    Number of vectors (out of 2048) where the generated HDL deviates from the Logisim simulation - Got 896. Expected 0.
```

A test case whose generated VHDL does not compile fails as well, without hiding the results of the other test cases. The
complete logs are in `build/hdl-verification/vunit_out/vunit.log` and `build/hdl-verification/iverilog_out/<test case>/log.txt`,
and the JUnit XML reports in `build/test-results/hdlVerificationVhdl` and `build/test-results/hdlVerificationVerilog`.

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
