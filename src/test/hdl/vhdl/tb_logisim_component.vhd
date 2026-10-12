--
-- Logisim-evolution - digital logic design tool and simulator
-- Copyright by the Logisim-evolution developers
--
-- https://github.com/logisim-evolution/
--
-- This is free software released under GNU GPLv3 license
--

-- Generic self-checking VUnit test bench for the VHDL that Logisim-evolution generates for a
-- component.
--
-- The HDL verification suite compiles this test bench into one library per test case, together
-- with the generated entity and two design-specific units:
--   * logisim_dut_harness: instantiates the generated entity with all driven inputs packed into
--     the 'stimulus' vector and all outputs packed into the 'response' vector.
--   * logisim_dut_info: the widths of these vectors and functions describing them port by port.
--
-- The vector file holds one stimulus and the response computed by the Logisim simulation model
-- per line. The test bench applies every stimulus and checks the response of the generated HDL
-- against it, treating '-' bits (unknown or error in the simulation model) as don't care.

library ieee;
use ieee.std_logic_1164.all;
use ieee.numeric_std.std_match;

library std;
use std.textio.all;

library vunit_lib;
context vunit_lib.vunit_context;

use work.logisim_dut_info.all;

entity tb_logisim_component is
  generic (
    runner_cfg              : string;
    -- absolute path of the vector file of this test case
    vector_file             : string;
    -- number of mismatching vectors reported in detail
    max_reported_mismatches : natural := 10);
end entity tb_logisim_component;

architecture behavior of tb_logisim_component is

  constant settle_time : time := 10 ns;

  signal stimulus : std_logic_vector(stimulus_bits - 1 downto 0) := (others => '0');
  signal response : std_logic_vector(response_bits - 1 downto 0);

begin

  dut : entity work.logisim_dut_harness
    port map (
      stimulus => stimulus,
      response => response);

  main : process
    file vectors            : text;
    variable vector_line    : line;
    variable next_stimulus  : std_logic_vector(stimulus_bits - 1 downto 0);
    variable expected       : std_logic_vector(response_bits - 1 downto 0);
    variable stimulus_ok    : boolean;
    variable expected_ok    : boolean;
    variable line_number    : natural := 0;
    variable vector_count   : natural := 0;
    variable mismatch_count : natural := 0;
  begin
    test_runner_setup(runner, runner_cfg);
    -- Keep going after a mismatch, so the total number of deviations gets reported.
    set_stop_level(failure);

    while test_suite loop
      if run("generated_hdl_matches_logisim_simulation") then
        info("Checking the generated HDL of " & dut_description);
        file_open(vectors, vector_file, read_mode);
        while not endfile(vectors) loop
          readline(vectors, vector_line);
          line_number := line_number + 1;
          next when vector_line'length = 0 or vector_line(vector_line'left) = '#';

          read(vector_line, next_stimulus, stimulus_ok);
          read(vector_line, expected, expected_ok);
          check(stimulus_ok and expected_ok,
                "Malformed line " & to_string(line_number) & " in " & vector_file);

          stimulus <= next_stimulus;
          wait for settle_time;

          if not std_match(response, expected) then
            mismatch_count := mismatch_count + 1;
            if mismatch_count <= max_reported_mismatches then
              error("Response deviates from the Logisim simulation (vector file line "
                    & to_string(line_number) & ")" & LF
                    & "  inputs   : " & describe_stimulus(next_stimulus) & LF
                    & "  expected : " & describe_response(expected) & LF
                    & "  actual   : " & describe_response(response));
            end if;
          end if;
          vector_count := vector_count + 1;
        end loop;
        file_close(vectors);

        check(vector_count > 0, "No vectors found in " & vector_file);
        check_equal(mismatch_count, 0,
                    "Number of vectors (out of " & to_string(vector_count)
                    & ") where the generated HDL deviates from the Logisim simulation");
        info("Checked " & to_string(vector_count) & " vectors");
      end if;
    end loop;

    test_runner_cleanup(runner);
  end process main;

end architecture behavior;
