#!/usr/bin/env python3
#
# Logisim-evolution - digital logic design tool and simulator
# Copyright by the Logisim-evolution developers
#
# https://github.com/logisim-evolution/
#
# This is free software released under GNU GPLv3 license
#

"""Sets up everything the HDL verification suite needs to run, without touching the system.

    python setup_environment.py <directory> <requirements file> [--no-python] [--no-simulators]

Into the given directory (the Gradle task 'setUpHdlVerification' uses .gradle/hdl-verification):
  python/    a virtual environment with the requirements (VUnit)
  ghdl/      GHDL, from the GHDL release archives
  iverilog/  Icarus Verilog, from conda-forge, installed with micromamba

The simulators are downloaded for Windows and Linux on x86-64; the downloads are pinned and their
SHA-256 checksums verified. A simulator that does not run on this system (e.g. GHDL needs glibc
2.38 or newer) is removed again, so a simulator installed on the PATH is used instead. Steps that
are up to date are skipped.
"""

import argparse
import hashlib
import json
import os
import platform
import shutil
import stat
import subprocess
import sys
import tarfile
import tempfile
import urllib.request
import venv
import zipfile
from pathlib import Path

GHDL_VERSION = "6.0.0"
GHDL_DOWNLOADS = {
    "windows": (
        f"https://github.com/ghdl/ghdl/releases/download/v{GHDL_VERSION}/ghdl-mcode-{GHDL_VERSION}-ucrt64.zip",
        "76e160ceec35834c73ada6e4e484416d13aed4b64cbc7c74c5cca53a7ef60e41",
    ),
    "linux": (
        f"https://github.com/ghdl/ghdl/releases/download/v{GHDL_VERSION}/ghdl-mcode-{GHDL_VERSION}-ubuntu24.04-x86_64.tar.gz",
        "30d6a977b8456d140bbafecbbe64b1947a3d92eeae8f5e6d9f528a174f9566e7",
    ),
}

MICROMAMBA_VERSION = "2.9.0-0"
MICROMAMBA_DOWNLOADS = {
    "windows": (
        f"https://github.com/mamba-org/micromamba-releases/releases/download/{MICROMAMBA_VERSION}/micromamba-win-64",
        "a6d804394b2418991c4e29562853eaace2f2ce9d9da661a98e74e02e8dbb44b0",
    ),
    "linux": (
        f"https://github.com/mamba-org/micromamba-releases/releases/download/{MICROMAMBA_VERSION}/micromamba-linux-64",
        "366cd9cd8be14df1ab8ed50352a82111082a36686b2d389fdb79a92c3fafb3e3",
    ),
}
ICARUS_PACKAGE = "iverilog=13_0"

STATE_FILE = "environment.json"


def main():
    parser = argparse.ArgumentParser(description=__doc__.split("\n\n")[0])
    parser.add_argument("directory", type=Path)
    parser.add_argument("requirements", type=Path)
    parser.add_argument("--no-python", action="store_true", help="do not set up the Python environment")
    parser.add_argument("--no-simulators", action="store_true", help="do not download the simulators")
    args = parser.parse_args()

    directory = args.directory.resolve()
    directory.mkdir(parents=True, exist_ok=True)
    state_file = directory / STATE_FILE
    state = json.loads(state_file.read_text(encoding="utf-8")) if state_file.exists() else {}

    def save(key, value):
        """Records a finished step, so it is skipped next time."""
        state[key] = value
        state_file.write_text(json.dumps(state, indent=2) + "\n", encoding="utf-8")

    if not args.no_python:
        requirements = args.requirements.read_text(encoding="utf-8")
        if state.get("python") != requirements or not python_executable(directory / "python").exists():
            set_up_python(directory / "python", args.requirements)
            save("python", requirements)

    if not args.no_simulators:
        system = platform_name()
        if system is None:
            print(
                f"The simulators are only downloaded for Windows and Linux on x86-64, not for "
                f"{platform.system()} on {platform.machine()}. Install GHDL (or NVC) and Icarus Verilog "
                "yourself, see docs/hdl_verification.md."
            )
        else:
            # a version that turned out not to run on this system is not downloaded again
            if state.get("ghdl", {}).get("version") != GHDL_VERSION:
                usable = set_up_ghdl(directory / "ghdl", system)
                save("ghdl", {"version": GHDL_VERSION, "usable": usable})
            if state.get("iverilog", {}).get("version") != ICARUS_PACKAGE:
                usable = set_up_icarus(directory / "iverilog", directory / "micromamba", system)
                save("iverilog", {"version": ICARUS_PACKAGE, "usable": usable})

    save("complete", True)


def platform_name():
    if platform.machine().lower() not in ("amd64", "x86_64"):
        return None
    return {"Windows": "windows", "Linux": "linux"}.get(platform.system())


