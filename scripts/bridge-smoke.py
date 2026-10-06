#!/usr/bin/env python3
"""Process-level protocol check for state, coordinate mapping and live-frame relay."""
import socket
import struct
import subprocess
import tempfile
import time
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]
CLASSES = ROOT / "bridge" / "build" / "classes" / "java" / "main"


def free_port():
    with socket.socket() as probe:
        probe.bind(("127.0.0.1", 0))
        return probe.getsockname()[1]


def recv_exact(sock, length):
    chunks = bytearray()
    while len(chunks) < length:
        part = sock.recv(length - len(chunks))
        if not part:
            raise EOFError("socket closed in the middle of a protocol record")
        chunks.extend(part)
    return bytes(chunks)


def float32(value):
    return struct.unpack(">f", struct.pack(">f", value))[0]


def state_connect(port, role):
    client = socket.create_connection(("127.0.0.1", port), timeout=3)
    client.settimeout(3)
    stream = client.makefile("rw", encoding="utf-8", newline="\n")
    stream.write("HELLO\t1\t" + role + "\n")
    stream.flush()
    welcome = stream.readline().rstrip("\n")
    settings = stream.readline().rstrip("\n")
    assert welcome.startswith("WELCOME\t1\t"), welcome
    assert settings == "SETTINGS\t96.0\t2.0", settings
    return client, stream


def state_connect_raw(port, role):
    client = socket.create_connection(("127.0.0.1", port), timeout=3)
    client.settimeout(3)
    stream = client.makefile("rw", encoding="utf-8", newline="\n")
    stream.write("HELLO\t1\t" + role + "\n")
    stream.flush()
    return client, stream.readline().rstrip("\n")


def state_connect_bad_version(port):
    client = socket.create_connection(("127.0.0.1", port), timeout=3)
    client.settimeout(3)
    stream = client.makefile("rw", encoding="utf-8", newline="\n")
    stream.write("HELLO\t2\tcities\n")
    stream.flush()
    return client, stream.readline().rstrip("\n")


def frame_connect(port, role, timeout=2):
    client = socket.create_connection(("127.0.0.1", port), timeout=timeout)
    client.settimeout(timeout)
    client.sendall(("CCFRAME/3\t" + role + "\n").encode("ascii"))
    response = bytearray()
    while not response.endswith(b"\n") and len(response) < 32:
        part = client.recv(1)
        if not part:
            client.close()
            raise ConnectionError("frame peer was not accepted")
        response.extend(part)
    if response != b"CCFRAME/3\tOK\n":
        client.close()
        raise ConnectionError("frame handshake rejected: " + repr(bytes(response)))
    return client


def send_frame(sock, sequence, timestamp, world, depth, hand, gui):
    header = struct.pack(">4siiqqiiiiiffdddffff", b"CCF3", 2, 2, sequence, timestamp,
                         len(world), len(depth), len(hand), len(gui), 1, 0.05, 256.0,
                         12.0, 65.0, -4.0, 0.0, -10.0, 70.0, 16.0 / 9.0)
    sock.sendall(header + world + depth + hand + gui)


def read_frame(sock):
    header = recv_exact(sock, 96)
    assert header[:4] == b"CCF3", header[:4]
    unpacked = struct.unpack(">iiqqiiiiiffdddffff", header[4:])
    width, height, sequence, timestamp = unpacked[:4]
    world_length, depth_length, hand_length, gui_length, encoding, near, far = unpacked[4:11]
    camera = unpacked[11:]
    world = recv_exact(sock, world_length)
    depth = recv_exact(sock, depth_length)
    hand = recv_exact(sock, hand_length)
    gui = recv_exact(sock, gui_length)
    return width, height, sequence, timestamp, encoding, near, far, camera, world, depth, hand, gui


