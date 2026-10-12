//
// Logisim-evolution - digital logic design tool and simulator
// Copyright by the Logisim-evolution developers
//
// https://github.com/logisim-evolution/
//
// This is free software released under GNU GPLv3 license
//

// Generic self-checking test bench for the Verilog that Logisim-evolution generates for a
// component.
//
// The HDL verification suite compiles this test bench once per test case, together with the
// generated module and the design-specific module 'logisim_dut_harness'. The harness instantiates
// the generated module with all driven inputs packed into 'stimulus' and all outputs packed into
// 'response', and provides the task 'describe' to print vectors port by port.
//
// The vector file (+vectors=<file>) holds one stimulus and the response computed by the Logisim
// simulation model per line. The test bench applies every stimulus and checks the response of the
// generated HDL against it, treating 'x' bits (unknown or error in the simulation model) as don't
// care. The last line printed is "PASSED: ..." or "FAILED: ...".

`timescale 1ns / 1ps

module tb_logisim_component;

  // set with iverilog -P tb_logisim_component.STIMULUS_BITS=... etc.
  parameter STIMULUS_BITS = 1;
  parameter RESPONSE_BITS = 1;
  parameter MAX_REPORTED_MISMATCHES = 10;
  localparam END_OF_FILE = -1;

  reg  [STIMULUS_BITS-1:0] stimulus;
  wire [RESPONSE_BITS-1:0] response;
  reg  [RESPONSE_BITS-1:0] expected;

  reg [8*1024-1:0] vector_file;
  integer file, character, fields, line_number, vector_count, mismatch_count, bit_index;
  reg deviates;

  logisim_dut_harness dut (.stimulus(stimulus), .response(response));

  initial begin
    if (!$value$plusargs("vectors=%s", vector_file)) begin
      $display("FAILED: no vector file given, use +vectors=<file>");
      $finish;
    end
    file = $fopen(vector_file, "r");
    if (file == 0) begin
      $display("FAILED: cannot open %0s", vector_file);
      $finish;
    end

    stimulus = 0;
    line_number = 1;
    vector_count = 0;
    mismatch_count = 0;
    // The file is parsed character by character, so lines of any length are supported.
    character = $fgetc(file);
    while (character != END_OF_FILE) begin
      if (character == "#") begin
        // comment, skip the rest of the line
        while (character != "\n" && character != END_OF_FILE) character = $fgetc(file);
      end else if (character != "\n" && character != "\r" && character != " ") begin
        // a vector, hand its first character back to $fscanf
        fields = $ungetc(character, file);
        fields = $fscanf(file, "%b %b", stimulus, expected);
        if (fields != 2) begin
          $display("ERROR: malformed line %0d in %0s", line_number, vector_file);
          mismatch_count = mismatch_count + 1;
          character = END_OF_FILE;
        end else begin
          #10;
          deviates = 0;
          for (bit_index = 0; bit_index < RESPONSE_BITS; bit_index = bit_index + 1) begin
            if (expected[bit_index] !== 1'bx && response[bit_index] !== expected[bit_index])
              deviates = 1;
          end
          if (deviates) begin
            mismatch_count = mismatch_count + 1;
            if (mismatch_count <= MAX_REPORTED_MISMATCHES) begin
              $display("ERROR: response deviates from the Logisim simulation (vector file line %0d)",
                       line_number);
              dut.describe(stimulus, expected, response);
            end
          end
          vector_count = vector_count + 1;
          character = $fgetc(file);
        end
      end
      if (character == "\n") line_number = line_number + 1;
      if (character != END_OF_FILE) character = $fgetc(file);
    end
    $fclose(file);

    if (vector_count == 0)
      $display("FAILED: no vectors found in %0s", vector_file);
    else if (mismatch_count != 0)
      $display("FAILED: %0d of %0d vectors deviate from the Logisim simulation",
               mismatch_count, vector_count);
    else
      $display("PASSED: %0d vectors match the Logisim simulation", vector_count);
    $finish;
  end

endmodule
