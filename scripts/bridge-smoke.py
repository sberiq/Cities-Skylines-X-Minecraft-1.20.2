#!/usr/bin/env python3
"""Process-level smoke check for the Bridge protocol and role routing."""
import socket
import subprocess
import time
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]
PORT = 25618
CLASSES = ROOT / "bridge" / "build" / "smoke-classes"


def connect(role):
    client = socket.create_connection(("127.0.0.1", PORT), timeout=2)
    client.settimeout(2)
    stream = client.makefile("rw", encoding="utf-8", newline="\n")
    stream.write("HELLO\t1\t" + role + "\n")
    stream.flush()
    welcome = stream.readline().rstrip("\n")
    assert welcome.startswith("WELCOME\t1\t"), welcome
    return client, stream


def main():
    proc = subprocess.Popen(
        ["java", "-Dcitiescraft.port=" + str(PORT), "-cp", str(CLASSES), "dev.citiescraft.bridge.BridgeServer"],
        stdout=subprocess.DEVNULL,
        stderr=subprocess.PIPE,
        text=True,
    )
    minecraft = cities = None
    try:
        deadline = time.time() + 5
        while True:
            try:
                probe = socket.create_connection(("127.0.0.1", PORT), timeout=0.2)
                probe.close()
                break
            except OSError:
                if proc.poll() is not None:
                    raise RuntimeError(proc.stderr.read())
                if time.time() > deadline:
                    raise RuntimeError("Bridge did not start")
                time.sleep(0.05)

        minecraft = connect("minecraft")
        cities = connect("cities")
        mc_socket, mc = minecraft
        city_socket, city = cities

        mc.write("PLAYER\t1\t12.5\t64\t-8\t90\t-10\n")
        mc.flush()
        assert city.readline().rstrip("\n") == "PLAYER\t1\t12.5\t64\t-8\t90\t-10"

        city.write("CAMERA\t1\t100\t200\t300\t45\t-20\t60\n")
        city.flush()
        assert mc.readline().rstrip("\n") == "CAMERA\t1\t100\t200\t300\t45\t-20\t60"

        duplicate, duplicate_stream = connect_raw("minecraft")
        assert duplicate_stream.startswith("ERROR\tduplicate\t"), duplicate_stream
        duplicate.close()

        mc.write("PLAYER\t2\tNaN\t64\t-8\t90\t-10\n")
        mc.flush()
        assert mc.readline().startswith("ERROR\tvalue\t"), "non-finite player state was not rejected"

        replacement_socket, replacement = connect("minecraft")
        assert replacement.readline().rstrip("\n").startswith("CAMERA\t1\t"), "latest camera state was not replayed on reconnect"
        replacement.write("PLAYER\t1\t13\t64\t-8\t90\t-10\n")
        replacement.flush()
        assert city.readline().rstrip("\n") == "PLAYER\t1\t13\t64\t-8\t90\t-10"

        bad_version, bad_response = connect_bad_version()
        assert bad_response.startswith("ERROR\thello\t"), bad_response
        bad_version.close()
        print("Bridge smoke passed: bidirectional routing, reconnect replay, version/role/value validation")
    finally:
        for pair in (minecraft, cities):
            if pair:
                pair[0].close()
        if "replacement_socket" in locals():
            replacement_socket.close()
        proc.terminate()
        try:
            proc.wait(timeout=3)
        except subprocess.TimeoutExpired:
            proc.kill()
            proc.wait(timeout=3)


def connect_raw(role):
    client = socket.create_connection(("127.0.0.1", PORT), timeout=2)
    client.settimeout(2)
    stream = client.makefile("rw", encoding="utf-8", newline="\n")
    stream.write("HELLO\t1\t" + role + "\n")
    stream.flush()
    response = stream.readline().rstrip("\n")
    return client, response


def connect_bad_version():
    client = socket.create_connection(("127.0.0.1", PORT), timeout=2)
    client.settimeout(2)
    stream = client.makefile("rw", encoding="utf-8", newline="\n")
    stream.write("HELLO\t2\tcities\n")
    stream.flush()
    response = stream.readline().rstrip("\n")
    return client, response


if __name__ == "__main__":
    main()