def python_executable(environment):
    return environment / ("Scripts/python.exe" if os.name == "nt" else "bin/python")


def set_up_python(environment, requirements):
    if not python_executable(environment).exists():
        print(f"Creating a Python environment in {environment}")
        venv.create(environment, with_pip=True)
    print(f"Installing the Python packages of {requirements.name}")
    subprocess.check_call(
        [str(python_executable(environment)), "-m", "pip", "install", "--disable-pip-version-check", "--quiet",
         "-r", str(requirements)]
    )


def set_up_ghdl(target, system):
    url, sha256 = GHDL_DOWNLOADS[system]
    remove(target)
    with tempfile.TemporaryDirectory() as temporary:
        archive = download(url, sha256, Path(temporary))
        extracted = Path(temporary) / "extracted"
        extract(archive, extracted)
        entries = list(extracted.iterdir())
        # the Linux archive has a top level directory, the Windows one does not
        root = entries[0] if len(entries) == 1 and entries[0].is_dir() else extracted
        shutil.move(str(root), str(target))
    ghdl = target / "bin" / ("ghdl.exe" if system == "windows" else "ghdl")
    return check_runs(target, [[str(ghdl), "--version"]], "GHDL")


def set_up_icarus(target, micromamba_directory, system):
    url, sha256 = MICROMAMBA_DOWNLOADS[system]
    remove(target)
    micromamba = micromamba_directory / ("micromamba.exe" if system == "windows" else "micromamba")
    if not micromamba.exists() or file_sha256(micromamba) != sha256:
        micromamba_directory.mkdir(parents=True, exist_ok=True)
        downloaded = download(url, sha256, micromamba_directory)
        downloaded.replace(micromamba)
        micromamba.chmod(micromamba.stat().st_mode | stat.S_IXUSR | stat.S_IXGRP | stat.S_IXOTH)
    root = micromamba_directory / "root"
    print(f"Installing Icarus Verilog ({ICARUS_PACKAGE}) from conda-forge")
    subprocess.check_call(
        [str(micromamba), "create", "--yes", "--quiet", "--root-prefix", str(root), "--prefix", str(target),
         "--channel", "conda-forge", "--override-channels", ICARUS_PACKAGE]
    )
    # the package cache is not needed any more
    remove(root / "pkgs")
    bin_directory = target / "Library" / "bin" if system == "windows" else target / "bin"
    executable = ".exe" if system == "windows" else ""
    # compile and run a module, which also checks the helper programs iverilog starts (ivlpp, ivl)
    with tempfile.TemporaryDirectory() as temporary:
        source = Path(temporary) / "check.v"
        source.write_text('module check; initial $display("ok"); endmodule\n', encoding="utf-8")
        compiled = Path(temporary) / "check.vvp"
        commands = [
            [str(bin_directory / f"iverilog{executable}"), "-o", str(compiled), str(source)],
            [str(bin_directory / f"vvp{executable}"), "-n", str(compiled)],
        ]
        environment = dict(os.environ, PATH=str(bin_directory) + os.pathsep + os.environ.get("PATH", ""))
        return check_runs(target, commands, "Icarus Verilog", environment)


def check_runs(target, commands, name, environment=None):
    """Runs the commands; if one fails, the tool is removed again and False returned."""
    try:
        for command in commands:
            subprocess.run(command, check=True, capture_output=True, timeout=60, env=environment)
        return True
    except (OSError, subprocess.SubprocessError) as error:
        details = getattr(error, "stderr", None) or b""
        print(
            f"The downloaded {name} does not run on this system and is not used: {error}\n"
            f"{details.decode(errors='replace').strip()}\n"
            f"Install {name} with the package manager of your system instead, see docs/hdl_verification.md."
        )
        remove(target)
        return False


def download(url, sha256, directory):
    target = directory / url.rsplit("/", 1)[1]
    print(f"Downloading {url}")
    with urllib.request.urlopen(url, timeout=120) as response, target.open("wb") as file:
        shutil.copyfileobj(response, file)
    actual = file_sha256(target)
    if actual != sha256:
        target.unlink()
        sys.exit(f"Checksum mismatch for {url}: expected {sha256}, got {actual}")
    return target


def file_sha256(path):
    digest = hashlib.sha256()
    with path.open("rb") as file:
        for block in iter(lambda: file.read(1 << 20), b""):
            digest.update(block)
    return digest.hexdigest()


def extract(archive, target):
    if archive.name.endswith(".zip"):
        with zipfile.ZipFile(archive) as zip_file:
            zip_file.extractall(target)
    else:
        with tarfile.open(archive) as tar_file:
            if hasattr(tarfile, "data_filter"):
                tar_file.extractall(target, filter="data")
            else:
                tar_file.extractall(target)


def remove(path):
    if path.is_dir():
        shutil.rmtree(path)
    elif path.exists():
        path.unlink()


if __name__ == "__main__":
    main()