def main():
    port = free_port()
    frame_port = free_port()
    while frame_port == port:
        frame_port = free_port()

    with tempfile.TemporaryDirectory(prefix="citiescraft-smoke-") as temp_dir:
        config_path = Path(temp_dir) / "citiescraft.properties"
        config_path.write_text(
            "world.scale=2\n"
            "world.yaw-offset-degrees=90\n"
            "world.minecraft-origin-x=10\n"
            "world.minecraft-origin-y=64\n"
            "world.minecraft-origin-z=-5\n"
            "world.cities-origin-x=100\n"
            "world.cities-origin-y=20\n"
            "world.cities-origin-z=200\n"
            "collision.radius-cities-units=96\n",
            encoding="utf-8",
        )
        proc = subprocess.Popen(
            [
                "java",
                "-Dcitiescraft.port=" + str(port),
                "-Dcitiescraft.framePort=" + str(frame_port),
                "-Dcitiescraft.config=" + str(config_path),
                "-cp",
                str(CLASSES),
                "dev.citiescraft.bridge.BridgeServer",
            ],
            stdout=subprocess.DEVNULL,
            stderr=subprocess.PIPE,
            text=True,
        )
        peers = []
        try:
            deadline = time.time() + 5
            while True:
                try:
                    probe = socket.create_connection(("127.0.0.1", port), timeout=0.2)
                    probe.close()
                    break
                except OSError:
                    if proc.poll() is not None:
                        raise RuntimeError(proc.stderr.read())
                    if time.time() > deadline:
                        raise RuntimeError("Bridge did not start")
                    time.sleep(0.05)

            mc_socket, mc = state_connect(port, "minecraft")
            city_socket, city = state_connect(port, "cities")
            peers.extend([mc_socket, city_socket])

            mc.write("PLAYER\t1\t12\t65\t-4\t0\t-10\n")
            mc.flush()
            player = city.readline().rstrip("\n").split("\t")
            assert player[0:2] == ["PLAYER", "1"], player
            assert [float(value) for value in player[2:]] == [102.0, 22.0, 196.0, 90.0, -10.0], player

            mc.write("VIEW\t1\t12\t65\t-4\t0\t-10\t70\t1.777778\n")
            mc.flush()
            view = city.readline().rstrip("\n").split("\t")
            assert view[:2] == ["VIEW", "1"], view
            assert [float(value) for value in view[2:]] == [102.0, 22.0, 196.0, 90.0, -10.0, 70.0, 1.777778], view

            city.write("INPUT\t1\tKEY\t87\t0\t1\t0\n")
            city.flush()
            assert mc.readline().rstrip("\n") == "INPUT\t1\tKEY\t87\t0\t1\t0"

            city.write("CAMERA\t1\t102\t22\t196\t90\t-10\t70\n")
            city.flush()
            camera = mc.readline().rstrip("\n").split("\t")
            assert camera[0:2] == ["CAMERA", "1"], camera
            assert [float(value) for value in camera[2:]] == [12.0, 65.0, -4.0, 0.0, -10.0, 70.0], camera

            heights = ["20"] * 9
            box = "7,101,20,201,105,28,205"
            snapshot = "\t".join(["SNAPSHOT", "1", "100", "200", "2", "3", "3"] + heights + ["1", box])
            city.write(snapshot + "\n")
            city.flush()
            world = mc.readline().rstrip("\n").split("\t")
            assert world[:2] == ["CITYWORLD", "0"], world[:2]
            assert len(world) == 12, len(world)
            terrain_center = [float(value) if index else value for index, value in enumerate(world[6].split(","))]
            assert terrain_center == ["T", 9.5, -64.0, -5.5, 10.5, 64.0, -4.5], terrain_center
            building = [float(value) if index else value for index, value in enumerate(world[11].split(","))]
            assert building == ["B", 7.5, 64.0, -4.5, 9.5, 68.0, -2.5], building

            duplicate, response = state_connect_raw(port, "minecraft")
            peers.append(duplicate)
            assert response.startswith("ERROR\tduplicate\t"), response
            duplicate.close()

            mc.write("PLAYER\t2\tNaN\t65\t-4\t0\t-10\n")
            mc.flush()
            assert mc.readline().startswith("ERROR\tvalue\t"), "non-finite player state was not rejected"

            source = frame_connect(frame_port, "minecraft")
            consumer = frame_connect(frame_port, "cities")
            peers.extend([source, consumer])
            first_world = bytes(range(16))
            first_depth = struct.pack(">ffff", 1.0, 2.0, float("inf"), 64.0)
            first_hand = bytes(reversed(range(16)))
            first_gui = bytes([0, 0, 0, 0] * 4)
            send_frame(source, 8, 1234, first_world, first_depth, first_hand, first_gui)
            first = read_frame(consumer)
            assert first[:7] == (2, 2, 0, 1234, 1, float32(0.05), 256.0), first
            assert first[7] == (102.0, 22.0, 196.0, 90.0, -10.0, 70.0, float32(16.0 / 9.0)), first
            assert first[8:] == (first_world, first_depth, first_hand, first_gui), first

            source.close()
            peers.remove(source)
            second_source = None
            deadline = time.time() + 5
            while second_source is None:
                try:
                    second_source = frame_connect(frame_port, "minecraft", timeout=0.3)
                except (ConnectionError, OSError, TimeoutError):
                    if time.time() > deadline:
                        raise RuntimeError("Minecraft frame peer did not reconnect")
                    time.sleep(0.1)
            peers.append(second_source)
            second_world = bytes(reversed(range(16)))
            second_depth = struct.pack(">ffff", 4.0, 8.0, 16.0, 32.0)
            second_hand = bytes([0, 0, 0, 0] * 4)
            second_gui = bytes([255, 255, 255, 255] * 4)
            send_frame(second_source, 0, 5678, second_world, second_depth, second_hand, second_gui)
            second = read_frame(consumer)
            assert second[:7] == (2, 2, 1, 5678, 1, float32(0.05), 256.0), second
            assert second[7] == (102.0, 22.0, 196.0, 90.0, -10.0, 70.0, float32(16.0 / 9.0)), second
            assert second[8:] == (second_world, second_depth, second_hand, second_gui), second

            bad_version, response = state_connect_bad_version(port)
            peers.append(bad_version)
            assert response.startswith("ERROR\thello\t"), response
            print("Bridge smoke passed: view/camera mapping, remote input, city geometry, CCF3 frame pose/depth layers and reconnect")
        finally:
            for peer in peers:
                try:
                    peer.close()
                except OSError:
                    pass
            proc.terminate()
            try:
                proc.wait(timeout=3)
            except subprocess.TimeoutExpired:
                proc.kill()
                proc.wait(timeout=3)


if __name__ == "__main__":
    main()
